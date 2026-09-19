#!/usr/bin/env python3
"""Fixed semantic boundary tests; synthetic sources/credentials only."""
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


runner = load('stage-compose-env-semantic.py')
operation = load('stage-compose-env-semantic-operation.py')


class ProtocolTest(unittest.TestCase):
    def test_workflow_exact_authority_and_negative_mutations(self):
        script = '''require "validate-workflow-capabilities"
w,_=WorkflowCapabilityPolicy.load_workflow(Pathname.new(ARGV[0]),StageComposeEnvSemanticWorkflow::PATH)
case ARGV[1]
when "input"; w["on"]["workflow_dispatch"]["inputs"]["path"]={"type"=>"string"}
when "trigger"; w["on"]["push"]={}
when "environment"; w["jobs"]["diagnose"]["environment"]="prod"
when "concurrency"; w["concurrency"]["group"]="other"
when "cancel"; w["concurrency"]["cancel-in-progress"]=true
when "credentials"; s=w["jobs"]["diagnose"]["steps"]; s[1],s[2]=s[2],s[1]
when "write"; w["permissions"]["contents"]="write"
when "command"; w["jobs"]["diagnose"]["steps"][-1]["run"]="arbitrary command"
when "checkout"; w["jobs"]["diagnose"]["steps"][0]["with"]["ref"]="main"
when "validation"; w["jobs"]["diagnose"]["steps"].delete_at(1)
when "confirmation"; w["on"]["workflow_dispatch"]["inputs"]["confirmation"]["default"]="automatic"
end
StageComposeEnvSemanticWorkflow.validate(WorkflowCapabilityPolicy,w)
'''
        for mutation in ('none','input','trigger','environment','concurrency','cancel','credentials',
                         'write','command','checkout','validation','confirmation'):
            result = subprocess.run(['ruby','-I',str(ROOT/'scripts'),'-e',script,str(ROOT),mutation],
                                    capture_output=True,timeout=10)
            self.assertEqual(result.returncode == 0, mutation == 'none', result.stderr)

    def test_exact_grammar_authentication_exit_and_redaction(self):
        nonce = bytes(range(32))
        success = b'compose-env-semantic:v=1 result=equivalent strategy=remove scope=snapshot future=requires_recheck application=not_authorized\n'
        def frame(body):
            return b'clb91-semantic-auth:v=1 tag='+hmac.new(nonce, body, hashlib.sha256).hexdigest().encode()+b' '+body
        self.assertEqual(runner.parse_result(frame(success), 0, nonce, operation), (success.decode().strip(), 0))
        for raw, code, key in ((frame(success), 1, nonce), (frame(success), 0, b'x'*32),
                              (frame(success)*2, 0, nonce), (b'startup\n'+frame(success), 0, nonce),
                              (frame(success+b'\n'), 0, nonce), (frame(success.replace(b'remove', b'PRIVATE')), 0, nonce),
                              (frame(success.replace(b' scope=', b' extra=PRIVATE scope=')), 0, nonce),
                              (b'x'*4097, 0, nonce)):
            with self.assertRaises((ValueError, AssertionError)):
                runner.parse_result(raw, code, key, operation)
        for reason in operation.REASONS:
            raw = operation.refused(reason)
            self.assertEqual(runner.parse_result(frame(raw), 1, nonce, operation)[1], 1)

    def test_distinct_authorization_and_single_transport(self):
        self.assertNotEqual(runner.CONFIRMATION, 'CLB-91:35206468948:diagnose-compose-subset')
        env = dict(SSH_USER='fixture', SSH_HOST='fixture.invalid', SSH_PORT='22')
        argv = runner.ssh_argv(env, '/proc/123/fd/4')
        old = load('stage-compose-diagnostic.py').ssh_argv(env, '/proc/123/fd/4')
        self.assertEqual(argv[:-1], old[:-1])
        self.assertIn('-I', argv[-1])
        self.assertIn('-S', argv[-1])
        self.assertIn('-B', argv[-1])


class SourceTest(unittest.TestCase):
    def setUp(self):
        parent = str(Path(os.environ.get('RUNNER_TEMP') or tempfile.gettempdir()).resolve())
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


@unittest.skipUnless(sys.platform == 'linux', 'real pinned Linux closure required')
class BootstrapTest(unittest.TestCase):
    def setUp(self):
        self.sources = {Path(path).name: (ROOT/path).read_bytes() for path in runner.REMOTE_SOURCES}
        self.nonce = bytes(range(32))

    def invoke(self, suffix=b'', *, sources=None):
        sources = dict(sources or self.sources)
        sources['stage-compose-env-semantic-operation.py'] += suffix
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
        parent = str(Path(os.environ['RUNNER_TEMP']).resolve())
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
                SSH_KNOWN_HOSTS='fixture.invalid '+' '.join(key[:2])+'\n', COMPOSE_PATH='/opt/clubs-bot-stage')
            result = subprocess.run(['/usr/bin/python3','-I','-S','-B',str(repo/runner.RUNNER_PATH)],
                                    env=env,capture_output=True,timeout=120)
            self.assertEqual(result.stderr,b'')
            self.assertTrue(calls.exists(), (result.returncode, result.stdout))
            self.assertEqual(calls.read_bytes(),b'invoked\n')
            self.assertNotIn(b'PRIVATE',result.stdout)
            return result.stdout.decode().strip(),result.returncode

    def test_real_runtime_build_passes_without_private_capture(self):
        suffix = b'''
def diagnose(principal, cancelled):
    runtime=Runtime(cancelled)
    try:
        runtime.open()
        runtime.ruby_probe('/run/user/1000')
        runtime.recheck()
        return b'compose-env-semantic:v=1 result=equivalent strategy=remove scope=snapshot future=requires_recheck application=not_authorized\\n'
    except P.Refused as error:
        return refused(error.reason)
    finally:
        runtime.close()
'''
        line, code = self.invoke(suffix)
        self.assertEqual((code, line), (0, 'compose-env-semantic:v=1 result=equivalent strategy=remove scope=snapshot future=requires_recheck application=not_authorized'))

    def test_wrong_runtime_fails_before_capture(self):
        with tempfile.TemporaryDirectory(dir=os.environ['RUNNER_TEMP']) as directory:
            marker = Path(directory)/'private-capture-canary'
            suffix = ('''
_diagnose=diagnose
def diagnose(principal,cancelled):
    def canary(*args):
        with open(%r, 'w') as stream: stream.write('synthetic capture invoked')
        raise AssertionError('PRIVATE capture executed')
    D.ReadOnlyCapture=canary
    return _diagnose(principal,cancelled)
''' % str(marker)).encode()
            for fault in ('compose_hash', 'ruby_version', 'psych_version', 'unlisted_ruby_source', 'alias'):
                sources = dict(self.sources)
                manifest = json.loads(sources['stage-compose-env-semantic-runtime.json'])
                if fault == 'compose_hash': manifest['files']['/usr/local/bin/docker-compose'] = '0'*64
                elif fault == 'ruby_version': manifest['ruby'] = '0.0.0'
                elif fault == 'psych_version': manifest['psych'] = '0.0.0'
                elif fault == 'alias': manifest['aliases']['/usr/bin/ruby'] = '/unsupported/ruby'
                else:
                    path = next(path for path in manifest['files'] if path.endswith('/psych.rb'))
                    del manifest['files'][path]
                sources['stage-compose-env-semantic-runtime.json'] = json.dumps(manifest).encode()
                with self.subTest(fault=fault):
                    self.assertEqual(self.invoke(suffix, sources=sources),
                        ('compose-env-semantic:v=1 result=unavailable reason=runtime', 1))
                    self.assertFalse(marker.exists())

    def test_actual_capture_bootstrap_planner_hmac_consumer(self):
        spec = importlib.util.spec_from_file_location('diagnostic_tests', ROOT/'scripts/tests/test_stage_compose_diagnostic.py')
        diagnostics = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(diagnostics)
        target = Path('/opt/clubs-bot-stage')
        self.assertTrue(target.is_dir() and target.stat().st_uid == os.getuid())
        self.assertEqual(list(target.iterdir()), [])
        fixture = diagnostics.Fixture()
        self.addCleanup(fixture.close)
        for item in fixture.paths['compose'].iterdir():
            if item.is_dir():
                shutil.copytree(item, target/item.name)
            else:
                shutil.copy2(item, target/item.name)
        def clean():
            for item in target.iterdir():
                if item.is_dir(): shutil.rmtree(item)
                else: item.unlink()
        self.addCleanup(clean)
        (target/'docker-compose.yml').write_bytes(b'services:\n  app:\n    image: fixture:local\n    env_file: [.env, ./.env]\n    environment:\n      A: ${A}\n  caddy:\n    image: caddy:fixture\n    volumes:\n      - ./Caddyfile:/etc/caddy/Caddyfile:ro\n')
        (target/'.env').write_bytes(b'A=SYNTHETIC_PRIVATE_VALUE\nEXTRA=second\n')
        # Docker's overlay backing is intentionally unsupported in production.
        # Only the backing fingerprint is injected; real fixed paths, metadata,
        # FD reads, shared flock, memfd, Compose and all protocol bytes execute.
        suffix = ('\n_original=diagnose\ndef diagnose(principal,cancelled):\n'
                  '    D.ReadOnlyCapture.mount_identity=lambda self: '+repr(diagnostics.MOUNT_FINGERPRINT)+'\n'
                  '    return _original(principal,cancelled)\n').encode()
        snapshots = {str(path): (path.read_bytes(), path.stat().st_mode) for path in target.rglob('*') if path.is_file()}
        line, code = self.invoke(suffix)
        self.assertEqual(code, 0, line)
        self.assertEqual(line, 'compose-env-semantic:v=1 result=equivalent strategy=explicit scope=snapshot future=requires_recheck application=not_authorized')
        self.assertEqual(self.through_runner(suffix), (line, code))
        for failure, injection in (
            ('io', "_file=D.ReadOnlyCapture.file\n    def fail(self,directory,name,**kwargs):\n        if name=='.env': raise OSError(5,'PRIVATE EIO')\n        return _file(self,directory,name,**kwargs)\n    D.ReadOnlyCapture.file=fail"),
            ('io', "_read=D.ReadOnlyCapture.bounded_read\n    def fail(self,fd,limit):\n        if os.fstat(fd).st_ino==os.stat('/opt/clubs-bot-stage/.env').st_ino: raise OSError(5,'PRIVATE EIO')\n        return _read(self,fd,limit)\n    D.ReadOnlyCapture.bounded_read=fail"),
            ('cleanup', "_close=D.ReadOnlyCapture.close\n    def fail(self):\n        _close(self)\n        assert not self.fds\n        raise OSError('PRIVATE cleanup')\n    D.ReadOnlyCapture.close=fail"),
            ('interrupted', "_read=D.ReadOnlyCapture.bounded_read\n    def fail(self,fd,limit): raise InterruptedError('PRIVATE cancel')\n    D.ReadOnlyCapture.bounded_read=fail")):
            altered = suffix.replace(b'    return _original(principal,cancelled)',
                ('    '+injection+'\n    return _original(principal,cancelled)').encode())
            with self.subTest(failure=failure):
                self.assertEqual(self.invoke(altered), ('compose-env-semantic:v=1 result=unavailable reason='+failure, 1))
        final_cancel = suffix.replace(b'    return _original(principal,cancelled)', b'''    import signal
    body=_original(principal,cancelled)
    signal.pthread_sigmask(signal.SIG_BLOCK,[signal.SIGTERM])
    os.kill(os.getpid(),signal.SIGTERM)
    return body''')
        self.assertEqual(self.invoke(final_cancel), ('compose-env-semantic:v=1 result=unavailable reason=interrupted', 1))
        self.assertEqual(snapshots, {str(path): (path.read_bytes(), path.stat().st_mode) for path in target.rglob('*') if path.is_file()})
        self.assertEqual(list(Path('/run/user/1000').iterdir()), [])


if __name__ == '__main__':
    unittest.main(verbosity=2)
