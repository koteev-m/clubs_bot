#!/usr/bin/env python3
"""Verify locked local downloads and copy a fresh offline test build context.

No download, installation, container invocation or signature-policy bypass.
The README's independent Sigstore verification is also required before building.
"""
import hashlib
import json
from pathlib import Path
import shutil
import sys


HERE = Path(__file__).resolve().parent


def main():
    if len(sys.argv) != 3:
        raise SystemExit('usage: prepare-amd64-context.py DOWNLOADS NEW_CONTEXT')
    source, destination = map(Path, sys.argv[1:])
    if destination.exists():
        raise SystemExit('context must not exist; no overwrite permitted')
    lock = json.loads((HERE / 'amd64-inputs.json').read_bytes())
    expected = dict(lock['compose']['assets'])
    expected.update({'debs/' + row['file']: row['sha256'] for row in lock['packages']})
    for name, digest in expected.items():
        path = source / name
        if path.is_symlink() or not path.is_file():
            raise SystemExit('missing or symlink input: ' + name)
        if hashlib.sha256(path.read_bytes()).hexdigest() != digest:
            raise SystemExit('input checksum mismatch: ' + name)
    provenance = json.loads((source / 'docker-compose-linux-x86_64.provenance.json').read_bytes())
    if provenance['subject'] != [{'name': 'docker-compose-linux-x86_64',
                                 'digest': {'sha256': lock['compose']['sha256']}}]:
        raise SystemExit('Compose provenance subject mismatch')
    if provenance['predicate']['buildDefinition']['externalParameters']['configSource'] != {
        'uri': 'https://github.com/docker/compose.git#refs/tags/v5.1.1',
        'digest': {'sha1': lock['compose']['source_commit']}, 'path': 'Dockerfile',
    }:
        raise SystemExit('Compose provenance source mismatch')
    destination.mkdir(mode=0o700)
    (destination / 'debs').mkdir()
    names = sorted(name for name in expected if name.startswith('debs/'))
    names.append('docker-compose-linux-x86_64')
    for name in names:
        shutil.copyfile(source / name, destination / name)
        if hashlib.sha256((destination / name).read_bytes()).hexdigest() != expected[name]:
            raise SystemExit('copied input checksum mismatch: ' + name)
    sums = ''.join(expected[name] + '  ' + name + '\n' for name in names)
    if hashlib.sha256(sums.encode()).hexdigest() != lock['input_sums_sha256']:
        raise SystemExit('build input inventory mismatch')
    (destination / 'SHA256SUMS').write_text(sums)
    shutil.copyfile(HERE / 'Dockerfile.amd64', destination / 'Dockerfile')
    print('verified offline amd64 context; signature verification remains a separate prerequisite')


if __name__ == '__main__':
    main()
