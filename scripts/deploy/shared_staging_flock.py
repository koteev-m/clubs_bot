#!/usr/bin/env python3
"""One bounded Linux SSH session; advisory coordination, not Docker fencing.

No CLI/environment lock-path override. Tests inject isolated paths via the API.
"""
import fcntl
import json
import os
from pathlib import Path
import select
import selectors
import signal
import shlex
import stat
import subprocess
import sys
import time

LOCK_PATH = '/run/lock/shared-staging-docker.lock'
LOCK_FD = 19
LOCK_OWNER_UID = 0
LIMIT = 1024 * 1024
ORDER = ('preflight', 'prepare', 'publish', 'quiesce', 'migrate', 'start', 'cleanup', 'helper-cleanup')


class Refused(Exception):
    pass


def require(condition):
    if not condition:
        raise Refused('untrusted_lock_or_protocol')


def lock_identity(fd, path=LOCK_PATH):
    require(sys.platform == 'linux')
    # Root-owned sticky /run/lock is supported. Operators must never replace the
    # file while participants are active. Deploy users cannot unlink this inode.
    for parent in Path(path).parents:
        info = parent.lstat()
        require(stat.S_ISDIR(info.st_mode) and info.st_uid in (0, LOCK_OWNER_UID))
        require(not info.st_mode & 0o022 or info.st_mode & stat.S_ISVTX)
    info, named = os.fstat(fd), os.lstat(path)
    require(stat.S_ISREG(info.st_mode) and stat.S_ISREG(named.st_mode))
    require(info.st_uid == LOCK_OWNER_UID and info.st_nlink == 1)
    require(stat.S_IMODE(info.st_mode) == 0o660)
    require((info.st_dev, info.st_ino) == (named.st_dev, named.st_ino))
    require(not os.listxattr(fd))  # no hidden ACL granting broader access
    return info.st_dev, info.st_ino


def verify_held(fd=LOCK_FD, path=LOCK_PATH):
    lock_identity(fd, path)
    # Kernel evidence on this open file description, not a PID/existence test
    # and not a second flock acquisition (which could deadlock or mint proof).
    lines = Path(f'/proc/self/fdinfo/{fd}').read_text().splitlines()
    require(any(line.startswith('lock:') and ' FLOCK  ADVISORY  WRITE ' in line
                and line.endswith(' 0 EOF') for line in lines))


class HostLock:
    def __init__(self, path=LOCK_PATH):
        self.path = path
        self.fd = None

    def __enter__(self):
        require(sys.platform == 'linux')
        fd = os.open(self.path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC)
        try:
            lock_identity(fd, self.path)
            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise Refused('BUSY') from None
            verify_held(fd, self.path)
            self.fd = fd
            return self
        except BaseException:
            os.close(fd)
            raise

    def check(self):
        verify_held(self.fd, self.path)

    def __exit__(self, *_):
        # Do not LOCK_UN: inherited copies must retain ownership after parent
        # death until their processes finish. Closing is never a success signal.
        os.close(self.fd)
        self.fd = None


def frame_read(fd, deadline):
    data = bytearray()
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0 or not select.select([fd], [], [], remaining)[0]:
            raise Refused('transport_timeout')
        byte = os.read(fd, 1)
        if not byte:
            raise Refused('transport_lost')
        if byte == b'\n':
            return json.loads(data)
        data.extend(byte)
        require(len(data) <= 3 * LIMIT)


def frame_write(fd, value):
    data = memoryview(json.dumps(value, ensure_ascii=True).encode() + b'\n')
    # Frames are bounded; the reader is waiting. A wedged SSH peer is bounded by
    # the session alarm, including writes to a full pipe.
    while data:
        data = data[os.write(fd, data):]


def run_owned(command, input_bytes, lock, deadline, disconnect_fd=None):
    """Drain bounded output, watch SSH EOF, kill/reap the owned process group."""
    lock.check()
    process = None
    interrupted = False
    handlers = {}

    def interrupt(_signum, _frame):
        nonlocal interrupted
        interrupted = True

    try:
        for sig in (signal.SIGHUP, signal.SIGINT, signal.SIGTERM, signal.SIGALRM):
            handlers[sig] = signal.signal(sig, interrupt)
        # Reserve a descriptor separate from FSM's 3, 6, 7, 8 and 9.
        if lock.fd != LOCK_FD:
            os.dup2(lock.fd, LOCK_FD, inheritable=True)
        with selectors.DefaultSelector() as reader:
            try:
                process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                           stderr=subprocess.PIPE, start_new_session=True,
                                           pass_fds=(LOCK_FD,), bufsize=0)
                process.stdin.write(input_bytes)
                process.stdin.close()
                output = [bytearray(), bytearray()]
                for index, stream in enumerate((process.stdout, process.stderr)):
                    os.set_blocking(stream.fileno(), False)
                    reader.register(stream, selectors.EVENT_READ, index)
                if disconnect_fd is not None:
                    reader.register(disconnect_fd, selectors.EVENT_READ, 'transport')
                while True:
                    if interrupted or time.monotonic() >= deadline:
                        raise Refused('UNKNOWN')
                    lock.check()
                    for key, _ in reader.select(0.05):
                        if key.data == 'transport':
                            # No pipelining: even extra input during an operation
                            # is refused, so EOF cannot schedule another mutation.
                            raise Refused('UNKNOWN')
                        chunk = os.read(key.fd, 65536)
                        if not chunk:
                            reader.unregister(key.fileobj)
                        else:
                            output[key.data].extend(chunk)
                            require(len(output[key.data]) <= LIMIT)
                    exited = os.waitid(os.P_PID, process.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
                    streams = [k for k in reader.get_map().values() if k.data != 'transport']
                    if exited is not None and not streams:
                        break
            finally:
                if process is not None:
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                    process.wait(timeout=5)
                    process.stdout.close()
                    process.stderr.close()
                if lock.fd != LOCK_FD:
                    os.close(LOCK_FD)
        if interrupted:
            raise Refused('UNKNOWN')
        lock.check()
        return process.returncode, *(bytes(x).decode('utf-8', 'replace') for x in output)
    finally:
        for sig, handler in handlers.items():
            signal.signal(sig, handler)


def serve(helper, owner, environment, path=LOCK_PATH):
    require(environment in ('stage', 'prod', 'test'))
    deadline = time.monotonic() + 900

    def stop(_sig, _frame):
        raise Refused('UNKNOWN')

    for sig in (signal.SIGHUP, signal.SIGINT, signal.SIGTERM, signal.SIGALRM):
        signal.signal(sig, stop)
    signal.alarm(900)
    with HostLock(path) as lock:
        frame_write(1, {'ready': True})
        for expected in ORDER:
            request = frame_read(0, min(deadline, time.monotonic() + 30))
            require(isinstance(request, dict) and set(request) == {'args', 'input'})
            args, data = request['args'], request['input']
            require(isinstance(args, list) and 3 <= len(args) <= 8
                    and all(isinstance(x, str) and len(x) <= 4096 and '\0' not in x for x in args))
            require(args[:3] == [expected, owner, environment])
            require(isinstance(data, str) and len(data) <= 4096)
            require(expected == 'preflight' or data == '')
            result = run_owned(['bash', helper, *args], data.encode(), lock,
                               min(deadline, time.monotonic() + 300), disconnect_fd=0)
            frame_write(1, result)
            if result[0] != 0:
                return 1
    return 0


def request(args):
    data = sys.stdin.read(4097) if args[0] == 'preflight' else ''
    require(len(data) <= 4096)
    frame_write(21, {'args': args, 'input': data})
    result = frame_read(20, time.monotonic() + 310)
    require(isinstance(result, list) and len(result) == 3 and type(result[0]) is int
            and 0 <= result[0] <= 255 and all(isinstance(x, str) for x in result[1:]))
    sys.stdout.write(result[1])
    sys.stderr.write(result[2])
    return result[0]


def main():
    try:
        action, *args = sys.argv[1:]
        if action == 'command' and len(args) == 3:
            print(shlex.join(['python3', '-I', '-S', '-B', '-c', Path(__file__).read_text(), 'serve', *args]))
            return 0
        if action == 'serve' and len(args) == 3:
            return serve(*args)
        if action == 'request':
            return request(args)
        if action == 'ready' and not args:
            response = frame_read(20, time.monotonic() + 30)
            if response == {'ready': False, 'reason': 'BUSY'}:
                print('shared-staging-flock: BUSY; no effects', file=sys.stderr)
                return 75
            require(response == {'ready': True})
            return 0
        if action == 'verify' and not args:
            verify_held()
            return 0
        return 2
    except (Refused, OSError, ValueError, TypeError, subprocess.TimeoutExpired) as exc:
        if sys.argv[1:2] == ['serve'] and isinstance(exc, Refused) and str(exc) == 'BUSY':
            frame_write(1, {'ready': False, 'reason': 'BUSY'})
            return 75
        # Transport/raw child diagnostics and credentials are never published.
        print('shared-staging-flock: REFUSED; reconcile read-only; no replay', file=sys.stderr)
        return 255


if __name__ == '__main__':
    sys.exit(main())
