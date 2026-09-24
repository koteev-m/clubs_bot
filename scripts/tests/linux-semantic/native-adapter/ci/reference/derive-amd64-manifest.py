#!/usr/bin/env python3
"""Test-image-only translation of the reviewed ARM closure; never approves stage.

Run with isolated Python inside the verified, read-only amd64 fixture image.
Missing or renamed files fail: there is no discovery, version fallback or pruning.
The actual runtime gate and semantic suites must subsequently accept this closure.
"""
import hashlib
import json
import os
from pathlib import Path
import platform
import stat
import sys


def translated(path):
    return path.replace('aarch64-linux-gnu', 'x86_64-linux-gnu').replace(
        'ld-linux-aarch64.so.1', 'ld-linux-x86-64.so.2')


def main():
    assert sys.platform == 'linux' and platform.machine() == 'x86_64'
    assert sys.flags.isolated and sys.flags.no_site and sys.dont_write_bytecode
    assert len(sys.argv) == 2
    reference = json.loads(Path(sys.argv[1]).read_bytes())
    assert reference['architecture'] == 'aarch64' and reference['format'] == 1
    assert reference['compose'] == '5.1.1' and len(reference['files']) == 191
    result = dict(reference, architecture='x86_64', files={}, aliases={})
    for original in sorted(reference['files']):
        path = Path(translated(original))
        assert str(path) == os.path.realpath(path)
        for parent in path.parents:
            metadata = parent.stat()
            assert metadata.st_uid == 0 and not metadata.st_mode & 0o022
        descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(descriptor, 'rb') as stream:
            before = os.fstat(stream.fileno())
            assert stat.S_ISREG(before.st_mode) and before.st_uid == 0
            assert not before.st_mode & 0o022 and before.st_size <= 33554432
            raw = stream.read(33554433)
            assert len(raw) == before.st_size
            assert os.fstat(stream.fileno()) == before == path.stat()
        result['files'][str(path)] = hashlib.sha256(raw).hexdigest()
    for alias, target in reference['aliases'].items():
        assert os.path.realpath(translated(alias)) == translated(target)
        result['aliases'][translated(alias)] = translated(target)
    assert len(result['files']) == len(reference['files'])
    print(json.dumps(result, sort_keys=True, indent=2))


if __name__ == '__main__':
    main()
