#!/usr/bin/env python3
"""CLB-132 synthetic local tests. No SSH, stage, package tool or network is invoked."""
import ast
import base64
import builtins
import copy
import errno
import hashlib
import hmac
import importlib.util
import io
import json
import os
from pathlib import Path
import signal
import struct
import subprocess
import sys
import tempfile
import types
import unittest
from contextlib import ExitStack, contextmanager, nullcontext
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


def load(path):
    spec = importlib.util.spec_from_file_location(Path(path).stem, ROOT / path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


r = load('scripts/deploy/stage-package-plan.py')
p = load(r.PROTOCOL_PATH)
c = load(r.REMOTE_PATH)
inventory_fixtures = load('scripts/tests/runtime_inventory_fixtures.py')


def sources():
    return {path: (ROOT / path).read_bytes() for path in r.LOCAL_SOURCES}


def environment():
    return dict(PATH=os.defpath, GITHUB_REPOSITORY='koteev-m/clubs_bot', GITHUB_EVENT_NAME='workflow_dispatch',
                GITHUB_REF='refs/heads/main', GITHUB_REF_TYPE='branch', REPOSITORY_DEFAULT_BRANCH='main',
                GITHUB_SHA='a'*40, GITHUB_WORKFLOW_SHA='a'*40, GITHUB_RUN_ID='132', GITHUB_RUN_ATTEMPT='1',
                GITHUB_WORKFLOW_REF='koteev-m/clubs_bot/'+r.WORKFLOW_PATH+'@refs/heads/main',
                APP_ENV='stage', CONFIRMATION=r.CONFIRMATION, SSH_USER='synthetic-deployer',
                SSH_HOST='178.20.209.5', SSH_PORT='22')


def observation():
    # Exercise the exact collector orchestration with every operating-system edge replaced.
    def command(argv, *unused):
        if argv == ['dpkg', '--print-architecture']: return 0, b'amd64\n', b''
        if argv == ['dpkg', '--print-foreign-architectures']: return 0, b'', b''
        if argv[0] == 'apt-get': return 100, b'Inst ruby (1:3.2~ubuntu1)\nConf ruby (1:3.2~ubuntu1)\n', b''
        raise AssertionError(argv)
    with patch.object(c, 'run', side_effect=command), patch.object(c, 'bounded_file', return_value=(b'ID=ubuntu\nVERSION_ID="24.04"\n', None)), patch.object(c, 'source_rows', return_value=[]), patch.object(c, 'index_rows', return_value=[]), patch.object(c, 'preferences', return_value=[]), patch.object(c, 'config_value', return_value=None), patch.object(c, 'package_state', return_value={}), patch.object(c, 'policy', return_value={}), patch.object(c, 'script_metadata', return_value=[]), patch.object(c, 'outside_mapping', return_value=dict(classification='UNKNOWN_REQUIRES_CONTRACT_DECISION', outside_count=0, reason='not_exactly_one_current_collector_mapping')):
        return c.collect()


def frame(body, key=b'k'*32):
    return b'clb132-package-plan-auth:v=1 tag='+hmac.new(key, body, hashlib.sha256).hexdigest().encode()+b' '+body


class SourceTest(unittest.TestCase):
    def test_exact_closure_and_pinned_collector_request(self):
        r.verify_sources(sources())
        self.assertEqual(len(c.REQUEST), 21)
        for path in (r.REMOTE_PATH, r.TARGET_PATH):
            self.assertEqual(hashlib.sha256((ROOT/path).read_bytes()).hexdigest(), r.SOURCE_PINS[path])
        self.assertEqual(c.TARGET_SHA256, r.SOURCE_PINS[r.TARGET_PATH])

    def test_changed_missing_additional_and_uncompilable_rejected_before_exec(self):
        original = sources()
        broken = []
        for path in original:
            value = dict(original); del value[path]; broken.append(value)
        value = dict(original); value['unexpected.py'] = b'pass'; broken.append(value)
        for path in r.SOURCE_PINS:
            value = dict(original); value[path] += b'\n# changed'; broken.append(value)
        value = dict(original); value[r.RUNNER_PATH] = b'! invalid python'; broken.append(value)
        for value in broken:
            with self.subTest(paths=list(value)), patch.object(r, 'exec', create=True) as execute:
                with self.assertRaises((ValueError, SyntaxError)): r.load_transport(value, [False])
                execute.assert_not_called()

    def test_retired_target_and_wrong_request_count_refuse_before_exec(self):
        original=sources()
        target=json.loads(original[r.TARGET_PATH])
        retired=copy.deepcopy(target)
        retired['request']=[row for row in retired['request'] if row['package']!='openssl']
        next(row for row in retired['request'] if row['package']=='libssl3t64')['version']='3.0.13-0ubuntu3.15'
        broken=dict(original); broken[r.TARGET_PATH]=p.canonical(retired)
        with patch.object(r,'exec',create=True) as execute:
            with self.assertRaises(ValueError): r.load_transport(broken,[False])
            execute.assert_not_called()
        # Exercise the fixed count independently of source-hash and pair-equality
        # guards: even mutually matching captured vectors cannot shrink to 20.
        for package in ('openssl','libc-bin'):
            wrong=copy.deepcopy(target)
            wrong['request']=[row for row in wrong['request'] if row['package']!=package]
            request=tuple((row['package'],row['version']) for row in wrong['request'])
            tree=ast.parse(original[r.REMOTE_PATH])
            node=next(node for node in tree.body if isinstance(node,ast.Assign)
                      and any(isinstance(item,ast.Name) and item.id=='REQUEST' for item in node.targets))
            node.value=ast.parse(repr(request),mode='eval').body
            broken=dict(original)
            broken[r.TARGET_PATH]=p.canonical(wrong)
            broken[r.REMOTE_PATH]=ast.unparse(ast.fix_missing_locations(tree)).encode()
            pins={path:hashlib.sha256(broken[path]).hexdigest() for path in r.SOURCE_PINS}
            with self.subTest(removed=package),patch.object(r,'SOURCE_PINS',pins),patch.object(r,'exec',create=True) as execute:
                with self.assertRaises(ValueError): r.load_transport(broken,[False])
                execute.assert_not_called()

    def test_git_object_snapshot_checks_every_working_file_and_blob(self):
        original = sources(); sha = environment()['GITHUB_SHA']
        ids = {path: hashlib.sha1(f'blob {len(raw)}\0'.encode()+raw).hexdigest() for path, raw in original.items()}
        blobs = {ids[path]: raw for path, raw in original.items()}
        def git(argv, **kwargs):
            args = argv[4:]
            self.assertEqual(argv[0:2], ['git', '--no-replace-objects'])
            self.assertEqual(kwargs['env']['GIT_NO_REPLACE_OBJECTS'], '1')
            if args == ['rev-parse', 'HEAD']: out = (sha+'\n').encode()
            elif args[0] == 'ls-tree':
                path=args[-1]; self.assertEqual(args[2], sha)
                out=('100644 blob '+ids[path]+'\t'+path+'\0').encode()
            elif args[:2] == ['cat-file', 'blob']: out=blobs[args[2]]
            else: raise AssertionError(args)
            return r.CaptureResult(0, out)
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            for path, raw in original.items():
                dest=root/path; dest.parent.mkdir(parents=True, exist_ok=True); dest.write_bytes(raw)
            with patch.object(r, 'ROOT', root), patch.object(r, 'capture_result', side_effect=git):
                self.assertEqual(dict(r.snapshot(environment(), [False])), original)
                for path in original:
                    dest=root/path; dest.write_bytes(original[path]+b'changed')
                    with self.subTest(path=path), self.assertRaises(ValueError): r.snapshot(environment(), [False])
                    dest.write_bytes(original[path])
                blobs[ids[r.REMOTE_PATH]] += b'changed'
                with self.assertRaises(ValueError): r.snapshot(environment(), [False])

    def test_wrong_checked_out_revision_rejects_before_reads(self):
        with patch.object(r, 'capture_result', return_value=r.CaptureResult(0, b'b'*40+b'\n')) as capture:
            with self.assertRaises(ValueError): r.snapshot(environment(), [False])
            self.assertEqual(capture.call_count, 1)

    def test_captured_closure_not_checkout_import_path(self):
        snapshot = sources()
        transport = r.load_transport(snapshot, [False])
        self.assertEqual(transport.capture_result([sys.executable, '-I', '-S', '-B', '-c', 'print("safe")'], timeout=3, limit=128).output, b'safe\n')
        tree=ast.parse((ROOT/r.RUNNER_PATH).read_bytes())
        self.assertFalse(any(isinstance(n,ast.Attribute) and isinstance(n.value,ast.Name) and n.value.id=='sys' and n.attr=='path' for n in ast.walk(tree)))


class AuthorityTest(unittest.TestCase):
    def test_selfcheck_registers_each_new_suite_once(self):
        selfcheck=(ROOT/'scripts/selfcheck-quality-gates.sh').read_text()
        for name in ('test_stage_package_plan.py','test_stage_package_plan_collector.py'):
            self.assertEqual(selfcheck.count('PYTHONDONTWRITEBYTECODE=1 python3 "$ROOT_DIR/scripts/tests/'+name+'"'),1)

    def test_exact_request_and_all_identity_negatives(self):
        env=environment(); r.validate(env)
        for key in env:
            if key.startswith('GITHUB_') or key in ('APP_ENV', 'CONFIRMATION', 'REPOSITORY_DEFAULT_BRANCH'):
                with self.subTest(key=key), self.assertRaises(ValueError): r.validate({**env, key:'wrong'})
        for attempt in ('0', '2', '01', ''):
            with self.assertRaises(ValueError): r.validate({**env, 'GITHUB_RUN_ATTEMPT':attempt})

    def test_target_is_fixed_and_no_root_or_alternate_host(self):
        env=environment()
        # Constructed fixture key has a patched digest only inside this unit test.
        key=b'\0\0\0\x0bssh-ed25519\0\0\0\x20'+b'k'*32
        env['SSH_KNOWN_HOSTS']='178.20.209.5 ssh-ed25519 '+base64.b64encode(key).decode()
        accepted_digest=base64.b64decode('Li2AIDm9/OG8CHWQw16qhDfzbRM7E9uLNjPeKOZ9ST0=')
        with patch.object(r.hashlib, 'sha256', return_value=types.SimpleNamespace(digest=lambda:accepted_digest)):
            r.validate_target(env)
            for name, value in (('SSH_USER','root'), ('SSH_USER','hookah-staging'), ('SSH_USER','x;id'), ('SSH_HOST','example.org'), ('SSH_PORT','2222'), ('SSH_KNOWN_HOSTS',env['SSH_KNOWN_HOSTS']+'\n'+env['SSH_KNOWN_HOSTS'])):
                with self.subTest(name=name), self.assertRaises(ValueError): r.validate_target({**env,name:value})
        with self.assertRaises(ValueError): r.validate_target(env)

    def test_ssh_argv_strict_principal_and_no_fallback(self):
        argv=r.ssh_argv(environment(), '/proc/1/fd/4')
        self.assertEqual(argv.count('ssh'), 1)
        self.assertIn('synthetic-deployer@178.20.209.5', argv)
        for option in ('StrictHostKeyChecking=yes', 'GlobalKnownHostsFile=/dev/null', 'KnownHostsCommand=none', 'ConnectionAttempts=1', 'ProxyCommand=none', 'ProxyJump=none', 'PermitLocalCommand=no'):
            self.assertIn(option, argv)
        self.assertIn('test "$(id -u)" != 0', argv[-1])
        self.assertIn('python3 -I -S -B -c', argv[-1])
        for term in ('accept-new', 'ssh-keyscan', 'StrictHostKeyChecking=no', 'sudo ', 'scp '):
            self.assertNotIn(term, ' '.join(argv))

    def test_validator_exact_positive_and_capability_mutations(self):
        program=r'''require 'json'; require './scripts/validate-workflow-capabilities'
value=JSON.parse(STDIN.read); StagePackagePlanWorkflow.validate(WorkflowCapabilityPolicy,value)
'''
        parsed=subprocess.check_output(['ruby','-ryaml','-rjson','-e','puts JSON.generate(YAML.safe_load(File.read(ARGV[0])))',str(ROOT/r.WORKFLOW_PATH)])
        workflow=json.loads(parsed)
        def check(value):
            return subprocess.run(['ruby','-e',program], cwd=ROOT, input=json.dumps(value).encode(), stdout=subprocess.PIPE, stderr=subprocess.PIPE).returncode
        self.assertEqual(check(workflow),0)
        mutations=[]
        for field, value in (('permissions', {'contents':'write'}), ('on', {'push':None})):
            changed=copy.deepcopy(workflow); changed[field]=value; mutations.append(changed)
        for field, value in (('environment','prod'), ('permissions',{'actions':'write'})):
            changed=copy.deepcopy(workflow); changed['jobs']['collect'][field]=value; mutations.append(changed)
        for command in ('apt-get install ruby', 'apt-get download ruby', 'gh workflow run stage-compose-env-semantic.yml', 'python3 scripts/deploy/corrected-stage-release.py', 'gh api --method PUT repos/x/environments/stage', '${{ inputs.command }}'):
            changed=copy.deepcopy(workflow); changed['jobs']['collect']['steps'][-1]['run']=command; mutations.append(changed)
        for name in ('command','script','package','path','hash','target'):
            changed=copy.deepcopy(workflow); changed['on']['workflow_dispatch']['inputs'][name]={'type':'string'}; mutations.append(changed)
        changed=copy.deepcopy(workflow); changed['jobs']['collect']['steps'][-1]['env']['OTHER']='${{ secrets.COMPOSE_PATH }}'; mutations.append(changed)
        for changed in mutations:
            with self.subTest(mutation=changed): self.assertNotEqual(check(changed),0)


class ProtocolTest(unittest.TestCase):
    def setUp(self):
        self.identity=r.invocation_identity(environment(),sources()); self.challenge='c'*64
        self.value=dict(schema='clb132-package-plan-v1',identity=self.identity,challenge=self.challenge,result='OBSERVED',reason='COLLECTION_COMPLETED',evidence=observation())
        self.body=p.body(self.value,0,c,self.identity,self.challenge)

    def parse(self, data, code=0, key=b'k'*32):
        return r.parse_result(data,code,key,p,c,self.identity,self.challenge)

    def test_valid_authenticated_observation_is_not_resolver_approval(self):
        self.assertEqual(self.parse(frame(self.body))[1],0)
        self.assertEqual(self.value['evidence']['resolver']['exit_status'],100)
        self.assertEqual(self.value['evidence']['generated_state_proof'],'NOT_ESTABLISHED')

    def test_native_package_identity_round_trips_without_missing_state(self):
        sample=b'libc6:amd64\t2.39-0ubuntu8.9\tamd64\tii \tno\toptional\n'
        with patch.object(c,'run',side_effect=[(0,sample,b''),(0,b'libc6:amd64\n',b'')]):
            self.value['evidence']['package_state']=c.package_state(['libc6'])
        body=p.body(self.value,0,c,self.identity,self.challenge)
        line,code=self.parse(frame(body))
        value=json.loads(line[len(p.PREFIX):])
        self.assertEqual(code,0)
        self.assertEqual(value['evidence']['package_state'],{'libc6':dict(
            version='2.39-0ubuntu8.9',architecture='amd64',status_abbrev='ii ',
            essential='no',priority='optional',held=True)})

    def test_previous_clb162_collector_identity_is_not_candidate_evidence(self):
        previous='2cc04008656c62ef633029392a37666189cd0d8286a05309def92bcb4689e970'
        self.assertNotEqual(self.identity['collector_sha256'],previous)
        broken=copy.deepcopy(self.value)
        broken['identity']['collector_sha256']=previous
        with self.assertRaises(ValueError):
            self.parse(frame(p.PREFIX+p.canonical(broken)+b'\n'))

    def test_large_release_relations_fit_unchanged_authenticated_contract(self):
        fixtures=load('scripts/tests/test_stage_package_plan_collector.py').ReleaseReferenceTests
        self.value['evidence']['indexes']=fixtures.indexes(fixtures.large_release(), ('main','universe'))
        body=p.body(self.value,0,c,self.identity,self.challenge)
        self.assertEqual(self.parse(frame(body))[1],0)
        self.assertIn(b'MATCHED_VERIFIED_RELEASE',body)
        # Retention cap remains part of the authenticated consumer contract.
        release=self.value['evidence']['indexes'][0]
        release['amd64_package_refs']=[release['amd64_package_refs'][0]]*33
        with self.assertRaises(ValueError):p.body(self.value,0,c,self.identity,self.challenge)

    def test_wrong_challenge_hmac_and_identity(self):
        with self.assertRaises(ValueError): self.parse(frame(self.body),key=b'z'*32)
        for key,value in (('challenge','d'*64), ('identity',{**self.identity,'run_id':'133'}), ('identity',{**self.identity,'attempt':'2'}), ('identity',{**self.identity,'workflow_sha':'b'*40})):
            broken={**self.value,key:value}; raw=p.PREFIX+p.canonical(broken)+b'\n'
            with self.subTest(key=key), self.assertRaises(ValueError): self.parse(frame(raw))

    def test_malformed_duplicate_trailing_oversized_and_spoof(self):
        valid=frame(self.body)
        for data in (valid+valid,valid+b'junk',b'spoof\n'+valid,valid+b'\n',b'x'*(r.FRAME_LIMIT+1),valid.replace(b'auth:v=1',b'auth:v=2'),frame(p.PREFIX+b'{bad}\n'),frame(self.body.replace(b'"schema":', b'"schema":"spoof","schema":',1))):
            with self.subTest(size=len(data)), self.assertRaises((ValueError,json.JSONDecodeError)): self.parse(data)

    def test_schema_unknown_fields_raw_credentials_and_nested_junk_refuse(self):
        for change in ('field','nested','credentials','target','schema'):
            broken=copy.deepcopy(self.value)
            if change=='field': broken['payload']='anything'
            if change=='nested': broken['evidence']['sources']=[{'private':'anything'}]
            if change=='credentials': broken['evidence']['resolver']['raw_lines']=['password=DO_NOT_EMIT']
            if change=='target': broken['evidence']['resolver']['requested']=['ruby-psych=1']
            if change=='schema': broken['evidence']['schema']='other'
            with self.subTest(change=change), self.assertRaises(ValueError): self.parse(frame(p.PREFIX+p.canonical(broken)+b'\n'))

    def test_retired_openssl_request_and_identities_are_not_current_evidence(self):
        retired_target='29e3592504e33066f2f2a9a208f4f038f052ca33067b8cd6322e46639bd16a14'
        retired_collector='6e10f52cbfbdc6edfb3f9c40431f795f098cdd646465dc12f0dadcf7da9df6ea'
        changes=('libssl15','openssl15','missing_openssl','duplicate_openssl','target','collector','evidence_target')
        for change in changes:
            broken=copy.deepcopy(self.value)
            requested=broken['evidence']['resolver']['requested']
            if change in ('libssl15','openssl15'):
                package='libssl3t64' if change=='libssl15' else 'openssl'
                requested[requested.index(package+'=3.0.13-0ubuntu3.16')]=package+'=3.0.13-0ubuntu3.15'
            elif change=='missing_openssl': requested.remove('openssl=3.0.13-0ubuntu3.16')
            elif change=='duplicate_openssl': requested.append('openssl=3.0.13-0ubuntu3.16')
            elif change=='target': broken['identity']['target_sha256']=retired_target
            elif change=='collector': broken['identity']['collector_sha256']=retired_collector
            else: broken['evidence']['target_sha256']=retired_target
            with self.subTest(change=change),self.assertRaises(ValueError):
                self.parse(frame(p.PREFIX+p.canonical(broken)+b'\n'))
        self.assertEqual(c.MAIN,p.BASE)
        self.assertEqual(c.TARGET_SHA256,p.TARGET_SHA)
        self.assertEqual(hashlib.sha256((ROOT/r.REMOTE_PATH).read_bytes()).hexdigest(),p.COLLECTOR_SHA)

    def test_refusal_preserved_and_nonzero_cannot_be_success(self):
        with patch.object(c,'collect',side_effect=c.Refuse('PLAN_TOO_LARGE')):
            body,code=p.collect(c,self.identity,self.challenge)
        self.assertEqual(code,1); self.assertIn(b'PLAN_TOO_LARGE',body)
        self.assertEqual(self.parse(frame(body),code=1)[1],1)
        with self.assertRaises(ValueError): self.parse(frame(body),code=0)
        with self.assertRaises(ValueError): self.parse(frame(self.body),code=1)
        with self.assertRaises(ValueError): self.parse(frame(self.body),code=255)

    def phase_failure(self, phase, error, argv=None):
        calls = {'architecture': 'run', 'os': 'bounded_file', 'sources': 'source_rows',
                 'indexes': 'index_rows', 'preferences': 'preferences', 'apt_config': 'config_value',
                 'package_state': 'package_state', 'apt_policy': 'policy', 'resolver': 'run',
                 'maintainer_scripts': 'script_metadata', 'outside_mapping': 'outside_mapping',
                 'final_validation': 'generated_impact'}
        selected = calls[phase]
        fake = types.SimpleNamespace(Refuse=c.Refuse, subprocess=subprocess,
                                     **{name: getattr(c, name) for name in p.PhaseTracker.DIRECT})
        fake.run = c.run
        fake.config_value = c.config_value

        def failure(argv=None, *_args):
            raise error

        setattr(fake, selected, failure)

        def collect():
            target = getattr(fake, selected)
            if selected == 'run':
                target(argv if argv is not None else ['dpkg', '--print-architecture'])
            elif selected == 'bounded_file':
                target('/etc/os-release', 4096)
            else:
                target()

        fake.collect = collect
        with patch.object(p, 'CommandOwnership', return_value=nullcontext()):
            return p.collect(fake, self.identity, self.challenge)

    def test_every_fixed_phase_reports_only_its_allowlisted_reason(self):
        canary = 'SECRET_PATH_token=DO_NOT_EMIT'
        self.assertEqual(set(p.PHASE_REASONS), {
            'architecture', 'os', 'sources', 'indexes', 'preferences', 'apt_config',
            'package_state', 'apt_policy', 'resolver', 'maintainer_scripts',
            'outside_mapping', 'final_validation'})
        for phase, reason in p.PHASE_REASONS.items():
            argv = ['apt-get'] if phase == 'resolver' else None
            with self.subTest(phase=phase):
                body, code = self.phase_failure(phase, ValueError(canary), argv)
                self.assertEqual(code, 1)
                value = json.loads(body[len(p.PREFIX):])
                self.assertEqual((value['result'], value['reason'], value['evidence']),
                                 ('UNAVAILABLE', reason, None))
                self.assertNotIn(canary.encode(), body)
                self.assertEqual(self.parse(frame(body), code=1), (body.decode().rstrip('\n'), 1))

    def test_generic_exception_classes_do_not_leak_payload(self):
        for exception in (OSError, UnicodeError, ValueError, subprocess.SubprocessError):
            with self.subTest(exception=exception.__name__):
                body, code = self.phase_failure('architecture', exception('PRIVATE_CANARY'))
                self.assertEqual(code, 1)
                self.assertEqual(json.loads(body[len(p.PREFIX):])['reason'],
                                 p.PHASE_REASONS['architecture'])
                self.assertNotIn(b'PRIVATE_CANARY', body)

    def test_unregistered_phase_fails_closed(self):
        body, code = self.phase_failure('architecture', OSError('PRIVATE_CANARY'), ['unexpected'])
        self.assertEqual(code, 1)
        self.assertEqual(json.loads(body[len(p.PREFIX):])['reason'], 'INVALID_EVIDENCE')
        self.assertNotIn(b'PRIVATE_CANARY', body)

        fake = types.SimpleNamespace(Refuse=c.Refuse, subprocess=subprocess,
                                     **{name: getattr(c, name) for name in p.PhaseTracker.DIRECT})
        fake.run = c.run

        def unknown_helper():
            raise ValueError('PRIVATE_UNKNOWN_CANARY')

        def collect():
            unknown_helper()

        fake.collect = collect
        with patch.object(p, 'CommandOwnership', return_value=nullcontext()):
            body, code = p.collect(fake, self.identity, self.challenge)
        self.assertEqual(code, 1)
        self.assertEqual(json.loads(body[len(p.PREFIX):])['reason'], 'INVALID_EVIDENCE')
        self.assertNotIn(b'PRIVATE_UNKNOWN_CANARY', body)

    def test_pinned_collector_calls_mark_sources_and_config_comprehension(self):
        def command(argv, *_args):
            if argv == ['dpkg', '--print-architecture']:
                return 0, b'amd64\n', b''
            if argv == ['dpkg', '--print-foreign-architectures']:
                return 0, b'', b''
            raise AssertionError(argv)

        def fail_sources():
            raise OSError('PRIVATE_SOURCE_CANARY')

        with patch.object(c, 'run', new=command), patch.object(c, 'bounded_file',
                return_value=(b'ID=ubuntu\nVERSION_ID=24.04\n', None)), patch.object(c, 'source_rows', new=fail_sources):
            body, code = p.collect(c, self.identity, self.challenge)
        self.assertEqual(code, 1)
        self.assertEqual(json.loads(body[len(p.PREFIX):])['reason'], p.PHASE_REASONS['sources'])
        self.assertNotIn(b'PRIVATE_SOURCE_CANARY', body)

        def fail_config(_key):
            raise UnicodeError('PRIVATE_CONFIG_CANARY')

        with patch.object(c, 'run', new=command), patch.object(c, 'bounded_file',
                return_value=(b'ID=ubuntu\nVERSION_ID=24.04\n', None)), patch.object(c, 'source_rows',
                return_value=[]), patch.object(c, 'index_rows', return_value=[]), patch.object(c, 'preferences',
                return_value=[]), patch.object(c, 'config_value', new=fail_config):
            body, code = p.collect(c, self.identity, self.challenge)
        self.assertEqual(code, 1)
        self.assertEqual(json.loads(body[len(p.PREFIX):])['reason'], p.PHASE_REASONS['apt_config'])
        self.assertNotIn(b'PRIVATE_CONFIG_CANARY', body)


class OsReleaseSlotTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        inventory_fixtures.make_fixture(self.root)
        self.identity = r.invocation_identity(environment(), sources())
        self.challenge = 'c' * 64

        class RoutedOS(inventory_fixtures.FixtureOS):
            def open(self, path, flags, *args, **kwargs):
                if path == '/etc/os-release':
                    fd = os.open(Path(self.root) / 'etc/os-release', flags, *args, **kwargs)
                    self.owned.add(fd)
                    return fd
                return super().open(path, flags, *args, **kwargs)

        self.routed_os = RoutedOS(self.root)

    def command(self, argv, *_unused):
        if argv == ['dpkg', '--print-architecture']:
            return 0, b'amd64\n', b''
        if argv == ['dpkg', '--print-foreign-architectures']:
            return 0, b'', b''
        if argv[0] == 'apt-get':
            return 100, b'Inst ruby (1:3.2~ubuntu1)\nConf ruby (1:3.2~ubuntu1)\n', b''
        self.fail('unexpected package argv')

    @contextmanager
    def routed(self):
        with ExitStack() as stack:
            for module in (c, p):
                stack.enter_context(patch.object(module, 'os', self.routed_os))
            stack.enter_context(patch.object(p, 'CommandOwnership', return_value=nullcontext()))
            stack.enter_context(patch.object(c, 'run', new=self.command))
            stack.enter_context(patch.object(c, 'source_rows', return_value=[]))
            stack.enter_context(patch.object(c, 'index_rows', return_value=[]))
            stack.enter_context(patch.object(c, 'preferences', return_value=[]))
            stack.enter_context(patch.object(c, 'config_value', return_value=None))
            stack.enter_context(patch.object(c, 'package_state', return_value={}))
            stack.enter_context(patch.object(c, 'policy', return_value={}))
            stack.enter_context(patch.object(c, 'script_metadata', return_value=[]))
            stack.enter_context(patch.object(c, 'outside_mapping', return_value=dict(
                classification='UNKNOWN_REQUIRES_CONTRACT_DECISION', outside_count=0,
                reason='not_exactly_one_current_collector_mapping')))
            yield

    def collect(self):
        with self.routed():
            body, code = p.collect(c, self.identity, self.challenge)
        return json.loads(body[len(p.PREFIX):]), code, body

    def test_frozen_direct_open_reproduces_eloop_and_phase_failure(self):
        with self.routed():
            with self.assertRaises(OSError) as caught:
                c.bounded_file('/etc/os-release', 4096)
            self.assertEqual(caught.exception.errno, errno.ELOOP)
            with patch.object(p, '_read_fixed_os_release', side_effect=OSError(errno.ELOOP, 'PRIVATE')):
                body, code = p.collect(c, self.identity, self.challenge)
        value = json.loads(body[len(p.PREFIX):])
        self.assertEqual((code, value['result'], value['reason'], value['evidence']),
                         (1, 'UNAVAILABLE', 'READ_ONLY_OS_FAILED', None))
        self.assertNotIn(b'PRIVATE', body)

    def test_exact_safe_symlink_and_regular_file_observe_same_os_bytes(self):
        expected = (self.root / 'usr/lib/os-release').read_bytes()
        with self.routed():
            self.assertEqual(p.collect(c, self.identity, self.challenge)[1], 0)
            self.assertEqual(p._read_fixed_os_release()[0], expected)
        (self.root / 'etc/os-release').unlink()
        (self.root / 'etc/os-release').symlink_to('/usr/lib/os-release')
        value, code, _ = self.collect()
        self.assertEqual((code, value['result']), (0, 'OBSERVED'))
        (self.root / 'etc/os-release').unlink()
        (self.root / 'etc/os-release').write_bytes(expected)
        value, code, _ = self.collect()
        self.assertEqual(code, 0)
        self.assertEqual((value['result'], value['reason']), ('OBSERVED', 'COLLECTION_COMPLETED'))
        self.assertEqual(value['evidence']['os'], {'ID': 'ubuntu', 'VERSION_ID': '24.04'})

    def test_unsafe_links_target_and_metadata_fail_closed(self):
        link = self.root / 'etc/os-release'
        target = self.root / 'usr/lib/os-release'
        cases = ('arbitrary', 'absolute_arbitrary', 'nested', 'oversized', 'directory',
                 'world_writable', 'bad_owner', 'unsafe_parent', 'hard_link')
        for case in cases:
            with self.subTest(case=case):
                link.unlink(missing_ok=True)
                target.unlink(missing_ok=True)
                if case == 'directory':
                    target.mkdir()
                else:
                    target.write_bytes(b'ID=ubuntu\nVERSION_ID=24.04\n')
                link.symlink_to('../usr/lib/os-release')
                if case == 'arbitrary':
                    link.unlink(); link.symlink_to('PRIVATE_OTHER_TARGET')
                elif case == 'absolute_arbitrary':
                    link.unlink(); link.symlink_to('/etc/PRIVATE_OTHER_TARGET')
                elif case == 'nested':
                    target.unlink(); target.symlink_to('PRIVATE_OTHER_TARGET')
                elif case == 'oversized':
                    target.write_bytes(b'x' * 4097)
                elif case == 'world_writable':
                    target.chmod(0o666)
                elif case == 'bad_owner':
                    self.routed_os.bad_owner = True
                elif case == 'unsafe_parent':
                    (self.root / 'usr/lib').chmod(0o777)
                elif case == 'hard_link':
                    os.link(target, self.root / 'usr/lib/second-name')
                value, code, body = self.collect()
                self.assertEqual((code, value['result'], value['reason'], value['evidence']),
                                 (1, 'UNAVAILABLE', 'READ_ONLY_OS_FAILED', None))
                self.assertNotIn(b'PRIVATE', body)
                self.routed_os.bad_owner = False
                (self.root / 'usr/lib').chmod(0o755)
                (self.root / 'usr/lib/second-name').unlink(missing_ok=True)
                if target.is_dir() and not target.is_symlink():
                    target.rmdir()

    def test_link_and_target_substitution_during_read_fail_closed(self):
        for slot in ('link', 'target'):
            with self.subTest(slot=slot):
                def replace():
                    if slot == 'link':
                        link = self.root / 'etc/os-release'
                        link.unlink(); link.symlink_to('PRIVATE_OTHER_TARGET')
                    else:
                        target = self.root / 'usr/lib/os-release'
                        target.rename(self.root / 'usr/lib/old-os-release')
                        target.write_bytes(b'ID=ubuntu\nVERSION_ID=24.04\n')
                self.routed_os.after_read = replace
                value, code, body = self.collect()
                self.assertEqual((code, value['reason'], value['evidence']),
                                 (1, 'READ_ONLY_OS_FAILED', None))
                self.assertNotIn(b'PRIVATE', body)
                (self.root / 'etc/os-release').unlink(missing_ok=True)
                (self.root / 'etc/os-release').symlink_to('../usr/lib/os-release')
                (self.root / 'usr/lib/old-os-release').unlink(missing_ok=True)

    def test_parent_symlink_chains_are_rejected(self):
        for name in ('etc', 'usr'):
            with self.subTest(name=name):
                parent = self.root / name
                saved = self.root / (name + '-saved')
                parent.rename(saved)
                parent.symlink_to(saved.name)
                try:
                    value, code, _ = self.collect()
                    self.assertEqual((code, value['reason'], value['evidence']),
                                     (1, 'READ_ONLY_OS_FAILED', None))
                finally:
                    parent.unlink()
                    saved.rename(parent)


class BootstrapTest(unittest.TestCase):
    def test_large_final_frame_backpressure_deadline_alarm_and_cancellation(self):
        identity=r.invocation_identity(environment(),sources());challenge='c'*64
        remote={Path(path).name:base64.b64encode((ROOT/path).read_bytes()).decode() for path in r.REMOTE_SOURCES}
        bundle=p.canonical(remote);control=p.canonical(dict(identity=identity,challenge=challenge,sha256=hashlib.sha256(bundle).hexdigest()))
        payload=b'k'*32+struct.pack('!I',len(control))+control+bundle
        program=f'''import builtins
observed={observation()!r}
def captured_exec(code,scope):
    builtins.exec(code,scope)
    if scope['__name__']=='fixed_package_collector':
        def synthetic_collect():
            observed['resolver'].update(scope['parse_plan']((('A'*200+'\\n')*800).encode()))
            observed['generated_state_impact']=[]
            return observed
        scope['collect']=synthetic_collect
builtins.exec({r.BOOTSTRAP!r},{{'exec':captured_exec}})
'''
        import selectors
        for stop in (None,signal.SIGALRM,signal.SIGTERM):
            with self.subTest(signal=stop):
                child=subprocess.Popen([sys.executable,'-I','-S','-B','-c',program],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,start_new_session=True)
                try:
                    child.stdin.write(payload);child.stdin.close()
                    with selectors.DefaultSelector() as ready:
                        ready.register(child.stdout,selectors.EVENT_READ)
                        self.assertTrue(ready.select(5),'bootstrap did not reach final emission')
                    prefix=os.read(child.stdout.fileno(),128)
                    self.assertTrue(prefix.startswith(b'clb132-package-plan-auth:v=1 '),prefix)
                    # Do not drain the >160 KB valid frame: stdout fills, while
                    # the exact bootstrap must remain time-bounded and cancellable.
                    if stop is not None: os.kill(child.pid,stop)
                    self.assertEqual(child.wait(timeout=7 if stop is None else 2),1)
                    partial=prefix+child.stdout.read()
                    with self.assertRaises(ValueError):r.parse_result(partial,1,b'k'*32,p,c,identity,challenge)
                finally:
                    if child.poll() is None:
                        os.killpg(child.pid,signal.SIGKILL);child.wait(timeout=2)
                    child.stdin.close();child.stdout.close()

    def test_real_isolated_bootstrap_success_and_signal_cleanup(self):
        identity=r.invocation_identity(environment(),sources()); challenge='c'*64
        remote={Path(path).name:base64.b64encode((ROOT/path).read_bytes()).decode() for path in r.REMOTE_SOURCES}
        bundle=p.canonical(remote); control=p.canonical(dict(identity=identity,challenge=challenge,sha256=hashlib.sha256(bundle).hexdigest()))
        payload=b'k'*32+struct.pack('!I',len(control))+control+bundle
        for interrupted in (False,True):
            # Only the synthetic system-edge function changes. Verified source
            # compilation, ownership adapter, bootstrap and wire consumer are real.
            child="import os,signal,time;os.kill(os.getppid(),signal.SIGTERM);time.sleep(2)" if interrupted else 'print("query")'
            program=f'''import builtins,sys
observed={observation()!r}
def captured_exec(code,scope):
    builtins.exec(code,scope)
    if scope['__name__']=='fixed_package_collector':
        def synthetic_collect():
            scope['run']([sys.executable,'-I','-S','-B','-c',{child!r}],timeout=3)
            return observed
        scope['collect']=synthetic_collect
builtins.exec({r.BOOTSTRAP!r},{{'exec':captured_exec}})
'''
            value=r.capture_result([sys.executable,'-I','-S','-B','-c',program],payload,timeout=8,limit=r.FRAME_LIMIT)
            self.assertIsNone(value.failure)
            if interrupted:
                self.assertEqual((value.code,value.output),(1,b''))
            else:
                self.assertEqual(r.parse_result(value.output,value.code,b'k'*32,p,c,identity,challenge)[1],0)

    def execute(self, remote=None, refusal=False):
        remote=remote if remote is not None else {Path(path).name:base64.b64encode((ROOT/path).read_bytes()).decode() for path in r.REMOTE_SOURCES}
        encoded=p.canonical(remote); identity=r.invocation_identity(environment(),sources()); challenge='c'*64
        control=p.canonical(dict(identity=identity,challenge=challenge,sha256=hashlib.sha256(encoded).hexdigest()))
        payload=b'k'*32+struct.pack('!I',len(control))+control+encoded
        writes=[]; executed=[]
        def execute(code, scope):
            executed.append(scope['__name__']); builtins.exec(code,scope)
            if scope['__name__']=='fixed_package_collector':
                def fake_collect():
                    if refusal: raise scope['Refuse']('PLAN_TOO_LARGE')
                    return observation()
                scope['collect']=fake_collect
        def write(fd,data): writes.append(data); return len(data)
        flags=types.SimpleNamespace(isolated=1,no_site=1)
        fake_selector=unittest.mock.MagicMock()
        fake_selector.__enter__.return_value.select.return_value=[(None,None)]
        with patch.object(r.selectors,'DefaultSelector',return_value=fake_selector),patch.object(os,'set_blocking'),patch.object(sys,'stdin',types.SimpleNamespace(buffer=io.BytesIO(payload))), patch.object(sys,'flags',flags), patch.object(sys,'dont_write_bytecode',True), patch.object(signal,'signal'), patch.object(signal,'alarm'), patch.object(signal,'pthread_sigmask'), patch.object(signal,'sigpending',return_value=set()), patch.object(os,'write',side_effect=write), patch.object(subprocess,'Popen',side_effect=AssertionError('No real commands allowed')):
            with self.assertRaises(SystemExit) as stopped: builtins.exec(compile(r.BOOTSTRAP,'<bootstrap>','exec'),{'exec':execute})
        return stopped.exception.code,b''.join(writes),executed,identity,challenge

    def test_exact_bootstrap_authenticated_complete_and_refused(self):
        for refusal in (False,True):
            code,raw,executed,identity,challenge=self.execute(refusal=refusal)
            self.assertEqual(code,int(refusal)); self.assertEqual(len(executed),2)
            self.assertEqual(r.parse_result(raw,code,b'k'*32,p,c,identity,challenge)[1],code)

    def test_bootstrap_source_pins_missing_extra_and_changed_precede_exec(self):
        original={Path(path).name:base64.b64encode((ROOT/path).read_bytes()).decode() for path in r.REMOTE_SOURCES}
        cases=[]
        for name in original:
            broken=dict(original); del broken[name]; cases.append(broken)
            broken=dict(original); broken[name]=base64.b64encode(b'print("canary")').decode(); cases.append(broken)
        cases.append({**original,'extra.py':'cGFzcw=='})
        for remote in cases:
            code,raw,executed,*_=self.execute(remote)
            self.assertEqual((code,raw,executed),(1,b'',[]))


class TransportTest(unittest.TestCase):
    def test_exact_existing_capture_primitive(self):
        def primitive(path): return (ROOT/path).read_text().split('# BEGIN EXACT CORRECTED CAPTURE PRIMITIVES\n')[1].split('# END EXACT CORRECTED CAPTURE PRIMITIVES')[0]
        self.assertEqual(primitive(r.RUNNER_PATH),primitive('scripts/deploy/stage-runtime-inventory.py'))

    def test_timeout_output_limit_and_nonzero(self):
        for script,timeout,limit,expected in [('import time;time.sleep(10)',.05,128,'capture_timeout'),('print("x"*1024)',3,10,'capture_output_limit'),('raise SystemExit(255)',3,128,None)]:
            value=r.capture_result([sys.executable,'-I','-S','-B','-c',script],timeout=timeout,limit=limit)
            self.assertEqual(value.failure,expected)
            if expected is None:self.assertEqual(value.code,255)

    def test_cancellation_and_group_cleanup_own_children(self):
        killed=[]; real_kill=os.killpg
        def kill(group,sig): killed.append((group,sig)); return real_kill(group,sig)
        real_spawn=subprocess.Popen
        def spawn(*args,**kwargs):
            child=real_spawn(*args,**kwargs); r._capture_cancellation[0]=True; return child
        with patch.object(r.subprocess,'Popen',side_effect=spawn),patch.object(r.os,'killpg',side_effect=kill):
            value=r.capture_result([sys.executable,'-I','-S','-B','-c','import time;time.sleep(10)'],timeout=3)
        self.assertEqual(value.failure,'capture_interrupted'); self.assertTrue(any(sig==signal.SIGKILL for _,sig in killed))
        self.assertIsNone(r._capture_cancellation)

    def test_successful_leader_with_descendant_is_cleaned(self):
        # Descendant closes captures; the leader exits. Cleanup must still own its group.
        script='import os,time; p=os.fork();\nif p==0:\n os.close(0); os.close(1); time.sleep(10)\nelse: print(p)'
        groups=[]; real_kill=os.killpg
        def kill(group,sig): groups.append((group,sig)); return real_kill(group,sig)
        with patch.object(r.os,'killpg',side_effect=kill):
            result=r.capture_result([sys.executable,'-I','-S','-B','-c',script],timeout=3,limit=128)
        self.assertEqual((result.code,result.failure),(0,None)); self.assertTrue(any(sig==signal.SIGKILL for _,sig in groups))

    def test_main_one_transport_no_retry_on_every_failure(self):
        @contextmanager
        def pin(env):
            with tempfile.TemporaryFile() as file: yield file.fileno(),'/synthetic/pin'
        for failure,code in ((None,255),('capture_timeout',124),('capture_output_limit',125),('capture_interrupted',124)):
            capture=unittest.mock.Mock(return_value=r.CaptureResult(code,b'',failure))
            transport=types.SimpleNamespace(pinned_hosts=pin,capture_result=capture,_capture_cancellation=None)
            with patch.object(r,'snapshot',return_value=sources()),patch.object(r,'load_transport',return_value=transport),patch.object(r,'validate_target'),patch.object(r.signal,'signal'):
                line,status=r.main(environment(),[],[False])
            self.assertEqual(status,1); self.assertIn('REFUSED',line); self.assertEqual(capture.call_count,1)
            self.assertEqual(capture.call_args.args[0][0],'ssh')
            self.assertEqual(capture.call_args.kwargs['timeout'],120)

    def test_collector_timeout_cleans_fixed_command_group(self):
        c.START=c.time.monotonic()
        with p.CommandOwnership(c,lambda:False) as owner:
            with self.assertRaises(c.Refuse): c.run([sys.executable,'-I','-S','-B','-c','import time;time.sleep(2)'],timeout=.02)
        self.assertTrue(all(child.finished for child in owner.children))

    def test_collector_post_spawn_pre_finally_failure_is_owned(self):
        c.START=c.time.monotonic()
        with self.assertRaises(c.Refuse):
            with p.CommandOwnership(c,lambda:False) as owner:
                with patch.object(c.selectors,'DefaultSelector',side_effect=c.Refuse('INTERRUPTED')):
                    c.run([sys.executable,'-I','-S','-B','-c','import time;time.sleep(2)'])
        self.assertEqual(len(owner.children),1)
        self.assertTrue(owner.children[0].finished)

    def test_collector_success_exited_leader_descendant_is_owned(self):
        script='import os,time; p=os.fork();\nif p==0:\n os.close(0); os.close(1); os.close(2); time.sleep(10)\nelse: print(p)'
        c.START=c.time.monotonic()
        with p.CommandOwnership(c,lambda:False) as owner:
            result=c.run([sys.executable,'-I','-S','-B','-c',script],timeout=3)
        self.assertEqual(result[0],0); self.assertTrue(owner.children[0].finished)

    def test_collector_cancel_after_spawn_and_during_cleanup_is_deferred(self):
        for edge in ('spawn','cleanup'):
            cancelled=[False]; spawn=subprocess.Popen; kill=os.killpg
            def starting(*args,**kwargs):
                child=spawn(*args,**kwargs)
                if edge=='spawn':
                    self.assertTrue(p.CRITICAL); cancelled[0]=True
                return child
            def ending(*args):
                if edge=='cleanup':
                    self.assertTrue(p.CRITICAL); cancelled[0]=True
                return kill(*args)
            c.START=c.time.monotonic()
            with patch.object(p.subprocess,'Popen',side_effect=starting),patch.object(p.os,'killpg',side_effect=ending):
                with self.assertRaises(c.Refuse):
                    with p.CommandOwnership(c,lambda:cancelled[0]) as owner:
                        c.run([sys.executable,'-I','-S','-B','-c','print("safe")'],timeout=2)
            self.assertTrue(owner.children[0].finished); self.assertFalse(p.CRITICAL)


class IntegrationTest(unittest.TestCase):
    def test_real_collector_orchestration_has_one_frozen_no_lock_simulation(self):
        calls=[]
        def command(argv,*args):
            calls.append(argv)
            if argv==['dpkg','--print-architecture']:return 0,b'amd64\n',b''
            if argv==['dpkg','--print-foreign-architectures']:return 0,b'',b''
            if argv[0]=='apt-get':return 100,b'Unable to resolve requested versions\n',b''
            raise AssertionError(argv)
        with patch.object(c,'run',side_effect=command),patch.object(c,'bounded_file',return_value=(b'ID=ubuntu\nVERSION_ID=24.04\n',None)),patch.object(c,'source_rows',return_value=[]),patch.object(c,'index_rows',return_value=[]),patch.object(c,'preferences',return_value=[]),patch.object(c,'config_value',return_value=None),patch.object(c,'package_state',return_value={}) as state,patch.object(c,'policy',return_value={}) as policy,patch.object(c,'script_metadata',return_value=[]),patch.object(c,'outside_mapping',return_value=dict(classification='UNKNOWN_REQUIRES_CONTRACT_DECISION',outside_count=0,reason='not_exactly_one_current_collector_mapping')):
            c.collect()
        for query in (state,policy):
            query.assert_called_once()
            self.assertTrue({'libssl3t64','openssl'} <= set(query.call_args.args[0]))
        apt=[args for args in calls if args[0]=='apt-get']
        self.assertEqual(apt,[['apt-get','-s','-o','Debug::NoLocking=1','-o','Dir::Cache::pkgcache=','-o','Dir::Cache::srcpkgcache=','install',*[n+'='+v for n,v in c.REQUEST]]])
        self.assertNotIn('update',apt[0]);self.assertNotIn('download',apt[0])
        target=json.loads((ROOT/r.TARGET_PATH).read_bytes())
        self.assertEqual(len(target['request']),21)
        self.assertFalse({'systemd','systemd-sysv','udev','ruby-psych','docker-ce','docker.io'} & {n for n,_ in c.REQUEST})
        self.assertEqual(len(c.KNOWN_DEPENDENCIES),14)
        self.assertFalse(set(c.KNOWN_DEPENDENCIES)&{n for n,_ in c.REQUEST})

    def test_valid_main_output_after_cleanup_and_no_second_capture(self):
        events=[]
        @contextmanager
        def pin(env):
            with tempfile.TemporaryFile() as file:
                yield file.fileno(),'/synthetic/pin'
            events.append('cleanup')
        def capture(argv,payload,**kwargs):
            events.append('ssh')
            key=payload[:32];size=struct.unpack('!I',payload[32:36])[0]
            control=json.loads(payload[36:36+size]);bundle=payload[36+size:]
            self.assertEqual(hashlib.sha256(bundle).hexdigest(),control['sha256'])
            self.assertEqual(set(json.loads(bundle)),{Path(x).name for x in r.REMOTE_SOURCES})
            with patch.object(c,'collect',return_value=observation()):
                body,code=p.collect(c,control['identity'],control['challenge'])
            return r.CaptureResult(code,frame(body,key))
        transport=types.SimpleNamespace(pinned_hosts=pin,capture_result=capture,_capture_cancellation=None)
        with patch.object(r,'snapshot',return_value=sources()),patch.object(r,'load_transport',return_value=transport),patch.object(r,'validate_target'),patch.object(r.signal,'signal'):
            line,code=r.main(environment(),[],[False])
        self.assertEqual(code,0);self.assertIn('OBSERVED',line);self.assertEqual(events,['ssh','cleanup'])

    def test_rerun_and_arbitrary_arguments_never_load_transport(self):
        for env,args in (({**environment(),'GITHUB_RUN_ATTEMPT':'2'},[]),(environment(),['--script','evil.py']),(environment(),['--package','ruby=1'])):
            with patch.object(r,'load_transport') as loader,patch.object(r,'snapshot') as snapshot:
                line,code=r.main(env,args,[False])
                self.assertEqual(code,1);loader.assert_not_called();snapshot.assert_not_called()

    def test_populated_nested_evidence_and_unknown_fields(self):
        value=observation()
        value['sources']=[dict(file='ubuntu.sources',uris=[c.safe_url('https://user:password@mirror.example/private?token=abc')],suites=['noble'],components=['main'],architectures=['amd64'],signed_by='unresolved',enabled=True,trusted_override=None,valid_until_override=None)]
        value['indexes']=[dict(file='archive_InRelease',size=100,mtime_ns=1,sha256='1'*64,origin='Ubuntu',amd64_package_refs=[dict(path='main/binary-amd64/Packages',sha256='2'*64,size=12)],signature=dict(status='VERIFIED_WITH_CONFIGURED_PUBLIC_KEY',fingerprint='A'*40))]
        value['preferences']=[{'file':'pin','Package':'ruby','Pin':'version 1:3.2*','Pin-Priority':'500'}]
        value['package_state']={'ruby':dict(version='1:3.2~ubuntu1',architecture='all',status_abbrev='ii ',essential='no',priority='optional',held=False)}
        value['apt_policy']={'ruby':dict(installed='1:3.2~ubuntu1',candidate='1:3.2~ubuntu1',versions=[dict(version='1:3.2~ubuntu1',priority=500,origins=[dict(priority=500,uri=c.safe_url('https://archive.ubuntu.com/ubuntu'),suite_component='noble/main',architecture_index='amd64')])])}
        p.evidence(value,c)
        for key in ('sources','indexes','preferences'):
            broken=copy.deepcopy(value);broken[key][0]['unexpected']='PRIVATE'
            with self.assertRaises(ValueError):p.evidence(broken,c)
        self.assertNotIn('password',p.canonical(value).decode());self.assertNotIn('token=abc',p.canonical(value).decode())

    def test_cleanup_failure_cannot_emit_authenticated_evidence(self):
        identity=r.invocation_identity(environment(),sources())
        with patch.object(c,'collect',return_value=observation()),patch.object(p.CommandOwnership,'__exit__',side_effect=c.Refuse('READ_ONLY_COLLECTION_FAILED')):
            body,code=p.collect(c,identity,'c'*64)
        self.assertEqual(code,1);self.assertIn(b'UNAVAILABLE',body);self.assertNotIn(b'OBSERVED',body)
        # This injected __exit__ did not perform the real restoration.
        p.CURRENT_OWNER.__exit__()


if __name__=='__main__': unittest.main()
