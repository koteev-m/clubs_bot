#!/usr/bin/env python3
"""Synthetic tests only: no SSH, live Docker, user data or workflow dispatch."""
import copy
import contextlib
import io
import importlib.util
import json
import os
from pathlib import Path
import signal
import shutil
import subprocess
import sys
import tempfile
import time
import threading
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]

def load(name, relative):
    spec = importlib.util.spec_from_file_location(name, ROOT / relative)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value

C = load('snapshot_collector', 'scripts/deploy/stage-readonly-snapshot-collector.py')
R = load('snapshot_runner', 'scripts/deploy/stage-readonly-snapshot.py')
L = load('snapshot_lock', 'scripts/deploy/shared_staging_flock.py')


def container(value, service='app', project=C.PROJECT):
    return {'id': value * 64, 'image': 'sha256:' + value * 64,
        'status': 'running', 'running': True, 'health': 'healthy', 'project': project,
        'service': service, 'root': C.ROOT, 'revision': None,
        'networks': {'synthetic-private-network': {}},
        'ports': {'8080/tcp': [{'HostIp': '127.0.0.1', 'HostPort': '8081'}]},
        'volumes': ['synthetic-private-volume']}


def database():
    return {'outcome': 'observed', 'catalog_validation': 'locked_heap_table', 'readonly': 'on', 'role_safe': True, 'history': [
        {'rank': v, 'version': str(v).zfill(3), 'checksum': v, 'success': True}
        for v in range(1, 77)], 'triggers': None}


class FakeCommands:
    def __init__(self):
        self.inventory = [container('a'), container('b', 'db')]
        self.db = database()
        self.second = None
        self.calls = []

    def containers(self):
        self.calls.append('containers')
        return copy.deepcopy(self.second if self.second is not None and self.calls.count('containers') > 1 else self.inventory)

    def image(self, value):
        return {'id': value, 'revision': '5' * 40, 'digests': [C.IMAGE + '@sha256:' + '6' * 64]}

    def health(self, endpoint, port=8081):
        return 200

    def database(self, value):
        return self.db


class FakeFiles:
    def observe(self):
        raise OSError('sensitive private path not exported')


class SnapshotTests(unittest.TestCase):
    def result(self, commands=None):
        return C.observe(commands or FakeCommands(), FakeFiles())

    def test_success_is_metadata_only_never_stage_admission(self):
        value = self.result()
        self.assertEqual(value['clubs']['health_http'], 200)
        self.assertEqual(value['database']['pending_target'], [77, 78, 79])
        self.assertFalse(value['stage_ready'])
        self.assertEqual(value['release']['backup_recovery'], 'unknown')
        self.assertEqual(value['neighbor']['identity_authority'], 'unknown')
        raw = json.dumps(value).encode() + b'\n'
        self.assertEqual(json.loads(R.sanitize(raw)), value)
        for secret in ('synthetic-private-network', 'synthetic-private-volume', C.ROOT, 'a' * 64, 'b' * 64):
            self.assertNotIn(secret, raw.decode())

    def test_absent_or_extra_app_db_or_wrong_root_is_unknown(self):
        for mutate in (lambda c: c.inventory.pop(), lambda c: c.inventory.append(container('c', 'app')),
                       lambda c: c.inventory[0].update(root='/foreign'),
                       lambda c: c.inventory[0].update(project='foreign')):
            with self.subTest(mutate=mutate):
                c = FakeCommands()
                mutate(c)
                self.assertEqual(self.result(c)['clubs']['availability'], 'unknown')

    def test_image_mismatch_and_port_substitution_fail_closed(self):
        c = FakeCommands()
        c.image = lambda _v: {'id': 'sha256:' + 'c' * 64, 'revision': '5' * 40, 'digests': []}
        self.assertEqual(self.result(c)['clubs']['availability'], 'unknown')
        c = FakeCommands()
        c.inventory[0]['ports'] = {'8080/tcp': [{'HostIp': '0.0.0.0', 'HostPort': '8081'}]}
        self.assertEqual(self.result(c)['clubs']['availability'], 'unknown')

    def test_changed_inventory_invalidates_earlier_results(self):
        c = FakeCommands()
        c.second = copy.deepcopy(c.inventory)
        c.second[0]['id'] = 'c' * 64
        value = self.result(c)
        self.assertEqual(value['clubs']['availability'], 'unknown')
        self.assertEqual(value['database']['availability'], 'unknown')

    def test_failed_flyway_not_reported_as_applied(self):
        d = database()
        d['history'].append({'rank': 77, 'version': '077', 'checksum': -1, 'success': False})
        value = C.summarize_database(d)
        self.assertTrue(value['failed_history'])
        self.assertEqual(value['pending_target'], [77, 78, 79])

    def test_database_readonly_and_role_guard(self):
        for updates in ({'readonly': 'off'}, {'role_safe': False}, {'history': []}, {'history': None}):
            with self.subTest(updates=updates):
                d = database()
                d.update(updates)
                with self.assertRaises(C.Unavailable):
                    C.summarize_database(d)

    def test_flyway_no_private_description_script_or_installed_by(self):
        d = database()
        d['history'][0]['installed_by'] = 'private identity'
        with self.assertRaises(C.Unavailable):
            C.summarize_database(d)
        d = database()
        d['history'][0]['version'] = 'token:12345'
        with self.assertRaises(C.Unavailable):
            C.summarize_database(d)

    def test_db_failure_is_unknown_without_losing_clubs(self):
        c = FakeCommands()
        c.database = lambda _v: (_ for _ in ()).throw(OSError('password=secret'))
        value = self.result(c)
        self.assertEqual(value['database']['availability'], 'unknown')
        self.assertEqual(value['clubs']['availability'], 'observed')
        self.assertNotIn('password', json.dumps(value))

    def test_cancellation_is_not_swallowed(self):
        for point in ('containers', 'database', 'health'):
            c = FakeCommands()
            setattr(c, point, lambda *args: (_ for _ in ()).throw(C.Cancelled()))
            with self.assertRaises(C.Cancelled):
                self.result(c)

    def test_command_allowlist_no_shell_no_mutators(self):
        c = C.Commands()
        calls = []
        def run(args, data=b''):
            calls.append((args, data))
            if args[1] == 'ps': return b''
            if args[1] == 'image': return b'{}'
            if args[0] == C.CURL: return b'200'
            if args[1] == 'exec':
                # database_wire() is tested independently with real PG output.
                return b'{"outcome":"not_found"}\n'
            raise AssertionError(args)
        c.run = run
        c.containers()
        c.image('sha256:' + 'a' * 64)
        c.health('health')
        c.health('ready')
        c.database('a' * 64)
        self.assertEqual(len(calls), 5)
        forbidden = {'start', 'stop', 'restart', 'run', 'rm', 'prune', 'pull', 'up', 'down', 'backup', 'restore'}
        for args, _ in calls:
            self.assertFalse(forbidden.intersection(args))
        sql = calls[-1][1].decode()
        self.assertIn('READ ONLY', sql)
        self.assertIn('SET LOCAL search_path = pg_catalog', sql)
        self.assertIn('ROLLBACK;', sql)
        self.assertIn('-w', calls[-1][0])
        self.assertIn('LOCK TABLE ONLY public.flyway_schema_history IN ACCESS SHARE MODE', sql)
        self.assertIn('\\gset locked_', sql)
        # The privilege-name literal is data; executable CREATE remains forbidden.
        self.assertNotRegex(sql.replace("'CREATE'", "''"), r'(?i)\b(?:INSERT|UPDATE|DELETE|TRUNCATE|ALTER|CREATE|DROP|CALL|DO)\b')
        self.assertIn('COPY public.flyway_schema_history(installed_rank,version,checksum,success) TO STDOUT WITH (FORMAT binary)',sql)
        self.assertEqual(sql.count('COPY public.'),1)
        self.assertNotRegex(sql,r'(?is)\bCOPY\s+public\.[^;]*\bFROM\b')
        self.assertNotIn('PROGRAM',sql.split('COPY public.')[1])
        for field in ('.Config.Env', '.Config.Cmd', '.State.Health.Log', '$m.Source'):
            self.assertNotIn(field, C.FORMAT)
        for name in ('bookings', 'users', 'client_club_grants', 'target_role_assignments'):
            self.assertNotIn(name, sql)

    def test_subprocess_timeout_and_output_bounds_kill_and_reap(self):
        c = C.Commands()
        c.deadline = time.monotonic() + 0.15
        start = time.monotonic()
        with self.assertRaises(C.Unavailable):
            c.run([sys.executable, '-c', 'import time;time.sleep(10)'])
        self.assertLess(time.monotonic() - start, 2)
        with self.assertRaises(C.Unavailable):
            C.Commands().run([sys.executable, '-c', 'import sys;sys.stderr.write("x"*60000)'])

    def test_no_target_authority_fields(self):
        raw = json.dumps(self.result())
        for key in ('permissions', 'grants', 'authorized', 'admission', 'initData', 'credentials'):
            self.assertNotIn('"' + key + '"', raw)

    def test_unknown_triggers_are_not_exported(self):
        d = database()
        d['triggers'] = [{'name': 'private', 'table': 'bookings', 'enabled': 'O'}]
        with self.assertRaises(C.Unavailable):
            C.summarize_database(d)

    def test_neighbor_http_unavailable_does_not_invalidate_clubs(self):
        c = FakeCommands()
        def health(endpoint, port=8081):
            if port == 8080: raise C.Unavailable()
            return 200
        c.health = health
        value = self.result(c)
        self.assertEqual(value['clubs']['availability'], 'observed')
        self.assertNotIn('loopback_8080_health_http', value['neighbor'])
        self.assertEqual(value['neighbor']['identity_authority'], 'unknown')

    def test_neighbor_shared_resource_is_only_observation(self):
        c = FakeCommands()
        other = container('c', 'backend', 'unverified-other-project')
        other['ports']['8080/tcp'][0]['HostPort'] = '8080'
        c.inventory.append(other)
        value = self.result(c)['neighbor']
        self.assertTrue(value['shared_named_volume_or_network_observed'])
        self.assertEqual(value['isolation'], 'unknown')
        self.assertEqual(len(value['candidate_container_identity_sha256']), 1)


class RunnerTests(unittest.TestCase):
    def env(self):
        return {'GITHUB_REPOSITORY': 'koteev-m/clubs_bot', 'GITHUB_EVENT_NAME': 'workflow_dispatch',
            'GITHUB_REF': 'refs/heads/main', 'GITHUB_REF_TYPE': 'branch', 'REPOSITORY_DEFAULT_BRANCH': 'main',
            'GITHUB_RUN_ATTEMPT': '1', 'GITHUB_SHA': 'a' * 40,
            'GITHUB_WORKFLOW_REF': 'koteev-m/clubs_bot/' + R.WORKFLOW + '@refs/heads/main',
            'APP_ENV': 'stage', 'CONFIRMATION': R.CONFIRMATION, 'COMPOSE_PATH': C.ROOT,
            'SSH_PORT': '22', 'SSH_USER': 'synthetic', 'SSH_HOST': 'synthetic.invalid'}

    def test_fixed_stage_main_first_attempt_only(self):
        R.validate(self.env())
        for key, value in (('APP_ENV', 'prod'), ('GITHUB_REF', 'refs/heads/feature'),
                           ('GITHUB_RUN_ATTEMPT', '2'), ('GITHUB_REPOSITORY', 'other/repo'),
                           ('CONFIRMATION', ';command'), ('COMPOSE_PATH', '/other'),
                           ('GITHUB_EVENT_NAME', 'pull_request')):
            env = self.env()
            env[key] = value
            with self.subTest(key=key), self.assertRaises(R.Refused):
                R.validate(env)

    def test_strict_ssh_options_and_fixed_remote_command(self):
        args = R.ssh_args(self.env(), 18)
        for value in ('StrictHostKeyChecking=yes', 'UserKnownHostsFile=/proc/self/fd/18',
                      'GlobalKnownHostsFile=/dev/null', 'ProxyCommand=none', 'ProxyJump=none',
                      'ForwardAgent=no', 'PermitLocalCommand=no', 'ClearAllForwardings=yes'):
            self.assertIn(value, args)
        self.assertEqual(args[-1], '/usr/bin/python3 -I -S -B -')
        self.assertNotIn('bash', args)

    def test_only_captured_collector_and_lock_source_in_payload(self):
        raw = R.payload({R.LOCK: b'fixed_lock=1', R.COLLECTOR: b'fixed_collector=1', R.PROCESS: b'fixed_process=1'})
        self.assertNotIn(b'SSH_HOST', raw)
        self.assertNotIn(b'COMPOSE_PATH', raw)
        self.assertIn(b'collector.main(lock.HostLock)', raw)

    def test_no_output_leakage_unknown_keys_strings_duplicates_or_multiline(self):
        base = {'protocol': 'clb-stage-snapshot-v1', 'environment': 'stage',
                'result': 'unavailable', 'stage_ready': False}
        for updates in ({'error': 'secret'}, {'result': 'password=secret'}, {'stage_ready': True},
                        {'environment': 'prod'}, {'clubs': {'status': '/private/path'}}):
            with self.subTest(updates=updates), self.assertRaises(R.Refused):
                R.sanitize(json.dumps(dict(base, **updates)).encode() + b'\n')
        observed = SnapshotTests().result()
        for key, invalid in (('status', 17), ('health_http', True), ('running', 'running')):
            altered = copy.deepcopy(observed)
            altered['clubs'][key] = invalid
            with self.subTest(key=key), self.assertRaises(R.Refused):
                R.sanitize(json.dumps(altered).encode() + b'\n')
        for raw in (b'{"protocol":"clb-stage-snapshot-v1","protocol":"duplicate"}\n',
                    json.dumps(base).encode() + b'\nextra\n', b'x' * 60000):
            with self.assertRaises((R.Refused, ValueError)):
                R.sanitize(raw)

    def test_transport_error_never_prints_raw_stderr(self):
        with self.assertRaises(R.Refused) as error:
            R.capture([sys.executable, '-c', 'import sys;sys.stderr.write("PRIVATE");sys.exit(2)'])
        self.assertNotIn('PRIVATE', str(error.exception))

    def test_transport_timeout_and_bounded_stream(self):
        with self.assertRaises(R.Refused):
            R.capture([sys.executable, '-c', 'import time;time.sleep(10)'], timeout=.1)
        with self.assertRaises(R.Refused):
            R.capture([sys.executable, '-c', 'print("x"*60000)'])

    def test_cancellation_reaps_local_process(self):
        def interrupted(_sig, _frame): raise R.Cancelled()
        old = signal.signal(signal.SIGALRM, interrupted)
        signal.setitimer(signal.ITIMER_REAL, .1)
        try:
            with self.assertRaises(R.Cancelled):
                R.capture([sys.executable, '-c', 'import time;time.sleep(10)'])
        finally:
            signal.setitimer(signal.ITIMER_REAL, 0)
            signal.signal(signal.SIGALRM, old)

    def test_source_closure_missing_blob_is_refused(self):
        env = self.env()
        def git(args, **kwargs):
            return (env['GITHUB_SHA'] + '\n').encode() if 'rev-parse' in args else b''
        with patch.object(R, 'capture', side_effect=git), self.assertRaises(R.Refused):
            R.sources(env)


@unittest.skipUnless(shutil.which('ruby'), 'Ruby validator runs separately on host/CI')
class WorkflowTests(unittest.TestCase):
    def validate(self, replacement=None):
        code = """require 'validate-workflow-capabilities'
raw=File.read('.github/workflows/stage-readonly-snapshot.yml')
raw=raw.sub(ARGV[0],ARGV[1]) if ARGV.length==2
workflow=WorkflowYamlSafety.safe_load_workflow(raw,StageReadonlySnapshotWorkflow::PATH)
StageReadonlySnapshotWorkflow.validate(WorkflowCapabilityPolicy,workflow)
"""
        args = ['ruby', '-Iscripts', '-e', code]
        if replacement: args.extend(replacement)
        return subprocess.run(args, cwd=ROOT, capture_output=True, timeout=5)

    def test_exact_workflow_accepts(self):
        self.assertEqual(self.validate().returncode, 0)

    def test_prod_arbitrary_input_permissions_unpinned_actions_and_no_gate_rejected(self):
        changes = [('environment: stage','environment: prod'), ('contents: read','contents: write'),
            ('cancel-in-progress: false','cancel-in-progress: true'),
            ('    environment: stage','    environment: unprotected'),
            ('type: string','type: choice'),
            ('692973e3d937129bcbf40652eb9f2f61becf3332','v4'),
            ('stage-readonly-snapshot.py --validate','stage-readonly-snapshot.py'),
            ('confirmation:', 'command:')]
        for change in changes:
            with self.subTest(change=change):
                self.assertNotEqual(self.validate(change).returncode, 0)


class FixRegressionTests(unittest.TestCase):
    def reject(self, value):
        with self.assertRaises((R.Refused, TypeError, ValueError)):
            R.sanitize(json.dumps(value).encode()+b'\n')

    def test_required_sections_and_fields_cannot_be_empty_or_removed(self):
        base = SnapshotTests().result()
        for section in ('clubs','database','release','neighbor'):
            for replacement in ({}, None, [], 'unknown'):
                with self.subTest(section=section,replacement=replacement):
                    value=copy.deepcopy(base);value[section]=replacement;self.reject(value)
            for field in base[section]:
                value=copy.deepcopy(base);del value[section][field]
                if section=='neighbor' and field=='loopback_8080_health_http':continue
                with self.subTest(section=section,field=field):self.reject(value)

    def test_all_unknown_boundaries_cannot_be_promoted(self):
        for section,fields in (('release',('backup_recovery','fsm_authority','migration_ledger_integrity','unfinished_release')),
                               ('neighbor',('identity_authority','isolation'))):
            for field in fields:
                for state in ('observed','pass',True):
                    value=SnapshotTests().result();value[section][field]=state
                    with self.subTest(section=section,field=field,state=state):self.reject(value)

    def test_http_ranges_exact_types_and_provenance(self):
        for section,key in (('clubs','health_http'),('clubs','ready_http'),('neighbor','loopback_8080_health_http')):
            for invalid in (-99,0,99,600,True,200.0,'200'):
                value=SnapshotTests().result();value[section][key]=invalid
                with self.subTest(section=section,key=key,invalid=invalid):self.reject(value)
        for section,key,bad in (('clubs','target_revision_matches',True),('clubs','target_digest_matches',True),
            ('clubs','running',False),('database','latest_version',79),('database','pending_target',[]),
            ('database','failed_history',True),('database','catalog_validation','unknown'),
            ('database','inflight_migration','observed'),('neighbor','observed_other_containers',-1)):
            value=SnapshotTests().result();value[section][key]=bad
            with self.subTest(section=section,key=key):self.reject(value)

    def binary_frame(self, version=None, checksum=None):
        header=database();header.pop('history')
        wire=json.dumps(header).encode()+b'\n'+b'PGCOPY\n\xff\r\n\x00'+C.struct.pack('!ii',0,0)
        wire+=C.struct.pack('!h',4)
        for field in (C.struct.pack('!i',1),None if version is None else version.encode(),None if checksum is None else C.struct.pack('!i',checksum),b'\x00'):
            wire+=C.struct.pack('!i',-1 if field is None else len(field))+(field or b'')
        return wire+C.struct.pack('!h',-1)

    def test_binary_copy_nulls_and_types_are_exact(self):
        row=C.database_wire(self.binary_frame())['history'][0]
        self.assertEqual(row,{'rank':1,'version':None,'checksum':None,'success':False})
        self.assertTrue(C.summarize_database(C.database_wire(self.binary_frame('076',-17)))['failed_history'])
        with self.assertRaises(C.Unavailable):C.summarize_database(C.database_wire(self.binary_frame('private\nvalue')))

    def test_binary_copy_wire_truncation_bounds_extra_bytes_and_fields_refuse(self):
        wire=self.binary_frame('076',17)
        prefix=wire.index(b'PGCOPY')
        for bad in (wire[:-1],wire+b'extra',wire[:prefix]+b'invalid',wire[:prefix+19]+C.struct.pack('!h',5)+wire[prefix+21:],b'x'*50000):
            with self.subTest(length=len(bad)),self.assertRaises(C.Unavailable):C.database_wire(bad)
        for frame in (b'{"outcome":"not_found"}\ntrailing',b'{"outcome":"unknown"}\nextra'):
            with self.assertRaises(C.Unavailable):C.database_wire(frame)

    def test_not_found_requires_canonical_absence_and_unknown_has_no_evidence(self):
        value=SnapshotTests().result()
        for data in ({'outcome':'not_found'},{'outcome':'unknown'}):
            value['database']=C.summarize_database(data)
            R.sanitize(json.dumps(value).encode()+b'\n')
        value['database']={'availability':'not_found'};self.reject(value)
        value['database']={'availability':'unknown','history':[]};self.reject(value)

    def test_extra_privileged_fields_unknown_trigger_pair_and_duplicate_rows_rejected(self):
        for section in ('clubs','database','release','neighbor'):
            value=SnapshotTests().result();value[section]['permissions']=['GLOBAL'];self.reject(value)
        value=SnapshotTests().result();value['database']['history'].append(value['database']['history'][0]);self.reject(value)
        value=SnapshotTests().result();value['database']['relevant_triggers']=[{'name':'target_control_guard','table':'clients','enabled':'O'}];self.reject(value)
        value=SnapshotTests().result();value['release']={'artifact_observation':'observed','active_state':'none_observed','checkpoint':'migration_started',
            'anchor_present':False,'retained_ledgers':0,'outcome_files':0,'fsm_authority':'unknown',
            'migration_ledger_integrity':'unknown','unfinished_release':'unknown','backup_recovery':'unknown'};self.reject(value)

    @unittest.skipUnless(shutil.which('ssh'), 'host ssh -G gate')
    def test_ssh_effective_policy_never_uses_home_keys_or_certificates(self):
        with tempfile.TemporaryDirectory() as tmp:
            home=Path(tmp);(home/'.ssh').mkdir()
            for name in ('id_rsa','id_ed25519','id_rsa-cert.pub','id_ed25519-cert.pub'):
                (home/'.ssh'/name).write_text('synthetic unusable fixture')
            (home/'.ssh/config').write_text('Host *\n IdentityFile '+str(home/'.ssh/id_rsa')+'\n CertificateFile '+str(home/'.ssh/id_rsa-cert.pub')+'\n ProxyCommand false\n')
            env=RunnerTests().env();args=R.ssh_args(env,18)
            result=subprocess.run([args[0],'-G',*args[1:]],env={'PATH':os.defpath,'HOME':str(home)},capture_output=True,text=True,timeout=5)
            self.assertEqual(result.returncode,0)
            policy={}
            for line in result.stdout.splitlines():
                key,_,v=line.partition(' ');policy.setdefault(key,[]).append(v)
            self.assertEqual(policy['identityfile'],['none'])
            self.assertEqual(policy['certificatefile'],['none'])
            for k in ('passwordauthentication','kbdinteractiveauthentication','hostbasedauthentication','gssapiauthentication'):
                self.assertEqual(policy[k],['no'])
            self.assertEqual(policy['preferredauthentications'],['publickey'])
            self.assertIn(policy['stricthostkeychecking'],(['true'],['yes']))
            self.assertEqual(policy['identitiesonly'],['no'])  # protected agent keys remain eligible
            self.assertNotIn(str(home),result.stdout)

    def test_snapshot_concurrency_is_disjoint_from_release_workflows(self):
        raw=(ROOT/'.github/workflows/stage-readonly-snapshot.yml').read_text()
        self.assertIn('group: clb-stage-readonly-snapshot',raw)
        self.assertNotIn('payments-schema-stage',raw)
        for path in ('deploy-ssh.yml','db-migrate.yml'):
            self.assertIn('payments-schema-',(ROOT/'.github/workflows'/path).read_text())

    @unittest.skipUnless(hasattr(os,'fork'),'POSIX owned descendant probes')
    def test_early_leader_exit_timeout_kills_stdout_and_stderr_holders(self):
        self.descendants(cancel=False)

    @unittest.skipUnless(hasattr(os,'fork'),'POSIX owned descendant probes')
    def test_sigterm_cancellation_kills_descendants_after_leader_exit(self):
        self.descendants(cancel=True)

    def descendants(self,cancel):
        for kind in ('runner','collector'):
            for held in ('stdout','stderr'):
                with self.subTest(kind=kind,held=held,cancel=cancel),tempfile.TemporaryDirectory() as tmp:
                    marker=Path(tmp)/'owned-child'
                    code="import os,time,signal;p=os.fork();\nif p:os._exit(0)\nsignal.signal(signal.SIGTERM,signal.SIG_IGN)\nos.close("+('2' if held=='stdout' else '1')+")\nopen("+repr(str(marker))+",'w').write(str(os.getpid()))\ntime.sleep(10)"
                    def interrupted(_sig,_frame):raise R.Cancelled()
                    old=signal.signal(signal.SIGTERM,interrupted)
                    stop=threading.Event()
                    def cancel_after_ready():
                        deadline=time.monotonic()+3
                        while not stop.is_set() and not marker.exists() and time.monotonic()<deadline:
                            stop.wait(.01)
                        if not stop.is_set():os.kill(os.getpid(),signal.SIGTERM)
                    timer=threading.Thread(target=cancel_after_ready) if cancel else None
                    if timer:timer.start()
                    try:
                        if cancel:
                            with self.assertRaises(R.Cancelled):
                                if kind=='runner':R.capture([sys.executable,'-c',code])
                                else:C.Commands().run([sys.executable,'-c',code])
                        else:
                            with self.assertRaises(R.Refused if kind=='runner' else C.Unavailable):
                                if kind=='runner':R.capture([sys.executable,'-c',code],timeout=1.0)
                                else:
                                    commands=C.Commands();commands.deadline=time.monotonic()+1.0;commands.run([sys.executable,'-c',code])
                    finally:
                        stop.set()
                        if timer:timer.join(timeout=1)
                        self.assertFalse(timer and timer.is_alive())
                        signal.signal(signal.SIGTERM,old)
                    self.assertTrue(marker.exists())
                    pid=int(marker.read_text());deadline=time.monotonic()+2
                    while time.monotonic()<deadline:
                        try:os.kill(pid,0)
                        except ProcessLookupError:break
                        time.sleep(.02)
                    else:
                        self.fail('owned descendant survived cleanup')

    def test_group_cleanup_is_idempotent_and_never_signals_after_reap(self):
        process=R.process_module()
        group=process.OwnedGroup([sys.executable,'-c','import time;time.sleep(10)'])
        calls=[];original=group._signal
        def owned(sig):
            self.assertIsNone(group._process.returncode);calls.append(sig);return original(sig)
        group._signal=owned
        group.cleanup();group.cleanup()
        self.assertEqual(calls,[signal.SIGTERM,signal.SIGKILL])
        self.assertIsNotNone(group._process.returncode)

    def cleanup_interruption(self, phase, real_signal=False):
        process=R.process_module()
        code='import signal,time;signal.signal(signal.SIGTERM,signal.SIG_IGN);print("READY",flush=True);time.sleep(15)'
        group=process.OwnedGroup([sys.executable,'-c',code])
        control=subprocess.Popen([sys.executable,'-c','import time;time.sleep(15)'],start_new_session=True)
        sent=threading.Event();ready=threading.Event();sender=None
        old=signal.getsignal(signal.SIGTERM)
        calls=[];original_signal=group._signal;original_wait=group._process.wait
        def interrupted(*_):raise R.Cancelled()
        def send():
            signal.pthread_sigmask(signal.SIG_UNBLOCK,{signal.SIGTERM})
            if ready.wait(2):
                os.kill(os.getpid(),signal.SIGTERM)
            sent.set()
        def observed_signal(sig):
            self.assertIsNone(group._process.returncode)
            calls.append(sig)
            result=original_signal(sig)
            if phase=='term' and sig==signal.SIGTERM:
                if real_signal:
                    ready.set()
                    self.assertTrue(sent.wait(2))
                else:
                    raise R.Cancelled()
            return result
        def interrupted_wait(*args,**kwargs):
            result=original_wait(*args,**kwargs)
            if phase=='reap':raise R.Cancelled()
            return result
        try:
            deadline=time.monotonic()+3
            while time.monotonic()<deadline:
                try:
                    if os.read(group._process.stdout.fileno(),64)==b'READY\n':break
                except BlockingIOError:pass
                time.sleep(.01)
            else:self.fail('owned fixture did not become ready')
            if real_signal:
                signal.signal(signal.SIGTERM,interrupted)
                sender=threading.Thread(target=send);sender.start()
            started=time.monotonic()
            with patch.object(group,'_signal',side_effect=observed_signal),patch.object(group._process,'wait',side_effect=interrupted_wait):
                with self.assertRaises(R.Cancelled):group.cleanup()
            self.assertFalse(group.cleaned)
            self.assertTrue(group.failed_cleanup)
            self.assertIsNone(control.poll())
            if phase=='term':
                self.assertFalse(group.exited())  # TERM-ignoring fixture really survived.
            else:
                self.assertIsNotNone(group._process.returncode)
            # A second bounded attempt finishes; after reap it must not signal again.
            if phase=='reap':
                with patch.object(group,'_signal',side_effect=AssertionError('signal after reap')):
                    group.cleanup()
            else:group.cleanup()
            self.assertTrue(group.cleaned);self.assertFalse(group.failed_cleanup)
            self.assertIsNotNone(group._process.returncode)
            self.assertTrue(all(s.closed for s in (group._process.stdin,group._process.stdout,group._process.stderr)))
            with patch.object(group,'_signal',side_effect=AssertionError('duplicate signal')):group.cleanup()
            self.assertLess(time.monotonic()-started,3)
            self.assertEqual(calls,[signal.SIGTERM] if phase=='term' else [signal.SIGTERM,signal.SIGKILL])
            self.assertIsNone(control.poll())
        finally:
            if sender:sender.join(timeout=3)
            signal.signal(signal.SIGTERM,old)
            # Fixture-only fallback also cleans the old implementation in RED runs.
            if group._process.returncode is None:
                os.killpg(group.pid,signal.SIGKILL);original_wait(timeout=2)
            for stream in (group._process.stdin,group._process.stdout,group._process.stderr):stream.close()
            control.terminate();control.wait(timeout=2)
            self.assertFalse(sender and sender.is_alive())

    def test_interrupted_term_cleanup_remains_failed_and_can_finish(self):
        self.cleanup_interruption('term')

    def test_interrupted_reap_cleanup_resumes_without_signalling_reused_group(self):
        self.cleanup_interruption('reap')

    @unittest.skipUnless(sys.platform=='linux','Linux cross-thread signal delivery')
    def test_real_sigterm_from_unblocked_thread_interrupts_cleanup_truthfully(self):
        self.cleanup_interruption('term',real_signal=True)

    def test_cleanup_wait_and_drain_errors_stay_explicit_until_finished(self):
        process=R.process_module()
        for phase in ('wait','drain'):
            with self.subTest(phase=phase):
                group=process.OwnedGroup([sys.executable,'-c','import time;time.sleep(15)'])
                failure=(patch.object(group._process,'wait',side_effect=subprocess.TimeoutExpired('fixture',.7))
                    if phase=='wait' else patch.object(process.os,'read',side_effect=OSError('fixture')))
                try:
                    with failure,self.assertRaises(process.CleanupFailure):group.cleanup()
                    self.assertFalse(group.cleaned);self.assertTrue(group.failed_cleanup)
                    with patch.object(group,'_signal',side_effect=AssertionError('repeat destructive signal')):
                        group.cleanup()
                    self.assertTrue(group.cleaned);self.assertFalse(group.failed_cleanup)
                    self.assertIsNotNone(group._process.returncode)
                finally:
                    if group._process.returncode is None:
                        os.killpg(group.pid,signal.SIGKILL);group._process.wait(timeout=2)
                    for stream in (group._process.stdin,group._process.stdout,group._process.stderr):stream.close()

    def test_cleanup_error_is_explicit_and_never_partial_success(self):
        process=R.process_module()
        group=process.OwnedGroup([sys.executable,'-c','pass'])
        group.cleanup();group.failed_cleanup=True
        with self.assertRaises(process.CleanupFailure):group.cleanup()
        with patch.object(R,'process_module',return_value=process),patch.object(process,'capture',side_effect=process.CleanupFailure('cleanup')):
            with self.assertRaises(R.CleanupRefused):R.capture(['unused'])
        class Lock:
            def __enter__(self):return self
            def check(self):pass
            def __exit__(self,*args):pass
        out=io.StringIO()
        handlers={s:signal.getsignal(s) for s in (signal.SIGINT,signal.SIGTERM,signal.SIGHUP,signal.SIGALRM)}
        try:
            with patch.object(C,'Commands',side_effect=C.CleanupUnavailable()),contextlib.redirect_stdout(out):
                self.assertEqual(C.main(Lock),0)  # sanitized refusal delivered to the runner
            result=json.loads(R.sanitize(out.getvalue().encode()))
            self.assertEqual(result['reason'],'cleanup');self.assertFalse(result['stage_ready'])
        finally:
            for sig,handler in handlers.items():signal.signal(sig,handler)


class FilesTests(unittest.TestCase):
    def test_release_file_read_is_nofollow_readonly_and_bounded(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp)
            record = path / 'checkpoint'
            record.write_bytes(b'migration_completed\n')
            record.chmod(0o600)
            fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
            files = C.Files()
            before = record.read_bytes()
            try:
                self.assertEqual(files.record(fd, 'checkpoint'), before)
                self.assertEqual(record.read_bytes(), before)
                (path / 'symlink').symlink_to(record)
                with self.assertRaises(OSError): files.record(fd, 'symlink')
                record.write_bytes(b'x' * 4097)
                with self.assertRaises(C.Unavailable): files.record(fd, 'checkpoint')
                for index in range(129): (path / ('entry-' + str(index))).touch()
                with self.assertRaises(C.Unavailable): files.names(fd)
            finally:
                files.close()
                os.close(fd)

    def test_full_observation_does_not_mutate_fsm_or_ledger(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            stage = root / '.clubs-bot-release-state/stage'
            lock = stage / 'clubs-bot-schema-stage.lock'
            ledger = stage / 'clubs-bot-schema-stage.migration-ledgers'
            lock.mkdir(parents=True)
            ledger.mkdir()
            for p in (lock / 'checkpoint', ledger / '123-1.ledger', ledger / '123-1.outcome'):
                p.write_text('migration_completed' if p.name == 'checkpoint' else 'not parsed')
                p.chmod(0o600)
            before = {str(p.relative_to(root)): (p.read_bytes(), p.stat().st_mtime_ns)
                      for p in root.rglob('*') if p.is_file()}
            files = C.Files()
            try:
                # Test fixture API substitutes directories; production has no path input.
                def directory(path):
                    target = root / path[len(C.ROOT)+1:]
                    fd = os.open(target, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
                    files.fds.append(fd)
                    return fd
                files.directory = directory
                with patch.object(C, 'ROOT', str(root)):
                    # replacement API above expects original prefix; use actual fixture path
                    files.directory = lambda path: self.open_directory(files, path)
                    value = files.observe()
                self.assertEqual(value['retained_ledgers'], 1)
                self.assertEqual(value['checkpoint'], 'migration_completed')
                self.assertEqual(value['fsm_authority'], 'unknown')
            finally:
                files.close()
            after = {str(p.relative_to(root)): (p.read_bytes(), p.stat().st_mtime_ns)
                     for p in root.rglob('*') if p.is_file()}
            self.assertEqual(before, after)

    def open_directory(self, files, path):
        fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        files.fds.append(fd)
        return fd


@unittest.skipUnless(sys.platform == 'linux' and os.geteuid() == 0, 'Linux root-owned synthetic flock fixture')
class LinuxBoundaryTests(unittest.TestCase):
    def test_existing_host_lock_held_excludes_other_process_then_releases(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'shared.lock'
            path.touch(mode=0o660)
            path.chmod(0o660)
            before = (path.read_bytes(), path.stat().st_mtime_ns, path.stat().st_ino)
            code = "import fcntl,os,sys;f=os.open(sys.argv[1],os.O_RDONLY);fcntl.flock(f,fcntl.LOCK_EX|fcntl.LOCK_NB)"
            with L.HostLock(str(path)) as lock:
                lock.check()
                other = subprocess.run([sys.executable, '-I', '-S', '-B', '-c', code, str(path)],
                                       capture_output=True, timeout=3)
                self.assertNotEqual(other.returncode, 0)
            other = subprocess.run([sys.executable, '-I', '-S', '-B', '-c', code, str(path)],
                                   capture_output=True, timeout=3)
            self.assertEqual(other.returncode, 0)
            self.assertEqual(before, (path.read_bytes(), path.stat().st_mtime_ns, path.stat().st_ino))

    def test_missing_lock_never_created(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'missing.lock'
            with self.assertRaises(FileNotFoundError):
                with L.HostLock(str(path)): pass
            self.assertFalse(path.exists())

    def test_collector_cancellation_releases_host_lock_without_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'shared.lock'
            path.touch()
            path.chmod(0o660)
            commands = FakeCommands()
            commands.containers = lambda: (_ for _ in ()).throw(C.Cancelled())
            out = io.StringIO()
            with patch.object(C, 'Commands', return_value=commands), contextlib.redirect_stdout(out):
                self.assertEqual(C.main(lambda: L.HostLock(str(path))), 2)
            self.assertEqual(out.getvalue(), '')
            with L.HostLock(str(path)) as lock: lock.check()

    def test_anonymous_pin_handoff_sealed_and_closed(self):
        env = RunnerTests().env()
        seen = []
        def fake_capture(args, data, **kwargs):
            fd = kwargs['fds'][0]
            seen.append(fd)
            self.assertEqual(os.pread(fd, 64, 0), b'synthetic-known-hosts')
            self.assertIn('UserKnownHostsFile=/proc/self/fd/' + str(fd), args)
            with self.assertRaises(OSError): os.write(fd, b'x')
            return b'{"protocol":"clb-stage-snapshot-v1","environment":"stage","result":"unavailable","stage_ready":false}\n'
        source = {R.LOCK: b'# synthetic', R.COLLECTOR: b'# synthetic', R.PROCESS: b'# synthetic', R.PIN: b'def validate(env): return b"synthetic-known-hosts"'}
        with patch.object(R, 'sources', return_value=source), patch.object(R, 'capture', side_effect=fake_capture):
            self.assertEqual(json.loads(R.run(env))['result'], 'unavailable')
        with self.assertRaises(OSError): os.fstat(seen[0])


if __name__ == '__main__':
    unittest.main()
