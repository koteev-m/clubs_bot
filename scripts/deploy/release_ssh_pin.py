#!/usr/bin/env python3
"""Offline host admission and anonymous pin handoff for ordinary releases only."""
import base64
import binascii
import fcntl
import hashlib
import os
from pathlib import Path
import re
import stat
import sys
import uuid

STAGE_HOST = '178.20.209.5'
STAGE_FINGERPRINT = 'SHA256:zpgtCw+yuSj+kjxAzuBb6TYMuEarfKtsLTg0g6htgTk'
PIN_FD = 18  # Distinct from the shared flock (19) and session protocol (20/21).
KEY_PREFIX = b'\x00\x00\x00\x0bssh-ed25519\x00\x00\x00\x20'


def require(value):
    if not value:
        raise ValueError('pin admission')


def validate(env):
    profile = env.get('APP_ENV')
    host, port, user = (env.get(k, '') for k in ('SSH_HOST', 'SSH_PORT', 'SSH_USER'))
    require(profile in ('stage', 'prod'))
    require(re.fullmatch(r'[A-Za-z0-9_][A-Za-z0-9._-]{0,63}', user))
    require(re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9.:-]{0,252}', host))
    require(re.fullmatch(r'[1-9][0-9]{0,4}', port) and int(port) <= 65535)
    raw = env.get('SSH_KNOWN_HOSTS', '')
    require(isinstance(raw, str) and 0 < len(raw) <= 512)
    match = re.fullmatch(r'([^ \t\r\n]+) ssh-ed25519 ([A-Za-z0-9+/]+={0,2})\n?', raw)
    require(match is not None)
    endpoint = host if port == '22' else f'[{host}]:{port}'
    require(match[1] == endpoint or (port == '22' and match[1] == f'[{host}]:22'))
    key = base64.b64decode(match[2], validate=True)
    require(len(key) == 51 and key.startswith(KEY_PREFIX))
    require(base64.b64encode(key).decode('ascii') == match[2])
    fingerprint = 'SHA256:' + base64.b64encode(hashlib.sha256(key).digest()).decode('ascii').rstrip('=')
    if profile == 'stage':
        require(host == STAGE_HOST and port == '22' and fingerprint == STAGE_FINGERPRINT)
    else:
        # Prod has its own pre-approved Environment secret, never stage's identity.
        require(host != STAGE_HOST and fingerprint != STAGE_FINGERPRINT)
    return f'{endpoint} ssh-ed25519 {match[2]}\n'.encode('ascii')


def check_fd(env):
    pin = validate(env)
    require(sys.platform == 'linux' and env.get('CLUBS_RELEASE_PIN_FD') == str(PIN_FD))
    value = os.fstat(PIN_FD)
    require(stat.S_ISREG(value.st_mode) and value.st_uid == os.geteuid()
            and stat.S_IMODE(value.st_mode) == 0o600 and value.st_nlink == 0)
    require(fcntl.fcntl(PIN_FD, fcntl.F_GETFL) & os.O_ACCMODE == os.O_RDONLY)
    require(os.pread(PIN_FD, len(pin) + 1, 0) == pin)


def bootstrap(env):
    pin = validate(env)
    require(sys.platform == 'linux')
    # Reuse the canonical runner-owned private-root predicate without import/pyc fallback.
    path = Path(__file__).with_name('release_private_root.py')
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd, 'rb') as stream:
        require(stat.S_ISREG(os.fstat(stream.fileno()).st_mode))
        module = {'__name__': 'release_private_root', '__file__': str(path)}
        exec(compile(stream.read(), str(path), 'exec'), module)
    root_fd = module['open_canonical_root'](env.get('RUNNER_TEMP', ''))
    descriptor = None
    try:
        name = '.clubs-release-pin-' + uuid.uuid4().hex
        descriptor = os.open(name, os.O_RDWR | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                             0o600, dir_fd=root_fd)
        os.unlink(name, dir_fd=root_fd)
        view = memoryview(pin)
        while view:
            written = os.write(descriptor, view)
            require(written > 0)
            view = view[written:]
        readonly = os.open(f'/proc/self/fd/{descriptor}', os.O_RDONLY)
        os.close(descriptor)
        descriptor = readonly
        os.dup2(descriptor, PIN_FD, inheritable=True)
        if descriptor != PIN_FD:
            os.close(descriptor)
        descriptor = PIN_FD
        os.close(root_fd)
        root_fd = None
        child_env = dict(env, CLUBS_RELEASE_PIN_FD=str(PIN_FD))
        check_fd(child_env)
        # exec retains PID/FD ownership; no new supervisor or release run identity.
        runner = str(Path(__file__).with_name('quiesced-release.sh'))
        os.execvpe('bash', ['bash', runner, '--pinned-ssh-v1'], child_env)
    finally:
        if descriptor is not None:
            os.close(descriptor)
        if root_fd is not None:
            os.close(root_fd)


def main():
    try:
        require(len(sys.argv) == 2)
        if sys.argv[1] == '--validate':
            validate(os.environ)
            print('release-ssh-pin:PASS')
        elif sys.argv[1] == '--bootstrap':
            bootstrap(os.environ)
        elif sys.argv[1] == '--check-fd':
            check_fd(os.environ)
        else:
            raise ValueError('mode')
        return 0
    except (ValueError, OSError, RuntimeError, binascii.Error):
        # No endpoint, key, private path, user or other environment material in logs.
        print('quiesced-release: trusted SSH pin unavailable', file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
