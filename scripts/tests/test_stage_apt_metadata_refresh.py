#!/usr/bin/env python3
"""CLB-175 local synthetic tests: never invoke SSH, sudo or APT."""
import ast
import base64
import copy
import hashlib
import hmac
import importlib.util
import json
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import types
import unittest
from contextlib import contextmanager
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
PATH = ROOT / 'scripts/deploy/stage-apt-metadata-refresh.py'
spec = importlib.util.spec_from_file_location('refresh', PATH)
r = importlib.util.module_from_spec(spec)
spec.loader.exec_module(r)
p = r.load_primitives()
wrapper = (ROOT / r.WRAPPER).read_text()
python = wrapper.split("<<'PY'\n", 1)[1].rsplit('\nPY\n', 1)[0]
tree = ast.parse(python)
tree.body = [n for n in tree.body if not isinstance(n, (ast.For, ast.Raise))]
w = types.ModuleType('wrapper_test')
exec(compile(tree, '<wrapper-test>', 'exec'), w.__dict__)


def env():
    return dict(GITHUB_REPOSITORY='koteev-m/clubs_bot', GITHUB_EVENT_NAME='workflow_dispatch',
        GITHUB_REF='refs/heads/main', GITHUB_REF_TYPE='branch', REPOSITORY_DEFAULT_BRANCH='main',
        APP_ENV='stage', CONFIRMATION=r.CONFIRMATION, GITHUB_RUN_ATTEMPT='1', GITHUB_RUN_ID='123',
        GITHUB_SHA='a'*40, GITHUB_WORKFLOW_SHA='a'*40,
        GITHUB_WORKFLOW_REF='koteev-m/clubs_bot/'+r.WORKFLOW+'@refs/heads/main')


class RequestTest(unittest.TestCase):
    def test_main_single_transport_failure_never_retries(self):
        @contextmanager
        def hosts(unused):
            yield 1, '/private-pin'
        capture=unittest.mock.Mock(return_value=p.CaptureResult(255,b''))
        transport=types.SimpleNamespace(pinned_hosts=hosts,capture_result=capture,
                                        _capture_cancellation=None)
        sources={name:(ROOT/name).read_bytes() for name in p.LOCAL_SOURCES}
        for name in (r.RUNNER,r.WRAPPER,r.WORKFLOW): sources[name]=(ROOT/name).read_bytes()
        flags=types.SimpleNamespace(isolated=True,no_site=True)
        with patch.object(r.sys,'flags',flags), patch.object(r.sys,'dont_write_bytecode',True), \
             patch.object(r,'load_primitives',return_value=p), \
             patch.object(r,'snapshot',return_value=sources), \
             patch.object(p,'load_transport',return_value=transport), \
             patch.object(p,'validate_target'), patch.object(r.signal,'signal'):
            body,code=r.main(dict(env(),SSH_AUTH_SOCK='/fixture-agent',SSH_USER='deploy',
                                 SSH_HOST='178.20.209.5',SSH_PORT='22'),[])
        self.assertEqual(code,1)
        self.assertEqual(body['reason'],'TRANSPORT_OR_UNKNOWN_OUTCOME')
        self.assertEqual(capture.call_count,1)
        argv,payload=capture.call_args.args
        self.assertEqual(len(payload),32)
        self.assertEqual(argv[0],'/usr/bin/ssh')

    def test_snapshot_binds_new_git_modes_and_bytes(self):
        sources={name:(ROOT/name).read_bytes() for name in p.LOCAL_SOURCES}
        def capture(argv,**unused):
            path=argv[-1].split(':',1)[-1]
            if 'ls-tree' in argv:
                raw=b'100644 blob '+b'a'*40+b'\t'+path.encode()+b'\x00'
            else:
                raw=(ROOT/path).read_bytes()
            return p.CaptureResult(0,raw)
        with patch.object(p,'snapshot',return_value=sources),patch.object(p,'capture_result',side_effect=capture):
            actual=r.snapshot(p,env(),[False])
        self.assertEqual(actual[r.WRAPPER],(ROOT/r.WRAPPER).read_bytes())
        with patch.object(p,'snapshot',return_value=sources), \
             patch.object(p,'capture_result',return_value=p.CaptureResult(0,b'120000 blob '+b'a'*40+b'\tx\x00')):
            with self.assertRaises(ValueError): r.snapshot(p,env(),[False])

    def test_valid(self):
        r.validate(env())

    def test_each_request_boundary(self):
        for key in env():
            with self.subTest(key=key), self.assertRaises(ValueError):
                bad = env(); bad[key] = 'invalid'; r.validate(bad)

    def test_reruns_and_other_branches(self):
        for changes in ({'GITHUB_RUN_ATTEMPT': '2'}, {'GITHUB_REF': 'refs/heads/task'},
                        {'GITHUB_EVENT_NAME': 'push'}, {'CONFIRMATION': 'apt update'}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                r.validate(dict(env(), **changes))

    def test_dependencies_frozen(self):
        sources = {name: (ROOT/name).read_bytes() for name in p.LOCAL_SOURCES}
        p.verify_sources(sources)
        self.assertEqual(hashlib.sha256((ROOT/r.WRAPPER).read_bytes()).hexdigest(), r.WRAPPER_SHA256)
        self.assertEqual(hashlib.sha256((ROOT/r.PACKAGE_RUNNER).read_bytes()).hexdigest(), r.PACKAGE_RUNNER_SHA256)

    def test_wrong_package_runner_pin_refuses_before_loading(self):
        with patch.object(r, 'PACKAGE_RUNNER_SHA256', '0' * 64), self.assertRaises(ValueError):
            r.load_primitives()

    def test_target_user_host_pin(self):
        # Existing validator also refuses root, alternate host/port and bad pin.
        for user, host, port in [('root','178.20.209.5','22'), ('deploy','evil','22'),
                                 ('deploy','178.20.209.5','2222')]:
            with self.assertRaises(ValueError):
                p.validate_target(dict(SSH_USER=user, SSH_HOST=host, SSH_PORT=port))

    def test_transport_single_fixed_command(self):
        args = r.ssh_argv(p, dict(SSH_USER='deploy', SSH_HOST='178.20.209.5', SSH_PORT='22'), '/proc/1/fd/3')
        self.assertEqual(args[0], '/usr/bin/ssh')
        for value in ('BatchMode=yes', 'StrictHostKeyChecking=yes', 'ConnectionAttempts=1',
                      'ProxyCommand=none', 'ProxyJump=none', 'PermitLocalCommand=no'):
            self.assertIn(value, args)
        self.assertIn('&& LC_ALL=C LANG=C exec /usr/bin/python3 -I -S -B -c', args[-1])
        self.assertEqual(shlex.split(args[-1].split(' exec ', 1)[1])[-1], r.BOOTSTRAP)
        self.assertIn("['/usr/bin/sudo', '-n', '--', path]", r.BOOTSTRAP)
        self.assertNotIn('apt-get', r.BOOTSTRAP)

    def test_bad_wrapper_refuses_before_sudo(self):
        # Exercise actual bootstrap pre-elevation path, not a string assertion.
        for variant in ('missing', 'symlink', 'mode', 'hash', 'owner', 'group'):
            with tempfile.TemporaryDirectory() as directory:
                target = Path(directory)/'wrapper'
                if variant != 'missing':
                    target.write_text('wrong')
                    target.chmod(0o644 if variant == 'mode' else 0o755)
                if variant == 'symlink':
                    link = Path(directory)/'link'; link.symlink_to(target); target = link
                code = r.BOOTSTRAP.replace(repr(r.INSTALL), repr(str(target)))
                # Parent metadata is separately checked in the actual code;
                # mock only those root-owned dirs to reach the file boundary.
                code = code.replace("('/usr', '/usr/local', '/usr/local/sbin', path)", '(path,)')
                injected = '''
from types import SimpleNamespace
real_lstat = os.lstat
def fake_lstat(name):
    s = real_lstat(name)
    return SimpleNamespace(st_uid=UID, st_gid=GID, st_mode=s.st_mode)
os.lstat = fake_lstat
'''.replace('UID', '1000' if variant == 'owner' else '0').replace('GID', '1000' if variant == 'group' else '0')
                code = code.replace('key = sys.stdin', injected+'\nkey = sys.stdin')
                done = subprocess.run(['/usr/bin/env', 'python3', '-I','-S','-B','-c',code],
                                      input=b'k'*32, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                self.assertNotEqual(done.returncode, 0)
                self.assertEqual(done.stdout, b'')


class EvidenceTest(unittest.TestCase):
    def body(self):
        manifest=dict(sha256='a'*64,files=1,bytes=10,latest_mtime_ns=1)
        return dict(schema='clb175-apt-refresh-v1', wrapper_sha256=r.WRAPPER_SHA256,
            result='REFRESHED', reason='METADATA_REFRESH_COMPLETED',started_ns=1,ended_ns=2,
            operation_sha256='a'*64,attempts=1, apt_exit_status=0,
            diagnostics={'failure':None,'sha256':'a'*64,'bytes':10},
            dpkg_before=manifest,dpkg_after=manifest,lists_before=manifest,lists_after=manifest,
            config_before=manifest,config_after=manifest,
            extended_state_before='ABSENT',extended_state_after='ABSENT',
            status_before='a'*64, status_after='a'*64)

    def frame(self, body, key=b'k'*32):
        raw = json.dumps(body).encode()+b'\n'
        return b'clb175-auth:v=1 '+hmac.new(key,raw,hashlib.sha256).hexdigest().encode()+b' '+raw

    def test_authentic_result(self):
        self.assertEqual(r.parse(self.frame(self.body()),0,b'k'*32), self.body())

    def test_bad_auth_or_replayed_key(self):
        with self.assertRaises(ValueError): r.parse(self.frame(self.body()),0,b'x'*32)
        with self.assertRaises(ValueError): r.parse(self.frame(self.body())+b'junk',0,b'k'*32)

    def test_extra_fields_and_nonsecret_schema_only(self):
        for field,value in [('secret','credential-url'),('reason','unredacted error'),('attempts',True)]:
            body=self.body();body[field]=value
            with self.assertRaises(ValueError): r.parse(self.frame(body),0,b'k'*32)

    def test_success_requires_package_invariants_and_zero_exit(self):
        for field, value in [('dpkg_after',{}),('status_after','b'*64),('apt_exit_status',100),
                             ('attempts',0),('diagnostics',{'failure':'TIMEOUT'})]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                body=self.body(); body[field]=value; r.parse(self.frame(body),0,b'k'*32)


class WrapperTest(unittest.TestCase):
    def test_root_refuses_without_update(self):
        with patch.object(w.os,'geteuid',return_value=1000), patch.object(w,'capture') as c:
            with patch('builtins.print'):
                self.assertEqual(w.main(),1)
            c.assert_not_called()

    def test_argument_refusal_actual_shell(self):
        result=subprocess.run(['/bin/sh',str(ROOT/r.WRAPPER),'update'],capture_output=True)
        self.assertEqual(result.returncode,64)
        self.assertEqual(result.stdout,b'')

    def test_fixed_operation_and_policy(self):
        self.assertEqual(w.OPERATION,['/usr/bin/apt-get','update'])
        for setting in (b'Acquire::Retries "0";', b'APT::List-Cleanup "false";',
                        b'APT::Get::List-Cleanup "false";',b'Dir::Cache::pkgcache "";',
                        b'Dir::Cache::srcpkgcache "";', b'APT::Update::Error-Mode "any";'):
            self.assertIn(setting,w.CONFIG)
        self.assertEqual(set(w.ENV),{'PATH','HOME','LANG','LC_ALL','DEBIAN_FRONTEND'})
        self.assertIn('/usr/bin/env -i', wrapper)
        self.assertEqual(wrapper.count('capture(OPERATION'),1)
        calls=[n for n in ast.walk(ast.parse(python)) if isinstance(n,ast.Call)]
        for n in calls:
            if isinstance(n.func,ast.Attribute):
                self.assertNotIn(n.func.attr,('system','unlink','rmdir','rename','remove'))
        self.assertNotIn('shell=True',python)

    def test_manifest_detects_contents_without_exposing_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            target=Path(directory)/'credential-bearing-source'
            target.write_text('before')
            before=w.manifest(directory)
            target.write_text('after')
            after=w.manifest(directory)
            self.assertNotEqual(before['sha256'],after['sha256'])
            self.assertNotIn('credential-bearing',json.dumps(after))

    def test_hook_config_is_excluded_before_parts_loading(self):
        self.assertTrue(w.CONFIG.startswith(b'Dir::Etc::parts "/dev/null";\nDir::Etc::main "/dev/null";'))
        self.assertIn('os.MFD_ALLOW_SEALING',python)
        self.assertIn('fcntl.F_SEAL_WRITE',python)
        for marker in ('::pre-invoke','::post-invoke','::auth-failure','proxy-auto-detect','binary::'):
            self.assertIn(marker,python)


class WorkflowTest(unittest.TestCase):
    def test_sudoers_digest_no_arguments_and_visudo(self):
        template=(ROOT/'scripts/deploy/stage-apt-metadata-refresh.sudoers.in').read_text()
        lines=[line for line in template.splitlines() if line and not line.startswith('#')]
        self.assertEqual(lines, ['@STAGE_PRINCIPAL@ 178.20.209.5 = (root:root) NOPASSWD: NOSETENV: sha256:'
                                +r.WRAPPER_SHA256+' '+r.INSTALL+' ""'])
        # This validates a private fixture, never the machine's installed policy.
        if Path('/usr/sbin/visudo').exists():
            with tempfile.NamedTemporaryFile(mode='w') as fixture:
                fixture.write(template.replace('@STAGE_PRINCIPAL@','clb175_fixture'))
                fixture.flush()
                done=subprocess.run(['/usr/sbin/visudo','-c','-f',fixture.name],capture_output=True)
                self.assertEqual(done.returncode,0,done.stderr.decode())

    def test_exact_validator_rejects_capability_mutations(self):
        ruby = r'''
require 'yaml'
require File.expand_path('scripts/validate-workflow-capabilities', Dir.pwd)
w = YAML.safe_load(File.read(StageAptMetadataRefreshWorkflow::PATH))
mutations = [
 ->(x){x['on']['push']={}},
 ->(x){x['on']['workflow_dispatch']['inputs']['command']={'type'=>'string'}},
 ->(x){x['permissions']['contents']='write'},
 ->(x){x['concurrency']['cancel-in-progress']=true},
 ->(x){x['concurrency']['group']='other'},
 ->(x){x['jobs']['refresh'].delete('needs')},
 ->(x){x['jobs']['refresh'].delete('environment')},
 ->(x){x['jobs']['validate']['environment']='stage'},
 ->(x){x['jobs']['refresh']['steps'].delete_at(1)},
 ->(x){x['jobs']['refresh']['steps'][-1]['run']='sudo apt update'},
 ->(x){x['jobs']['collect']={}},
 ->(x){x['jobs']['refresh']['steps'][2]['with']['ssh-private-key']='${{ secrets.ROOT_KEY }}'}
]
StageAptMetadataRefreshWorkflow.validate(WorkflowCapabilityPolicy,w)
mutations.each do |m|
 x=Marshal.load(Marshal.dump(w)); m.call(x)
 begin
  StageAptMetadataRefreshWorkflow.validate(WorkflowCapabilityPolicy,x)
  abort 'mutation accepted'
 rescue SystemExit => e
  raise if e.status == 0
 end
end
puts "12 workflow mutations refused"
'''
        done=subprocess.run(['ruby','-e',ruby],cwd=ROOT,capture_output=True,text=True)
        self.assertEqual(done.returncode,0,done.stderr)
        self.assertIn('12 workflow mutations refused',done.stdout)


if __name__=='__main__': unittest.main()
