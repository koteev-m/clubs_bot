#!/usr/bin/env python3
"""Fixed test inputs only. Network preparation, never a stage installer."""
import hashlib
import json
import io
import lzma
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.request

HERE = Path(__file__).resolve().parent
SNAPSHOT = 'https://snapshot.ubuntu.com/ubuntu/20260921T200000Z/'


def checked(data, digest, label):
    actual = hashlib.sha256(data).hexdigest()
    if actual != digest:
        raise ValueError(f'{label}: expected {digest}, actual {actual}')
    return data


def download(url, limit):
    if not url.startswith('https://'):
        raise ValueError('HTTPS required')
    with urllib.request.urlopen(url, timeout=60) as response:
        if not response.url.startswith('https://'):
            raise ValueError('HTTPS redirect required')
        data = response.read(limit + 1)
    if len(data) > limit:
        raise ValueError('download size bound')
    return data


def signed_hashes(data):
    # Only SHA256 entries in the gpgv-verified clear-signed Release body.
    section = data.decode().split('\nSHA256:\n', 1)[1].split('\nSHA512:', 1)[0]
    return {path: (digest, int(size)) for digest, size, path in
            re.findall(r'^ ([0-9a-f]{64})\s+(\d+) (\S+)$', section, re.M)}


def unpack_index(compressed, expected_size, expected_digest):
    # Ubuntu may concatenate XZ streams. Process all of them, with a full-output
    # bound and exact signed size/hash; never accept only the first stream.
    with lzma.LZMAFile(io.BytesIO(compressed)) as stream:
        data = stream.read(128 * 2**20 + 1)
    if len(data) > 128 * 2**20 or len(data) != expected_size:
        raise ValueError('index decompression/size bound')
    return checked(data, expected_digest, 'uncompressed index')


def package_rows(data):
    for stanza in data.decode().split('\n\n'):
        yield dict(line.split(': ', 1) for line in stanza.splitlines()
                   if ': ' in line and not line.startswith((' ', '\t')))


def main():
    destination = Path(sys.argv[1]).resolve()
    destination.mkdir(mode=0o700)  # Existing destination is an error.
    metadata = destination / 'metadata'
    metadata.mkdir()
    (destination / 'debs').mkdir()
    lock = json.loads((HERE / 'amd64-inputs.json').read_bytes())
    # Digest-pinned official base is also the independent signing-key source.
    subprocess.run(['docker', 'pull', '--platform', 'linux/amd64', lock['base']], check=True)
    subprocess.run(['docker', 'pull', lock['cosign_verifier']], check=True)
    key = subprocess.run(['docker', 'run', '--rm', '--platform', 'linux/amd64',
        '--network=none', '--read-only', '--cap-drop=ALL', '--security-opt=no-new-privileges',
        '--user', '1000:1000', lock['base'], 'cat',
        '/usr/share/keyrings/ubuntu-archive-keyring.gpg'], check=True, capture_output=True).stdout
    (metadata / 'keyring.gpg').write_bytes(checked(key, lock['ubuntu_keyring_sha256'], 'keyring'))
    releases = {}
    packages = {}
    for row in lock['indexes']:
        name = row['release']
        if name not in releases:
            url = SNAPSHOT + row['release_url'].split('/ubuntu/', 1)[1]
            body = checked(download(url, 2**20), row['release_sha256'], name)
            (metadata / name).write_bytes(body)
            result = subprocess.run(['docker', 'run', '--rm', '--platform', 'linux/amd64',
                '--network=none', '--read-only', '--cap-drop=ALL', '--security-opt=no-new-privileges',
                '--user', f'{os.getuid()}:{os.getgid()}', '--tmpfs', '/tmp:mode=1777',
                '--mount', f'type=bind,src={metadata},dst=/metadata,readonly', lock['base'],
                'gpgv', '--homedir', '/tmp', '--status-fd', '1', '--keyring',
                '/metadata/keyring.gpg', '/metadata/' + name],
                check=True, capture_output=True, timeout=30)
            if ('[GNUPG:] VALIDSIG ' + lock['ubuntu_signer'] + ' ').encode() not in result.stdout:
                raise ValueError('unexpected Ubuntu signer')
            releases[name] = signed_hashes(body)
        relative = row['url'].split('/dists/', 1)[1].split('/', 1)[1]
        hashes = releases[name]
        if hashes[relative][0] != row['sha256']:
            raise ValueError('locked index is absent from signed Release')
        compressed = download(SNAPSHOT + row['url'].split('/ubuntu/', 1)[1] + '.xz', 64 * 2**20)
        checked(compressed, hashes[relative + '.xz'][0], row['name'] + '.xz')
        data = unpack_index(compressed, hashes[relative][1], row['sha256'])
        for item in package_rows(data):
            if 'Filename' in item:
                packages[(item.get('Package'), item.get('Version'), item.get('Architecture'))] = item
    for row in lock['packages']:
        item = packages[(row['package'], row['version'], row['architecture'])]
        if (item['SHA256'], item['Filename']) != (row['sha256'], row['archive_path']):
            raise ValueError('package does not match signed index: ' + row['package'])
        url = SNAPSHOT + row['archive_path']
        data = checked(download(url, 64 * 2**20), row['sha256'], row['file'])
        if len(data) != int(item['Size']):
            raise ValueError('package size mismatch')
        (destination / 'debs' / row['file']).write_bytes(data)
    for name, digest in lock['compose']['assets'].items():
        url = 'https://github.com/docker/compose/releases/download/v5.1.1/' + name
        (destination / name).write_bytes(checked(download(url, 64 * 2**20), digest, name))
    subprocess.run(['docker', 'run', '--rm', '--read-only', '--cap-drop=ALL',
        '--security-opt=no-new-privileges', '--user', f'{os.getuid()}:{os.getgid()}',
        '--tmpfs', '/tmp:mode=1777', '--env', 'HOME=/tmp',
        '--mount', f'type=bind,src={destination},dst=/artifacts,readonly',
        lock['cosign_verifier'], 'verify-blob-attestation', '--new-bundle-format',
        '--type', 'slsaprovenance1', '--bundle', '/artifacts/docker-compose-linux-x86_64.sigstore.json',
        '--certificate-identity', lock['compose']['signer'],
        '--certificate-oidc-issuer', lock['compose']['issuer'],
        '/artifacts/docker-compose-linux-x86_64'], check=True, timeout=180)
    print('verified four signed Releases, 15 indexes, 61 packages and Compose provenance')


if __name__ == '__main__':
    main()
