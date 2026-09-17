#!/usr/bin/env -S python3 -I -S -B
"""CLB-91 fixed manual read-only diagnostic; one transport, no retry."""
import sys
if __name__ == '__main__' and not (sys.flags.isolated and sys.flags.no_site):
    raise SystemExit('compose-diagnostic:v=1 result=unavailable reason=request')

import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import signal
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


def load_transport():
    # Existing capture ownership and anonymous pinned-known-hosts primitives.
    # Import does not invoke corrected main, BoundContext or a mutation path.
    path = Path(__file__).resolve().with_name('corrected-stage-release.py')
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd, 'rb') as stream:
        module = types.ModuleType('corrected_transport')
        module.__file__ = str(path)
        exec(compile(stream.read(), str(path), 'exec'), module.__dict__)
    return module


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


def snapshot(transport, env):
    git_env = dict(PATH=env.get('PATH', os.defpath), LC_ALL='C', GIT_NO_REPLACE_OBJECTS='1',
                   GIT_OPTIONAL_LOCKS='0', GIT_LITERAL_PATHSPECS='1')

    def git(*args, limit=1024):
        code, raw = transport.capture(['git', '--no-replace-objects', '-C', str(ROOT), *args],
                                      env=git_env, timeout=10, limit=limit)
        require(code == 0)
        return raw

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
    for path in LOCAL_SOURCES:
        raw = source(path)
        fd = os.open(ROOT / path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(fd, 'rb') as stream:
            require(stream.read(SOURCE_LIMIT + 1) == raw)
    return source(REMOTE_PATH)


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
    code = 1 if body.startswith(b'compose-diagnostic:v=1 result=unavailable ') else 0
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
    match = re.fullmatch(rb'clb91-compose-auth:v=1 tag=([0-9a-f]{64}) (compose-diagnostic:v=1 [^\r\n]*\n)', raw)
    require(match is not None)
    require(hmac.compare_digest(match[1], hmac.new(nonce, match[2], hashlib.sha256).hexdigest().encode()))
    return protocol.parse_body(match[2], code)


def main(env, args, cancelled):
    phase = 'request'
    try:
        validate(env)
        require(args in ([], ['--validate']))
        transport = load_transport()

        def stop(*unused):
            cancelled[0] = True
            if transport._capture_cancellation is not None:
                transport._capture_cancellation[0] = True

        for sig in WATCHED:
            signal.signal(sig, stop)
        source = snapshot(transport, env)
        require(not cancelled[0])
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
        return 'compose-diagnostic:v=1 result=unavailable reason=' + reason, 1


def publish(line, code, cancelled):
    # Local publication follows SSH process-group AND anonymous pin cleanup.
    signal.pthread_sigmask(signal.SIG_BLOCK, WATCHED)
    if cancelled[0] or set(signal.sigpending()).intersection(WATCHED):
        line, code = 'compose-diagnostic:v=1 result=unavailable reason=interrupted', 1
    body = (line + '\n').encode('ascii')
    require(len(body) <= 2048)
    require(os.write(1, body) == len(body))
    return code


if __name__ == '__main__':
    os.umask(0o077)
    cancellation = [False]
    try:
        line, code = main(os.environ.copy(), sys.argv[1:], cancellation)
        code = publish(line, code, cancellation)
    except BaseException:
        code = 1
    sys.exit(code)
