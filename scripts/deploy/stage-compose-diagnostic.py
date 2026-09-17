#!/usr/bin/env -S python3 -I -S -B
"""CLB-91 fixed manual read-only diagnostic; one transport, no retry."""
import sys
if __name__ == '__main__' and not (sys.flags.isolated and sys.flags.no_site):
    raise SystemExit('compose-diagnostic:v=2 result=unavailable reason=request')

import ast
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import signal
import selectors
import stat
import subprocess
import time
import struct
import types

ROOT = Path(__file__).resolve().parents[2]
REMOTE_PATH = 'scripts/deploy/stage-compose-diagnostic-operation.py'
RUNNER_PATH = 'scripts/deploy/stage-compose-diagnostic.py'
WORKFLOW_PATH = '.github/workflows/stage-compose-diagnostic.yml'
CONFIRMATION = 'CLB-91:35206468948:diagnose-compose-subset'
SOURCE_LIMIT = 65536
FRAME_LIMIT = 4096
WATCHED = (signal.SIGHUP, signal.SIGINT, signal.SIGTERM)
LOCAL_SOURCES = (RUNNER_PATH, 'scripts/deploy/corrected-stage-release.py',
                 'scripts/deploy/release_private_root.py', 'scripts/deploy/release_authority.py')


# Fixed, stdlib-only source closure of the unchanged corrected transport.
# Pins restrict the loader adapter below to precisely these reviewed modules.
TRANSPORT_PATH = 'scripts/deploy/corrected-stage-release.py'
DEPENDENCY_PATHS = ('scripts/deploy/release_private_root.py', 'scripts/deploy/release_authority.py')
SOURCE_PINS = {
    TRANSPORT_PATH: 'f7717f5cb41ad56a74c397000a44112d622e5aa040175abe5ce4d0921b88add2',
    DEPENDENCY_PATHS[0]: '250d359d114779ddcc12c38bc64dc7015d5679f0ffce12a859f007810726cdba',
    DEPENDENCY_PATHS[1]: '83c8c81adeffc0ba7bb97d499ac3d8077950593ab03875856e17feccc8540216',
}


def active(cancelled):
    if cancelled[0]:
        raise InterruptedError()


def load_transport(sources, cancelled):
    # snapshot() has verified the WHOLE closure before this first project exec.
    # Recheck completeness/pins and compile everything before executing anything.
    active(cancelled)
    require(set(sources) == set((*LOCAL_SOURCES, REMOTE_PATH)))
    require(all(type(raw) is bytes and 0 < len(raw) <= SOURCE_LIMIT for raw in sources.values()))
    require(all(hashlib.sha256(sources[path]).hexdigest() == digest for path, digest in SOURCE_PINS.items()))
    tree = ast.parse(sources[TRANSPORT_PATH], filename=str(ROOT / TRANSPORT_PATH))
    loaders = [node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == 'source_module']
    require(len(loaders) == 1)
    # The sole adaptation of the pinned source: omit its pathname loader and
    # supply the same two-name API from captured bytes. All other AST nodes are
    # untouched. No import hooks, sys.path, .pyc, fallback or working-copy reread.
    tree.body.remove(loaders[0])
    compiled = {path: compile(sources[path], str(ROOT / path), 'exec') for path in DEPENDENCY_PATHS}
    compiled[TRANSPORT_PATH] = compile(tree, str(ROOT / TRANSPORT_PATH), 'exec')
    modules = {}
    for path in DEPENDENCY_PATHS:
        active(cancelled)
        name = Path(path).stem
        module = types.ModuleType(name)
        module.__file__ = str(ROOT / path)
        exec(compiled[path], module.__dict__)
        modules[name] = module

    def captured_module(name):
        active(cancelled)
        require(name in ('release_private_root', 'release_authority'))
        return modules[name]

    active(cancelled)
    transport = types.ModuleType('corrected_transport')
    transport.__file__ = str(ROOT / TRANSPORT_PATH)
    transport.source_module = captured_module
    exec(compiled[TRANSPORT_PATH], transport.__dict__)
    active(cancelled)
    return transport


def require(ok):
    if not ok:
        raise ValueError()


def validate(env):
    require(env.get('GITHUB_REPOSITORY') == 'koteev-m/clubs_bot'
            and env.get('GITHUB_EVENT_NAME') == 'workflow_dispatch'
            and env.get('GITHUB_REF') == 'refs/heads/main'
            and env.get('GITHUB_REF_TYPE') == 'branch'
            and env.get('REPOSITORY_DEFAULT_BRANCH') == 'main'
            and env.get('APP_ENV') == 'stage'
            and env.get('CONFIRMATION') == CONFIRMATION
            and env.get('GITHUB_RUN_ATTEMPT') == '1'
            and re.fullmatch('[0-9a-f]{40}', env.get('GITHUB_SHA', ''))
            and env.get('GITHUB_WORKFLOW_REF') == 'koteev-m/clubs_bot/' + WORKFLOW_PATH + '@refs/heads/main')


def snapshot(env, cancelled):
    git_env = dict(PATH=env.get('PATH', os.defpath), LC_ALL='C', GIT_NO_REPLACE_OBJECTS='1',
                   GIT_OPTIONAL_LOCKS='0', GIT_LITERAL_PATHSPECS='1')

    def git(*args, limit=1024):
        active(cancelled)
        result = capture_result(['git', '--no-replace-objects', '-C', str(ROOT), *args],
                                env=git_env, timeout=10, limit=limit)
        active(cancelled)
        require(result.failure is None and result.code == 0)
        return result.output

    sha = env['GITHUB_SHA']
    require(git('rev-parse', 'HEAD') == (sha + '\n').encode())

    def source(path):
        entry = git('ls-tree', '-z', sha, '--', path)
        match = re.fullmatch(rb'100644 blob ([0-9a-f]{40})\t' + re.escape(path.encode()) + b'\x00', entry)
        require(match is not None)
        raw = git('cat-file', 'blob', match[1].decode(), limit=SOURCE_LIMIT)
        require(hashlib.sha1(f'blob {len(raw)}\0'.encode() + raw).hexdigest().encode() == match[1])
        return raw

    # No working-copy fallback, filters, URL, caller path or Python bytecode.
    sources = {path: source(path) for path in (*LOCAL_SOURCES, REMOTE_PATH)}
    for path in LOCAL_SOURCES:
        active(cancelled)
        raw = sources[path]
        fd = os.open(ROOT / path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(fd, 'rb') as stream:
            require(stat.S_ISREG(os.fstat(stream.fileno()).st_mode))
            require(stream.read(SOURCE_LIMIT + 1) == raw)
    active(cancelled)
    require(all(hashlib.sha256(sources[path]).hexdigest() == digest for path, digest in SOURCE_PINS.items()))
    return types.MappingProxyType(sources)


def validate_target(env):
    require(env.get('COMPOSE_PATH') == '/opt/clubs-bot-stage'
            and re.fullmatch(r'[a-zA-Z0-9_][a-zA-Z0-9._-]*', env.get('SSH_USER', ''))
            and env['SSH_USER'] not in ('root', 'hookah-staging')
            and re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9._-]*', env.get('SSH_HOST', ''))
            and re.fullmatch(r'[0-9]{1,5}', env.get('SSH_PORT', ''))
            and 1 <= int(env['SSH_PORT']) <= 65535)


BOOTSTRAP = r'''
import sys, os, json, struct, hashlib, hmac, signal, types, re
watched = (signal.SIGHUP, signal.SIGINT, signal.SIGTERM, signal.SIGALRM)
cancelled = [False]
module = None
def stop(*unused):
    cancelled[0] = True
    capture = getattr(module, '_capture_cancellation', None)
    if capture is not None:
        capture[0] = True
    else:
        raise InterruptedError()
def unique(pairs):
    result = {}
    for key, value in pairs:
        assert key not in result
        result[key] = value
    return result
for sig in watched: signal.signal(sig, stop)
signal.alarm(25)
try:
    data = sys.stdin.buffer.read(66597)
    assert 36 < len(data) <= 66596
    nonce, size = data[:32], struct.unpack('!I', data[32:36])[0]
    assert 0 < size <= 1024 and len(data) > 36 + size
    control = json.loads(data[36:36+size], object_pairs_hook=unique)
    assert set(control) == {'principal', 'sha256'}
    assert type(control['principal']) is str and re.fullmatch('[a-zA-Z0-9_][a-zA-Z0-9._-]*', control['principal'])
    source = data[36+size:]
    assert 0 < len(source) <= 65536 and hashlib.sha256(source).hexdigest() == control['sha256']
    module = types.ModuleType('fixed_compose_diagnostic')
    exec(compile(source, '<fixed-compose-diagnostic>', 'exec'), module.__dict__)
    body = module.diagnose(control['principal'], lambda: cancelled[0])
    # Linearize publication only after all FD/process/pin-independent cleanup.
    # A signal pending at handoff wins; no pre-handoff success is cached.
    signal.pthread_sigmask(signal.SIG_BLOCK, watched)
    signal.alarm(0)
    if cancelled[0] or set(signal.sigpending()).intersection(watched):
        body = module.unavailable('interrupted')
    code = 1 if body.startswith(b'compose-diagnostic:v=2 result=unavailable ') else 0
    module.parse_body(body, code)
    tag = hmac.new(nonce, body, hashlib.sha256).hexdigest().encode('ascii')
    frame = b'clb91-compose-auth:v=1 tag=' + tag + b' ' + body
    assert len(frame) <= 4096
    # One bounded write, no partial-frame retry. Signals after this handoff do
    # not revoke an already complete read-only snapshot or authorize mutation.
    assert os.write(1, frame) == len(frame)
except BaseException:
    sys.exit(1)
sys.exit(code)
'''


def ssh_argv(env, reference):
    command = ('test "$(id -un)" = ' + shlex.quote(env['SSH_USER'])
               + ' && test "$(id -u)" != 0 && exec '
               + shlex.join(['python3', '-I', '-S', '-B', '-c', BOOTSTRAP]))
    return ['ssh', '-p', env['SSH_PORT'], '-F', '/dev/null',
            '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes',
            '-o', f'UserKnownHostsFile={reference}', '-o', 'GlobalKnownHostsFile=/dev/null',
            '-o', 'KnownHostsCommand=none', '-o', 'VerifyHostKeyDNS=no', '-o', 'UpdateHostKeys=no',
            '-o', 'ProxyCommand=none', '-o', 'ProxyJump=none', '-o', 'PermitLocalCommand=no',
            '-o', 'ConnectTimeout=15', '-o', 'ConnectionAttempts=1', '--',
            env['SSH_USER'] + '@' + env['SSH_HOST'], command]


def parse_result(raw, code, nonce, protocol):
    require(type(raw) is bytes and len(raw) <= FRAME_LIMIT)
    match = re.fullmatch(rb'clb91-compose-auth:v=1 tag=([0-9a-f]{64}) (compose-diagnostic:v=2 [^\r\n]*\n)', raw)
    require(match is not None)
    require(hmac.compare_digest(match[1], hmac.new(nonce, match[2], hashlib.sha256).hexdigest().encode()))
    return protocol.parse_body(match[2], code)


def main(env, args, cancelled):
    phase = 'request'
    try:
        validate(env)
        require(args in ([], ['--validate']))
        transport = None

        def stop(*unused):
            cancelled[0] = True
            capture_cancel = _capture_cancellation if transport is None else transport._capture_cancellation
            if capture_cancel is not None:
                capture_cancel[0] = True
            elif transport is None:
                # Before module loading, cancellation must abort execution.
                # Once loaded, retain the existing pin/transport ownership
                # handoff: latch outside captures and let cleanup finish.
                raise InterruptedError()

        for sig in WATCHED:
            signal.signal(sig, stop)
        sources = snapshot(env, cancelled)
        active(cancelled)
        transport = load_transport(sources, cancelled)
        source = sources[REMOTE_PATH]
        if args == ['--validate']:
            return 'compose-diagnostic-validation:v=1 result=ok', 0
        validate_target(env)
        protocol = types.ModuleType('fixed_diagnostic_protocol')
        exec(compile(source, '<fixed-diagnostic-protocol>', 'exec'), protocol.__dict__)
        nonce = secrets.token_bytes(32)
        control = json.dumps(dict(principal=env['SSH_USER'], sha256=hashlib.sha256(source).hexdigest()),
                             separators=(',', ':')).encode()
        payload = nonce + struct.pack('!I', len(control)) + control + source
        with transport.pinned_hosts(env) as (fd, reference):
            os.lseek(fd, 0, os.SEEK_SET)
            require(not cancelled[0])
            child_env = {key: env[key] for key in ('PATH', 'SSH_AUTH_SOCK') if key in env}
            child_env['LC_ALL'] = 'C'
            phase = 'transport'
            captured = transport.capture_result(ssh_argv(env, reference), payload, env=child_env,
                                                 timeout=45, limit=FRAME_LIMIT, pass_fds=(fd,))
            require(captured.failure is None and not cancelled[0])
            phase = 'protocol'
            answer = parse_result(captured.output, captured.code, nonce, protocol)
            phase = 'cleanup'
        require(not cancelled[0])
        return answer
    except BaseException:
        reason = 'interrupted' if cancelled[0] else phase
        return 'compose-diagnostic:v=2 result=unavailable reason=' + reason, 1


def publish(line, code, cancelled):
    # Local publication follows SSH process-group AND anonymous pin cleanup.
    signal.pthread_sigmask(signal.SIG_BLOCK, WATCHED)
    if cancelled[0] or set(signal.sigpending()).intersection(WATCHED):
        line, code = 'compose-diagnostic:v=2 result=unavailable reason=interrupted', 1
    body = (line + '\n').encode('ascii')
    require(len(body) <= 2048)
    require(os.write(1, body) == len(body))
    return code


# BEGIN EXACT CORRECTED CAPTURE PRIMITIVES
# Trusted bootstrap copy: Git verification cannot depend on unchecked modules.
class CaptureResult:
    """Private bounded bytes plus local termination provenance, never a log record."""
    __slots__ = ("code", "output", "failure")

    def __init__(self, code, output, failure=None):
        self.code, self.output, self.failure = code, output, failure


class CaptureInterrupted(BaseException):
    """Signal cancellation that selectors cannot swallow as an EINTR retry."""


class CaptureCleanupError(OSError):
    """The owned process group could not be cleaned up; never publish OS text."""


# main() runs captures serially on the signal-handling thread. During a capture
# the existing handler records cancellation without unwinding any ownership
# transition. No signal mask/handler changes are inherited by the child.
_capture_cancellation = None


def interrupted(_signum, _frame):
    if _capture_cancellation is not None:
        _capture_cancellation[0] = True
        return
    for watched in (signal.SIGHUP, signal.SIGINT, signal.SIGTERM):
        signal.signal(watched, signal.SIG_IGN)
    raise CaptureInterrupted


def capture(argv, payload=b"", *, timeout=30, limit=32768, env=None, pass_fds=(), spawn_failed=None):
    """Keep the existing tuple API, including its legacy 124/125 conventions."""
    result = capture_result(argv, payload, timeout=timeout, limit=limit, env=env,
                            pass_fds=pass_fds, spawn_failed=spawn_failed)
    return result.code, result.output


def capture_result(argv, payload=b"", *, timeout=30, limit=32768, env=None, pass_fds=(), spawn_failed=None):
    """Own cancellation before spawn, through cleanup, until outcome selection."""
    global _capture_cancellation
    cancellation = [False]
    previous = _capture_cancellation
    result, failure = None, None
    _capture_cancellation = cancellation
    try:
        result = _capture_owned(argv, payload, timeout=timeout, limit=limit, env=env,
                                pass_fds=pass_fds, spawn_failed=spawn_failed, cancellation=cancellation)
    except BaseException as error:
        failure = error
    finally:
        # This handoff happens only after cleanup (or failed spawn). A signal
        # before it is recorded; one after it uses the original terminal path.
        # Check the recorded outcome AFTER handoff, never cache a success first.
        _capture_cancellation = previous
    if isinstance(failure, CaptureCleanupError):
        raise failure
    if cancellation[0]:
        if previous is not None:
            previous[0] = True
        return CaptureResult(124, result.output if result is not None else b"", "capture_interrupted")
    if failure is not None:
        raise failure
    return result


def _capture_owned(argv, payload, *, timeout, limit, env, pass_fds, spawn_failed, cancellation):
    """No named stdout/stderr captures; bounded memory and bounded process lifetime."""
    output = bytearray()
    offset = 0
    deadline = time.monotonic() + timeout
    try:
        child = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                 stderr=subprocess.DEVNULL, env=env, pass_fds=pass_fds,
                                 start_new_session=True)
    except OSError:
        if spawn_failed is not None:
            spawn_failed()
        raise
    # Keep the leader unreaped until killpg: its PID pins the owned group ID,
    # even after exit, so cleanup cannot target a recycled PID/process group.
    group = child.pid
    try:
        with selectors.DefaultSelector() as poll:
            os.set_blocking(child.stdin.fileno(), False)
            os.set_blocking(child.stdout.fileno(), False)
            poll.register(child.stdout, selectors.EVENT_READ)
            if payload:
                poll.register(child.stdin, selectors.EVENT_WRITE)
            else:
                child.stdin.close()
            while poll.get_map():
                if cancellation[0]:
                    return CaptureResult(124, bytes(output), "capture_interrupted")
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return CaptureResult(124, bytes(output), "capture_timeout")
                for key, _ in poll.select(min(remaining, 0.2)):
                    if key.fileobj is child.stdin:
                        try:
                            offset += os.write(child.stdin.fileno(), payload[offset:offset + 4096])
                        except BrokenPipeError:
                            offset = len(payload)
                        if offset == len(payload):
                            poll.unregister(child.stdin)
                            child.stdin.close()
                    else:
                        chunk = os.read(child.stdout.fileno(), 4096)
                        if not chunk:
                            poll.unregister(child.stdout)
                        else:
                            output.extend(chunk)
                            if len(output) > limit:
                                return CaptureResult(125, b"", "capture_output_limit")
            while True:
                if cancellation[0]:
                    return CaptureResult(124, bytes(output), "capture_interrupted")
                exited = os.waitid(os.P_PID, group, os.WEXITED | os.WNOHANG | os.WNOWAIT)
                if exited is not None:
                    code = exited.si_status if exited.si_code == os.CLD_EXITED else -exited.si_status
                    return CaptureResult(code, bytes(output))
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return CaptureResult(124, bytes(output), "capture_timeout")
                time.sleep(min(remaining, .01))
    except subprocess.TimeoutExpired:
        return CaptureResult(124, bytes(output), "capture_timeout")
    except (InterruptedError, CaptureInterrupted):
        return CaptureResult(124, bytes(output), "capture_interrupted")
    finally:
        # No command retry. Terminate this invocation's group on every outcome,
        # including an exited leader with live descendants and a successful EOF.
        # Cancellation is already deferred, including entry into this finally.
        cleanup_failed = False
        permission_denied = False
        try:
            os.killpg(group, signal.SIGKILL)
        except ProcessLookupError:
            pass
        except PermissionError:
            permission_denied = True
        except OSError:
            cleanup_failed = True
        try:
            child.wait(timeout=2)
        except (OSError, subprocess.TimeoutExpired):
            cleanup_failed = True
        finally:
            for stream in (child.stdin, child.stdout):
                try:
                    if not stream.closed:
                        stream.close()
                except OSError:
                    cleanup_failed = True
        if permission_denied:
            # Darwin can report EPERM for an unreaped, zombie-only group.
            # Accept that case only if the group is now proven absent. This
            # is a non-mutating existence probe after reap, never another
            # kill against a group ID that could have been recycled.
            try:
                os.killpg(group, 0)
            except ProcessLookupError:
                pass
            except OSError:
                cleanup_failed = True
            else:
                cleanup_failed = True
        if cleanup_failed:
            raise CaptureCleanupError() from None


# END EXACT CORRECTED CAPTURE PRIMITIVES


if __name__ == '__main__':
    os.umask(0o077)
    cancellation = [False]
    try:
        line, code = main(os.environ.copy(), sys.argv[1:], cancellation)
        code = publish(line, code, cancellation)
    except BaseException:
        code = 1
    sys.exit(code)
