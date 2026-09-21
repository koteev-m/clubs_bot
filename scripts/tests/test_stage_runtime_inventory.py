#!/usr/bin/env python3
"""Bounded inventory: synthetic files, non-secret Linux system reads, no server."""
import base64
import hashlib
import hmac
import importlib.util
import json
import os
from pathlib import Path
import pwd
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


def load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT/'scripts/deploy'/name)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


runner = load('stage-runtime-inventory.py')
operation = load('stage-runtime-inventory-operation.py')


import errno
import stat
import types
from contextlib import contextmanager
operation.REFERENCE = json.loads((ROOT/runner.REFERENCE_PATH).read_bytes())

def fixture_parent():
    # Same supported safe-temp selection as the existing local source fixtures.
    # The production pin guard still rejects unsafe ancestors; no guard is relaxed.
    parent = Path(os.environ.get('RUNNER_TEMP') or tempfile.gettempdir()).resolve()
    if parent.is_relative_to(ROOT):
        raise AssertionError('disposable fixtures must be outside the checkout')
    return str(parent)


class SourceTest(unittest.TestCase):
    def setUp(self):
        parent = fixture_parent()
        self.temp = tempfile.TemporaryDirectory(dir=parent, prefix='clb91-semantic-source-')
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        self.assertFalse(self.repo.is_relative_to(ROOT))
        self.env = dict(PATH=os.environ.get('PATH', os.defpath), HOME=str(self.repo), LC_ALL='C',
                        GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_SYSTEM=os.devnull,
                        GIT_TERMINAL_PROMPT='0', GIT_AUTHOR_NAME='Synthetic', GIT_AUTHOR_EMAIL='fixture@example.invalid',
                        GIT_COMMITTER_NAME='Synthetic', GIT_COMMITTER_EMAIL='fixture@example.invalid')
        for path in (*runner.LOCAL_SOURCES, runner.REMOTE_PATH):
            destination = self.repo/path
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes((ROOT/path).read_bytes())
        def git(*args):
            return subprocess.check_output(['git', '-C', str(self.repo), *args], env=self.env, stderr=subprocess.DEVNULL)
        git('-c', 'init.templateDir=', 'init', '-q')
        git('config', 'core.hooksPath', os.devnull)
        git('add', 'scripts')
        git('commit', '-q', '-m', 'synthetic verified source closure')
        self.env['GITHUB_SHA'] = git('rev-parse', 'HEAD').decode().strip()

    def test_each_local_source_canary_and_incomplete_snapshot_precede_execution(self):
        marker = self.repo/'canary'
        with patch.object(runner, 'ROOT', self.repo):
            for path in runner.LOCAL_SOURCES:
                original = (self.repo/path).read_bytes()
                (self.repo/path).write_bytes(('open('+repr(str(marker))+',"w").write("PRIVATE")\n').encode())
                try:
                    with self.subTest(path=path), patch.object(runner, 'exec', create=True) as execute:
                        with self.assertRaises(ValueError): runner.snapshot(self.env, [False])
                        execute.assert_not_called()
                        self.assertFalse(marker.exists())
                finally:
                    (self.repo/path).write_bytes(original)
            sources = runner.snapshot(self.env, [False])
            for path in sources:
                broken = dict(sources); del broken[path]
                with self.subTest(missing=path), patch.object(runner, 'exec', create=True) as execute:
                    with self.assertRaises(ValueError): runner.load_transport(broken, [False])
                    execute.assert_not_called()
            with patch.object(runner, 'exec', create=True) as execute:
                with self.assertRaises(InterruptedError): runner.load_transport(sources, [True])
                execute.assert_not_called()

    def test_captured_dependencies_survive_path_replacement_new_snapshot_refuses(self):
        with patch.object(runner, 'ROOT', self.repo):
            sources = runner.snapshot(self.env, [False])
            marker = self.repo/'canary'
            for path in runner.LOCAL_SOURCES:
                (self.repo/path).write_bytes(('open('+repr(str(marker))+',"w").write("PRIVATE")\n').encode())
            transport = runner.load_transport(sources, [False])
            result = transport.capture_result([sys.executable, '-I', '-S', '-B', '-c', 'print("captured")'],
                env={'PATH':os.defpath}, limit=64, timeout=5)
            self.assertEqual((result.code, result.output, result.failure), (0,b'captured\n',None))
            self.assertFalse(marker.exists())
            with self.assertRaises(ValueError): runner.snapshot(self.env, [False])

    def test_request_replay_and_old_diagnostic_confirmation_refuse(self):
        env = dict(self.env, GITHUB_REPOSITORY='koteev-m/clubs_bot', GITHUB_EVENT_NAME='workflow_dispatch',
            GITHUB_REF='refs/heads/main', GITHUB_REF_TYPE='branch', REPOSITORY_DEFAULT_BRANCH='main',
            APP_ENV='stage', CONFIRMATION=runner.CONFIRMATION, GITHUB_RUN_ATTEMPT='1',
            GITHUB_WORKFLOW_REF='koteev-m/clubs_bot/'+runner.WORKFLOW_PATH+'@refs/heads/main')
        runner.validate(env)
        for key, value in (('GITHUB_RUN_ATTEMPT','2'),('GITHUB_EVENT_NAME','push'),
                           ('CONFIRMATION','CLB-91:35206468948:diagnose-compose-subset'),
                           ('GITHUB_REF','refs/heads/other'),('APP_ENV','prod')):
            with self.assertRaises(ValueError): runner.validate(dict(env, **{key:value}))


@unittest.skipUnless(sys.platform == 'linux', 'real Linux system interfaces required')
class BootstrapTest(unittest.TestCase):
    def setUp(self):
        self.sources = {Path(path).name: (ROOT/path).read_bytes() for path in runner.REMOTE_SOURCES}
        self.nonce = bytes(range(32))

    def invoke(self, suffix=b'', *, sources=None):
        sources = dict(sources or self.sources)
        sources['stage-runtime-inventory-operation.py'] += suffix
        bundle = json.dumps({k: base64.b64encode(v).decode() for k,v in sources.items()},
                            sort_keys=True, separators=(',', ':')).encode()
        control = json.dumps(dict(principal=pwd.getpwuid(os.getuid()).pw_name,
                                sha256=hashlib.sha256(bundle).hexdigest())).encode()
        payload = self.nonce + struct.pack('!I', len(control)) + control + bundle
        result = subprocess.run(['/usr/bin/python3', '-I', '-S', '-B', '-c', runner.BOOTSTRAP],
            input=payload, capture_output=True, timeout=110,
            env=dict(PATH='/usr/bin:/bin', LC_ALL='C'))
        self.assertEqual(result.stderr, b'')
        self.assertNotIn(b'PRIVATE', result.stdout)
        return runner.parse_result(result.stdout, result.returncode, self.nonce, operation)

    def through_runner(self, suffix):
        # Real local Git snapshot/whole-closure load/pin setup and one substitute
        # SSH executable; the subprocess executes the actual production bootstrap.
        parent = fixture_parent()
        self.assertFalse(os.statvfs(parent).f_flag & os.ST_NOEXEC,
                         'synthetic executable fixture needs an executable local test mount')
        with tempfile.TemporaryDirectory(dir=parent) as directory:
            root = Path(directory); repo = root/'repo'; repo.mkdir()
            bin_dir = root/'bin'; bin_dir.mkdir()
            calls = root/'submissions'
            env = dict(PATH='/usr/bin:/bin', HOME=directory, TMPDIR=directory, LC_ALL='C',
                GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_SYSTEM=os.devnull, GIT_TERMINAL_PROMPT='0',
                GIT_AUTHOR_NAME='Synthetic', GIT_AUTHOR_EMAIL='fixture@example.invalid',
                GIT_COMMITTER_NAME='Synthetic', GIT_COMMITTER_EMAIL='fixture@example.invalid')
            for path in (*runner.LOCAL_SOURCES, runner.REMOTE_PATH):
                target = repo/path; target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((ROOT/path).read_bytes() + (suffix if path == runner.REMOTE_PATH else b''))
            def git(*args):
                return subprocess.check_output(['git','-C',str(repo),*args], env=env, stderr=subprocess.DEVNULL)
            git('-c','init.templateDir=','init','-q'); git('config','core.hooksPath',os.devnull)
            git('add','scripts'); git('commit','-q','-m','synthetic remote capture fixture')
            sha = git('rev-parse','HEAD').decode().strip()
            subprocess.run(['ssh-keygen','-q','-t','ed25519','-N','','-f',str(root/'key')],
                           env=env,capture_output=True,check=True,timeout=10)
            key = (root/'key.pub').read_text().split()
            executable = bin_dir/'ssh'
            executable.write_text('#!/usr/bin/env -S /usr/bin/python3 -I -S -B\n'
                'import os,sys,shlex\n'
                'assert sys.flags.isolated and sys.flags.no_site\n'
                'with open('+repr(str(calls))+',"ab") as stream: stream.write(b"invoked\\n")\n'
                'command=shlex.split(sys.argv[-1])\n'
                'assert command[-6:-1]==["python3","-I","-S","-B","-c"]\n'
                'os.execve("/usr/bin/python3",["python3","-I","-S","-B","-c",command[-1]],'
                '{"PATH":"/usr/bin:/bin","LC_ALL":"C"})\n')
            executable.chmod(0o700)
            env.update(PATH=str(bin_dir)+':/usr/bin:/bin', GITHUB_SHA=sha,
                GITHUB_REPOSITORY='koteev-m/clubs_bot', GITHUB_EVENT_NAME='workflow_dispatch',
                GITHUB_REF='refs/heads/main', GITHUB_REF_TYPE='branch', REPOSITORY_DEFAULT_BRANCH='main',
                APP_ENV='stage', CONFIRMATION=runner.CONFIRMATION, GITHUB_RUN_ATTEMPT='1',
                GITHUB_WORKFLOW_REF='koteev-m/clubs_bot/'+runner.WORKFLOW_PATH+'@refs/heads/main',
                SSH_USER=pwd.getpwuid(os.getuid()).pw_name, SSH_HOST='fixture.invalid', SSH_PORT='22',
                SSH_KNOWN_HOSTS='fixture.invalid '+' '.join(key[:2])+'\n')
            result = subprocess.run(['/usr/bin/python3','-I','-S','-B',str(repo/runner.RUNNER_PATH)],
                                    env=env,capture_output=True,timeout=120)
            self.assertEqual(result.stderr,b'')
            self.assertTrue(calls.exists(), (result.returncode, result.stdout))
            self.assertEqual(calls.read_bytes(),b'invoked\n')
            self.assertNotIn(b'PRIVATE',result.stdout)
            return result.stdout.decode().strip(),result.returncode

    def test_real_linux_bootstrap_profile_no_target_reads_or_tools(self):
        # Audit the actual collector, with the real immutable Linux filesystem.
        suffix = b'''
def audit(event, args):
    if event in ('subprocess.Popen','os.system','os.exec','socket.connect','os.mkdir','os.chmod','os.chown','os.remove','os.rename'):
        raise RuntimeError('PRIVATE forbidden effect')
    if event == 'open':
        path, mode, flags = args
        if flags & (os.O_WRONLY|os.O_RDWR|os.O_CREAT|os.O_TRUNC|os.O_APPEND):
            raise RuntimeError('PRIVATE write')
        if isinstance(path,str) and path.startswith(('/opt/','/home/','/root/','/run/','/var/run/')):
            raise RuntimeError('PRIVATE forbidden read')
original_collect=collect
def collect(cancelled):
    sys.addaudithook(audit)
    return original_collect(cancelled)
'''
        line, code = self.invoke(suffix)
        self.assertEqual(code, 0)
        value = json.loads(line[len(operation.PREFIX):])
        self.assertEqual(value['bootstrap']['os_family'], 'linux')
        self.assertTrue(all(value['bootstrap'][key] for key in ('isolated','no_site','no_user_site','bytecode_disabled')))
        self.assertEqual(value['artifacts']['python_bootstrap']['status'], 'observed')
        self.assertEqual(value['distro']['status'], 'observed')
        self.assertEqual(value['trust'], 'observed_not_approved')
        # Generic selfcheck may provide only safe TMPDIR, not GitHub RUNNER_TEMP.
        safe_parent = fixture_parent()
        with patch.dict(os.environ, TMPDIR=safe_parent), patch.object(tempfile, 'tempdir', None):
            os.environ.pop('RUNNER_TEMP', None)
            self.assertEqual(self.through_runner(suffix), (line, code))

    def test_synthetic_profile_actual_bootstrap_hmac_consumer_and_cleanup(self):
        from runtime_inventory_fixtures import make_fixture
        with tempfile.TemporaryDirectory(dir=fixture_parent()) as directory:
            make_fixture(directory)
            fixture_source = (ROOT/'scripts/tests/runtime_inventory_fixtures.py').read_bytes()
            # Test-only verified source suffix. No production path can select it.
            suffix = b'\nfixture=__import__("types").ModuleType("fixture")\nexec('+repr(fixture_source).encode()+b',fixture.__dict__)\nos=fixture.FixtureOS('+repr(directory).encode()+b')\n'
            line,code = self.invoke(suffix)
            self.assertEqual(code,0)
            self.assertEqual(self.through_runner(suffix),(line,code))
            self.assertEqual(json.loads(line[len(operation.PREFIX):])['packages']['python3']['architecture'],'amd64')
            for extra, reason in ((b'os.close_failure=True\n','cleanup'),
                    (b"os.read=lambda *a: (_ for _ in ()).throw(OSError(5,'PRIVATE IO'))\n",'io'),
                    (b"os.read=lambda *a: (_ for _ in ()).throw(InterruptedError('PRIVATE signal'))\n",'interrupted')):
                expected = operation.refused(reason).decode().strip(),1
                self.assertEqual(self.invoke(suffix+extra),expected)
                self.assertEqual(self.through_runner(suffix+extra),expected)

    def test_source_control_and_final_cancellation(self):
        broken = dict(self.sources)
        del broken[Path(runner.REFERENCE_PATH).name]
        with self.assertRaises(ValueError): self.invoke(sources=broken)
        suffix = b'\ndef collect(cancelled):\n    signal=__import__("signal")\n    os.kill(os.getpid(), signal.SIGTERM)\n'
        with self.assertRaises(ValueError): self.invoke(suffix)
        # Handoff just after cleanup must not publish a successful observation.
        suffix = b'\noriginal=collect\ndef collect(cancelled):\n    body=original(cancelled)\n    __import__("signal").pthread_sigmask(__import__("signal").SIG_BLOCK, [__import__("signal").SIGTERM])\n    os.kill(os.getpid(), __import__("signal").SIGTERM)\n    return body\n'
        self.assertEqual(self.invoke(suffix),(operation.refused('interrupted').decode().strip(),1))


class FixtureCase(unittest.TestCase):
    def setUp(self):
        from runtime_inventory_fixtures import FixtureOS, make_fixture
        self.temp = tempfile.TemporaryDirectory(dir=fixture_parent())
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.put = make_fixture(self.root)
        self.files = FixtureOS(self.root)
        self.addCleanup(lambda: self.assertEqual(self.files.owned, set()))
        self.addCleanup(lambda: None)

    def collect(self, cancelled=lambda: False):
        with patch.object(operation,'os',self.files):
            body=operation.collect(cancelled)
        self.assertNotIn(b'PRIVATE',body)
        self.assertNotIn(str(self.root).encode(),body)
        code=1 if b'"result":"unavailable"' in body else 0
        operation.parse_body(body,code)
        self.assertEqual(self.files.owned,set())
        return json.loads(body[len(operation.PREFIX):]),code


class InventoryTest(FixtureCase):
    def test_useful_different_reference_observations_and_fixed_sources(self):
        with patch.object(operation,'bootstrap_info',return_value=dict(source='bootstrap_memory',os_family='linux',architecture='x86_64',bits=64,implementation='cpython',version='3.11.9',isolated=True,no_site=True,no_user_site=True,bytecode_disabled=True)):
            value,code=self.collect()
        self.assertEqual(code,0)
        self.assertEqual(value['bootstrap']['architecture'],'x86_64')
        self.assertEqual(value['artifacts']['python_system']['reference'],'different_digest')
        self.assertEqual(value['artifacts']['python_system']['layout'],'symlink')
        self.assertEqual(value['artifacts']['compose_system']['status'],'missing')
        self.assertEqual(value['packages']['ruby']['version'],'3.2.2-1')
        self.assertEqual(value['packages']['ruby-psych']['status'],'missing')
        self.assertEqual(value['distro']['id'],'ubuntu')
        self.assertEqual(value['distro']['source'],'etc_os_release')
        self.assertEqual(value['trust'],'observed_not_approved')
        self.assertEqual(value['completeness'],'complete')

    def test_symlink_cannot_reach_forbidden_file_or_execute_tools(self):
        for target in ('/home/fixture/key','/opt/clubs-bot-stage/.env','/etc/shadow','/usr/bin/ruby3.2','/usr/bin/python3'):
            with self.subTest(target=target):
                p=self.root/'usr/bin/python3'; p.unlink(); p.symlink_to(target)
                value,code=self.collect()
                self.assertEqual(code,0)
                self.assertEqual(value['artifacts']['python_system']['reason'],'unsafe')
                self.assertNotIn('shadow',self.files.opens)
                self.assertNotIn('.env',self.files.opens)
        source=(ROOT/runner.REMOTE_PATH).read_text()
        for forbidden in ('subprocess','os.environ','os.system','os.exec','mkdir(', 'chmod(', 'chown('):
            self.assertNotIn(forbidden,source.replace('No subprocess','No child tools'))

    def test_unsafe_metadata_and_hardlink(self):
        p=self.root/'usr/local/bin/docker-compose'
        p.chmod(0o777)
        value,code=self.collect()
        self.assertEqual((code,value['artifacts']['compose_standalone']['reason']),(0,'unsafe'))
        p.chmod(0o755); os.link(p,self.root/'hardlink')
        value,code=self.collect()
        self.assertEqual(value['artifacts']['compose_standalone']['reason'],'unsafe')
        self.files.bad_owner=True
        value,code=self.collect()
        self.assertEqual(code,0)
        self.assertTrue(all(v['status']=='unknown' for v in value['artifacts'].values()))

    def test_metadata_canaries_duplicate_keys_invalid_utf8_and_bounds(self):
        cases=(b'ID=PRIVATE_CANARY\nVERSION_ID=1\n', b'ID=ubuntu\nID=debian\nVERSION_ID=1\n',b'\xff',b'X'*16385)
        for raw in cases:
            with self.subTest(size=len(raw)):
                self.put('/usr/lib/os-release',raw)
                value,code=self.collect()
                self.assertEqual(code,0)
                self.assertEqual(value['distro']['status'],'unknown')
                self.assertEqual(value['completeness'],'partial')
        for raw in (b'Package: ruby\nStatus: install ok installed\nVersion: 3.PRIVATE\nArchitecture: amd64\n',
                    b'Package: ruby\nPackage: ruby\n',b'\xff',b'x'*4194305):
            self.put('/var/lib/dpkg/status',raw)
            value,code=self.collect()
            self.assertEqual(code,0)
            self.assertEqual(value['packages']['ruby']['status'],'unknown')

    def test_io_permission_interruption_cleanup_and_redaction(self):
        original=self.files.read
        for error, reason in ((OSError(errno.EIO,'PRIVATE IO'),'io'),(InterruptedError('PRIVATE cancel'),'interrupted')):
            self.files.read=lambda *args: (_ for _ in ()).throw(error)
            self.assertEqual(self.collect(),({'result':'unavailable','reason':reason},1))
        self.files.read=original
        self.files.close_failure=True
        self.assertEqual(self.collect(),({'result':'unavailable','reason':'cleanup'},1))
        self.files.close_failure=False
        self.assertEqual(self.collect(lambda:True),({'result':'unavailable','reason':'interrupted'},1))
        originalopen=self.files.open
        def denied(path,*a,**kw):
            if path=='docker-compose': raise PermissionError(errno.EACCES,'PRIVATE path')
            return originalopen(path,*a,**kw)
        self.files.open=denied
        value,code=self.collect()
        self.assertEqual((code,value['artifacts']['compose_standalone']['reason']),(0,'permission'))

    def test_held_fd_path_replacement_inplace_and_missing_drift(self):
        for mode in ('replacement','inplace','missing'):
            from runtime_inventory_fixtures import FixtureOS
            self.files=FixtureOS(self.root)
            p=self.root/'usr/bin/python3.12'
            def drift():
                if mode=='replacement':
                    new=p.with_name('replacement'); new.write_bytes(b'other'); new.chmod(0o755); new.replace(p)
                elif mode=='inplace': p.write_bytes(b'changed in-place with distinct size')
                else: self.put('/usr/local/bin/python3',b'later',0o755)
            # Missing drift must occur after its missing observation, not before.
            if mode=='missing':
                old=operation.distro
                def late(files):
                    drift(); return old(files)
                with patch.object(operation,'distro',late): value,code=self.collect()
            else:
                self.files.after_read=drift
                value,code=self.collect()
            self.assertEqual((value,code),({'result':'unavailable','reason':'identity'},1),mode)

    def test_unexpected_proc_link_io_is_not_hidden(self):
        original=self.files.readlink
        for error,reason in ((OSError(errno.EIO,'PRIVATE'),'io'),(InterruptedError('PRIVATE'),'interrupted')):
            def bad(path,**kwargs):
                if path=='/proc/self/exe': raise error
                return original(path,**kwargs)
            self.files.readlink=bad
            self.assertEqual(self.collect(),({'result':'unavailable','reason':reason},1))


class ProtocolTest(FixtureCase):
    # Only setUp/collect are shared; do not duplicate the acquisition test matrix.
    def test_authenticated_schema_replay_unknown_fields_and_bounds(self):
        value,code=self.collect()
        nonce=bytes(range(32))
        def frame(raw): return b'clb91-runtime-inventory-auth:v=1 tag='+hmac.new(nonce,raw,hashlib.sha256).hexdigest().encode()+b' '+raw
        raw=operation.canonical(value)
        self.assertEqual(runner.parse_result(frame(raw),code,nonce,operation),(raw.decode().strip(),0))
        bad=[]
        for mutate in (lambda v:v.update(extra='PRIVATE'), lambda v:v['bootstrap'].update(architecture='PRIVATE'),
                       lambda v:v.update(completeness='complete' if v['completeness']=='partial' else 'partial'),lambda v:v['artifacts']['python_system'].update(sha256='PRIVATE'),
                       lambda v:v['packages']['ruby'].update(version='3.PRIVATE'),lambda v:v['bootstrap'].update(bits=True),
                       lambda v:v['artifacts']['compose_system'].update(status='observed')):
            v=json.loads(json.dumps(value)); mutate(v); bad.append(operation.canonical(v))
        bad += [raw+b'\n',raw.replace(b'{',b'{"result":"observed",',1),b'X'*8193,
                (operation.PREFIX+json.dumps(value)+'\n').encode()]
        for body in bad:
            with self.subTest(length=len(body)),self.assertRaises((ValueError,operation.Unavailable,TypeError)):
                runner.parse_result(frame(body),0,nonce,operation)
        for blob,exitcode,key in ((frame(raw),1,nonce),(frame(raw),0,b'x'*32),(b'startup\n'+frame(raw),0,nonce),
                                  (frame(raw)*2,0,nonce),(b'x'*12289,0,nonce)):
            with self.assertRaises((ValueError,operation.Unavailable)):
                runner.parse_result(blob,exitcode,key,operation)
        for reason in operation.REASONS:
            body=operation.refused(reason)
            self.assertEqual(runner.parse_result(frame(body),1,nonce,operation)[1],1)
        for row in value['artifacts'].values():
            row.update(status='observed',reason='none',placement='usr_local_plugin',layout='symlink',sha256='f'*64,reference='different_digest')
        for row in value['packages'].values():
            row.update(status='observed',reason='none',version='1'*64,architecture='ppc64el')
        value['completeness']='partial' if any(v=='unknown' for v in value['bootstrap'].values()) else 'complete'
        largest=operation.canonical(value)
        self.assertLessEqual(len(largest),operation.BODY_LIMIT)
        self.assertLessEqual(len(frame(largest)),runner.FRAME_LIMIT)
        operation.parse_body(largest,0)

    def test_distinct_authority_and_hardened_transport(self):
        env=dict(SSH_USER='fixture',SSH_HOST='fixture.invalid',SSH_PORT='22')
        self.assertNotEqual(runner.CONFIRMATION,'CLB-91:35371386455:private-env-semantic-dry-run')
        previous=load('stage-compose-env-semantic.py')
        self.assertEqual(runner.ssh_argv(env,'/proc/123/fd/4')[:-1],previous.ssh_argv(env,'/proc/123/fd/4')[:-1])
        self.assertEqual(set(runner.REMOTE_SOURCES),{runner.REMOTE_PATH,runner.REFERENCE_PATH})
        self.assertNotIn('semantic-operation.py',runner.BOOTSTRAP)

    def test_workflow_exact_authority_and_negative_mutations(self):
        script = '''require "validate-workflow-capabilities"
w,_=WorkflowCapabilityPolicy.load_workflow(Pathname.new(ARGV[0]),StageRuntimeInventoryWorkflow::PATH)
case ARGV[1]
when "input"; w["on"]["workflow_dispatch"]["inputs"]["path"]={"type"=>"string"}
when "trigger"; w["on"]["push"]={}
when "environment"; w["jobs"]["inventory"]["environment"]="prod"
when "concurrency"; w["concurrency"]["group"]="other"
when "cancel"; w["concurrency"]["cancel-in-progress"]=true
when "credentials"; s=w["jobs"]["inventory"]["steps"]; s[1],s[2]=s[2],s[1]
when "write"; w["permissions"]["contents"]="write"
when "command"; w["jobs"]["inventory"]["steps"][-1]["run"]="arbitrary command"
when "checkout"; w["jobs"]["inventory"]["steps"][0]["with"]["ref"]="main"
when "validation"; w["jobs"]["inventory"]["steps"].delete_at(1)
when "confirmation"; w["on"]["workflow_dispatch"]["inputs"]["confirmation"]["default"]="automatic"
end
StageRuntimeInventoryWorkflow.validate(WorkflowCapabilityPolicy,w)
'''
        for mutation in ('none','input','trigger','environment','concurrency','cancel','credentials',
                         'write','command','checkout','validation','confirmation'):
            result = subprocess.run(['ruby','-I',str(ROOT/'scripts'),'-e',script,str(ROOT),mutation],
                                    capture_output=True,timeout=10)
            self.assertEqual(result.returncode == 0, mutation == 'none', result.stderr)



if __name__ == "__main__":
    unittest.main()
