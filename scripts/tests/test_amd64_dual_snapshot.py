#!/usr/bin/env python3
"""Offline rejection controls for the two fixed Ubuntu snapshots; no network/containers."""
import copy
from contextlib import ExitStack
import hashlib
import importlib.util
import json
import lzma
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
HERE = ROOT / 'scripts/tests/linux-semantic'


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


d = load('dual_downloads', HERE / 'prepare-amd64-downloads.py')
c = load('dual_context', HERE / 'prepare-amd64-context.py')


class RoutingTest(unittest.TestCase):
    def setUp(self):
        self.lock = json.loads((HERE / 'amd64-inputs.json').read_bytes())

    def test_current_lock_routes_exactly_two_new_packages(self):
        d.validate_lock(self.lock)
        selected = [r for r in self.lock['packages'] if d.source_id(r) == 'openssl-security']
        self.assertEqual({r['package'] for r in selected}, {'libssl3t64', 'openssl'})
        self.assertEqual(len(selected), 2)
        self.assertEqual(d.SOURCES, {
            'default': 'https://snapshot.ubuntu.com/ubuntu/20260921T200000Z/',
            'openssl-security': 'https://snapshot.ubuntu.com/ubuntu/20260930T120000Z/',
            'expat-security': 'https://snapshot.ubuntu.com/ubuntu/20260930T120000Z/',
        })
        self.assertTrue(all('source' not in r for r in self.lock['indexes']))
        self.assertEqual(len(self.lock['indexes']), 15)
        self.assertEqual((HERE / 'prepare-amd64-downloads.py').read_bytes(),
                         (HERE / 'native-adapter/ci/reference/prepare-amd64-downloads.py').read_bytes())

    def test_default_closure_remains_frozen(self):
        # Canonical identities of exact merged-main indexes and all 59 unchanged
        # DEB rows, derived before this change. No default package is re-resolved.
        canonical = lambda value: sha(json.dumps(value, sort_keys=True, separators=(',', ':')).encode())
        self.assertEqual(canonical(self.lock['indexes']),
                         'd0896d3bdb60f5866c95d4e559694f7d81a72a0d3dc75fbf8cf05888618da579')
        default = [row for row in self.lock['packages'] if 'source' not in row]
        self.assertEqual(len(default), 59)
        self.assertEqual(canonical(default),
                         '921186c8b3480936f48ded1baa4ea8fa56ac1b70df086838e89f8b99efa3150d')
        legacy = copy.deepcopy(self.lock)
        legacy.pop('supplemental_indexes')
        legacy['packages'] = default + [{
            'architecture': 'amd64', 'package': 'openssl', 'version': '3.0.13-0ubuntu3.15',
            'archive_path': 'pool/main/o/openssl/openssl_3.0.13-0ubuntu3.15_amd64.deb',
            'file': 'openssl_3.0.13-0ubuntu3.15_amd64.deb',
            'url': 'https://archive.ubuntu.com/ubuntu/pool/main/o/openssl/openssl_3.0.13-0ubuntu3.15_amd64.deb',
            'sha256': '1f2401db59a0beffde6cc47bea3664965ab8fa206b7a5ac8f1cc649cfeeb056b',
        }]
        d.validate_lock(legacy)

    def test_supplemental_package_mutations_refuse(self):
        for key, value in (
            ('source', 'other'), ('source', 'default'), ('source', d.SNAPSHOT),
            ('url', 'https://attacker.invalid/ubuntu/pool/a.deb'),
            ('url', 'https://archive.ubuntu.com/ubuntu/../evil.deb'),
            ('package', 'libexpat1'), ('version', '3.0.13-0ubuntu3.15'),
            ('architecture', 'arm64'), ('archive_path', '../evil.deb'),
            ('file', '../evil.deb'), ('bytes', 1), ('sha256', '0' * 64),
        ):
            lock = copy.deepcopy(self.lock)
            row = next(r for r in lock['packages'] if r['package'] == 'libssl3t64')
            row[key] = value
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                d.validate_lock(lock)

    def test_default_cannot_claim_fixed_openssl(self):
        for name in ('libssl3t64', 'openssl'):
            lock = copy.deepcopy(self.lock)
            next(r for r in lock['packages'] if r['package'] == name).pop('source')
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'requires supplemental'):
                d.validate_lock(lock)

    def test_unrelated_supplemental_assignment_refuses(self):
        lock = copy.deepcopy(self.lock)
        next(r for r in lock['packages'] if r['package'] == 'libexpat1')['source'] = 'openssl-security'
        with self.assertRaisesRegex(ValueError, 'supplemental package assignment'):
            d.validate_lock(lock)

    def test_index_mutations_and_duplicate_records_refuse(self):
        for key, value in (
            ('source', 'default'), ('source', 'unknown'), ('release_sha256', '0' * 64),
            ('sha256', '0' * 64), ('compressed_sha256', '0' * 64),
            ('bytes', 1), ('compressed_bytes', 1),
            ('release_url', 'https://attacker.invalid/InRelease'),
            ('url', 'https://security.ubuntu.com/ubuntu/dists/noble-security/main/binary-arm64/Packages'),
            ('snapshot', 'https://attacker.invalid/'),
        ):
            lock = copy.deepcopy(self.lock)
            lock['supplemental_indexes'][0][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                d.validate_lock(lock)
        for name in ('indexes', 'supplemental_indexes', 'packages'):
            lock = copy.deepcopy(self.lock)
            lock[name].append(copy.deepcopy(lock[name][0]))
            with self.subTest(duplicate=name), self.assertRaises(ValueError):
                d.validate_lock(lock)
        lock = copy.deepcopy(self.lock)
        row = copy.deepcopy(next(r for r in lock['packages'] if r['package'] == 'openssl'))
        row.pop('source')
        lock['packages'].append(row)
        with self.assertRaises(ValueError):
            d.validate_lock(lock)

    def test_missing_source_records_refuse(self):
        for target in ('supplemental_indexes', 'packages'):
            lock = copy.deepcopy(self.lock)
            if target == 'supplemental_indexes':
                lock.pop(target)
            else:
                lock[target] = [r for r in lock[target] if r['package'] != 'openssl']
            with self.subTest(target=target), self.assertRaises(ValueError):
                d.validate_lock(lock)

    def test_default_url_injection_and_signer_assignment_refuse(self):
        for target, key, value in (
            ('indexes', 'url', 'https://evil.invalid/ubuntu/dists/noble/main/binary-amd64/Packages'),
            ('indexes', 'release_url', 'https://archive.ubuntu.com/ubuntu/dists/../InRelease'),
            ('indexes', 'source', 'openssl-security'),
            ('packages', 'url', 'https://evil.invalid/ubuntu/pool/example.deb'),
        ):
            lock = copy.deepcopy(self.lock)
            lock[target][0][key] = value
            with self.subTest(target=target, key=key), self.assertRaises(ValueError):
                d.validate_lock(lock)
        self.lock['ubuntu_signer'] = '0' * 40
        with self.assertRaises(ValueError):
            d.validate_lock(self.lock)

    def test_signature_status_refuses_wrong_or_multiple_signers(self):
        valid = b'[GNUPG:] VALIDSIG ' + d.UBUNTU_SIGNER.encode() + b' 0 0 0\n'
        d.verify_signer(valid)
        for status in (b'', valid.replace(d.UBUNTU_SIGNER.encode(), b'0' * 40),
                       valid + valid, valid + b'[GNUPG:] BADSIG anything\n'):
            with self.subTest(status=status), self.assertRaises(ValueError):
                d.verify_signer(status)


class AcquisitionTest(unittest.TestCase):
    def fixture(self, metadata_change=None, duplicate=None, misroute=False):
        # Synthetic supplier records exercise real acquisition code. Only byte
        # identities are fixture values; package/source allowlists remain exact.
        blobs = {'widget': b'default-deb', 'libssl3t64': b'fixed-library',
                 'openssl': b'fixed-program', 'libexpat1': b'fixed-expat'}
        constants = {name: (len(blobs[name]), sha(blobs[name])) for name in d.OPENSSL_PACKAGES}
        rows = []
        for name, raw in blobs.items():
            version = '1.0' if name == 'widget' else d.OPENSSL_VERSION
            package = 'widget' if name == 'widget' else 'openssl'
            if name == 'libexpat1':
                version, package = d.EXPAT_VERSION, 'expat'
            path = 'pool/main/' + package[0] + '/' + package + '/' + name + '_' + version + '_amd64.deb'
            row = dict(package=name, architecture='amd64', version=version,
                       archive_path=path, file=path.rsplit('/', 1)[1], sha256=sha(raw),
                       url='https://archive.ubuntu.com/ubuntu/' + path)
            if name != 'widget':
                row.update(source='expat-security' if name == 'libexpat1' else 'openssl-security', bytes=len(raw))
            rows.append(row)
        resources = {}
        indexes = []
        for source in ('default', 'openssl-security'):
            selected = [r for r in rows if r.get('source', 'default') == source]
            if source == 'openssl-security':
                selected += [r for r in rows if r.get('source') == 'expat-security']
            if misroute and source == 'default':
                selected = list(rows)
            stanzas = []
            for row in selected:
                fields = {'Package': row['package'], 'Version': row['version'],
                          'Architecture': row['architecture'], 'Filename': row['archive_path'],
                          'SHA256': row['sha256'], 'Size': str(len(blobs[row['package']]))}
                if metadata_change:
                    metadata_change(source, fields)
                stanzas.append(''.join(k + ': ' + v + '\n' for k, v in fields.items()))
                resources[d.SOURCES[source] + row['archive_path']] = blobs[row['package']]
            if source == 'default' and duplicate:
                extra = stanzas[0]
                if duplicate == 'unrelated':
                    extra = extra.replace('Package: widget\n', 'Package: unrelated\n')
                    stanzas.append(extra)
                stanzas.append(extra.replace('SHA256: ' + sha(blobs['widget']), 'SHA256: ' + '0' * 64))
            data = ('\n'.join(stanzas) + '\n').encode()
            compressed = lzma.compress(data)
            host = 'archive.ubuntu.com' if source == 'default' else 'security.ubuntu.com'
            suite = 'noble' if source == 'default' else 'noble-security'
            relative = 'main/binary-amd64/Packages'
            release = ('signed fixture\nSHA256:\n ' + sha(data) + ' ' + str(len(data)) + ' ' + relative + '\n '
                       + sha(compressed) + ' ' + str(len(compressed)) + ' ' + relative + '.xz\nSHA512:\n').encode()
            row = dict(name=host + '_ubuntu_dists_' + suite + '_main_binary-amd64_Packages',
                       release=host + '_ubuntu_dists_' + suite + '_InRelease',
                       release_url='https://' + host + '/ubuntu/dists/' + suite + '/InRelease',
                       release_sha256=sha(release), sha256=sha(data),
                       url='https://' + host + '/ubuntu/dists/' + suite + '/' + relative)
            if source != 'default':
                row.update(source=source, bytes=len(data), compressed_sha256=sha(compressed), compressed_bytes=len(compressed))
            indexes.append(row)
            resources[d.SOURCES[source] + 'dists/' + suite + '/InRelease'] = release
            resources[d.SOURCES[source] + 'dists/' + suite + '/' + relative + '.xz'] = compressed
        lock = dict(ubuntu_signer=d.UBUNTU_SIGNER, indexes=[indexes[0]],
                    supplemental_indexes=[indexes[1], dict(indexes[1], source='expat-security')], packages=rows)
        return lock, resources, constants

    def acquire(self, lock, resources, constants, status=None):
        calls = []
        def fetch(url, limit):
            calls.append(url)
            self.assertIn(url, resources)
            self.assertLessEqual(len(resources[url]), limit)
            return resources[url]
        with tempfile.TemporaryDirectory() as folder, ExitStack() as stack:
            destination = Path(folder)
            (destination / 'metadata').mkdir()
            (destination / 'debs').mkdir()
            stack.enter_context(patch.object(d, 'OPENSSL_PACKAGES', constants))
            stack.enter_context(patch.object(d, 'SUPPLEMENTAL_INDEX', lock['supplemental_indexes'][0]))
            stack.enter_context(patch.object(d, 'EXPAT_INDEX', lock['supplemental_indexes'][1]))
            stack.enter_context(patch.object(d, 'EXPAT_PACKAGE', (len(b'fixed-expat'), sha(b'fixed-expat'))))
            d.acquire_ubuntu(lock, destination,
                lambda p: status if status is not None else b'[GNUPG:] VALIDSIG ' + d.UBUNTU_SIGNER.encode() + b' 0\n', fetch)
            result = {p.name: p.read_bytes() for p in (destination / 'debs').iterdir()}
        return result, calls

    def test_both_sources_retrieve_only_assigned_exact_packages(self):
        lock, resources, constants = self.fixture()
        files, calls = self.acquire(lock, resources, constants)
        self.assertEqual(len(files), 4)
        for row in lock['packages']:
            self.assertIn(d.SOURCES[row.get('source', 'default')] + row['archive_path'], calls)
            self.assertEqual(sha(files[row['file']]), row['sha256'])

    def test_corrupt_release_index_and_deb_refuse(self):
        for suffix in ('InRelease', 'Packages.xz', '.deb'):
            lock, resources, constants = self.fixture()
            key = next(url for url in resources if url.startswith(d.SOURCES['openssl-security']) and url.endswith(suffix))
            resources[key] += b'changed'
            with self.subTest(suffix=suffix), self.assertRaises(ValueError):
                self.acquire(lock, resources, constants)

    def test_wrong_signed_package_fields_refuse(self):
        for field, value in (('Package', 'unrelated'), ('Version', '3.0.13-0ubuntu3.15'),
                             ('Architecture', 'arm64'), ('Filename', 'pool/main/w/wrong/wrong.deb'),
                             ('SHA256', '0' * 64), ('Size', '999')):
            lock, resources, constants = self.fixture(
                lambda source, fields: fields.update({field: value}) if source == 'openssl-security' else None)
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.acquire(lock, resources, constants)

    def test_signature_rejected_before_any_index_or_deb_download(self):
        lock, resources, constants = self.fixture()
        with self.assertRaisesRegex(ValueError, 'signer'):
            self.acquire(lock, resources, constants, b'[GNUPG:] VALIDSIG ' + b'0' * 40 + b' 0\n')

    def test_expat_deb_and_signed_identity_tampering_refuse(self):
        lock, resources, constants = self.fixture()
        key = next(url for url in resources if 'libexpat1_' in url)
        resources[key] += b'tampered'
        with self.assertRaises(ValueError):
            self.acquire(lock, resources, constants)
        for field, value in (('Version', '2.6.1-2ubuntu0.5'), ('SHA256', '0' * 64),
                             ('Architecture', 'arm64'), ('Size', '1')):
            lock, resources, constants = self.fixture(
                lambda source, fields: fields.update({field: value})
                if source == 'openssl-security' and fields['Package'] == 'libexpat1' else None)
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.acquire(lock, resources, constants)

    def test_default_index_cannot_authorize_supplemental_package(self):
        # Correct OpenSSL tuples exist only in the default signed index. Even
        # valid identities there must not satisfy the supplemental assignment.
        lock, resources, constants = self.fixture(misroute=True,
            metadata_change=lambda source, fields: fields.update(Package='unrelated')
                if source == 'openssl-security' else None)
        with self.assertRaisesRegex(ValueError, 'absent from assigned signed index'):
            self.acquire(lock, resources, constants)

    def test_unrelated_duplicate_index_packages_are_ignored(self):
        lock, resources, constants = self.fixture(duplicate='unrelated')
        files, calls = self.acquire(lock, resources, constants)
        self.assertEqual(len(files), 4)

    def test_requested_conflicting_index_packages_refuse(self):
        lock, resources, constants = self.fixture(duplicate='requested')
        with self.assertRaisesRegex(ValueError, 'conflicting signed package identity'):
            self.acquire(lock, resources, constants)

    def test_default_only_lock_retains_original_routing(self):
        lock, resources, constants = self.fixture()
        lock['packages'] = [lock['packages'][0]]
        lock.pop('supplemental_indexes')
        with tempfile.TemporaryDirectory() as folder:
            destination = Path(folder)
            (destination / 'metadata').mkdir()
            (destination / 'debs').mkdir()
            calls = []
            def fetch(url, limit):
                calls.append(url)
                return resources[url]
            d.acquire_ubuntu(lock, destination, lambda p: b'[GNUPG:] VALIDSIG ' + d.UBUNTU_SIGNER.encode() + b' 0\n', fetch)
            self.assertTrue(all(url.startswith(d.SNAPSHOT) for url in calls))
            self.assertEqual(len(list((destination / 'debs').iterdir())), 1)


class ContextTest(unittest.TestCase):
    def fixture(self, root):
        here, downloads, output = root / 'here', root / 'downloads', root / 'context'
        here.mkdir()
        (downloads / 'debs').mkdir(parents=True)
        binary = b'fixture-compose'
        (downloads / 'docker-compose-linux-x86_64').write_bytes(binary)
        package = b'fixture-deb'
        (downloads / 'debs/fixture.deb').write_bytes(package)
        provenance = {'subject': [{'name': 'docker-compose-linux-x86_64', 'digest': {'sha256': sha(binary)}}],
            'predicate': {'buildDefinition': {'externalParameters': {'configSource': {
                'uri': 'https://github.com/docker/compose.git#refs/tags/v5.1.1', 'digest': {'sha1': 'fixture'}, 'path': 'Dockerfile'}}}}}
        raw = json.dumps(provenance).encode()
        (downloads / 'docker-compose-linux-x86_64.provenance.json').write_bytes(raw)
        lock = {'packages': [{'file': 'fixture.deb', 'sha256': sha(package)}],
            'compose': {'assets': {'docker-compose-linux-x86_64': sha(binary),
                                  'docker-compose-linux-x86_64.provenance.json': sha(raw)},
                        'sha256': sha(binary), 'source_commit': 'fixture'},
            'input_sums_sha256': sha((sha(package) + '  debs/fixture.deb\n' + sha(binary) + '  docker-compose-linux-x86_64\n').encode())}
        (here / 'amd64-inputs.json').write_text(json.dumps(lock))
        (here / 'Dockerfile.amd64').write_text('FROM scratch\n')
        return here, downloads, output, lock

    def test_valid_context_and_refusal_controls(self):
        for mutation in ('valid', 'existing', 'bad', 'missing', 'symlink', 'inventory'):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as folder:
                root = Path(folder)
                here, downloads, output, lock = self.fixture(root)
                package = downloads / 'debs/fixture.deb'
                if mutation == 'existing': output.mkdir()
                if mutation == 'bad': package.write_bytes(b'wrong')
                if mutation in ('missing', 'symlink'): package.unlink()
                if mutation == 'symlink':
                    target = root / 'other.deb'
                    target.write_bytes(b'fixture-deb')
                    package.symlink_to(target)
                if mutation == 'inventory':
                    lock['input_sums_sha256'] = '0' * 64
                    (here / 'amd64-inputs.json').write_text(json.dumps(lock))
                with patch.object(c, 'HERE', here), patch.object(sys, 'argv', ['prepare', str(downloads), str(output)]):
                    if mutation == 'valid':
                        c.main()
                        self.assertEqual((output / 'debs/fixture.deb').read_bytes(), b'fixture-deb')
                    else:
                        with self.assertRaises(SystemExit): c.main()


if __name__ == '__main__':
    unittest.main()
