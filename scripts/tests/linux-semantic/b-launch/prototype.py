"""CLB-91 test-only mount-view experiment. No command-line/native entrypoint.

Only a separately authorized disposable Linux harness may call child_setup.
There is no release operation, Engine client, external command or stage adapter.
Synthetic tests inject kernel observations; they cannot produce native evidence.
"""
import ctypes
from dataclasses import dataclass
import fcntl
import os
import platform
import re
import signal
import stat
import sys
import time

FIXTURE = '/opt/clb91-b-launch-fixture'
NEWNS = 0x00020000
PRIVATE_RECURSIVE = (1 << 18) | (1 << 14)
BIND = 4096
READONLY_BIND = BIND | 32 | 1 | 2 | 4
CAPS = frozenset((5, 6, 7, 8, 21))  # supervisor KILL; SETGID/SETUID/SETPCAP/SYS_ADMIN


class Refused(Exception):
    """Never carry paths, observed identities or exception text."""


def require(ok):
    if not ok:
        raise Refused() from None


def exact(left, right):
    if type(left) is not type(right):
        return False
    if type(left) is tuple:
        return len(left) == len(right) and all(exact(a, b) for a, b in zip(left, right))
    return type(left) in (int, str) and left == right


@dataclass(frozen=True)
class Principal:
    uid: int
    gid: int
    groups: tuple

    def validate(self):
        require(type(self.uid) is int and 0 < self.uid < 2**31)
        require(type(self.gid) is int and 0 < self.gid < 2**31)
        require(type(self.groups) is tuple and len(self.groups) <= 64)
        require(all(type(g) is int and 0 < g < 2**31 for g in self.groups))
        require(self.groups == tuple(sorted(set(self.groups))))


def identity(info):
    return (info.st_dev, info.st_ino, info.st_mode, info.st_uid, info.st_gid,
            info.st_nlink, info.st_size, info.st_mtime_ns, info.st_ctime_ns)


class Lease:
    """A no-follow edge and its opened object; replacement is never adopted."""
    def __init__(self, parent, name, owner, mode, directory=False, writable=False, group=None):
        require(type(name) is str and re.fullmatch(r'[a-z][a-z0-9-]{0,31}', name))
        flags = os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC
        flags |= os.O_DIRECTORY if directory else 0
        flags |= os.O_RDWR if writable else os.O_RDONLY
        self.fd = os.open(name, flags, dir_fd=parent)
        self.parent, self.name, self.closed = parent, name, False
        try:
            info = os.fstat(self.fd)
            require((stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode)))
            require(info.st_uid == owner and stat.S_IMODE(info.st_mode) == mode)
            require(group is None or info.st_gid == group)
            require(directory or (info.st_nlink == 1 and info.st_size <= 4096))
            self.expected = identity(info)
            self.recheck()
        except BaseException:
            os.close(self.fd)
            self.closed = True
            raise

    def recheck(self):
        require(not self.closed and identity(os.fstat(self.fd)) == self.expected)
        require(identity(os.stat(self.name, dir_fd=self.parent, follow_symlinks=False)) == self.expected)

    def close(self):
        require(not self.closed)
        self.closed = True
        os.close(self.fd)


def close_all(leases):
    failed = False
    for lease in reversed(leases):
        try:
            lease.close()
        except BaseException:
            failed = True
    require(not failed)


def mount_table(raw):
    """Bounded fail-closed parser, not a mount discovery/selection API."""
    require(type(raw) is bytes and 0 < len(raw) <= 1048576 and raw.endswith(b'\n'))
    require(b'\x00' not in raw and b'\\' not in raw and b'\t' not in raw)
    lines = raw.decode('ascii').splitlines()
    require(0 < len(lines) <= 4096)
    result = {}
    for line in lines:
        fields = line.split(' ')
        require('' not in fields and fields.count('-') == 1)
        separator = fields.index('-')
        require(separator >= 6 and len(fields) == separator + 4)
        require(all(re.fullmatch('[1-9][0-9]{0,9}', v) for v in fields[:2]))
        require(re.fullmatch('[0-9]+:[0-9]+', fields[2]))
        require(fields[3].startswith('/') and fields[4].startswith('/'))
        number = int(fields[0])
        require(number not in result)
        result[number] = (fields[2], fields[3], fields[4], fields[separator+1],
                          fields[separator+2], tuple(fields[6:separator]), fields[5])
    return result


def backing(fd, table):
    with open('/proc/self/fdinfo/' + str(fd), 'rb') as stream:
        raw = stream.read(4097)
    require(len(raw) <= 4096)
    ids = re.findall(rb'^mnt_id:\s*([1-9][0-9]*)$', raw, re.M)
    require(len(ids) == 1 and int(ids[0]) in table)
    return table[int(ids[0])][:5]


def view_matches(origin, current, projected=False):
    require(type(origin) is dict and type(current) is dict)
    keys = {'user', 'pid', 'net', 'mount', 'root', 'app', 'locks', 'backing', 'runtime'}
    require(set(origin) == keys == set(current))
    for value in (origin, current):
        def integers(items, length):
            return type(items) is tuple and len(items) == length and all(type(i) is int and i >= 0 for i in items)
        require(all(integers(value[k], 2) for k in ('user', 'pid', 'net', 'mount')))
        require(all(integers(value[k], 9) for k in ('root', 'app')))
        require(type(value['locks']) is tuple and len(value['locks']) == 2
                and all(integers(lock, 9) for lock in value['locks']))
        require(type(value['backing']) is tuple and len(value['backing']) == 5
                and all(type(item) is str and 0 < len(item) <= 4096 for item in value['backing']))
        require(value['runtime'] in ('source', 'target'))
    require(all(exact(current[k], origin[k]) for k in keys - {'mount', 'runtime'}))
    require(current['mount'] != origin['mount'])
    require(current['runtime'] == ('source' if projected else 'target'))


def credential_matches(principal, value):
    require(type(value) is dict and set(value) == {'uids', 'gids', 'groups', 'caps', 'nnp', 'keepcaps'})
    require(exact(value['uids'], (principal.uid,) * 3) and exact(value['gids'], (principal.gid,) * 3))
    require(exact(value['groups'], principal.groups) and exact(value['caps'], (0,) * 5))
    require(type(value['nnp']) is int and value['nnp'] == 1)
    require(type(value['keepcaps']) is int and value['keepcaps'] == 0)


def child_setup(kernel, principal):
    """Fixed operation sequence; executes ONLY in an already forked test child."""
    principal.validate()
    leases = None
    try:
        kernel.admit()
        leases = kernel.hold(principal)
        origin = kernel.observe()
        require(origin['runtime'] == 'target')
        kernel.unshare_private()
        kernel.reopen()
        view_matches(origin, kernel.observe())
        kernel.project()
        view_matches(origin, kernel.observe(), projected=True)
        kernel.drop(principal)
        credential_matches(principal, kernel.credentials())
        kernel.worker()
        view_matches(origin, kernel.observe(), projected=True)
        kernel.recheck()
    finally:
        failed = False
        try:
            kernel.close_directories()
        except BaseException:
            failed = True
        try:
            if leases is not None:
                close_all(leases)
        except BaseException:
            failed = True
        require(not failed)


class LinuxKernel:
    """Native source candidate. NOT executed/accepted by the portable suite.

    Python/libc/bootstrap and this source must be independently verified by a
    future disposable native harness. This class supplies no privilege grant.
    """
    def __init__(self, supervisor_pid):
        require(type(supervisor_pid) is int and supervisor_pid > 1)
        self.supervisor_pid = supervisor_pid

    def parent_death(self):
        require(os.getppid() == self.supervisor_pid)
        require(self.prctl(1, signal.SIGKILL) == 0)
        require(os.getppid() == self.supervisor_pid)

    def admit(self):
        self.leases, self.directories, self.projected = [], [], False
        require(sys.platform == 'linux' and platform.machine() == 'x86_64')
        require(os.getuid() == os.geteuid() == 0 and len(os.listdir('/proc/self/task')) == 1)
        self.libc = ctypes.CDLL(None, use_errno=True)
        self.libc.mount.argtypes = [ctypes.c_char_p] * 3 + [ctypes.c_ulong, ctypes.c_void_p]
        self.libc.mount.restype = ctypes.c_int
        self.libc.unshare.argtypes, self.libc.unshare.restype = [ctypes.c_int], ctypes.c_int
        self.libc.prctl.argtypes = [ctypes.c_int] + [ctypes.c_ulong] * 4
        self.libc.prctl.restype = ctypes.c_int
        self.parent_death()
        status = self.status()
        require(CAPS <= {i for i in range(64) if int(status['CapEff'], 16) & (1 << i)})

    def call(self, name, *args):
        # No syscall/operation selector is accepted from a request or argv.
        require(name in ('unshare', 'mount', 'capset'))
        if name == 'unshare':
            require(args == (NEWNS,))
        if name == 'mount':
            allowed = [(None, b'/', None, PRIVATE_RECURSIVE, None)]
            if hasattr(self, 'target') and hasattr(self, 'source'):
                allowed += [
                    (('/proc/self/fd/' + str(self.source.fd)).encode(),
                     ('/proc/self/fd/' + str(self.target)).encode(), None, BIND, None),
                    (None, (FIXTURE + '/view').encode(), None, READONLY_BIND, None)]
            require(args in allowed)
        require(getattr(self.libc, name)(*args) == 0)

    def prctl(self, operation, argument=0):
        return self.libc.prctl(operation, argument, 0, 0, 0)

    def status(self):
        with open('/proc/self/status', 'rb') as stream:
            raw = stream.read(32769)
        require(len(raw) <= 32768)
        values = {}
        for line in raw.decode('ascii').splitlines():
            key, _, value = line.partition(':')
            require(key not in values)
            values[key] = value.strip()
        return values

    def hold(self, principal):
        # No caller paths. All mount targets and inputs belong to this one fixture.
        try:
            parent = os.open('/', os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC)
            self.directories.append(parent)
            for name in ('opt', 'clb91-b-launch-fixture'):
                lease = Lease(parent, name, 0, 0o755, directory=True, group=0)
                self.leases.append(lease)
                parent = lease.fd
            self.base = parent
            for name in ('source', 'view'):
                lease = Lease(parent, name, 0, 0o555, directory=True, group=0)
                self.leases.append(lease)
                setattr(self, name, lease)
            runtime = Lease(self.source.fd, 'runtime', 0, 0o444, group=0)
            self.leases.append(runtime)
            require(os.pread(runtime.fd, 4097, 0) == b'CLB91-SYNTHETIC-RUNTIME\n')
            require(os.listdir(self.source.fd) == ['runtime'] and os.listdir(self.view.fd) == [])
            app = Lease(parent, 'application', principal.uid, 0o700, directory=True, group=principal.gid)
            self.leases.append(app)
            self.app = app
            self.locks = []
            for name in ('application-lock', 'operation-lock'):
                lock = Lease(app.fd, name, principal.uid, 0o600, writable=True, group=principal.gid)
                self.leases.append(lock)
                require(os.fstat(lock.fd).st_size == 0)
                fcntl.flock(lock.fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                self.locks.append(lock)
            return self.leases
        except BaseException:
            # child_setup always closes directory handles, including partial hold.
            acquired, self.leases = self.leases, []
            close_all(acquired)
            raise

    def current_directory(self, name):
        require(name in ('application', 'view', 'source'))
        descriptor = os.open('/', os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            for part in ('opt', 'clb91-b-launch-fixture', name):
                child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=descriptor)
                previous, descriptor = descriptor, child
                os.close(previous)
            return descriptor
        except BaseException:
            os.close(descriptor)
            raise

    def recheck(self):
        for lease in self.leases:
            if lease is self.view and self.projected:
                # The one intentionally overmounted runtime target keeps its
                # original anchor, while its current view must match the source.
                require(identity(os.fstat(lease.fd)) == lease.expected)
            else:
                lease.recheck()

    def table(self):
        with open('/proc/self/mountinfo', 'rb') as stream:
            raw = stream.read(1048577)
        return mount_table(raw)

    def observe(self):
        table = self.table()
        # Reopen from the current namespace; inherited anchor FDs alone do not
        # prove mount membership. Every canonical edge is also rechecked.
        self.recheck()
        current = self.current_directory('application')
        try:
            require(identity(os.fstat(current)) == self.app.expected)
            filesystem = backing(current, table)
            require(filesystem[3] in ('ext2', 'ext3', 'ext4', 'xfs', 'btrfs', 'zfs', 'f2fs'))
            for lock in self.locks:
                fresh = os.open(lock.name, os.O_RDWR | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=current)
                try:
                    require(identity(os.fstat(fresh)) == lock.expected)
                    require(backing(fresh, table) == filesystem)
                finally:
                    os.close(fresh)
        finally:
            os.close(current)
        ns = {n: (os.stat('/proc/self/ns/' + n).st_dev, os.stat('/proc/self/ns/' + n).st_ino)
              for n in ('user', 'pid', 'net', 'mnt')}
        descriptor = self.current_directory('view')
        try:
            target = os.fstat(descriptor)
            backing(descriptor, table)
        finally:
            os.close(descriptor)
        label = 'source' if identity(target) == self.source.expected else 'target'
        require(label == 'source' or identity(target) == self.view.expected)
        return dict(user=ns['user'], pid=ns['pid'], net=ns['net'], mount=ns['mnt'],
                    root=identity(os.stat('/')), app=self.app.expected,
                    locks=tuple(identity(os.fstat(x.fd)) for x in self.locks),
                    backing=filesystem, runtime=label)

    def unshare_private(self):
        self.call('unshare', NEWNS)
        self.call('mount', None, b'/', None, PRIVATE_RECURSIVE, None)
        self.require_private()

    def require_private(self):
        require(all(not row[5] for row in self.table().values()))

    def reopen(self):
        # A target FD from the parent mount namespace is not a mount destination.
        self.target = self.current_directory('view')
        self.directories.append(self.target)
        require(identity(os.fstat(self.target)) == self.view.expected)
        table = self.table()
        backing(self.target, table)
        require(not any(row[2].startswith(FIXTURE + '/source/') for row in table.values()))

    def project(self):
        before = self.table()
        require(not any(row[2] == FIXTURE + '/view' or row[2].startswith(FIXTURE + '/view/')
                        for row in before.values()))
        self.call('mount', ('/proc/self/fd/' + str(self.source.fd)).encode(),
                  ('/proc/self/fd/' + str(self.target)).encode(), None, BIND, None)
        self.call('mount', None, (FIXTURE + '/view').encode(), None, READONLY_BIND, None)
        self.projected = True
        after = self.table()
        added = set(after) - set(before)
        require(len(added) == 1 and after[next(iter(added))][2] == FIXTURE + '/view')
        require(all(after[k] == v for k, v in before.items()))
        self.require_private()
        require({'ro', 'nosuid', 'nodev'} <= set(after[next(iter(added))][6].split(',')))

    def drop(self, principal):
        # The held directory anchors are read-only (including '/'); no setup
        # writable FD or namespace handle is handed to the non-root worker.
        retained = {0, 1, 2} | {lease.fd for lease in self.leases} | set(self.directories)
        writable = {lock.fd for lock in self.locks}
        require(all(fcntl.fcntl(fd, fcntl.F_GETFL) & os.O_ACCMODE ==
                    (os.O_RDWR if fd in writable else os.O_RDONLY) for fd in retained - {0, 1, 2}))
        descriptors = [int(n) for n in os.listdir('/proc/self/fd')]
        require(len(descriptors) <= 1024)
        for fd in descriptors:
            if fd not in retained:
                try:
                    os.close(fd)
                except OSError as error:
                    require(error.errno == 9)  # listdir's already closed own FD
        require(self.prctl(8, 0) == 0)  # KEEPCAPS disabled
        require(self.prctl(47, 4) == 0)  # clear ambient set
        with open('/proc/sys/kernel/cap_last_cap', 'rb') as stream:
            raw = stream.read(5)
        require(re.fullmatch(rb'[0-9]{1,2}\n', raw))
        last = int(raw)
        require(21 <= last <= 63)
        for capability in range(last + 1):
            require(self.prctl(24, capability) == 0)  # bounding drop, including SETPCAP
        os.setgroups(principal.groups)
        os.setresgid(principal.gid, principal.gid, principal.gid)
        os.setresuid(principal.uid, principal.uid, principal.uid)
        class Header(ctypes.Structure):
            _fields_ = [('version', ctypes.c_uint32), ('pid', ctypes.c_int)]
        class Data(ctypes.Structure):
            _fields_ = [('effective', ctypes.c_uint32), ('permitted', ctypes.c_uint32),
                        ('inheritable', ctypes.c_uint32)]
        header, data = Header(0x20080522, 0), (Data * 2)()
        self.libc.capset.argtypes = [ctypes.POINTER(Header), ctypes.POINTER(Data)]
        self.libc.capset.restype = ctypes.c_int
        self.call('capset', ctypes.byref(header), data)
        require(self.prctl(38, 1) == 0)  # NO_NEW_PRIVS, irreversible
        # setresuid/setresgid clear PDEATHSIG: rearm and reject an orphan before
        # the worker can proceed. The expected supervisor PID is a harness value.
        self.parent_death()
        # Credential changes reset dumpability. Same-UID subprocesses must be
        # able to dereference the worker's FD paths, as the helper requires.
        require(self.prctl(4, 1) == 0)

    def credentials(self):
        value = self.status()
        return dict(uids=os.getresuid(), gids=os.getresgid(), groups=tuple(sorted(os.getgroups())),
                    caps=tuple(int(value[k], 16) for k in ('CapInh', 'CapPrm', 'CapEff', 'CapBnd', 'CapAmb')),
                    nnp=int(value['NoNewPrivs']), keepcaps=self.prctl(7))

    def worker(self):
        # No external program or arbitrary callback. Actual cross-process proc
        # dereference and contention controls belong to the future native harness.
        for lock in self.locks:
            descriptor = os.open('/proc/self/fd/' + str(lock.fd), os.O_RDWR)
            try:
                require(identity(os.fstat(descriptor)) == lock.expected)
                try:
                    fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
                except BlockingIOError:
                    pass
                else:
                    raise Refused()
            finally:
                os.close(descriptor)
        directory = self.current_directory('view')
        try:
            descriptor = os.open('runtime', os.O_RDONLY | os.O_NOFOLLOW, dir_fd=directory)
            try:
                require(os.read(descriptor, 4097) == b'CLB91-SYNTHETIC-RUNTIME\n')
            finally:
                os.close(descriptor)
        finally:
            os.close(directory)

    def close_directories(self):
        failed = False
        for descriptor in reversed(self.directories):
            try:
                os.close(descriptor)
            except OSError:
                failed = True
        self.directories = []
        require(not failed)


def _supervise_owned(fixed_child, *, timeout, cancelled, observer=None):
    """Portable owned-child envelope, for this fixed synthetic test only.

    fixed_child is the in-process test function, never argv, shell or IPC input.
    The fixed worker never forks/execs. This envelope reaps its one owned child;
    it makes no general descendant-containment guarantee.
    """
    require(type(timeout) in (int, float) and 0 < timeout <= 10)
    require(not cancelled())
    pid = os.fork()
    if pid == 0:
        try:
            for sig in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
                signal.signal(sig, signal.SIG_DFL)
            # No child output is part of the public protocol.
            null = os.open(os.devnull, os.O_RDWR | os.O_NOFOLLOW)
            info = os.fstat(null)
            require(stat.S_ISCHR(info.st_mode) and info.st_uid == 0
                    and stat.S_IMODE(info.st_mode) == 0o666)
            os.dup2(null, 0)
            os.dup2(null, 1)
            os.dup2(null, 2)
            if null > 2:
                os.close(null)
            fixed_child()
            os._exit(0)
        except BaseException:
            os._exit(1)
    status, stopped, clean = None, False, True
    try:
        if observer is not None:
            observer.spawned(pid)
        end = time.monotonic() + timeout
        while True:
            found, value = os.waitpid(pid, os.WNOHANG)
            if found == pid:
                status = value
                break
            if cancelled() or time.monotonic() >= end:
                stopped = True
                break
            time.sleep(0.005)
    finally:
        if status is None:
            # Until waitpid reaps it, our live/zombie child reserves its PID.
            # Never signal the number after reaping: it could have been reused.
            try:
                os.kill(pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            except OSError:
                clean = False
            deadline = time.monotonic() + 1
            while status is None:
                try:
                    found, value = os.waitpid(pid, os.WNOHANG)
                    if found == pid:
                        status = value
                        break
                except OSError:
                    clean = False
                    break
                if time.monotonic() >= deadline:
                    # Cleanup is UNCONFIRMED. Refuse; the disposable harness
                    # must quarantine/tear down its VM, never claim native PASS.
                    clean = False
                    break
                time.sleep(.005)
        if observer is not None:
            observer.finished(status, stopped, clean)
    require(clean and not stopped and status is not None and os.WIFEXITED(status)
            and os.WEXITSTATUS(status) == 0 and not cancelled())
    return 'SYNTHETIC_CHILD_COMPLETED'  # never namespace/native/stage acceptance


def supervise(fixed_child, *, timeout, cancelled, observer=None):
    previous, interrupted = {}, []
    def stop(_signal, _frame):
        interrupted.append(True)
    try:
        for sig in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
            previous[sig] = signal.getsignal(sig)
            signal.signal(sig, stop)
        return _supervise_owned(fixed_child, timeout=timeout,
                                cancelled=lambda: bool(interrupted) or cancelled(), observer=observer)
    finally:
        failed = False
        for sig, handler in previous.items():
            try:
                signal.signal(sig, handler)
            except BaseException:
                failed = True
        require(not failed and not interrupted)


def native_trial(principal, cancelled):
    """Dormant test-only call for a FUTURE separately approved Linux harness.

    No source/bootstrap/disposable-host attestation is minted here. That harness
    must verify them independently before calling this function as root.
    """
    require(sys.platform == 'linux' and platform.machine() == 'x86_64')
    require(os.getuid() == os.geteuid() == 0)
    require(not cancelled())
    principal.validate()
    kernel = LinuxKernel(os.getpid())
    # Verify supervisor kill authority before forking a different-UID worker.
    require(CAPS <= {i for i in range(64) if int(kernel.status()['CapEff'], 16) & (1 << i)})
    before = kernel.table(), identity(os.stat('/'))
    try:
        supervise(lambda: child_setup(kernel, principal), timeout=5, cancelled=cancelled)
    finally:
        require((kernel.table(), identity(os.stat('/'))) == before)
    require(not cancelled())
    return 'NATIVE_TRIAL_COMPLETED_NOT_ACCEPTED'


if __name__ == '__main__':
    # The future native harness must first be reviewed and separately authorized.
    print('b-launch:v=1 result=not_run reason=native_harness_required')
    raise SystemExit(1)
