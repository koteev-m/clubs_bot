#!/usr/bin/env -S python3 -I -S -B
"""CLB-91 manual stage-only, one-transport mode remediation runner."""
import sys
if __name__ == '__main__' and not (sys.flags.isolated and sys.flags.no_site):
    raise SystemExit('compose-mode-repair:v=1 result=blocked reason=request')

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
REMOTE_PATH = 'scripts/deploy/stage-compose-mode-operation.py'
WORKFLOW_PATH = '.github/workflows/stage-compose-mode-repair.yml'
CONFIRMATION = 'CLB-91:35108589661:repair-compose-mode-0600'
BLOCKED = frozenset(('request', 'principal', 'layout', 'identity', 'busy', 'acl', 'filesystem', 'io', 'interrupted', 'local'))
AMBIGUOUS = frozenset(('write', 'readback', 'cleanup', 'interrupted', 'transport', 'protocol'))


def load_transport():
    # Reuse reviewed capture ownership and anonymous pinned-known-hosts handling
    # unchanged. Load exact source only, no sys.path or bytecode imports.
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
    entry = git('ls-tree', '-z', sha, '--', REMOTE_PATH)
    match = re.fullmatch(rb'100644 blob ([0-9a-f]{40})\t' + REMOTE_PATH.encode() + b'\x00', entry)
    require(match is not None)
    source = git('cat-file', 'blob', match[1].decode(), limit=16384)
    require(hashlib.sha1(f'blob {len(source)}\0'.encode() + source).hexdigest().encode() == match[1])
    return source


def validate_target(env):
    require(env.get('COMPOSE_PATH') == '/opt/clubs-bot-stage'
            and re.fullmatch(r'[a-zA-Z0-9_][a-zA-Z0-9._-]*', env.get('SSH_USER', ''))
            and env['SSH_USER'] not in ('root', 'hookah-staging')
            and re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9._-]*', env.get('SSH_HOST', ''))
            and re.fullmatch(r'[0-9]{1,5}', env.get('SSH_PORT', ''))
            and 1 <= int(env['SSH_PORT']) <= 65535)


# Private nonce is delivered after SSH authentication, never on argv/environment.
# No child stdout, shell startup output or prior response can forge a result.
BOOTSTRAP = r'''
import sys, os, json, struct, hashlib, hmac, signal, types
cancelled = [False]
def stop(*unused): cancelled[0] = True
for sig in (signal.SIGHUP, signal.SIGINT, signal.SIGTERM, signal.SIGALRM): signal.signal(sig, stop)
signal.alarm(20)
try:
    data = sys.stdin.buffer.read(32769)
    assert len(data) <= 32768 and len(data) >= 36
    nonce, size = data[:32], struct.unpack('!I', data[32:36])[0]
    assert 0 < size <= 1024
    control = json.loads(data[36:36+size])
    source = data[36+size:]
    assert 0 < len(source) <= 16384 and hashlib.sha256(source).hexdigest() == control['sha256']
    module = types.ModuleType('fixed_mode_operation')
    exec(compile(source, '<fixed-mode-operation>', 'exec'), module.__dict__)
    result, reason = module.repair(control['principal'], lambda: cancelled[0])
    if cancelled[0]: result, reason = 'ambiguous', 'interrupted'
    body = 'compose-mode-repair:v=1 result=' + result + ((' reason=' + reason) if reason else '') + '\n'
    tag = hmac.new(nonce, body.encode('ascii'), hashlib.sha256).hexdigest()
    sys.stdout.write('clb91-mode-auth:v=1 tag=' + tag + ' ' + body)
    sys.stdout.flush()
    exit_code = 0 if result in ('changed', 'already_valid') else 1
except BaseException:
    # No retry and no raw traceback; even output failure must be non-success.
    sys.exit(1)
sys.exit(exit_code)
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


def parse_result(raw, code, nonce):
    require(len(raw) <= 256)
    match = re.fullmatch(rb'clb91-mode-auth:v=1 tag=([0-9a-f]{64}) (compose-mode-repair:v=1 result=([a-z_]+)(?: reason=([a-z_]+))?\n)', raw)
    require(match is not None)
    require(hmac.compare_digest(match[1], hmac.new(nonce, match[2], hashlib.sha256).hexdigest().encode()))
    result, reason = match[3].decode(), match[4].decode() if match[4] else None
    require((result in ('changed', 'already_valid') and reason is None and code == 0)
            or (result == 'blocked' and reason in BLOCKED and code == 1)
            or (result == 'ambiguous' and reason in AMBIGUOUS and code == 1))
    return match[2].decode().rstrip('\n'), 0 if code == 0 else 1


def main(env, args):
    started = False
    try:
        validate(env)
        require(args in ([], ['--validate']))
        transport = load_transport()
        for sig in (signal.SIGHUP, signal.SIGINT, signal.SIGTERM):
            signal.signal(sig, transport.interrupted)
        source = snapshot(transport, env)
        if args == ['--validate']:
            return 'compose-mode-repair-validation:v=1 result=ok', 0
        validate_target(env)
        nonce = secrets.token_bytes(32)
        control = json.dumps(dict(principal=env['SSH_USER'], sha256=hashlib.sha256(source).hexdigest()),
                             separators=(',', ':')).encode()
        payload = nonce + struct.pack('!I', len(control)) + control + source
        with transport.pinned_hosts(env) as (fd, reference):
            os.lseek(fd, 0, os.SEEK_SET)
            child_env = {k: env[k] for k in ('PATH', 'SSH_AUTH_SOCK') if k in env}
            child_env['LC_ALL'] = 'C'
            started = True  # Failure after submission may hide an applied chmod.
            captured = transport.capture_result(ssh_argv(env, reference), payload, env=child_env,
                                                 timeout=45, limit=256, pass_fds=(fd,))
            require(captured.failure is None)
            answer = parse_result(captured.output, captured.code, nonce)
        return answer  # Publish only after local pin cleanup succeeded.
    except BaseException:
        return ('compose-mode-repair:v=1 result=ambiguous reason=transport' if started
                else 'compose-mode-repair:v=1 result=blocked reason=request'), 1


if __name__ == '__main__':
    os.umask(0o077)
    try:
        line, code = main(os.environ.copy(), sys.argv[1:])
        print(line, flush=True)
    except BaseException:
        # A late signal/output failure cannot disclose a traceback or retry SSH.
        code = 1
    sys.exit(code)
