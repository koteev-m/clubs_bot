#!/usr/bin/env python3
"""Exercise the two CLB-91 checksum exceptions with the pinned real scanner.

All canaries and Git history are synthetic and disposable. Never print matches.
"""
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
IMAGE = 'ghcr.io/gitleaks/gitleaks@sha256:cdbb7c955abce02001a9f6c9f602fb195b7fadc1e812065883f695d1eeaba854'
RUNTIME_PATHS = (
    '/usr/lib/python3.12/__pycache__/' + 'se' + 'crets.cpython-312.pyc',
    '/usr/lib/python3.12/' + 'se' + 'crets.py',
)
RUNTIME_DIGESTS = (
    '08cd4dd20cb98e58ed935d9353928e74a80b17ea3412a9d6009431249cefbff6',
    '277000574358a6ecda4bb40e73332ae81a3bc1c8e1fa36f50e5c6a7d4d3f0f17',
)


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


def scanner(repo, output):
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
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='clb91-gitleaks-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.repo = self.root / 'repo'
        self.repo.mkdir()
        git(self.repo, 'init', '-q', '-b', 'main')
        self.raw = (ROOT / MANIFEST).read_bytes()
        self.config = (ROOT / '.gitleaks.toml').read_bytes()

    def scan(self, label):
        return scanner(self.repo, self.root / (label + '.json'))

    def commit(self, label):
        git(self.repo, 'add', '--', '.')
        git(self.repo, 'commit', '-q', '-m', label)

    def test_exact_configuration_and_manifest_identity(self):
        manifest = json.loads(self.raw)
        self.assertEqual(tuple(manifest['files'][name] for name in RUNTIME_PATHS), RUNTIME_DIGESTS)
        config = tomllib.loads(self.config.decode())
        self.assertEqual(set(config), {'title', 'extend', 'rules'})
        self.assertEqual(config['extend'], {'useDefault': True})
        self.assertEqual(len(config['rules']), 1)
        rule = config['rules'][0]
        self.assertEqual(set(rule), {'id', 'allowlists'})
        self.assertEqual(rule['id'], 'generic-api-key')
        self.assertEqual(len(rule['allowlists']), 1)
        allowlist = rule['allowlists'][0]
        self.assertEqual(set(allowlist), {'description', 'condition', 'paths', 'regexTarget', 'regexes'})
        self.assertEqual(allowlist['condition'], 'AND')
        self.assertEqual(allowlist['paths'], [r'^scripts/deploy/stage-compose-env-semantic-runtime\.json$'])
        self.assertEqual(allowlist['regexTarget'], 'line')
        expected = [r'^\s*"' + re.escape(name).replace('secrets', '[s]ecrets').replace(r'\-', '-') +
            r'": "' + digest + r'",$' for name, digest in zip(RUNTIME_PATHS, RUNTIME_DIGESTS)]
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
        write(self.repo, MANIFEST, self.raw)
        self.commit('manifest without config')
        code, findings = self.scan('before')
        self.assertEqual(code, 1)
        self.assertEqual(findings, [('generic-api-key', str(MANIFEST), 65),
                                    ('generic-api-key', str(MANIFEST), 140)])
        write(self.repo, Path('.gitleaks.toml'), self.config)
        self.commit('add exact rule allowlist')
        self.assertEqual(self.scan('after'), (0, []))

    def test_shallow_synthetic_merge_matches_ci_history_scope(self):
        write(self.repo, Path('README.md'), b'synthetic main\n')
        self.commit('synthetic main')
        git(self.repo, 'switch', '-q', '-c', 'feature')
        write(self.repo, MANIFEST, self.raw)
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
        exact = '"' + RUNTIME_PATHS[0] + '": "' + RUNTIME_DIGESTS[0] + '",'
        cases = (
            ('changed_digest', base.replace(RUNTIME_DIGESTS[0], other), str(MANIFEST), 'generic-api-key'),
            ('same_key_credential', base.replace(RUNTIME_DIGESTS[0], marker), str(MANIFEST), 'generic-api-key'),
            ('other_field_credential', base.replace('  "files": {',
                 '  "files": {\n    "synthetic_api_key": "' + marker + '",'), str(MANIFEST), 'generic-api-key'),
            ('extra_same_line', base.replace(exact, exact + ' "synthetic_api_key": "' + marker + '",'),
                 str(MANIFEST), 'generic-api-key'),
            ('other_file', base, 'other/runtime.json', 'generic-api-key'),
        )
        for label, altered, name, rule in cases:
            with self.subTest(label=label):
                fixture = self.root / label
                fixture.mkdir()
                git(fixture, 'init', '-q', '-b', 'main')
                write(fixture, Path('.gitleaks.toml'), self.config)
                if label == 'other_file':
                    write(fixture, Path(name), '\n'.join(base.splitlines()[63:66] + base.splitlines()[138:141]).encode())
                else:
                    write(fixture, MANIFEST, altered.encode())
                git(fixture, 'add', '--', '.')
                git(fixture, 'commit', '-q', '-m', 'synthetic negative control')
                code, findings = scanner(fixture, self.root / (label + '.json'))
                self.assertEqual(code, 1, label)
                self.assertTrue(any(found_rule == rule and path == name for found_rule, path, _ in findings),
                    label + ': expected blocking rule/path absent')

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


if __name__ == '__main__':
    unittest.main(verbosity=2)
