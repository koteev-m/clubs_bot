#!/usr/bin/env python3
"""Exercise the exact CLB-91 checksum exceptions with the pinned real scanner.

All canaries and Git history are synthetic and disposable. Never print matches.
"""
import ast
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import tempfile
import tomllib
import unittest

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = Path('scripts/deploy/stage-compose-env-semantic-runtime.json')
ARM64_MANIFEST = Path('scripts/tests/linux-semantic/arm64-runtime-reference.json')
AMD64_MANIFEST = Path('scripts/tests/linux-semantic/amd64-runtime-candidate.json')
NATIVE_ACCEPTED_MANIFEST = Path('scripts/tests/linux-semantic/native-adapter/ci/reference/accepted-manifest.json')
NATIVE_AMD64_MANIFEST = Path('scripts/tests/linux-semantic/native-adapter/ci/reference/amd64-runtime-candidate.json')
NATIVE_ARM64_MANIFEST = Path('scripts/tests/linux-semantic/native-adapter/ci/reference/arm64-runtime-reference.json')
NATIVE_CANDIDATE_MANIFEST = Path('scripts/tests/linux-semantic/native-adapter/reference/candidate-runtime.json')
IMAGE = 'ghcr.io/gitleaks/gitleaks@sha256:cdbb7c955abce02001a9f6c9f602fb195b7fadc1e812065883f695d1eeaba854'
RUNTIME_PATHS = (
    '/usr/lib/python3.12/__pycache__/' + 'se' + 'crets.cpython-312.pyc',
    '/usr/lib/python3.12/' + 'se' + 'crets.py',
)
RUNTIME_DIGESTS = (
    '08cd4dd20cb98e58ed935d9353928e74a80b17ea3412a9d6009431249cefbff6',
    '277000574358a6ecda4bb40e73332ae81a3bc1c8e1fa36f50e5c6a7d4d3f0f17',
)

SYNTHETIC_PATHS = ('/etc/' + 'pass' + 'wd',) + RUNTIME_PATHS
SYNTHETIC_DIGESTS = ('3cd567e7c68f0bfb5594a4b594788734355b83b38a82a409482973952f9dad58',) + RUNTIME_DIGESTS
PYTHON_MANIFESTS = (MANIFEST, AMD64_MANIFEST, ARM64_MANIFEST, NATIVE_ACCEPTED_MANIFEST,
                    NATIVE_AMD64_MANIFEST, NATIVE_ARM64_MANIFEST)


def git(repo, *args):
    environment = dict(os.environ, GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_SYSTEM=os.devnull,
        GIT_AUTHOR_NAME='Synthetic', GIT_AUTHOR_EMAIL='fixture@example.invalid',
        GIT_COMMITTER_NAME='Synthetic', GIT_COMMITTER_EMAIL='fixture@example.invalid',
        GIT_TERMINAL_PROMPT='0')
    result = subprocess.run(['git', '-C', str(repo), *args], env=environment,
        capture_output=True, timeout=20)
    if result.returncode:
        raise AssertionError('synthetic Git fixture failed: ' + ' '.join(args))
    return result.stdout.decode().strip()


def write(repo, name, content):
    path = repo / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)


def scanner(repo, output, *, directory=False):
    # Same detect/Git/history mode and --redact as Secret Scan; no --no-git or
    # log truncation. The host checkout, home, credentials and socket are absent.
    output_owner = output.parent.stat()
    repo_owner = repo.stat()
    if (repo_owner.st_uid, repo_owner.st_gid) != (output_owner.st_uid, output_owner.st_gid):
        raise AssertionError('synthetic source/output owners differ')
    command = [os.environ.get('DOCKER_BIN', 'docker'), 'run', '--rm', '--read-only',
        '--network=none', '--cap-drop=ALL', '--security-opt=no-new-privileges',
        '--tmpfs', '/tmp:mode=1777,nosuid,nodev',
        '--user', f'{output_owner.st_uid}:{output_owner.st_gid}',
        '-v', str(repo) + ':/repo:ro', '-v', str(output.parent) + ':/out',
        '-w', '/repo', '-e', 'GIT_CONFIG_COUNT=1',
        '-e', 'GIT_CONFIG_KEY_0=safe.directory', '-e', 'GIT_CONFIG_VALUE_0=/repo',
        IMAGE, 'detect', '--source', '.', '--report-format', 'json',
        '--report-path', '/out/' + output.name, '--redact']
    if directory:
        command.append('--no-git')
    try:
        result = subprocess.run(command, capture_output=True, timeout=90)
    except (OSError, subprocess.TimeoutExpired):
        raise AssertionError('pinned Gitleaks could not start or finish') from None
    if not output.is_file():
        raise AssertionError(f'pinned Gitleaks produced no report (scanner exit {result.returncode})')
    if result.returncode not in (0, 1):
        raise AssertionError(f'pinned Gitleaks failed outside finding status (exit {result.returncode})')
    try:
        findings = json.loads(output.read_text())
    except (OSError, ValueError):
        raise AssertionError('pinned Gitleaks report is unreadable or malformed') from None
    if type(findings) is not list or (result.returncode == 0) != (len(findings) == 0):
        raise AssertionError('scanner exit and redacted report disagree')
    return result.returncode, [(item['RuleID'], item['File'], item['StartLine']) for item in findings]


class RuntimeChecksumAllowlistTest(unittest.TestCase):
    manifest = MANIFEST
    lines = (39, 114)
    runtime_paths = RUNTIME_PATHS
    runtime_digests = RUNTIME_DIGESTS

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='clb91-gitleaks-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.repo = self.root / 'repo'
        self.repo.mkdir()
        git(self.repo, 'init', '-q', '-b', 'main')
        self.raw = (ROOT / self.manifest).read_bytes()
        self.config = (ROOT / '.gitleaks.toml').read_bytes()

    def scan(self, label):
        return scanner(self.repo, self.root / (label + '.json'))

    def commit(self, label):
        git(self.repo, 'add', '--', '.')
        git(self.repo, 'commit', '-q', '-m', label)

    def test_exact_configuration_and_manifest_identity(self):
        manifest = json.loads(self.raw)
        self.assertEqual(tuple(manifest['files'][name] for name in self.runtime_paths), self.runtime_digests)
        config = tomllib.loads(self.config.decode())
        self.assertEqual(set(config), {'title', 'extend', 'rules'})
        self.assertEqual(config['extend'], {'useDefault': True})
        self.assertEqual(len(config['rules']), 1)
        rule = config['rules'][0]
        self.assertEqual(set(rule), {'id', 'allowlists'})
        self.assertEqual(rule['id'], 'generic-api-key')
        expected_entries = [(name, RUNTIME_PATHS, RUNTIME_DIGESTS) for name in PYTHON_MANIFESTS]
        expected_entries.append((NATIVE_CANDIDATE_MANIFEST, SYNTHETIC_PATHS, SYNTHETIC_DIGESTS))
        self.assertEqual(len(rule['allowlists']), len(expected_entries))
        for allowlist, (name, paths, digests) in zip(rule['allowlists'], expected_entries):
            self.assertEqual(set(allowlist), {'description', 'condition', 'paths', 'regexTarget', 'regexes'})
            self.assertEqual(allowlist['condition'], 'AND')
            self.assertEqual(allowlist['paths'], ['^' + str(name).replace('.', r'\.') + '$'])
            self.assertEqual(allowlist['regexTarget'], 'line')
            expected = [r'^\s*"' + re.escape(path).replace('secrets', '[s]ecrets').replace('passwd', '[p]asswd').replace(r'\-', '-') +
                r'": "' + digest + r'",$' for path, digest in zip(paths, digests)]
            self.assertEqual(allowlist['regexes'], expected)


    def test_scanner_creates_report_for_owner_only_output(self):
        owner = self.root.stat()
        self.assertEqual(stat.S_IMODE(owner.st_mode), 0o700)
        self.assertEqual((self.repo.stat().st_uid, self.repo.stat().st_gid),
                         (owner.st_uid, owner.st_gid))
        write(self.repo, Path('README.md'), b'benign synthetic fixture\n')
        self.commit('benign synthetic fixture')
        self.assertEqual(self.scan('owner-only'), (0, []))
        report = (self.root / 'owner-only.json').stat()
        self.assertEqual((report.st_uid, report.st_gid), (owner.st_uid, owner.st_gid))

    def test_unwritable_output_never_passes_without_report(self):
        write(self.repo, Path('README.md'), b'benign synthetic fixture\n')
        self.commit('benign synthetic fixture')
        unwritable = self.root / 'unwritable'
        unwritable.mkdir(mode=0o500)
        try:
            with self.assertRaisesRegex(AssertionError, 'produced no report'):
                scanner(self.repo, unwritable / 'report.json')
            self.assertFalse((unwritable / 'report.json').exists())
        finally:
            unwritable.chmod(0o700)

    def test_original_and_exact_config_in_git_mode(self):
        write(self.repo, self.manifest, self.raw)
        self.commit('manifest without config')
        code, findings = self.scan('before')
        self.assertEqual(code, 1)
        self.assertEqual(findings, [('generic-api-key', str(self.manifest), line) for line in self.lines])
        write(self.repo, Path('.gitleaks.toml'), self.config)
        self.commit('add exact rule allowlist')
        self.assertEqual(self.scan('after'), (0, []))
        self.assertEqual(scanner(self.repo, self.root / 'directory.json', directory=True), (0, []))

    def test_shallow_synthetic_merge_matches_ci_history_scope(self):
        write(self.repo, Path('README.md'), b'synthetic main\n')
        self.commit('synthetic main')
        git(self.repo, 'switch', '-q', '-c', 'feature')
        write(self.repo, self.manifest, self.raw)
        write(self.repo, Path('.gitleaks.toml'), self.config)
        self.commit('synthetic feature')
        git(self.repo, 'switch', '-q', 'main')
        git(self.repo, 'merge', '-q', '--no-ff', '-m', 'synthetic PR merge', 'feature')
        merge = git(self.repo, 'rev-parse', 'HEAD')
        self.assertEqual(len(git(self.repo, 'show', '-s', '--format=%P', merge).split()), 2)
        clone = self.root / 'checkout'
        result = subprocess.run(['git', 'clone', '-q', '--no-local', '--depth=1',
            self.repo.as_uri(), str(clone)], capture_output=True, timeout=20)
        self.assertEqual(result.returncode, 0, 'synthetic shallow checkout failed')
        self.assertEqual(git(clone, 'rev-parse', 'HEAD'), merge)
        self.assertEqual(git(clone, 'rev-list', '--count', 'HEAD'), '1')
        self.assertEqual(scanner(clone, self.root / 'merge.json'), (0, []))

    def test_negative_controls_still_block(self):
        base = self.raw.decode()
        marker = hashlib.sha256(b'CLB-91 synthetic canary A').hexdigest()
        other = hashlib.sha256(b'CLB-91 synthetic canary B').hexdigest()
        provider = 'ghp_' + hashlib.sha256(b'CLB-91 synthetic GitHub canary').hexdigest()[:36]
        exact = '"' + self.runtime_paths[0] + '": "' + self.runtime_digests[0] + '",'
        other_path = self.runtime_paths[0].replace('/usr/lib/', '/usr/local/lib/').replace('/etc/', '/different/etc/')
        cases = [
            ('other_runtime_key', base.replace(self.runtime_paths[0], other_path), str(self.manifest), 'generic-api-key'),
            ('changed_digest', base.replace(self.runtime_digests[0], other), str(self.manifest), 'generic-api-key'),
            ('same_key_credential', base.replace(self.runtime_digests[0], marker), str(self.manifest), 'generic-api-key'),
            ('other_field_credential', base.replace('  "files": {',
                 '  "files": {\n    "synthetic_api_key": "' + marker + '",'), str(self.manifest), 'generic-api-key'),
            ('extra_same_line', base.replace(exact, exact + ' "synthetic_api_key": "' + marker + '",'),
                 str(self.manifest), 'generic-api-key'),
            ('other_file', base, 'other/runtime.json', 'generic-api-key'),
            ('known_digest_other_field', base.replace(self.runtime_paths[0], 'synthetic_api_key'),
                 str(self.manifest), 'generic-api-key'),
        ]
        # Exercise every approved key/digest, including both Python entries in
        # the synthetic candidate manifest, in Git and directory scan modes.
        for index, (path, digest) in enumerate(zip(self.runtime_paths[1:], self.runtime_digests[1:]), 1):
            line = '"' + path + '": "' + digest + '",'
            cases.extend([
                (f'changed_digest_{index}', base.replace(digest, other), str(self.manifest), 'generic-api-key'),
                (f'other_runtime_key_{index}', base.replace(path, '/different' + path),
                    str(self.manifest), 'generic-api-key'),
                (f'extra_same_line_{index}', base.replace(line, line + ' "synthetic_api_key": "' + marker + '",'),
                    str(self.manifest), 'generic-api-key'),
            ])
        for label, altered, name, rule in cases:
            with self.subTest(label=label):
                fixture = self.root / label
                fixture.mkdir()
                git(fixture, 'init', '-q', '-b', 'main')
                write(fixture, Path('.gitleaks.toml'), self.config)
                if label == 'other_file':
                    write(fixture, Path(name), '\n'.join(line for line in base.splitlines() if any(path in line for path in self.runtime_paths)).encode())
                else:
                    write(fixture, self.manifest, altered.encode())
                git(fixture, 'add', '--', '.')
                git(fixture, 'commit', '-q', '-m', 'synthetic negative control')
                code, findings = scanner(fixture, self.root / (label + '.json'))
                self.assertEqual(code, 1, label)
                directory_code, directory_findings = scanner(fixture, self.root / (label + '-dir.json'), directory=True)
                self.assertEqual(directory_code, 1, label)
                self.assertTrue(any(r == rule and p == name for r, p, _ in directory_findings))
                self.assertTrue(any(found_rule == rule and path == name for found_rule, path, _ in findings),
                    label + ': expected blocking rule/path absent')
                if label == 'other_file':
                    expected = [('generic-api-key', name, line)
                                for line in range(1, len(self.runtime_paths) + 1)]
                    self.assertCountEqual(findings, expected)
                    self.assertCountEqual(directory_findings, expected)

        fixture = self.root / 'default_canaries'
        fixture.mkdir()
        git(fixture, 'init', '-q', '-b', 'main')
        write(fixture, Path('.gitleaks.toml'), self.config)
        write(fixture, Path('canaries.txt'),
            ('synthetic_api_key = "' + marker + '"\n' + 'synthetic_github_token = "' + provider + '"\n').encode())
        git(fixture, 'add', '--', '.')
        git(fixture, 'commit', '-q', '-m', 'synthetic default rule canaries')
        code, findings = scanner(fixture, self.root / 'default_canaries.json')
        self.assertEqual(code, 1)
        self.assertIn('generic-api-key', {rule for rule, _, _ in findings})
        self.assertTrue(any(rule.startswith('github-') for rule, _, _ in findings))


class Amd64ChecksumAllowlistTest(RuntimeChecksumAllowlistTest):
    manifest = AMD64_MANIFEST
    lines = (39, 114)


class Arm64ReferenceChecksumAllowlistTest(RuntimeChecksumAllowlistTest):
    manifest = ARM64_MANIFEST
    lines = (65, 140)


class NativeAcceptedChecksumAllowlistTest(RuntimeChecksumAllowlistTest):
    manifest = NATIVE_ACCEPTED_MANIFEST


class NativeAmd64ChecksumAllowlistTest(RuntimeChecksumAllowlistTest):
    manifest = NATIVE_AMD64_MANIFEST


class NativeArm64ChecksumAllowlistTest(RuntimeChecksumAllowlistTest):
    manifest = NATIVE_ARM64_MANIFEST
    lines = (65, 140)


class NativeCandidateChecksumAllowlistTest(RuntimeChecksumAllowlistTest):
    manifest = NATIVE_CANDIDATE_MANIFEST
    lines = (33, 61, 136)
    runtime_paths = SYNTHETIC_PATHS
    runtime_digests = SYNTHETIC_DIGESTS

    def test_synthetic_source_checksum_provenance(self):
        source = ROOT / 'scripts/tests/linux-semantic/native-adapter/ci/reference/export_inputs.py'
        tree = ast.parse(source.read_text())
        literals = [node.args[1].value for node in ast.walk(tree)
                    if isinstance(node, ast.Call) and isinstance(node.func, ast.Name)
                    and node.func.id == 'add_file' and len(node.args) >= 2
                    and isinstance(node.args[0], ast.Constant)
                    and node.args[0].value == SYNTHETIC_PATHS[0]
                    and isinstance(node.args[1], ast.Constant)]
        self.assertEqual(len(literals), 1)
        self.assertIsInstance(literals[0], bytes)
        self.assertEqual(hashlib.sha256(literals[0]).hexdigest(), SYNTHETIC_DIGESTS[0])
        self.assertTrue(literals[0].splitlines())
        self.assertTrue(all(line.split(b':')[1] == b'x' for line in literals[0].splitlines()))


class CandidateScanTest(unittest.TestCase):
    def test_complete_visible_candidate_directory_and_shallow_merge(self):
        # Include authorized untracked harness materials for local verification;
        # on CI these are tracked. Never commit or stage the actual checkout.
        with tempfile.TemporaryDirectory(prefix='clb91-complete-gitleaks-') as directory:
            root = Path(directory).resolve()
            repo = root / 'repo'
            repo.mkdir()
            git(repo, 'init', '-q', '-b', 'main')
            write(repo, Path('synthetic-base.txt'), b'synthetic base\n')
            git(repo, 'add', '--', '.')
            git(repo, 'commit', '-q', '-m', 'synthetic base')
            git(repo, 'switch', '-q', '-c', 'feature')
            files = subprocess.check_output(['git', '-C', str(ROOT), 'ls-files',
                '--cached', '--others', '--exclude-standard', '-z']).split(b'\0')
            for raw in files:
                if raw:
                    relative = Path(os.fsdecode(raw))
                    source = ROOT / relative
                    if source.is_symlink() or not source.is_file():
                        raise AssertionError('candidate scan refuses nonregular visible file')
                    write(repo, relative, source.read_bytes())
            git(repo, 'add', '--', '.')
            git(repo, 'commit', '-q', '-m', 'complete synthetic candidate')
            self.assertEqual(scanner(repo, root / 'directory.json', directory=True), (0, []))
            self.assertEqual(scanner(repo, root / 'history.json'), (0, []))
            git(repo, 'switch', '-q', 'main')
            git(repo, 'merge', '-q', '--no-ff', '-m', 'synthetic PR merge', 'feature')
            self.assertEqual(len(git(repo, 'show', '-s', '--format=%P', 'HEAD').split()), 2)
            clone = root / 'checkout'
            subprocess.run(['git', 'clone', '-q', '--no-local', '--depth=1', repo.as_uri(),
                            str(clone)], check=True, capture_output=True, timeout=30)
            self.assertEqual(git(clone, 'rev-list', '--count', 'HEAD'), '1')
            self.assertEqual(scanner(clone, root / 'merge.json'), (0, []))


if __name__ == '__main__':
    unittest.main(verbosity=2)
