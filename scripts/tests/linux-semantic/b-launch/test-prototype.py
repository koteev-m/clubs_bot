"""Portable synthetic controls. No Linux syscall, root, Engine or stage access."""
import ast
from copy import deepcopy
import importlib.util
import io
import os
from pathlib import Path
import signal
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
from unittest.mock import Mock

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('b_launch_prototype', HERE/'prototype.py')
P = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = P
spec.loader.exec_module(P)


class ModelLease:
    def __init__(self, kernel):
        self.kernel, self.closed = kernel, False

    def close(self):
        self.closed = True
        self.kernel.event('close')


class SyntheticKernel:
    """An adversarial observation source, not a Linux namespace simulator."""
    def __init__(self, fault=None, mutate=None):
        self.events, self.fault, self.mutate = [], fault, mutate
        self.projected, self.dropped = False, False
        obj = lambda ino: (2, ino, 0o100600, 1701, 2701, 1, 0, 1, 1)
        self.origin = dict(user=(1, 1), pid=(1, 2), net=(1, 3), mount=(1, 4),
                           root=obj(1), app=obj(10), locks=(obj(11), obj(12)),
                           backing=('8:1', '/', '/opt', 'ext4', '/dev/synthetic'), runtime='target')
        self.reads, self.leases = 0, [ModelLease(self), ModelLease(self)]

    def event(self, name):
        self.events.append(name)
        if name == self.fault:
            raise RuntimeError('PRIVATE-SYNTHETIC-CANARY')

    def admit(self): self.event('admit')
    def hold(self, principal):
        self.event('hold')
        self.principal = principal
        return self.leases
    def observe(self):
        self.reads += 1
        self.event(('origin', 'reopen_observed', 'projected_observed', 'final_observed')[self.reads-1])
        value = deepcopy(self.origin)
        if self.reads > 1:
            value['mount'] = (1, 5)
        if self.projected:
            value['runtime'] = 'source'
        if self.mutate:
            self.mutate(self.reads, value)
        return value
    def unshare_private(self): self.event('unshare_private')
    def reopen(self): self.event('reopen')
    def project(self):
        self.event('project')
        self.projected = True
    def drop(self, principal):
        self.event('drop')
        self.dropped = True
    def credentials(self):
        self.event('credentials')
        p = self.principal
        return dict(uids=(p.uid,)*3, gids=(p.gid,)*3, groups=p.groups,
                    caps=(0,)*5, nnp=1, keepcaps=0)
    def worker(self):
        self.event('worker')
        P.require(self.dropped)
    def recheck(self): self.event('recheck')
    def close_directories(self): self.event('close_directories')


class Contract(unittest.TestCase):
    principal = P.Principal(1701, 2701, (2701, 3701))

    def test_multiple_original_principals_and_order(self):
        for principal in (self.principal, P.Principal(2499, 3499, ())):
            with self.subTest(uid=principal.uid):
                kernel = SyntheticKernel()
                P.child_setup(kernel, principal)
                self.assertEqual(kernel.events, ['admit', 'hold', 'origin', 'unshare_private',
                    'reopen', 'reopen_observed', 'project', 'projected_observed', 'drop',
                    'credentials', 'worker', 'final_observed', 'recheck', 'close_directories', 'close', 'close'])
                self.assertTrue(all(lease.closed for lease in kernel.leases))

    def test_each_setup_worker_and_cleanup_failure_refuses(self):
        events = ['admit', 'hold', 'origin', 'unshare_private', 'reopen', 'reopen_observed',
                  'project', 'projected_observed', 'drop', 'credentials', 'worker',
                  'final_observed', 'recheck', 'close_directories', 'close']
        for event in events:
            with self.subTest(event=event):
                kernel = SyntheticKernel(fault=event)
                with self.assertRaises(Exception):
                    P.child_setup(kernel, self.principal)
                if 'hold' in kernel.events and event != 'hold':
                    self.assertTrue(all(lease.closed for lease in kernel.leases))
                if event in ('admit', 'hold', 'origin', 'unshare_private', 'reopen',
                             'reopen_observed', 'project', 'projected_observed', 'drop', 'credentials'):
                    self.assertNotIn('worker', kernel.events)

    def test_all_identity_and_backing_drift_refused(self):
        for key in ('user', 'pid', 'net', 'root', 'app', 'locks', 'backing', 'mount', 'runtime'):
            for when in (2, 3, 4):
                with self.subTest(key=key, observation=when):
                    def mutate(read, value):
                        if read == when:
                            value[key] = SyntheticKernel().origin['mount'] if key == 'mount' else 'substituted'
                    with self.assertRaises(P.Refused):
                        P.child_setup(SyntheticKernel(mutate=mutate), self.principal)

    def test_missing_extra_or_unknown_observation_refused(self):
        for key in SyntheticKernel().origin:
            for extra in (False, True):
                def mutate(read, value):
                    if read > 1:
                        if extra: value['unknown'] = True
                        else: del value[key]
                with self.subTest(key=key, extra=extra), self.assertRaises(P.Refused):
                    P.child_setup(SyntheticKernel(mutate=mutate), self.principal)

    def test_ambiguous_namespace_and_metadata_types_refused(self):
        for key, replacement in (('mount', 'different-namespace'), ('user', (True, 1)),
                                 ('root', (1, 2)), ('locks', ((), ())),
                                 ('backing', ('8:1', '/', '/opt', '', '/dev/fake'))):
            def mutate(read, value):
                if read == 2: value[key] = replacement
            with self.subTest(key=key), self.assertRaises(P.Refused):
                P.child_setup(SyntheticKernel(mutate=mutate), self.principal)

    def test_unsafe_principals(self):
        for principal in (P.Principal(0, 1, ()), P.Principal(True, 1, ()),
                          P.Principal(1, 0, ()), P.Principal(1, 2, (0,)),
                          P.Principal(1, 2, (2, 2)), P.Principal(1, 2, (3, 2)),
                          P.Principal(1, 2, [2])):
            with self.subTest(principal=principal), self.assertRaises(P.Refused):
                P.child_setup(SyntheticKernel(), principal)

    def test_every_retained_capability_or_unsafe_credential_refused(self):
        baseline = dict(uids=(1701,)*3, gids=(2701,)*3, groups=(2701, 3701),
                        caps=(0,)*5, nnp=1, keepcaps=0)
        alternatives = [('uids', (1701, 1701, 0)), ('gids', (2701, 0, 2701)),
                        ('groups', (2701,)), ('groups', (2701, 3701, 9999)),
                        ('nnp', 0), ('nnp', True), ('keepcaps', 1), ('keepcaps', False)]
        alternatives += [('caps', tuple(1 if j == i else 0 for j in range(5))) for i in range(5)]
        alternatives += [('caps', (False,)*5), ('uids', (True,)*3)]
        for key, value in alternatives:
            with self.subTest(key=key, value=value), self.assertRaises(P.Refused):
                P.credential_matches(self.principal, {**baseline, key: value})

    def test_credential_mismatch_precedes_worker(self):
        kernel = SyntheticKernel()
        with patch.object(kernel, 'credentials', return_value=dict()), self.assertRaises(P.Refused):
            P.child_setup(kernel, self.principal)
        self.assertNotIn('worker', kernel.events)

    def test_missing_capability_precedes_fixture_open(self):
        for missing in P.CAPS:
            kernel = P.LinuxKernel(os.getppid())
            mask = sum(1 << capability for capability in P.CAPS - {missing})
            with self.subTest(missing=missing), \
                 patch.object(P.sys, 'platform', 'linux'), patch.object(P.platform, 'machine', return_value='x86_64'), \
                 patch.object(P.os, 'getuid', return_value=0), patch.object(P.os, 'geteuid', return_value=0), \
                 patch.object(P.os, 'listdir', return_value=['1']), patch.object(P.ctypes, 'CDLL'), \
                 patch.object(kernel, 'parent_death'), \
                 patch.object(kernel, 'status', return_value={'CapEff': hex(mask)[2:]}), \
                 patch.object(kernel, 'hold') as hold, self.assertRaises(P.Refused):
                P.child_setup(kernel, self.principal)
            hold.assert_not_called()

    def test_unsupported_platform_never_loads_linux_libc(self):
        with patch.object(P.sys, 'platform', 'darwin'), patch.object(P.ctypes, 'CDLL') as loader, \
             self.assertRaises(P.Refused):
            P.LinuxKernel(os.getppid()).admit()
        loader.assert_not_called()

    def test_mount_parser_rejects_ambiguity_incomplete_bounds(self):
        valid = b'20 1 8:1 / / rw shared:1 - ext4 /dev/fake rw\n'
        self.assertEqual(P.mount_table(valid)[20][5], ('shared:1',))
        for raw in (b'', valid[:-1], valid+valid, valid.replace(b' - ', b' - - '),
                    valid.replace(b'20 ', b'0 ', 1), valid.replace(b'/dev/fake', b'/bad\\040path'),
                    valid.replace(b'8:1', b'unknown'), valid.replace(b' rw\n', b'\n'),
                    valid+b'\x00\n', b'x'*1048577):
            with self.subTest(size=len(raw)), self.assertRaises(Exception):
                P.mount_table(raw)

    def test_exact_syscall_operation_allowlist(self):
        tree = ast.parse((HERE/'prototype.py').read_text())
        source = (HERE/'prototype.py').read_text()
        calls = [node for node in ast.walk(tree) if isinstance(node, ast.Call)]
        privileged = [node for node in calls if isinstance(node.func, ast.Attribute) and node.func.attr == 'call']
        self.assertEqual([ast.literal_eval(node.args[0]) for node in privileged],
                         ['unshare', 'mount', 'mount', 'mount', 'capset'])
        flags = [ast.unparse(node.args[4]) for node in privileged if ast.literal_eval(node.args[0]) == 'mount']
        self.assertEqual(flags, ['PRIVATE_RECURSIVE', 'BIND', 'READONLY_BIND'])
        self.assertEqual(P.NEWNS, 0x20000)
        self.assertTrue(P.PRIVATE_RECURSIVE & (1 << 18))
        self.assertFalse(any(isinstance(n, ast.Import) and any(a.name in ('socket', 'subprocess') for a in n.names) for n in ast.walk(tree)))
        self.assertNotIn('CLONE_NEWUSER', source)
        self.assertNotIn('chroot(', source)
        self.assertNotIn('setns(', source)
        self.assertEqual(P.FIXTURE, '/opt/clb91-b-launch-fixture')

    def test_arbitrary_operations_and_mount_targets_refuse_before_syscall(self):
        kernel = P.LinuxKernel(os.getppid())
        kernel.libc = Mock()
        kernel.source, kernel.target = Mock(fd=50), 51
        for name, args in (
            ('system', (b'id',)), ('unshare', (P.NEWNS | 0x10000000,)),
            ('mount', (None, b'/opt/clubs-bot-stage', None, P.READONLY_BIND, None)),
            ('mount', (None, b'/', None, 1 << 20, None)),
            ('mount', (None, b'/', None, P.BIND, None)),
            ('mount', (b'/untrusted', b'/proc/self/fd/51', None, P.BIND, None))):
            with self.subTest(operation=name), self.assertRaises(P.Refused):
                kernel.call(name, *args)
        self.assertEqual(kernel.libc.mock_calls, [])

    def test_mocked_drop_preserves_groups_and_rearms_parent_death(self):
        kernel = P.LinuxKernel(os.getppid())
        kernel.leases, kernel.directories, kernel.locks = [], [], []
        kernel.libc = Mock()
        events = []
        def prctl(operation, argument=0):
            events.append(('prctl', operation, argument))
            return 0
        def call(name, *args):
            events.append((name,))
        with patch.object(kernel, 'prctl', side_effect=prctl), patch.object(kernel, 'call', side_effect=call), \
             patch.object(P.os, 'listdir', return_value=[]), \
             patch.object(P.os, 'setgroups', side_effect=lambda groups: events.append(('groups', groups))), \
             patch.object(P.os, 'setresgid', side_effect=lambda *args: events.append(('gids', args)), create=True), \
             patch.object(P.os, 'setresuid', side_effect=lambda *args: events.append(('uids', args)), create=True), \
             patch('builtins.open', return_value=io.BytesIO(b'40\n')):
            kernel.drop(self.principal)
        self.assertEqual([e for e in events if e[0] in ('groups', 'gids', 'uids')],
                         [('groups', self.principal.groups), ('gids', (2701,)*3), ('uids', (1701,)*3)])
        self.assertEqual([e[2] for e in events if e[:2] == ('prctl', 24)], list(range(41)))
        self.assertLess(events.index(('uids', (1701,)*3)), events.index(('capset',)))
        self.assertLess(events.index(('capset',)), events.index(('prctl', 38, 1)))
        self.assertLess(events.index(('prctl', 38, 1)), events.index(('prctl', 1, signal.SIGKILL)))

    def test_orphan_or_parent_death_setup_failure_refuses(self):
        kernel = P.LinuxKernel(os.getppid())
        with patch.object(P.os, 'getppid', return_value=1), self.assertRaises(P.Refused):
            kernel.parent_death()
        with patch.object(kernel, 'prctl', return_value=-1), self.assertRaises(P.Refused):
            kernel.parent_death()


class RealPortableLease(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.directory = Path(self.temporary.name)
        self.parent = os.open(self.directory, os.O_RDONLY | os.O_DIRECTORY)
        self.path = self.directory/'input'
        self.path.write_bytes(b'SYNTHETIC')
        self.path.chmod(0o600)

    def tearDown(self):
        os.close(self.parent)
        self.temporary.cleanup()

    def lease(self, **kwargs):
        return P.Lease(self.parent, 'input', os.getuid(), 0o600, **kwargs)

    def test_replacement_same_path_not_identity(self):
        held = self.lease()
        self.path.rename(self.directory/'old')
        self.path.write_bytes(b'SYNTHETIC')
        self.path.chmod(0o600)
        try:
            with self.assertRaises(P.Refused): held.recheck()
            self.assertEqual(os.pread(held.fd, 20, 0), b'SYNTHETIC')
        finally: held.close()

    def test_symlink_refused(self):
        self.path.rename(self.directory/'old')
        self.path.symlink_to('old')
        with self.assertRaises(OSError): self.lease()

    def test_owner_group_mode_link_and_size_refused(self):
        with self.assertRaises(P.Refused): P.Lease(self.parent, 'input', os.getuid()+1, 0o600)
        with self.assertRaises(P.Refused): self.lease(group=os.getgid()+1)
        self.path.chmod(0o666)
        with self.assertRaises(P.Refused): self.lease()
        self.path.chmod(0o600)
        os.link(self.path, self.directory/'hardlink')
        with self.assertRaises(P.Refused): self.lease()
        (self.directory/'hardlink').unlink()
        self.path.write_bytes(b'x'*4097)
        with self.assertRaises(P.Refused): self.lease()

    def test_content_drift_refused(self):
        held = self.lease()
        try:
            self.path.write_bytes(b'CHANGED')
            with self.assertRaises(P.Refused): held.recheck()
        finally: held.close()

    def test_exact_fixture_component_name_is_supported(self):
        directory = self.directory/'clb91-b-launch-fixture'
        directory.mkdir(mode=0o755)
        held = P.Lease(self.parent, directory.name, os.getuid(), 0o755, directory=True)
        held.close()

    def test_real_kernel_lock_contention_same_inode(self):
        first = self.lease(writable=True)
        second = self.lease(writable=True)
        try:
            P.fcntl.flock(first.fd, P.fcntl.LOCK_EX | P.fcntl.LOCK_NB)
            with self.assertRaises(BlockingIOError):
                P.fcntl.flock(second.fd, P.fcntl.LOCK_EX | P.fcntl.LOCK_NB)
            P.fcntl.flock(first.fd, P.fcntl.LOCK_UN)
            P.fcntl.flock(second.fd, P.fcntl.LOCK_EX | P.fcntl.LOCK_NB)
        finally:
            second.close()
            first.close()

    def test_close_failure_attempts_other_resources(self):
        first, second = self.lease(), self.lease()
        with patch.object(second, 'close', side_effect=OSError()), self.assertRaises(P.Refused):
            P.close_all([first, second])
        self.assertTrue(first.closed)
        second.close()


class SyntheticRootHold(unittest.TestCase):
    """Real disposable FD tree; only root ownership/root path are injected.

    This executes native hold's actual acquisition and partial-cleanup code,
    without mounting, changing IDs, reading /opt or claiming root ownership.
    """
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.fixture = self.root/'opt'/'clb91-b-launch-fixture'
        self.fixture.mkdir(parents=True, mode=0o755)
        (self.root/'opt').chmod(0o755)
        for name in ('source', 'view', 'application'):
            (self.fixture/name).mkdir(mode=0o755)
        (self.fixture/'source'/'runtime').write_bytes(b'CLB91-SYNTHETIC-RUNTIME\n')
        (self.fixture/'source'/'runtime').chmod(0o444)
        (self.fixture/'source').chmod(0o555)
        (self.fixture/'view').chmod(0o555)
        (self.fixture/'application').chmod(0o700)
        for name in ('application-lock', 'operation-lock'):
            path = self.fixture/'application'/name
            path.touch(mode=0o600)
        self.principal = P.Principal(os.getuid(), os.getgid(), ())

    def tearDown(self):
        (self.fixture/'source').chmod(0o755)
        self.temporary.cleanup()

    def kernel(self):
        kernel = P.LinuxKernel(os.getppid())
        kernel.leases, kernel.directories, kernel.projected = [], [], False
        return kernel

    def hold(self, kernel):
        real_open, real_lease = os.open, P.Lease
        def synthetic_root(path, flags, *args, **kwargs):
            return real_open(self.root if path == '/' else path, flags, *args, **kwargs)
        def synthetic_owner(parent, name, owner, mode, **kwargs):
            if owner == 0: owner = os.getuid()
            if kwargs.get('group') == 0: kwargs['group'] = os.getgid()
            return real_lease(parent, name, owner, mode, **kwargs)
        with patch.object(P.os, 'open', side_effect=synthetic_root), patch.object(P, 'Lease', side_effect=synthetic_owner):
            return kernel.hold(self.principal)

    def test_complete_fixed_fixture_acquisition_and_cleanup(self):
        kernel = self.kernel()
        try:
            leases = self.hold(kernel)
            self.assertEqual(len(leases), 8)
            for lease in leases: lease.recheck()
        finally:
            kernel.close_directories()
            P.close_all(kernel.leases)
        self.assertTrue(all(lease.closed for lease in leases))

    def test_wrong_runtime_bytes_refuse_without_retained_leases(self):
        path = self.fixture/'source'/'runtime'
        path.chmod(0o600)
        path.write_bytes(b'WRONG-SYNTHETIC-RUNTIME\n')
        path.chmod(0o444)
        kernel = self.kernel()
        try:
            with self.assertRaises(P.Refused): self.hold(kernel)
            self.assertEqual(kernel.leases, [])
        finally: kernel.close_directories()

    def test_busy_application_lock_refuses_partial_acquisition(self):
        first, second = self.kernel(), self.kernel()
        try:
            self.hold(first)
            with self.assertRaises(BlockingIOError): self.hold(second)
            self.assertEqual(second.leases, [])
        finally:
            second.close_directories()
            first.close_directories()
            P.close_all(first.leases)


class RealPortableSupervision(unittest.TestCase):
    def test_stdin_canary_is_not_inherited(self):
        reader, writer = os.pipe()
        os.write(writer, b'PRIVATE-SYNTHETIC-STDIN-CANARY')
        os.close(writer)
        saved = os.dup(0)
        try:
            os.dup2(reader, 0)
            def worker():
                P.require(os.read(0, 128) == b'')
                P.require(P.stat.S_ISCHR(os.fstat(0).st_mode))
            self.assertEqual(P.supervise(worker, timeout=1, cancelled=lambda: False),
                             'SYNTHETIC_CHILD_COMPLETED')
        finally:
            os.dup2(saved, 0)
            os.close(saved)
            os.close(reader)

    def test_observer_receives_only_exact_owned_child_lifecycle(self):
        observer = Mock()
        P.supervise(lambda: None, timeout=1, cancelled=lambda: False, observer=observer)
        self.assertEqual(len(observer.spawned.call_args.args), 1)
        self.assertGreater(observer.spawned.call_args.args[0], 1)
        self.assertEqual(observer.finished.call_args.args, (0, False, True))
    def test_success_is_only_synthetic(self):
        self.assertEqual(P.supervise(lambda: None, timeout=1, cancelled=lambda: False),
                         'SYNTHETIC_CHILD_COMPLETED')

    def test_failure_never_passes_or_publishes_canary(self):
        def fail():
            os.write(1, b'PRIVATE-SYNTHETIC-CANARY')
            os.write(2, b'PRIVATE-SYNTHETIC-CANARY')
            raise RuntimeError('PRIVATE-SYNTHETIC-CANARY')
        reader, writer = os.pipe()
        saved = (os.dup(1), os.dup(2))
        try:
            os.dup2(writer, 1)
            os.dup2(writer, 2)
            with self.assertRaises(P.Refused):
                P.supervise(fail, timeout=1, cancelled=lambda: False)
        finally:
            os.dup2(saved[0], 1)
            os.dup2(saved[1], 2)
            os.close(saved[0])
            os.close(saved[1])
            os.close(writer)
        try:
            self.assertEqual(os.read(reader, 4097), b'')
        finally:
            os.close(reader)

    def test_timeout_cancellation_signal_and_confirmed_child_reaping(self):
        ticks = []
        def later_cancel():
            ticks.append(True)
            return len(ticks) >= 3
        for cancelled, timeout, task in ((lambda: False, .03, lambda: time.sleep(2)),
                                         (later_cancel, 1, lambda: time.sleep(2)),
                                         (lambda: False, 1, lambda: os.kill(os.getpid(), signal.SIGTERM))):
            seen = []
            fork = os.fork
            def record():
                pid = fork()
                if pid: seen.append(pid)
                return pid
            with self.subTest(timeout=timeout), patch.object(P.os, 'fork', side_effect=record), self.assertRaises(P.Refused):
                P.supervise(task, timeout=timeout, cancelled=cancelled)
            self.assertEqual(len(seen), 1)
            with self.assertRaises(ChildProcessError): os.waitpid(seen[0], os.WNOHANG)
            with self.assertRaises(ProcessLookupError): os.kill(seen[0], 0)

    def test_cleanup_failure_never_passes(self):
        kill = os.kill
        def fail_after_cleanup(pid, sig):
            kill(pid, sig)
            raise PermissionError()
        with patch.object(P.os, 'kill', side_effect=fail_after_cleanup), self.assertRaises(P.Refused):
            P.supervise(lambda: time.sleep(.2), timeout=.03, cancelled=lambda: False)

    def test_supervisor_signal_cancels_and_restores_handlers(self):
        handlers = {s: signal.getsignal(s) for s in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP)}
        sent = []
        def cancel():
            if not sent:
                sent.append(True)
                os.kill(os.getpid(), signal.SIGTERM)
            return False
        with self.assertRaises(P.Refused):
            P.supervise(lambda: time.sleep(2), timeout=1, cancelled=cancel)
        self.assertEqual({s: signal.getsignal(s) for s in handlers}, handlers)

    def test_cancellation_at_successful_reap_cannot_become_pass(self):
        waitpid = os.waitpid
        def signal_at_reap(pid, flags):
            found, status = waitpid(pid, flags)
            if found:
                os.kill(os.getpid(), signal.SIGTERM)
            return found, status
        with patch.object(P.os, 'waitpid', side_effect=signal_at_reap), self.assertRaises(P.Refused):
            P.supervise(lambda: None, timeout=1, cancelled=lambda: False)

    def test_never_signals_pid_after_successful_reap(self):
        with patch.object(P.os, 'kill') as kill:
            self.assertEqual(P.supervise(lambda: None, timeout=1, cancelled=lambda: False),
                             'SYNTHETIC_CHILD_COMPLETED')
        kill.assert_not_called()

    def test_unconfirmed_cleanup_has_bounded_refusal(self):
        # No real child: an adversarial kernel model refuses KILL and never
        # supplies a wait result. It must terminate with failure, not block.
        clock = iter(range(10))
        with patch.object(P.os, 'fork', return_value=12345), \
             patch.object(P.os, 'waitpid', return_value=(0, 0)) as wait, \
             patch.object(P.os, 'kill', side_effect=PermissionError()), \
             patch.object(P.time, 'monotonic', side_effect=lambda: next(clock)), \
             patch.object(P.time, 'sleep'), self.assertRaises(P.Refused):
            P.supervise(lambda: None, timeout=.1, cancelled=lambda: False)
        self.assertTrue(all(call.args[1] == os.WNOHANG for call in wait.call_args_list))

    def test_native_trial_refuses_here_without_root_or_syscalls(self):
        with patch.object(P.sys, 'platform', 'darwin'), patch.object(P, 'LinuxKernel') as kernel, \
             self.assertRaises(P.Refused):
            P.native_trial(Contract.principal, lambda: False)
        kernel.assert_not_called()

    def test_preexisting_cancellation_prevents_fork(self):
        with patch.object(P.os, 'fork') as fork, self.assertRaises(P.Refused):
            P.supervise(lambda: None, timeout=1, cancelled=lambda: True)
        fork.assert_not_called()


if __name__ == '__main__':
    require_non_root = os.geteuid() != 0
    if not require_non_root:
        raise SystemExit('portable suite requires non-root')
    unittest.main(verbosity=2)
