#!/usr/bin/env python3
"""Real Linux flock/process/session tests; synthetic Docker only, no sockets."""
import contextlib
import importlib.util
import io
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


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


M = load('flock', ROOT / 'scripts/deploy/shared_staging_flock.py')
R = load('release', ROOT / 'scripts/tests/test_quiesced_release_state.py')
I = load('install', ROOT / 'scripts/tests/test_aeza_first_install.py')
IMPORT = ("import importlib.util,sys,time,os; "
          f"s=importlib.util.spec_from_file_location('lock',{str(Path(M.__file__))!r}); "
          "m=importlib.util.module_from_spec(s); s.loader.exec_module(m); ")


def contender(path, effect=None):
    code = (IMPORT + f"\ntry:\n with m.HostLock({str(path)!r}):\n"
            + (f"  open({str(effect)!r},'w').write('effect')\n" if effect else '  pass\n')
            + "except m.Refused as e:\n print(str(e)); sys.exit(75)\n")
    return subprocess.run([sys.executable, '-I', '-S', '-B', '-c', code],
                          capture_output=True, text=True, timeout=5)


class Session:
    def __init__(self, harness, helper=None):
        self.h = harness
        retained = harness.volatile_root / f'clubs-bot-release-{R.OWNER}.sh'
        retained.write_bytes(harness.helper.read_bytes())
        code = (IMPORT + f"\ntry: sys.exit(m.serve({str(helper or retained)!r},"
                f"{R.OWNER!r},'test',{str(harness.host_lock)!r}))\n"
                "except (m.Refused,OSError,ValueError): sys.exit(255)\n")
        self.p = subprocess.Popen([sys.executable, '-I', '-S', '-B', '-c', code], env=harness.env,
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                  bufsize=0)

    def ready(self):
        return M.frame_read(self.p.stdout.fileno(), time.monotonic() + 10)

    def send(self, mode):
        args = ([str(self.h.compose_path), R.IMAGE_REPOSITORY, 'candidate', R.REVISION, 'fixture-bot']
                if mode == 'preflight' else [str(self.h.compose_path), R.DIGEST, R.REVISION])
        M.frame_write(self.p.stdin.fileno(), {'args': [mode, R.OWNER, 'test', *args],
                                            'input': 'synthetic-token\n' if mode == 'preflight' else ''})

    def receive(self):
        return M.frame_read(self.p.stdout.fileno(), time.monotonic() + 30)

    def rpc(self, mode):
        self.send(mode)
        return self.receive()

    def close(self):
        if not self.p.stdin.closed:
            self.p.stdin.close()
        try:
            self.p.wait(timeout=6)
        except subprocess.TimeoutExpired:
            self.p.kill()
            self.p.wait(timeout=5)
            raise
        finally:
            self.p.stdout.close()
            self.p.stderr.close()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()


@unittest.skipUnless(sys.platform == 'linux', 'actual flock tests require Linux')
class FlockTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='clb-flock-', dir='/tmp')
        self.root = Path(self.temp.name)
        self.path = self.root / 'shared.lock'
        self.path.touch()
        self.path.chmod(0o660)

    def tearDown(self):
        self.temp.cleanup()

    def test_production_exact_path_no_environment_override(self):
        self.assertEqual('/run/lock/shared-staging-docker.lock', M.LOCK_PATH)
        with mock.patch.dict(os.environ, {'CLB_LOCK_PATH': str(self.path), 'SHARED_LOCK_PATH': str(self.path)}):
            self.assertEqual(M.LOCK_PATH, M.HostLock().path)
        self.assertNotIn('--lock-path', Path(M.__file__).read_text())

    def test_exclusive_acquisition_competing_process_busy_before_effect(self):
        effect = self.root / 'effect'
        with M.HostLock(self.path) as lock:
            lock.check()
            result = contender(self.path, effect)
            self.assertEqual((75, 'BUSY\n'), (result.returncode, result.stdout))
            self.assertFalse(effect.exists())
        self.assertEqual(0, contender(self.path, effect).returncode)
        self.assertEqual('effect', effect.read_text())

    def test_missing_symlink_fifo_directory_wrong_permissions_owner_hardlink(self):
        self.path.unlink()
        variants = ('missing', 'symlink', 'fifo', 'directory', 'mode', 'owner', 'hardlink')
        for kind in variants:
            with self.subTest(kind=kind):
                other = self.root / 'other'
                other.write_text('')
                other.chmod(0o660)
                if kind == 'symlink': self.path.symlink_to(other)
                elif kind == 'fifo': os.mkfifo(self.path, 0o660)
                elif kind == 'directory': self.path.mkdir()
                elif kind == 'hardlink': os.link(other, self.path)
                elif kind != 'missing':
                    self.path.touch()
                    self.path.chmod(0o660 if kind == 'owner' else 0o666)
                    if kind == 'owner': os.chown(self.path, 12345, -1)
                self.assertNotEqual(0, contender(self.path, self.root / 'effect').returncode)
                self.assertFalse((self.root / 'effect').exists())
                if self.path.is_dir(): self.path.rmdir()
                else: self.path.unlink(missing_ok=True)
                other.unlink()

    def test_replaced_inode_refused_while_held(self):
        with M.HostLock(self.path) as lock:
            self.path.rename(self.root / 'old')
            self.path.touch(mode=0o660)
            with self.assertRaises(M.Refused): lock.check()

    def test_open_descriptor_without_kernel_lock_is_not_proof(self):
        fd = os.open(self.path, os.O_RDONLY)
        try:
            with self.assertRaises(M.Refused): M.verify_held(fd, self.path)
        finally:
            os.close(fd)

    def test_inherited_description_verified_without_nested_acquisition(self):
        with M.HostLock(self.path) as lock:
            command = [sys.executable, '-I', '-S', '-B', '-c',
                       IMPORT + f'm.verify_held(19,{str(self.path)!r}); print("held")']
            result = M.run_owned(command, b'', lock, time.monotonic() + 5)
            self.assertEqual((0, 'held\n', ''), result)
            self.assertEqual(75, contender(self.path).returncode)

    def test_owned_timeout_kills_descendants_and_keeps_lock_until_cleanup(self):
        pidfile = self.root / 'pids'
        code = ("import os,time; from pathlib import Path; "
                "child=os.fork(); "
                f"Path({str(pidfile)!r}+str(os.getpid())).write_text(str(os.getpid())); time.sleep(60)")
        with M.HostLock(self.path) as lock:
            with self.assertRaisesRegex(M.Refused, 'UNKNOWN'):
                M.run_owned([sys.executable, '-c', code], b'', lock, time.monotonic() + 0.4)
            self.assertEqual(75, contender(self.path).returncode)
        self.assertEqual(0, contender(self.path).returncode)
        for f in self.root.glob('pids*'):
            status = Path('/proc') / f.read_text() / 'stat'
            self.assertTrue(not status.exists() or status.read_text().split()[2] == 'Z')

    def test_initial_phase_lifetime_and_manual_restore_gap(self):
        root = self.root / 'install'
        I.fixture(root)
        engine = I.Engine(root)
        original_run = engine.run
        observations = []

        def run(args, timeout=30):
            observations.append(contender(self.path).returncode)
            return original_run(args, timeout)

        engine.run = run
        for phase in ('check', 'database', 'application', 'verify'):
            with mock.patch.object(I.M._lock_module, 'HostLock', lambda: M.HostLock(self.path)), \
                    mock.patch.object(I.M._lock_module, 'Refused', M.Refused), \
                    mock.patch.object(I.M, 'Install', lambda _: engine), \
                    mock.patch.object(sys, 'argv', ['first-install', phase, '--root', str(root)]), \
                    contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, I.M.main())
            # Includes the manual restore waiting boundary after database; the
            # recipe has returned and an independent owner can acquire flock.
            self.assertEqual(0, contender(self.path).returncode)
        self.assertTrue(observations)
        self.assertEqual({75}, set(observations))

    def test_initial_busy_creates_no_attempt_or_docker_effect(self):
        root = self.root / 'install'
        I.fixture(root)
        with M.HostLock(self.path), \
                mock.patch.object(I.M._lock_module, 'HostLock', lambda: M.HostLock(self.path)), \
                    mock.patch.object(I.M._lock_module, 'Refused', M.Refused), \
                mock.patch.object(I.M, 'Install') as construct, \
                mock.patch.object(sys, 'argv', ['first-install', 'database', '--root', str(root)]), \
                contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(1, I.M.main())
            construct.assert_not_called()
        self.assertFalse((root / '.first-install').exists())

    def test_recovery_holds_host_flock_through_files_validator_start_and_baseline(self):
        root = self.root / 'install'
        engine, target = I.recovery_fixture(root)
        observations = []

        def observed(operation, original):
            def call(*args, **kwargs):
                observations.append((operation, contender(self.path).returncode))
                return original(*args, **kwargs)
            return call

        for name in ('run', 'mark', 'replace_recovery_file'):
            setattr(engine, name, observed(name, getattr(engine, name)))
        argv = ['first-install', 'recover-application', '--root', str(root), '--recovery-env', str(target),
                '--stopped-app-id', 'c' * 64, '--database-id', 'd' * 64]
        with mock.patch.object(I.M._lock_module, 'HostLock', lambda: M.HostLock(self.path)), \
                mock.patch.object(I.M._lock_module, 'Refused', M.Refused), \
                mock.patch.object(I.M, 'Install', lambda _, **kwargs: engine), \
                mock.patch.object(I.M.time, 'sleep'), mock.patch.object(sys, 'argv', argv), \
                contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, I.M.main())
        self.assertEqual({'run', 'mark', 'replace_recovery_file'}, {op for op, _ in observations})
        self.assertEqual({75}, {status for _, status in observations})
        self.assertEqual(0, contender(self.path).returncode)

    def test_recovery_busy_never_constructs_or_writes_attempt(self):
        root = self.root / 'install'
        engine, target = I.recovery_fixture(root)
        argv = ['first-install', 'recover-application', '--root', str(root), '--recovery-env', str(target),
                '--stopped-app-id', 'c' * 64, '--database-id', 'd' * 64]
        with M.HostLock(self.path), \
                mock.patch.object(I.M._lock_module, 'HostLock', lambda: M.HostLock(self.path)), \
                mock.patch.object(I.M._lock_module, 'Refused', M.Refused), \
                mock.patch.object(I.M, 'Install') as construct, mock.patch.object(sys, 'argv', argv), \
                contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(1, I.M.main())
            construct.assert_not_called()
        self.assertFalse((engine.state / I.M.RECOVERY_ATTEMPT).exists())

    def test_root_owned_lock_is_usable_by_operator_group_without_root(self):
        os.chown(self.root, 0, 1000)
        self.root.chmod(0o750)
        os.chown(self.path, 0, 1000)
        code = IMPORT + f"\nwith m.HostLock({str(self.path)!r}): print('acquired')\n"
        result = subprocess.run(['setpriv', '--reuid=1000', '--regid=1000', '--clear-groups',
                                 sys.executable, '-I', '-S', '-B', '-c', code],
                                capture_output=True, text=True, timeout=5)
        self.assertEqual((0, 'acquired\n', ''), (result.returncode, result.stdout, result.stderr))

    def test_initial_command_detects_lock_replacement_and_cleans_child(self):
        root = self.root / 'install'
        I.fixture(root)
        install = I.M.Install(root)
        code = (f"import os,time; os.rename({str(self.path)!r},{str(self.root / 'old')!r}); "
                f"open({str(self.path)!r},'w').close(); time.sleep(60)")
        with M.HostLock(self.path) as lock:
            install.host_lock = lock
            with self.assertRaises(M.Refused):
                install.run([sys.executable, '-c', code], timeout=3)
        self.assertFalse(install.state.exists())

    def test_guard_embedding_matches_reviewed_source(self):
        source = Path(M.__file__).read_text()
        predicate = source[source.index('def require('):source.index('\n\nclass HostLock:')]
        self.assertIn(predicate, R.REMOTE_HELPER.read_text())


@unittest.skipUnless(sys.platform == 'linux', 'server process tests require Linux')
class ReleaseSessionTest(unittest.TestCase):
    def test_one_lock_all_operations_no_quiesce_migrate_gap_and_status_readonly(self):
        with R.RemoteHarness(auto_preflight=False) as h:
            h.env['FAKE_FLOCK_REAL'] = 'yes'
            with Session(h) as s:
                self.assertEqual({'ready': True}, s.ready())
                inode = h.host_lock.stat().st_ino
                for mode in M.ORDER:
                    self.assertEqual(75, contender(h.host_lock).returncode)
                    result = s.rpc(mode)
                    self.assertEqual(0, result[0], (mode, result))
                    self.assertEqual(inode, h.host_lock.stat().st_ino)
                    if mode != 'helper-cleanup':
                        self.assertEqual(75, contender(h.host_lock).returncode)
                    if mode == 'quiesce':
                        before = R.snapshot_authoritative_tree(h)
                        status = h.status('quiesce')
                        self.assertEqual(0, status.returncode)
                        self.assertEqual(before, R.snapshot_authoritative_tree(h))
                        self.assertIn('checkpoint=app_quiesced', status.stdout)
                        self.assertEqual(0, h.docker_state()['migration_invocations'])
                self.assertEqual(1, h.docker_state()['migration_invocations'])
                self.assertEqual('cleanup_completed', h.checkpoint())
            self.assertEqual(0, contender(h.host_lock).returncode)

    def test_busy_no_project_mutations_or_docker_and_no_fallback(self):
        with R.RemoteHarness(auto_preflight=False) as h, M.HostLock(h.host_lock):
            with Session(h) as s:
                with self.assertRaises(M.Refused): s.ready()
            self.assertEqual([], h.docker_commands())
            self.assertEqual([], list(h.state_root.iterdir()))

    def test_disconnect_after_acquire_before_intent_no_effect(self):
        with R.RemoteHarness(auto_preflight=False) as h:
            with Session(h) as s:
                s.ready()
                s.p.stdin.close()
            self.assertEqual([], h.docker_commands())
            self.assertEqual([], list(h.state_root.iterdir()))
            self.assertEqual(0, contender(h.host_lock).returncode)

    def test_direct_helper_without_held_lock_cannot_bypass(self):
        with R.RemoteHarness(auto_preflight=False) as h:
            for mode in (*M.ORDER, 'resume', 'abort', 'retention', 'corrected-start'):
                result = subprocess.run(['bash', str(h.helper), mode, R.OWNER, 'test', str(h.compose_path)],
                                        env=h.env, capture_output=True, timeout=5)
                self.assertEqual(75, result.returncode, mode)
            self.assertEqual([], h.docker_commands())
            self.assertEqual([], list(h.state_root.iterdir()))

    def test_lost_ack_retains_checkpoint_no_automatic_migration(self):
        with R.RemoteHarness(auto_preflight=False) as h:
            with Session(h) as s:
                s.ready()
                for mode in ('preflight', 'prepare', 'publish'):
                    self.assertEqual(0, s.rpc(mode)[0])
                s.send('quiesce')
                deadline = time.monotonic() + 10
                while h.checkpoint() != 'app_quiesced' and time.monotonic() < deadline:
                    time.sleep(0.02)
                self.assertEqual('app_quiesced', h.checkpoint())
                # Drop the ACK without scheduling another command.
                s.p.stdout.close()
                s.p.stdin.close()
            self.assertEqual(0, h.docker_state()['migration_invocations'])
            self.assertIn('checkpoint=app_quiesced', h.status('quiesce').stdout)

    def test_disconnect_during_migration_keeps_unknown_and_blocks_replay(self):
        with R.RemoteHarness(auto_preflight=False) as h:
            docker = h.fake_bin / 'docker'
            text = docker.read_text()
            text = text.replace('        fail("compose_run")',
                                '        fail("compose_run")\n        import time; time.sleep(60)')
            docker.write_text(text)
            with Session(h) as s:
                s.ready()
                for mode in ('preflight', 'prepare', 'publish', 'quiesce'):
                    self.assertEqual(0, s.rpc(mode)[0])
                s.send('migrate')
                deadline = time.monotonic() + 10
                while h.checkpoint() != 'migration_started' and time.monotonic() < deadline:
                    time.sleep(0.02)
                self.assertEqual('migration_started', h.checkpoint())
                s.p.stdin.close()
            status = h.status('migrate')
            self.assertIn('incomplete_unknown', status.stdout)
            self.assertIn('resume_permitted=no', status.stdout)
            before = h.docker_state()['migration_invocations']
            self.assertNotEqual(0, h.operation('migrate').returncode)
            self.assertEqual(before, h.docker_state()['migration_invocations'])

    def test_signal_owner_cleans_descendants_no_next_operation(self):
        with R.RemoteHarness(auto_preflight=False) as h:
            helper = h.root / 'blocking.sh'
            pids = h.root / 'pids'
            helper.write_text(f'#!/bin/bash\nsleep 60 &\nprintf "%s %s" "$$" "$!" >{pids}\nwait\n')
            with Session(h, helper) as s:
                s.ready()
                s.send('preflight')
                deadline = time.monotonic() + 5
                while not pids.exists() and time.monotonic() < deadline: time.sleep(0.02)
                self.assertTrue(pids.exists())
                s.p.send_signal(signal.SIGTERM)
                s.p.wait(timeout=6)
                for pid in pids.read_text().split():
                    status = Path('/proc') / pid / 'stat'
                    self.assertTrue(not status.exists() or status.read_text().split()[2] == 'Z')
            self.assertEqual(0, contender(h.host_lock).returncode)

    def test_sigkill_owner_does_not_unlock_live_inherited_child(self):
        with R.RemoteHarness(auto_preflight=False) as h:
            helper = h.root / 'blocking.sh'
            pids = h.root / 'pids'
            helper.write_text(f'#!/bin/bash\nsleep 60 &\nprintf "%s %s" "$$" "$!" >{pids}\nwait\n')
            with Session(h, helper) as s:
                s.ready()
                s.send('preflight')
                deadline = time.monotonic() + 5
                while not pids.exists() and time.monotonic() < deadline: time.sleep(0.02)
                self.assertTrue(pids.exists())
                leader = int(pids.read_text().split()[0])
                try:
                    s.p.kill()
                    s.p.wait(timeout=5)
                    self.assertEqual(75, contender(h.host_lock).returncode)
                finally:
                    # Test-owned process group only, after positive PID evidence.
                    os.killpg(leader, signal.SIGKILL)
            deadline = time.monotonic() + 5
            while contender(h.host_lock).returncode and time.monotonic() < deadline: time.sleep(0.02)
            self.assertEqual(0, contender(h.host_lock).returncode)

    def test_actual_runner_and_server_round_trip_and_busy_before_upload(self):
        with R.RemoteHarness(app_env='stage', auto_preflight=False) as h:
            helper = Path(f'/tmp/clubs-bot-release-{R.OWNER}.sh')
            self.assertFalse(helper.exists())
            ssh_log = h.root / 'ssh.log'
            scp_log = h.root / 'scp.log'
            # Execute the actual generated SSH command locally. Substitute only
            # the test lock constant; all protocol/guard/FSM code remains real.
            R.write_executable(h.fake_bin / 'ssh', '#!/usr/bin/env python3\n'
                'import sys,subprocess\nfrom pathlib import Path\n'
                f'with Path({str(ssh_log)!r}).open("a") as f: f.write("ssh\\n")\n'
                f'command=sys.argv[-1].replace({M.LOCK_PATH!r},{str(h.host_lock)!r})\n'
                'raise SystemExit(subprocess.call(["bash","-c",command]))\n')
            R.write_executable(h.fake_bin / 'scp', '#!/usr/bin/env python3\n'
                'import sys\nfrom pathlib import Path\n'
                f'Path({str(scp_log)!r}).write_text("upload")\n'
                f'p=Path({str(helper)!r}); assert not p.exists()\n'
                f'p.write_text(Path(sys.argv[-2]).read_text().replace({M.LOCK_PATH!r},{str(h.host_lock)!r}))\n')
            rm = h.fake_bin / 'rm'
            rm.write_text(rm.read_text().replace('    allowed = allowed or bool(',
                f'    allowed = allowed or target == Path({str(helper)!r})\n'
                f'    allowed = allowed or (target.parent == Path("/tmp") and target.name.startswith(".clubs-bot-migration-log.{R.OWNER}.") and len(target.name.rsplit(".", 1)[-1]) == 6)\n'
                '    allowed = allowed or bool('))
            env = dict(h.env, TMPDIR=str(h.volatile_root), APP_ENV='stage', COMPOSE_PATH=str(h.compose_path),
                       EXPECTED_REVISION=R.REVISION, IMAGE_REPOSITORY=R.IMAGE_REPOSITORY,
                       IMAGE_TAG='candidate', REGISTRY_USERNAME='fixture-bot',
                       REGISTRY_READ_TOKEN='synthetic-token', GITHUB_RUN_ID='12345',
                       GITHUB_RUN_ATTEMPT='1', SSH_USER='fixture', SSH_HOST='fixture.invalid', SSH_PORT='22')
            try:
                with M.HostLock(h.host_lock):
                    result = subprocess.run(['bash', str(R.RUNNER)], env=env, capture_output=True,
                                            text=True, timeout=10)
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn('outcome=busy', result.stderr)
                    self.assertFalse(scp_log.exists())
                    self.assertFalse(helper.exists())
                    self.assertEqual([], h.docker_commands())
                result = subprocess.run(['bash', str(R.RUNNER)], env=env, capture_output=True,
                                        text=True, timeout=90)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertEqual(2, len(ssh_log.read_text().splitlines()))
                self.assertEqual(1, h.docker_state()['migration_invocations'])
                self.assertEqual('cleanup_completed', h.checkpoint())
                self.assertFalse(helper.exists())
            finally:
                # Only the exact helper this fixture created in its container.
                if scp_log.exists(): helper.unlink(missing_ok=True)

    def test_upload_timeout_never_requests_preflight(self):
        with R.RunnerHarness() as h:
            R.write_executable(h.fake_bin / 'scp', '#!/bin/bash\nexec sleep 60\n')
            start = time.monotonic()
            result = subprocess.run(['bash', str(R.RUNNER)], env=h.env, capture_output=True,
                                    text=True, timeout=30)
            elapsed = time.monotonic() - start
            self.assertGreaterEqual(elapsed, 19)
            self.assertLess(elapsed, 27)
            self.assertNotEqual(0, result.returncode)
            self.assertIn('operation=upload', result.stderr)
            self.assertEqual({'host-session': 1}, h.mode_counts())

    def test_out_of_order_and_recovery_commands_refuse_without_effect(self):
        with R.RemoteHarness(auto_preflight=False) as h:
            for mode in ('migrate', 'resume', 'abort', 'cleanup'):
                with Session(h) as s:
                    s.ready()
                    s.send(mode)
                    with self.assertRaises(M.Refused): s.receive()
            self.assertEqual([], h.docker_commands())
            self.assertEqual([], list(h.state_root.iterdir()))


if __name__ == '__main__':
    if sys.platform != 'linux' or os.getuid() != 0:
        raise SystemExit('NOT_RUN: requires disposable Linux root-owned fixtures')
    unittest.main()
