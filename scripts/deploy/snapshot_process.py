"""Bounded owned process groups. Never reap the leader before the last signal.

POSIX waitid(WNOWAIT) pins the leader PID/PGID while descendants are terminated.
Only trusted child tools that stay in their new session/group are supported.
No raw child output or exception text is part of this module's error protocol.
"""
import os
import selectors
import signal
import subprocess
import time


class ProcessFailure(Exception):
    pass


class CleanupFailure(ProcessFailure):
    pass


class OwnedGroup:
    def __init__(self, args, *, env=None, fds=()):
        if not all(hasattr(os, k) for k in ('waitid', 'WNOWAIT', 'P_PID')):
            raise ProcessFailure('unsupported')
        if signal.getsignal(signal.SIGCHLD) != signal.SIG_DFL:
            raise ProcessFailure('child_ownership')
        self._process = subprocess.Popen(args, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, env=env, pass_fds=fds, start_new_session=True, bufsize=0)
        self.pid = self._process.pid  # start_new_session establishes SID == PGID == PID
        self.cleaned = False
        self.failed_cleanup = False
        self._signals_done = False
        self._verify_absence = False
        for stream in (self._process.stdin, self._process.stdout, self._process.stderr):
            os.set_blocking(stream.fileno(), False)

    def exited(self):
        try:
            return os.waitid(os.P_PID, self.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT) is not None
        except ChildProcessError:
            # An external reaper invalidates ownership. Never signal a reused PGID.
            raise CleanupFailure('ownership_lost') from None

    def _signal(self, sig):
        self.exited()  # proves this PID is still our unreaped child, alive or zombie
        try:
            os.killpg(self.pid, sig)
        except ProcessLookupError:
            pass
        except PermissionError:
            # Darwin can report EPERM for a group containing only its zombie
            # leader. Do not assume this is harmless: verify absence after reap.
            if not self.exited():
                raise
            return False
        return True

    def cleanup(self):
        if self.cleaned:
            if self.failed_cleanup:
                raise CleanupFailure('cleanup')
            return
        blocked = {signal.SIGINT, signal.SIGTERM, signal.SIGHUP, signal.SIGALRM}
        # A signal handler can still interrupt us via another unblocked thread.
        # Completion is committed only after termination, reap, drain and close.
        self.failed_cleanup = True
        previous = signal.pthread_sigmask(signal.SIG_BLOCK, blocked)
        try:
            try:
                if not self._signals_done:
                    term_ok = self._signal(signal.SIGTERM)
                    time.sleep(.08)
                    # Always signal the whole group, even when the leader already exited.
                    kill_ok = self._signal(signal.SIGKILL)
                    self._verify_absence = not (term_ok and kill_ok)
                    # Persist before wait: a resumed cleanup must never signal after reap.
                    self._signals_done = True
                # Do not reap the leader until all intended group signals are done.
                self._process.wait(timeout=.7)
                with selectors.DefaultSelector() as reader:
                    for stream in (self._process.stdout, self._process.stderr):
                        if not stream.closed:
                            reader.register(stream, selectors.EVENT_READ)
                    deadline = time.monotonic() + .3
                    while reader.get_map() and time.monotonic() < deadline:
                        for event, _ in reader.select(.02):
                            if not os.read(event.fd, 4096):
                                reader.unregister(event.fileobj)
                    if reader.get_map():
                        raise CleanupFailure('cleanup')
                if self._verify_absence:
                    # No destructive signal after reap. Signal 0 is only a
                    # non-mutating existence check; PID reuse can only refuse.
                    try:
                        os.killpg(self.pid, 0)
                    except ProcessLookupError:
                        pass
                    else:
                        raise CleanupFailure('cleanup')
                # Keep pipes available on interruption so a bounded retry can drain.
                for stream in (self._process.stdin, self._process.stdout, self._process.stderr):
                    stream.close()
                self.failed_cleanup = False
                self.cleaned = True
            except (OSError, subprocess.TimeoutExpired):
                raise CleanupFailure('cleanup') from None
        finally:
            signal.pthread_sigmask(signal.SIG_SETMASK, previous)

    def capture(self, data, *, timeout, limit):
        process = self._process
        deadline = time.monotonic() + timeout
        pending = memoryview(data)
        output = bytearray()
        total = 0
        try:
            with selectors.DefaultSelector() as reader:
                for stream in (process.stdout, process.stderr):
                    reader.register(stream, selectors.EVENT_READ)
                if pending:
                    reader.register(process.stdin, selectors.EVENT_WRITE)
                else:
                    process.stdin.close()
                while reader.get_map():
                    if time.monotonic() >= deadline:
                        raise ProcessFailure('timeout')
                    for event, _ in reader.select(.02):
                        if event.fileobj is process.stdin:
                            size = os.write(event.fd, pending[:4096])
                            pending = pending[size:]
                            if not pending:
                                reader.unregister(process.stdin)
                                process.stdin.close()
                            continue
                        chunk = os.read(event.fd, 4096)
                        if not chunk:
                            reader.unregister(event.fileobj)
                            continue
                        total += len(chunk)
                        if total > limit:
                            raise ProcessFailure('bounds')
                        if event.fileobj is process.stdout:
                            output.extend(chunk)
                while not self.exited():
                    if time.monotonic() >= deadline:
                        raise ProcessFailure('timeout')
                    time.sleep(.01)
        finally:
            self.cleanup()
        if process.returncode != 0:
            raise ProcessFailure('exit')
        return bytes(output)


def capture(args, data=b'', *, env=None, fds=(), timeout=90, limit=49152):
    pending = []
    watched = (signal.SIGINT, signal.SIGTERM, signal.SIGHUP, signal.SIGALRM)
    handlers = {}
    group = None
    try:
        for sig in watched:
            handlers[sig] = signal.signal(sig, lambda number, _frame: pending.append(number))
        group = OwnedGroup(args, env=env, fds=fds)
        for sig, handler in handlers.items():
            signal.signal(sig, handler)
        if pending:
            # Deliver the original cancellation only after ownership is established.
            handler = handlers[pending[0]]
            if callable(handler):
                handler(pending[0], None)
            raise ProcessFailure('interrupted')
        return group.capture(data, timeout=timeout, limit=limit)
    finally:
        try:
            if group is not None:
                group.cleanup()
        finally:
            for sig, handler in handlers.items():
                signal.signal(sig, handler)
