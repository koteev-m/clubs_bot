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
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = Path('scripts/deploy/stage-compose-env-semantic-runtime.json')
DOCKER_PROFILE = Path('scripts/deploy/stage-docker-compose-profile.json')
HELPER = Path('scripts/deploy/remote-compose-release.sh')
# Official checksum-verified v8.28.0 Darwin arm64 release, test-only optional backend.
# CI retains the digest-pinned container below; never adopt this as stage Runtime.
LOCAL_BINARY_DIGEST = '5588b5d942dffa048720f7e6e1d274283219fb5722a2c7564d22e83ba39087d7'
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


def embedded_public_line():
    lines = [line for line in (ROOT / HELPER).read_text().splitlines()
             if line.startswith("CLB91_PROFILE['runtime']['files'] = ")]
    if len(lines) != 1:
        raise AssertionError('embedded public profile line is ambiguous')
    statement = ast.parse(lines[0]).body[0]
    rows = ast.literal_eval(statement.value.generators[0].iter)
    reconstructed = {prefix + leaf: digest for prefix, entries in rows for leaf, digest in entries}
    if (len(reconstructed) != 196 or len(reconstructed) != sum(len(entries) for _, entries in rows)
            or reconstructed != json.loads((ROOT / DOCKER_PROFILE).read_text())['runtime']['files']):
        raise AssertionError('embedded public tuple map differs from fixed profile')
    return lines[0]


def embedded_literal_pattern():
    # No wildcard, substring/hash-presence filter or match-only omitted-prefix gap.
    # v8.28.0 location() may include exactly the preceding LF in finding.Line.
    # Accept only that separator or fragment start; retain the entire literal line.
    return r'^(?:\n)?' + re.escape(embedded_public_line()).replace('secrets', '[s]ecrets') + '$'


def embedded_public_fragment():
    # Actual v8.28.0 directory finding.Line, verified against the pinned scanner.
    # The unterminated final line uses the fragment's start index. Bind every byte;
    # a changed fragment boundary refuses rather than widening the exception.
    raw = (ROOT / HELPER).read_bytes()
    fragment = raw[161864:190536].decode('ascii')
    if (len(fragment) != 28672 or raw.count(fragment.encode()) != 1
            or "CLB91_PROFILE['runtime']['files'] = " not in fragment):
        raise AssertionError('public scanner fragment identity refused')
    for path, digest in zip(RUNTIME_PATHS, RUNTIME_DIGESTS):
        if (repr(str(Path(path).parent) + '/') not in fragment
                or repr((Path(path).name, digest)) not in fragment):
            raise AssertionError('public scanner fragment lacks a full pinned runtime key')
    return fragment


def embedded_fragment_pattern():
    # Fully literal scanner representation: no omitted-prefix/match-only gap.
    return '^' + re.escape(embedded_public_fragment()).replace('\\\n', r'\n').replace('secrets', '[s]ecrets') + '$'


def scanner_command(repo, output, directory):
    local = os.environ.get('CLB91_TEST_GITLEAKS')
    environment = None
    if local:
        tool = Path(local)
        try:
            info = tool.lstat()
            if (not tool.is_absolute() or not stat.S_ISREG(info.st_mode)
                    or info.st_mode & 0o022 or info.st_size != 21258274
                    or hashlib.sha256(tool.read_bytes()).hexdigest() != LOCAL_BINARY_DIGEST):
                raise AssertionError('test scanner executable identity refused')
            version = subprocess.run([str(tool), 'version'], capture_output=True, timeout=10)
            if version.returncode != 0 or version.stdout.strip() != b'8.28.0':
                raise AssertionError('test scanner version refused')
        except (OSError, subprocess.TimeoutExpired):
            raise AssertionError('test scanner identity unavailable') from None
        environment = {key: value for key, value in os.environ.items()
                       if not key.startswith('GITLEAKS_')}
        environment.update(GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_SYSTEM=os.devnull)
        command = [str(tool), 'detect', '--source', '.', '--report-format', 'json',
                   '--report-path', str(output), '--redact', '--no-banner', '--no-color']
        if (repo / '.gitleaks.toml').is_file():
            command.extend(['--config', '.gitleaks.toml'])
    else:
        # Same pinned CI image, read-only/networkless/unprivileged synthetic scope.
        owner = output.parent.stat()
        command = [os.environ.get('DOCKER_BIN', 'docker'), 'run', '--rm', '--read-only',
            '--network=none', '--cap-drop=ALL', '--security-opt=no-new-privileges',
            '--tmpfs', '/tmp:mode=1777,nosuid,nodev', '--user', f'{owner.st_uid}:{owner.st_gid}',
            '-v', str(repo) + ':/repo:ro', '-v', str(output.parent) + ':/out',
            '-w', '/repo', '-e', 'GIT_CONFIG_COUNT=1', '-e', 'GIT_CONFIG_KEY_0=safe.directory',
            '-e', 'GIT_CONFIG_VALUE_0=/repo', IMAGE, 'detect', '--source', '.',
            '--report-format', 'json', '--report-path', '/out/' + output.name, '--redact', '--no-color']
    if directory:
        command.append('--no-git')
    return command, environment


def scanner(repo, output, *, directory=False):
    owner = output.parent.stat()
    repo_owner = repo.stat()
    if (repo_owner.st_uid, repo_owner.st_gid) != (owner.st_uid, owner.st_gid):
        raise AssertionError('synthetic source/output owners differ')
    if output.exists() or output.is_symlink():
        raise AssertionError('scanner refuses an existing report')
    command, environment = scanner_command(repo, output, directory)
    try:
        result = subprocess.run(command, cwd=repo, env=environment,
                                capture_output=True, timeout=90)
    except (OSError, subprocess.TimeoutExpired):
        raise AssertionError('pinned Gitleaks could not start or finish') from None
    if not output.is_file() or output.is_symlink():
        raise AssertionError(f'pinned Gitleaks produced no report (scanner exit {result.returncode})')
    if result.returncode not in (0, 1):
        raise AssertionError(f'pinned Gitleaks failed outside finding status (exit {result.returncode})')
    try:
        info = output.stat()
        if ((info.st_uid, info.st_gid) != (owner.st_uid, owner.st_gid)
                or info.st_mode & 0o022 or info.st_size > 8388608):
            raise AssertionError('scanner report identity or bound refused')
        findings = json.loads(output.read_bytes())
    except (OSError, ValueError):
        raise AssertionError('pinned Gitleaks report is unreadable or malformed') from None
    if type(findings) is not list or (result.returncode == 0) != (len(findings) == 0):
        raise AssertionError('scanner exit and redacted report disagree')
    diagnostics = result.stdout + result.stderr
    invalid_warning = False
    for line in diagnostics.splitlines():
        if b'WRN' in line:
            warning = re.search(rb'\bWRN leaks found: ([0-9]+)$', line)
            if (warning is None or result.returncode != 1
                    or int(warning.group(1)) != len(findings)):
                invalid_warning = True
    if re.search(rb'\b(?:ERR|FTL|PNC)\b', diagnostics) or invalid_warning:
        raise AssertionError('pinned Gitleaks reported an incomplete scan or source error')
    rows = []
    for item in findings:
        if (type(item) is not dict or type(item.get('RuleID')) is not str
                or not item['RuleID'] or type(item.get('File')) is not str
                or not item['File'] or '\n' in item['File'] or '\x00' in item['File']
                or type(item.get('StartLine')) is not int or item['StartLine'] <= 0):
            raise AssertionError('scanner finding schema refused')
        rows.append((item['RuleID'], item['File'], item['StartLine']))
    return result.returncode, rows


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
        files = manifest['files'] if 'files' in manifest else manifest['runtime']['files']
        self.assertEqual(tuple(files[name] for name in self.runtime_paths), self.runtime_digests)
        config = tomllib.loads(self.config.decode())
        self.assertEqual(set(config), {'title', 'extend', 'rules'})
        self.assertEqual(config['extend'], {'useDefault': True})
        self.assertEqual(len(config['rules']), 1)
        rule = config['rules'][0]
        self.assertEqual(set(rule), {'id', 'allowlists'})
        self.assertEqual(rule['id'], 'generic-api-key')
        expected_entries = [(name, RUNTIME_PATHS, RUNTIME_DIGESTS) for name in PYTHON_MANIFESTS]
        expected_entries.append((NATIVE_CANDIDATE_MANIFEST, SYNTHETIC_PATHS, SYNTHETIC_DIGESTS))
        self.assertEqual(len(rule['allowlists']), len(expected_entries) + 2)
        for allowlist, (name, paths, digests) in zip(rule['allowlists'], expected_entries):
            self.assertEqual(set(allowlist), {'description', 'condition', 'paths', 'regexTarget', 'regexes'})
            self.assertEqual(allowlist['condition'], 'AND')
            self.assertEqual(allowlist['paths'], ['^' + str(name).replace('.', r'\.') + '$'])
            self.assertEqual(allowlist['regexTarget'], 'line')
            expected = [r'^\s*"' + re.escape(path).replace('secrets', '[s]ecrets').replace('passwd', '[p]asswd').replace(r'\-', '-') +
                r'": "' + digest + r'",$' for path, digest in zip(paths, digests)]
            self.assertEqual(allowlist['regexes'], expected)
        fixed = rule['allowlists'][-2]
        self.assertEqual(set(fixed), {'description', 'condition', 'paths', 'regexTarget', 'regexes'})
        self.assertEqual(fixed['condition'], 'AND')
        self.assertEqual(fixed['paths'], ['^scripts/deploy/stage-docker-compose-profile\\.json$'])
        self.assertEqual(fixed['regexTarget'], 'line')
        self.assertEqual(fixed['regexes'], [r'^\s*"' + re.escape(path).replace('secrets', '[s]ecrets').replace(r'\-', '-') +
            r'": "' + digest + r'",$' for path, digest in zip(RUNTIME_PATHS, RUNTIME_DIGESTS)])
        embedded = rule['allowlists'][-1]
        self.assertEqual(set(embedded), {'description', 'condition', 'paths', 'regexTarget', 'regexes'})
        self.assertEqual(embedded['condition'], 'AND')
        self.assertEqual(embedded['paths'], ['^scripts/deploy/remote-compose-release\\.sh$'])
        self.assertEqual(embedded['regexTarget'], 'line')
        self.assertEqual(embedded['regexes'], [embedded_literal_pattern(), embedded_fragment_pattern()])


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


class FixedDockerChecksumAllowlistTest(RuntimeChecksumAllowlistTest):
    manifest = DOCKER_PROFILE
    lines = (59, 135)


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


class DockerEmbeddedAllowlistTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='clb91-fixed-checksums-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.config = (ROOT / '.gitleaks.toml').read_bytes()
        self.profile = (ROOT / DOCKER_PROFILE).read_bytes()
        self.helper = (ROOT / HELPER).read_bytes()
        self.public_line = embedded_public_line()

    def fixture(self, label, profile=None, helper=None, *, config=None, other_path=None):
        repo = self.root / label
        repo.mkdir()
        git(repo, 'init', '-q', '-b', 'main')
        write(repo, Path('.gitleaks.toml'), self.config if config is None else config)
        if profile is not None:
            write(repo, DOCKER_PROFILE if other_path is None else Path(other_path), profile)
        if helper is not None:
            write(repo, HELPER if other_path is None else Path(other_path), helper)
        git(repo, 'add', '--', '.')
        git(repo, 'commit', '-q', '-m', 'synthetic checksum control')
        return repo

    def both(self, repo, label):
        return [(mode, scanner(repo, self.root / (label + '-' + mode + '.json'), directory=(mode == 'dir')))
                for mode in ('dir', 'git')]

    def assert_blocked(self, repo, label, path, *, provider=False):
        for mode, (code, findings) in self.both(repo, label):
            self.assertEqual(code, 1, (label, mode))
            self.assertTrue(any(rule == 'generic-api-key' and name == str(path)
                                for rule, name, _ in findings), (label, mode))
            if provider:
                self.assertTrue(any(rule.startswith('github-') for rule, _, _ in findings))

    def test_original_four_without_new_exceptions_and_zero_with_exact_rules(self):
        marker = b'\n[[rules.allowlists]]\ndescription = "CLB-91: exact public Python checksums in fixed Docker profile"'
        old = self.config[:self.config.index(marker)]
        self.assertEqual(len(tomllib.loads(old.decode())['rules'][0]['allowlists']), 7)
        # Every tracked and authorized untracked candidate source, not just the two findings files.
        repo = self.root / 'complete-before'
        repo.mkdir()
        git(repo, 'init', '-q', '-b', 'main')
        paths = subprocess.check_output(['git', '-C', str(ROOT), 'ls-files',
            '--cached', '--others', '--exclude-standard', '-z']).split(b'\0')
        for raw in paths:
            if raw:
                rel = Path(os.fsdecode(raw)); source = ROOT / rel
                self.assertTrue(source.is_file() and not source.is_symlink())
                write(repo, rel, source.read_bytes())
        write(repo, Path('.gitleaks.toml'), old)
        git(repo, 'add', '--', '.'); git(repo, 'commit', '-q', '-m', 'complete synthetic old policy')
        expected = [('generic-api-key', str(DOCKER_PROFILE), 59),
                    ('generic-api-key', str(DOCKER_PROFILE), 135),
                    ('generic-api-key', str(HELPER), 3489), ('generic-api-key', str(HELPER), 3489)]
        for mode, (code, findings) in self.both(repo, 'complete-before'):
            self.assertEqual(code, 1); self.assertCountEqual(findings, expected, mode)
        write(repo, Path('.gitleaks.toml'), self.config)
        git(repo, 'add', '--', '.'); git(repo, 'commit', '-q', '-m', 'exact synthetic policy')
        for mode, result in self.both(repo, 'complete-after'):
            self.assertEqual(result, (0, []), mode)

    def test_both_digest_changes_block_in_json_and_embedded(self):
        for index, digest in enumerate(RUNTIME_DIGESTS):
            changed = ('1' if digest[0] != '1' else '2') + digest[1:]
            for label, path, data in [('json', DOCKER_PROFILE, self.profile), ('embedded', HELPER, self.helper)]:
                label += str(index)
                altered = data.replace(digest.encode(), changed.encode())
                self.assertNotEqual(data, altered)
                repo = self.fixture(label, profile=altered if path == DOCKER_PROFILE else None,
                                    helper=altered if path == HELPER else None)
                self.assert_blocked(repo, label, path)

    def test_full_key_prefix_and_leaf_changes_block_in_both_sources(self):
        for index, key in enumerate(RUNTIME_PATHS):
            leaf = Path(key).name
            for kind in ('prefix', 'leaf'):
                for form, path, data in [('json', DOCKER_PROFILE, self.profile), ('embedded', HELPER, self.helper)]:
                    label = form + kind + str(index)
                    if kind == 'prefix':
                        old = key if form == 'json' else str(Path(key).parent) + '/'
                        replacement = '/different' + old
                    else:
                        old = leaf; replacement = leaf.replace('secrets', 'secretsx')
                    altered = data.replace(old.encode(), replacement.encode())
                    self.assertNotEqual(data, altered)
                    repo = self.fixture(label, profile=altered if path == DOCKER_PROFILE else None,
                                        helper=altered if path == HELPER else None)
                    self.assert_blocked(repo, label, path)

    def test_same_public_values_in_other_files_and_other_fields_block(self):
        for label, data, other in [('profile', self.profile, 'other/runtime.json'),
                                   ('helper', self.helper, 'other/helper.sh')]:
            repo = self.fixture('other-' + label, profile=data, other_path=other)
            self.assert_blocked(repo, 'other-' + label, other)
        for index, digest in enumerate(RUNTIME_DIGESTS):
            value = ('synthetic_api_key = "' + digest + '"\n').encode()
            repo = self.fixture('other-field' + str(index), helper=value)
            self.assert_blocked(repo, 'other-field' + str(index), HELPER)

    def test_additional_generic_credential_on_same_embedded_line_never_hides(self):
        value = hashlib.sha256(b'CLB-91 exact-line synthetic API canary').hexdigest()
        credential = ' synthetic_api_key = "' + value + '" '
        for index, altered_line in enumerate((credential + self.public_line,
                    self.public_line + credential,
                    self.public_line.replace('for leaf, digest in entries}', credential + 'for leaf, digest in entries}'))):
            altered = self.helper.replace(self.public_line.encode(), altered_line.encode())
            repo = self.fixture('same-line' + str(index), helper=altered)
            self.assert_blocked(repo, 'same-line' + str(index), HELPER)
            for mode, (_, findings) in self.both(repo, 'same-line-confirm' + str(index)):
                # Fragmented directory scans may still allow the two public pins.
                # Prove the additional credential itself is a real redacted finding.
                report = self.root / ('same-line-confirm' + str(index) + '-' + mode + '.json')
                raw_report = report.read_bytes()
                self.assertTrue(value.encode() not in raw_report, 'synthetic match must be redacted')
                self.assertTrue(any(item['RuleID'] == 'generic-api-key'
                    and item['File'] == str(HELPER) and 'synthetic_api_key' in item['Match']
                    for item in json.loads(raw_report)), mode)

    def test_other_generic_and_github_rules_remain_default(self):
        value = hashlib.sha256(b'CLB-91 API negative control').hexdigest()
        provider = 'ghp_' + hashlib.sha256(b'CLB-91 GitHub negative control').hexdigest()[:36]
        extra = ('\nsynthetic_api_key = "' + value + '"\nsynthetic_github_token = "' + provider + '"\n').encode()
        repo = self.fixture('defaults', helper=self.helper + extra)
        self.assert_blocked(repo, 'defaults', HELPER, provider=True)

    def test_path_anchoring_rejects_prefix_and_suffix_neighbors(self):
        for index, other in enumerate(('prefix/' + str(HELPER), str(HELPER) + '.copy',
                                     'prefix/' + str(DOCKER_PROFILE), str(DOCKER_PROFILE) + '.copy')):
            data = self.helper if 'remote-compose' in other else self.profile
            repo = self.fixture('neighbor' + str(index), profile=data, other_path=other)
            self.assert_blocked(repo, 'neighbor' + str(index), other)

    def test_embedded_expression_is_literal_and_all_196_keys_are_public(self):
        expression = tomllib.loads(self.config.decode())['rules'][0]['allowlists'][-1]['regexes'][0]
        self.assertEqual(expression, embedded_literal_pattern())
        self.assertIsNotNone(re.fullmatch(expression, self.public_line))
        self.assertIsNotNone(re.fullmatch(expression, '\n' + self.public_line))
        self.assertIsNone(re.fullmatch(expression, '\n\n' + self.public_line))
        self.assertIsNone(re.fullmatch(expression, ' ' + self.public_line))
        # Exact round-trip construction, not merely a checksum-presence test.
        for value in RUNTIME_DIGESTS:
            self.assertIsNone(re.fullmatch(expression, self.public_line.replace(value, value + 'a')))
        self.assertIsNone(re.fullmatch(expression, self.public_line + ' '))
        self.assertIsNone(re.fullmatch(expression, 'prefix' + self.public_line))
        fragment_expression = tomllib.loads(self.config.decode())['rules'][0]['allowlists'][-1]['regexes'][1]
        self.assertEqual(fragment_expression, embedded_fragment_pattern())
        fragment = embedded_public_fragment()
        self.assertIsNotNone(re.fullmatch(fragment_expression, fragment))
        self.assertIsNone(re.fullmatch(fragment_expression, fragment + ' '))
        for value in RUNTIME_DIGESTS:
            self.assertIsNone(re.fullmatch(fragment_expression, fragment.replace(value, value + 'a')))


class ScannerFailureContractTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='clb91-scanner-refusal-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.repo = self.root / 'repo'; self.repo.mkdir()

    def invoke(self, code, payload, *, error=None, diagnostics=b'PRIVATE_SCANNER_CANARY'):
        output = self.root / 'result.json'
        def run(*args, **kwargs):
            if payload is not None:
                output.write_bytes(payload); output.chmod(0o600)
            if error is not None:
                raise error
            return subprocess.CompletedProcess([], code, b'', diagnostics)
        with mock.patch(__name__ + '.scanner_command', return_value=(['fixed-test-command'], None)), \
                mock.patch(__name__ + '.subprocess.run', side_effect=run):
            return scanner(self.repo, output)

    def test_scanner_requests_plain_diagnostics(self):
        command, _ = scanner_command(self.repo, self.root / 'result.json', False)
        self.assertIn('--no-color', command)

    def test_valid_finding_warning_is_not_a_source_error(self):
        payload = b'[{"RuleID":"generic-api-key","File":"canary.txt","StartLine":1}]'
        self.assertEqual((1, [('generic-api-key', 'canary.txt', 1)]),
                         self.invoke(1, payload, diagnostics=b'WRN leaks found: 1'))

    def test_scanner_error_and_missing_report_never_pass(self):
        for code, payload in ((2, b'[]'), (0, None), (1, None)):
            with self.subTest(code=code, payload_present=payload is not None):
                with self.assertRaises(AssertionError): self.invoke(code, payload)
                (self.root / 'result.json').unlink(missing_ok=True)

    def test_source_error_or_incomplete_scan_diagnostics_never_pass(self):
        finding = b'[{"RuleID":"generic-api-key","File":"canary.txt","StartLine":1}]'
        for code, payload, diagnostics in (
                (0, b'[]', b'ERR PRIVATE_SCANNER_CANARY'),
                (0, b'[]', b'WRN PRIVATE_SCANNER_CANARY'),
                (0, b'[]', b'WRN leaks found: 1'),
                (0, b'[]', b'WRN leaks found: 0'),
                (1, finding, b'WRN leaks found: 2'),
                (1, finding, b'WRN leaks found: 0')):
            with self.subTest(code=code, severity=diagnostics[:3]):
                with self.assertRaisesRegex(AssertionError, 'incomplete scan') as caught:
                    self.invoke(code, payload, diagnostics=diagnostics)
                self.assertNotIn('PRIVATE_SCANNER_CANARY', str(caught.exception))
                (self.root / 'result.json').unlink(missing_ok=True)

    def test_spawn_timeout_and_partial_report_never_pass(self):
        for error in (OSError('PRIVATE_SCANNER_CANARY'), subprocess.TimeoutExpired('fixed-test', 90)):
            with self.subTest(kind=type(error).__name__):
                with self.assertRaises(AssertionError) as caught: self.invoke(0, b'[]', error=error)
                self.assertNotIn('PRIVATE_SCANNER_CANARY', str(caught.exception))
                (self.root / 'result.json').unlink(missing_ok=True)

    def test_invalid_output_schema_and_exit_disagreement_never_pass(self):
        examples = [(0, b'invalid JSON'), (0, b'{}'), (1, b'[]'),
                    (0, b'[{"RuleID":"generic-api-key","File":"canary.txt","StartLine":1}]'),
                    (1, b'[{}]'), (1, b'[{"RuleID":"generic-api-key","File":"canary.txt","StartLine":true}]')]
        for code, payload in examples:
            with self.subTest(code=code, shape=len(payload)):
                with self.assertRaises(AssertionError): self.invoke(code, payload)
                (self.root / 'result.json').unlink(missing_ok=True)

    def test_stale_or_symlink_report_never_reuses_previous_pass(self):
        output = self.root / 'result.json'
        output.write_bytes(b'[]')
        with self.assertRaisesRegex(AssertionError, 'existing report'): scanner(self.repo, output)
        output.unlink(); target = self.root / 'old.json'; target.write_bytes(b'[]'); output.symlink_to(target)
        with self.assertRaisesRegex(AssertionError, 'existing report'): scanner(self.repo, output)

    def test_explicit_local_scanner_substitution_refused_before_execution(self):
        tool = self.root / 'substituted'; tool.write_bytes(b'PRIVATE_EXECUTABLE_CANARY'); tool.chmod(0o755)
        with mock.patch.dict(os.environ, {'CLB91_TEST_GITLEAKS': str(tool)}):
            with self.assertRaisesRegex(AssertionError, 'identity refused'):
                scanner_command(self.repo, self.root / 'result.json', False)


if __name__ == '__main__':
    unittest.main(verbosity=2)
