#!/usr/bin/env python3
"""CLB-191 LOCAL/TEST ONLY. Synthetic bytes only; no stage capture/transport.

Launch via python3 -I -S -B -c <these exact bytes>, as in README. This keeps
source modules out of sys.modules, matching the existing captured-source loader.
The container supervisor and kernel are trusted test infrastructure, not attested.
"""
import hashlib
import json
import os
from pathlib import Path
import signal
import stat
import sys
import types

PINS = {'stage-compose-diagnostic-operation.py': '2487c9b9d82de02ab2df9d11bd54c45c75e423d595079c9cf3ab2e10890ee96c', 'stage-compose-env-file-plan.py': '1317a5a89323b0d3296e8678dd1a8f7a668aa91cc5594c066d93bad1919a6cd5', 'release_private_root.py': '250d359d114779ddcc12c38bc64dc7015d5679f0ffce12a859f007810726cdba', 'stage-compose-env-semantic-operation.py': 'e8e89bf69d89fb44c0c113b21f7ce573e8a22782bf77cb0d7e91ac661224f49f', 'stage-compose-env-semantic-runtime.json': '7ec2c9354972024933677eaf752768820d17577f6fd5fd3491c2d7ce85b7b342'}
LIMIT = 196608
FIELDS = ['format', 'base', 'dotenv', 'override', 'interpolation']
TEMP = '/run/user/1000'
CANONICAL = '/opt/clubs-bot-stage'


class Refused(Exception):
    pass


def need(ok):
    if not ok:
        raise Refused()


def unique(pairs):
    result = {}
    for key, value in pairs:
        need(key not in result)
        result[key] = value
    return result


def parse(raw):
    need(type(raw) is bytes and 0 < len(raw) <= LIMIT)
    data = json.loads(raw, object_pairs_hook=unique)
    need(type(data) is dict and list(data) == FIELDS)
    need(type(data['format']) is int and data['format'] == 1)
    for name in ('base', 'dotenv', 'override'):
        need(type(data[name]) is str and 0 < len(data[name].encode()) <= 65536)
    env = data['interpolation']
    need(type(env) is dict and len(env) <= 256)
    need(all(type(k) is str and type(v) is str for k, v in env.items()))
    return data


def sources(root):
    need(root.is_absolute() and str(root) == os.path.realpath(root))
    raw = {}
    for name, digest in PINS.items():
        path = root / name
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        try:
            before = os.fstat(fd)
            need(stat.S_ISREG(before.st_mode) and 0 < before.st_size <= 65536)
            data = bytearray()
            while len(data) <= 65536:
                part = os.read(fd, min(65536, 65537 - len(data)))
                if not part:
                    break
                data.extend(part)
            identity = lambda s: (s.st_dev, s.st_ino, s.st_size, s.st_mtime_ns, s.st_ctime_ns)
            need(identity(before) == identity(os.fstat(fd)) == identity(os.lstat(path)))
            need(len(data) == before.st_size and hashlib.sha256(data).hexdigest() == digest)
            raw[name] = bytes(data)
        finally:
            os.close(fd)
    # Every source hash precedes the first compile/exec. No sys.path loader.
    compiled = {n: compile(b, '/clb191-source/scripts/deploy/' + n, 'exec')
                for n, b in raw.items() if n.endswith('.py')}
    modules = {}
    for name, code in compiled.items():
        module = types.ModuleType('clb191_captured')
        module.__file__ = '/clb191-source/scripts/deploy/' + name
        exec(code, module.__dict__)
        modules[name] = module
    d = modules['stage-compose-diagnostic-operation.py']
    p = modules['stage-compose-env-file-plan.py']
    s = modules['release_private_root.py']
    op = modules['stage-compose-env-semantic-operation.py']
    op.D, op.P, op.S = d, p, s
    op.MANIFEST = json.loads(raw['stage-compose-env-semantic-runtime.json'])
    p.DIAGNOSTIC, p.CAPTURE, p.SAFE_ROOT = vars(d), d.capture_result, s.open_canonical_root
    return op


def evaluate(op, request):
    p = op.P
    data = parse(request)
    result = p.prepare(*(data[n].encode() for n in ('base', 'dotenv', 'override')),
        interpolation=data['interpolation'], project='clb191-synthetic',
        compose=op.COMPOSE, temporary_root=TEMP, canonical_directory=CANONICAL)
    # The private candidate/model never enters the result or an artifact.
    return result.public()


def execute(op, reader, cancelled):
    runtime = op.Runtime(cancelled)
    try:
        runtime.open()
        runtime.private_root(TEMP)
        runtime.private_root(CANONICAL)
        runtime.require_compatible()
        runtime.ruby_probe(TEMP)
        runtime.recheck()
        # No input read/parse/semantic work before the complete runtime gate.
        result = evaluate(op, reader())
        runtime.recheck()
        return result
    finally:
        runtime.close()


def read_input(fd=0):
    value = os.fstat(fd)
    # The supervisor owns the FIFO; caller retains ownership until process exit.
    need(stat.S_ISFIFO(value.st_mode) and value.st_uid in (0, os.getuid()))
    data = bytearray()
    while len(data) <= LIMIT:
        part = os.read(fd, min(65536, LIMIT + 1 - len(data)))
        if not part:
            break
        data.extend(part)
    need(len(data) <= LIMIT)
    return bytes(data)


def main():
    op = None
    cancelled = [False]
    def stop(*unused):
        cancelled[0] = True
        if op is not None:
            op.D._capture_cancellation[0] = True
        raise InterruptedError()
    for sig in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP, signal.SIGALRM):
        signal.signal(sig, stop)
    signal.alarm(30)
    code, line = 1, 'clb191-local:v=1 result=refused'
    try:
        need(sys.platform == 'linux' and sys.flags.isolated and sys.flags.no_site and os.getuid() == 1000)
        need(set(os.environ) <= {'PATH', 'LC_ALL'})
        need(os.environ.get('PATH') == '/usr/bin:/bin' and os.environ.get('LC_ALL') == 'C')
        op = sources(Path('/source/scripts/deploy'))
        line = execute(op, read_input, lambda: cancelled[0])
        code = 0
    except BaseException as failure:
        # Only a fixed semantic distinction is public; all other failures refuse.
        if op is not None and isinstance(failure, op.P.Refused) and failure.reason == 'different':
            line = 'clb191-local:v=1 result=different'
    finally:
        signal.alarm(0)
    need(type(line) is str and len(line) <= 512)
    os.write(1, (line + '\n').encode('ascii'))
    return code


if __name__ == '__main__':
    sys.exit(main())
