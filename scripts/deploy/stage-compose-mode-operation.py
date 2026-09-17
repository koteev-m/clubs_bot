"""CLB-91 fixed remote operation. No CLI, content reads or lifecycle commands."""
import ctypes
import fcntl
import os
import platform
import pwd
import stat
import sys

COMPOSE_PATH = '/opt/clubs-bot-stage'
LAYOUT = (
    ('opt', '/', 'opt', True),
    ('compose', 'opt', 'clubs-bot-stage', True),
    ('parent', 'compose', '.clubs-bot-release-state', True),
    ('stage', 'parent', 'stage', True),
    ('results', 'stage', 'clubs-bot-schema-stage.results', True),
    ('application_lock', 'parent', 'application.lock', False),
    ('operation_lock', 'results', 'operation.lock', False),
    ('file', 'compose', 'docker-compose.yml', False),
)


class Blocked(Exception):
    pass


def require(condition, reason):
    if not condition:
        raise Blocked(reason)


def identity(s):
    # atime is not authority; chmod may change mode and ctime only.
    return (s.st_dev, s.st_ino, s.st_uid, s.st_gid, s.st_mode,
            s.st_nlink, s.st_size, s.st_mtime_ns, s.st_ctime_ns)


def check_filesystem(fd):
    # Restrict ACL/flock assumptions to Linux local ext[234], XFS or Btrfs.
    # Do not silently accept NFS/SMB/FUSE/rich-ACL filesystem semantics.
    require(sys.platform == 'linux' and platform.machine() in ('x86_64', 'aarch64'), 'filesystem')
    libc = ctypes.CDLL(None, use_errno=True)
    call = libc.fstatfs
    call.argtypes, call.restype = [ctypes.c_int, ctypes.c_void_p], ctypes.c_int
    buffer = ctypes.create_string_buffer(256)  # Linux statfs, supported ABIs.
    require(call(fd, buffer) == 0, 'filesystem')
    magic = ctypes.c_long.from_buffer(buffer).value & 0xffffffff
    require(magic in (0xef53, 0x58465342, 0x9123683e), 'filesystem')


def check_acl(fd):
    # Names only, never xattr values. Failure to enumerate is a rejection.
    try:
        names = os.listxattr(fd)
        require(not any('acl' in name.lower() or name == 'security.capability'
                        for name in names), 'acl')
    except (AttributeError, OSError):
        raise Blocked('acl') from None


def repair(expected_principal, cancelled):
    """Exactly one possible write to the retained target; no retry/rollback.

    The authenticated channel, fixed canonical path and trusted chain establish
    the target. Snapshots here detect in-invocation drift; they are NOT a stored
    root-binding approval. Advisory locks serialize cooperating release clients.
    """
    held, snapshots, edges, owned = {}, {}, [], []
    attempted = False
    result = ('blocked', 'local')
    phase = 'layout'
    try:
        uid = os.geteuid()
        require(uid != 0 and expected_principal not in ('root', 'hookah-staging')
                and pwd.getpwuid(uid).pw_name == expected_principal, 'principal')
        require(not cancelled(), 'interrupted')
        root = os.open('/', os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_NONBLOCK)
        owned.append(root)
        held['/'] = root
        s = os.fstat(root)
        require(stat.S_ISDIR(s.st_mode) and s.st_uid == 0
                and not stat.S_IMODE(s.st_mode) & 0o022, 'layout')
        snapshots['/'] = identity(s)
        check_acl(root)
        for key, parent, name, directory in LAYOUT:
            require(not cancelled(), 'interrupted')
            flags = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK
            fd = os.open(name, flags | (os.O_DIRECTORY if directory else 0), dir_fd=held[parent])
            owned.append(fd)
            held[key] = fd
            edges.append((parent, name, key))
            s = os.fstat(fd)
            snapshots[key] = identity(s)
            check_acl(fd)
            if directory:
                require(stat.S_ISDIR(s.st_mode) and not stat.S_IMODE(s.st_mode) & 0o022, 'layout')
                require(s.st_uid in ((0, uid) if key == 'opt' else (uid,)), 'identity')
                if key in ('parent', 'stage', 'results'):
                    require(stat.S_IMODE(s.st_mode) == 0o700, 'layout')
                if key == 'compose':
                    check_filesystem(fd)
            else:
                require(stat.S_ISREG(s.st_mode) and s.st_uid == uid and s.st_nlink == 1, 'identity')
                if key != 'file':
                    require(stat.S_IMODE(s.st_mode) == 0o600, 'layout')
                    try:
                        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    except BlockingIOError:
                        raise Blocked('busy') from None
            if key not in ('opt', 'compose'):
                require(s.st_dev == os.fstat(held['compose']).st_dev, 'identity')

        def recheck():
            for key, fd in held.items():
                require(identity(os.fstat(fd)) == snapshots[key], 'identity')
                check_acl(fd)
            for parent, name, key in edges:
                require(identity(os.stat(name, dir_fd=held[parent], follow_symlinks=False))
                        == identity(os.fstat(held[key])), 'identity')

        recheck()
        require(not cancelled(), 'interrupted')
        before = os.fstat(held['file'])
        require(identity(before) == snapshots['file'], 'identity')
        if stat.S_IMODE(before.st_mode) in (0o600, 0o644):
            result = ('already_valid', None)
        else:
            phase = 'write'
            attempted = True  # Established before the sole fallible mutation.
            os.fchmod(held['file'], 0o600)
            phase = 'readback'
            after = os.fstat(held['file'])
            old, new = identity(before), identity(after)
            require(stat.S_IMODE(after.st_mode) == 0o600
                    and old[:4] == new[:4] and old[5:8] == new[5:8]
                    and stat.S_IFMT(before.st_mode) == stat.S_IFMT(after.st_mode), 'identity')
            snapshots['file'] = new
            recheck()
            require(not cancelled(), 'interrupted')
            result = ('changed', None)
    except BaseException as error:
        reason = error.args[0] if isinstance(error, Blocked) else 'io'
        result = ('ambiguous', 'interrupted' if reason == 'interrupted' else phase) if attempted else ('blocked', reason)
    finally:
        # Retain both locks through readback. Attempt every close even on error.
        for fd in reversed(owned):
            try:
                os.close(fd)
            except BaseException:
                result = ('ambiguous', 'cleanup') if attempted else ('blocked', 'io')
    if cancelled():
        result = ('ambiguous' if attempted else 'blocked', 'interrupted')
    return result
