#!/usr/bin/env -S python3 -I -S -B
"""CLB-194 UID-only observation. Never an independent host/principal attestation."""
import sys
if __name__ == '__main__' and not (sys.flags.isolated and sys.flags.no_site and sys.dont_write_bytecode):
    raise SystemExit('principal-uid:v=1 {"result":"UNKNOWN","reason":"request"}')

import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import shlex
import signal
import stat
import struct
import types

ROOT = Path(__file__).absolute().parents[2]
RUNNER = 'scripts/deploy/stage-principal-uid.py'
WORKFLOW = '.github/workflows/stage-principal-uid.yml'
PRIMITIVE = 'scripts/deploy/stage-runtime-feasibility.py'
PRIMITIVE_SHA = '45598cb97b84158ea0fcb60d7f291eda32e83fda15463fd98f63f7737a2328c1'
CONFIRMATION = 'CLB-194:principal-uid'
PREFIX = b'principal-uid:v=1 '
FRAME_PREFIX = b'clb194-uid-auth:v=1 '
LIMIT = 4096
UID_MAX = 4294967294
WATCHED = (signal.SIGHUP, signal.SIGINT, signal.SIGTERM)
REASONS = frozenset(('none', 'request', 'source', 'target', 'host_pin', 'transport',
                     'protocol', 'platform', 'root', 'identity', 'interrupted', 'cleanup', 'publication'))


def need(ok):
    if not ok:
        raise ValueError()


def unique(pairs):
    value = {}
    for key, item in pairs:
        need(key not in value)
        value[key] = item
    return value


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode('ascii')


def validate(env):
    need(env.get('GITHUB_REPOSITORY') == 'koteev-m/clubs_bot'
         and env.get('GITHUB_EVENT_NAME') == 'workflow_dispatch'
         and env.get('GITHUB_REF') == 'refs/heads/main'
         and env.get('GITHUB_REF_TYPE') == 'branch'
         and env.get('REPOSITORY_DEFAULT_BRANCH') == 'main'
         and env.get('APP_ENV') == 'stage'
         and env.get('CONFIRMATION') == CONFIRMATION
         and env.get('GITHUB_RUN_ATTEMPT') == '1'
         and re.fullmatch('[1-9][0-9]{0,19}', env.get('GITHUB_RUN_ID', ''))
         and re.fullmatch('[0-9a-f]{40}', env.get('GITHUB_SHA', ''))
         and env.get('GITHUB_WORKFLOW_SHA') == env['GITHUB_SHA']
         and env.get('GITHUB_WORKFLOW_REF') == 'koteev-m/clubs_bot/' + WORKFLOW + '@refs/heads/main')


def read_source(path):
    target = ROOT / path
    need(ROOT.resolve() == ROOT and target.resolve() == target)
    fd = os.open(target, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, 'rb') as stream:
        before = os.fstat(stream.fileno())
        need(stat.S_ISREG(before.st_mode) and before.st_nlink == 1 and 0 < before.st_size <= 65536)
        raw = stream.read(65537)
        after = os.fstat(stream.fileno())
        identity = lambda s: (s.st_dev, s.st_ino, s.st_size, s.st_mtime_ns, s.st_ctime_ns)
        need(identity(before) == identity(after) and len(raw) == before.st_size)
    return raw


def load_primitive():
    # The first dependency executes only after its independent fixed-byte pin.
    # Reuse the existing bounded capture/cleanup and whole-closure loader; no copy.
    raw = read_source(PRIMITIVE)
    need(hashlib.sha256(raw).hexdigest() == PRIMITIVE_SHA)
    module = types.ModuleType('uid_capture_primitives')
    module.__file__ = str(ROOT / PRIMITIVE)
    exec(compile(raw, module.__file__, 'exec'), module.__dict__)
    return module


def snapshot(env, primitive, cancelled):
    git_env = dict(PATH=env.get('PATH', os.defpath), LC_ALL='C', GIT_NO_REPLACE_OBJECTS='1',
                   GIT_OPTIONAL_LOCKS='0', GIT_LITERAL_PATHSPECS='1')

    def git(*args, limit=1024):
        need(not cancelled[0])
        result = primitive.capture_result(['git', '--no-replace-objects', '-C', str(ROOT), *args],
                                          env=git_env, timeout=10, limit=limit)
        need(not cancelled[0] and result.failure is None and result.code == 0)
        return result.output

    sha = env['GITHUB_SHA']
    need(git('rev-parse', 'HEAD') == (sha + '\n').encode())
    legacy = (*primitive.LOCAL_SOURCES, primitive.REMOTE_PATH)
    sources = {}
    for path in (*legacy, RUNNER, WORKFLOW):
        entry = git('ls-tree', '-z', sha, '--', path)
        match = re.fullmatch(rb'100644 blob ([0-9a-f]{40})\t' + re.escape(path.encode()) + b'\x00', entry)
        need(match is not None)
        raw = git('cat-file', 'blob', match[1].decode(), limit=65536)
        need(hashlib.sha1(f'blob {len(raw)}\0'.encode() + raw).hexdigest().encode() == match[1])
        need(read_source(path) == raw)
        sources[path] = raw
    need(hashlib.sha256(sources[PRIMITIVE]).hexdigest() == PRIMITIVE_SHA)
    transport = primitive.load_transport({path: sources[path] for path in legacy}, cancelled)
    return sources, transport


def validate_target(env):
    user = env.get('SSH_USER', '')
    need(type(user) is str and re.fullmatch('[a-zA-Z0-9_][a-zA-Z0-9._-]{0,63}', user)
         and user not in ('root', 'hookah-staging')
         and env.get('SSH_HOST') == '178.20.209.5' and env.get('SSH_PORT') == '22'
         and type(env.get('SSH_AUTH_SOCK')) is str and env['SSH_AUTH_SOCK'].startswith('/')
         and len(env['SSH_AUTH_SOCK']) <= 4096)


def validate_pin(env):
    pin = env.get('SSH_KNOWN_HOSTS', '')
    need(type(pin) is str and len(pin) <= 1024)
    match = re.fullmatch(r'(?:178\.20\.209\.5|\[178\.20\.209\.5\]:22) ssh-ed25519 ([A-Za-z0-9+/]+={0,2})\n?', pin)
    need(match is not None)
    key = base64.b64decode(match[1], validate=True)
    need(key.startswith(b'\x00\x00\x00\x0bssh-ed25519\x00\x00\x00\x20') and len(key) == 51)
    need(base64.b64encode(hashlib.sha256(key).digest()).decode().rstrip('=') ==
         'Li2AIDm9/OG8CHWQw16qhDfzbRM7E9uLNjPeKOZ9ST0')


BOOTSTRAP = r'''
import sys, os, pwd, json, struct, hashlib, hmac, signal
def stop(*unused): raise InterruptedError()
for sig in (signal.SIGHUP,signal.SIGINT,signal.SIGTERM,signal.SIGALRM): signal.signal(sig,stop)
signal.alarm(20)
def unique(pairs):
    result={}
    for k,v in pairs:
        assert k not in result
        result[k]=v
    return result
data=sys.stdin.buffer.read(1025)
assert 36<len(data)<=1024
key,size=data[:32],struct.unpack('!I',data[32:36])[0]
assert 0<size<=988 and len(data)==36+size
control=json.loads(data[36:],object_pairs_hook=unique)
assert set(control)=={'principal'} and type(control['principal']) is str
body={'account_match':'UNKNOWN','non_root':'UNKNOWN','uid':None,'reason':'identity'}
try:
    if sys.platform!='linux' or not (sys.flags.isolated and sys.flags.no_site and sys.dont_write_bytecode):
        body['reason']='platform'
    else:
        uid=os.getuid(); euid=os.geteuid()
        if uid==0 or euid==0:
            body.update(non_root='FAIL',reason='root')
        elif uid!=euid or not 0<uid<=4294967294:
            body.update(non_root='FAIL',reason='identity')
        elif pwd.getpwuid(uid).pw_name!=control['principal']:
            body.update(account_match='FAIL',non_root='PASS',reason='identity')
        else:
            body.update(account_match='PASS',non_root='PASS',uid=uid,reason='none')
except InterruptedError: body['reason']='interrupted'
except BaseException: pass
raw=json.dumps(body,sort_keys=True,separators=(',',':')).encode('ascii')
frame=b'clb194-uid-auth:v=1 '+hmac.new(key,raw,hashlib.sha256).hexdigest().encode()+b' '+raw+b'\n'
assert len(frame)<=4096
signal.alarm(0)
assert os.write(1,frame)==len(frame)
sys.exit(0 if body['reason']=='none' else 1)
'''


def ssh_argv(env, reference):
    # Account name travels privately in stdin for comparison, never in remote code.
    command = shlex.join(['/usr/bin/env', '-i', 'PATH=/usr/bin:/bin', 'LC_ALL=C',
                          '/usr/bin/python3', '-I', '-S', '-B', '-c', BOOTSTRAP])
    options = {'BatchMode': 'yes', 'PreferredAuthentications': 'publickey',
               'PasswordAuthentication': 'no', 'KbdInteractiveAuthentication': 'no',
               'IdentityFile': 'none', 'CertificateFile': 'none', 'NumberOfPasswordPrompts': '0',
               'StrictHostKeyChecking': 'yes', 'HostKeyAlgorithms': 'ssh-ed25519',
               'UserKnownHostsFile': reference, 'GlobalKnownHostsFile': '/dev/null',
               'KnownHostsCommand': 'none', 'VerifyHostKeyDNS': 'no', 'UpdateHostKeys': 'no',
               'ProxyCommand': 'none', 'ProxyJump': 'none', 'PermitLocalCommand': 'no',
               'CanonicalizeHostname': 'no', 'ForwardAgent': 'no', 'ForwardX11': 'no',
               'ClearAllForwardings': 'yes', 'ControlMaster': 'no', 'ControlPersist': 'no',
               'ControlPath': 'none', 'RequestTTY': 'no', 'RemoteCommand': 'none',
               'ConnectTimeout': '15', 'ConnectionAttempts': '1',
               'ServerAliveInterval': '5', 'ServerAliveCountMax': '2'}
    argv = ['ssh', '-T', '-a', '-x', '-S', 'none', '-F', '/dev/null', '-p', '22']
    for key, value in options.items(): argv += ['-o', key + '=' + value]
    return argv + ['--', env['SSH_USER'] + '@178.20.209.5', command]


def parse_result(raw, code, key):
    need(type(raw) is bytes and len(raw) <= LIMIT and type(code) is int and code in (0, 1))
    match = re.fullmatch(re.escape(FRAME_PREFIX) + rb'([0-9a-f]{64}) ([^\r\n]+)\n', raw)
    need(match is not None and hmac.compare_digest(match[1], hmac.new(key, match[2], hashlib.sha256).hexdigest().encode()))
    value = json.loads(match[2], object_pairs_hook=unique)
    need(canonical(value) == match[2] and set(value) == {'account_match', 'non_root', 'uid', 'reason'})
    need(value['account_match'] in ('PASS', 'FAIL', 'UNKNOWN') and value['non_root'] in ('PASS', 'FAIL', 'UNKNOWN'))
    need(value['reason'] in ('none', 'platform', 'root', 'identity', 'interrupted'))
    if value['reason'] == 'none':
        need(code == 0 and value['account_match'] == value['non_root'] == 'PASS'
             and type(value['uid']) is int and 0 < value['uid'] <= UID_MAX)
    else:
        need(code == 1 and value['uid'] is None)
        need(not (value['account_match'] == value['non_root'] == 'PASS'))
        if value['reason'] == 'root': need(value['non_root'] == 'FAIL')
    return value


def record(env, reason, remote=None):
    value = dict(version=1, result='UNKNOWN', reason=reason, selection='UNKNOWN',
                 account_match='UNKNOWN', non_root='UNKNOWN', uid=None,
                 startup_trust='UNKNOWN', independent_host_binding='UNKNOWN',
                 revision=None, run_id=None, attempt=None)
    try:
        validate(env)
        value.update(revision=env['GITHUB_SHA'], run_id=env['GITHUB_RUN_ID'], attempt=1)
    except BaseException: pass
    if remote is not None:
        value.update(selection='PASS', account_match=remote['account_match'], non_root=remote['non_root'], uid=remote['uid'])
        value['result'] = 'PASS' if reason == 'none' else 'FAIL' if 'FAIL' in (remote['account_match'], remote['non_root']) else 'UNKNOWN'
    return value


def publication(value, env):
    # Predict masking of significant fields against supplied protected inputs.
    # Never encode, split, hash or reconstruct a masked field to evade GitHub.
    meaningful = [str(value[k]) for k in ('revision', 'run_id', 'attempt', 'uid') if value[k] is not None]
    secrets = [env.get(k, '') for k in ('SSH_USER', 'SSH_HOST', 'SSH_PORT', 'SSH_KNOWN_HOSTS')]
    if any(secret and secret in field for secret in secrets for field in meaningful):
        value = record({}, 'publication')
    line = PREFIX + canonical(value) + b'\n'
    need(len(line) <= LIMIT)
    return line, 0 if value['result'] == 'PASS' else 1


def parse_public(raw, expected):
    # Persisted logs are independently matched to the authenticated run/attempt.
    # Masked bytes cannot be evidence, including when the runner could not predict it.
    need(type(raw) is bytes and len(raw) <= LIMIT)
    if b'***' in raw: return record({}, 'publication')
    need(raw.startswith(PREFIX) and raw.endswith(b'\n'))
    value = json.loads(raw[len(PREFIX):-1], object_pairs_hook=unique)
    template = record({}, 'request')
    need(set(value) == set(template) and PREFIX + canonical(value) + b'\n' == raw)
    need(type(value['version']) is int and value['version'] == 1 and value['reason'] in REASONS)
    need(all(value[k] in ('PASS', 'FAIL', 'UNKNOWN') for k in
             ('result', 'selection', 'account_match', 'non_root', 'startup_trust', 'independent_host_binding')))
    need(value['startup_trust'] == value['independent_host_binding'] == 'UNKNOWN')
    if all(value[k] is None for k in ('revision', 'run_id', 'attempt')):
        need(value == record({}, value['reason']))
        return value
    need(all(value[k] == expected[k] and type(value[k]) is type(expected[k]) for k in ('revision', 'run_id', 'attempt')))
    if value['result'] == 'PASS':
        need(value['reason'] == 'none' and value['selection'] == value['account_match'] == value['non_root'] == 'PASS'
             and type(value['uid']) is int and 0 < value['uid'] <= UID_MAX)
    else: need(value['uid'] is None)
    return value


def main(env, args, cancelled):
    phase = 'request'; primitive = transport = None
    def stop(*unused):
        cancelled[0] = True
        latched = False
        for module in (primitive, transport):
            active = getattr(module, '_capture_cancellation', None)
            if active is not None: active[0] = True; latched = True
        if not latched: raise InterruptedError()
    try:
        validate(env); need(args in ([], ['--validate']))
        for sig in WATCHED: signal.signal(sig, stop)
        phase = 'source'; primitive = load_primitive()
        sources, transport = snapshot(env, primitive, cancelled)
        if args == ['--validate']:
            return b'principal-uid-validation:v=1 result=PASS\n', 0
        phase = 'target'; validate_target(env)
        phase = 'host_pin'; validate_pin(env)
        key = os.urandom(32)
        control = canonical(dict(principal=env['SSH_USER']))
        payload = key + struct.pack('!I', len(control)) + control
        need(len(payload) <= 1024 and not cancelled[0])
        with transport.pinned_hosts(env) as (fd, reference):
            os.lseek(fd, 0, os.SEEK_SET)
            child_env = {k: env[k] for k in ('PATH', 'SSH_AUTH_SOCK') if k in env}; child_env['LC_ALL'] = 'C'
            phase = 'transport'
            captured = transport.capture_result(ssh_argv(env, reference), payload, env=child_env,
                                                 timeout=30, limit=LIMIT, pass_fds=(fd,))
            need(captured.failure is None and not cancelled[0])
            phase = 'protocol'; remote = parse_result(captured.output, captured.code, key)
            phase = 'cleanup'
        need(not cancelled[0])
        # Refuse changed checkout/source bytes before public evidence.
        phase = 'source'
        need(all(read_source(path) == raw for path, raw in sources.items()))
        head = primitive.capture_result(['git', '--no-replace-objects', '-C', str(ROOT), 'rev-parse', 'HEAD'],
                                        timeout=10, limit=1024,
                                        env=dict(PATH=env.get('PATH', os.defpath), LC_ALL='C', GIT_NO_REPLACE_OBJECTS='1', GIT_OPTIONAL_LOCKS='0'))
        need(head.failure is None and head.code == 0 and head.output == (env['GITHUB_SHA'] + '\n').encode()
             and not cancelled[0])
        return publication(record(env, remote['reason'], remote), env)
    except BaseException:
        return publication(record(env, 'interrupted' if cancelled[0] else phase), env)


if __name__ == '__main__':
    os.umask(0o077)
    cancelled = [False]
    try:
        line, code = main(os.environ.copy(), sys.argv[1:], cancelled)
        signal.pthread_sigmask(signal.SIG_BLOCK, WATCHED)
        if cancelled[0] or set(signal.sigpending()).intersection(WATCHED):
            line, code = publication(record({}, 'interrupted'), {})
        need(os.write(1, line) == len(line))
    except BaseException: code = 1
    sys.exit(code)
