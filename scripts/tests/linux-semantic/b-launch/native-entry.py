"""One fixed future disposable-native entrypoint; no sudo or external command."""
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import sys
import types

NAMES = ('native-entry.py', 'native-protocol.py', 'native-harness.py', 'prototype.py')
BASE = '34460528411d377528dd963f0e482ee06a861afe'
TREE = '3101ddd39e3258f144ca586b1b95841d4d987a0f'
ROOT_SOURCE = '/opt/clb91-b-native-sources'


def need(value):
    if not value:
        raise ValueError() from None


def unique(pairs):
    result = {}
    for key, value in pairs:
        need(key not in result)
        result[key] = value
    return result


def identity(info):
    return (info.st_dev, info.st_ino, info.st_uid, info.st_gid, info.st_mode,
            info.st_nlink, info.st_size, info.st_mtime_ns, info.st_ctime_ns)


def sources(directory, expected, native=False):
    """Capture exact no-follow public source bytes; execute only these buffers."""
    need(type(expected) is str and re.fullmatch('[0-9a-f]{64}', expected))
    directory = Path(directory)
    need(directory.is_absolute() and directory == directory.resolve())
    if native:
        need(str(directory) == ROOT_SOURCE)
        for path in (directory, *directory.parents):
            info = path.stat()
            need(stat.S_ISDIR(info.st_mode) and info.st_uid == 0 and not info.st_mode & 0o022)
    fd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    initial = identity(os.fstat(fd))
    def read(name, bound):
        handle = os.open(name, os.O_RDONLY | os.O_NONBLOCK | os.O_NOFOLLOW, dir_fd=fd)
        try:
            info = os.fstat(handle)
            need(stat.S_ISREG(info.st_mode) and info.st_nlink == 1 and info.st_size <= bound)
            need(info.st_uid in (0, os.geteuid()) and not info.st_mode & 0o022)
            data = bytearray()
            while len(data) <= bound:
                part = os.read(handle, min(65536, bound+1-len(data)))
                if not part: break
                data.extend(part)
            need(len(data) == info.st_size and len(data) <= bound)
            need(identity(os.fstat(handle)) == identity(info))
            need(identity(os.stat(name, dir_fd=fd, follow_symlinks=False)) == identity(info))
            return bytes(data)
        finally: os.close(handle)
    try:
        raw = read('native-sources.json', 4096)
        need(hashlib.sha256(raw).hexdigest() == expected)
        contract = json.loads(raw, object_pairs_hook=unique)
        need(type(contract) is dict and set(contract) == {'format', 'base', 'tree', 'files'})
        need(type(contract['format']) is int and contract['format'] == 1)
        need(contract['base'] == BASE and contract['tree'] == TREE)
        need(type(contract['files']) is dict and set(contract['files']) == set(NAMES))
        result = {}
        for name in NAMES:
            value = read(name, 131072)
            need(contract['files'][name] == hashlib.sha256(value).hexdigest())
            result[name] = value
        need(identity(os.fstat(fd)) == initial and identity(directory.stat()) == initial)
        return result
    finally: os.close(fd)


def module(name, raw):
    value = types.ModuleType(name)
    sys.modules[name] = value
    exec(compile(raw, '<verified-'+name+'>', 'exec'), value.__dict__)
    return value


def arguments(argv):
    need(type(argv) is list and len(argv) in (4, 5))
    need(argv[:1] == ['--source-contract'] and argv[2:3] == ['--revision'])
    need(re.fullmatch('[0-9a-f]{64}', argv[1]) and re.fullmatch('[0-9a-f]{40}', argv[3]))
    need(len(argv) == 4 or argv[4] == '--execute-disposable-native')
    return argv[1], argv[3], len(argv) == 5


def main(argv):
    # Even source/preflight errors have a single fixed, bounded public fallback.
    try:
        digest, revision, authorized = arguments(argv)
        native_source = authorized and sys.platform == 'linux' and os.geteuid() == 0
        buffers = sources(Path(__file__).resolve().parent, digest, native=native_source)
        R = module('clb91_b_protocol', buffers['native-protocol.py'])
        report = R.new(revision, digest)
        report['prerequisites']['source'] = 'PASS'
        if not authorized:
            raw = R.encode(report)
            need(os.write(1, raw) == len(raw))
            return 2
        P = module('clb91_b_prototype', buffers['prototype.py'])
        H = module('clb91_b_native', buffers['native-harness.py'])
        H.P, H.R = P, R
        H.run(report)
        raw = R.encode(report)
        need(os.write(1, raw) == len(raw))
        return 0 if report['verdict'] == 'PASS' else 1
    except BaseException:
        try: os.write(1, b'{"format":1,"verdict":"FAIL","reason":"entry_refused","native":"UNCONFIRMED"}\n')
        except BaseException: pass
        return 1


if __name__ == '__main__':
    raise SystemExit(main(sys.argv[1:]))
