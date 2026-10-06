#!/usr/bin/env python3
"""CLB-189 exact reference and synthetic offline resolver regressions."""
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
HERE = ROOT / 'scripts/tests/linux-semantic'
VERSION = '2.6.1-2ubuntu0.6'
DEB = '494b8e672f722130c6bca6a7bc4cc31a43ca891a31d60d868bfdd699a3c20b13'
LIB = '286682ecbc5e59a638963b1a4e6351e65eb32fcf4bdcb9cb7569b6a61fe06a8d'
PATH = '/usr/lib/x86_64-linux-gnu/libexpat.so.1.9.1'


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


d = load('expat_downloads', HERE / 'prepare-amd64-downloads.py')
r = load('expat_runner', ROOT / 'scripts/deploy/stage-package-plan.py')
c = load('expat_collector', ROOT / r.REMOTE_PATH)


class ReferenceTest(unittest.TestCase):
    def setUp(self):
        self.lock = json.loads((HERE / 'amd64-inputs.json').read_bytes())
        self.target = json.loads((ROOT / r.TARGET_PATH).read_bytes())

    def test_exact_target_request_and_signed_source_agree(self):
        row = next(x for x in self.target['request'] if x['package'] == 'libexpat1')
        lock = next(x for x in self.lock['packages'] if x['package'] == 'libexpat1')
        self.assertEqual(row['version'], VERSION)
        self.assertEqual(lock['version'], VERSION)
        self.assertEqual(dict(c.REQUEST)['libexpat1'], VERSION)
        self.assertEqual(row['architecture'], 'amd64')
        self.assertEqual(row['target_runtime_files'], [PATH])
        self.assertEqual(row['trusted_reference_source'], {
            'kind': 'frozen_ubuntu_deb_lock', 'sha256': DEB,
            'archive_path': 'pool/main/e/expat/libexpat1_' + VERSION + '_amd64.deb'})
        self.assertEqual(lock['sha256'], DEB)
        self.assertEqual(lock['source'], 'expat-security')
        self.assertEqual(d.package_route(lock), ('expat-security', row['trusted_reference_source']['archive_path']))
        self.assertEqual(d.EXPAT_INDEX, dict(d.SUPPLEMENTAL_INDEX, source='expat-security'))
        d.validate_lock(self.lock)

    def test_other_twenty_requests_are_byte_semantically_unchanged(self):
        rows = [x for x in self.target['request'] if x['package'] != 'libexpat1']
        self.assertEqual(len(rows), 20)
        self.assertEqual(sha(json.dumps(rows, sort_keys=True, separators=(',', ':')).encode()),
                         'd92c729a1d6e1b68cc7a81cbeeae66494e977e817617bbb8942c86e934bc62e7')

    def test_retained_supplier_evidence_matches_current_reference(self):
        evidence = json.loads((ROOT / 'scripts/tests/fixtures/clb189-expat-comparison.json').read_bytes())
        current = evidence['packages']['6']
        self.assertEqual(current['deb_sha256'], DEB)
        self.assertEqual(current['deb_bytes'], d.EXPAT_PACKAGE[0])
        self.assertEqual(current['elf']['libexpat.so.1.9.1']['sha256'], LIB)
        provenance = evidence['signed_provenance']
        self.assertEqual(provenance['signer'], d.UBUNTU_SIGNER)
        self.assertEqual(provenance['snapshot'], d.SOURCES['expat-security'])
        self.assertEqual(provenance['InRelease_sha256'], d.EXPAT_INDEX['release_sha256'])
        self.assertEqual(provenance['Packages_sha256'], d.EXPAT_INDEX['sha256'])
        self.assertEqual(provenance['Packages_xz_sha256'], d.EXPAT_INDEX['compressed_sha256'])
        for library, old in evidence['packages']['5']['elf'].items():
            for key in ('soname', 'needed', 'size', 'abi_surface_sha256'):
                self.assertEqual(old[key], current['elf'][library][key], (library, key))

    def test_partial_duplicate_update_is_detected(self):
        for name in ('amd64-inputs.json', 'amd64-packages.txt', 'amd64-runtime-candidate.json',
                     'prepare-amd64-downloads.py'):
            original = (HERE / name).read_bytes()
            duplicate = (HERE / 'native-adapter/ci/reference' / name).read_bytes()
            self.assertEqual(original, duplicate, name)
            with self.assertRaises(AssertionError):
                self.assertEqual(original, duplicate + b'\n', name)

    def test_target_library_and_all_current_manifests_agree(self):
        for path in ('scripts/deploy/stage-compose-env-semantic-runtime.json',
                     'scripts/tests/linux-semantic/amd64-runtime-candidate.json',
                     'scripts/tests/linux-semantic/native-adapter/ci/reference/accepted-manifest.json',
                     'scripts/tests/linux-semantic/native-adapter/reference/candidate-runtime.json'):
            self.assertEqual(json.loads((ROOT / path).read_bytes())['files'][PATH], LIB)
        self.assertEqual(self.target['manifest_sha256'],
                         sha((ROOT / 'scripts/deploy/stage-compose-env-semantic-runtime.json').read_bytes()))

    def test_changed_deb_or_identity_refuses(self):
        for key, value in (('sha256', '0' * 64), ('version', '2.6.1-2ubuntu0.5'),
                           ('source', 'default'), ('architecture', 'arm64'), ('bytes', 1),
                           ('archive_path', 'pool/main/e/expat/other.deb')):
            lock = copy.deepcopy(self.lock)
            next(x for x in lock['packages'] if x['package'] == 'libexpat1')[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                d.validate_lock(lock)
        with self.assertRaises(ValueError):
            d.checked(b'tampered deb', DEB, 'libexpat1')

    def test_missing_or_tampered_expat_index_refuses(self):
        for action in ('remove', 'digest', 'source'):
            lock = copy.deepcopy(self.lock)
            row = lock['supplemental_indexes'][-1]
            if action == 'remove':
                lock['supplemental_indexes'].pop()
            else:
                row['sha256' if action == 'digest' else 'source'] = '0' * 64
            with self.subTest(action=action), self.assertRaises(ValueError):
                d.validate_lock(lock)

    def test_source_closure_and_partial_request_update_refuse(self):
        sources = {p: (ROOT / p).read_bytes() for p in r.LOCAL_SOURCES}
        r.verify_sources(sources)
        for path in r.SOURCE_PINS:
            damaged = dict(sources)
            damaged[path] += b'\n'
            with self.subTest(path=path), self.assertRaises(ValueError):
                r.verify_sources(damaged)
        damaged = dict(sources)
        damaged[r.REMOTE_PATH] = damaged[r.REMOTE_PATH].replace(VERSION.encode(), b'2.6.1-2ubuntu0.5')
        with self.assertRaises(ValueError):
            r.verify_sources(damaged)


@unittest.skipUnless(sys.platform == 'linux', 'real APT resolver requires disposable Linux')
class ResolverTest(unittest.TestCase):
    def test_observed_available_versions_resolve_and_stale_exact_pin_fails(self):
        # Synthetic observed availability universe, NOT a stage transaction proof.
        # APT_CONFIG isolates all config/status/lists/cache; -s never installs.
        with tempfile.TemporaryDirectory(prefix='clb189-apt-') as td:
            root = Path(td)
            (root / 'lists/partial').mkdir(parents=True)
            (root / 'empty').mkdir()
            (root / 'status').write_text('')
            (root / 'sources.list').write_text('deb file:/synthetic noble main\n')
            stanzas = []
            for package, version in c.REQUEST:
                stanza = f'Package: {package}\nVersion: {version}\nArchitecture: amd64\n'
                stanza += f'Filename: pool/{package}.deb\nSize: 1\nSHA256: {sha(b"x")}\n'
                if package == 'libexpat1':
                    stanza += 'Depends: libc6 (>= 2.38)\n'
                stanzas.append(stanza + 'Description: synthetic resolver fixture\n')
            (root / 'lists/_synthetic_dists_noble_main_binary-amd64_Packages').write_text('\n'.join(stanzas))
            config = root / 'apt.conf'
            config.write_text(f'Dir::Etc::parts "{root}/empty";\nDir::Etc::main "/dev/null";\n'
                              f'Dir::Etc::sourcelist "{root}/sources.list";\nDir::Etc::sourceparts "{root}/empty";\n'
                              f'Dir::State::status "{root}/status";\nDir::State::lists "{root}/lists";\n'
                              f'Dir::Cache "{root}/cache";\nDir::Log "{root}/log";\n'
                              'APT::Architecture "amd64";\nDebug::NoLocking "true";\n')
            env = {'PATH': '/usr/bin:/bin', 'LC_ALL': 'C', 'APT_CONFIG': str(config)}
            request = [p + '=' + v for p, v in c.REQUEST]
            def resolve(items):
                return subprocess.run(['apt-get', '-s', 'install', *items], env=env,
                                      capture_output=True, text=True, timeout=20)
            result = resolve(request)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn('Inst libexpat1 (2.6.1-2ubuntu0.6', result.stdout)
            old = [x.replace('libexpat1=' + VERSION, 'libexpat1=2.6.1-2ubuntu0.5') for x in request]
            result = resolve(old)
            self.assertEqual(result.returncode, 100)
            self.assertIn("Version '2.6.1-2ubuntu0.5' for 'libexpat1' was not found", result.stderr)
            self.assertEqual((root / 'status').read_text(), '')


if __name__ == '__main__':
    unittest.main()
