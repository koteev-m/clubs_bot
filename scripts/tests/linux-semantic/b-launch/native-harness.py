"""Dormant real-kernel controls. Entry loads only source-contract-checked bytes.

No CLI here, no Engine, subprocess, external program, installer or host grant.
All mutations are fixed, owned synthetic fixture objects or child-local state.
"""
import ctypes
import errno
import json
import mmap
import os
from pathlib import Path
import platform
import resource
import select
import signal
import stat
import struct
import sys
import time

P = R = None  # source-verified injection, never path/plugin discovery
ALLOWED_FS = ('ext2', 'ext3', 'ext4', 'xfs', 'btrfs', 'zfs', 'f2fs')
PRINCIPALS = ((24001, 25001, (25001, 26001)), (24002, 25002, (25002, 26002)))
CAP_CASES = dict(missing_kill=5, missing_setgid=6, missing_setuid=7,
                 missing_setpcap=8, missing_sys_admin=21)
EVENTS = {'projected', 'worker', 'complete', 'refused', 'held', 'armed'}
LSMS = {'capability', 'landlock', 'lockdown', 'yama', 'integrity', 'apparmor', 'bpf'}
FIXTURE_CAPS = {0, 1, 3}  # CHOWN, DAC_OVERRIDE, FOWNER for owned synthetic setup/removal


class Prerequisite(Exception):
    def __init__(self, name): self.name = name


class ProtocolRejected(Exception):
    pass


def read(path, bound):
    with open(path, 'rb') as stream:
        raw = stream.read(bound+1)
    P.require(len(raw) <= bound)
    return raw


def check(name, operation, report):
    try:
        operation()
    except BaseException:
        report['prerequisites'][name] = 'FAIL'
        raise Prerequisite(name) from None
    report['prerequisites'][name] = 'PASS'


def lsm_policy(raw, context, seccomp):
    P.require(type(raw) is bytes and raw.endswith(b'\n') and len(raw) <= 256)
    values = raw[:-1].decode('ascii').split(',')
    P.require(len(values) == len(set(values)) and {'capability', 'landlock'} <= set(values) <= LSMS)
    P.require(context == b'unconfined\n' and seccomp == '0')


def syscall(libc, number, *args):
    # Only the two fixed Landlock calls; no caller selector reaches the CLI.
    P.require(number in (444, 446))
    libc.syscall.restype = ctypes.c_long
    return libc.syscall(ctypes.c_long(number), *args)


def execution(report):
    env = os.environ
    expected = {'GITHUB_REPOSITORY': R.REPOSITORY, 'GITHUB_WORKFLOW': 'CLB-91 B Native Acceptance',
                'GITHUB_EVENT_NAME': 'workflow_dispatch', 'GITHUB_SHA': report['revision'],
                'GITHUB_RUN_ATTEMPT': '1', 'RUNNER_OS': 'Linux', 'RUNNER_ARCH': 'X64'}
    P.require(all(env.get(k) == v for k, v in expected.items()))
    ref = env.get('GITHUB_WORKFLOW_REF', '')
    P.require(ref == R.REPOSITORY+'/'+R.WORKFLOW+'@refs/heads/main')
    P.require(ref.count('@') == 1 and len(ref) <= 256)
    run_id = env.get('GITHUB_RUN_ID', '')
    P.require(run_id.isascii() and run_id.isdecimal() and 0 < int(run_id) and len(run_id) <= 20)
    P.require(not any(k.startswith(('LD_', 'DYLD_', 'PYTHON')) for k in env))


def check_backing(kernel, report):
    """Same read-only admission predicates; only fixed refusal attribution."""
    reason = 'backing_diagnostic'
    try:
        try:
            fd = os.open('/opt', os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        except OSError:
            reason = 'backing_open'
            raise
        try:
            info = os.fstat(fd)
            try:
                P.require(info.st_uid == info.st_gid == 0 and stat.S_IMODE(info.st_mode) == 0o755)
            except P.Refused:
                reason = 'backing_metadata'
                raise
            try:
                filesystem = P.backing(fd, kernel.table())[3]
            except BaseException:
                reason = 'backing_lookup'
                raise
            try:
                P.require(filesystem in ALLOWED_FS)
            except P.Refused:
                reason = 'backing_filesystem'
                raise
        finally:
            try: os.close(fd)
            except BaseException:
                reason = 'backing_diagnostic'
                raise
    except BaseException:
        report['prerequisites']['backing'] = 'FAIL'
        raise Prerequisite(reason) from None
    report['prerequisites']['backing'] = 'PASS'


def preflight(report):
    check('platform', lambda: P.require(sys.platform == 'linux' and platform.machine() == 'x86_64'
        and sys.flags.isolated and sys.flags.no_site and sys.dont_write_bytecode
        and struct.calcsize('P') == 8), report)
    check('execution', lambda: execution(report), report)
    kernel = P.LinuxKernel(os.getpid())
    def namespaces():
        P.require(os.getuid() == os.geteuid() == 0 and os.getgid() == os.getegid() == 0)
        P.require(len(os.listdir('/proc/self/task')) == 1)
        P.require(read('/proc/self/uid_map', 256).split() == [b'0', b'0', b'4294967295'])
        status = kernel.status()
        P.require(status['NSpid'].split() == [str(os.getpid())])
        for name in ('user', 'pid', 'net'):
            P.require(os.stat('/proc/self/ns/'+name).st_ino == os.stat('/proc/1/ns/'+name).st_ino)
        P.require(not any(Path(p).exists() for p in ('/.dockerenv', '/run/.containerenv')))
    check('namespaces', namespaces, report)
    def capabilities():
        P.require(P.CAPS | FIXTURE_CAPS <= {i for i in range(64) if int(kernel.status()['CapEff'], 16) & (1 << i)})
    check('capabilities', capabilities, report)
    def procfs():
        P.require(read('/proc/self/task/'+str(os.getpid())+'/children', 4096).strip() == b'')
        kernel.table()
        P.require(hasattr(os,'pidfd_open') and hasattr(signal,'pidfd_send_signal'))
        fd = os.open('/', os.O_RDONLY | os.O_DIRECTORY)
        try: P.backing(fd, kernel.table())
        finally: os.close(fd)
    check('procfs', procfs, report)
    check_backing(kernel, report)
    libc = ctypes.CDLL(None, use_errno=True)
    def lsm():
        lsm_policy(read('/sys/kernel/security/lsm', 256), read('/proc/self/attr/current', 256),
                   kernel.status()['Seccomp'])
        P.require(syscall(libc, 444, ctypes.c_void_p(), ctypes.c_size_t(0), ctypes.c_uint(1)) >= 1)
    check('lsm', lsm, report)
    def fixture():
        P.require(not os.path.lexists(P.FIXTURE))
        # Fixed synthetic identities must be unused; never borrow host accounts.
        uids = {value[0] for value in PRINCIPALS}
        gids = {group for value in PRINCIPALS for group in value[2]}
        for path, column, forbidden in (('/etc/passwd',2,uids),('/etc/group',2,gids)):
            for line in read(path,1048576).decode('ascii').splitlines():
                fields = line.split(':')
                P.require(len(fields) > column and fields[column].isdecimal())
                P.require(int(fields[column]) not in forbidden)
        processes = [p for p in os.listdir('/proc') if p.isdecimal()]
        P.require(len(processes) <= 4096)
        for process in processes:
            try: raw = read('/proc/'+process+'/status',32768)
            except FileNotFoundError: continue  # exited before observation
            values = dict(line.split(':',1) for line in raw.decode('ascii').splitlines())
            P.require(not uids.intersection(map(int,values['Uid'].split())))
            P.require(not gids.intersection(map(int,values['Gid'].split()+values['Groups'].split())))
    check('fixture', fixture, report)
    def bootstrap():
        # RLIMIT_CORE=0 does not prevent a configured piped core collector.
        P.require(not read('/proc/sys/kernel/core_pattern',4096).startswith(b'|'))
        P.require(os.path.realpath('/proc/self/exe') == os.path.realpath(sys.executable))
        header = read_prefix('/proc/self/exe', 64)
        P.require(header[:6] == b'\x7fELF\x02\x01' and header[18:20] == b'\x3e\x00')
        paths = {os.path.realpath(sys.executable)}
        for module in tuple(sys.modules.values()):
            for key in ('__file__', '__cached__'):
                path = getattr(module, key, None)
                if path and Path(path).is_file(): paths.add(os.path.realpath(path))
        for line in read('/proc/self/maps', 1048576).decode().splitlines():
            fields = line.split(None, 5)
            if len(fields) == 6 and fields[5].startswith('/'):
                paths.add(fields[5])
        P.require(len(paths) <= 1024)
        for path in paths:
            P.require('qemu' not in path.lower() and not path.endswith(' (deleted)'))
            for parent in Path(path).parents:
                info = parent.stat()
                P.require(info.st_uid == 0 and not info.st_mode & 0o022)
            info = os.stat(path, follow_symlinks=False)
            P.require(stat.S_ISREG(info.st_mode) and info.st_uid == 0 and not info.st_mode & 0o022)
    check('bootstrap', bootstrap, report)
    return kernel


def read_prefix(path, bound):
    with open(path, 'rb') as stream: return stream.read(bound)


class Mailbox:
    """Bounded private anonymous shared memory, not public evidence or a socket.

    One writer publishes a complete body then a sequence word, awaiting an ack.
    All raw observations are synthetic, ephemeral, and wiped after confirmed reap.
    """
    def __init__(self):
        self.memory = mmap.mmap(-1, 16384)
        self.nonce, self.sent, self.seen = os.urandom(16).hex(), 0, 0

    def send(self, event, payload):
        P.require(event in EVENTS and self.sent < 4)
        self.sent += 1
        raw = json.dumps(dict(nonce=self.nonce, event=event, payload=payload),
                         separators=(',', ':')).encode('ascii')
        P.require(len(raw) <= 8192)
        self.memory[16:16+len(raw)] = raw
        self.memory[4:8] = struct.pack('!I', len(raw))
        self.memory[0:4] = struct.pack('!I', self.sent)
        end = time.monotonic()+2
        while struct.unpack('!I', self.memory[8:12])[0] != self.sent:
            P.require(time.monotonic() < end)
            time.sleep(.002)

    def receive(self):
        sequence, size = struct.unpack('!II', self.memory[:8])
        if sequence == self.seen: return None
        P.require(sequence == self.seen+1 and sequence <= 4 and 0 < size <= 8192)
        raw = bytes(self.memory[16:16+size])
        P.require(self.memory[:8] == struct.pack('!II', sequence, size))
        message = R.decode(raw, 8192)
        R.need(type(message) is dict and set(message) == {'nonce', 'event', 'payload'})
        R.need(message['nonce'] == self.nonce and message['event'] in EVENTS and type(message['payload']) is dict)
        self.seen = sequence
        return message

    def ack(self): self.memory[8:12] = struct.pack('!I', self.seen)

    def close(self):
        self.memory[:] = b'\0'*len(self.memory)
        self.memory.close()


class Fixture:
    """Exclusive, bounded owned-object ledger; never recursive removal."""
    def __init__(self, principal, case):
        self.principal, self.case, self.entries, self.base = principal, case, [], None

    def remember(self, path, directory=False):
        info = os.lstat(path)
        self.entries.append([path, info.st_dev, info.st_ino, info.st_uid, info.st_gid,
                             info.st_mode, directory])

    def create(self):
        os.mkdir(P.FIXTURE, 0o755)  # exclusive, no exist_ok
        self.remember(P.FIXTURE, True)
        for name, owner, group, mode in (('source', 0, 0, 0o555), ('view', 0, 0, 0o555),
                ('application', self.principal.uid, self.principal.gid, 0o700)):
            path = P.FIXTURE+'/'+name
            os.mkdir(path, 0o755)
            os.chown(path, owner, group)
            os.chmod(path, mode)
            self.remember(path, True)
        for name, data, owner, group, mode in (
            ('source/runtime', b'CLB91-SYNTHETIC-RUNTIME\n', 0, 0, 0o444),
            ('application/application-lock', b'', self.principal.uid, self.principal.gid, 0o600),
            ('application/operation-lock', b'', self.principal.uid, self.principal.gid, 0o600)):
            if self.case == 'partial_init' and name.endswith('operation-lock'): continue
            if self.case == 'owner' and name == 'source/runtime': owner = self.principal.uid
            if self.case == 'mode' and name == 'source/runtime': mode = 0o666
            path = P.FIXTURE+'/'+name
            fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
            try:
                P.require(os.write(fd, data) == len(data))
                os.fchown(fd, owner, group)
                os.fchmod(fd, mode)
            finally: os.close(fd)
            self.remember(path)

    def mutate_lock(self):
        path = P.FIXTURE+'/application/application-lock'
        old = P.FIXTURE+'/application/retained-lock'
        known = next(entry for entry in self.entries if entry[0] == path)
        P.require((os.lstat(path).st_dev, os.lstat(path).st_ino) == tuple(known[1:3]))
        os.rename(path, old)
        known[0] = old
        if self.case == 'symlink':
            os.symlink('retained-lock', path)
        else:
            fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
            try: os.fchown(fd, self.principal.uid, self.principal.gid)
            finally: os.close(fd)
        self.remember(path)
        P.require(os.lstat(path).st_ino != os.lstat(old).st_ino)

    def cleanup(self):
        failed = False
        for path, device, inode, uid, gid, mode, directory in reversed(self.entries):
            try:
                info = os.lstat(path)
                P.require((info.st_dev, info.st_ino, info.st_uid, info.st_gid, info.st_mode) ==
                          (device, inode, uid, gid, mode))
                # Parent directories are trusted root-owned fixture objects;
                # chmod only own directories to permit unlink as a portable user.
                if directory:
                    os.rmdir(path)
                else:
                    os.unlink(path)
            except BaseException:
                failed = True
        self.entries = []
        P.require(not failed and not os.path.lexists(P.FIXTURE))


def tuples(value):
    if type(value) is list: return tuple(tuples(item) for item in value)
    if type(value) is dict: return {key: tuples(item) for key, item in value.items()}
    return value


def expect_refusal(operation, error=None):
    # No expected exception text is used for attribution.
    try: operation()
    except Exception as failure:
        if error is None: P.require(type(failure) is P.Refused)
        else: P.require(isinstance(failure, OSError) and failure.errno == error)
    else: raise P.Refused()


def capset(kernel, effective, permitted=0, inheritable=0):
    class Header(ctypes.Structure):
        _fields_ = [('version', ctypes.c_uint32), ('pid', ctypes.c_int)]
    class Data(ctypes.Structure):
        _fields_ = [('effective', ctypes.c_uint32), ('permitted', ctypes.c_uint32),
                    ('inheritable', ctypes.c_uint32)]
    header, data = Header(0x20080522, 0), (Data*2)()
    for index in range(2):
        data[index].effective = (effective >> (index*32)) & 0xffffffff
        data[index].permitted = (permitted >> (index*32)) & 0xffffffff
        data[index].inheritable = (inheritable >> (index*32)) & 0xffffffff
    kernel.libc.capset.argtypes = [ctypes.POINTER(Header), ctypes.POINTER(Data)]
    kernel.libc.capset.restype = ctypes.c_int
    return kernel.libc.capset(ctypes.byref(header), data)


def landlock_deny_reads(kernel):
    # Per-child irreversible LSM restriction, never global LSM configuration.
    handled = ctypes.c_uint64((1 << 2) | (1 << 3))  # READ_FILE | READ_DIR
    fd = syscall(kernel.libc, 444, ctypes.byref(handled), ctypes.c_size_t(8), ctypes.c_uint(0))
    P.require(fd >= 0)
    try:
        P.require(kernel.prctl(38, 1) == 0)
        P.require(syscall(kernel.libc, 446, ctypes.c_int(fd), ctypes.c_uint(0)) == 0)
    finally: os.close(fd)


def seccomp_deny_unshare(kernel):
    # Fixed x86_64 BPF: arch check, deny unshare(272) with EPERM, allow rest.
    # This is fault injection, NOT a general sandbox or a claim about LSM state.
    class Filter(ctypes.Structure):
        _fields_ = [('code', ctypes.c_ushort), ('jt', ctypes.c_ubyte),
                    ('jf', ctypes.c_ubyte), ('k', ctypes.c_uint32)]
    class Program(ctypes.Structure):
        _fields_ = [('len', ctypes.c_ushort), ('filter', ctypes.POINTER(Filter))]
    instructions = (Filter*7)(Filter(0x20,0,0,4), Filter(0x15,1,0,0xc000003e),
        Filter(0x06,0,0,0x80000000), Filter(0x20,0,0,0), Filter(0x15,0,1,272),
        Filter(0x06,0,0,0x00050000 | errno.EPERM), Filter(0x06,0,0,0x7fff0000))
    program = Program(7, instructions)
    P.require(kernel.prctl(38, 1) == 0)
    pointer = ctypes.cast(ctypes.byref(program), ctypes.c_void_p).value
    P.require(kernel.libc.prctl(22, 2, pointer, 0, 0) == 0)


def simple_control(case, principal, mailbox):
    kernel = P.LinuxKernel(os.getppid())
    leases = None
    try:
        kernel.admit()
        if case in CAP_CASES:
            status = kernel.status()
            mask = int(status['CapEff'], 16) & ~(1 << CAP_CASES[case])
            P.require(capset(kernel, mask, int(status['CapPrm'],16), int(status['CapInh'],16)) == 0)
            P.require(not int(kernel.status()['CapEff'],16) & (1 << CAP_CASES[case]))
            expect_refusal(kernel.admit)
        elif case == 'lsm_denial':
            landlock_deny_reads(kernel)
            expect_refusal(lambda: os.open(P.FIXTURE+'/source/runtime', os.O_RDONLY), errno.EACCES)
            expect_refusal(lambda: kernel.hold(principal), errno.EACCES)
        elif case == 'partial_init':
            expect_refusal(lambda: kernel.hold(principal), errno.ENOENT)
            P.require(kernel.leases == [])
        elif case in ('owner', 'mode'):
            fd = os.open(P.FIXTURE+'/source', os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
            try: expect_refusal(lambda: P.Lease(fd, 'runtime', 0, 0o444, group=0))
            finally: os.close(fd)
        elif case == 'principal':
            other = P.Principal(*PRINCIPALS[1])
            kernel.locks = []
            kernel.drop(other)
            P.credential_matches(other, kernel.credentials())
            expect_refusal(lambda: P.credential_matches(principal, kernel.credentials()))
        else:
            leases = kernel.hold(principal)
            origin = kernel.observe()
            if case in ('symlink', 'path_replacement'):
                mailbox.send('held', {})
                expect_refusal(kernel.locks[0].recheck)
            elif case == 'wrong_namespace':
                expect_refusal(lambda: P.view_matches(origin, kernel.observe()))
            elif case == 'setup_failure':
                seccomp_deny_unshare(kernel)
                expect_refusal(kernel.unshare_private)
                P.require(ctypes.get_errno() == errno.EPERM and kernel.observe()['mount'] == origin['mount'])
            elif case in ('wrong_backing', 'unsafe_propagation'):
                kernel.unshare_private(); kernel.reopen(); kernel.project()
                if case == 'unsafe_propagation':
                    P.require(kernel.libc.mount(None, (P.FIXTURE+'/view').encode(), None, 1 << 20, None) == 0)
                    P.require(any(row[2] == P.FIXTURE+'/view' and row[5] for row in kernel.table().values()))
                    expect_refusal(kernel.require_private)
                else:
                    fd = kernel.current_directory('view')
                    try: before = P.backing(fd, kernel.table())
                    finally: os.close(fd)
                    P.require(kernel.libc.mount(b'tmpfs', (P.FIXTURE+'/view').encode(), b'tmpfs', 7,
                                                b'size=4096,mode=0555,uid=0,gid=0') == 0)
                    fd = kernel.current_directory('view')
                    try: P.require(P.backing(fd, kernel.table()) != before)
                    finally: os.close(fd)
                    expect_refusal(kernel.observe)
            else: raise P.Refused()
    finally:
        failed = False
        try: kernel.close_directories()
        except BaseException: failed = True
        try:
            if leases is not None: P.close_all(leases)
        except BaseException: failed = True
        P.require(not failed)
    mailbox.send('refused', {})


def worker_checks(kernel, principal):
    P.credential_matches(principal, kernel.credentials())
    status = kernel.status()
    P.require(tuple(map(int,status['Uid'].split())) == (principal.uid,)*4)
    P.require(tuple(map(int,status['Gid'].split())) == (principal.gid,)*4)
    for fd in (0, 1, 2):
        info = os.fstat(fd)
        P.require(stat.S_ISCHR(info.st_mode) and info.st_uid == 0 and os.major(info.st_rdev) == 1
                  and os.minor(info.st_rdev) == 3)
    P.require(os.read(0, 1) == b'')
    death = ctypes.c_int()
    P.require(kernel.libc.prctl(2, ctypes.addressof(death), 0, 0, 0) == 0 and death.value == signal.SIGKILL)
    expect_refusal(lambda: os.setresuid(0,0,0), errno.EPERM)
    expect_refusal(lambda: os.setgroups([0]), errno.EPERM)
    P.require(capset(kernel, 1 << 21, 1 << 21) == -1 and ctypes.get_errno() == errno.EPERM)
    P.require(kernel.libc.mount(None,(P.FIXTURE+'/view').encode(),None,P.READONLY_BIND,None) == -1
              and ctypes.get_errno() == errno.EPERM)
    P.credential_matches(principal, kernel.credentials())


def measured_child(case, principal, mailbox):
    class Measured(P.LinuxKernel):
        def project(self):
            source = self.current_directory('source')
            try: source_backing = P.backing(source, self.table())
            finally: os.close(source)
            super().project()
            target = self.current_directory('view')
            try:
                actual = P.backing(target, self.table())
                relative = os.path.relpath(P.FIXTURE+'/source',source_backing[2])
                P.require(not relative.startswith('..'))
                expected = (source_backing[0],os.path.normpath(source_backing[1]+'/'+relative),
                            P.FIXTURE+'/view',source_backing[3],source_backing[4])
                P.require(actual == expected)
                leaf = os.open('runtime',os.O_RDONLY | os.O_NOFOLLOW,dir_fd=target)
                try: P.require(os.fstat(leaf).st_uid == os.fstat(leaf).st_gid == 0)
                finally: os.close(leaf)
                def write_open():
                    descriptor = os.open('runtime',os.O_WRONLY | os.O_NOFOLLOW,dir_fd=target)
                    os.close(descriptor)
                expect_refusal(write_open, errno.EROFS)
            finally: os.close(target)
            mailbox.send('projected', dict(origin=self.origin,view=self.observe()))

        def observe(self):
            value = super().observe()
            if not hasattr(self, 'origin'): self.origin = value
            return value

        def worker(self):
            super().worker()
            worker_checks(self, principal)
            mailbox.send('worker', dict(view=self.observe(),credentials=self.credentials(),
                         fds=[self.app.fd]+[lock.fd for lock in self.locks]))
            if case in ('worker_failure','timeout','cancellation','cleanup_failure'):
                mailbox.send('armed', {})
                if case == 'worker_failure': raise RuntimeError()
                if case in ('timeout','cancellation'): time.sleep(10)

        def close_directories(self):
            if case == 'cleanup_failure' and getattr(self,'projected',False):
                # Intentional real EBADF, with no intervening allocation/reuse.
                os.close(self.directories[-1])
            super().close_directories()
    P.child_setup(Measured(os.getppid()),principal)
    mailbox.send('complete', {})


def peer(principal, worker_pid, fds, expected, anchor):
    kernel = P.LinuxKernel(os.getppid())
    kernel.admit()
    # Keep one genuinely inherited read-only anchor, not a fabricated identity.
    class Held:
        fd = anchor
    kernel.leases, kernel.directories, kernel.locks = [Held()], [], []
    kernel.drop(principal)
    P.credential_matches(principal,kernel.credentials())
    P.require(P.identity(os.fstat(anchor)) == expected['app'])
    for fd, identity in zip(fds, (expected['app'],)+expected['locks']):
        fresh = os.open('/proc/'+str(worker_pid)+'/fd/'+str(fd),os.O_RDONLY)
        try:
            P.require(P.identity(os.fstat(fresh)) == identity)
            if fd != fds[0]:
                try: P.fcntl.flock(fresh,P.fcntl.LOCK_EX | P.fcntl.LOCK_NB)
                except BlockingIOError: pass
                else: raise P.Refused()
        finally: os.close(fresh)


class Coordinator:
    def __init__(self, fixture, mailbox, baseline, kernel, cancelled):
        self.fixture,self.mailbox,self.baseline,self.kernel,self.cancelled = fixture,mailbox,baseline,kernel,cancelled
        self.case,self.pid,self.outcome,self.events = fixture.case,None,None,[]
        self.protocol_failed = False
        self.peer_state = None
        self.anchor = os.open(P.FIXTURE+'/application',os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        self.reference = dict(app=P.identity(os.fstat(self.anchor)),locks=tuple(
            P.identity(os.stat(P.FIXTURE+'/application/'+name,follow_symlinks=False))
            for name in ('application-lock','operation-lock') if os.path.exists(P.FIXTURE+'/application/'+name)))

    def spawned(self,pid):
        P.require(self.pid is None and type(pid) is int and pid > 1)
        self.pid = pid

    def finished(self,status,stopped,clean):
        P.require(self.outcome is None)
        self.outcome = status,stopped,clean

    def parent(self): P.require((self.kernel.table(),P.identity(os.stat('/'))) == self.baseline)

    def tick(self):
        message = self.mailbox.receive()
        if message is not None:
            self.parent()
            try:
                event,payload = message['event'],tuples(message['payload'])
                if event == 'projected':
                    R.need(not self.events and set(payload) == {'origin','view'})
                    P.view_matches(payload['origin'],payload['view'],projected=True)
                    for key in ('app','locks'): P.require(payload['origin'][key] == self.reference[key])
                    for key in ('user','pid','net'):
                        info = os.stat('/proc/self/ns/'+key)
                        P.require(payload['origin'][key] == (info.st_dev,info.st_ino))
                    P.require(payload['origin']['root'] == P.identity(os.stat('/')))
                    self.view = payload['view']
                    P.require(os.listdir(P.FIXTURE+'/view') == [])
                elif event == 'worker':
                    R.need(self.events == ['projected'] and set(payload) == {'view','credentials','fds'})
                    P.require(payload['view'] == self.view)
                    P.credential_matches(self.fixture.principal,payload['credentials'])
                    R.need(type(payload['fds']) is tuple and len(payload['fds']) == 3
                           and len(set(payload['fds'])) == 3
                           and all(type(fd) is int and 3 <= fd <= 1024 for fd in payload['fds']))
                    self.peer_state = Lifecycle()
                    P.supervise(lambda:peer(self.fixture.principal,self.pid,payload['fds'],self.view,self.anchor),
                                timeout=1,cancelled=self.cancelled,observer=self.peer_state)
                elif event == 'held':
                    R.need(not self.events and not payload and self.case in ('symlink','path_replacement'))
                    self.fixture.mutate_lock()
                elif event == 'refused':
                    R.need(not payload and self.events in ([],['held']))
                elif event == 'armed': R.need(self.events == ['projected','worker'] and not payload)
                elif event == 'complete': R.need(self.events == ['projected','worker'] and not payload)
                else: raise R.Invalid()
                self.events.append(event)
                self.mailbox.ack()
            except (R.Invalid,P.Refused):
                self.protocol_failed = True
                raise ProtocolRejected() from None
        return self.cancelled() or (self.case == 'cancellation' and self.events[-1:] == ['armed'])

    def confirmed(self):
        return (self.outcome is not None and self.outcome[0] is not None and self.outcome[2] is True
                and (self.peer_state is None or self.peer_state.confirmed()))

    def accepted(self, successful):
        P.require(self.confirmed())
        status,stopped,_ = self.outcome
        if self.case.startswith('positive_'):
            return successful and status == 0 and not stopped and self.events == ['projected','worker','complete']
        if self.case in ('worker_failure','cleanup_failure'):
            return not successful and status != 0 and not stopped and self.events == ['projected','worker','armed']
        if self.case in ('timeout','cancellation'):
            return not successful and os.WIFSIGNALED(status) and stopped and self.events == ['projected','worker','armed']
        if self.case == 'incomplete_observation': return not successful and self.protocol_failed
        expected = ['held','refused'] if self.case in ('symlink','path_replacement') else ['refused']
        return successful and status == 0 and not stopped and self.events == expected


class Lifecycle:
    def __init__(self): self.pid,self.outcome = None,None
    def spawned(self,pid):
        P.require(self.pid is None)
        self.pid = pid
    def finished(self,status,stopped,clean):
        P.require(self.outcome is None)
        self.outcome = status,stopped,clean
    def confirmed(self):
        return self.outcome is not None and self.outcome[0] is not None and self.outcome[2] is True


def run_case(case,principal,kernel,cancelled):
    if case == 'parent_death': return parent_death_control(principal,kernel,cancelled)
    fixture,mailbox,coordinator = Fixture(principal,case),Mailbox(),None
    confirmed = True
    try:
        fixture.create()
        baseline = kernel.table(),P.identity(os.stat('/'))
        coordinator = Coordinator(fixture,mailbox,baseline,kernel,cancelled)
        def child():
            if case == 'incomplete_observation': mailbox.send('projected',{})
            elif case.startswith('positive_') or case in ('worker_failure','timeout','cancellation','cleanup_failure'):
                measured_child(case,principal,mailbox)
            else: simple_control(case,principal,mailbox)
        successful = False
        try:
            P.supervise(child,timeout=.2 if case == 'timeout' else 8,cancelled=coordinator.tick,observer=coordinator)
            successful = True
        except (P.Refused,ProtocolRejected): pass
        finally:
            confirmed = coordinator.confirmed()
            coordinator.parent()
        if confirmed and case.startswith('positive_'):
            # Reacquisition AFTER both actual processes are reaped proves unlock
            # and guards against an unaccounted lock-holding descriptor/child.
            for name,expected in zip(('application-lock','operation-lock'),coordinator.reference['locks']):
                fd = os.open(P.FIXTURE+'/application/'+name,os.O_RDWR | os.O_NOFOLLOW)
                try:
                    P.require(P.identity(os.fstat(fd)) == expected)
                    P.fcntl.flock(fd,P.fcntl.LOCK_EX | P.fcntl.LOCK_NB)
                    P.fcntl.flock(fd,P.fcntl.LOCK_UN)
                finally: os.close(fd)
        return coordinator.accepted(successful)
    finally:
        if coordinator is not None: os.close(coordinator.anchor)
        mailbox.close()
        # Never remove an in-use fixture when a child could still be alive.
        P.require(confirmed)
        fixture.cleanup()


def parent_death_control(principal,kernel,cancelled):
    """Actual PDEATHSIG after UID/GID drop, adopted/reaped by a test subreaper.

    No new PID namespace. pidfd prevents signaling a recycled orphan PID.
    Process-local subreaper state is restored; uncertain cleanup quarantines VM.
    """
    libc = ctypes.CDLL(None,use_errno=True)
    libc.prctl.argtypes = [ctypes.c_int]+[ctypes.c_ulong]*4
    libc.prctl.restype = ctypes.c_int
    previous = ctypes.c_int()
    P.require(libc.prctl(37,ctypes.addressof(previous),0,0,0) == 0 and previous.value == 0)
    P.require(libc.prctl(36,1,0,0,0) == 0)
    fixture,mailbox,owner = Fixture(principal,'parent_death'),Mailbox(),Lifecycle()
    pidfd,worker_pid,worker_status,clean = None,None,None,False
    events = []
    try:
        fixture.create()
        baseline = kernel.table(),P.identity(os.stat('/'))
        def supervisor():
            class Worker(Lifecycle):
                def spawned(self,pid):
                    super().spawned(pid)
                    mailbox.memory[12:16] = struct.pack('!I',pid)
            P.supervise(lambda:measured_child('positive_a',principal,mailbox),
                        timeout=8,cancelled=lambda:False,observer=Worker())
        def tick():
            nonlocal pidfd,worker_pid
            # Acquire a stable process handle before a cancellation can kill
            # its supervisor. Never signal a PID recovered after that death.
            spawned = struct.unpack('!I',mailbox.memory[12:16])[0]
            if pidfd is None and spawned:
                P.require(spawned > 1)
                pidfd = os.pidfd_open(spawned,0)
                worker_pid = spawned
                status = read('/proc/'+str(worker_pid)+'/status',32768)
                values = dict(line.split(':',1) for line in status.decode().splitlines())
                P.require(int(values['PPid']) == owner.pid)
            message = mailbox.receive()
            if message is not None:
                P.require((kernel.table(),P.identity(os.stat('/'))) == baseline)
                event,payload = message['event'],tuples(message['payload'])
                if event == 'projected':
                    P.require(not events and set(payload) == {'origin','view'})
                    P.view_matches(payload['origin'],payload['view'],projected=True)
                    mailbox.ack()
                elif event == 'worker':
                    P.require(events == ['projected'] and set(payload) == {'view','credentials','fds'})
                    P.credential_matches(principal,payload['credentials'])
                    P.require(worker_pid is not None and pidfd is not None)
                    status = read('/proc/'+str(worker_pid)+'/status',32768)
                    lines = dict(line.split(':',1) for line in status.decode().splitlines())
                    P.require(int(lines['PPid']) == owner.pid)
                    P.require(tuple(map(int,lines['Uid'].split())) == (principal.uid,)*4)
                    # Keep the worker blocked at its acknowledged credential
                    # handoff while the exact live supervisor is terminated.
                    os.kill(owner.pid,signal.SIGKILL)
                else: raise P.Refused()
                events.append(event)
            return cancelled()
        try: P.supervise(supervisor,timeout=8,cancelled=tick,observer=owner)
        except P.Refused: pass
        P.require(owner.confirmed() and os.WIFSIGNALED(owner.outcome[0])
                  and os.WTERMSIG(owner.outcome[0]) == signal.SIGKILL)
        P.require(events == ['projected','worker'] and worker_pid is not None and pidfd is not None)
        deadline = time.monotonic()+1
        while time.monotonic() < deadline:
            try:
                found,status = os.waitpid(worker_pid,os.WNOHANG)
                if found == worker_pid:
                    worker_status = status
                    break
            except ChildProcessError: break
            time.sleep(.005)
        clean = worker_status is not None
        P.require(clean and os.WIFSIGNALED(worker_status) and os.WTERMSIG(worker_status) == signal.SIGKILL)
        P.require((kernel.table(),P.identity(os.stat('/'))) == baseline)
        return True
    finally:
        if pidfd is not None and not clean:
            try: signal.pidfd_send_signal(pidfd,signal.SIGKILL)
            except ProcessLookupError: pass
            deadline = time.monotonic()+1
            while time.monotonic() < deadline:
                try:
                    found,_ = os.waitpid(worker_pid,os.WNOHANG)
                    if found == worker_pid:
                        clean = True
                        break
                except ChildProcessError:
                    # Exact pidfd readiness proves process exit but not a
                    # successful adoption/acceptance; return path remains FAIL.
                    clean = bool(select.select([pidfd],[],[],0)[0])
                    break
                time.sleep(.005)
        if pidfd is not None: os.close(pidfd)
        mailbox.close()
        restored = libc.prctl(36,0,0,0,0) == 0
        P.require(restored and (owner.pid is None or owner.confirmed()) and clean)
        fixture.cleanup()


def run(report):
    ledger = R.Ledger(report)
    interrupted = []
    def stop(_signal,_frame):
        interrupted.append(True)
        raise P.Refused()
    previous = {}
    try:
        for name in (signal.SIGALRM,signal.SIGINT,signal.SIGTERM,signal.SIGHUP):
            previous[name] = signal.getsignal(name)
            signal.signal(name,stop)
        signal.alarm(120)
        kernel = preflight(report)  # read-only prerequisites precede ANY setup
        report.update(execution='NATIVE',run_id=os.environ['GITHUB_RUN_ID'],attempt='1',cleanup='UNCONFIRMED')
        resource.setrlimit(resource.RLIMIT_CORE,(0,0))
        for case in R.CONTROLS:
            principal = P.Principal(*PRINCIPALS[1 if case == 'positive_b' else 0])
            report['cleanup'] = 'UNCONFIRMED'
            passed = run_case(case,principal,kernel,lambda:bool(interrupted))
            report['cleanup'] = 'PASS'
            ledger.record(case,passed)
            if not passed: raise P.Refused()
        P.require(read('/proc/self/task/'+str(os.getpid())+'/children',4096).strip() == b'')
        report['cleanup'] = 'PASS'
        ledger.complete()
    except Prerequisite as failure:
        report.update(verdict='FAIL',reason='interrupted' if interrupted else failure.name)
    except BaseException:
        report.update(verdict='FAIL',reason='interrupted' if interrupted else 'control')
        if report['execution'] != 'NATIVE':
            report['prerequisites']['execution'] = 'FAIL'
        elif ledger.position < len(R.CONTROLS) and not ledger.failed:
            ledger.record(R.CONTROLS[ledger.position],False)
        else:
            report.update(cleanup='UNCONFIRMED',reason='cleanup')
    finally:
        signal.alarm(0)
        for name,handler in previous.items(): signal.signal(name,handler)
