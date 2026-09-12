#!/usr/bin/env python3
"""Executable CLB-82 coverage. Every transport is synthetic; no real SSH target."""
import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import base64
import atexit
import struct
import getpass
import secrets
import shlex
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts/deploy'))
sys.path.insert(0, str(ROOT / 'scripts/tests'))
import test_quiesced_release_state as state
from test_read_only_release_status import status_record, RunnerHarness, channel
import release_authority as authority

spec = importlib.util.spec_from_file_location('corrected', ROOT / 'scripts/deploy/corrected-stage-release.py')
executor = importlib.util.module_from_spec(spec)
spec.loader.exec_module(executor)
RUNNER = Path(spec.origin)
WORKFLOW = ROOT / '.github/workflows/corrected-stage-release.yml'


# These Git objects exist only in a disposable repository, without touching the
# actual checkout index/refs. CLI source and approved helper are the working bytes.
def external_git_fixture(prefix):
    # Resolve macOS /tmp aliases and custom TMPDIR before creating/copying any
    # fixture or Git objects. A checkout-local TMPDIR is not an external root.
    parent = Path(tempfile.gettempdir()).resolve(strict=True)
    if parent.is_relative_to(ROOT):
        raise RuntimeError('disposable Git fixture temp root must be outside checkout')
    return tempfile.TemporaryDirectory(prefix=prefix, dir=parent)


_fixture_git = external_git_fixture('clb82-implementation-')
atexit.register(_fixture_git.cleanup)
FIXTURE_REPO = Path(_fixture_git.name)
subprocess.run(['git', 'init', '--bare', '-q', str(FIXTURE_REPO/'.git')], check=True)
for path in ('corrected-stage-release.py', 'release_authority.py', 'release_private_root.py', 'release-status.pattern'):
    target = FIXTURE_REPO/'scripts/deploy'/path; target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(ROOT/'scripts/deploy'/path, target)
for path in authority.PRODUCER_PATHS:
    target = FIXTURE_REPO/path; target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(ROOT/path, target)
_helper_bytes = (ROOT/executor.HELPER_PATH).read_bytes()
# Bind this executable suite invocation to the sources it actually loaded,
# including when a long delegated selfcheck invokes the suite near its end.
print('corrected-executor-snapshot:'+json.dumps({path:hashlib.sha256((ROOT/path).read_bytes()).hexdigest()
    for path in sorted(set(authority.PRODUCER_PATHS) | {executor.HELPER_PATH,
        'scripts/deploy/corrected-stage-release.py','scripts/tests/test_corrected_stage_release.py',
        'scripts/tests/test_quiesced_release_state.py','scripts/tests/test_read_only_release_status.py',
        'scripts/validate-corrected-stage-workflow.rb'})},sort_keys=True),flush=True)
def fixture_git(*args, data=None):
    return subprocess.check_output(['git', '--git-dir='+str(FIXTURE_REPO/'.git'), *args], input=data).decode().strip()
_blob = fixture_git('hash-object', '-w', '--stdin', data=_helper_bytes)
_tree = fixture_git('mktree', data=f'100644 blob {_blob}\tremote-compose-release.sh\n'.encode())
_tree = fixture_git('mktree', data=f'040000 tree {_tree}\tdeploy\n'.encode())
_tree = fixture_git('mktree', data=f'040000 tree {_tree}\tscripts\n'.encode())
_commit = fixture_git('-c', 'user.name=CLB82 fixture', '-c', 'user.email=fixture@example.invalid', 'commit-tree', _tree, data=b'Synthetic local fixture only\n')
IMPLEMENTATION = ':'.join((_commit, _blob, hashlib.sha256(_helper_bytes).hexdigest()))
executor.ROOT = FIXTURE_REPO
RUNNER = FIXTURE_REPO/'scripts/deploy/corrected-stage-release.py'
os.environ['CLB82_APPROVED_IMPLEMENTATION'] = IMPLEMENTATION
os.environ['IMPLEMENTATION'] = IMPLEMENTATION


def request(action='inspect'):
    return {**executor.INCIDENT, 'IMPLEMENTATION':IMPLEMENTATION, 'CLB82_APPROVED_IMPLEMENTATION':IMPLEMENTATION, 'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_REF': 'refs/heads/main',
            'GITHUB_REF_TYPE': 'branch', 'REPOSITORY_DEFAULT_BRANCH': 'main', 'GITHUB_REPOSITORY': 'koteev-m/clubs_bot',
            'GITHUB_RUN_ID': '987654', 'GITHUB_RUN_NUMBER': '7', 'GITHUB_RUN_ATTEMPT': '1', 'ACTION': action,
            'PRIOR_STATUS': '876543:1:' + 'b'*40,
            'AUTHORIZATION': executor.authorization('7', IMPLEMENTATION, 'd'*64) if action == 'resume-start' else '',
            'COMPOSE_PATH': executor.COMPOSE_PATH, 'SSH_USER': 'deployment-fixture', 'SSH_HOST': 'fixture.invalid', 'SSH_PORT': '22'}


class PriorApiFixture:
    """Synthetic responses at the gh transport boundary; no caller JSON input."""
    OLD_HELP = '\nUSAGE\n  gh api <endpoint> [flags]\n\nFLAGS\n  -X, --method string   The HTTP method\n\n'
    MODERN_HELP = OLD_HELP.replace('FLAGS\n', 'FLAGS\n      --allow-escape-sequences   Allow printing terminal escape sequences\n')

    def __init__(self, root, bin_path, env, line=None):
        self.path = root / 'github-responses.json'
        self.calls_path = root / 'github-calls.jsonl'
        run_id, attempt, revision = env['PRIOR_STATUS'].split(':')
        self.run_path = f'actions/runs/{run_id}/attempts/{attempt}'
        self.jobs_path = self.run_path + '/jobs?per_page=100'
        self.logs_path = 'actions/jobs/333/logs'
        producer_env = {**os.environ, **env, 'GITHUB_RUN_ID': run_id, 'GITHUB_RUN_ATTEMPT': attempt,
                        'GITHUB_SHA': revision, 'GITHUB_JOB': 'status',
                        'GITHUB_WORKFLOW_REF': f'{authority.REPOSITORY}/{authority.WORKFLOW}@refs/heads/main',
                        'REQUESTED_OPERATION': 'start', 'EXPECTED_HELPER_SHA256': authority.INCIDENT_HELPER_SHA256}
        line = line or status_record(checkpoint='migration_completed', operation_result='remote_failure',
                                     app_state='replaced', resume_permitted='no').decode().strip()
        produced = subprocess.run([sys.executable, '-I', '-S', '-B', str(ROOT/'scripts/deploy/release_authority.py'), 'produce', line],
                                  env=producer_env, capture_output=True, check=True)
        self.value = json.loads(produced.stdout.decode().removeprefix(authority.PREFIX))
        self.responses = {
            '_cli': {'help': self.OLD_HELP, 'modern': False},
            'actions/workflows/release-status.yml': {'id': 222, 'path': authority.WORKFLOW, 'name': 'Release Status (read-only)'},
            self.run_path: {'id': int(run_id), 'run_attempt': int(attempt), 'head_sha': revision, 'head_branch': 'main',
                           'event': 'workflow_dispatch', 'status': 'completed', 'conclusion': 'success', 'workflow_id': 222,
                           'path': authority.WORKFLOW, 'repository': {'full_name': authority.REPOSITORY},
                           'head_repository': {'full_name': authority.REPOSITORY}},
            self.jobs_path: {'total_count': 2, 'jobs': [
                {'id': job_id, 'run_id': int(run_id), 'head_sha': revision, 'head_branch': 'main', 'status': 'completed',
                 'conclusion': 'success', 'name': name,
                 'steps': [{'name': authority.STEP, 'status': 'completed', 'conclusion': 'success'}]}
                for job_id, name in ((332, 'validate-status-request'), (333, 'deployment-principal-status'))]},
        }
        for path in authority.PRODUCER_PATHS:
            data = (ROOT/path).read_bytes()
            self.responses[f'contents/{path}?ref={revision}'] = {
                'type': 'file', 'path': path, 'encoding': 'base64', 'size': len(data),
                'content': base64.b64encode(data).decode(), 'sha': hashlib.sha1(f'blob {len(data)}\0'.encode()+data).hexdigest()}
        self.write()
        source = '''#!/usr/bin/env -S python3 -I -S -B
import json,os,sys
from pathlib import Path
responses=json.loads(Path(RESPONSES).read_text()); cli=responses['_cli']
if sys.argv[1:]==['api','--help']:
 assert set(os.environ)<= {'PATH','LC_ALL','GH_PROMPT_DISABLED','GH_NO_UPDATE_NOTIFIER','GH_TELEMETRY','GH_CONFIG_DIR','__CF_USER_TEXT_ENCODING'}
 assert 'GH_TOKEN' not in os.environ and 'GITHUB_TOKEN' not in os.environ
 config=Path(os.environ['GH_CONFIG_DIR'])
 assert config.is_dir() and not list(config.iterdir()) and config.stat().st_mode & 0o777 == 0o700
 with Path(CALLS+'.help').open('a') as calls:
  calls.write(json.dumps(sys.argv[1:])+'\\n')
 sys.stdout.write(cli['help']);print(cli.get('stderr',''),file=sys.stderr)
 raise SystemExit(cli.get('code',0))
assert sys.argv[1:8]==['api','--hostname','github.com','--method','GET','-H','X-GitHub-Api-Version: 2026-03-10']
assert os.environ.get('GH_TOKEN')=='synthetic-github-token'
assert os.environ.get('GH_NO_UPDATE_NOTIFIER')=='1' and os.environ.get('GH_TELEMETRY')=='0'
assert 'SSH_KNOWN_HOSTS' not in os.environ and 'UNRELATED_SECRET' not in os.environ
prefix='repos/koteev-m/clubs_bot/'
assert sys.argv[-1].startswith(prefix)
value=responses[sys.argv[-1][len(prefix):]]
with Path(CALLS).open('a') as calls:
 calls.write(json.dumps(sys.argv[1:])+'\\n')
opted_in=sys.argv[8:-1]==['--allow-escape-sequences']
assert sys.argv[8:-1] in ([],['--allow-escape-sequences'])
assert not opted_in or (cli['modern'] and sys.argv[-1].endswith('/logs'))
if cli['modern'] and sys.argv[-1].endswith('/logs') and not opted_in:
 print('private ANSI guard failure',file=sys.stderr);raise SystemExit(1)
if isinstance(value,dict) and '_error' in value:
 print(value.get('_stderr','synthetic private API diagnostics'),file=sys.stderr)
 sys.stdout.write(value.get('_body',''));raise SystemExit(value['_error'])
sys.stdout.write(value if isinstance(value,str) else json.dumps(value))
'''.replace('RESPONSES', repr(str(self.path))).replace('CALLS', repr(str(self.calls_path)))
        state.write_executable(bin_path/'gh', source)
        env['GITHUB_TOKEN'] = 'synthetic-github-token'

    def write(self, log=None):
        self.responses[self.logs_path] = log if log is not None else (
            '2026-09-07T01:00:00.000Z '+authority.PREFIX+json.dumps(self.value, sort_keys=True)+'\n')
        self.path.write_text(json.dumps(self.responses))


def configure_bound_docker(harness):
    """Real offline Compose parser; all Engine/lifecycle calls remain fake."""
    candidates = ['/Applications/Docker.app/Contents/Resources/cli-plugins/docker-compose',
                  '/usr/libexec/docker/cli-plugins/docker-compose', '/usr/lib/docker/cli-plugins/docker-compose',
                  '/usr/local/lib/docker/cli-plugins/docker-compose']
    parser = next((p for p in candidates if Path(p).is_file()), shutil.which('docker-compose'))
    if not parser:
        raise AssertionError('isolated Compose parser dependency unavailable')
    source = state.FAKE_DOCKER
    needle = '    while len(args) >= 2 and args[0] == "-f":\n        args = args[2:]'
    replacement = r'''
    import subprocess,re,hashlib,tempfile
    captured=[];options=[];descriptors=[]
    while len(args)>=2 and args[0] in ('-f','--project-name','--project-directory','--env-file'):
        flag,value=args[:2];args=args[2:];options.extend((flag,value))
        if flag in ('-f','--env-file'):
            content=Path(value).read_bytes();captured.append((flag,value,content))
            if value.startswith('/dev/fd/'):
                fd=int(value.rsplit('/',1)[1]);os.lseek(fd,0,0);descriptors.append(fd)
    if options:
        assert options[0:2]==['--project-name',state['compose_project']]
        assert options[2:4]==['--project-directory',os.environ['FAKE_BOUND_COMPOSE']]
        assert all('/fd/' in path for flag,path,content in captured)
        assert not Path(os.environ['DOCKER_CONFIG'],'config.json').exists()
        # Preserve captured bytes in a private fixture audit, never public output.
        with Path(os.environ['FAKE_BOUND_CAPTURE_LOG']).open('a') as audit:
            audit.write(json.dumps({'arguments':options+args,'cwd':os.getcwd(),
                'captures':[(flag,content.decode()) for flag,path,content in captured]})+'\n')
        if os.environ['DOCKER_CONFIG'].startswith('/dev/fd/'):
            descriptors.append(int(os.environ['DOCKER_CONFIG'].rsplit('/',1)[1]))
        parser_options=options
        adapter=None
        if sys.platform=='darwin':
            # macOS /dev/fd shares offsets on reopen; Linux /proc/PID/fd opens
            # the captured regular file independently. Adapt captured bytes at
            # this fake Docker boundary only, with the real offline parser.
            # This is not evidence that the Linux FD backend ran on macOS.
            adapter=tempfile.TemporaryDirectory(prefix='parser-captures-',dir=str(Path(os.environ['FAKE_BOUND_CAPTURE_LOG']).parent))
            parser_options=list(options)
            for index,(flag,path,content) in enumerate(captured):
                target=Path(adapter.name)/str(index);target.write_bytes(content);target.chmod(0o600)
                parser_options[parser_options.index(path)]=str(target)
        if args==['config','--format','json']:
            env={**os.environ,'DOCKER_HOST':'unix://'+os.environ['FAKE_BOUND_COMPOSE']+'/no-daemon.sock'}
            result=subprocess.run([PARSER,*parser_options,*args],env=env,pass_fds=tuple(descriptors),capture_output=True)
            if result.returncode:
                Path(os.environ['FAKE_BOUND_PARSER_ERRORS']).write_bytes(result.stderr)
            sys.stdout.buffer.write(result.stdout);raise SystemExit(result.returncode)
        # Every operational Compose call must receive the rendered captured model.
        files=[content for flag,path,content in captured if flag=='-f']
        assert len(files)==1
        model=json.loads(files[0]); assert model['services']['app']['image']==state['digest']
        env={**os.environ,'DOCKER_HOST':'unix://'+os.environ['FAKE_BOUND_COMPOSE']+'/no-daemon.sock'}
        result=subprocess.run([PARSER,*parser_options,'config','--format','json'],env=env,pass_fds=tuple(descriptors),capture_output=True)
        assert result.returncode==0
        parsed=json.loads(result.stdout)
        assert parsed==model
        with Path(os.environ['FAKE_BOUND_CAPTURE_LOG']).open('a') as audit:
            audit.write(json.dumps({'parsed':parsed})+'\n')
'''
    assert needle in source
    source=source.replace(needle,replacement.replace('[PARSER,', '['+repr(parser)+','))
    state.write_executable(harness.fake_bin/'docker', source)
    state.write_executable(harness.fake_bin/'id', '#!/usr/bin/env python3\nimport os,sys\nprint("deployment-fixture" if sys.argv[1]=="-un" else os.geteuid())\n')
    harness.env.update(FAKE_BOUND_COMPOSE=str(harness.compose_path), FAKE_BOUND_CAPTURE_LOG=str(harness.root/'bound-compose-captures.jsonl'),
                       FAKE_BOUND_PARSER_ERRORS=str(harness.root/'bound-parser-error.log'))


class FixtureBootstrapTest(unittest.TestCase):
    # Execute module initialization in a fresh interpreter, including actual
    # disposable Git object creation. No suite recursion or production mocks.
    PROBE = r'''
import errno,json,os,runpy,stat,sys,tempfile
from pathlib import Path
if sys.argv[2] == 'missing-macos-parent':
    def absent_parent(event,args):
        if event == 'os.mkdir':
            target=Path(args[0])
            if target.parent == Path('/private/tmp') and target.name.startswith('clb82-implementation-'):
                raise FileNotFoundError(errno.ENOENT,os.strerror(errno.ENOENT),str(target))
    sys.addaudithook(absent_parent)
module=runpy.run_path(sys.argv[1],run_name='fixture_bootstrap')
root=module['FIXTURE_REPO']
assert (root/'.git/objects').is_dir()
assert module['executor'].helper_snapshot() == (module['ROOT']/module['executor'].HELPER_PATH).read_bytes()
with module['external_git_fixture']('clb82-replace-') as second:
    other=Path(second)
    assert other != root and other.parent == root.parent
    assert stat.S_IMODE(other.stat().st_mode) == 0o700
print('bootstrap-result:'+json.dumps(dict(root=str(root),other=str(other),
    temp_root=str(Path(tempfile.gettempdir()).resolve()),mode=stat.S_IMODE(root.stat().st_mode))))
'''

    def bootstrap(self, temp_root=None, fault='none'):
        env = {k:v for k,v in os.environ.items() if k not in ('TMPDIR', 'TEMP', 'TMP')}
        if temp_root is not None:
            env['TMPDIR'] = str(temp_root)
        return subprocess.run([sys.executable, '-I', '-S', '-B', '-c', self.PROBE,
                               str(Path(__file__).resolve()), fault],
                              cwd=ROOT, env=env, capture_output=True, timeout=30)

    def assert_bootstrapped_and_cleaned(self, result, expected_parent=None):
        self.assertEqual(0, result.returncode, result.stderr.decode())
        lines = [line for line in result.stdout.decode().splitlines() if line.startswith('bootstrap-result:')]
        self.assertEqual(1, len(lines))
        value = json.loads(lines[0].removeprefix('bootstrap-result:'))
        root = Path(value['root'])
        self.assertEqual(root, root.resolve())
        self.assertEqual(Path(value['temp_root']), root.parent)
        if expected_parent is not None:
            self.assertEqual(expected_parent.resolve(), root.parent)
        self.assertFalse(root.is_relative_to(ROOT))
        self.assertEqual(0o700, value['mode'])
        self.assertFalse(root.exists(), 'module Git fixture survived child exit')
        self.assertFalse(Path(value['other']).exists(), 'secondary Git fixture survived context exit')

    def test_git_fixture_bootstrap_default_custom_and_symlink_temp_roots(self):
        self.assert_bootstrapped_and_cleaned(self.bootstrap())
        with external_git_fixture('clb82-bootstrap-') as temporary:
            parent = Path(temporary)
            custom = parent/'custom'; custom.mkdir(mode=0o700)
            alias = parent/'alias'; alias.symlink_to(custom, target_is_directory=True)
            for selected in (custom, alias):
                with self.subTest(selected=selected.name):
                    self.assert_bootstrapped_and_cleaned(self.bootstrap(selected), custom)
        # Git's macOS launcher may cache xcrun_db in TMPDIR. The owned outer
        # directory must also be cleaned, including such subprocess caches.
        self.assertFalse(parent.exists())

    def test_git_fixture_bootstrap_without_macos_temp_parent(self):
        # On macOS emulate only the hosted ENOENT; Linux also runs the complete
        # suite with /private/tmp genuinely absent. Leave the fault active.
        with external_git_fixture('clb82-bootstrap-') as temporary:
            self.assert_bootstrapped_and_cleaned(
                self.bootstrap(temporary, 'missing-macos-parent'), Path(temporary))
        self.assertFalse(Path(temporary).exists())

    def test_git_fixture_rejects_checkout_temp_root_before_setup(self):
        with tempfile.TemporaryDirectory(prefix='.clb82-temp-root-', dir=ROOT) as inside, \
                external_git_fixture('clb82-bootstrap-') as outside:
            alias = Path(outside)/'checkout-alias'
            alias.symlink_to(inside, target_is_directory=True)
            for selected in (ROOT, Path(inside), alias):
                with self.subTest(selected=str(selected)):
                    result = self.bootstrap(selected)
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn(b'disposable Git fixture temp root must be outside checkout', result.stderr)
                    self.assertNotIn(b'corrected-executor-snapshot:', result.stdout)
                    self.assertEqual([], list(Path(inside).iterdir()))


class CorrectedExecutorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.snapshot = executor.helper_snapshot()

    def rejected(self, category, callback):
        with self.assertRaisesRegex(executor.Rejected, '^' + category + '$'):
            callback()

    def fixture(self, production=True):
        production = True
        harness = state.RemoteHarness(app_env='stage' if production else 'test')
        self.addCleanup(harness.close)
        if production:
            # Existing stage fixtures keep state on the checkout's persistent
            # path. Permit only this synthetic owner's own 0600 migration log
            # in the helper's fixed volatile directory; no broad rm allowance.
            source = state.FAKE_RM.replace('import shutil\n', 'import shutil\nimport stat\n')
            source = source.replace('    if not allowed:\n', '''    allowed = allowed or bool(
        re.fullmatch(r"/tmp/\\.clubs-bot-migration-log\\." + os.environ["FAKE_OWNER"] + r"\\.[A-Za-z0-9]+", str(target))
        and target.is_file() and not target.is_symlink()
        and target.stat().st_uid == os.geteuid() and stat.S_IMODE(target.stat().st_mode) == 0o600
    )
    if not allowed:
''')
            state.write_executable(harness.fake_bin/'rm', source)
            # The shared harness counter uses read/truncate/write; concurrent
            # status processes otherwise observe an empty synthetic findmnt
            # counter. Lock only its bookkeeping, never the helper or claims.
            source = state.FAKE_FINDMNT.replace('import json\n', 'import json\nimport fcntl\n')
            source = source.replace('counter = int(counter_path.read_text(encoding="ascii")) if counter_path.exists() else 0\ncounter += 1\ncounter_path.write_text(str(counter), encoding="ascii")', '''with counter_path.open("a+", encoding="ascii") as counter_file:
    fcntl.flock(counter_file, fcntl.LOCK_EX)
    counter_file.seek(0)
    counter = int(counter_file.read() or "0") + 1
    counter_file.seek(0)
    counter_file.truncate()
    counter_file.write(str(counter))
    counter_file.flush()
    fcntl.flock(counter_file, fcntl.LOCK_UN)''')
            state.write_executable(harness.fake_bin/'findmnt', source)
        harness.progress_to('migration_completed')
        # Reproduce the incident using the actual old helper, including its
        # retained-oneoff selection defect. No handwritten success/result record.
        old = subprocess.check_output(['git', '--no-replace-objects', '-C', str(ROOT), 'cat-file', 'blob',
                                       '430595929a09566ff29ad6fe58bd19fa4f0c7ca4'])
        retained = harness.volatile_root / f'clubs-bot-release-{state.OWNER}.sh'
        retained.write_bytes(old)
        harness.retained = retained
        result = subprocess.run(['bash', '-s', '--', 'start', state.OWNER, harness.app_env, str(harness.compose_path),
                                 state.DIGEST, state.REVISION], input=old, env=harness.env, capture_output=True)
        self.assertNotEqual(0, result.returncode)
        self.assertEqual('migration_completed', harness.checkpoint())
        self.assertEqual('app_identity_mismatch', harness.result()['failure_category'])
        harness.clear_command_logs()
        configure_bound_docker(harness)
        env = {**request('resume-start'), 'RELEASE_OWNER': state.OWNER, 'EXPECTED_REVISION': state.REVISION,
               'IMAGE_DIGEST': state.DIGEST, 'COMPOSE_PATH': str(harness.compose_path)}
        calls = []

        def remote(snapshot, argv, timeout, control):
            self.assertIs(snapshot, self.snapshot)
            calls.append(list(argv))
            payload_control = json.dumps(control, sort_keys=True, separators=(',', ':')).encode()
            payload = struct.pack('!II', len(payload_control), len(snapshot)) + payload_control + snapshot
            return executor.capture([sys.executable, '-I', '-S', '-B', '-c', executor.REMOTE_BOUND_BOOTSTRAP, *argv],
                                    payload, timeout=timeout, env=harness.env)
        control = executor.binding_control({**env, "ACTION":"inspect", "AUTHORIZATION":""}, self.snapshot)
        control['principal'] = 'deployment-fixture'
        argv = ['corrected-start', state.OWNER, 'stage', str(harness.compose_path), state.REVISION, state.DIGEST, 'inspect']
        code, raw = remote(self.snapshot, argv, 90, control)
        self.assertEqual(0, code, raw)
        control['binding'] = json.loads(raw.splitlines()[1].split(b' ', 1)[1])
        env['CLB82_AUTHORIZED_ROOT_BINDING'] = json.dumps(control['binding'], sort_keys=True, separators=(',', ':'))
        env['AUTHORIZATION'] = executor.authorization(env['GITHUB_RUN_NUMBER'], IMPLEMENTATION,
            hashlib.sha256(env['CLB82_AUTHORIZED_ROOT_BINDING'].encode()).hexdigest())
        control['authorization'] = hashlib.sha256(env['AUTHORIZATION'].encode()).hexdigest()
        self.fixture_control = control
        calls.clear(); harness.clear_command_logs()
        return harness, env, calls, remote

    def execute(self, env, remote):
        with contextlib.redirect_stdout(io.StringIO()) as output:
            result = executor.execute(env, self.snapshot, remote, getattr(self, 'fixture_control', {}))
        for secret in state.SENSITIVE_VALUES:
            self.assertNotIn(secret, output.getvalue())
        return result

    def test_dispatch_validation_and_injection_before_secrets(self):
        changes = [('GITHUB_REF', 'refs/heads/feature'), ('GITHUB_REF_TYPE', 'tag'),
                   ('GITHUB_EVENT_NAME', 'push'), ('REPOSITORY_DEFAULT_BRANCH', 'other'),
                   ('GITHUB_REPOSITORY', 'attacker/repo'), ('APP_ENV', 'prod'),
                   ('INCIDENT_TAG', 'deploy-stage-deadbee'), ('RELEASE_OWNER', '123-1'),
                   ('EXPECTED_REVISION', 'a'*40), ('IMAGE_DIGEST', 'other'), ('IMPLEMENTATION', '')]
        for key, value in changes + [(k, '$(touch /tmp/CLB82-NOT-EXECUTED)\nmalicious=yes') for k in executor.INCIDENT]:
            with self.subTest(key=key, value=value):
                env = request(); env[key] = value
                with tempfile.TemporaryDirectory() as temporary:
                    output = Path(temporary) / 'outputs'
                    env['GITHUB_OUTPUT'] = str(output)
                    completed = subprocess.run([sys.executable, '-I', '-S', '-B', str(RUNNER), '--validate'],
                                               env={**os.environ, **env}, capture_output=True)
                    self.assertNotEqual(0, completed.returncode)
                    self.assertFalse(output.exists())
                    self.assertNotIn(b'malicious', completed.stdout + completed.stderr)
        self.assertFalse(Path('/tmp/CLB82-NOT-EXECUTED').exists())
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / 'outputs'
            env = {**os.environ, **request(), 'GITHUB_OUTPUT': str(output)}
            env.pop('ACTION')
            completed = subprocess.run([sys.executable, '-I', '-S', '-B', str(RUNNER), '--validate'], env=env, capture_output=True)
            self.assertEqual(0, completed.returncode, completed.stdout)
            self.assertIn('ACTION=inspect\n', output.read_text())

    def test_authorization_exact_tuple_run_number_and_rerun(self):
        executor.validate_request(request('resume-start'))
        for key, value in [('AUTHORIZATION', ''), ('AUTHORIZATION', 'yes'), ('GITHUB_RUN_ATTEMPT', '2'),
                           ('GITHUB_RUN_NUMBER', '8'), ('AUTHORIZATION', executor.authorization('6', IMPLEMENTATION, 'd'*64))]:
            with self.subTest(key=key, value=value):
                env = request('resume-start'); env[key] = value
                with self.assertRaises(executor.Rejected): executor.validate_request(env)
        self.rejected('INSPECT_AUTHORIZATION_INVALID', lambda: executor.validate_request({**request(), 'AUTHORIZATION':'yes'}))

    def test_exact_git_snapshot_and_replace_environment(self):
        with patch.dict(os.environ, {'GIT_REPLACE_REF_BASE': 'refs/evil/', 'GIT_OBJECT_DIRECTORY': '/missing',
                                     'GIT_WORK_TREE': '/missing', 'GIT_CONFIG_COUNT': '1', 'GIT_CONFIG_KEY_0': 'core.bare',
                                     'GIT_CONFIG_VALUE_0': 'true'}):
            self.assertEqual(self.snapshot, executor.helper_snapshot())
        self.assertEqual(executor.IMPLEMENTATION_SHA256, hashlib.sha256(self.snapshot).hexdigest())
        self.assertEqual(executor.IMPLEMENTATION_SIZE, len(self.snapshot))

    def test_real_git_replace_object_cannot_substitute_approved_blob(self):
        # Object/ref writes belong solely to an isolated synthetic Git database.
        with external_git_fixture('clb82-replace-') as temporary:
            repo=Path(temporary)
            subprocess.run(['git','init','--bare','-q',str(repo)],check=True,capture_output=True)
            objects=subprocess.check_output(['git','-C',str(FIXTURE_REPO),'rev-parse','--path-format=absolute','--git-path','objects']).decode().strip()
            (repo/'objects/info/alternates').write_text(objects+'\n')
            replacement=subprocess.check_output(['git','--git-dir='+str(repo),'hash-object','-w','--stdin'],input=b'echo malicious\n').decode().strip()
            (repo/'refs/replace').mkdir()
            (repo/'refs/replace'/executor.IMPLEMENTATION_BLOB).write_text(replacement+'\n')
            self.assertEqual(b'echo malicious\n',subprocess.check_output(['git','--git-dir='+str(repo),'cat-file','blob',executor.IMPLEMENTATION_BLOB]))
            with patch.object(executor,'ROOT',repo):
                self.assertEqual(self.snapshot,executor.helper_snapshot())

    def test_wrong_git_type_mode_path_size_blob_and_snapshot_bytes(self):
        real_capture = executor.capture
        mutations = [('ls-tree', b'120000 blob '+executor.IMPLEMENTATION_BLOB.encode()+b'\t'+executor.HELPER_PATH.encode()+b'\0'),
                     ('ls-tree', b'100644 blob '+b'a'*40+b'\t'+executor.HELPER_PATH.encode()+b'\0'),
                     ('ls-tree', b'100644 blob '+executor.IMPLEMENTATION_BLOB.encode()+b'\tsubstitute\0'),
                     ('-t', b'tree\n'), ('-s', b'138834\n'), ('blob', self.snapshot[:-1]+b'X')]
        for selector, replacement in mutations:
            with self.subTest(selector=selector, replacement=replacement[:20]):
                def captured(argv, *args, **kwargs):
                    if selector in argv and (selector != 'blob' or argv[-2] == 'blob'):
                        return 0, replacement
                    return real_capture(argv, *args, **kwargs)
                with patch.object(executor, 'capture', captured):
                    self.rejected('IMPLEMENTATION_INVALID', executor.helper_snapshot)

    def test_inspect_real_helper_read_only_and_preserves_incident(self):
        h, env, calls, remote = self.fixture()
        before = state.snapshot_authoritative_tree(h)
        retained = h.retained.read_bytes()
        env['ACTION'] = 'inspect'
        self.assertEqual('INSPECTED', self.execute(env, remote))
        self.assertEqual(['inspect'], [c[-1] for c in calls])
        self.assertEqual(before, state.snapshot_authoritative_tree(h))
        self.assertEqual(retained, h.retained.read_bytes())
        self.assertEqual([], h.lifecycle_commands())
        self.assertTrue(all(v == 0 for v in h.status_filesystem_write_counts().values()))

    def test_real_retained_oneoff_one_start_no_migration_or_cleanup(self):
        h, env, calls, remote = self.fixture()
        before = {p.name:p.read_bytes() for p in h.ledger_dir.iterdir()}
        retained = h.retained.read_bytes()
        self.assertEqual('SUCCESS', self.execute(env, remote))
        self.assertEqual(['inspect','claim','resume-start','reconcile'], [c[-1] for c in calls])
        self.assertEqual('inspect', calls[0][-1]); self.assertEqual('reconcile', calls[-1][-1])
        self.assertEqual(['corrected-start', state.OWNER, 'stage', str(h.compose_path), state.REVISION, state.DIGEST, 'resume-start'], calls[2])
        current = h.docker_state()
        self.assertEqual(1, current['start_invocations']); self.assertEqual(1, current['migration_invocations'])
        self.assertEqual(0, current['migration_removals']); self.assertTrue(current['migration_exists'])
        self.assertEqual(before, {p.name:p.read_bytes() for p in h.ledger_dir.iterdir()})
        self.assertEqual(retained, h.retained.read_bytes())
        self.assertEqual('resume-start', h.result()['requested_operation'])
        self.assertEqual('candidate_healthy', h.checkpoint())
        commands = h.docker_commands()
        self.assertTrue(any('/ready' in ' '.join(c) for c in commands))
        self.assertTrue(any('/health' in ' '.join(c) for c in commands))
        calls.clear()
        self.rejected('FRESH_START_GATE_REJECTED', lambda: self.execute(env, remote))
        self.assertEqual(['inspect'], [c[-1] for c in calls])
        self.assertEqual(1, h.docker_state()['start_invocations'])

    def test_candidate_start_begun_never_second_start_or_resume(self):
        for app in ('absent','candidate_running'):
            with self.subTest(app=app):
                h, env, calls, remote = self.fixture()
                (h.lock_dir/'checkpoint').write_text('candidate_start_begun')
                h.update_docker_state(app_state=app)
                self.rejected('FRESH_START_GATE_REJECTED', lambda: self.execute(env, remote))
                self.assertEqual(['inspect'], [c[-1] for c in calls])
                self.assertEqual(0, h.docker_state()['start_invocations'])

    def test_untrusted_root_wrong_owner_and_malformed_records(self):
        for change in ('root-mode', 'owner', 'result', 'ledger'):
            with self.subTest(change=change):
                h, env, calls, remote = self.fixture()
                if change == 'root-mode': h.state_root.chmod(0o777)
                if change == 'owner': env['RELEASE_OWNER'] = '88888-1'
                if change == 'result': (h.result_dir/f'{state.OWNER}.result').write_text('invalid\n')
                if change == 'ledger': (h.ledger_dir/f'{state.OWNER}.ledger').write_text('invalid\n')
                with self.assertRaises(executor.Rejected): self.execute(env, remote)
                self.assertEqual(['inspect'], [c[-1] for c in calls])
                self.assertEqual(0, h.docker_state()['start_invocations'])

    def test_malformed_busy_unknown_and_inconsistent_status_block_mutation(self):
        env=request('resume-start')
        control=executor.binding_control({**env,'ACTION':'inspect','AUTHORIZATION':''},self.snapshot)
        candidate=dict(version=1,incident=dict(owner=env['RELEASE_OWNER'],environment='stage',revision=env['EXPECTED_REVISION'],image=env['IMAGE_DIGEST']),
            implementation=control['implementation'],principal=dict(name=env['SSH_USER'],uid=os.geteuid()),
            compose=dict(path=env['COMPOSE_PATH'],project='clubs',service='app'),backing='mount-v2:'+'a'*64,
            objects={k:[1,2] for k in ('/','parent','root','state','results','ledger','application_lock','operation_lock')},
            configuration={k:'a'*64 for k in ('main','override','release','dotenv','resolved')})
        suffix=b'corrected-binding-candidate:v=1 '+json.dumps(candidate).encode()+b'\n'
        baseline=status_record(checkpoint='migration_completed',app_state='absent',operation_result='remote_failure',resume_permitted='yes')
        cases=[b'bad\n',baseline+b'secret\n',baseline.replace(b'owner_match=yes',b'owner_match=no'),
               baseline.replace(b'operation_result=remote_failure',b'operation_result=unavailable'),
               baseline.replace(b'migration_evidence=present',b'migration_evidence=unknown')]
        self.fixture_control=control
        for raw in cases:
            with self.subTest(raw=raw[:50]):
                calls=[]
                def remote(data,argv,timeout,control):calls.append(argv);return 0,raw+suffix
                with self.assertRaises(executor.Rejected):self.execute(env,remote)
                self.assertEqual(1,len(calls));self.assertEqual('inspect',calls[0][-1])

    def test_state_drift_guard_under_helper_locks(self):
        h, env, calls, remote = self.fixture()
        def drift(data, argv, timeout, control):
            if argv[-1] == 'resume-start': h.update_docker_state(app_state='replaced')
            return remote(data, argv, timeout, control)
        self.rejected('SEPARATE_DECISION_REQUIRED', lambda: self.execute(env, drift))
        self.assertEqual(0, h.docker_state()['start_invocations'])
        self.assertEqual('reconcile', calls[-1][-1])
        # A failed resume that never started the app still consumes operation
        # evidence: returning to absent/migration_completed cannot reuse it.
        h.update_docker_state(app_state='absent')
        calls.clear()
        self.rejected('AUTHORIZATION_CONSUMED_OR_UNKNOWN', lambda: self.execute(env, remote))
        self.assertEqual(['inspect','claim'], [c[-1] for c in calls])
        self.assertEqual(0, h.docker_state()['start_invocations'])

    def test_post_start_compose_drift_cannot_claim_success(self):
        h, env, calls, remote = self.fixture()
        def drift(data, argv, timeout, control):
            if argv[-1] == 'reconcile':
                (h.compose_path/'docker-compose.override.yml').write_bytes(h.prior_override_bytes)
            return remote(data, argv, timeout, control)
        self.rejected('STATUS_UNAVAILABLE', lambda: self.execute(env, drift))
        self.assertEqual(1, h.docker_state()['start_invocations'])
        self.assertEqual(1, h.docker_state()['migration_invocations'])
        self.assertEqual('candidate_healthy', h.checkpoint())
        self.assertEqual('success', h.result()['result'])
        self.assertEqual(['inspect', 'claim', 'resume-start', 'reconcile'], [c[-1] for c in calls])

    def test_timeout_255_or_bad_ack_after_success_reconciles_without_retry(self):
        for code, ack in ((124,b''), (255,b''), (0,b'wrong secret acknowledgement\n')):
            with self.subTest(code=code):
                h, env, calls, remote = self.fixture()
                def lost(data, argv, timeout, control):
                    result = remote(data, argv, timeout, control)
                    return (code,ack) if argv[-1]=='resume-start' else result
                self.assertEqual('COMPLETED_ACK_LOST', self.execute(env, lost))
                self.assertEqual(1, h.docker_state()['start_invocations'])
                self.assertEqual(['inspect','claim','resume-start','reconcile'], [c[-1] for c in calls])
                self.assertEqual('reconcile', calls[-1][-1])

    def test_unknown_no_submission_evidence_and_partial_start_fail_closed(self):
        for partial in (False,True):
            with self.subTest(partial=partial):
                h, env, calls, remote = self.fixture()
                def lost(data, argv, timeout, control):
                    if argv[-1]=='resume-start':
                        calls.append(argv)
                        if partial: (h.lock_dir/'checkpoint').write_text('candidate_start_begun')
                        return 255,b''
                    return remote(data,argv,timeout,control)
                self.rejected('SEPARATE_DECISION_REQUIRED', lambda:self.execute(env,lost))
                self.assertEqual('reconcile',calls[-1][-1])
                self.assertEqual(1,sum(c[-1]=='resume-start' for c in calls))
                self.assertEqual(0,h.docker_state()['start_invocations'])
                if partial:self.assertEqual('candidate_start_begun',h.checkpoint())

    def test_competing_submissions_use_existing_real_locks(self):
        h, env, calls, remote = self.fixture()
        h.env['FAKE_FLOCK_REAL']='yes'
        gate = threading.Barrier(2)
        claimed = threading.Barrier(2)
        failures=[]
        def competing(data,argv,timeout,control):
            result=remote(data,argv,timeout,control)
            if argv[-1]=='inspect': gate.wait(timeout=30)
            # Both competing claim helpers release their real locks before the
            # positive-control winner receives its transport acknowledgement.
            if argv[-1]=='claim': claimed.wait(timeout=30)
            return result
        def run():
            try: executor.execute(env,self.snapshot,competing,self.fixture_control)
            except executor.Rejected: pass
            except BaseException as error: failures.append(error)
        with contextlib.redirect_stdout(io.StringIO()):
            threads=[threading.Thread(target=run) for _ in range(2)]
            for t in threads:t.start()
            for t in threads:t.join(timeout=90)
        self.assertFalse(failures,failures)
        self.assertTrue(all(not t.is_alive() for t in threads))
        self.assertEqual(1,h.docker_state()['start_invocations'])
        self.assertEqual(1,h.docker_state()['migration_invocations'])
        self.assertEqual(1, sum(c[-1]=='resume-start' for c in calls))

    def test_snapshot_object_survives_source_path_substitution(self):
        h,env,calls,remote=self.fixture()
        # A substitute checkout cannot influence any call after capture.
        with tempfile.TemporaryDirectory() as temporary:
            substitute=Path(temporary)/executor.HELPER_PATH
            substitute.parent.mkdir(parents=True);substitute.write_text('echo malicious\n')
            with patch.object(executor,'ROOT',Path(temporary)):
                self.assertEqual('SUCCESS',self.execute(env,remote))
        self.assertEqual(1,h.docker_state()['start_invocations'])

    def test_bounded_capture_discards_stderr_limits_stdout_and_timeout(self):
        secret='private-process-secret'
        code,data=executor.capture([sys.executable, '-I', '-S', '-B','-c',f'import sys; print({secret!r}, file=sys.stderr); print("ok")'])
        self.assertEqual((0,b'ok\n'),(code,data))
        code,data=executor.capture([sys.executable, '-I', '-S', '-B','-c','print("x"*20000)'],limit=1024)
        self.assertEqual((125,b''),(code,data))
        start=time.monotonic()
        code,data=executor.capture([sys.executable, '-I', '-S', '-B','-c','import time; time.sleep(10)'],timeout=0.1)
        self.assertEqual(124,code);self.assertLess(time.monotonic()-start,3)

    def test_private_pin_missing_invalid_unsafe_root_and_cleanup(self):
        for pin in ('','arbitrary secret\n','host key bad\x01'):
            with self.subTest(pin=pin):
                with self.assertRaises(executor.Rejected):
                    with executor.pinned_hosts({'SSH_KNOWN_HOSTS':pin}):self.fail('accepted')
        with tempfile.TemporaryDirectory(dir=ROOT) as temporary:
            key=Path(temporary)/'fixture-key'
            subprocess.run(['ssh-keygen','-q','-t','ed25519','-N','','-f',str(key)],check=True,capture_output=True)
            pin='fixture.invalid '+key.with_suffix('.pub').read_text()
            root=Path(temporary)/'private';root.mkdir(mode=0o700)
            with executor.pinned_hosts({'TMPDIR':str(root),'SSH_KNOWN_HOSTS':pin}) as (fd,path):
                self.assertEqual([],list(root.iterdir()))
                self.assertEqual(0,os.fstat(fd).st_nlink)
                self.assertEqual(pin.encode(),os.read(fd,65536))
                os.lseek(fd,0,os.SEEK_SET)
                self.assertEqual(pin.encode(),Path(path).read_bytes())
            self.assertEqual([],list(root.iterdir()))
            root.chmod(0o777)
            with self.assertRaises(RuntimeError):
                with executor.pinned_hosts({'TMPDIR':str(root),'SSH_KNOWN_HOSTS':pin}):self.fail('accepted')

    def test_cli_rejects_wrong_principal_and_never_leaks_inputs(self):
        for field,value in [('SSH_USER','root'),('SSH_USER','hookah-staging'),('SSH_USER','name;echo secret'),
                            ('COMPOSE_PATH','/srv/secret'),('SSH_HOST','$(echo secret)'),('SSH_PORT','65536')]:
            with self.subTest(field=field,value=value):
                env={**os.environ,**request(),field:value}
                result=subprocess.run([sys.executable, '-I', '-S', '-B',str(RUNNER)],env=env,capture_output=True)
                self.assertNotEqual(0,result.returncode)
                self.assertNotIn(value.encode(),result.stdout+result.stderr)

    def test_legacy_shared_dependencies_missing_fail_closed(self):
        for missing in ('release_private_root.py', 'release-status.pattern'):
            with self.subTest(missing=missing), tempfile.TemporaryDirectory() as temporary:
                root=Path(temporary)
                legacy=ROOT/'scripts/deploy/read-only-release-status.sh'
                for name in ('read-only-release-status.sh', 'release_private_root.py', 'release-status.pattern'):
                    if name != missing: shutil.copy2(legacy.with_name(name), root/name)
                harness=RunnerHarness()
                try:
                    result=harness.run(runner=root/legacy.name)
                    self.assertEqual(1,result.returncode)
                    self.assertEqual(channel('unavailable','LOCAL_FAILURE'),result.stdout)
                    self.assertEqual(b'',result.stderr)
                    harness.assert_cleaned(self)
                finally:
                    harness.close()

    def test_cli_transport_principal_guard_pins_bytes_and_secret_redaction(self):
        f=AuthorityCliFixture(self)
        source=(f.bin/'ssh').read_text()
        needle="words=shlex.split(sys.argv[-1]);command=words[words.index('exec')+1:]"
        replacement=needle+"\nrecord('ssh_options',values=sys.argv[1:])\nguard=sys.argv[-1].rsplit(' && exec ',1)[0]\nchecked=subprocess.run(['/bin/bash','--noprofile','--norc','-c',guard],env=config['helper_env'],capture_output=True)\nif checked.returncode:raise SystemExit(checked.returncode)"
        self.assertIn(needle,source);state.write_executable(f.bin/'ssh',source.replace(needle,replacement))
        for user,uid,accepted in [('deployment-fixture',str(os.geteuid()),True),('wrong-user',str(os.geteuid()),False),('deployment-fixture','0',False)]:
            with self.subTest(user=user,uid=uid):
                state.write_executable(f.helper.fake_bin/'id','#!/usr/bin/env -S '+shlex.quote(sys.executable)+' -I -S -B\nimport sys\nprint('+repr(user)+' if sys.argv[1]=="-un" else '+repr(uid)+')\n')
                script=f.helper.fake_bin/'id'
                interpreter,optional=script.read_text().splitlines()[0][2:].split(' ',1)
                linux_argv=subprocess.run([interpreter,optional,str(script),'-un'],env=f.helper.env,capture_output=True,timeout=15)
                self.assertEqual(0,linux_argv.returncode,linux_argv.stderr)
                self.assertEqual((user+'\n').encode(),linux_argv.stdout)
                result=f.run(ACTION='inspect',AUTHORIZATION='')
                self.assertEqual(accepted,result.returncode==0,result.stdout)
                for secret in (f.env['SSH_USER'],f.env['SSH_KNOWN_HOSTS'],'synthetic-private-value','synthetic-github-token','deployment-fixture@fixture.invalid'):
                    self.assertNotIn(secret.encode(),result.stdout+result.stderr)
                entries=[json.loads(line) for line in f.audit.read_text().splitlines()]
                args=[entry['values'] for entry in entries if entry['kind']=='ssh_options'][-1]
                for option in ('StrictHostKeyChecking=yes','GlobalKnownHostsFile=/dev/null','KnownHostsCommand=none','ConnectionAttempts=1','VerifyHostKeyDNS=no','UpdateHostKeys=no'):
                    self.assertIn(option,args)
                self.assertEqual([],list(f.private.iterdir()))
        self.assertEqual(0,f.counts()['resume']);self.assertEqual(0,f.counts()['claim'])

    def test_workflow_validator_rejects_privilege_and_executable_mutations(self):
        source=WORKFLOW.read_text()
        mutations=[('cancel-in-progress: false','cancel-in-progress: true'),('group: payments-schema-stage','group: isolated'),
                   ('environment: stage','environment: prod'),('needs: validate','needs: other'),
                   ('default: inspect','default: resume-start'),('contents: read','contents: write'),
                   ('workflow_dispatch:','push:'),('python3 -I -S -B scripts/deploy/corrected-stage-release.py --validate','echo skipped'),
                   ('python3 -I -S -B scripts/deploy/corrected-stage-release.py\n','ssh target start\n'),
                   ('persist-credentials: false','persist-credentials: true'),
                   ('python3 -I -S -B scripts/deploy/corrected-stage-release.py','python3 -S -B scripts/deploy/corrected-stage-release.py'),
                   ('python3 -I -S -B scripts/deploy/corrected-stage-release.py','python3 -I -B scripts/deploy/corrected-stage-release.py'),
                   ('${{ vars.CLB82_AUTHORIZED_ROOT_BINDING }}','${{ inputs.authorization }}'),
                   ('${{ vars.CLB82_APPROVED_IMPLEMENTATION }}','${{ inputs.implementation }}'),
                   ('--validate-execution','--validate')]
        # Use the existing safe YAML reader API, as the global capability gate does.
        ruby='require "validate-workflow-capabilities"; root=Pathname.new(ARGV[0]); w,raw=WorkflowCapabilityPolicy.load_workflow(root,CorrectedStageWorkflow::PATH); CorrectedStageWorkflow.validate(WorkflowCapabilityPolicy,w)'
        with tempfile.TemporaryDirectory() as temporary:
            target=Path(temporary)/'.github/workflows/corrected-stage-release.yml';target.parent.mkdir(parents=True)
            for old,new in [(None,None),*mutations]:
                with self.subTest(old=old):
                    target.write_text(source if old is None else source.replace(old,new,1))
                    result=subprocess.run(['ruby','-I'+str(ROOT/'scripts'),'-e',ruby,temporary],capture_output=True)
                    self.assertEqual(old is None,result.returncode==0,result.stderr.decode())

    def test_bound_structure_validator_rejects_stale_identity_and_missing_regression(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary)
            for name in ('scripts/deploy/remote-compose-release.sh','scripts/deploy/corrected-stage-release.py',
                         'scripts/tests/test_corrected_stage_release.py'):
                target=root/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(ROOT/name,target)
            command=['ruby',str(ROOT/'scripts/validate-corrected-stage-workflow.rb'),str(root)]
            self.assertEqual(0,subprocess.run(command,capture_output=True).returncode)
            for name,old,new in (
                ('scripts/deploy/remote-compose-release.sh','os.O_NOFOLLOW','0'),
                ('scripts/deploy/corrected-stage-release.py',executor.IMPLEMENTATION_SHA256,'0'*64),
                ('scripts/tests/test_corrected_stage_release.py',
                 '\n    def test_root_and_parent_substitution_must_not_authorize_two_resumes(self):','\n    def disabled_root(self):')):
                target=root/name;original=target.read_text();self.assertIn(old,original)
                target.write_text(original.replace(old,new,1))
                self.assertNotEqual(0,subprocess.run(command,capture_output=True).returncode,name)
                target.write_text(original)


class AuthorityCliFixture:
    """Production CLI + actual bound helper; only transports and Engine are fake."""
    def __init__(self, case, actual=True):
        self.temporary = tempfile.TemporaryDirectory(prefix='.clb82-authority-', dir=ROOT)
        case.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.bin = self.root/'bin'; self.bin.mkdir()
        self.private = self.root/'private'; self.private.mkdir(mode=0o700)
        self.control = self.root/'control.json'; self.audit = self.root/'audit.jsonl'; self.audit.touch()
        helper_case = CorrectedExecutorTest(); helper_case.setUpClass(); case.addCleanup(helper_case.doCleanups)
        self.helper, helper_env, _, _ = helper_case.fixture(production=True)
        self.remote_root = self.helper.state_root
        self.marker = self.remote_root/'.clb82-resume-start-33468965282-1.consumed'
        self.config = dict(fault='', actual=True, audit=str(self.audit), root=str(self.remote_root), compose=str(self.helper.compose_path),
                           owner=state.OWNER, revision=state.REVISION, image=state.DIGEST,
                           helper_env={k:v for k,v in self.helper.env.items() if k.startswith(('FAKE_', 'REMOTE_')) or k == 'PATH'},
                           helper_sha=executor.IMPLEMENTATION_SHA256, bootstrap=executor.REMOTE_BOUND_BOOTSTRAP)
        self.save()
        key=self.root/'synthetic-key'
        subprocess.run(['ssh-keygen','-q','-t','ed25519','-N','','-f',str(key)],check=True,capture_output=True)
        self.env={**os.environ, **request('resume-start'), 'TMPDIR':str(self.private),
                  'SSH_KNOWN_HOSTS':'fixture.invalid '+key.with_suffix('.pub').read_text(),
                  'PATH':str(self.bin)+os.pathsep+os.environ['PATH'], 'UNRELATED_SECRET':'synthetic-private-value',
                  'CLB82_AUTHORIZED_ROOT_BINDING': helper_env['CLB82_AUTHORIZED_ROOT_BINDING']}
        binding=json.loads(self.env['CLB82_AUTHORIZED_ROOT_BINDING'])
        binding['incident']=dict(owner=executor.INCIDENT['RELEASE_OWNER'],environment='stage',revision=executor.INCIDENT['EXPECTED_REVISION'],image=executor.INCIDENT['IMAGE_DIGEST'])
        binding['compose']['path']=executor.COMPOSE_PATH
        self.env['CLB82_AUTHORIZED_ROOT_BINDING']=json.dumps(binding,sort_keys=True,separators=(',',':'))
        self.authorize()
        self.api=PriorApiFixture(self.root,self.bin,self.env)
        source=r'''#!/usr/bin/env -S python3 -I -S -B
import hashlib,json,os,shlex,signal,subprocess,sys,time,struct
from pathlib import Path
control=Path(CONTROL_PATH); audit=Path(AUDIT_PATH); config=json.loads(control.read_text())
def record(kind,**fields):
 fd=os.open(audit,os.O_WRONLY|os.O_APPEND)
 os.write(fd,(json.dumps({'kind':kind,**fields})+'\n').encode());os.close(fd)
assert 'GITHUB_TOKEN' not in os.environ and 'SSH_KNOWN_HOSTS' not in os.environ
words=shlex.split(sys.argv[-1]);command=words[words.index('exec')+1:]
assert command[:5]==['python3','-I','-S','-B','-c'] and command[5]==config['bootstrap']
payload=sys.stdin.buffer.read(); csize,hsize=struct.unpack('!II',payload[:8])
request=json.loads(payload[8:8+csize]); helper=payload[8+csize:]
assert len(helper)==hsize and hashlib.sha256(helper).hexdigest()==config['helper_sha']
assert len(request['token'])==64 and request['token'] not in sys.argv[-1]
argv=command[6:]; assert argv[0]=='corrected-start'; phase=argv[-1];fault=config['fault']
record('transport',phase=phase)
if phase in ('inspect','reconcile'):record('status',operation=phase)
if phase=='claim':record('claim_attempt')
if phase=='resume-start':record('resume')
if phase=='claim' and fault in ('compete','late-compete'):
 deadline=time.monotonic()+30
 while sum(json.loads(line)['kind']=='claim_attempt' for line in audit.read_text().splitlines())<2:
  assert time.monotonic()<deadline;time.sleep(.02)
leader=False
if phase=='claim' and fault=='late-compete':
 try:
  fd=os.open(str(audit)+'.leader',os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600);os.close(fd);leader=True
 except FileExistsError:
  deadline=time.monotonic()+30
  while not Path(str(audit)+'.leader-done').exists():
   assert time.monotonic()<deadline;time.sleep(.02)
if phase=='resume-start' and fault in ('loss','root-swap','parent-swap','copy-swap'):
 raise SystemExit(255)
if phase=='resume-start' and fault in ('wrong-token','missing-token'):
 request['token']='d'*64 if fault=='wrong-token' else ''
# Precise A/B substitution before claim. Both roots are retained after the call.
selected=replaced=None
if (phase=='claim' and fault in ('root-swap','parent-swap','copy-swap','missing-root')) or (phase=='resume-start' and fault in ('between-root','between-parent')):
 import shutil
 root=Path(config['root']);selected=root.parent if fault in ('parent-swap','between-parent') else root
 replaced=selected.with_name(selected.name+'-original');selected.rename(replaced)
 if fault in ('copy-swap','between-root','between-parent'):shutil.copytree(replaced,selected)
 elif fault!='missing-root':selected.mkdir(mode=0o700)
 if fault=='parent-swap':root.mkdir(mode=0o700)
argv=[{'/opt/clubs-bot-stage':config['compose'],INCIDENT_OWNER:config['owner'],
       INCIDENT_REVISION:config['revision'],INCIDENT_IMAGE:config['image']}.get(a,a) for a in argv]
env=config['helper_env'].copy(); env['FAKE_FLOCK_REAL']='yes'
if phase=='claim' and fault=='late-compete' and not leader:env['FIXTURE_LATE_CLAIM']='yes'
# Faults enter at an explicit test interpreter boundary. Poison imports stay on
# disk and are not used to inject faults into the isolated production startup.
if request['binding'] is not None:
 request['binding']['incident']=dict(owner=config['owner'],environment='stage',revision=config['revision'],image=config['image'])
 request['binding']['compose']['path']=config['compose']
request_bytes=json.dumps(request,separators=(',',':')).encode()
payload=struct.pack('!II',len(request_bytes),len(helper))+request_bytes+helper
record('helper',phase=phase)
result=subprocess.run([REAL_PYTHON,'-I','-S','-B','-c',config['bootstrap'],*argv],input=payload,
                      env=env,cwd=config.get('cwd'),capture_output=True)
record('phase_result',phase=phase,code=result.returncode)
if phase=='claim' and fault=='compete':
 # Concurrent helpers still race on their own locks/O_EXCL. Hold only the
 # transport ACK until both finish, so this positive schedule has no later
 # losing claim holding a lock across the winner's distinct resume phase.
 deadline=time.monotonic()+30
 while sum(row['kind']=='phase_result' and row.get('phase')=='claim' for row in map(json.loads,audit.read_text().splitlines()))<2:
  assert time.monotonic()<deadline;time.sleep(.02)
if fault=='late-compete':
 if phase=='claim' and leader:
  Path(str(audit)+'.leader-done').touch()
  deadline=time.monotonic()+30
  while not Path(str(audit)+'.loser-held').exists():
   assert time.monotonic()<deadline;time.sleep(.02)
 if phase=='resume-start':Path(str(audit)+'.resume-done').touch()
if config.get('hook') and phase==config.get('hook_phase','claim') and config.get('swap_path'):
 possible=Path(config['swap_path']);saved=possible.with_name(possible.name+'-held-original')
 if saved.exists():
  if possible.is_dir():record('hook_root',identity=[possible.stat().st_dev,possible.stat().st_ino])
  possible.rename(possible.with_name(possible.name+'-hook-target'));saved.rename(possible)
if selected is not None and fault!='missing-root':
 root=Path(config['root']);record('claim_root',device=root.stat().st_dev,inode=root.stat().st_ino)
 selected.rename(selected.with_name(selected.name+'-claim-target'));replaced.rename(selected)
if phase=='claim' and result.returncode==0:
 record('claim_ack',root=config['root'],identity=[Path(config['root']).stat().st_dev,Path(config['root']).stat().st_ino])
 if fault=='kill-after-claim':os.kill(os.getppid(),signal.SIGKILL);raise SystemExit(0)
 if fault=='claim-ack-loss':raise SystemExit(255)
if phase=='resume-start' and fault=='resume-ack-loss':raise SystemExit(255)
output=result.stdout
if result.returncode==0 and phase=='inspect':
 lines=output.splitlines();candidate=json.loads(lines[1].split(b' ',1)[1])
 candidate['incident']=dict(owner=INCIDENT_OWNER,environment='stage',revision=INCIDENT_REVISION,image=INCIDENT_IMAGE)
 candidate['compose']['path']='/opt/clubs-bot-stage'
 output=lines[0]+b'\ncorrected-binding-candidate:v=1 '+json.dumps(candidate,sort_keys=True,separators=(',',':')).encode()+b'\n'
sys.stdout.buffer.write(output);raise SystemExit(result.returncode)
'''
        for key,value in {'CONTROL_PATH' :repr(str(self.control)), 'AUDIT_PATH':repr(str(self.audit)), 'REAL_PYTHON':repr(sys.executable),
                          'INCIDENT_OWNER':repr(executor.INCIDENT['RELEASE_OWNER']),
                          'INCIDENT_REVISION':repr(executor.INCIDENT['EXPECTED_REVISION']),
                          'INCIDENT_IMAGE':repr(executor.INCIDENT['IMAGE_DIGEST'])}.items():
            source=source.replace(key,value)
        state.write_executable(self.bin/'ssh',source)
        self.install_interpreter_fixture()

    def authorize(self, run_number=None):
        if run_number is not None:self.env['GITHUB_RUN_NUMBER']=run_number
        digest=hashlib.sha256(json.dumps(json.loads(self.env['CLB82_AUTHORIZED_ROOT_BINDING']),sort_keys=True,separators=(',',':')).encode()).hexdigest()
        self.env['AUTHORIZATION']=executor.authorization(self.env['GITHUB_RUN_NUMBER'],IMPLEMENTATION,digest)

    def install_interpreter_fixture(self):
        source=r'''#!/usr/bin/env -S REAL_PYTHON -I -S -B
import os,sys,json
from pathlib import Path
arguments=sys.argv[1:]
config=json.loads(Path(CONTROL_PATH).read_text());fault=config['fault']
if arguments[:5]==['-I','-S','-B','-c',arguments[4] if len(arguments)>4 else None] and 'class BoundContext:' in arguments[4]:
 code=arguments[4]
 if arguments[-1]=='claim' and os.environ.get('FIXTURE_LATE_CLAIM')=='yes':
  # Pause the late real claim inside ready(), after helper-owned lock acquire.
  # The real resume must fail busy, consume no additional authority and never
  # retry; no lock or guard is mocked by this scheduling hook.
  injection=r"""
original_ready=BoundContext.ready
def delayed_ready(self):
 import time
 from pathlib import Path
 Path(AUDIT+'.loser-held').touch()
 deadline=time.monotonic()+30
 while not Path(AUDIT+'.resume-done').exists():
  assert time.monotonic()<deadline;time.sleep(.02)
 return original_ready(self)
BoundContext.ready=delayed_ready
""".replace('AUDIT',repr(config['audit']))
  code=code.replace('context = None\ntry:',injection+'\ncontext = None\ntry:')
 # All overrides are test-only Python source injections, restricted to claim.
 if arguments[-1]=='claim' and fault in ('write','fsync','parent-fsync','short-write'):
  prefix="import os,stat\n_marker="+repr(str(Path(config['root'])/'.clb82-resume-start-33468965282-1.consumed'))+"\n_root="+repr(config['root'])+"\n"
  prefix+="def selected(fd,path):\n try:return os.fstat(fd).st_ino==os.stat(path).st_ino\n except FileNotFoundError:return False\n"
  if fault in ('write','short-write'):
   prefix+="_original_write=os.write\ndef write(fd,data):\n if selected(fd,_marker):\n"
   prefix+=("  raise OSError('synthetic private write detail')\n" if fault=='write' else "  return _original_write(fd,data[:7])\n")
   prefix+=" return _original_write(fd,data)\nos.write=write\n"
  else:
   prefix+="_original_fsync=os.fsync\ndef sync(fd):\n if selected(fd,"+('_root' if fault=='parent-fsync' else '_marker')+"):raise OSError('synthetic private fsync detail')\n return _original_fsync(fd)\nos.fsync=sync\n"
  code=prefix+code
 if config.get('hook') and arguments[-1]==config.get('hook_phase','claim'):
  injection=r"""
def fixture_swap(self):
 from pathlib import Path
 import shutil
 selected=Path(SWAP_PATH);saved=selected.with_name(selected.name+'-held-original')
 is_file=selected.is_file();selected.rename(saved)
 if COPY_SWAP:
  if is_file:shutil.copy2(saved,selected)
  else:shutil.copytree(saved,selected)
 elif is_file:selected.touch(mode=0o600)
 else:selected.mkdir(mode=0o700)
HOOK_BODY
"""
  hook=config['hook']
  if hook=='after-open':
   body='original=BoundContext.open_context\ndef changed(self):\n original(self);fixture_swap(self)\nBoundContext.open_context=changed'
  elif hook=='before-config':
   body='original=BoundContext.compose_call\ndef changed(self,args,normalize=False):\n if normalize:fixture_swap(self)\n return original(self,args,normalize)\nBoundContext.compose_call=changed'
  elif hook=='finalizer':
   body='original=BoundContext.operation_result\ndef changed(self,result,category):\n if category!="operation_in_progress":fixture_swap(self)\n return original(self,result,category)\nBoundContext.operation_result=changed'
  elif hook=='signal-after-begun':
   body='original=BoundContext.rpc\ndef changed(self,args):\n answer=original(self,args)\n if args==["transition","migration_completed","candidate_start_begun"]:os.kill(os.getpid(),15)\n return answer\nBoundContext.rpc=changed'
  elif hook=='signal-after-claim':
   body='original=BoundContext.claim\ndef changed(self):\n answer=original(self);os.kill(os.getpid(),15)\n return answer\nBoundContext.claim=changed'
  else:raise AssertionError('unknown hook')
  injection=injection.replace('SWAP_PATH',repr(config.get('swap_path',''))).replace('COPY_SWAP',repr(config.get('copy_swap',False))).replace('HOOK_BODY',body)
  code=code.replace('context = None\ntry:',injection+'\ncontext = None\ntry:')
 if arguments[-1]=='claim':
  audit_prefix=r"""
import os,json
_real_open=os.open
_real_fsync=os.fsync
_claim_fd=None
_claim_root=None
_claim_file_synced=False
def audit_open(path,flags,*args,**kwargs):
 global _claim_fd,_claim_root
 fd=_real_open(path,flags,*args,**kwargs)
 if path=='.clb82-resume-start-33468965282-1.consumed' and flags & os.O_EXCL:
  _claim_fd=fd;v=os.fstat(kwargs['dir_fd']);_claim_root=[v.st_dev,v.st_ino]
 return fd
def audit_fsync(fd):
 global _claim_file_synced
 result=_real_fsync(fd)
 if fd==_claim_fd:_claim_file_synced=True
 v=os.fstat(fd)
 if _claim_file_synced and [v.st_dev,v.st_ino]==_claim_root:
  log=_real_open(AUDIT_PATH,os.O_WRONLY|os.O_APPEND)
  os.write(log,(json.dumps({'kind':'claim_durable','identity':_claim_root})+'\n').encode());os.close(log)
 return result
os.open=audit_open
os.fsync=audit_fsync
"""
  code=audit_prefix.replace('AUDIT_PATH',repr(str(config['audit'])))+code
 arguments[4]=code
os.execv(REAL_PYTHON,[REAL_PYTHON,*arguments])
'''
        # env -S splits the single optional shebang argument used by Linux.
        # The absolute interpreter avoids recursively finding this wrapper.
        source=source.replace('REAL_PYTHON',repr(sys.executable)).replace('CONTROL_PATH',repr(str(self.control)))
        state.write_executable(self.helper.fake_bin/'python3',source)

    def save(self, fault=None):
        if fault is not None:self.config['fault']=fault
        self.control.write_text(json.dumps(self.config))

    def run(self, *arguments, **changes):
        return subprocess.run([sys.executable,'-I','-S','-B',str(RUNNER),*arguments],env={**self.env,**changes},capture_output=True,timeout=180)

    def counts(self):
        entries=[json.loads(line)['kind'] for line in self.audit.read_text().splitlines()]
        return {**{key:entries.count(key) for key in ('claim_attempt','claim_ack','resume','status','transport','helper')}, 'claim':entries.count('claim_durable')}


class PriorApiDiagnosticsTest(unittest.TestCase):
    """Instrumentation contract with real verifier argv, synthetic API/CLI only."""
    PHASES = ('workflow_metadata', 'run_attempt', 'source_workflow', 'source_status_script',
              'source_private_root', 'source_status_pattern', 'source_authority',
              'attempt_jobs', 'producer_job_logs')
    HOSTILE = ('private-body\ncorrected-prior-api:v=1 phase=forged failure=forged\n'
               'Authorization: Bearer synthetic-github-token https://signed.invalid/log?token=secret '
               '/private/credentials/config private-header-value')

    def setUp(self):
        # main() legitimately tightens umask; restore the test process afterward.
        previous_umask = os.umask(0o077)
        os.umask(previous_umask)
        self.addCleanup(os.umask, previous_umask)
        temporary = external_git_fixture('clb91-diagnostics-')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.bin = self.root / 'bin'; self.bin.mkdir()
        self.env = {**request(), 'PATH': str(self.bin) + os.pathsep + os.environ['PATH'],
                    'UNRELATED_SECRET': self.HOSTILE}
        self.api = PriorApiFixture(self.root, self.bin, self.env)
        revision = self.env['PRIOR_STATUS'].split(':')[2]
        self.paths = ['actions/workflows/release-status.yml', self.api.run_path,
                      *[f'contents/{path}?ref={revision}' for path in authority.PRODUCER_PATHS],
                      self.api.jobs_path, self.api.logs_path]
        self.original = json.loads(json.dumps(self.api.responses))
        self.ssh_calls = self.root / 'ssh-called'
        state.write_executable(self.bin/'ssh', '#!/bin/sh\n: > ' + shlex.quote(str(self.ssh_calls)) + '\nexit 99\n')

    def inject(self, index, code=1):
        responses = json.loads(json.dumps(self.original))
        responses[self.paths[index]] = {'_error': code, '_stderr': self.HOSTILE, '_body': self.HOSTILE}
        self.api.path.write_text(json.dumps(responses))
        self.api.calls_path.unlink(missing_ok=True)

    def cli(self, *args):
        return subprocess.run([sys.executable, '-I', '-S', '-B', str(RUNNER), *args],
                              env=self.env, capture_output=True, timeout=15)

    def assert_terminal(self, result, phase, failure, category='PRIOR_STATUS_UNAVAILABLE'):
        self.assertEqual(1, result.returncode)
        self.assertEqual('', result.stderr.decode())
        self.assertEqual(f'corrected-prior-api:v=1 phase={phase} failure={failure}\n'
                         f'corrected-stage:v=1 result=blocked category={category}\n', result.stdout.decode())
        for private in (self.HOSTILE, 'synthetic-github-token', 'Authorization', 'https://', '/private/credentials'):
            self.assertNotIn(private, (result.stdout + result.stderr).decode())
        self.assertFalse(self.ssh_calls.exists())

    def test_positive_real_verifier_contract_and_result_unchanged(self):
        seen = []
        original_capture = executor.capture
        def recording(argv, **kwargs):
            seen.append((argv, kwargs))
            return original_capture(argv, **kwargs)
        with patch.object(executor, 'capture', side_effect=recording), contextlib.redirect_stdout(io.StringIO()) as output:
            actual = executor.verify_prior(self.env, executor.prior_api_capture)
        self.assertEqual('', output.getvalue())
        expected = authority.verify_prior(self.env, original_capture)
        self.assertEqual(expected, actual)
        self.assertEqual({'reference', 'job_id', 'evidence_sha256'}, set(actual))
        self.assertEqual(self.env['PRIOR_STATUS'], actual['reference'])
        self.assertEqual(333, actual['job_id'])
        self.assertRegex(actual['evidence_sha256'], r'^[0-9a-f]{64}$')
        self.assertEqual(10, len(seen))
        probe_argv, probe_kwargs = seen.pop(8)
        self.assertEqual(['gh', 'api', '--help'], probe_argv)
        self.assertEqual(5, probe_kwargs['timeout'])
        self.assertEqual(32768, probe_kwargs['limit'])
        config_dir = probe_kwargs['env'].pop('GH_CONFIG_DIR')
        self.assertFalse(Path(config_dir).exists())
        self.assertEqual({'PATH': self.env['PATH'], 'LC_ALL': 'C', 'GH_PROMPT_DISABLED': '1',
                          'GH_NO_UPDATE_NOTIFIER': '1', 'GH_TELEMETRY': '0'}, probe_kwargs['env'])
        self.assertEqual(self.PHASES, tuple(name for name, _ in executor.PRIOR_API_PHASES))
        for index, (argv, kwargs) in enumerate(seen):
            self.assertEqual(['gh', 'api', '--hostname', 'github.com', '--method', 'GET', '-H',
                              'X-GitHub-Api-Version: 2026-03-10', 'repos/koteev-m/clubs_bot/' + self.paths[index]], argv)
            self.assertEqual(30, kwargs['timeout'])
            self.assertEqual(1048576 if index == 8 else 262144, kwargs['limit'])
            self.assertEqual({'PATH': self.env['PATH'], 'GH_TOKEN': self.env['GITHUB_TOKEN'],
                              'GH_PROMPT_DISABLED': '1', 'GH_NO_UPDATE_NOTIFIER': '1',
                              'GH_TELEMETRY': '0', 'LC_ALL': 'C'}, kwargs['env'])
        result = self.cli('--verify-prior')
        self.assertEqual(0, result.returncode)
        self.assertEqual(b'corrected-stage:v=1 result=ok category=PRIOR_STATUS_VERIFIED\n', result.stdout)
        self.assertEqual(b'', result.stderr)

    def test_every_phase_fails_closed_in_both_production_call_sites(self):
        for args in (('--verify-prior',), ()):
            for index, phase in enumerate(self.PHASES):
                with self.subTest(args=args, phase=phase):
                    self.inject(index)
                    self.assert_terminal(self.cli(*args), phase, 'command_failed')
                    calls = [json.loads(line) for line in self.api.calls_path.read_text().splitlines()]
                    self.assertEqual(['repos/koteev-m/clubs_bot/' + p for p in self.paths[:index+1]],
                                     [argv[-1] for argv in calls])
                    # Real main and verifier, with no disabled validation predicate.
                    # Prove even snapshot/helper setup cannot run after rejection.
                    with patch.dict(os.environ, self.env, clear=True), patch.object(sys, 'argv', [str(RUNNER), *args]), \
                            patch.object(executor, 'helper_snapshot') as snapshot, \
                            patch.object(executor, 'pinned_hosts') as hosts, patch.object(executor, 'execute') as execute, \
                            patch.object(executor.signal, 'signal'), contextlib.redirect_stdout(io.StringIO()) as output:
                        with self.assertRaisesRegex(executor.AuthorityError, '^PRIOR_STATUS_UNAVAILABLE$'):
                            executor.main()
                        self.assertEqual(f'corrected-prior-api:v=1 phase={phase} failure=command_failed\n', output.getvalue())
                        snapshot.assert_not_called(); hosts.assert_not_called(); execute.assert_not_called()

    def test_capture_sentinel_classes_in_both_call_sites(self):
        for code, failure in ((124, 'timeout'), (125, 'output_limit')):
            for args in (('--verify-prior',), ()):
                with self.subTest(code=code, args=args):
                    self.inject(8, code)
                    self.assert_terminal(self.cli(*args), 'producer_job_logs', failure)
                    self.assertEqual(9, len(self.api.calls_path.read_text().splitlines()))

    def test_real_spawn_oserror_preserves_local_failure(self):
        # Missing executable in an isolated PATH fails inside Popen. No fallback
        # to an installed gh, shell interpreter or real GitHub endpoint is possible.
        (self.bin/'gh').unlink()
        self.env['PATH'] = str(self.bin)
        for args in (('--verify-prior',), ()):
            with self.subTest(args=args):
                self.assert_terminal(self.cli(*args), 'workflow_metadata', 'spawn_failed', 'LOCAL_FAILURE')
                self.assertFalse(self.api.calls_path.exists())

    def test_spawn_exception_identity_and_internal_errors_are_not_reclassified(self):
        argv = ['gh', 'api', '--hostname', 'github.com', '--method', 'GET', '-H',
                'X-GitHub-Api-Version: 2026-03-10', 'repos/koteev-m/clubs_bot/' + self.paths[0]]
        for target, exception, marker in (
                ('subprocess.Popen', OSError(self.HOSTILE), True),
                ('subprocess.Popen', ValueError(self.HOSTILE), False),
                ('os.set_blocking', OSError(self.HOSTILE), False)):
            with self.subTest(target=target, exception=type(exception).__name__):
                owner, attribute = target.split('.')
                with patch.object(getattr(executor, owner), attribute, side_effect=exception), \
                        contextlib.redirect_stdout(io.StringIO()) as output:
                    with self.assertRaises(type(exception)) as caught:
                        executor.prior_api_capture(argv, timeout=30, limit=262144, env=self.env)
                    self.assertIs(exception, caught.exception)
                self.assertEqual('corrected-prior-api:v=1 phase=workflow_metadata failure=spawn_failed\n'
                                 if marker else '', output.getvalue())

    def test_unknown_contract_fails_before_capture_without_echo(self):
        good = ['gh', 'api', '--hostname', 'github.com', '--method', 'GET', '-H',
                'X-GitHub-Api-Version: 2026-03-10', 'repos/koteev-m/clubs_bot/' + self.paths[0]]
        cases = [(good + [self.HOSTILE], 30, 262144), (good[:-1] + [self.HOSTILE], 30, 262144),
                 (['curl'] + good[1:], 30, 262144), (good[:5] + ['POST'] + good[6:], 30, 262144),
                 (good, 31, 262144), (good, 30, 1048576)]
        for endpoint in ('actions/runs/1/attempts/0', 'actions/runs/1/jobs?per_page=100',
                         'actions/jobs/333/logs?token=secret', 'contents/scripts/deploy/other.py?ref=' + 'b'*40,
                         'actions/workflows/release-status.yml\n', 'actions/workflows/release-status.yml/extra'):
            cases.append((good[:-1] + ['repos/koteev-m/clubs_bot/' + endpoint], 30, 262144))
        for argv, timeout, limit in cases:
            with self.subTest(argv=argv, timeout=timeout, limit=limit), \
                    patch.object(executor, 'capture') as capture, contextlib.redirect_stdout(io.StringIO()) as output:
                with self.assertRaisesRegex(executor.Rejected, '^PRIOR_API_CONTRACT_INVALID$'):
                    executor.prior_api_capture(argv, timeout=timeout, limit=limit, env=self.env)
                capture.assert_not_called()
                self.assertEqual('', output.getvalue())

    def test_internal_programming_exception_keeps_terminal_local_failure(self):
        launcher = ('import runpy, sys\nfrom unittest.mock import patch\n'
                    'runner = sys.argv.pop(1)\n'
                    'with patch("subprocess.Popen", side_effect=ValueError("private internal detail")):\n'
                    '    runpy.run_path(runner, run_name="__main__")\n')
        for args in (('--verify-prior',), ()):
            with self.subTest(args=args):
                result = subprocess.run([sys.executable, '-I', '-S', '-B', '-c', launcher, str(RUNNER), *args],
                                        env=self.env, capture_output=True, timeout=15)
                self.assertEqual(1, result.returncode)
                self.assertEqual(b'corrected-stage:v=1 result=blocked category=LOCAL_FAILURE\n', result.stdout)
                self.assertEqual(b'', result.stderr)
                self.assertFalse(self.api.calls_path.exists())
                self.assertFalse(self.ssh_calls.exists())


class ProducerLogEscapeCompatibilityTest(unittest.TestCase):
    """Modern/legacy CLI emulation at the real bounded process boundary."""
    setUp = PriorApiDiagnosticsTest.setUp
    cli = PriorApiDiagnosticsTest.cli
    assert_terminal = PriorApiDiagnosticsTest.assert_terminal
    HOSTILE = '\x1b[31m' + PriorApiDiagnosticsTest.HOSTILE + '\x1b[0m'

    def configure(self, modern=True, **probe):
        self.api.responses['_cli'] = {
            'help': self.api.MODERN_HELP if modern else self.api.OLD_HELP,
            'modern': modern, **probe}
        original = self.original[self.api.logs_path]
        self.raw = (self.HOSTILE + '\n' + original + '\x1b[2Ktail\n').encode()
        self.api.write(self.raw.decode())
        self.api.calls_path.unlink(missing_ok=True)

    def test_modern_guard_requires_scoped_flag_and_preserves_exact_bytes(self):
        self.configure()
        with self.assertRaisesRegex(authority.AuthorityError, '^PRIOR_STATUS_UNAVAILABLE$'):
            authority.verify_prior(self.env, executor.capture)
        self.api.calls_path.unlink()
        original_capture = executor.capture
        retrieved = []
        def at_verifier_boundary(argv, **kwargs):
            result = executor.prior_api_capture(argv, **kwargs)
            if argv[-1].endswith('/logs'):
                retrieved.append(result)
            return result
        with contextlib.redirect_stdout(io.StringIO()) as output:
            actual = authority.verify_prior(self.env, at_verifier_boundary)
        self.assertEqual('', output.getvalue())
        self.assertEqual([(0, self.raw)], retrieved)
        self.assertIn(b'\x1b', retrieved[0][1])
        expected = hashlib.sha256(self.original[self.api.logs_path].split(authority.PREFIX)[1].strip().encode()).hexdigest()
        self.assertEqual(expected, actual['evidence_sha256'])
        calls = [json.loads(line) for line in self.api.calls_path.read_text().splitlines()]
        self.assertEqual(9, len(calls))
        for index, argv in enumerate(calls):
            self.assertEqual(['--allow-escape-sequences'] if index == 8 else [], argv[7:-1])
            self.assertEqual('repos/koteev-m/clubs_bot/' + self.paths[index], argv[-1])
        self.assertIs(executor.capture, original_capture)

    def test_both_call_sites_modern_and_old_pass_prior_before_helper_or_ssh(self):
        for modern in (True, False):
            with self.subTest(modern=modern):
                self.configure(modern)
                self.env['CLB82_APPROVED_IMPLEMENTATION'] = ''
                for args, category, code in ((('--verify-prior',), 'PRIOR_STATUS_VERIFIED', 0),
                                              ((), 'IMPLEMENTATION_NOT_APPROVED', 1)):
                    result = self.cli(*args)
                    self.assertEqual(code, result.returncode)
                    verdict = 'ok' if code == 0 else 'blocked'
                    self.assertEqual(f'corrected-stage:v=1 result={verdict} category={category}\n'.encode(), result.stdout)
                    self.assertEqual(b'', result.stderr)
                    self.assertFalse(self.ssh_calls.exists())
                calls = [json.loads(line) for line in self.api.calls_path.read_text().splitlines()]
                self.assertEqual(18, len(calls))
                for argv in calls:
                    self.assertEqual(modern and argv[-1].endswith('/logs'), '--allow-escape-sequences' in argv)

    def test_probe_failures_stop_before_log_and_never_leak_in_both_call_sites(self):
        for code, failure in ((1, 'command_failed'), (124, 'timeout'), (125, 'output_limit')):
            for args in (('--verify-prior',), ()):
                with self.subTest(code=code, args=args):
                    self.configure(code=code, help=self.HOSTILE, stderr=self.HOSTILE)
                    self.assert_terminal(self.cli(*args), 'producer_job_logs', failure)
                    self.assertEqual(8, len(self.api.calls_path.read_text().splitlines()))

    def test_help_must_be_valid_and_flag_must_be_declared_in_flags(self):
        for help_data in ('', self.HOSTILE, 'FLAGS\n  --allow-escape-sequences   allowed\n\n',
                          self.api.OLD_HELP.replace('FLAGS', 'OTHER')):
            with self.subTest(help=repr(help_data)):
                self.configure(help=help_data)
                self.assert_terminal(self.cli('--verify-prior'), 'producer_job_logs', 'command_failed')
                self.assertEqual(8, len(self.api.calls_path.read_text().splitlines()))
        for help_data in (self.api.OLD_HELP + '\nEXAMPLES\n  gh api --allow-escape-sequences\n',
                          self.api.OLD_HELP.replace('--method', '--allow-escape-sequences-extra')):
            with self.subTest(help=repr(help_data)):
                self.configure(False, help=help_data)
                self.assertEqual(0, self.cli('--verify-prior').returncode)
                self.assertNotIn('--allow-escape-sequences"', self.api.calls_path.read_text())

    def test_real_probe_timeout_output_limit_and_spawn_failure(self):
        for body, failure, category in (
                ('import time; time.sleep(10)', 'timeout', 'PRIOR_STATUS_UNAVAILABLE'),
                ('import sys; sys.stdout.write("x"*32769)', 'output_limit', 'PRIOR_STATUS_UNAVAILABLE'),
                (None, 'spawn_failed', 'LOCAL_FAILURE')):
            with self.subTest(failure=failure):
                self.configure()
                gh = self.bin / 'gh'
                source = gh.read_text()
                if body is None:
                    # The eighth successful API process removes this fixture
                    # executable before the help process is spawned.
                    source = source.replace('value=responses[',
                        'if sys.argv[-1].endswith("jobs?per_page=100"): Path(sys.argv[0]).unlink()\nvalue=responses[')
                    self.env['PATH'] = str(self.bin)
                    (self.bin/'python3').symlink_to(sys.executable)
                else:
                    source = source.replace("assert set(os.environ)<=", body + '\n assert set(os.environ)<=')
                gh.write_text(source)
                self.assert_terminal(self.cli('--verify-prior'), 'producer_job_logs', failure, category)
                self.assertEqual(8, len(self.api.calls_path.read_text().splitlines()))
                # Restore the fixture for the next independent case.
                self.api = PriorApiFixture(self.root, self.bin, self.env)

    def test_modern_transport_failure_and_real_log_limit_remain_closed(self):
        for response, failure in (({'_error': 1, '_body': self.HOSTILE, '_stderr': self.HOSTILE}, 'command_failed'),
                                  (self.HOSTILE + 'x'*1048576, 'output_limit')):
            for args in (('--verify-prior',), ()):
                with self.subTest(failure=failure, args=args):
                    self.configure()
                    self.api.responses[self.api.logs_path] = response
                    self.api.path.write_text(json.dumps(self.api.responses))
                    self.assert_terminal(self.cli(*args), 'producer_job_logs', failure)
                    self.assertEqual(9, len(self.api.calls_path.read_text().splitlines()))

    def test_modern_opt_out_does_not_bypass_evidence_or_source_authentication(self):
        cases = [('malformed', None, None), ('duplicate', None, None),
                 ('evidence_run', 'GITHUB_RUN_ID', '1'), ('evidence_attempt', 'GITHUB_RUN_ATTEMPT', '2'),
                 ('evidence_sha', 'GITHUB_SHA', 'a'*40), ('evidence_job', 'GITHUB_JOB', 'validate'), ('source', None, None),
                 ('run', 'id', 1), ('run', 'run_attempt', 2), ('run', 'head_sha', 'a'*40),
                 ('job', 'run_id', 1), ('job', 'head_sha', 'a'*40)]
        for kind, key, value in cases:
            with self.subTest(kind=kind, key=key):
                self.api.responses = json.loads(json.dumps(self.original))
                self.configure()
                if kind == 'malformed':
                    self.api.responses[self.api.logs_path] = authority.PREFIX + '{invalid}\n'
                elif kind == 'duplicate':
                    self.api.responses[self.api.logs_path] *= 2
                elif kind.startswith('evidence_'):
                    evidence = {**self.api.value, key: value}
                    self.api.responses[self.api.logs_path] = self.HOSTILE + '\n' + authority.PREFIX + json.dumps(evidence) + '\n'
                elif kind == 'source':
                    self.api.responses[self.paths[2]]['sha'] = 'a'*40
                elif kind == 'run':
                    self.api.responses[self.api.run_path][key] = value
                else:
                    self.api.responses[self.api.jobs_path]['jobs'][1][key] = value
                self.api.path.write_text(json.dumps(self.api.responses))
                result = self.cli('--verify-prior')
                self.assertEqual(1, result.returncode)
                category = 'LOCAL_FAILURE' if kind == 'malformed' else 'PRIOR_STATUS_INVALID'
                self.assertEqual(f'corrected-stage:v=1 result=blocked category={category}\n'.encode(), result.stdout)
                self.assertEqual(b'', result.stderr)
                self.assertFalse(self.ssh_calls.exists())


class V3EvidenceTest(unittest.TestCase):
    """Real isolated producer -> persisted-log boundary -> independent verifier.

    Only GitHub GET responses are synthetic; no SSH or helper execution.
    """
    def setUp(self):
        temporary = external_git_fixture('clb90-v3-')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        bin_path = self.root / 'bin'
        bin_path.mkdir()
        self.env = {**request(), 'PATH': str(bin_path) + os.pathsep + os.environ['PATH']}
        self.api = PriorApiFixture(self.root, bin_path, self.env)

    def produce(self, overrides=None, status=None):
        run, attempt, sha = self.env['PRIOR_STATUS'].split(':')
        env = {**os.environ, **self.env, 'GITHUB_RUN_ID': run, 'GITHUB_RUN_ATTEMPT': attempt,
               'GITHUB_SHA': sha, 'GITHUB_JOB': 'status',
               'GITHUB_WORKFLOW_REF': f'{authority.REPOSITORY}/{authority.WORKFLOW}@refs/heads/main',
               'REQUESTED_OPERATION': 'start', 'EXPECTED_HELPER_SHA256': authority.INCIDENT_HELPER_SHA256,
               **(overrides or {})}
        return subprocess.run([sys.executable, '-I', '-S', '-B', str(ROOT/'scripts/deploy/release_authority.py'),
                               'produce', status or self.api.value['status']],
                              env=env, capture_output=True, timeout=10)

    def verify(self, overrides=None):
        return authority.verify_prior({**self.env, **(overrides or {})}, executor.capture)

    def test_exact_positive_and_single_immutable_incident_source(self):
        self.assertIs(executor.INCIDENT, executor._authority.INCIDENT)
        self.assertEqual(dict(authority.INCIDENT), dict(executor.INCIDENT))
        with self.assertRaises(TypeError):
            executor.INCIDENT['IMAGE_DIGEST'] = 'substitution'
        result = self.produce({'exact_incident': 'false'})
        self.assertEqual(0, result.returncode, result.stdout)
        value = json.loads(result.stdout.decode().removeprefix(authority.PREFIX))
        self.assertIs(value['exact_incident'], True)
        self.assertNotIn('IMAGE_DIGEST', value)
        self.assertNotIn(authority.INCIDENT['IMAGE_DIGEST'].encode(), result.stdout)
        self.assertNotIn(hashlib.sha256(authority.INCIDENT['IMAGE_DIGEST'].encode()).hexdigest().encode(), result.stdout)
        self.api.write(result.stdout.decode())
        self.assertEqual(333, self.verify()['job_id'])

    def test_persisted_log_digest_masking_preserves_positive_v3(self):
        produced = self.produce()
        self.assertEqual(0, produced.returncode)
        digest = authority.INCIDENT['IMAGE_DIGEST']
        # Simulate GitHub rewriting a public digest (whole or substring), with
        # actual producer stdout and unrelated step text in the persisted log.
        original = '2026-09-10T00:00:00Z input image=' + digest + '\n' + produced.stdout.decode()
        for mask in (digest, digest.split(':')[-1][8:20]):
            with self.subTest(mask_length=len(mask)):
                masked = original.replace(mask, '***')
                self.assertNotEqual(original, masked)
                self.assertNotIn(digest, masked)
                self.api.write(masked)
                self.assertEqual(333, self.verify()['job_id'])

    def test_other_valid_inputs_cannot_attest_even_with_caller_true(self):
        cases = [{'IMAGE_DIGEST': 'ghcr.io/koteev-m/clubs_bot/app-bot@sha256:' + 'a'*64},
                 {'APP_ENV': 'prod', 'INCIDENT_TAG': 'deploy-prod-deadbee'},
                 {'INCIDENT_TAG': 'deploy-stage-deadbee'}, {'RELEASE_OWNER': '112233-1'},
                 {'EXPECTED_REVISION': 'c'*40}, {'REQUESTED_OPERATION': 'resume-start'},
                 {'EXPECTED_HELPER_SHA256': executor.IMPLEMENTATION_SHA256}]
        for overrides in cases:
            with self.subTest(overrides=overrides):
                produced = self.produce({**overrides, 'exact_incident': 'true'})
                self.assertEqual(0, produced.returncode, produced.stdout)
                value = json.loads(produced.stdout.decode().removeprefix(authority.PREFIX))
                self.assertIs(value['exact_incident'], False)
                self.assertNotIn('IMAGE_DIGEST', value)
                self.api.write(produced.stdout.decode())
                with self.assertRaises(authority.AuthorityError):
                    self.verify()

    def test_verifier_independently_rejects_own_incident_substitution(self):
        for key, value in authority.INCIDENT.items():
            replacement = ('ghcr.io/koteev-m/clubs_bot/app-bot@sha256:' + 'a'*64
                           if key == 'IMAGE_DIGEST' else value + '0')
            with self.subTest(key=key):
                # Keep the authenticated positive producer output unchanged.
                with self.assertRaises(authority.AuthorityError), patch.object(executor, 'capture') as capture:
                    authority.verify_prior({**self.env, key: replacement}, capture)
                capture.assert_not_called()

    def test_attestation_false_missing_wrong_type_and_malformed_fail_closed(self):
        original = self.api.value.copy()
        for attestation in (False, None, 0, 1, 'true', 'false', [], {}, {'exact': True}):
            with self.subTest(attestation=attestation):
                self.api.value = {**original, 'exact_incident': attestation}
                self.api.write()
                with self.assertRaises(authority.AuthorityError):
                    self.verify()
        self.api.value = {k: v for k, v in original.items() if k != 'exact_incident'}
        self.api.write()
        with self.assertRaises(authority.AuthorityError):
            self.verify()
        self.api.write(authority.PREFIX + '{"exact_incident":tru}\n')
        with self.assertRaises(json.JSONDecodeError):
            self.verify()

    def test_v2_masked_unmasked_and_mixed_records_have_no_authority(self):
        v2 = {k: v for k, v in self.api.value.items() if k != 'exact_incident'}
        v2['IMAGE_DIGEST'] = authority.INCIDENT['IMAGE_DIGEST']
        old = 'release-status-evidence:v=2 ' + json.dumps(v2) + '\n'
        positive = self.api.responses[self.api.logs_path]
        for log in (old, old.replace(v2['IMAGE_DIGEST'], '***'), old + positive):
            with self.subTest(masked='***' in log, mixed=positive in log):
                self.api.write(log)
                with self.assertRaises(authority.AuthorityError):
                    self.verify()

    def test_duplicate_spoofed_or_masked_v3_records_fail_closed(self):
        original = self.api.responses[self.api.logs_path]
        duplicate_key = original.replace('"exact_incident": true', '"exact_incident": false,"exact_incident": true')
        for log in (original*2, duplicate_key, authority.PREFIX+'{}\n',
                    original.replace('"exact_incident": true', '"exact_incident": "***"'),
                    original + 'release-status-evidence:v=3 unavailable\n'):
            with self.subTest(log_length=len(log)):
                self.api.write(log)
                with self.assertRaises((authority.AuthorityError, json.JSONDecodeError)):
                    self.verify()

    def test_positive_attestation_does_not_bypass_run_job_or_source_authentication(self):
        original = json.loads(json.dumps(self.api.responses))
        mutations = [('run', 'id', 99), ('run', 'run_attempt', 2), ('run', 'head_sha', 'a'*40),
                     ('run', 'head_repository', {'full_name': 'attacker/repo'}),
                     ('run', 'status', 'in_progress'), ('job', 'head_sha', 'c'*40),
                     ('job', 'conclusion', 'failure'), ('job', 'head_branch', 'feature'),
                     ('job', 'name', 'copied-status')]
        for target, key, replacement in mutations:
            with self.subTest(target=target, key=key):
                self.api.responses = json.loads(json.dumps(original))
                selected = (self.api.responses[self.api.run_path] if target == 'run'
                            else self.api.responses[self.api.jobs_path]['jobs'][1])
                selected[key] = replacement
                self.api.write()
                with self.assertRaises(authority.AuthorityError):
                    self.verify()
        for fault in ('failed-step', 'duplicate-step', *authority.PRODUCER_PATHS):
            with self.subTest(fault=fault):
                self.api.responses = json.loads(json.dumps(original))
                steps = self.api.responses[self.api.jobs_path]['jobs'][1]['steps']
                if fault == 'failed-step':
                    steps[0]['conclusion'] = 'failure'
                elif fault == 'duplicate-step':
                    steps.append(steps[0].copy())
                else:
                    source = self.api.responses[f'contents/{fault}?ref=' + 'b'*40]
                    changed = base64.b64decode(source['content']) + b'\n# substituted producer\n'
                    source.update(content=base64.b64encode(changed).decode(), size=len(changed),
                                  sha=hashlib.sha1(f'blob {len(changed)}\0'.encode()+changed).hexdigest())
                self.api.write()
                with self.assertRaises(authority.AuthorityError):
                    self.verify()

    def test_trust_state_and_principal_path_guards_remain_required(self):
        original = self.api.value.copy()
        for key, replacement in [('status_available', 'no'), ('owner_match', 'no'),
                                 ('revision_match', 'no'), ('digest_match', 'no'),
                                 ('failure_category', 'untrusted_state_root'),
                                 ('checkpoint', 'candidate_start_begun'), ('migration_evidence', 'absent'),
                                 ('operation_result', 'unavailable')]:
            with self.subTest(key=key):
                fields = dict(part.split('=', 1) for part in original['status'].split()[1:])
                fields[key] = replacement
                line = 'release-status:v=1 ' + ' '.join(f'{k}={v}' for k, v in fields.items())
                self.api.value = {**original, 'status': line}
                self.api.write()
                with self.assertRaises(authority.AuthorityError):
                    self.verify()
                if key in ('status_available', 'owner_match', 'revision_match', 'digest_match', 'failure_category'):
                    produced = self.produce(status=line)
                    self.assertNotEqual(0, produced.returncode)
                    self.assertEqual(b'release-status-evidence:v=3 unavailable\n', produced.stdout)
        for key in ('principal_sha256', 'compose_path_sha256'):
            self.api.value = {**original, key: 'f'*64}
            self.api.write()
            with self.assertRaises(authority.AuthorityError):
                self.verify()


class AuthorityRegressionTest(unittest.TestCase):
    def assert_blocked(self, fixture, result):
        self.assertNotEqual(0,result.returncode,result.stdout)
        self.assertEqual(0,fixture.counts()['resume'])
        for sensitive in ('synthetic-private-value','synthetic private API diagnostics','synthetic private fsync detail',
                          'synthetic private write detail','synthetic-github-token'):
            self.assertNotIn(sensitive.encode(),result.stdout+result.stderr)

    def test_f1_missing_reference_and_offline_validation_are_distinct(self):
        f=AuthorityCliFixture(self)
        for reference in ('','yes','123:latest:'+'b'*40,'123:1:bad','$(echo injected)'):
            for args in ((),('--validate',)):
                with self.subTest(reference=reference,args=args):
                    self.assert_blocked(f,f.run(*args,PRIOR_STATUS=reference,GITHUB_OUTPUT=str(f.root/'outputs')))
        self.assertEqual(0,f.counts()['status'])
        result=f.run('--validate',GITHUB_OUTPUT=str(f.root/'outputs'))
        self.assertEqual(0,result.returncode)
        self.assertIn(b'SYNTAX_VALIDATED',result.stdout)

    def test_f1_original_readonly_producer_emits_only_verified_provenance(self):
        response=status_record(checkpoint='migration_completed',operation_result='remote_failure',resume_permitted='no')
        for raw,code,accepted in ((response,0,True),(b'private malformed result\n',0,False),(response,255,False)):
            with self.subTest(code=code,accepted=accepted):
                h=RunnerHarness(response=raw,ssh_exit=code,ssh_mode='transport' if code else 'execute')
                try:
                    result=h.run({'STATUS_PROVENANCE':'yes','GITHUB_REPOSITORY':authority.REPOSITORY,
                        'GITHUB_EVENT_NAME':'workflow_dispatch','GITHUB_REF':'refs/heads/main','GITHUB_REF_TYPE':'branch',
                        'GITHUB_JOB':'status','GITHUB_WORKFLOW_REF':f'{authority.REPOSITORY}/{authority.WORKFLOW}@refs/heads/main',
                        'GITHUB_RUN_ID':'876543','GITHUB_RUN_ATTEMPT':'1','GITHUB_SHA':'b'*40})
                    lines=[line for line in result.stdout.decode().splitlines() if line.startswith(authority.PREFIX)]
                    self.assertEqual(accepted,result.returncode==0,result.stdout)
                    self.assertEqual(1 if accepted else 0,len(lines))
                    self.assertEqual(1,h.ssh_count)
                    if accepted:
                        value=json.loads(lines[0].removeprefix(authority.PREFIX))
                        self.assertEqual(response.decode().strip(),value['status'])
                        self.assertEqual(h.helper_hash,value['EXPECTED_HELPER_SHA256'])
                        self.assertEqual(h.release_owner,value['RELEASE_OWNER'])
                        self.assertIs(value['exact_incident'], False)
                        self.assertNotIn('IMAGE_DIGEST', value)
                    self.assertNotIn(b'private malformed',result.stdout+result.stderr)
                    self.assertNotIn(h.environment['SSH_KNOWN_HOSTS'].encode(),result.stdout+result.stderr)
                    h.assert_cleaned(self)
                finally:h.close()

    def test_f1_authenticated_attempt_incident_and_result_negative_matrix(self):
        f=AuthorityCliFixture(self)
        original=json.loads(json.dumps(f.api.responses)); value=f.api.value.copy()
        mutations=[('run','repository',{'full_name':'attacker/repo'}),('run','workflow_id',999),
                   ('run','run_attempt',2),('run','head_sha','c'*40),('run','event','push'),
                   ('run','head_branch','feature'),('run','conclusion','failure'),
                   ('value','GITHUB_RUN_ID','123'),('value','GITHUB_RUN_ATTEMPT','2'),
                   ('value','GITHUB_JOB','other'),('value','APP_ENV','prod'),('value','RELEASE_OWNER','123-1'),
                   ('value','INCIDENT_TAG','deploy-stage-deadbee'),('value','EXPECTED_REVISION','c'*40),
                   ('value','IMAGE_DIGEST','invalid'),('value','REQUESTED_OPERATION','resume-start'),
                   ('value','EXPECTED_HELPER_SHA256',executor.IMPLEMENTATION_SHA256),
                   ('value','principal_sha256','f'*64),('value','status','success')]
        for target,key,replacement in mutations:
            with self.subTest(target=target,key=key):
                f.api.responses=json.loads(json.dumps(original));f.api.value=value.copy()
                (f.api.responses[f.api.run_path] if target=='run' else f.api.value)[key]=replacement
                f.api.write();self.assert_blocked(f,f.run())
        for fault in ('no-line','duplicate','malformed','api-error','wrong-job-attempt','wrong-job','producer-bytes'):
            with self.subTest(fault=fault):
                f.api.responses=json.loads(json.dumps(original));f.api.value=value.copy()
                log=None
                if fault=='no-line':log='conclusion=success\n'
                if fault=='duplicate':log=original[f.api.logs_path]*2
                if fault=='malformed':log=authority.PREFIX+'{"fake":true}\n'
                if fault=='api-error':f.api.responses[f.api.run_path]={'_error':True}
                if fault=='wrong-job-attempt':f.api.responses[f.api.jobs_path]['jobs'][1]['run_id']=123
                if fault=='wrong-job':f.api.responses[f.api.jobs_path]['jobs'][1]['name']='other'
                if fault=='producer-bytes':
                    path=next(k for k in f.api.responses if k.startswith('contents/'))
                    f.api.responses[path]['content']=base64.b64encode(b'forged').decode()
                f.api.write(log);self.assert_blocked(f,f.run())
        self.assertEqual(0,f.counts()['status'])

    def test_f1_old_selector_no_is_accepted_but_readiness_and_authorization_are_separate(self):
        f=AuthorityCliFixture(self,actual=True)
        self.assertIn('resume_permitted=no',f.api.value['status'])
        result=f.run(ACTION='inspect',AUTHORIZATION='')
        self.assertEqual(0,result.returncode,result.stdout)
        self.assertEqual(0,f.counts()['claim'])
        self.assert_blocked(f,f.run(AUTHORIZATION=''))
        f.helper.update_docker_state(app_state='replaced')
        self.assert_blocked(f,f.run())
        self.assertEqual(0,f.counts()['claim'])

    def test_f2_two_processes_lost_submission_ack_consume_once(self):
        for actual in (True,):
            with self.subTest(actual=actual):
                f=AuthorityCliFixture(self,actual=actual);f.save('loss')
                first=f.run();self.assertNotEqual(0,first.returncode)
                self.assertIn(b'SEPARATE_DECISION_REQUIRED',first.stdout)
                self.assertTrue(f.marker.exists())
                f.save('')
                second=f.run();self.assertNotEqual(0,second.returncode)
                self.assertIn(b'AUTHORIZATION_CONSUMED_OR_UNKNOWN',second.stdout)
                self.assertEqual(1,f.counts()['claim']);self.assertEqual(1,f.counts()['resume'])
                if actual:
                    self.assertEqual(0,f.helper.docker_state()['start_invocations'])
                    self.assertEqual(1,f.helper.docker_state()['migration_invocations'])
                    self.assertEqual('start',f.helper.result()['requested_operation'])
                print('authority-regression-evidence:'+json.dumps({'case':'lost-submission-ack','actual_helper':actual,
                      **f.counts(),'starts':f.helper.docker_state()['start_invocations'] if actual else None,
                      'migrations':f.helper.docker_state()['migration_invocations'] if actual else None,
                      'first_exit':first.returncode,'second_exit':second.returncode},sort_keys=True),flush=True)

    def test_f2_durable_claim_interruption_failures_and_changed_context(self):
        for fault in ('kill-after-claim','claim-ack-loss','fsync','parent-fsync','write'):
            with self.subTest(fault=fault):
                f=AuthorityCliFixture(self);f.save(fault)
                first=f.run();self.assert_blocked(f,first);self.assertTrue(f.marker.exists())
                saved=f.marker.read_bytes();f.save('')
                other=f.root/'new-runner-temp';other.mkdir(mode=0o700)
                second=f.run(TMPDIR=str(other),GITHUB_RUN_ID='987655')
                self.assert_blocked(f,second)
                self.assertEqual(saved,f.marker.read_bytes())
                self.assertEqual(0,f.counts()['resume'])
                self.assert_blocked(f,f.run(GITHUB_RUN_ATTEMPT='2'))
                self.assert_blocked(f,f.run(GITHUB_RUN_ID='987656',GITHUB_RUN_NUMBER='8',
                                            AUTHORIZATION=executor.authorization('8', IMPLEMENTATION, hashlib.sha256(json.dumps(json.loads(f.env['CLB82_AUTHORIZED_ROOT_BINDING']),sort_keys=True,separators=(',',':')).encode()).hexdigest())))
        f=AuthorityCliFixture(self);f.save('missing-root')
        self.assert_blocked(f,f.run());self.assertFalse(f.remote_root.exists())
        f.save('');self.assert_blocked(f,f.run());self.assertFalse(f.remote_root.exists())

    def test_f2_existing_partial_malformed_or_unsafe_claim_never_becomes_empty(self):
        for kind in ('empty','malformed','symlink','directory'):
            with self.subTest(kind=kind):
                f=AuthorityCliFixture(self)
                if kind=='directory':f.marker.mkdir()
                elif kind=='symlink':f.marker.symlink_to(f.root/'absent')
                else:f.marker.write_text('' if kind=='empty' else 'malformed')
                self.assert_blocked(f,f.run())
                self.assertEqual(0,f.counts()['claim'])
                self.assertTrue(f.marker.exists() or f.marker.is_symlink())

    def test_f2_competing_cli_processes_one_claim_one_submission(self):
        f=AuthorityCliFixture(self,actual=True);f.save('compete')
        children=[subprocess.Popen([sys.executable, '-I', '-S', '-B',str(RUNNER)],env=f.env,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
                  for _ in range(2)]
        results=[(p.communicate(timeout=100),p.returncode) for p in children]
        self.assertEqual(1,f.counts()['claim'],results)
        self.assertEqual(1,f.counts()['resume'])
        self.assertEqual(1,f.helper.docker_state()['start_invocations'])
        self.assertEqual(1,f.helper.docker_state()['migration_invocations'])
        self.assertTrue(any(code==0 for _,code in results),results)

    def test_f2_late_competing_claim_blocks_resume_without_retry(self):
        f=AuthorityCliFixture(self,actual=True);f.save('late-compete')
        children=[subprocess.Popen([sys.executable,'-I','-S','-B',str(RUNNER)],env=f.env,
                                  stdout=subprocess.PIPE,stderr=subprocess.PIPE) for _ in range(2)]
        results=[(p.communicate(timeout=100),p.returncode) for p in children]
        self.assertTrue(all(code==1 for _,code in results),results)
        self.assertEqual(2,f.counts()['claim_attempt'])
        self.assertEqual(1,f.counts()['claim']);self.assertEqual(1,f.counts()['claim_ack'])
        self.assertEqual(1,f.counts()['resume'])
        self.assertEqual(0,f.helper.docker_state()['start_invocations'])
        self.assertEqual(1,f.helper.docker_state()['migration_invocations'])
        phases=[json.loads(line) for line in f.audit.read_text().splitlines()]
        self.assertEqual([1],[row['code'] for row in phases if row['kind']=='phase_result' and row['phase']=='resume-start'])
        saved=f.marker.read_bytes();f.save('')
        restarted=f.run();self.assertEqual(1,restarted.returncode,restarted.stdout)
        self.assertIn(b'AUTHORIZATION_CONSUMED_OR_UNKNOWN',restarted.stdout)
        self.assertEqual(saved,f.marker.read_bytes());self.assertEqual(1,f.counts()['resume'])
        print('authority-regression-evidence:'+json.dumps({'case':'late-claim-busy-resume',**f.counts(),
              'starts':0,'migrations':1,'exits':[code for _,code in results]},sort_keys=True))

    def test_f2_success_preserves_helper_records_health_and_blocks_restart(self):
        f=AuthorityCliFixture(self,actual=True)
        records={p.name:p.read_bytes() for p in f.helper.ledger_dir.iterdir()}
        retained=f.helper.retained.read_bytes()
        result=f.run();self.assertEqual(0,result.returncode,result.stdout)
        self.assertIn(b'category=SUCCESS',result.stdout)
        self.assertIs(True,json.loads(f.marker.read_text())['consumed'])
        self.assertEqual(1,f.counts()['claim']);self.assertEqual(1,f.counts()['resume'])
        self.assertEqual(1,f.helper.docker_state()['start_invocations'])
        self.assertEqual(1,f.helper.docker_state()['migration_invocations'])
        self.assertEqual(records,{p.name:p.read_bytes() for p in f.helper.ledger_dir.iterdir()})
        self.assertEqual(retained,f.helper.retained.read_bytes())
        self.assertTrue(f.helper.docker_state()['migration_exists'])
        commands=[' '.join(c) for c in f.helper.docker_commands()]
        self.assertTrue(any('/ready' in c for c in commands));self.assertTrue(any('/health' in c for c in commands))
        self.assertNotEqual(0,f.run().returncode)
        self.assertEqual(1,f.counts()['resume'])
        print('authority-regression-evidence:'+json.dumps({'case':'first-success',**f.counts(),
              'starts':f.helper.docker_state()['start_invocations'],
              'migrations':f.helper.docker_state()['migration_invocations'],'first_exit':result.returncode},sort_keys=True),flush=True)




class ImportRootRegressionTest(unittest.TestCase):
    def test_interpreter_fixture_isolated_startup_with_linux_shebang_argv(self):
        f=AuthorityCliFixture(self)
        poison=f.root/'shebang-poison';poison.mkdir()
        for name in ('json.py','sitecustomize.py','usercustomize.py'):
            (poison/name).write_text("raise SystemExit('poisoned startup')\n")
        wrapper=f.helper.fake_bin/'python3'
        interpreter,optional=wrapper.read_text().splitlines()[0][2:].split(' ',1)
        args=['-I','-S','-B','-c','import sys,json; print(json.dumps([sys.flags.isolated,sys.flags.no_site,sys.dont_write_bytecode]))']
        env={**f.helper.env,'PYTHONPATH':str(poison),'PYTHONHOME':str(poison/'missing'),'PYTHONUSERBASE':str(poison)}
        # Emulate execve's single optional argument without pretending to run a
        # Linux kernel; also exercise the native host's normal script startup.
        for argv in ([interpreter,optional,str(wrapper),*args],[str(wrapper),*args]):
            with self.subTest(argv=argv[:2]):
                result=subprocess.run(argv,env=env,cwd=poison,capture_output=True,timeout=30)
                self.assertEqual(0,result.returncode,result.stderr)
                self.assertEqual(b'[1, 1, true]\n',result.stdout)
        self.assertTrue(all((poison/name).is_file() for name in ('json.py','sitecustomize.py','usercustomize.py')))

    @staticmethod
    def producer_env(f):
        run, attempt, sha = f.env['PRIOR_STATUS'].split(':')
        return {**f.env, 'STATUS_PROVENANCE': 'yes', 'GITHUB_RUN_ID': run,
                'GITHUB_RUN_ATTEMPT': attempt, 'GITHUB_SHA': sha, 'GITHUB_JOB': 'status',
                'GITHUB_WORKFLOW_REF': f'{authority.REPOSITORY}/{authority.WORKFLOW}@refs/heads/main',
                'REQUESTED_OPERATION': 'start', 'EXPECTED_HELPER_SHA256': authority.INCIDENT_HELPER_SHA256}

    def prior_checkout(self, f):
        prior = f.root/'prior-checkout'
        for path in (*authority.PRODUCER_PATHS, 'scripts/deploy/corrected-stage-release.py'):
            dest = prior/path; dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT/path, dest)
        return prior

    def test_producer_shadow_import_and_startup_cannot_forge_owner(self):
        f = AuthorityCliFixture(self, actual=True)
        old = subprocess.run(['/bin/bash', '-s', '--', 'status', state.OWNER, 'stage',
                              str(f.helper.compose_path), state.REVISION, state.DIGEST, 'start'],
                             input=f.helper.retained.read_bytes(), env=f.helper.env, capture_output=True, timeout=80)
        self.assertEqual(0, old.returncode)
        self.assertIn(b'resume_permitted=no', old.stdout)
        prior = self.prior_checkout(f); deploy = prior/'scripts/deploy'
        import py_compile
        cached_source = f.root/'cached-poison.py'
        cached_source.write_text("raise SystemExit('untrusted cached dependency')\n")
        cached_target = Path(importlib.util.cache_from_source(str(deploy/'release_private_root.py')))
        cached_target.parent.mkdir(exist_ok=True)
        py_compile.compile(str(cached_source), cfile=str(cached_target), doraise=True,
                           invalidation_mode=py_compile.PycInvalidationMode.UNCHECKED_HASH)
        # This is the reviewer's actual import substitution, left in place.
        (deploy/'json.py').write_text("""import sys,importlib,os
mine=os.path.dirname(__file__)
sys.path[:]=[x for x in sys.path if x!=mine]
sys.modules.pop('json',None)
real=importlib.import_module('json')
original=real.dumps
def altered(value,*args,**kwargs):
 if isinstance(value,dict) and 'RELEASE_OWNER' in value:
  value=dict(value);value['RELEASE_OWNER']='33468965282-1'
 return original(value,*args,**kwargs)
real.dumps=altered
sys.modules['json']=real
""")
        poison = f.root/'startup'; poison.mkdir()
        hook = f.root/'startup-ran'
        body = f"from pathlib import Path\nPath({str(hook)!r}).write_text('untrusted startup')\n"
        (poison/'sitecustomize.py').write_text(body)
        (poison/'usercustomize.py').write_text(body)
        (poison/'startup.py').write_text(body)
        user_site = poison/f'lib/python{sys.version_info.major}.{sys.version_info.minor}/site-packages'
        user_site.mkdir(parents=True)
        (user_site/'usercustomize.py').write_text(body)
        overrides = {'PYTHONPATH': str(poison), 'PYTHONUSERBASE': str(poison),
                     'PYTHONSTARTUP': str(poison/'startup.py')}
        # Prove the fixture is active without changing or deleting its poison.
        subprocess.run([sys.executable, '-c', 'pass'], env={**os.environ, **overrides}, check=True)
        self.assertTrue(hook.exists()); stamp = hook.stat().st_mtime_ns
        pb = f.root/'producer-bin'; pb.mkdir()
        state.write_executable(pb/'ssh', '#!/usr/bin/env -S python3 -I -S -B\nimport sys\nsys.stdin.buffer.read()\n'
                               + 'sys.stdout.buffer.write('+repr(old.stdout)+')\n')
        env = {**self.producer_env(f), **overrides, 'PYTHONHOME': str(poison/'nonexistent'),
               'PATH': str(pb)+os.pathsep+os.environ['PATH'], 'RELEASE_OWNER': '112233-1'}
        raw = subprocess.run(['/bin/bash', str(deploy/'read-only-release-status.sh')],
                             cwd=deploy, env=env, capture_output=True, timeout=60)
        self.assertEqual(0, raw.returncode, raw.stdout)
        lines = [line for line in raw.stdout.decode().splitlines() if line.startswith(authority.PREFIX)]
        self.assertEqual(1, len(lines))
        self.assertEqual('112233-1', json.loads(lines[0][len(authority.PREFIX):])['RELEASE_OWNER'])
        self.assertEqual(stamp, hook.stat().st_mtime_ns)
        self.assertTrue(all((prior/path).read_bytes()==(ROOT/path).read_bytes() for path in authority.PRODUCER_PATHS))
        f.api.write(log=raw.stdout.decode())
        verified, executed = f.run('--verify-prior'), f.run()
        self.assertEqual([1, 1], [verified.returncode, executed.returncode])
        self.assertEqual({'claim_attempt':0, 'claim':0, 'claim_ack':0, 'resume':0, 'status':0, 'transport':0, 'helper':0}, f.counts())
        # Positive control: the same producer, poisoned directories and prior
        # old-helper resume=no are accepted with the actual authorized owner.
        raw = subprocess.run(['/bin/bash', str(deploy/'read-only-release-status.sh')], cwd=deploy,
                             env={**env, 'RELEASE_OWNER':executor.INCIDENT['RELEASE_OWNER']},
                             capture_output=True, timeout=60)
        self.assertEqual(0, raw.returncode, raw.stdout)
        f.api.write(log=raw.stdout.decode())
        result = f.run('--verify-prior', **overrides, PYTHONHOME=str(poison/'nonexistent'))
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertEqual(stamp, hook.stat().st_mtime_ns)
        print('import-root-evidence:'+json.dumps({'case':'producer-shadow', **f.counts(), 'starts':0, 'migrations':1,
              'producer_owner':'112233-1', 'forged_exits':[verified.returncode,executed.returncode],
              'positive_exit':result.returncode}), flush=True)

    def test_own_dependencies_bypass_pyc_and_all_producer_sources_are_verified(self):
        import py_compile
        f = AuthorityCliFixture(self); prior = self.prior_checkout(f)
        for name in ('release_authority.py', 'release_private_root.py'):
            source = prior/'scripts/deploy'/name
            poison = f.root/(name+'.poison')
            poison.write_text("print('forged cached module'); raise SystemExit(0)\n")
            cache = Path(importlib.util.cache_from_source(str(source)))
            cache.parent.mkdir(exist_ok=True)
            py_compile.compile(str(poison), cfile=str(cache), doraise=True,
                               invalidation_mode=py_compile.PycInvalidationMode.UNCHECKED_HASH)
        raw = subprocess.run([sys.executable, '-I', '-S', '-B', str(prior/'scripts/deploy/corrected-stage-release.py'),
                              '--verify-prior'], env=f.env, capture_output=True)
        self.assertEqual(0, raw.returncode, raw.stdout)
        self.assertIn(b'PRIOR_STATUS_VERIFIED', raw.stdout); self.assertNotIn(b'forged', raw.stdout)
        alias = f.root/'entry-alias'; alias.mkdir()
        (alias/'corrected.py').symlink_to(prior/'scripts/deploy/corrected-stage-release.py')
        for name in ('release_authority.py', 'release_private_root.py'):
            (alias/name).write_text("print('forged alias dependency'); raise SystemExit(0)\n")
        aliased = subprocess.run([sys.executable, '-I', '-S', '-B', str(alias/'corrected.py'), '--verify-prior'],
                                 env=f.env, capture_output=True)
        self.assertEqual(0, aliased.returncode, aliased.stdout)
        self.assertIn(b'PRIOR_STATUS_VERIFIED', aliased.stdout); self.assertNotIn(b'forged', aliased.stdout)
        original = json.loads(json.dumps(f.api.responses))
        for path in authority.PRODUCER_PATHS:
            with self.subTest(path=path):
                f.api.responses = json.loads(json.dumps(original))
                key = next(k for k in f.api.responses if k.startswith('contents/'+path+'?'))
                item = f.api.responses[key]
                data = base64.b64decode(item['content'])+b'\n# different executable dependency\n'
                item.update(content=base64.b64encode(data).decode(), size=len(data),
                            sha=hashlib.sha1(f'blob {len(data)}\0'.encode()+data).hexdigest())
                f.api.write(); self.assertEqual(1, f.run().returncode)
        f.api.responses = original
        f.api.write(log=original[f.api.logs_path].replace(authority.PREFIX, 'release-status-evidence:v=1 '))
        self.assertEqual(1, f.run('--verify-prior').returncode)
        self.assertEqual(0, f.counts()['status'])

    def test_remote_cwd_shadow_cannot_ack_without_claim_or_submit_twice(self):
        f=AuthorityCliFixture(self,actual=True)
        # Real id/findmnt are native tools. Their Python fixture stand-ins must
        # not add a startup/import surface before the actual isolated helper.
        # Leave the poisoned cwd/environment intact for every production child.
        for name in ('id','findmnt'):
            path=f.helper.fake_bin/name;source=path.read_text()
            self.assertTrue(source.startswith('#!/usr/bin/env python3\n'))
            state.write_executable(path,source.replace('#!/usr/bin/env python3\n',
                                                       '#!/usr/bin/env -S python3 -I -S -B\n',1))
        home=f.root/'remote-poison';home.mkdir()
        poison="import os\nprint('release-authorization:v=1 consumed=yes',flush=True)\nos._exit(0)\n"
        for name in ('json.py','sitecustomize.py','usercustomize.py','startup.py'):
            (home/name).write_text(poison)
        f.config['cwd']=str(home)
        f.config['helper_env'].update(PYTHONPATH=str(home),PYTHONUSERBASE=str(home),
            PYTHONHOME=str(home/'missing'),PYTHONSTARTUP=str(home/'startup.py'))
        f.save('loss');first=f.run()
        self.assertIn(b'SEPARATE_DECISION_REQUIRED',first.stdout)
        saved=f.marker.read_bytes()
        f.save('');second=f.run()
        self.assertEqual(saved,f.marker.read_bytes())
        self.assertTrue(all((home/name).is_file() for name in ('json.py','sitecustomize.py','usercustomize.py','startup.py')))
        self.assertEqual([1,1],[first.returncode,second.returncode])
        self.assertIn(b'SEPARATE_DECISION_REQUIRED',first.stdout)
        self.assertIn(b'AUTHORIZATION_CONSUMED_OR_UNKNOWN',second.stdout)
        self.assertEqual(1,f.counts()['claim']);self.assertEqual(1,f.counts()['resume'])
        self.assertEqual(0,f.helper.docker_state()['start_invocations'])
        self.assertEqual(1,f.helper.docker_state()['migration_invocations'])
        print('bound-evidence:'+json.dumps({'case':'remote-import',**f.counts(),'durable_claims':1,'starts':0,'migrations':1}),flush=True)

    def test_short_writes_preserve_one_consumed_record(self):
        f = AuthorityCliFixture(self); f.save('short-write')
        # Synthetic transport loses submission acknowledgement; it never fakes
        # lifecycle success. Real writes must still persist the complete record.
        source = (f.bin/'ssh').read_text()
        needle = "fault in ('loss','root-swap','parent-swap','copy-swap')"
        self.assertIn(needle, source)
        source = source.replace(needle, "fault in ('loss','short-write','root-swap','parent-swap','copy-swap')")
        state.write_executable(f.bin/'ssh', source)
        first = f.run(); record = json.loads(f.marker.read_text()); f.save(''); second = f.run()
        self.assertIs(True, record['consumed'])
        self.assertEqual([1,1], [first.returncode,second.returncode])
        self.assertEqual(1, f.counts()['claim']); self.assertEqual(1, f.counts()['resume'])

    def test_pinned_resume_cannot_adopt_external_application_lock(self):
        import fcntl
        f = AuthorityCliFixture(self, actual=True)
        with (f.helper.state_parent/'application.lock').open('rb') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            # A held advisory lock does not prevent an owner from renaming the
            # state directory. Restore it before invoking the exact helper.
            original = f.remote_root.with_name('held-stage')
            identity = f.remote_root.stat().st_ino
            f.remote_root.rename(original); f.remote_root.mkdir(mode=0o700)
            self.assertNotEqual(identity, f.remote_root.stat().st_ino)
            f.remote_root.rmdir(); original.rename(f.remote_root)
            result = subprocess.run(['/bin/bash', '-s', '--', 'resume', state.OWNER, 'stage', 'start',
                                     str(f.helper.compose_path), state.DIGEST, state.REVISION],
                                    input=executor.helper_snapshot(), env={**f.helper.env, 'FAKE_FLOCK_REAL':'yes'},
                                    pass_fds=(lock.fileno(),), capture_output=True, timeout=60)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual(0, f.helper.docker_state()['start_invocations'])
        self.assertEqual('start', f.helper.result()['requested_operation'])
        self.assertEqual(1, f.helper.docker_state()['migration_invocations'])

    def test_root_and_parent_substitution_must_not_authorize_two_resumes(self):
        # The original mandatory assertion remains ordinary, with fully copied B.
        for level,fault in (('root','root-swap'),('parent','parent-swap'),('copy','copy-swap')):
            with self.subTest(level=level):
                f=AuthorityCliFixture(self,actual=True)
                initial=[f.remote_root.stat().st_dev,f.remote_root.stat().st_ino]
                f.save(fault);first=f.run();f.save('');second=f.run()
                displaced=(f.remote_root.parent.with_name(f.remote_root.parent.name+'-claim-target')/'stage' if level=='parent'
                           else f.remote_root.with_name(f.remote_root.name+'-claim-target'))
                records=[p for p in (f.marker,displaced/f.marker.name) if p.is_file()]
                metrics={'case':level+'-substitution',**f.counts(),'durable_claims':len(records),
                         'root_identities':[[p.parent.stat().st_dev,p.parent.stat().st_ino] for p in records],
                         'initial_root':initial,'starts':f.helper.docker_state()['start_invocations'],
                         'migrations':f.helper.docker_state()['migration_invocations'],
                         'exits':[first.returncode,second.returncode]}
                print('bound-evidence:'+json.dumps(metrics),flush=True)
                self.assertEqual(1,metrics['migrations']);self.assertLessEqual(metrics['resume'],1,metrics)
                self.assertEqual(1,metrics['durable_claims']);self.assertEqual(1,metrics['starts'])
                self.assertEqual([1,0],metrics['exits']);self.assertTrue(displaced.is_dir())

    def test_fixed_binding_missing_malformed_and_candidate_is_not_authority(self):
        f=AuthorityCliFixture(self)
        invalid_principal=json.loads(f.env['CLB82_AUTHORIZED_ROOT_BINDING']);invalid_principal['principal']['uid']=0
        for pin in ('','{}','null','malformed',json.dumps(invalid_principal)):
            with self.subTest(pin=pin[:12]):
                result=f.run(CLB82_AUTHORIZED_ROOT_BINDING=pin)
                self.assertNotEqual(0,result.returncode);self.assertEqual(0,f.counts()['resume'])
        result=f.run(ACTION='inspect',AUTHORIZATION='',CLB82_AUTHORIZED_ROOT_BINDING='')
        self.assertEqual(0,result.returncode,result.stdout)
        self.assertIn(b'corrected-binding-candidate:v=1',result.stdout)
        self.assertNotIn(f.env['SSH_USER'].encode(),result.stdout+result.stderr)
        observed=next(line.split(b' ',1)[1] for line in result.stdout.splitlines() if line.startswith(b'corrected-binding-candidate:v=1 '))
        candidate=json.loads(observed)
        self.assertEqual({'name_sha256':hashlib.sha256(f.env['SSH_USER'].encode()).hexdigest(),'uid':os.geteuid()},candidate['principal'])
        public_auth=executor.authorization(f.env['GITHUB_RUN_NUMBER'],IMPLEMENTATION,hashlib.sha256(observed).hexdigest())
        rejected=f.run(CLB82_AUTHORIZED_ROOT_BINDING=observed.decode(),AUTHORIZATION=public_auth)
        self.assertNotEqual(0,rejected.returncode)
        self.assertIn(b'ROOT_BINDING_INVALID',rejected.stdout)
        self.assertFalse(f.marker.exists())
        # A subsequent mutation without fixed configuration cannot adopt output.
        self.assertNotEqual(0,f.run(CLB82_AUTHORIZED_ROOT_BINDING='').returncode)
        self.assertEqual(0,f.counts()['claim_attempt'])
        value=json.loads(f.env['CLB82_AUTHORIZED_ROOT_BINDING']);value['objects']['root'][1]+=1
        f.env['CLB82_AUTHORIZED_ROOT_BINDING']=json.dumps(value);f.authorize()
        self.assertNotEqual(0,f.run().returncode);self.assertFalse(f.marker.exists())

    def test_context_swap_at_configuration_read_is_rejected_before_claim(self):
        f=AuthorityCliFixture(self)
        for index,path in enumerate((f.helper.compose_path,f.helper.state_parent)):
            with self.subTest(path=path.name):
                f.config.update(hook='before-config',hook_phase='claim',swap_path=str(path),copy_swap=True);f.save()
                result=f.run();self.assertNotEqual(0,result.returncode)
                self.assertEqual(0,f.counts()['resume']);self.assertFalse(f.marker.exists())
                preserved=path.with_name(path.name+'-hook-target')
                self.assertTrue(preserved.exists());preserved.rename(f.root/('config-preserved-'+str(index)))
        f.config.update(hook='',swap_path='');f.save()
        self.assertEqual(0,f.run().returncode);self.assertEqual(1,f.counts()['claim'])
        self.assertEqual(1,f.helper.docker_state()['start_invocations'])

    def test_nested_directories_and_lock_substitution_after_context_open(self):
        # No claim is created by any injected pre-claim swap, so the same fixed
        # A and untouched authorization can test each independent rejected B.
        f=AuthorityCliFixture(self)
        paths=[f.helper.compose_path,f.helper.state_parent,f.remote_root,f.helper.lock_dir,
               f.helper.result_dir,f.helper.ledger_dir,f.helper.state_parent/'application.lock',f.helper.result_dir/'operation.lock']
        for path in paths:
            with self.subTest(path=path.name):
                f.config.update(hook='after-open',hook_phase='claim',swap_path=str(path),copy_swap=True);f.save()
                result=f.run();self.assertNotEqual(0,result.returncode,result.stdout)
                self.assertFalse(f.marker.exists());self.assertEqual(0,f.counts()['resume'])
                retained=path.with_name(path.name+'-hook-target')
                self.assertTrue(retained.exists())
                # Preserve B outside A's protocol tree after the dangerous call;
                # no fixture is removed before the attempted claim.
                retained.rename(f.root/('preserved-'+str(paths.index(path))))
        f.config.update(hook='',swap_path='');f.save()
        self.assertEqual(0,f.run().returncode)
        self.assertEqual(1,f.counts()['resume']);self.assertEqual(1,f.helper.docker_state()['start_invocations'])

    def test_wrong_missing_and_foreign_claim_token_block_resume(self):
        for fault in ('wrong-token','missing-token'):
            with self.subTest(fault=fault):
                f=AuthorityCliFixture(self);f.save(fault);first=f.run();saved=f.marker.read_bytes()
                self.assertNotEqual(0,first.returncode);self.assertEqual(1,f.counts()['resume'])
                self.assertEqual(0,f.helper.docker_state()['start_invocations'])
                f.save('');second=f.run();self.assertNotEqual(0,second.returncode)
                self.assertEqual(saved,f.marker.read_bytes());self.assertEqual(1,f.counts()['resume'])

    def test_signal_after_durable_claim_and_after_candidate_begun(self):
        for hook,phase,submissions,checkpoint in (('signal-after-claim','claim',0,'migration_completed'),
                                                ('signal-after-begun','resume-start',1,'candidate_start_begun')):
            with self.subTest(hook=hook):
                f=AuthorityCliFixture(self);f.config.update(hook=hook,hook_phase=phase);f.save()
                first=f.run();self.assertNotEqual(0,first.returncode)
                self.assertTrue(f.marker.exists());self.assertEqual(submissions,f.counts()['resume'])
                self.assertEqual(checkpoint,f.helper.checkpoint());self.assertEqual(0,f.helper.docker_state()['start_invocations'])
                f.config['hook']='';f.save();second=f.run();self.assertNotEqual(0,second.returncode)
                self.assertEqual(submissions,f.counts()['resume'])
                if phase=='resume-start':self.assertEqual('interrupted',f.helper.result()['failure_category'])

    def test_finalizer_writes_held_root_and_never_substituted_root(self):
        f=AuthorityCliFixture(self)
        f.helper.update_docker_state(fail_actions={'compose_up':1})
        f.config.update(hook='finalizer',hook_phase='resume-start',swap_path=str(f.remote_root),copy_swap=True);f.save()
        first=f.run();self.assertNotEqual(0,first.returncode)
        other=f.remote_root.with_name(f.remote_root.name+'-hook-target')
        retained=other/'clubs-bot-schema-stage.results'/f'{state.OWNER}.result'
        self.assertEqual('incomplete_unknown',state.parse_record(retained)['result'])
        self.assertEqual('remote_failure',f.helper.result()['result'])
        self.assertEqual('candidate_start_begun',f.helper.checkpoint())
        self.assertEqual(1,f.counts()['claim']);self.assertEqual(1,f.counts()['resume'])
        f.config.update(hook='',swap_path='');f.save();self.assertNotEqual(0,f.run().returncode)
        self.assertEqual(1,f.counts()['resume'])

    def test_between_claim_and_resume_copied_context_rejects_same_token(self):
        for fault in ('between-root','between-parent'):
            with self.subTest(fault=fault):
                f=AuthorityCliFixture(self);f.save(fault);first=f.run()
                self.assertNotEqual(0,first.returncode)
                self.assertEqual(1,f.counts()['claim']);self.assertEqual(1,f.counts()['resume'])
                self.assertEqual(0,f.helper.docker_state()['start_invocations'])
                copied=(f.remote_root.with_name(f.remote_root.name+'-claim-target') if fault=='between-root' else
                        f.remote_root.parent.with_name(f.remote_root.parent.name+'-claim-target')/'stage')
                self.assertEqual(f.marker.read_bytes(),(copied/f.marker.name).read_bytes())
                self.assertNotEqual(f.remote_root.stat().st_ino,copied.stat().st_ino)
                f.save('');second=f.run();self.assertNotEqual(0,second.returncode)
                self.assertEqual(1,f.counts()['resume']);self.assertEqual(1,f.helper.docker_state()['migration_invocations'])
                print('bound-evidence:'+json.dumps({'case':fault,**f.counts(),'record_copies':2,'starts':0,'migrations':1}),flush=True)

    def test_captured_configuration_interpolation_relative_paths_and_public_swap(self):
        f=AuthorityCliFixture(self)
        compose=f.helper.compose_path
        (compose/'docker-compose.yml').write_text('services:\n  app:\n    image: fixture\n    environment:\n      BOUND_VALUE: ${BOUND_LITERAL}\n      BOUND_DEFAULT: ${CLB82_UNSET:-fallback}\n  caddy:\n    image: caddy:fixture\n    volumes:\n      - ./Caddyfile:/etc/caddy/Caddyfile:ro\n')
        literal='synthetic$literal${still-literal}'
        (compose/'.env').write_text("BOUND_LITERAL='"+literal+"'\n");(compose/'.env').chmod(0o600)
        (compose/'Caddyfile').write_text('fixture only\n')
        observed=f.run(ACTION='inspect',AUTHORIZATION='',CLB82_AUTHORIZED_ROOT_BINDING='')
        self.assertEqual(0,observed.returncode,observed.stdout)
        line=next(line for line in observed.stdout.decode().splitlines() if line.startswith('corrected-binding-candidate:v=1 '))
        # Explicit fixture provisioning is separate from the production CLI.
        candidate=json.loads(line.split(' ',1)[1])
        self.assertEqual(hashlib.sha256(f.env['SSH_USER'].encode()).hexdigest(),candidate['principal']['name_sha256'])
        # An explicit fixture authority decision supplies the separately known
        # principal; the production CLI never turns public output into a pin.
        candidate['principal']={'name':f.env['SSH_USER'],'uid':candidate['principal']['uid']}
        f.env['CLB82_AUTHORIZED_ROOT_BINDING']=json.dumps(candidate,sort_keys=True,separators=(',',':'));f.authorize()
        source=(f.helper.fake_bin/'docker').read_text()
        source=source.replace('if command == "compose":','''if command == "compose":
    if "up" in args:
        selected=Path(os.environ['FAKE_BOUND_COMPOSE'])/'.env'
        selected.rename(selected.with_name('.env-preserved'))
        selected.write_text('BOUND_LITERAL=poisoned-public-config\\n');selected.chmod(0o600)
''',1)
        state.write_executable(f.helper.fake_bin/'docker',source)
        result=f.run()
        self.assertNotEqual(0,result.returncode)
        self.assertIn(b'STATUS_UNAVAILABLE',result.stdout)
        self.assertEqual(1,f.counts()['claim']);self.assertEqual(1,f.counts()['resume'])
        self.assertEqual(1,f.helper.docker_state()['start_invocations']);self.assertEqual(1,f.helper.docker_state()['migration_invocations'])
        self.assertEqual('success',f.helper.result()['result'])
        rows=[json.loads(line) for line in (f.helper.root/'bound-compose-captures.jsonl').read_text().splitlines()]
        parsed=[row['parsed'] for row in rows if 'parsed' in row and 'BOUND_VALUE' in row['parsed']['services']['app'].get('environment',{})]
        self.assertTrue(parsed)
        for model in parsed:
            # Compose serializes literal $ as $$ so that its model can be reused.
            self.assertEqual(literal.replace('$','$$'),model['services']['app']['environment']['BOUND_VALUE'])
            self.assertEqual('fallback',model['services']['app']['environment']['BOUND_DEFAULT'])
            self.assertEqual(str(compose/'Caddyfile'),model['services']['caddy']['volumes'][0]['source'])
        self.assertTrue((compose/'.env-preserved').is_file())
        self.assertNotIn(literal.encode(),result.stdout+result.stderr)
        self.assertNotIn(b'poisoned-public-config',result.stdout+result.stderr)
        self.assertNotEqual(0,f.run().returncode);self.assertEqual(1,f.counts()['resume'])

    def test_configuration_file_expansion_is_rejected_before_compose_reads(self):
        f=AuthorityCliFixture(self)
        capture=f.helper.root/'bound-compose-captures.jsonl'
        before=capture.read_bytes()
        for content in ('include: ./poison.yml\n',
                        'services: {app: {image: fixture, env_file: ./poison.env}}\n',
                        'services:\n  app:\n    image: fixture\n    env_file: ./poison.env\n',
                        'services:\n  app:\n    image: fixture\n    label_file: ./poison.env\n'):
            with self.subTest(content=content[:25]):
                (f.helper.compose_path/'docker-compose.yml').write_text(content)
                (f.helper.compose_path/'poison.env').write_text('sensitive-fixture-value')
                (f.helper.compose_path/'poison.yml').write_text('sensitive-fixture-value')
                result=f.run(ACTION='inspect',AUTHORIZATION='',CLB82_AUTHORIZED_ROOT_BINDING='')
                self.assertNotEqual(0,result.returncode)
                self.assertEqual(before,capture.read_bytes())
                self.assertTrue((f.helper.compose_path/'poison.env').is_file())
                self.assertEqual(0,f.counts()['resume']);self.assertFalse(f.marker.exists())



if __name__=='__main__':
    unittest.main(verbosity=2)
