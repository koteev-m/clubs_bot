#!/usr/bin/env python3
"""Disposable FD/adapter regressions; synthetic executor, no stage/Engine reads."""
import ast
import contextlib
import io
import os
from pathlib import Path
import stat
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
CANARY = b'CLB91_PRIVATE_FD_CANARY_$${literal}'


def definitions():
    raw = (ROOT/'scripts/deploy/remote-compose-release.sh').read_text()
    source = raw.split("<<'CLB82_BOUND_PYTHON' || true\n", 1)[1].split('\nCLB82_BOUND_PYTHON', 1)[0]
    tree = ast.parse(source)
    nodes = []
    for node in tree.body:
        if isinstance(node, (ast.Import, ast.ImportFrom, ast.FunctionDef, ast.ClassDef, ast.Assign)):
            if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id in ('FUNCTIONS', 'context') for t in node.targets):
                continue
            nodes.append(node)
        elif isinstance(node, ast.Expr) and isinstance(node.value, ast.Call) and (
            isinstance(node.value.func, ast.Name) and node.value.func.id == 'exec' or
            isinstance(node.value.func, ast.Attribute) and isinstance(node.value.func.value, ast.Name)
            and node.value.func.value.id in ('CLB91_SEMANTIC', 'CLB91_EXECUTOR')):
            nodes.append(node)
    namespace = {}
    with patch.object(sys, 'argv', ['helper', '33468965282-1', 'stage', '/srv/synthetic', 'a'*40, 'x', 'inspect']):
        exec(compile(ast.Module(body=nodes, type_ignores=[]), '<captured-helper-source>', 'exec'), namespace)
    return namespace


class CaptureFDTest(unittest.TestCase):
    def setUp(self):
        self.namespace = definitions()
        self.B = self.namespace['BoundContext']
        self.context = self.B.__new__(self.B)
        self.context.phase = 'inspect'
        self.context.uid = os.geteuid()
        self.context.fds, self.context.config_fds, self.context.config_leases = [], [], []
        self.context.metadata, self.context.directories, self.context.objects = {}, {}, {}
        self.context.edges = []
        self.context.signaled = False
        self.runtime_closes = []
        self.context.runtime = types.SimpleNamespace(close=lambda:self.runtime_closes.append(True))
        self.temporary = tempfile.TemporaryDirectory(prefix='clb91-fd-canary-')
        self.root = Path(self.temporary.name)
        self.directory = self.context.keep(os.open(self.root, os.O_RDONLY | os.O_DIRECTORY))
        self.context.directories['compose'] = self.directory

    def tearDown(self):
        self.context.close()
        if self.directory >= 0:
            try: os.close(self.directory)
            except OSError: pass
        self.temporary.cleanup()

    def input(self, data=CANARY):
        path = self.root/'.env'; path.write_bytes(data); path.chmod(0o600)
        return path

    def test_required_absence_refuses_and_empty_present_is_distinct(self):
        with self.assertRaises(FileNotFoundError):
            self.context.captured_file('compose', '.env', required=True)
        self.assertEqual(b'', self.context.captured_file('compose', '.env', required=False))
        self.input(b'')
        self.assertEqual(b'', self.context.captured_file('compose', '.env', required=True))
        self.assertIn('configuration/.env', self.context.objects)

    def test_source_replacement_and_deletion_keep_lease_but_refuse_rechecks(self):
        path = self.input()
        self.assertEqual(CANARY, self.context.captured_file('compose', '.env'))
        fd = self.context.config_leases[-1][0]
        path.rename(path.with_name('original-held'))
        self.assertEqual(CANARY, os.pread(fd, len(CANARY), 0))
        with self.assertRaises(Exception): self.context.check_edges()
        self.input()
        with self.assertRaises(Exception): self.context.check_edges()

    def test_same_inode_content_drift_refuses(self):
        path = self.input()
        self.context.captured_file('compose', '.env')
        path.write_bytes(CANARY + b'x')
        with self.assertRaises(self.namespace['InspectFailure']): self.context.check_edges()

    def test_symlink_hardlink_mode_and_bound_fail_closed(self):
        other = self.root/'other'; other.write_bytes(CANARY); other.chmod(0o600)
        (self.root/'.env').symlink_to('other')
        with self.assertRaises(OSError): self.context.captured_file('compose', '.env')
        (self.root/'.env').unlink(); os.link(other, self.root/'.env')
        with self.assertRaises(self.namespace['InspectFailure']): self.context.captured_file('compose', '.env')
        (self.root/'.env').unlink(); path = self.input(); path.chmod(0o644)
        with self.assertRaises(self.namespace['InspectFailure']): self.context.captured_file('compose', '.env')
        path.chmod(0o600)
        with self.assertRaises(self.namespace['InspectFailure']): self.context.captured_file('compose', '.env', limit=1)

    def test_short_read_keeps_owned_fd_and_cannot_normalize(self):
        self.input()
        with patch.object(self.namespace['os'], 'read', side_effect=[CANARY[:-1], b'']):
            with self.assertRaises(Exception): self.context.captured_file('compose', '.env')
        self.assertEqual(1, len(self.context.config_leases))
        fd = self.context.config_leases[0][0]
        self.context.close()
        with self.assertRaises(OSError): os.fstat(fd)

    def test_original_dotenv_not_passed_to_normalizer(self):
        self.input()
        self.context.captured_file('compose', '.env')
        original = self.context.config_leases[0][0]
        self.context.env_capture = '/proc/12345/fd/7'
        self.context.project, self.context.compose = 'real-bound-project', '/srv/canonical-project'
        self.context.docker_config_fd = self.directory
        actual = {'A': CANARY.decode(), 'HOME':'/actual/home', 'PATH':'/usr/bin:/bin'}
        self.context.docker_environment = actual
        calls = []
        self.context.runtime = types.SimpleNamespace(
            command_environment=lambda env:self.assertIs(env, actual), recheck=lambda:None, close=lambda:None)
        result = self.namespace['CLB91_CAPTURE']['CaptureResult'](0, b'{}')
        def capture(argv, payload=b'', **kw):
            calls.append(argv)
            self.assertIs(kw['env'], actual)
            self.assertNotIn(original, kw['pass_fds'])
            self.assertEqual(kw['cwd_fd'], self.directory)
            self.assertNotIn(str(self.root/'.env'), argv)
            return result
        with patch.dict(self.namespace['CLB91_CAPTURE'], capture_result=capture):
            self.context.private_call(self.context.compose_argv(['-f', '/proc/12345/fd/8']) + ['config','--format','json'])
        self.assertEqual(calls[0][:2], ['/usr/bin/docker','compose'])
        self.assertEqual(calls[0][calls[0].index('--env-file')+1], self.context.env_capture)

    def test_cancellation_timeout_and_capture_cleanup_never_return_a_model(self):
        self.context.docker_config_fd = self.directory
        self.context.docker_environment = {'A': CANARY.decode()}
        self.context.runtime = types.SimpleNamespace(command_environment=lambda _:None, recheck=lambda:None, close=lambda:None)
        for failure in ('capture_timeout', 'capture_interrupted', 'capture_failed'):
            result = self.namespace['CLB91_CAPTURE']['CaptureResult'](124, CANARY, failure)
            with self.subTest(failure=failure), patch.dict(self.namespace['CLB91_CAPTURE'], capture_result=lambda *a, **k:result):
                with self.assertRaises(Exception): self.context.private_call(['/synthetic-normalizer'])
        self.context.signaled = True
        with self.assertRaises(InterruptedError): self.context.check_interruption()

    def test_mount_query_uses_bounded_capture_and_terminal_cancellation(self):
        self.context.directories.update({key:self.directory for key in
            ('parent','root','state','results','ledger','application_lock','operation_lock')})
        self.context.docker_config_fd = self.directory
        self.context.docker_environment = {'A':CANARY.decode()}
        self.context.runtime = types.SimpleNamespace(command_environment=lambda _:None, recheck=lambda:None, close=lambda:None)
        calls = []
        def capture(argv, payload=b'', **kwargs):
            calls.append((argv,kwargs))
            self.assertEqual(argv[0],'/usr/bin/findmnt')
            self.assertEqual(kwargs['timeout'],5)
            self.assertEqual(kwargs['limit'],4096)
            return self.namespace['CLB91_CAPTURE']['CaptureResult'](124,CANARY,'capture_timeout')
        with patch.dict(self.namespace['CLB91_CAPTURE'],capture_result=capture):
            with self.assertRaises(self.namespace['InspectFailure']) as failure:
                self.context.mount_identity()
        self.assertEqual(1,len(calls))
        self.assertNotIn(CANARY.decode(),self.namespace['inspect_diagnostic'](failure.exception))
        self.assertEqual('mount_query',failure.exception.guard)
        self.context.signaled = True
        with self.assertRaises(InterruptedError): self.context.check_interruption()

    def test_anonymous_capture_acquisition_faults_register_every_private_fd(self):
        # A real unlinked FD supplies bytes/ownership; seal calls are synthetic.
        # Native kernel seals/namespace acceptance are explicitly NOT_RUN here.
        real_fstat, real_fdopen, real_fchmod = os.fstat, os.fdopen, os.fchmod
        for failure in ('chmod','fdopen','write','seal','getseal','fstat','seek'):
            with self.subTest(failure=failure):
                acquired = []
                self.context.closed = False
                self.context.fds, self.context.config_fds = [], []
                self.context.metadata = {}
                def acquire(*args):
                    fd, name = tempfile.mkstemp(dir=self.root); os.unlink(name); acquired.append(fd); return fd
                def chmod(fd, mode):
                    if failure == 'chmod': raise OSError(CANARY)
                    real_fchmod(fd, mode)
                class Stream:
                    def __init__(self, fd): self.stream = real_fdopen(fd, 'rb+')
                    def fileno(self): return self.stream.fileno()
                    def close(self): self.stream.close()
                    def seek(self, *args):
                        if failure == 'seek': raise OSError(CANARY)
                        return self.stream.seek(*args)
                def fdopen(fd, mode):
                    if failure == 'fdopen': raise OSError(CANARY)
                    return Stream(fd)
                def write(fd, data):
                    os.write(fd, data)
                    if failure == 'write': raise OSError(CANARY)
                stats = []
                def fstat(fd):
                    stats.append(fd)
                    if failure == 'fstat' and len(stats) > 1: raise OSError(CANARY)
                    return real_fstat(fd)
                def seals(fd, command, *args):
                    if command == 100:
                        if failure == 'seal': raise OSError(CANARY)
                        return 0
                    return 0 if failure == 'getseal' else 15
                fcntl = self.namespace['fcntl']
                with patch.object(os,'memfd_create',side_effect=acquire,create=True), patch.object(os,'MFD_CLOEXEC',0,create=True), patch.object(os,'MFD_ALLOW_SEALING',0,create=True), \
                     patch.object(os,'fchmod',side_effect=chmod), patch.object(os,'fdopen',side_effect=fdopen), patch.object(os,'fstat',side_effect=fstat), \
                     patch.dict(self.namespace, write_all=write), patch.object(fcntl,'fcntl',side_effect=seals), \
                     patch.object(fcntl,'F_ADD_SEALS',100,create=True), patch.object(fcntl,'F_GET_SEALS',101,create=True), \
                     patch.object(fcntl,'F_SEAL_WRITE',1,create=True), patch.object(fcntl,'F_SEAL_GROW',2,create=True), \
                     patch.object(fcntl,'F_SEAL_SHRINK',4,create=True), patch.object(fcntl,'F_SEAL_SEAL',8,create=True):
                    with self.assertRaises(self.namespace['InspectFailure']) as rejected:
                        self.context.capture(CANARY)
                    public = self.namespace['inspect_diagnostic'](rejected.exception)
                    self.assertNotIn(CANARY.decode(), public)
                self.context.close()
                for fd in acquired:
                    with self.assertRaises(OSError): real_fstat(fd)
        # Directory FD was not included in fault-fixture ownership.
        os.close(self.directory)
        self.directory = -1

    def test_cleanup_failure_attempts_remaining_resources_and_retains_failure(self):
        self.input(); self.context.captured_file('compose','.env')
        descriptors = list(self.context.fds); attempted = []
        original_close = os.close
        def closed(fd):
            attempted.append(fd)
            original_close(fd)
            if fd == descriptors[-1]: raise OSError(CANARY)
        with patch.object(os,'close',side_effect=closed):
            with self.assertRaises(OSError): self.context.close()
        self.assertEqual(set(descriptors), set(attempted))
        self.assertEqual([True], self.runtime_closes)
        self.context.close()  # Idempotence is not a success verdict.


if __name__ == '__main__':
    unittest.main()
