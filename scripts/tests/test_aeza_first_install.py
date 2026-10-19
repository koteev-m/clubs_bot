#!/usr/bin/env python3
"""Synthetic filesystem/Engine and real Linux process tests; no daemon or secrets."""
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('initial', ROOT / 'scripts/deploy/aeza-initial/first-install.py')
M = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(M)
ENV = dict(CLB_PG_IMAGE='postgres@sha256:' + 'd' * 64, CLB_PLATFORM='linux/arm64', CLB_APP_IMAGE='ghcr.io/koteev-m/clubs_bot/app-bot@sha256:' + 'a' * 64,
           CLB_REVISION='b' * 40, CLB_DB_PASSWORD='synthetic_db_' + 'x' * 32,
           CLB_BOT_TOKEN='123456:' + 's' * 32, CLB_WEBHOOK_SECRET='s' * 32,
           CLB_OWNER_ID='123456', CLB_QR_SECRET='q' * 32, CLB_PHONE_KEY='p' * 32)


def fixture(root):
    root.mkdir(mode=0o700)
    for name in ('docker-compose.yml', '10-clubs-role.sql'):
        (root / name).write_bytes((M.TEMPLATE / name).read_bytes())
        (root / name).chmod(0o644)
    (root / '.env').write_text(''.join(k + '=' + v + '\n' for k, v in ENV.items()))
    (root / '.env').chmod(0o600)
    (root / 'secrets').mkdir(mode=0o700)
    for name, content in [('postgres_password', 'synthetic_admin_' + 'a' * 32)]:
        (root / 'secrets' / name).write_text(content)
        (root / 'secrets' / name).chmod(0o600)


class Engine(M.Install):
    def __init__(self, root):
        super().__init__(root)
        self.containers, self.networks, self.volumes, self.calls = [], [], [], []
        self.validator = b'schema-validation:v=1 result=compatible\n'
        self.invalid = None
        self.phase_failure = None
        self.stopped = False

    def ids(self, kind):
        return list({'container': self.containers, 'network': self.networks, 'volume': self.volumes}[kind])

    def inspect(self, kind, name):
        if kind == 'image':
            image = dict(Id='sha256:' + 'a' * 64, Os='linux', Architecture='arm64', RepoDigests=[name],
                         Config={'Env': ['PG_MAJOR=16'], 'Labels': {'org.opencontainers.image.revision': ENV['CLB_REVISION']}, 'User': '10001:10001'})
            if name.startswith('sha256:'):
                image['RepoDigests'] = [ENV['CLB_APP_IMAGE']]
            if self.invalid == 'platform':
                image['Architecture'] = 'amd64'
            if self.invalid == 'revision':
                image['Config']['Labels'] = {}
            if self.invalid == 'null_labels':
                image['Config']['Labels'] = None
            if self.invalid == 'pg_version':
                image['Config']['Env'] = ['PG_MAJOR=17']
            if self.invalid == 'digest':
                image['RepoDigests'] = []
            return image
        if kind == 'network':
            return dict(Internal=True, Labels={'com.docker.compose.project': M.PROJECT})
        service = 'db' if name == 'd' * 64 else 'app'
        app = service == 'app'
        return dict(Id=name, Image='sha256:' + 'a' * 64,
                    Config={'Image': ENV['CLB_APP_IMAGE'] if app else ENV['CLB_PG_IMAGE'],
                            'Labels': {'com.docker.compose.project': M.PROJECT, 'com.docker.compose.service': service,
                                       'com.docker.compose.oneoff': 'False'}},
                    State={'Running': not (app and self.stopped), 'Health': {'Status': 'healthy'}, 'StartedAt': '2026-10-19T00:00:00Z'},
                    RestartCount=0, HostConfig={'PortBindings': {'8080/tcp': [{'HostIp': '127.0.0.1', 'HostPort': '8081'}]} if app else {}},
                    NetworkSettings={'Networks': {M.PROJECT + '_database': {}}},
                    Mounts=[{'Destination': '/var/lib/postgresql/data', 'Type': 'volume', 'Name': M.PROJECT + '_pgdata'}])

    def run(self, args, timeout=30):
        self.calls.append(args)
        if args[:2] == ['docker', 'stop']:
            self.stopped = True
        if args[:2] == ['docker', 'compose']:
            if 'run' in args:
                if self.phase_failure == 'validator':
                    raise M.Refused('command_failed')
                if self.phase_failure == 'timeout':
                    raise subprocess.TimeoutExpired('synthetic', timeout)
                return self.validator
            if 'up' in args:
                if args[-1] == 'db':
                    self.containers = ['d' * 64]
                    self.networks = ['network-id']
                    self.volumes = [M.PROJECT + '_pgdata']
                else:
                    self.containers.append('c' * 64)
                    if self.phase_failure == 'start':
                        raise M.Refused('command_failed')
        return b''


class FirstInstallTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='clb91-first-install-')
        self.root = Path(self.temp.name).resolve() / 'application'
        fixture(self.root)
        self.engine = Engine(self.root)

    def tearDown(self):
        self.temp.cleanup()

    def test_order_and_real_baseline_contract_without_release_state(self):
        self.engine.database()
        self.engine.application()
        self.engine.verify()
        calls = self.engine.calls
        validate = next(i for i, a in enumerate(calls) if '/opt/app/bin/app-bot-validate-schema' in a)
        start = next(i for i, a in enumerate(calls) if 'up' in a and a[-1] == 'app')
        self.assertLess(validate, start)
        self.assertFalse((self.root / '.clubs-bot-release-state').exists())
        text = json.dumps(calls)
        for forbidden in ('sudo', 'ssh', 'pull always', 'migrate-and-validate', 'repair', 'baseline', 'caddy'):
            self.assertNotIn(forbidden, text)
        self.assertIn('baseline-observed', {p.name for p in self.engine.state.iterdir()})

    def test_bad_schema_output_never_starts(self):
        for output in (b'', b'schema-validation:v=1 result=rejected\n', b'schema-validation:v=1 result=compatible\nextra\n'):
            with self.subTest(output=output):
                with tempfile.TemporaryDirectory() as tmp:
                    root = Path(tmp).resolve() / 'root'
                    fixture(root)
                    e = Engine(root)
                    e.database()
                    e.validator = output
                    with self.assertRaises(M.Refused):
                        e.application()
                    self.assertFalse(any('up' in a and a[-1] == 'app' for a in e.calls))
                    with self.assertRaises(M.Refused):
                        e.application()

    def test_validator_exit_or_timeout_keeps_partial_attempt(self):
        self.engine.database()
        self.engine.phase_failure = 'validator'
        with self.assertRaises(M.Refused):
            self.engine.application()
        self.assertEqual(self.engine.containers, ['d' * 64])
        with self.assertRaises(M.Refused):
            self.engine.application()

    def test_validator_timeout(self):
        self.engine.database()
        self.engine.phase_failure = 'timeout'
        with self.assertRaises(subprocess.TimeoutExpired):
            self.engine.application()
        self.assertEqual(self.engine.containers, ['d' * 64])

    def test_missing_old_state_is_required(self):
        (self.root / '.clubs-bot-release-state').mkdir()
        with self.assertRaisesRegex(M.Refused, 'foreign_or_release_state'):
            Engine(self.root)

    def test_override_refused(self):
        (self.root / 'docker-compose.override.yml').write_text('services: {}')
        with self.assertRaises(M.Refused):
            Engine(self.root)

    def test_partial_db_refused(self):
        self.engine.state.mkdir()
        with self.assertRaises(M.Refused):
            self.engine.database()
        self.assertEqual(self.engine.calls, [])

    def test_repeated_db_refused(self):
        self.engine.database()
        with self.assertRaises(M.Refused):
            self.engine.database()

    def test_existing_project_refused(self):
        self.engine.containers = ['unowned']
        with self.assertRaisesRegex(M.Refused, 'existing_project'):
            self.engine.database()

    def test_existing_app_refused(self):
        self.engine.database()
        self.engine.containers.append('partial-app')
        with self.assertRaises(M.Refused):
            self.engine.application()

    def test_resource_continuity(self):
        self.engine.database()
        self.engine.volumes.append('foreign-volume')
        with self.assertRaisesRegex(M.Refused, 'resource_continuity'):
            self.engine.application()

    def test_image_contract(self):
        for invalid in ('platform', 'revision', 'null_labels', 'digest', 'pg_version'):
            with self.subTest(invalid=invalid):
                self.engine.invalid = invalid
                with self.assertRaises(M.Refused):
                    self.engine.database()
                self.assertFalse(self.engine.state.exists())

    def test_bad_configuration(self):
        original = (self.root / '.env').read_text()
        for before, after in [(ENV['CLB_APP_IMAGE'], 'image:latest'), (ENV['CLB_REVISION'], 'main'),
                              (ENV['CLB_PLATFORM'], 'linux/unknown'), (ENV['CLB_DB_PASSWORD'], 'short'),
                              (ENV['CLB_BOT_TOKEN'], '${SECRET}'), (ENV['CLB_OWNER_ID'], '0')]:
            with self.subTest(before=before):
                (self.root / '.env').write_text(original.replace(before, after))
                with self.assertRaises(M.Refused):
                    Engine(self.root)
        (self.root / '.env').write_text(original + 'FLYWAY_MODE=migrate\n')
        with self.assertRaises(M.Refused):
            Engine(self.root)

    def test_config_change_after_db(self):
        self.engine.database()
        (self.root / '.env').write_text((self.root / '.env').read_text().replace('q' * 32, 'z' * 32))
        other = Engine(self.root)
        with self.assertRaisesRegex(M.Refused, 'configuration_changed'):
            other.application()

    def test_secrets_private_and_no_symlink(self):
        (self.root / '.env').chmod(0o644)
        with self.assertRaises(M.Refused):
            Engine(self.root)
        (self.root / '.env').chmod(0o600)
        target = self.root / 'secrets/postgres_password'
        target.unlink()
        target.symlink_to(self.root / '.env')
        with self.assertRaises(M.Refused):
            Engine(self.root)

    def test_changed_template_refused(self):
        path = self.root / 'docker-compose.yml'
        path.write_text(path.read_text().replace('127.0.0.1:8081', '0.0.0.0:8081'))
        with self.assertRaisesRegex(M.Refused, 'template_changed'):
            Engine(self.root)

    def test_start_failure_stops_only_new_app_and_blocks_retry(self):
        self.engine.database()
        self.engine.phase_failure = 'start'
        with self.assertRaises(M.Refused):
            self.engine.application()
        self.assertTrue(self.engine.stopped)
        self.assertEqual([a for a in self.engine.calls if a[:2] == ['docker', 'stop']],
                         [['docker', 'stop', '--time', '10', 'c' * 64]])
        self.assertFalse((self.engine.state / 'baseline-observed').exists())
        with self.assertRaises(M.Refused):
            self.engine.application()

    def test_unlabelled_name_collision_is_refused(self):
        original = self.engine.run
        def run(args, timeout=30):
            if args[:3] == ['docker', 'volume', 'ls']:
                return (M.PROJECT + '_pgdata\n').encode()
            return original(args, timeout)
        self.engine.run = run
        with self.assertRaisesRegex(M.Refused, 'resource_name_collision'):
            self.engine.database()
        self.assertFalse(self.engine.state.exists())

    def test_actual_fsm_old_app_collector(self):
        source = (ROOT / 'scripts/deploy/remote-compose-release.sh').read_text()
        function = source.split('collect_old_app_evidence() {', 1)[1].split('\n}\n', 1)[0]
        functions = source.split('validate_revision() {', 1)[1].split('validate_checkpoint() {', 1)[0]
        script = 'validate_revision() {' + functions
        script += '''
current_compose_container_id() { printf '%s' "$TEST_CONTAINER"; }
image_digest() { printf '%s' "$TEST_IMAGE"; }
image_revision() { printf '%s' "$TEST_REVISION"; }
inspect_container_value() {
 case "$1" in
  *State.Running*) printf true ;;
  *'{{.Image}}'*) printf 'sha256:%064d' 1 ;;
  *compose.project*) printf clubs-bot-stage ;;
  *compose.service*) printf app ;;
  *State.StartedAt*) printf '2026-10-19T00:00:00Z' ;;
  *RestartCount*) printf 0 ;;
  *) return 1 ;;
 esac
}
collect_old_app_evidence() {''' + function + '\n}\ncollect_old_app_evidence /synthetic\n'
        env = dict(os.environ, TEST_CONTAINER='c' * 64, TEST_IMAGE=ENV['CLB_APP_IMAGE'], TEST_REVISION=ENV['CLB_REVISION'])
        for updates, expected in [({}, 0), ({'TEST_CONTAINER': ''}, 1),
                                  ({'TEST_REVISION': ''}, 1), ({'TEST_IMAGE': 'clubs-bot@sha256:' + 'a' * 64}, 1)]:
            with self.subTest(updates=updates):
                result = subprocess.run(['bash', '-c', script], env=env | updates, capture_output=True)
                self.assertEqual(result.returncode, expected, result.stderr)

    def test_admin_password_must_be_separate(self):
        (self.root / 'secrets/postgres_password').write_text(ENV['CLB_DB_PASSWORD'])
        with self.assertRaises(M.Refused):
            Engine(self.root)

    def test_raw_compose_root_matches_actual_release_admission(self):
        source = (ROOT / 'scripts/deploy/remote-compose-release.sh').read_text()
        functions = source.split('validate_compose_path() {', 1)[1].split('\npath_owner_id() {', 1)[0]
        script = 'validate_compose_path() {' + functions + '\nvalidate_compose_path "$1"\n'
        spaced = self.root.parent / 'Clubs Bot'
        unicode = self.root.parent / 'Clubs_Бот'
        for path in (spaced, unicode):
            fixture(path)
        link = self.root.parent / 'alias'
        link.symlink_to(self.root, target_is_directory=True)
        parent_link = self.root.parent / 'parent-alias'
        parent_link.symlink_to(self.root.parent, target_is_directory=True)
        raw = str(self.root)
        cases = [raw, str(spaced), str(unicode), str(link), str(parent_link / self.root.name),
                 raw + '/', raw + '/.', raw + '/..', raw + '//',
                 str(self.root.parent) + '//application',
                 str(self.root.parent) + '/./application', raw + '/../application',
                 'relative/root', '/', raw + '/bad:component', raw + '/bad\\component',
                 raw + '\n', raw + '/bad;component']
        for value in cases:
            with self.subTest(value=value):
                expected = value == raw
                result = subprocess.run(['bash', '-c', script, 'test', value], capture_output=True)
                self.assertEqual(result.returncode == 0, expected)
                with mock.patch.object(M.Install, 'run', side_effect=AssertionError('must not execute')):
                    if expected:
                        self.assertEqual(M.Install(value).root, self.root)
                    else:
                        with self.assertRaisesRegex(M.Refused, '^root_path$'):
                            M.Install(value)
                self.assertFalse(self.engine.state.exists())


class SubprocessBoundaryTest(unittest.TestCase):
    """Real child processes and pipes, using Install.run rather than Engine.run."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='clb91-subprocess-')
        self.root = Path(self.temp.name).resolve() / 'root'
        fixture(self.root)
        self.install = M.Install(self.root)

    def tearDown(self):
        self.temp.cleanup()

    def command(self, source):
        return [sys.executable, '-I', '-S', '-B', '-c', source]

    def run_child(self, source, timeout=5):
        handlers = {s: signal.getsignal(s) for s in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP)}
        try:
            return self.install.run(self.command(source), timeout)
        finally:
            self.assertEqual(handlers, {s: signal.getsignal(s) for s in handlers})

    def test_small_success_and_nonzero_refusal(self):
        self.assertEqual(self.run_child("import os; os.write(1,b'ok'); os.write(2,b'private')"), b'ok')
        with self.assertRaisesRegex(M.Refused, '^command_failed$'):
            self.run_child("import os; os.write(2,b'private-secret'); raise SystemExit(7)")

    def test_each_stream_bound_and_exact_stderr_reproduction(self):
        self.assertEqual((M.STDOUT_LIMIT, M.STDERR_LIMIT), (1_048_576, 1_048_576))
        for fd, limit in ((1, M.STDOUT_LIMIT), (2, M.STDERR_LIMIT)):
            with self.subTest(fd=fd):
                source = f'import os; data=b"s"*{limit};\nwhile data: data=data[os.write({fd},data):]'
                self.assertEqual(self.run_child(source), b's' * limit if fd == 1 else b'')
                # Exactly 1,048,577 bytes of stderr previously succeeded.
                with self.assertRaisesRegex(M.Refused, '^output_limit$'):
                    self.run_child(source + f'\nos.write({fd},b"x")')

    def test_simultaneous_full_streams_do_not_deadlock(self):
        source = f'''
import os, threading
def write(fd, limit):
    data = b'x' * limit
    while data:
        data = data[os.write(fd, data):]
threads = [threading.Thread(target=write, args=pair) for pair in [(1,{M.STDOUT_LIMIT}), (2,{M.STDERR_LIMIT})]]
for thread in threads: thread.start()
for thread in threads: thread.join()
'''
        self.assertEqual(self.run_child(source), b'x' * M.STDOUT_LIMIT)
        with self.assertRaisesRegex(M.Refused, '^output_limit$'):
            self.run_child(source.replace(f'(2,{M.STDERR_LIMIT})', f'(2,{M.STDERR_LIMIT + 1})'))

    def assert_not_running(self, pids):
        deadline = time.monotonic() + 3
        while True:
            alive = []
            for pid in pids:
                result = subprocess.run(['ps', '-p', str(pid), '-o', 'stat='], capture_output=True)
                state = result.stdout.strip()
                # Orphaned killed descendants may await the host init's reaping;
                # zombies cannot execute or hold open our pipes.
                if state and not state.startswith(b'Z'):
                    alive.append(pid)
            if not alive or time.monotonic() >= deadline:
                self.assertEqual(alive, [])
                return
            time.sleep(0.02)

    def tree_source(self, action, close_pipes=False):
        # Both descendants stay in the dedicated group, including when their
        # leader exits first. They ignore TERM to require unconditional cleanup.
        pids = self.root.parent / 'pids'
        source = f'''
import os, signal, time
from pathlib import Path
signal.signal(signal.SIGTERM, signal.SIG_IGN)
child = os.fork()
if child == 0:
    grandchild = os.fork()
    if grandchild == 0:
        if {close_pipes!r}:
            os.close(1); os.close(2)
        time.sleep(30)
        os._exit(0)
    record = Path({str(pids) + '.tmp'!r})
    record.write_text(str(os.getppid())+' '+str(os.getpid())+' '+str(grandchild))
    record.rename({str(pids)!r})
    if {close_pipes!r}:
        os.close(1); os.close(2)
    time.sleep(30)
    os._exit(0)
while not Path({str(pids)!r}).exists(): time.sleep(0.005)
{action}
'''
        return source, pids

    def test_timeout_kills_group_with_both_open_and_closed_pipes(self):
        for close_pipes in (False, True):
            with self.subTest(close_pipes=close_pipes):
                source, pids = self.tree_source('os.close(1); os.close(2); time.sleep(30)', close_pipes)
                with self.assertRaisesRegex(M.Refused, '^command_timeout$'):
                    self.run_child(source, timeout=0.5)
                self.assert_not_running([int(p) for p in pids.read_text().split()])
                pids.unlink()

    def test_interrupt_signals_kill_group(self):
        for signum in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
            with self.subTest(signum=signum):
                source, pids = self.tree_source(f'os.kill(os.getppid(), {int(signum)}); time.sleep(30)')
                with self.assertRaisesRegex(M.Refused, '^interrupted$'):
                    self.run_child(source)
                self.assert_not_running([int(p) for p in pids.read_text().split()])
                pids.unlink()

    def test_overflow_kills_group_during_reading(self):
        for fd in (1, 2):
            with self.subTest(fd=fd):
                source, pids = self.tree_source(f'while True: os.write({fd}, b"private-canary" * 4096)')
                start = time.monotonic()
                with self.assertRaisesRegex(M.Refused, '^output_limit$'):
                    self.run_child(source)
                self.assertLess(time.monotonic() - start, 3)
                self.assert_not_running([int(p) for p in pids.read_text().split()])
                pids.unlink()

    def test_exited_leader_does_not_leave_descendants(self):
        for close_pipes in (False, True):
            with self.subTest(close_pipes=close_pipes):
                source, pids = self.tree_source('os._exit(0)', close_pipes)
                if close_pipes:
                    self.assertEqual(self.run_child(source), b'')
                else:
                    with self.assertRaisesRegex(M.Refused, '^command_timeout$'):
                        self.run_child(source, timeout=0.5)
                self.assert_not_running([int(p) for p in pids.read_text().split()])
                pids.unlink()

    def test_phase_deadline_prevents_spawn(self):
        self.install.deadline = time.monotonic() - 1
        with mock.patch.object(M.subprocess, 'Popen', side_effect=AssertionError('must not spawn')):
            with self.assertRaisesRegex(M.Refused, '^phase_deadline$'):
                self.run_child('pass')

    def test_unsupported_process_semantics_refuse_before_spawn(self):
        with mock.patch.object(M.sys, 'platform', 'darwin'), \
                mock.patch.object(M.subprocess, 'Popen', side_effect=AssertionError('must not spawn')):
            with self.assertRaisesRegex(M.Refused, '^command_platform$'):
                self.run_child('pass')

    def test_real_overflow_and_timeout_never_start_app_or_allow_retry(self):
        for action, reason in ((f'os.write(1,b"s"*{M.STDOUT_LIMIT+1})', 'output_limit'),
                               (f'os.write(2,b"s"*{M.STDERR_LIMIT+1})', 'output_limit'),
                               ('time.sleep(30)', 'command_timeout'),
                               ('os.kill(os.getppid(), signal.SIGTERM); time.sleep(30)', 'interrupted')):
            with self.subTest(reason=reason), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp).resolve() / 'root'
                fixture(root)
                engine = Engine(root)
                engine.database()
                fake_run = engine.run
                def run(args, timeout=30):
                    if '/opt/app/bin/app-bot-validate-schema' in args:
                        return M.Install.run(engine, self.command('import os, time, signal; ' + action), 0.5)
                    return fake_run(args, timeout)
                engine.run = run
                with self.assertRaisesRegex(M.Refused, '^' + reason + '$'):
                    engine.application()
                self.assertFalse(any('up' in a and a[-1] == 'app' for a in engine.calls))
                self.assertEqual(engine.containers, ['d' * 64])
                self.assertEqual({p.name for p in engine.state.iterdir()},
                                 {'database-attempt', 'database-ready', 'application-attempt'})
                with self.assertRaisesRegex(M.Refused, '^partial_or_repeated_install$'):
                    engine.application()

    def test_real_app_start_overflow_preserves_owned_app_stop(self):
        engine = Engine(self.root)
        engine.database()
        fake_run = engine.run

        def run(args, timeout=30):
            result = fake_run(args, timeout)
            if 'up' in args and args[-1] == 'app':
                return M.Install.run(engine, self.command(f'import os; os.write(2,b"s"*{M.STDERR_LIMIT+1})'))
            return result

        engine.run = run
        with self.assertRaisesRegex(M.Refused, '^output_limit$'):
            engine.application()
        self.assertTrue(engine.stopped)
        self.assertEqual([a for a in engine.calls if a[:2] == ['docker', 'stop']],
                         [['docker', 'stop', '--time', '10', 'c' * 64]])
        self.assertFalse((engine.state / 'baseline-observed').exists())

    def test_cli_rejects_raw_paths_and_never_publishes_private_output(self):
        bin_dir = self.root.parent / 'bin'
        bin_dir.mkdir()
        docker = bin_dir / 'docker'
        self.install.child_env['PATH'] = str(bin_dir)
        for source, reason in ((f'os.write(1,b"secret-canary"); os.write(2,b"s"*{M.STDERR_LIMIT+1})', 'output_limit'),
                               ('os.write(2,b"private-error-secret"); raise SystemExit(7)', 'command_failed'),
                               ('os.kill(os.getppid(), signal.SIGINT); time.sleep(30)', 'interrupted')):
            with self.subTest(reason=reason):
                docker.write_text('#!' + sys.executable + '\nimport os, time, signal\n' + source + '\n')
                docker.chmod(0o700)
                command = [sys.executable, '-I', '-S', '-B', str(M.TEMPLATE / 'first-install.py'),
                           'check', '--root', str(self.root)]
                with tempfile.TemporaryFile() as out, tempfile.TemporaryFile() as err:
                    result = subprocess.run(command, env=self.install.child_env, stdout=out, stderr=err, timeout=5)
                    self.assertEqual(result.returncode, 1)
                    self.assertLess(out.tell(), 128)
                    self.assertEqual(err.tell(), 0)
                    out.seek(0)
                    self.assertEqual(out.read(128), ('first-install:check:REFUSED:' + reason + '\n').encode())
        # argparse must not convert the string to Path before admission.
        for value in (str(self.root) + '//', str(self.root) + '/.', str(self.root) + '/../root'):
            command = [sys.executable, '-I', '-S', '-B', str(M.TEMPLATE / 'first-install.py'),
                       'database', '--root', value]
            result = subprocess.run(command, env=self.install.child_env, capture_output=True, timeout=5)
            self.assertEqual(result.returncode, 1)
            self.assertEqual(result.stdout, b'first-install:database:REFUSED:root_path\n')
            self.assertEqual(result.stderr, b'')
            self.assertFalse(self.install.state.exists())



if __name__ == '__main__':
    unittest.main()
