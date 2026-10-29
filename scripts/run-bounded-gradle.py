#!/usr/bin/env python3
"""One foreground, ownership-scoped Gradle invocation on macOS/Linux. No shared daemon stop.

Example: python3 scripts/run-bounded-gradle.py --timeout 300 -- :app-bot:test --tests '*AdminMusicRoutesTest'
The supervisor must remain alive: SIGKILL of the controller/OS failure is outside its guarantee.
"""
import argparse
import ctypes
import dataclasses
import errno
import json
import os
import re
from pathlib import Path
import selectors
import signal
import subprocess
import sys
import tempfile
import time
import uuid


@dataclasses.dataclass(frozen=True)
class Process:
    pid: int
    parent: int
    group: int
    uid: int
    started: str
    rss: int
    cpu: float
    args: str = dataclasses.field(repr=False)

    @property
    def identity(self):
        # exec() may change argv legitimately (gradlew shell -> launcher JVM).
        # The birth identity AND exact invocation nonce are rechecked before signals.
        return self.pid, self.started, self.uid


class DarwinBsdInfo(ctypes.Structure):
    # Apple's public proc_bsdinfo, PROC_PIDTBSDINFO=3 (136 bytes).
    _fields_ = [(name, ctypes.c_uint32) for name in (
        'flags', 'status', 'xstatus', 'pid', 'parent', 'uid', 'gid', 'ruid', 'rgid',
        'svuid', 'svgid', 'reserved')] + [
        ('comm', ctypes.c_char * 16), ('name', ctypes.c_char * 32)] + [
        (name, ctypes.c_uint32) for name in ('files', 'group', 'jobc', 'tdev', 'tpgid')] + [
        ('nice', ctypes.c_int32), ('seconds', ctypes.c_uint64), ('micros', ctypes.c_uint64)]


def darwin_birth(pid):
    library = ctypes.CDLL('/usr/lib/libproc.dylib', use_errno=True)
    call = library.proc_pidinfo
    call.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_uint64, ctypes.c_void_p, ctypes.c_int]
    call.restype = ctypes.c_int
    info = DarwinBsdInfo()
    ctypes.set_errno(0)
    size = call(pid, 3, 0, ctypes.byref(info), ctypes.sizeof(info))
    if size == 0 and ctypes.get_errno() == errno.ESRCH:
        return None
    if size != ctypes.sizeof(info) or info.pid != pid or not info.seconds:
        raise RuntimeError('precise process identity unavailable')
    if info.status == 5:  # SZOMB: exited, cannot execute. Direct child still reaped by Popen.
        return None
    return info


def ps_snapshot(timeout):
    result = subprocess.run(
        ['ps', '-ww', '-axo', 'pid=,ppid=,pgid=,uid=,lstart=,rss=,pcpu=,stat=,args='],
        capture_output=True, text=True, timeout=timeout, env={**os.environ, 'LC_ALL': 'C'}, check=True,
    )
    found = {}
    for line in result.stdout.splitlines():
        fields = line.split(None, 12)
        if len(fields) != 13:
            raise RuntimeError('incomplete process snapshot')
        if fields[11].startswith('Z'):
            continue  # Exited zombies cannot execute; our direct child is reaped via Popen.
        pid, parent, group, uid = map(int, fields[:4])
        started = ' '.join(fields[4:9])
        found[pid] = Process(pid, parent, group, uid, started, int(fields[9]), float(fields[10]), fields[12])
    return found


def processes(timeout=3):
    """Bind private argv to a stable kernel birth identity; never print command lines."""
    deadline = time.monotonic() + timeout
    found = ps_snapshot(remaining(deadline, timeout))
    if sys.platform == 'darwin':
        before = {pid: darwin_birth(pid) for pid, p in found.items() if p.uid == os.getuid()}
        # Bind argv between two precise birth reads, not to ps's coarse lstart alone.
        fresh = ps_snapshot(remaining(deadline, timeout))
        for pid in list(found):
            if found[pid].uid != os.getuid():
                continue
            first = before[pid]
            second = darwin_birth(pid) if pid in fresh else None
            if first is None or second is None:
                del found[pid]
                continue
            if (first.pid, first.uid, first.seconds, first.micros) != (
                    second.pid, second.uid, second.seconds, second.micros):
                raise RuntimeError('process identity changed during inspection')
            p = fresh[pid]
            if p.uid != second.uid:
                raise RuntimeError('process metadata changed during inspection')
            # Reparenting/group changes do not change birth identity. Use the later
            # kernel metadata; Ownership still requires a live verified parent/group.
            found[pid] = dataclasses.replace(p, parent=second.parent, group=second.group,
                                               started=f'{second.seconds}:{second.micros}')
        return found
    if sys.platform.startswith('linux'):
        for pid, p in list(found.items()):
            if p.uid != os.getuid():
                continue
            try:
                proc = Path(f'/proc/{pid}')
                first = (proc / 'stat').read_text().rsplit(')', 1)[1].split()
                args = os.fsdecode((proc / 'cmdline').read_bytes().replace(b'\0', b' ')).strip()
                second = (proc / 'stat').read_text().rsplit(')', 1)[1].split()
                if first[19] != second[19]:
                    raise RuntimeError('process identity changed during inspection')
                if second[0] == 'Z':
                    del found[pid]
                    continue
                found[pid] = dataclasses.replace(p, parent=int(second[1]), group=int(second[2]),
                                                  started=second[19], args=args)
            except FileNotFoundError:
                del found[pid]
        return found
    raise RuntimeError('unsupported process identity platform')


def marked(process, marker):
    # Exact argument, never a substring such as another run's nonce.
    return marker in process.args.split()


class Ownership:
    """Keep birth/argv records; a PGID alone is never evidence of ownership.

    Unmarked children need a live, verified parent or a group containing a live,
    verified member in this very snapshot. Once that continuity disappears, the
    group number cannot be trusted again. A detached, unmarked child that was
    never observed with its parent/group cannot safely be discovered later.
    """
    def __init__(self, initial, marker, uid):
        self.initial = initial
        self.initial_groups = {p.group for p in initial.values()}
        self.marker = marker
        self.uid = uid
        self.records = {}
        self.nonce_owned = set()
        self.refused = set()
        self.retired = set()

    def observe(self, snapshot):
        live = {}
        for pid, record in self.records.items():
            current = snapshot.get(pid)
            if current is None:
                self.retired.add(pid)
            elif (pid in self.retired or current.identity != record.identity or
                  (not marked(current, self.marker) and current.args != record.args)):
                self.refused.add(pid)
            elif pid not in self.refused:
                live[pid] = current
                if marked(current, self.marker):
                    self.nonce_owned.add(pid)

        # Fixed point handles any ordering of parent/child rows. Groups are derived
        # afresh from verified live identities, never from historical PGID values.
        for _ in range(len(snapshot) + 1):
            changed = False
            groups = {p.group for p in live.values() if p.group not in self.initial_groups}
            for item in snapshot.values():
                if item.uid != self.uid or item.pid in live or item.pid in self.refused:
                    continue
                nonce = marked(item, self.marker)
                if not (nonce or item.parent in live or item.group in groups):
                    continue
                if item.pid in self.initial or item.pid in self.records:
                    self.refused.add(item.pid)
                    continue
                self.records[item.pid] = item
                live[item.pid] = item
                if nonce:
                    self.nonce_owned.add(item.pid)
                changed = True
            if not changed:
                break
        return live

    def cleaned(self, snapshot, launcher_exit):
        return (not self.refused and launcher_exit is not None and
                not any(pid in snapshot for pid in self.records))


class CpuPressure:
    """Sustained aggregate ps CPU pressure, not an OS-enforced CPU quota.

    ps %cpu is an OS-specific average (including a lifetime average on Linux).
    This is a conservative early stop in addition to RSS, idle and wall deadlines.
    """
    def __init__(self, limit_percent, duration):
        self.limit_percent = limit_percent
        self.duration = duration
        self.since = None
        self.peak = 0.0

    def exceeded(self, live, now):
        total = sum(p.cpu for p in live.values())
        self.peak = max(self.peak, total)
        if total <= self.limit_percent:
            self.since = None
        elif self.since is None:
            self.since = now
        return self.since is not None and now - self.since >= self.duration


def remaining(deadline, maximum):
    value = min(maximum, deadline - time.monotonic())
    if value <= 0:
        raise TimeoutError('controller deadline elapsed')
    return value


def signal_owned(record, marker, sig, snapshot=processes, send=os.kill, require_marker=True):
    current = snapshot().get(record.pid)
    if current is None:
        return True
    def matches(item):
        return item.identity == record.identity and (
            marked(item, marker) if require_marker else item.args == record.args
        )
    if not matches(current):
        return False
    try:
        # On Linux use a kernel-bound pidfd, then revalidate after obtaining it.
        if sys.platform.startswith('linux') and hasattr(os, 'pidfd_open') and send is os.kill:
            fd = os.pidfd_open(record.pid)
            try:
                again = snapshot().get(record.pid)
                if again is None:
                    return True
                if not matches(again):
                    return False
                signal.pidfd_send_signal(fd, sig)
            finally:
                os.close(fd)
        else:
            send(record.pid, sig)
        return True
    except ProcessLookupError:
        return True


class LimitedLog:
    def __init__(self, path, limit):
        self.file = open(path, 'xb')
        self.remaining = limit

    def write(self, data):
        chunk = data[:self.remaining]
        self.file.write(chunk)
        self.file.flush()
        self.remaining -= len(chunk)

    def close(self):
        self.file.close()


def diagnostics(owned, marker, output, deadline):
    """A small bounded snapshot, never a heap dump or histogram. Failure is non-fatal to cleanup."""
    for record in list(owned.values()):
        if time.monotonic() >= deadline:
            break
        try:
            current = processes(timeout=remaining(deadline, 1)).get(record.pid)
            if current is None or current.identity != record.identity or not marked(current, marker):
                continue
            executable = Path(current.args.split()[0])
            jcmd = executable.parent / 'jcmd'
            if executable.name != 'java' or not jcmd.is_file():
                continue
            # Numeric-only memory report: no arbitrary application arguments or credentials.
            wait_seconds = remaining(deadline, 2)
            if wait_seconds < 0.5:
                break
            with tempfile.TemporaryFile() as capture:
                proc = subprocess.Popen([str(jcmd), str(record.pid), 'GC.heap_info'], stdout=capture, stderr=capture)
                try:
                    proc.wait(timeout=wait_seconds - 0.25)
                except subprocess.TimeoutExpired:
                    proc.kill()  # Direct child, still held/reaped by Popen; no PID discovery.
                    try:
                        proc.wait(timeout=remaining(deadline, 0.25))
                    except (TimeoutError, subprocess.TimeoutExpired):
                        return False
                capture.seek(0)
                output.write(capture.read(32768))
        except (OSError, subprocess.SubprocessError, RuntimeError):
            continue
    return True


def supervise(command, marker, directory, timeout, idle_timeout, rss_limit_mb=3072, diagnose=True, env=None,
              cpu_limit_percent=300, cpu_seconds=10, cleanup_timeout=20):
    """command's launched process and JVMs must carry marker as an exact argv token.

    Discover by per-invocation nonce (also after reparenting), exclude initial processes,
    and revalidate full identity before each signal. No signal is sent on uncertain identity.
    """
    begin = time.monotonic()
    deadline = begin + timeout + cleanup_timeout
    # Include the initial inspection in the wall budget; never launch without it.
    initial = processes(timeout=remaining(deadline, 3))
    parent = os.getppid()
    owner_uid = os.getuid()
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    output = LimitedLog(directory / 'output.log', 2 * 1024 * 1024)
    diag = LimitedLog(directory / 'diagnostic.log', 128 * 1024)
    ownership = Ownership(initial, marker, owner_uid)
    owned = ownership.records
    refused = ownership.refused
    pressure = CpuPressure(cpu_limit_percent, cpu_seconds)
    reason = None
    requested = []
    old_handlers = {}
    for sig in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
        old_handlers[sig] = signal.signal(sig, lambda number, frame: requested.append(number))
    proc = None
    selector = selectors.DefaultSelector()
    last_output = begin

    def discover():
        snapshot = processes(timeout=remaining(deadline, 3))
        return snapshot, ownership.observe(snapshot)

    def drain(wait=0.1):
        nonlocal last_output
        for key, _ in selector.select(min(wait, max(0, deadline - time.monotonic()))):
            data = os.read(key.fd, 16384)
            if data:
                output.write(data)  # Continue draining even after log cap.
                last_output = time.monotonic()
            else:
                selector.unregister(key.fileobj)

    cleanup_ok = False
    child_exit = None
    released = False
    diagnostic_cleanup = True
    gate_read, gate_write = os.pipe()
    try:
        # Handshake establishes the OS session/group and root birth identity before
        # Gradle can fork anything (including an immediately reparented child).
        bootstrap = ('import os,sys; fd=int(sys.argv[1]); ok=os.read(fd,1); os.close(fd); '
                     'os.execvpe(sys.argv[3],sys.argv[3:],os.environ) if ok else sys.exit(125)')
        proc = subprocess.Popen([sys.executable, '-c', bootstrap, str(gate_read), marker, *command],
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                start_new_session=True, env=env, pass_fds=(gate_read,))
        os.close(gate_read)
        gate_read = -1
        selector.register(proc.stdout, selectors.EVENT_READ)
        discover()
        if proc.pid not in owned or proc.pid in refused:
            raise RuntimeError('launcher identity not established')
        os.write(gate_write, b'1')
        os.close(gate_write)
        gate_write = -1
        released = True
        while True:
            snapshot, live = discover()
            drain()
            child_exit = proc.poll()
            now = time.monotonic()
            if refused:
                reason = 'identity_unconfirmed'
            elif requested or os.getppid() != parent:
                reason = 'cancelled'
            elif now - begin >= timeout:
                reason = 'timeout'
            elif now - last_output >= idle_timeout:
                reason = 'no_progress'
            elif sum(p.rss for p in live.values()) > rss_limit_mb * 1024:
                reason = 'memory_limit'
            elif pressure.exceeded(live, now):
                reason = 'cpu_pressure'
            elif child_exit is not None:
                reason = 'completed' if child_exit == 0 else 'command_failed'
            if reason is not None:
                break
    except (OSError, subprocess.SubprocessError, RuntimeError) as error:
        # Do not render exception command/environment strings.
        reason = 'inspection_failed:' + type(error).__name__
    finally:
        try:
            if proc is not None:
                if not released:
                    # Still our direct, unreaped bootstrap; it has not been allowed to fork.
                    proc.kill()
                    proc.wait(timeout=remaining(deadline, 2))
                # Give a normally exiting single-use daemon a brief normal shutdown window.
                settle = min(deadline, time.monotonic() + (2 if reason == 'completed' else 0))
                while time.monotonic() < settle:
                    snapshot, live = discover()
                    proc.poll()
                    if not live:
                        break
                    drain()
                snapshot, live = discover()
                if live and reason == 'completed':
                    reason = 'leftover_processes'
                if live and diagnose:
                    diagnostic_cleanup = diagnostics(owned, marker, diag, min(deadline - 6, time.monotonic() + 4))
                # Include marked JVMs discovered during shutdown; never target a process group.
                for sig, seconds in ((signal.SIGTERM, 3), (signal.SIGKILL, 2)):
                    until = min(deadline, time.monotonic() + seconds)
                    while True:
                        snapshot, live = discover()
                        proc.poll()  # Reap launcher before checking for its absence.
                        for p in live.values():
                            if time.monotonic() > until:
                                break
                            if not signal_owned(owned[p.pid], marker, sig,
                                                snapshot=lambda: processes(timeout=remaining(deadline, 1)),
                                                require_marker=p.pid in ownership.nonce_owned):
                                refused.add(p.pid)
                        if not live or time.monotonic() >= until:
                            break
                        drain(0.1)
                snapshot, _ = discover()
                proc.poll()
                cleanup_ok = diagnostic_cleanup and ownership.cleaned(snapshot, proc.returncode)
            else:
                cleanup_ok = True
        except (OSError, subprocess.SubprocessError, RuntimeError):
            cleanup_ok = False
        selector.close()
        for fd in (gate_read, gate_write):
            if fd >= 0:
                os.close(fd)
        if proc is not None and proc.stdout is not None:
            proc.stdout.close()
        output.close()
        diag.close()
        for sig, old in old_handlers.items():
            signal.signal(sig, old)
    code = (0 if reason == 'completed' else 124 if reason in ('timeout', 'no_progress', 'memory_limit', 'cpu_pressure')
            else 130 if reason == 'cancelled' else 1) if cleanup_ok else 125
    summary = {'reason': reason, 'exitCode': code, 'commandExitCode': child_exit,
               'cleanup': 'verified' if cleanup_ok else 'unconfirmed',
               'elapsedSeconds': round(time.monotonic() - begin, 3),
               'cpuGuard': {'limitPercent': cpu_limit_percent, 'sustainedSeconds': cpu_seconds,
                            'peakObservedPercent': round(pressure.peak, 1)},
               'owned': [{'pid': p.pid, 'started': p.started} for p in owned.values()],
               'refusedPids': sorted(refused)}
    (directory / 'result.json').write_text(json.dumps(summary, indent=2) + '\n')
    return code


def valid_tasks(tasks):
    """No project relocation, daemon/JVM overrides or executable init/build inputs."""
    index = 0
    has_task = False
    while index < len(tasks):
        item = tasks[index]
        if item == '--tests':
            index += 1
            if index == len(tasks) or tasks[index].startswith('-'):
                return False
        elif item in ('--rerun-tasks', '--no-build-cache', '--offline', '--stacktrace', '--continue'):
            pass
        elif not item.startswith('-') and re.fullmatch(r':?[A-Za-z0-9_-]+(?::[A-Za-z0-9_-]+)*', item):
            has_task = True
        else:
            return False
        index += 1
    return has_task


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--timeout', type=int, default=300)
    parser.add_argument('--idle-timeout', type=int, default=120)
    parser.add_argument('--heap-mib', type=int, default=512)
    parser.add_argument('--output', type=Path)
    parser.add_argument('gradle', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    tasks = args.gradle[1:] if args.gradle[:1] == ['--'] else args.gradle
    if not 30 <= args.timeout <= 300 or not 1 <= args.idle_timeout <= 300 or not 128 <= args.heap_mib <= 1536:
        parser.error('timeout must be 30..300 and heap 128..1536 MiB')
    if not valid_tasks(tasks):
        parser.error('only task names, --tests filters and documented diagnostic flags are supported')
    root = Path(__file__).resolve().parent.parent
    if Path.cwd().resolve() != root:
        parser.error('run from this clubs_bot worktree root')
    run = Path(tempfile.mkdtemp(prefix='clb-gradle-'))
    output = args.output or run / 'evidence'
    marker = '-Dclb.test.run=' + uuid.uuid4().hex
    init = run / 'bounded.gradle'
    init.write_text("allprojects { tasks.withType(Test).configureEach {\n"
                    f"maxHeapSize = '{args.heap_mib}m'; maxParallelForks = 1\n"
                    f"timeout = java.time.Duration.ofSeconds({min(180, args.timeout)})\n"
                    f"jvmArgs '{marker}', '-XX:ActiveProcessorCount=2'\n"
                    "} }\n")
    # Wrapper JVM has an explicit argv marker via JAVA_OPTS; daemon and Tests get their own.
    env = dict(os.environ)
    if any(env.get(k) for k in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'JAVA_OPTS', 'GRADLE_OPTS')):
        parser.error('ambient JVM option overrides require explicit review; no process was launched')
    env['JAVA_OPTS'] = marker + ' -XX:ActiveProcessorCount=2 -Xmx128m'
    command = [str(root / 'gradlew'), '--no-daemon', '--no-parallel', '--max-workers=1',
               '--no-configuration-cache', '--console=plain',
               '-Dorg.gradle.daemon.registry.base=' + str(run / 'daemon'),
               '-Dorg.gradle.jvmargs=-Xmx1024m -XX:ActiveProcessorCount=2 ' + marker,
               '-Pkotlin.compiler.execution.strategy=in-process', '-I', str(init), *tasks]
    print('Bounded Gradle evidence: ' + str(output), flush=True)
    # Reserve time for bounded diagnostics and TERM/KILL reconciliation, rather than
    # waiting the entire 300 seconds before attempting cleanup.
    return supervise(command, marker, output, args.timeout - 20, args.idle_timeout, env=env)


if __name__ == '__main__':
    sys.exit(main())
