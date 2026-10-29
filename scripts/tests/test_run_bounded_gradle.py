"""Real lightweight processes only; never starts Gradle or a JVM."""
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
from unittest.mock import patch
import uuid

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('bounded_gradle', ROOT / 'scripts/run-bounded-gradle.py')
RUNNER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = RUNNER
SPEC.loader.exec_module(RUNNER)


def fixture(output, mode):
    marker = '-Dclb.test.run=' + uuid.uuid4().hex
    child = "import os,sys,time,signal; from pathlib import Path; "
    child += "Path(sys.argv[1]).write_text(str(os.getpid())); "
    if mode == 'success':
        child += "print('done',flush=True)"
    elif mode == 'logs':
        child += "sys.stdout.write('x'*3000000);sys.stdout.flush()"
    elif mode == 'failure':
        child += "sys.exit(7)"
    elif mode == 'leftover':
        child += "import subprocess; subprocess.Popen([sys.executable,'-c','import time; time.sleep(30)',sys.argv[2]],start_new_session=True); time.sleep(0.2)"
    elif mode == 'unmarked':
        child += "import subprocess; subprocess.Popen([sys.executable,'-c','import time; time.sleep(30)']); time.sleep(0.2)"
    elif mode == 'unmarked_detached':
        child += "import subprocess; subprocess.Popen([sys.executable,'-c','import time; time.sleep(30)'],start_new_session=True); time.sleep(0.5)"
    elif mode == 'term_ignored':
        child += "signal.signal(signal.SIGTERM,signal.SIG_IGN); time.sleep(30)"
    else:
        child += "time.sleep(30)"
    return RUNNER.supervise(
        [sys.executable, '-c', child, str(Path(output) / 'child.pid'), marker],
        marker, output, timeout=0.6 if mode in ('timeout', 'term_ignored') else 15,
        idle_timeout=0.4 if mode == 'idle' else 15, diagnose=False,
    )


class BoundedGradleTest(unittest.TestCase):
    def launch(self, mode):
        temporary = tempfile.TemporaryDirectory(prefix='clb-supervisor-test-')
        self.addCleanup(temporary.cleanup)
        path = Path(temporary.name)
        child = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), 'fixture', str(path), mode],
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.addCleanup(lambda: self.reap(child))
        return child, path

    @staticmethod
    def reap(proc):
        if proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait(timeout=2)
        if proc.stdout:
            proc.stdout.close()
        if proc.stderr:
            proc.stderr.close()

    def finish(self, proc, path, expected):
        out, err = proc.communicate(timeout=15)
        self.assertEqual(expected, proc.returncode, (out, err))
        result = json.loads((path / 'result.json').read_text())
        self.assertEqual('verified', result['cleanup'])
        self.assertEqual(expected, result['exitCode'])
        snapshot = RUNNER.processes()
        for record in result['owned']:
            current = snapshot.get(record['pid'])
            self.assertTrue(current is None or current.started != record['started'], record)
        return result

    def test_success(self):
        proc, path = self.launch('success')
        self.assertEqual('completed', self.finish(proc, path, 0)['reason'])

    def test_failure_is_not_pass(self):
        proc, path = self.launch('failure')
        self.assertEqual(7, self.finish(proc, path, 1)['commandExitCode'])

    def test_timeout(self):
        proc, path = self.launch('timeout')
        self.assertEqual('timeout', self.finish(proc, path, 124)['reason'])

    def test_term_then_kill_for_owned_noncooperative_process(self):
        proc, path = self.launch('term_ignored')
        self.assertEqual('timeout', self.finish(proc, path, 124)['reason'])

    def test_no_progress(self):
        proc, path = self.launch('idle')
        self.assertEqual('no_progress', self.finish(proc, path, 124)['reason'])

    def test_cancellation_does_not_signal_unrelated_process(self):
        unrelated = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)'])
        self.addCleanup(lambda: self.reap(unrelated))
        proc, path = self.launch('cancel')
        limit = time.monotonic() + 5
        while not (path / 'child.pid').exists() and time.monotonic() < limit:
            time.sleep(0.02)
        self.assertTrue((path / 'child.pid').exists())
        proc.terminate()
        self.assertEqual('cancelled', self.finish(proc, path, 130)['reason'])
        self.assertIsNone(unrelated.poll())

    def test_reparented_marker_child_is_cleaned_and_success_rejected(self):
        proc, path = self.launch('leftover')
        result = self.finish(proc, path, 1)
        self.assertEqual('leftover_processes', result['reason'])
        self.assertGreaterEqual(len(result['owned']), 2)

    def test_logs_are_capped_and_still_drained(self):
        proc, path = self.launch('logs')
        self.finish(proc, path, 0)
        self.assertEqual(2 * 1024 * 1024, (path / 'output.log').stat().st_size)

    def test_unmarked_children_keep_ownership_after_reparenting(self):
        for mode in ('unmarked', 'unmarked_detached'):
            proc, path = self.launch(mode)
            result = self.finish(proc, path, 1)
            self.assertEqual('leftover_processes', result['reason'])
            self.assertGreaterEqual(len(result['owned']), 2)

    def test_isolation_options_cannot_be_overridden(self):
        for args in [['test', '-p', '/other'], ['test', '--system-prop', 'org.gradle.jvmargs=x'],
                     ['test', '-D', 'org.gradle.daemon.registry.base=x'], ['test', '--parallel'],
                     ['test', '-g', '/other'], ['test', '--include-build', '/other'],
                     ['test', '--daemon'], ['test', '--max-workers=8'], ['test', '-I', 'other.gradle'],
                     ['test', '--project-dir=/other'], ['test', '--no-daemon', '--parallel'],
                     ['--parallel'], ['--tests', '--parallel'], ['test', '--tests']]:
            with self.subTest(args=args):
                self.assertFalse(RUNNER.valid_tasks(args))
        self.assertTrue(RUNNER.valid_tasks([':app-bot:test', '--tests', '*AdminMusicRoutesTest', '--rerun-tasks']))

    def test_refuses_reused_pid_and_missing_marker(self):
        marker = '-Dclb.test.run=synthetic'
        record = RUNNER.Process(123, 1, 123, os.getuid(), 'first', 1, 0, 'java ' + marker)
        signalled = []
        for changed in [RUNNER.dataclasses.replace(record, started='second'),
                        RUNNER.dataclasses.replace(record, args='java unrelated')]:
            self.assertFalse(RUNNER.signal_owned(record, marker, signal.SIGKILL,
                                                snapshot=lambda: {123: changed},
                                                send=lambda *x: signalled.append(x)))
        self.assertEqual([], signalled)

    def test_inspection_error_is_not_empty_success(self):
        record = RUNNER.Process(123, 1, 123, os.getuid(), 'first', 1, 0, 'java -Dclb.test.run=synthetic')
        def unavailable():
            raise RuntimeError('snapshot unavailable')
        with self.assertRaises(RuntimeError):
            RUNNER.signal_owned(record, '-Dclb.test.run=synthetic', signal.SIGTERM, snapshot=unavailable)


class OwnershipUnitTest(unittest.TestCase):
    """Synthetic snapshots reproduce PID/PGID reuse without signalling real processes."""
    marker = '-Dclb.test.run=synthetic'

    def process(self, pid, parent=1, group=100, nonce=False, started='first', cpu=0):
        return RUNNER.Process(pid, parent, group, 501, started, 1, cpu,
                              'python worker' + (' ' + self.marker if nonce else ''))

    def tracker(self):
        return RUNNER.Ownership({}, self.marker, 501)

    def test_reused_group_without_verified_survivor_does_not_adopt_stranger(self):
        tracker = self.tracker()
        root = self.process(100, nonce=True)
        tracker.observe({100: root})
        # No empty intermediate snapshot: root vanished and the OS reused PGID 100.
        stranger = self.process(200, group=100, started='later')
        self.assertEqual({}, tracker.observe({200: stranger}))
        self.assertNotIn(200, tracker.records)
        self.assertTrue(tracker.cleaned({200: stranger}, 0))

    def test_group_continuity_requires_live_verified_member(self):
        tracker = self.tracker()
        root = self.process(100, nonce=True)
        child = self.process(101, parent=100)
        tracker.observe({101: child, 100: root})
        # A known child's birth/argv survives reparenting, so its group is still ours.
        reparented = RUNNER.dataclasses.replace(child, parent=1)
        newcomer = self.process(102)
        self.assertEqual({101, 102}, set(tracker.observe({101: reparented, 102: newcomer})))
        self.assertEqual(child, tracker.records[101])

    def test_detached_child_requires_live_ancestry_then_retains_birth_and_argv(self):
        tracker = self.tracker()
        root = self.process(100, nonce=True)
        child = self.process(101, parent=100, group=101)
        tracker.observe({101: child, 100: root})
        reparented = RUNNER.dataclasses.replace(child, parent=1)
        self.assertEqual({101}, set(tracker.observe({101: reparented})))
        changed = RUNNER.dataclasses.replace(reparented, args='python unrelated')
        self.assertEqual({}, tracker.observe({101: changed}))
        self.assertIn(101, tracker.refused)
        self.assertEqual(child, tracker.records[101])

    def test_reused_parent_pid_cannot_authorize_child_or_its_group(self):
        tracker = self.tracker()
        root = self.process(100, nonce=True)
        tracker.observe({100: root})
        reused = self.process(100, started='second')
        stranger = self.process(101, parent=100)
        self.assertEqual({}, tracker.observe({100: reused, 101: stranger}))
        self.assertNotIn(101, tracker.records)
        self.assertIn(100, tracker.refused)
        self.assertFalse(tracker.cleaned({100: reused, 101: stranger}, 0))

    def test_absent_identity_cannot_be_resurrected(self):
        tracker = self.tracker()
        root = self.process(100, nonce=True)
        tracker.observe({100: root})
        tracker.observe({})
        self.assertEqual({}, tracker.observe({100: root}))
        self.assertIn(100, tracker.refused)

    def test_initial_process_and_group_are_never_adopted(self):
        initial = self.process(200, group=200)
        tracker = RUNNER.Ownership({200: initial}, self.marker, 501)
        root = self.process(100, nonce=True, group=200)
        stranger = self.process(201, group=200)
        self.assertEqual({100}, set(tracker.observe({100: root, 200: initial, 201: stranger})))
        self.assertNotIn(200, tracker.records)
        self.assertNotIn(201, tracker.records)

    def test_cleanup_requires_no_refusals_no_live_owned_pid_and_reaped_launcher(self):
        tracker = self.tracker()
        root = self.process(100, nonce=True)
        tracker.observe({100: root})
        self.assertFalse(tracker.cleaned({100: root}, 0))
        self.assertFalse(tracker.cleaned({}, None))
        self.assertTrue(tracker.cleaned({}, 0))
        tracker.refused.add(100)
        self.assertFalse(tracker.cleaned({}, 0))

    def test_cpu_pressure_is_aggregate_sustained_and_resets(self):
        pressure = RUNNER.CpuPressure(limit_percent=300, duration=10)
        hot = {100: self.process(100, cpu=180), 101: self.process(101, cpu=180)}
        cool = {100: self.process(100, cpu=200)}
        self.assertFalse(pressure.exceeded(hot, 0))
        self.assertFalse(pressure.exceeded(hot, 9.9))
        self.assertFalse(pressure.exceeded(cool, 10))
        self.assertFalse(pressure.exceeded(hot, 11))
        self.assertFalse(pressure.exceeded(hot, 20.9))
        self.assertTrue(pressure.exceeded(hot, 21))
        self.assertEqual(360, pressure.peak)

    def test_cpu_pressure_uses_only_verified_current_ownership(self):
        tracker = self.tracker()
        pressure = RUNNER.CpuPressure(limit_percent=300, duration=10)
        root = self.process(100, nonce=True, cpu=10)
        stranger = self.process(200, group=200, cpu=850)
        live = tracker.observe({100: root, 200: stranger})
        self.assertFalse(pressure.exceeded(live, 0))
        self.assertFalse(pressure.exceeded(live, 20))
        self.assertEqual(10, pressure.peak)

    def test_expired_wall_budget_never_starts_another_blocking_inspection(self):
        with patch.object(RUNNER.time, 'monotonic', return_value=300):
            with self.assertRaises(TimeoutError):
                RUNNER.remaining(300, 3)
            self.assertEqual(0.5, RUNNER.remaining(300.5, 3))

    def test_macos_microsecond_identity_binds_argv_and_rejects_reuse(self):
        record = self.process(100, nonce=True)
        def info(micros):
            value = RUNNER.DarwinBsdInfo()
            value.pid = 100; value.uid = 501; value.parent = 1; value.group = 100
            value.seconds = 123456; value.micros = micros
            return value
        self.assertEqual(136, RUNNER.ctypes.sizeof(RUNNER.DarwinBsdInfo))
        with patch.object(RUNNER.sys, 'platform', 'darwin'), \
             patch.object(RUNNER.os, 'getuid', return_value=501), \
             patch.object(RUNNER, 'ps_snapshot', return_value={100: record}):
            with patch.object(RUNNER, 'darwin_birth', side_effect=[info(1), info(2)]):
                with self.assertRaises(RuntimeError):
                    RUNNER.processes()
            with patch.object(RUNNER, 'darwin_birth', side_effect=[info(2), info(2)]):
                self.assertEqual('123456:2', RUNNER.processes()[100].started)
            reparented = info(2); reparented.parent = 3
            with patch.object(RUNNER, 'darwin_birth', side_effect=[info(2), reparented]):
                self.assertEqual(3, RUNNER.processes()[100].parent)
            with patch.object(RUNNER, 'darwin_birth', side_effect=[info(2), None]):
                self.assertEqual({}, RUNNER.processes())


if __name__ == '__main__':
    if len(sys.argv) > 1 and sys.argv[1] == 'fixture':
        sys.exit(fixture(sys.argv[2], sys.argv[3]))
    unittest.main()
