#!/usr/bin/env -S python3 -I -S -B
"""CLB-132 fixed read-only package-plan channel; one transport, no retry."""
import sys
if __name__ == '__main__' and not (sys.flags.isolated and sys.flags.no_site and sys.dont_write_bytecode):
    raise SystemExit('clb132-package-plan-local:v=1 {"authenticated":false,"reason":"request","result":"REFUSED"}')

import ast
import base64
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
REMOTE_PATH = 'scripts/deploy/stage-package-plan-operation.py'
RUNNER_PATH = 'scripts/deploy/stage-package-plan.py'
WORKFLOW_PATH = '.github/workflows/stage-package-plan.yml'
CONFIRMATION = 'CLB-132:collect-package-plan'
SOURCE_LIMIT = 65536
FRAME_LIMIT = 1052800
WATCHED = (signal.SIGHUP, signal.SIGINT, signal.SIGTERM)
PROTOCOL_PATH = 'scripts/deploy/stage-package-plan-protocol.py'
TARGET_PATH = 'scripts/deploy/stage-package-plan-targets.json'
TRANSPORT_PATH = 'scripts/deploy/corrected-stage-release.py'
DEPENDENCY_PATHS = ('scripts/deploy/release_private_root.py', 'scripts/deploy/release_authority.py')
LOCAL_SOURCES = (RUNNER_PATH, WORKFLOW_PATH, TRANSPORT_PATH, *DEPENDENCY_PATHS, PROTOCOL_PATH, TARGET_PATH, REMOTE_PATH)
REMOTE_SOURCES = (REMOTE_PATH, PROTOCOL_PATH, TARGET_PATH)
SOURCE_PINS = {
    TRANSPORT_PATH: 'f7717f5cb41ad56a74c397000a44112d622e5aa040175abe5ce4d0921b88add2',
    DEPENDENCY_PATHS[0]: '250d359d114779ddcc12c38bc64dc7015d5679f0ffce12a859f007810726cdba',
    DEPENDENCY_PATHS[1]: '83c8c81adeffc0ba7bb97d499ac3d8077950593ab03875856e17feccc8540216',
    REMOTE_PATH: '077d715634d092238be907abc8de7e69d6ddb131fbebacb1294d513589aa114a',
    TARGET_PATH: '9c9ba991a9edcea28ed9b24ee6f3d2f9928a748883cc923fd621bf012fb42e02',
    PROTOCOL_PATH: '6f85d99ab22be7d7005cc81f2a9de632222dfb2e2998c18f56a3f80905d29bf0',
}


def verify_sources(sources):
    require(set(sources) == set(LOCAL_SOURCES))
    require(all(type(raw) is bytes and 0 < len(raw) <= SOURCE_LIMIT for raw in sources.values()))
    require(all(hashlib.sha256(sources[path]).hexdigest() == digest for path, digest in SOURCE_PINS.items()))
    # Every fixed Python object compiles before the first dependent project exec.
    for path, raw in sources.items():
        if path.endswith('.py'):
            compile(raw, str(ROOT / path), 'exec')
    target = json.loads(sources[TARGET_PATH], object_pairs_hook=unique)
    tree = ast.parse(sources[REMOTE_PATH])
    request = [n for n in tree.body if isinstance(n, ast.Assign)
               and any(isinstance(t, ast.Name) and t.id == 'REQUEST' for t in n.targets)]
    require(len(request) == 1)
    require([(r['package'], r['version']) for r in target['request']] == list(ast.literal_eval(request[0].value)))
    require(len(target['request']) == 21)


def unique(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result)
        result[key] = value
    return result


def captured_protocol(sources):
    modules = []
    for path in (REMOTE_PATH, PROTOCOL_PATH):
        module = types.ModuleType(Path(path).stem)
        module.__file__ = str(ROOT / path)
        exec(compile(sources[path], module.__file__, 'exec'), module.__dict__)
        modules.append(module)
    return modules


def active(cancelled):
    if cancelled[0]:
        raise InterruptedError()


def load_transport(sources, cancelled):
    # snapshot() has verified the WHOLE closure before this first project exec.
    # Recheck completeness/pins and compile everything before executing anything.
    active(cancelled)
    verify_sources(sources)
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
            and re.fullmatch('[1-9][0-9]{0,19}', env.get('GITHUB_RUN_ID', ''))
            and env.get('GITHUB_WORKFLOW_SHA') == env.get('GITHUB_SHA')
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
    sources = {path: source(path) for path in LOCAL_SOURCES}
    for path in LOCAL_SOURCES:
        active(cancelled)
        raw = sources[path]
        fd = os.open(ROOT / path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(fd, 'rb') as stream:
            require(stat.S_ISREG(os.fstat(stream.fileno()).st_mode))
            require(stream.read(SOURCE_LIMIT + 1) == raw)
    active(cancelled)
    verify_sources(sources)
    return types.MappingProxyType(sources)


def validate_target(env):
    # Protected stage inputs choose the already-established deployment principal.
    # No caller principal, host, port, key or alternate endpoint input exists.
    require(re.fullmatch(r'[a-zA-Z0-9_][a-zA-Z0-9._-]*', env.get('SSH_USER', ''))
            and env['SSH_USER'] not in ('root', 'hookah-staging')
            and env.get('SSH_HOST') == '178.20.209.5' and env.get('SSH_PORT') == '22')
    match = re.fullmatch(r'(?:178\.20\.209\.5|\[178\.20\.209\.5\]:22) ssh-ed25519 ([A-Za-z0-9+/]+={0,2})\n?', env.get('SSH_KNOWN_HOSTS', ''))
    require(match is not None)
    key = base64.b64decode(match[1], validate=True)
    require(key.startswith(b'\x00\x00\x00\x0bssh-ed25519\x00\x00\x00\x20') and len(key) == 51)
    require(base64.b64encode(hashlib.sha256(key).digest()).decode().rstrip('=') == 'Li2AIDm9/OG8CHWQw16qhDfzbRM7E9uLNjPeKOZ9ST0')


BOOTSTRAP = r'''
import sys, os, json, struct, hashlib, hmac, signal, types, re, base64, selectors, time
watched = (signal.SIGHUP, signal.SIGINT, signal.SIGTERM, signal.SIGALRM)
cancelled = [False]
collector = None
protocol = None
def stop(*unused):
    already = cancelled[0]
    cancelled[0] = True
    if already or (protocol is not None and protocol.CRITICAL): return
    if collector is not None: raise collector.Refuse('INTERRUPTED')
    raise InterruptedError()
def unique(pairs):
    result = {}
    for key, value in pairs:
        assert key not in result
        result[key] = value
    return result
for sig in watched: signal.signal(sig, stop)
signal.alarm(105)
try:
    assert sys.flags.isolated and sys.flags.no_site and sys.dont_write_bytecode
    data = sys.stdin.buffer.read(270337)
    assert 36 < len(data) <= 270336
    key, size = data[:32], struct.unpack('!I', data[32:36])[0]
    assert 0 < size <= 4096 and len(data) > 36 + size
    control = json.loads(data[36:36+size], object_pairs_hook=unique)
    assert set(control) == {'identity', 'challenge', 'sha256'}
    encoded = data[36+size:]
    assert len(encoded) <= 262144 and hashlib.sha256(encoded).hexdigest() == control['sha256']
    sources = json.loads(encoded, object_pairs_hook=unique)
    pins = REMOTE_PIN_LITERAL
    assert set(sources) == set(pins)
    raw = {name: base64.b64decode(sources[name], validate=True) for name in pins}
    assert all(0 < len(value) <= 65536 and hashlib.sha256(value).hexdigest() == pins[name] for name, value in raw.items())
    target = json.loads(raw['stage-package-plan-targets.json'], object_pairs_hook=unique)
    compiled = {name: compile(raw[name], '/clb132-source/scripts/deploy/'+name, 'exec') for name in pins if name.endswith('.py')}
    assert not cancelled[0]
    collector = types.ModuleType('fixed_package_collector')
    exec(compiled['stage-package-plan-operation.py'], collector.__dict__)
    protocol = types.ModuleType('fixed_package_protocol')
    exec(compiled['stage-package-plan-protocol.py'], protocol.__dict__)
    assert [(r['package'], r['version']) for r in target['request']] == list(collector.REQUEST)
    protocol.identity(control['identity'])
    body, code = protocol.collect(collector, control['identity'], control['challenge'], lambda: cancelled[0])
    assert not cancelled[0] and not set(signal.sigpending()).intersection(watched)
    protocol.parse(body, code, collector, control['identity'], control['challenge'])
    tag = hmac.new(key, body, hashlib.sha256).hexdigest().encode('ascii')
    frame = b'clb132-package-plan-auth:v=1 tag=' + tag + b' ' + body
    assert len(frame) <= 1052800
    # A 1 MiB frame can exceed the SSH stdout pipe capacity. Never mask signals
    # or disable the overall alarm before a bounded, nonblocking emission ends.
    os.set_blocking(1, False)
    with selectors.DefaultSelector() as output:
        output.register(1, selectors.EVENT_WRITE)
        offset, deadline = 0, time.monotonic() + 5
        while offset < len(frame):
            remaining = deadline - time.monotonic()
            assert remaining > 0 and not cancelled[0]
            for unused in output.select(min(remaining, .1)):
                try:
                    count = os.write(1, frame[offset:offset+4096])
                    assert count > 0
                    offset += count
                except BlockingIOError:
                    pass
    assert not cancelled[0]
    signal.alarm(0)
except BaseException:
    cancelled[0] = True
    if protocol is not None and protocol.CURRENT_OWNER is not None:
        try: protocol.CURRENT_OWNER.__exit__()
        except BaseException: pass
    sys.exit(1)
sys.exit(code)
'''.replace('REMOTE_PIN_LITERAL', repr({Path(path).name: SOURCE_PINS[path] for path in REMOTE_SOURCES}))


def ssh_argv(env, reference):
    command = ('test "$(id -un)" = ' + shlex.quote(env['SSH_USER'])
               + ' && test "$(id -u)" != 0 && LC_ALL=C LANG=C exec '
               + shlex.join(['python3', '-I', '-S', '-B', '-c', BOOTSTRAP]))
    return ['ssh', '-p', env['SSH_PORT'], '-F', '/dev/null',
            '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes',
            '-o', f'UserKnownHostsFile={reference}', '-o', 'GlobalKnownHostsFile=/dev/null',
            '-o', 'KnownHostsCommand=none', '-o', 'VerifyHostKeyDNS=no', '-o', 'UpdateHostKeys=no',
            '-o', 'ProxyCommand=none', '-o', 'ProxyJump=none', '-o', 'PermitLocalCommand=no',
            '-o', 'ConnectTimeout=15', '-o', 'ConnectionAttempts=1', '--',
            env['SSH_USER'] + '@' + env['SSH_HOST'], command]


def invocation_identity(env, sources):
    hashes = {path: hashlib.sha256(raw).hexdigest() for path, raw in sources.items()}
    return dict(run_id=env['GITHUB_RUN_ID'], attempt=env['GITHUB_RUN_ATTEMPT'], workflow_sha=env['GITHUB_SHA'],
                workflow_sha256=hashes[WORKFLOW_PATH], runner_sha256=hashes[RUNNER_PATH],
                closure_sha256=hashlib.sha256(json.dumps(hashes, sort_keys=True, separators=(',', ':')).encode()).hexdigest(),
                collector_sha256=hashes[REMOTE_PATH], target_sha256=hashes[TARGET_PATH])


def parse_result(raw, code, key, protocol, collector, identity, challenge):
    require(type(raw) is bytes and len(raw) <= FRAME_LIMIT)
    match = re.fullmatch(rb'clb132-package-plan-auth:v=1 tag=([0-9a-f]{64}) (clb132-package-plan:v=1 [^\r\n]*\n)', raw)
    require(match is not None)
    require(hmac.compare_digest(match[1], hmac.new(key, match[2], hashlib.sha256).hexdigest().encode()))
    return protocol.parse(match[2], code, collector, identity, challenge)


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
        if args == ['--validate']:
            return 'package-plan-validation:v=1 result=ok', 0
        validate_target(env)
        collector, protocol = captured_protocol(sources)
        identity = invocation_identity(env, sources)
        nonce = secrets.token_bytes(32)
        challenge = secrets.token_hex(32)
        bundle = json.dumps({Path(path).name: base64.b64encode(sources[path]).decode('ascii')
                             for path in REMOTE_SOURCES}, sort_keys=True, separators=(',', ':')).encode()
        require(len(bundle) <= 262144)
        control = json.dumps(dict(identity=identity, challenge=challenge, sha256=hashlib.sha256(bundle).hexdigest()),
                             separators=(',', ':')).encode()
        payload = nonce + struct.pack('!I', len(control)) + control + bundle
        with transport.pinned_hosts(env) as (fd, reference):
            os.lseek(fd, 0, os.SEEK_SET)
            require(not cancelled[0])
            child_env = {key: env[key] for key in ('PATH', 'SSH_AUTH_SOCK') if key in env}
            child_env['LC_ALL'] = 'C'
            phase = 'transport'
            captured = transport.capture_result(ssh_argv(env, reference), payload, env=child_env,
                                                 timeout=120, limit=FRAME_LIMIT, pass_fds=(fd,))
            require(captured.failure is None and not cancelled[0])
            phase = 'protocol'
            answer = parse_result(captured.output, captured.code, nonce, protocol, collector, identity, challenge)
            phase = 'cleanup'
        require(not cancelled[0])
        return answer
    except BaseException:
        reason = 'interrupted' if cancelled[0] else phase
        return 'clb132-package-plan-local:v=1 '+json.dumps(dict(reason=reason,result='REFUSED',authenticated=False),sort_keys=True,separators=(',',':')), 1


def publish(line, code, cancelled):
    # Local publication follows SSH process-group AND anonymous pin cleanup.
    signal.pthread_sigmask(signal.SIG_BLOCK, WATCHED)
    if cancelled[0] or set(signal.sigpending()).intersection(WATCHED):
        line, code = 'clb132-package-plan-local:v=1 {"authenticated":false,"reason":"interrupted","result":"REFUSED"}', 1
    body = (line + '\n').encode('ascii')
    require(len(body) <= FRAME_LIMIT)
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
