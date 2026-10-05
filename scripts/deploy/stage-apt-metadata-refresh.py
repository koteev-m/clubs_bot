#!/usr/bin/env -S python3 -I -S -B
"""CLB-175 fixed maintenance channel; pinned existing transport, one attempt."""
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import signal
import stat
import sys
import types

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = '.github/workflows/stage-apt-metadata-refresh.yml'
RUNNER = 'scripts/deploy/stage-apt-metadata-refresh.py'
WRAPPER = 'scripts/deploy/stage-apt-metadata-refresh-wrapper'
INSTALL = '/usr/local/sbin/clubs-stage-apt-metadata-refresh'
CONFIRMATION = 'CLB-175:refresh-stage-apt-metadata'
PACKAGE_RUNNER = 'scripts/deploy/stage-package-plan.py'
PACKAGE_RUNNER_SHA256 = '269ef70c1e646e74a22cce93eac559021ab628b612d726345a605e59b17bda60'
WRAPPER_SHA256 = '877715742a47e19bfa596be9c9aebc00de6e18de1a64b8d44346f2070f094c9b'
LIMIT = 16384
CANCELLATION = [False]


def require(ok):
    if not ok:
        raise ValueError()


def load_primitives():
    # No import/path fallback or changed shared package-plan bytes.
    fd = os.open(ROOT / PACKAGE_RUNNER, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, 'rb') as stream:
        require(stat.S_ISREG(os.fstat(stream.fileno()).st_mode))
        raw = stream.read(65537)
    require(hashlib.sha256(raw).hexdigest() == PACKAGE_RUNNER_SHA256)
    module = types.ModuleType('pinned_package_primitives')
    module.__file__ = str(ROOT / PACKAGE_RUNNER)
    exec(compile(raw, module.__file__, 'exec'), module.__dict__)
    return module


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
            and env.get('GITHUB_WORKFLOW_REF') == 'koteev-m/clubs_bot/' + WORKFLOW + '@refs/heads/main')


def snapshot(p, env, cancelled):
    sources = dict(p.snapshot(env, cancelled))
    git_env = dict(PATH='/usr/bin:/bin', LC_ALL='C', GIT_NO_REPLACE_OBJECTS='1',
                   GIT_OPTIONAL_LOCKS='0', GIT_LITERAL_PATHSPECS='1')
    for path in (WORKFLOW, RUNNER, WRAPPER):
        mode = p.capture_result(['/usr/bin/git', '--no-replace-objects', '-C', str(ROOT),
                                 'ls-tree', '-z', env['GITHUB_SHA'], '--', path],
                                env=git_env, limit=1024, timeout=10)
        require(mode.failure is None and mode.code == 0
                and re.fullmatch(rb'100644 blob [0-9a-f]{40}\t' + re.escape(path.encode()) + b'\x00', mode.output))
        captured = p.capture_result(['/usr/bin/git', '--no-replace-objects', '-C', str(ROOT),
                                     'show', env['GITHUB_SHA']+':'+path], env=git_env,
                                    limit=65536, timeout=10)
        require(captured.failure is None and captured.code == 0 and not cancelled[0])
        fd = os.open(ROOT / path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(fd, 'rb') as stream:
            require(stat.S_ISREG(os.fstat(stream.fileno()).st_mode))
            require(stream.read(65537) == captured.output)
        sources[path] = captured.output
    require(hashlib.sha256(sources[WRAPPER]).hexdigest() == WRAPPER_SHA256)
    return sources


# Runs as the existing non-root principal. No remote temp file or sudo fallback.
BOOTSTRAP = r'''
import sys, os, stat, hashlib, json, subprocess, selectors, time, hmac, signal
assert sys.flags.isolated and sys.flags.no_site and sys.dont_write_bytecode
key = sys.stdin.buffer.read(33)
assert len(key) == 32
path = INSTALL_LITERAL
expected = HASH_LITERAL
for name in ('/usr', '/usr/local', '/usr/local/sbin', path):
    s = os.lstat(name)
    assert s.st_uid == 0 and s.st_gid == 0 and stat.S_IMODE(s.st_mode) == 0o755
    assert not stat.S_ISLNK(s.st_mode)
assert stat.S_ISREG(os.lstat(path).st_mode)
fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
with os.fdopen(fd, 'rb') as f:
    assert stat.S_ISREG(os.fstat(f.fileno()).st_mode)
    raw = f.read(65537)
assert hashlib.sha256(raw).hexdigest() == expected
# sudoers independently binds this digest and refuses arguments.
child = subprocess.Popen(['/usr/bin/sudo', '-n', '--', path], stdin=subprocess.DEVNULL,
                         stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                         env={'PATH':'/usr/sbin:/usr/bin:/sbin:/bin','HOME':'/',
                              'LANG':'C','LC_ALL':'C'}, start_new_session=True)
data = bytearray()
deadline = time.monotonic()+330
try:
    with selectors.DefaultSelector() as poll:
        os.set_blocking(child.stdout.fileno(), False)
        poll.register(child.stdout, selectors.EVENT_READ)
        while poll.get_map():
            assert time.monotonic() < deadline
            for item, unused in poll.select(.1):
                chunk = os.read(item.fd, 4096)
                if not chunk: poll.unregister(item.fd)
                else:
                    data.extend(chunk)
                    assert len(data) <= 16384
    code = child.wait(timeout=max(.01, deadline-time.monotonic()))
    body = bytes(data)
    assert body.endswith(b'\n') and body.count(b'\n') == 1
    value = json.loads(body)
    assert value['wrapper_sha256'] == expected
    assert value['schema'] == 'clb175-apt-refresh-v1'
    assert (value['result'] == 'REFRESHED') == (code == 0)
    tag = hmac.new(key, body, hashlib.sha256).hexdigest().encode()
    sys.stdout.buffer.write(b'clb175-auth:v=1 '+tag+b' '+body)
    sys.stdout.buffer.flush()
finally:
    # sudo forwards TERM normally. Root wrapper has an independent hard budget;
    # lost transport can leave outcome unknown, never permission for a retry.
    if child.poll() is None:
        child.terminate()
    child.stdout.close()
sys.exit(code)
'''.replace('INSTALL_LITERAL', repr(INSTALL)).replace('HASH_LITERAL', repr(WRAPPER_SHA256))


def ssh_argv(p, env, reference):
    argv = p.ssh_argv(env, reference)
    argv[0] = '/usr/bin/ssh'
    argv[-1] = ('test "$(/usr/bin/id -un)" = ' + shlex.quote(env['SSH_USER'])
                + ' && test "$(/usr/bin/id -u)" != 0 && LC_ALL=C LANG=C exec '
                + shlex.join(['/usr/bin/python3', '-I', '-S', '-B', '-c', BOOTSTRAP]))
    return argv


def parse(raw, code, key):
    import hmac
    require(len(raw) <= LIMIT)
    match = re.fullmatch(rb'clb175-auth:v=1 ([0-9a-f]{64}) ([^\r\n]+\n)', raw)
    require(match is not None and hmac.compare_digest(
        match[1], hmac.new(key, match[2], hashlib.sha256).hexdigest().encode()))
    def unique(pairs):
        result = {}
        for name, value in pairs:
            require(name not in result)
            result[name] = value
        return result
    body = json.loads(match[2], object_pairs_hook=unique)
    require(type(body) is dict and set(body) == {
        'schema', 'result', 'reason', 'started_ns', 'ended_ns', 'attempts',
        'wrapper_sha256', 'operation_sha256', 'apt_exit_status', 'diagnostics',
        'lists_before', 'lists_after', 'dpkg_before', 'dpkg_after', 'status_before',
        'status_after', 'config_before', 'config_after', 'extended_state_before', 'extended_state_after'})
    require(body['schema'] == 'clb175-apt-refresh-v1' and body['wrapper_sha256'] == WRAPPER_SHA256)
    require(body['result'] in ('REFUSED', 'REFRESHED') and type(body['attempts']) is int and body['attempts'] in (0, 1))
    require(body['reason'] in ('PREFLIGHT', 'UPDATE_FAILED', 'POSTCONDITION_UNAVAILABLE',
                              'PACKAGE_STATE_CHANGED', 'CONFIG_STATE_CHANGED', 'METADATA_REFRESH_COMPLETED'))
    require(type(body['started_ns']) is int and type(body['ended_ns']) is int
            and 0 < body['started_ns'] <= body['ended_ns'])
    digest = lambda value: type(value) is str and re.fullmatch('[0-9a-f]{64}', value) is not None
    require(digest(body['operation_sha256']))
    for name in ('status_before', 'status_after', 'extended_state_before', 'extended_state_after'):
        require(body[name] is None or digest(body[name]) or
                (name.startswith('extended_state') and body[name] == 'ABSENT'))
    for name in ('lists_before', 'lists_after', 'dpkg_before', 'dpkg_after', 'config_before', 'config_after'):
        value = body[name]
        require(value is None or (type(value) is dict and set(value) ==
            {'sha256', 'files', 'bytes', 'latest_mtime_ns'} and digest(value['sha256'])
            and all(type(value[field]) is int and value[field] >= 0
                    for field in ('files', 'bytes', 'latest_mtime_ns'))))
    require(body['apt_exit_status'] is None or type(body['apt_exit_status']) is int)
    value = body['diagnostics']
    require(value is None or (type(value) is dict and set(value) == {'sha256', 'bytes', 'failure'}
        and digest(value['sha256']) and type(value['bytes']) is int and 0 <= value['bytes'] <= 4259840
        and value['failure'] in (None, 'TIMEOUT', 'INTERRUPTED', 'OUTPUT_LIMIT')))
    require((code == 0) == (body['result'] == 'REFRESHED'))
    if code == 0:
        require(body['attempts'] == 1 and body['apt_exit_status'] == 0
                and body['reason'] == 'METADATA_REFRESH_COMPLETED' and body['diagnostics'] is not None
                and body['diagnostics']['failure'] is None
                and body['lists_before'] is not None and body['lists_after'] is not None
                and body['config_before'] is not None and body['config_before'] == body['config_after']
                and body['extended_state_before'] is not None and body['extended_state_before'] == body['extended_state_after']
                and body['dpkg_before'] is not None and body['dpkg_before'] == body['dpkg_after']
                and body['status_before'] is not None and body['status_before'] == body['status_after'])
    return body


def main(env, args):
    cancelled = CANCELLATION
    phase = 'REQUEST'
    try:
        require(sys.flags.isolated and sys.flags.no_site and sys.dont_write_bytecode)
        validate(env)
        require(args in ([], ['--validate']))
        p = load_primitives()
        transport = None
        def stop(*unused):
            cancelled[0] = True
            owner = p if transport is None else transport
            if owner._capture_cancellation is not None:
                owner._capture_cancellation[0] = True
        for sig in p.WATCHED:
            signal.signal(sig, stop)
        sources = snapshot(p, env, cancelled)
        transport = p.load_transport(sources={k: sources[k] for k in p.LOCAL_SOURCES}, cancelled=cancelled)
        if args:
            return {'result': 'VALIDATED'}, 0
        p.validate_target(env)
        nonce = secrets.token_bytes(32)
        phase = 'TRANSPORT_OR_UNKNOWN_OUTCOME'
        with transport.pinned_hosts(env) as (fd, reference):
            captured = transport.capture_result(ssh_argv(p, env, reference), nonce,
                env={'PATH': '/usr/bin:/bin', 'LC_ALL': 'C', 'SSH_AUTH_SOCK': env['SSH_AUTH_SOCK']},
                pass_fds=(fd,), timeout=350, limit=LIMIT)
            require(captured.failure is None and not cancelled[0])
            body = parse(captured.output, captured.code, nonce)
        require(not cancelled[0])
        body['identity'] = {'run_id': env['GITHUB_RUN_ID'], 'attempt': '1', 'sha': env['GITHUB_SHA'],
            'sources_sha256': {k: hashlib.sha256(v).hexdigest() for k, v in sorted(sources.items())}}
        return body, captured.code
    except BaseException:
        return {'schema': 'clb175-local-v1', 'result': 'REFUSED', 'reason': phase}, 1


if __name__ == '__main__':
    body, code = main(dict(os.environ), sys.argv[1:])
    watched = (signal.SIGHUP, signal.SIGINT, signal.SIGTERM)
    signal.pthread_sigmask(signal.SIG_BLOCK, watched)
    if CANCELLATION[0] or set(signal.sigpending()).intersection(watched):
        body, code = {'schema': 'clb175-local-v1', 'result': 'REFUSED', 'reason': 'INTERRUPTED_UNKNOWN_OUTCOME'}, 1
    raw = (json.dumps(body, sort_keys=True, separators=(',', ':'))+'\n').encode('ascii')
    require(len(raw) <= LIMIT and os.write(1, raw) == len(raw))
    raise SystemExit(code)
