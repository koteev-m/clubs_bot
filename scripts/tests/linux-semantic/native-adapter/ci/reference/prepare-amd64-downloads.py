#!/usr/bin/env python3
"""Fixed authenticated test inputs; never a resolver or stage installer."""
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
SOURCES = {'default': SNAPSHOT,
           'expat-security': 'https://snapshot.ubuntu.com/ubuntu/20260930T120000Z/',
           'openssl-security': 'https://snapshot.ubuntu.com/ubuntu/20260930T120000Z/'}
UBUNTU_SIGNER = 'F6ECB3762474EDA9D21B7022871920D1991BC93C'
SUPPLEMENTAL_INDEX = {
    'source': 'openssl-security',
    'name': 'security.ubuntu.com_ubuntu_dists_noble-security_main_binary-amd64_Packages',
    'release': 'security.ubuntu.com_ubuntu_dists_noble-security_InRelease',
    'release_url': 'https://security.ubuntu.com/ubuntu/dists/noble-security/InRelease',
    'release_sha256': 'ba88a6da0b679ab57efe1b2ec88f3451d877cb4c01eaa112965fe7aedd77a8bf',
    'url': 'https://security.ubuntu.com/ubuntu/dists/noble-security/main/binary-amd64/Packages',
    'sha256': 'abfa0e7c57b579cf87e09b90501306dfb1bffcfbd4fee5be1c775a39adf1bd74',
    'bytes': 5797350,
    'compressed_sha256': 'b9aa52a2bd0e42b0976036f59251c2bf9c76b20d7c6be96405b6290f38dff61f',
    'compressed_bytes': 1066176,
}
OPENSSL_PACKAGES = {
    'libssl3t64': (1945300, '219f43b1cd836a4da550938db5fda93160d269d39a4edcf1f1ce698a470db797'),
    'openssl': (1003702, '675b84971ffd4467707008c25ef7520f90ea7c23ef27b7a76b0dccf1d7c4dc3f'),
}
OPENSSL_VERSION = '3.0.13-0ubuntu3.16'
EXPAT_VERSION = '2.6.1-2ubuntu0.6'
EXPAT_PACKAGE = (98940, '494b8e672f722130c6bca6a7bc4cc31a43ca891a31d60d868bfdd699a3c20b13')
EXPAT_INDEX = dict(SUPPLEMENTAL_INDEX, source='expat-security')


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
    # Call only after gpgv verified the clear-signed Release and expected signer.
    section = data.decode().split('\nSHA256:\n', 1)[1].split('\nSHA512:', 1)[0]
    return {path: (digest, int(size)) for digest, size, path in
            re.findall(r'^ ([0-9a-f]{64})\s+(\d+) (\S+)$', section, re.M)}


def unpack_index(compressed, expected_size, expected_digest):
    # Include every concatenated XZ stream, bounded by authenticated size/hash.
    with lzma.LZMAFile(io.BytesIO(compressed)) as stream:
        data = stream.read(128 * 2**20 + 1)
    if len(data) > 128 * 2**20 or len(data) != expected_size:
        raise ValueError('index decompression/size bound')
    return checked(data, expected_digest, 'uncompressed index')


def package_rows(data):
    for stanza in data.decode().split('\n\n'):
        yield dict(line.split(': ', 1) for line in stanza.splitlines()
                   if ': ' in line and not line.startswith((' ', '\t')))


def source_id(row):
    source = row.get('source', 'default')
    if source not in SOURCES:
        raise ValueError('unknown input source')
    return source


def index_route(row):
    source = source_id(row)
    # Lock URLs describe provenance; code-owned routes determine all retrieval.
    match = re.fullmatch(r'https://(archive|security)\.ubuntu\.com/ubuntu/dists/'
                         r'(noble(?:-updates|-security|-backports)?)/'
                         r'(main|restricted|universe|multiverse)/binary-amd64/Packages', row['url'])
    if not match:
        raise ValueError('index URL assignment')
    host, suite, component = match.groups()
    host += '.ubuntu.com'
    relative = component + '/binary-amd64/Packages'
    expected_release = 'https://' + host + '/ubuntu/dists/' + suite + '/InRelease'
    if row['release_url'] != expected_release:
        raise ValueError('release URL assignment')
    if row['release'] != host + '_ubuntu_dists_' + suite + '_InRelease':
        raise ValueError('release name assignment')
    if row['name'] != host + '_ubuntu_dists_' + suite + '_' + relative.replace('/', '_'):
        raise ValueError('index name assignment')
    return source, 'dists/' + suite + '/InRelease', 'dists/' + suite + '/' + relative, relative


def package_route(row):
    source = source_id(row)
    path = row['archive_path']
    if not re.fullmatch(r'pool/(main|restricted|universe|multiverse)/[a-z0-9][a-z0-9+.-]*/'
                        r'[a-z0-9][a-z0-9+.-]*/[A-Za-z0-9+_.~-]+\.deb', path):
        raise ValueError('archive path assignment')
    if row['file'] != row['package'] + '_' + row['version'].replace(':', '%3a') + '_' + row['architecture'] + '.deb':
        raise ValueError('archive filename assignment')
    if row['url'] not in ('https://archive.ubuntu.com/ubuntu/' + path,
                          'https://security.ubuntu.com/ubuntu/' + path):
        raise ValueError('package URL assignment')
    if source == 'openssl-security':
        package = row['package']
        if package not in OPENSSL_PACKAGES:
            raise ValueError('supplemental package assignment')
        size, digest = OPENSSL_PACKAGES[package]
        expected = 'pool/main/o/openssl/' + package + '_' + OPENSSL_VERSION + '_amd64.deb'
        if (row['version'], row['architecture'], path, row.get('bytes'), row['sha256']) != (
                OPENSSL_VERSION, 'amd64', expected, size, digest):
            raise ValueError('supplemental package identity')
    elif source == 'expat-security':
        expected = 'pool/main/e/expat/libexpat1_' + EXPAT_VERSION + '_amd64.deb'
        if (row['package'], row['version'], row['architecture'], path, row.get('bytes'), row['sha256']) != (
                'libexpat1', EXPAT_VERSION, 'amd64', expected, *EXPAT_PACKAGE):
            raise ValueError('expat supplemental package identity')
    elif row['package'] == 'libexpat1' and row['version'] == EXPAT_VERSION:
        raise ValueError('Expat .6 requires supplemental source')
    elif row['package'] in OPENSSL_PACKAGES and row['version'] == OPENSSL_VERSION:
        raise ValueError('OpenSSL .16 requires supplemental source')
    return source, path


def validate_lock(lock):
    if lock['ubuntu_signer'] != UBUNTU_SIGNER:
        raise ValueError('Ubuntu signer assignment')
    indexes = lock['indexes']
    if any(source_id(row) != 'default' for row in indexes):
        raise ValueError('default index source assignment')
    supplemental = lock.get('supplemental_indexes', [])
    if supplemental and supplemental not in ([SUPPLEMENTAL_INDEX], [SUPPLEMENTAL_INDEX, EXPAT_INDEX]):
        raise ValueError('supplemental index identity or duplicate')
    names = set()
    for row in indexes + supplemental:
        source, *_ = index_route(row)
        key = source, row['name']
        if key in names:
            raise ValueError('duplicate index')
        names.add(key)
    packages = set()
    filenames = set()
    selected = set()
    expat_selected = False
    for row in lock['packages']:
        source, _ = package_route(row)
        key = row['package'], row['architecture']
        if key in packages or row['file'] in filenames:
            raise ValueError('duplicate package or conflicting source')
        packages.add(key)
        filenames.add(row['file'])
        if source == 'openssl-security':
            selected.add(row['package'])
        if source == 'expat-security':
            expat_selected = True
    if selected != (set(OPENSSL_PACKAGES) if supplemental else set()):
        raise ValueError('supplemental source requires both exact packages and index')
    if expat_selected != (EXPAT_INDEX in supplemental):
        raise ValueError('expat source requires exact package and index')


def verify_signer(status):
    signers = re.findall(rb'^\[GNUPG:\] VALIDSIG ([0-9A-F]+) ', status, re.M)
    if signers != [UBUNTU_SIGNER.encode()] or re.search(
            rb'^\[GNUPG:\] (?:BADSIG|ERRSIG|REVKEYSIG|EXPKEYSIG|EXPSIG) ', status, re.M):
        raise ValueError('unexpected Ubuntu signer')


def acquire_ubuntu(lock, destination, verify_release, fetch=download):
    """Download authenticated fixed inputs; injectable I/O supports offline negative tests."""
    validate_lock(lock)
    releases = {}
    packages = {}
    requested = {(source_id(row), row['package'], row['version'], row['architecture'])
                 for row in lock['packages']}
    for row in lock['indexes'] + lock.get('supplemental_indexes', []):
        source, release_path, index_path, relative = index_route(row)
        key = source, row['release']
        if key not in releases:
            body = checked(fetch(SOURCES[source] + release_path, 2**20), row['release_sha256'], row['release'])
            name = source + '--' + row['release']
            path = destination / 'metadata' / name
            path.write_bytes(body)
            verify_signer(verify_release(path))
            releases[key] = (row['release_sha256'], signed_hashes(body))
        release_sha, hashes = releases[key]
        if release_sha != row['release_sha256']:
            raise ValueError('conflicting release identity')
        if hashes[relative][0] != row['sha256']:
            raise ValueError('locked index is absent from signed Release')
        if source in ('openssl-security', 'expat-security') and (hashes[relative], hashes[relative + '.xz']) != (
                (row['sha256'], row['bytes']), (row['compressed_sha256'], row['compressed_bytes'])):
            raise ValueError('supplemental signed index identity')
        compressed = checked(fetch(SOURCES[source] + index_path + '.xz', 64 * 2**20),
                             hashes[relative + '.xz'][0], row['name'] + '.xz')
        if len(compressed) != hashes[relative + '.xz'][1]:
            raise ValueError('compressed index size mismatch')
        data = unpack_index(compressed, hashes[relative][1], row['sha256'])
        for item in package_rows(data):
            if 'Filename' not in item:
                continue
            key = (source, item.get('Package'), item.get('Version'), item.get('Architecture'))
            # Ubuntu indexes may contain unrelated duplicate tuples. They cannot
            # authorize this frozen request and do not participate in its closure.
            if key not in requested:
                continue
            identity = item['SHA256'], item['Filename'], item['Size']
            if key in packages and packages[key] != identity:
                raise ValueError('conflicting signed package identity: ' + repr(key))
            packages[key] = identity
    for row in lock['packages']:
        source, path = package_route(row)
        key = source, row['package'], row['version'], row['architecture']
        if key not in packages:
            raise ValueError('package absent from assigned signed index: ' + row['package'])
        digest, archive, size = packages[key]
        if (digest, archive) != (row['sha256'], path):
            raise ValueError('package does not match signed index: ' + row['package'])
        if source in ('openssl-security', 'expat-security') and int(size) != row['bytes']:
            raise ValueError('supplemental signed package size')
        data = checked(fetch(SOURCES[source] + path, 64 * 2**20), row['sha256'], row['file'])
        if len(data) != int(size):
            raise ValueError('package size mismatch')
        (destination / 'debs' / row['file']).write_bytes(data)


def main():
    destination = Path(sys.argv[1]).resolve()
    lock = json.loads((HERE / 'amd64-inputs.json').read_bytes())
    validate_lock(lock)
    destination.mkdir(mode=0o700)
    metadata = destination / 'metadata'
    metadata.mkdir()
    (destination / 'debs').mkdir()
    subprocess.run(['docker', 'pull', '--platform', 'linux/amd64', lock['base']], check=True)
    subprocess.run(['docker', 'pull', lock['cosign_verifier']], check=True)
    key = subprocess.run(['docker', 'run', '--rm', '--platform', 'linux/amd64',
        '--network=none', '--read-only', '--cap-drop=ALL', '--security-opt=no-new-privileges',
        '--user', '1000:1000', lock['base'], 'cat',
        '/usr/share/keyrings/ubuntu-archive-keyring.gpg'], check=True, capture_output=True).stdout
    (metadata / 'keyring.gpg').write_bytes(checked(key, lock['ubuntu_keyring_sha256'], 'keyring'))

    def verify_release(path):
        result = subprocess.run(['docker', 'run', '--rm', '--platform', 'linux/amd64',
            '--network=none', '--read-only', '--cap-drop=ALL', '--security-opt=no-new-privileges',
            '--user', f'{os.getuid()}:{os.getgid()}', '--tmpfs', '/tmp:mode=1777',
            '--mount', f'type=bind,src={metadata},dst=/metadata,readonly', lock['base'],
            'gpgv', '--homedir', '/tmp', '--status-fd', '1', '--keyring',
            '/metadata/keyring.gpg', '/metadata/' + path.name],
            check=True, capture_output=True, timeout=30)
        return result.stdout

    acquire_ubuntu(lock, destination, verify_release)
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
    print('verified signed default/supplemental Ubuntu inputs and Compose provenance: '
          + str(len(lock['packages'])) + ' exact packages')


if __name__ == '__main__':
    main()
