#!/usr/bin/env python3
"""Focused wrapper regressions; real pinned Docker tests are explicitly opt-in.

Every Git repository/credential here is synthetic and disposable. No JVMs.
"""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('secret_scan', ROOT / 'scripts/secret-scan.py')
SCAN = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SCAN)


def git(repo, *args):
    env = dict(os.environ, GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_SYSTEM=os.devnull,
               GIT_AUTHOR_NAME='Synthetic', GIT_AUTHOR_EMAIL='fixture@example.invalid',
               GIT_COMMITTER_NAME='Synthetic', GIT_COMMITTER_EMAIL='fixture@example.invalid')
    result = subprocess.run(['git', '-C', str(repo), *args], env=env,
                            capture_output=True, timeout=20)
    if result.returncode:
        raise AssertionError('Disposable Git fixture operation failed')
    return result.stdout.decode().strip()


def commit(repo):
    git(repo, 'add', '--all')
    return git(repo, 'commit', '-qm', 'synthetic scanner regression')


class WrapperUnitTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix='clb-secret-unit-')
        self.addCleanup(temp.cleanup)
        self.temp = Path(temp.name).resolve()
        self.report = self.temp / 'report.json'
        self.report.write_text('[]')

    def result(self, text='INF scanned ~12 bytes\nINF no leaks found\n', code=0):
        return subprocess.CompletedProcess([], code, b'', text.encode())

    def test_nonempty_complete_scan(self):
        result = SCAN.parse_result(self.result(), self.report, False)
        self.assertEqual(result['scanned_bytes'], 12)

    def test_zero_missing_and_partial_scans_fail_closed(self):
        for text, history in [
            ('INF scanned ~0 bytes\nINF no leaks found\n', False),
            ('INF no leaks found\n', False),
            ('INF 0 commits scanned.\nINF scanned ~12 bytes\nINF no leaks found\n', True),
            ('INF scanned ~12 bytes\nINF no leaks found\n', True),
            ('ERR git source unavailable\nINF scanned ~12 bytes\nINF no leaks found\n', False),
            ('fatal: git source unavailable\nINF 1 commits scanned.\nINF scanned ~12 bytes\nINF no leaks found\n', True),
            ('WRN partial scan\nINF scanned ~12 bytes\nINF no leaks found\n', False),
        ]:
            with self.subTest(history=history, text=text), self.assertRaises(SCAN.ScanError):
                SCAN.parse_result(self.result(text), self.report, history)

    def test_report_failure_and_exit_disagreement(self):
        for value, code in [('[]', 1), ('{}', 0), ('[]', 128)]:
            self.report.write_text(value)
            with self.assertRaises(SCAN.ScanError):
                SCAN.parse_result(self.result(code=code), self.report, False)
        self.report.unlink()
        self.report.symlink_to(self.temp / 'missing')
        with self.assertRaises(SCAN.ScanError):
            SCAN.parse_result(self.result(), self.report, False)

    def test_malformed_finding_has_static_error(self):
        self.report.write_text('[42]')
        with self.assertRaises(SCAN.ScanError):
            SCAN.parse_result(self.result('INF scanned ~90 bytes\nWRN leaks found: 1\n', 1), self.report, False)

    def test_container_timeout_and_cancellation_cleanup(self):
        for error in (subprocess.TimeoutExpired('synthetic', 1), KeyboardInterrupt()):
            for cleanup_code in (0, 1):
                with mock.patch.object(SCAN.subprocess, 'run', side_effect=[
                    error, subprocess.CompletedProcess([], cleanup_code, b'', b'')]) as run:
                    with self.assertRaises(SCAN.ScanError) as caught:
                        SCAN.container(['docker', 'run'], ['version'])
                    self.assertEqual(run.call_args_list[1].args[0][:3], ['docker', 'rm', '-f'])
                    self.assertEqual('unconfirmed' in str(caught.exception), bool(cleanup_code))

    def test_only_metadata_leaves_report(self):
        value = hashlib.sha256(b'non-secret sentinel').hexdigest()
        row = dict(RuleID='generic-api-key', File='fixture.txt', StartLine=1, Commit='',
                   Fingerprint='fixture.txt:generic-api-key:1', Secret=value, Match=value,
                   Message=value, Link='https://invalid.example/' + value)
        self.report.write_text(json.dumps([row]))
        result = SCAN.parse_result(self.result('INF scanned ~90 bytes\nWRN leaks found: 1\n', 1), self.report, False)
        SCAN.write_sarif(self.temp / 'out.sarif', 'current-source', result['findings'])
        self.assertNotIn(value, json.dumps(result))
        self.assertNotIn(value, (self.temp / 'out.sarif').read_text())

    def test_only_commit_scoped_unique_fingerprints(self):
        for text in ['file:rule:1', 'a' * 40 + ':file:rule:1\n' + 'a' * 40 + ':file:rule:1',
                     'a' * 40 + ':file:rule:*']:
            self.report.write_text(text)
            with self.assertRaises(SCAN.ScanError):
                SCAN.validate_ignore(self.report)
        SCAN.validate_ignore(ROOT / '.gitleaksignore')
        entries = [s for s in (ROOT / '.gitleaksignore').read_text().splitlines() if s and not s.startswith('#')]
        self.assertEqual(len(entries), 4)

    def test_snapshot_includes_untracked_and_refuses_symlinks(self):
        repo = self.temp / 'repo'; repo.mkdir(); git(repo, 'init', '-q')
        (repo / 'untracked.txt').write_text('synthetic input')
        snap = self.temp / 'snap'; snap.mkdir()
        self.assertEqual(SCAN.candidate_snapshot(repo, snap)['files'], 1)
        self.assertEqual((snap / 'untracked.txt').read_text(), 'synthetic input')
        (repo / 'link').symlink_to(self.report)
        with self.assertRaises(SCAN.ScanError):
            SCAN.candidate_snapshot(repo)

    def test_empty_and_broken_common_dir_refuse(self):
        repo = self.temp / 'repo'; repo.mkdir(); git(repo, 'init', '-q')
        with self.assertRaises(SCAN.ScanError):
            SCAN.candidate_snapshot(repo)
        with self.assertRaises(SCAN.ScanError):
            SCAN.repository_state(repo)
        linked = self.temp / 'linked'; linked.mkdir()
        (linked / '.git').write_text('gitdir: /nonexistent-clb-synthetic-git\n')
        with self.assertRaises(SCAN.ScanError):
            SCAN.repository_state(linked)

    def test_readonly_mounts_and_pinned_scanner(self):
        repo = self.temp / 'repo'; common = self.temp / 'common'
        command = SCAN.docker_command(repo, {'common_dir': str(common)}, self.temp / 'source', self.temp / 'out', SCAN.IMAGE)
        mounts = [command[i + 1] for i, arg in enumerate(command) if arg == '--mount']
        self.assertEqual(sum(m.endswith(',readonly') for m in mounts), 3)
        self.assertIn('--read-only', command)
        self.assertIn('--network=none', command)
        with self.assertRaises(SCAN.ScanError):
            SCAN.docker_command(repo, {'common_dir': str(common)}, repo, repo, 'unpinned:latest')


@unittest.skipUnless(os.environ.get('CLB_SECRET_SCAN_DOCKER_TESTS') == '1', 'explicit pinned Docker regressions')
class WrapperDockerTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory(prefix='clb-secret-integration-')
        self.addCleanup(temp.cleanup)
        self.temp = Path(temp.name).resolve()
        self.repo = self.temp / 'repo'; self.repo.mkdir()
        git(self.repo, 'init', '-q')
        (self.repo / '.gitleaks.toml').write_bytes((ROOT / '.gitleaks.toml').read_bytes())
        (self.repo / '.gitleaksignore').write_text('# exact synthetic fingerprints only\n')
        (self.repo / 'README').write_text('Synthetic scanner fixture.\n')
        commit(self.repo)
        self.number = 0

    def scan(self, repo=None):
        self.number += 1
        return SCAN.scan(repo or self.repo, self.temp / f'output-{self.number}')

    def test_regular_checkout_and_current_untracked_finding(self):
        # Preserve the existing exact runtime-checksum exception in both scopes.
        rel = Path('scripts/deploy/stage-compose-env-semantic-runtime.json')
        (self.repo / rel).parent.mkdir(parents=True)
        (self.repo / rel).write_bytes((ROOT / rel).read_bytes())
        commit(self.repo)
        result = self.scan()
        self.assertEqual(result['verdict'], 'PASS')
        self.assertGreater(result['history']['scanned_commits'], 0)
        value = hashlib.sha256(b'synthetic untracked test canary').hexdigest()
        (self.repo / 'untracked.txt').write_text('synthetic_api_key = "' + value + '"\n')
        result = self.scan()
        self.assertEqual(result['verdict'], 'FINDINGS')
        self.assertEqual(len(result['current-source']['findings']), 1)
        self.assertEqual(result['history']['findings'], [])
        self.assertNotIn(value, json.dumps(result))

    def test_all_refs_history_and_shallow_refusal(self):
        initial = git(self.repo, 'rev-parse', 'HEAD')
        value = hashlib.sha256(b'synthetic other ref canary').hexdigest()
        (self.repo / 'only-in-other-ref.txt').write_text('synthetic_api_key = "' + value + '"\n')
        commit(self.repo)
        git(self.repo, 'tag', 'synthetic-side-ref')
        git(self.repo, 'checkout', '--detach', initial)
        result = self.scan()
        self.assertEqual(result['current-source']['findings'], [])
        self.assertEqual(len(result['history']['findings']), 1)
        shallow = self.temp / 'shallow'
        git(self.temp, 'clone', '-q', '--depth=1', self.repo.as_uri(), str(shallow))
        with self.assertRaisesRegex(SCAN.ScanError, 'Shallow'):
            self.scan(shallow)

    def test_managed_worktree_and_unavailable_container_common_dir(self):
        linked = self.temp / 'linked'
        git(self.repo, 'worktree', 'add', '--detach', str(linked), 'HEAD')
        self.assertEqual(self.scan(linked)['verdict'], 'PASS')
        original = SCAN.docker_command

        def without_common(*args, **kwargs):
            command = original(*args, **kwargs)
            common = str(self.repo / '.git')
            index = next(i for i, arg in enumerate(command) if arg.startswith('type=bind,src=' + common + ','))
            del command[index - 1:index + 1]
            return command

        with mock.patch.object(SCAN, 'docker_command', without_common), self.assertRaises(SCAN.ScanError):
            self.scan(linked)

    def test_exact_historical_fingerprints_do_not_hide_future_or_current(self):
        # Match the four real file/rule/line shapes using only generated test values.
        specs = [('docs/product/CONCEPT_CODE_GAP.md', 424, False),
                 ('core-data/src/test/kotlin/com/example/bot/data/client/onboarding/OnboardingFoundationContract.kt', 63, False),
                 ('core-data/src/test/kotlin/com/example/bot/data/repo/PaymentsRepositoryH2Test.kt', 80, False),
                 ('scripts/dev-env.sh', 5, True)]

        def write_canaries(generation):
            for name, line, telegram in specs:
                value = hashlib.sha256((name + str(generation)).encode()).hexdigest()
                if telegram:
                    value = '1000000000' + ':' + 'A' + value[:34]
                path = self.repo / name; path.parent.mkdir(parents=True, exist_ok=True)
                key = 'synthetic_telegram_token' if telegram else 'synthetic_api_key'
                path.write_text('\n' * (line - 1) + key + ' = "' + value + '"\n')

        write_canaries(1); commit(self.repo)
        first = self.scan()
        self.assertEqual(len(first['history']['findings']), 4)
        (self.repo / '.gitleaksignore').write_text('\n'.join(f['Fingerprint'] for f in first['history']['findings']) + '\n')
        still_current = self.scan()
        self.assertEqual(still_current['history']['findings'], [])
        self.assertEqual(len(still_current['current-source']['findings']), 4)
        for name, _, _ in specs:
            (self.repo / name).unlink()
        commit(self.repo)
        self.assertEqual(self.scan()['verdict'], 'PASS')
        write_canaries(2); commit(self.repo)
        future = self.scan()
        self.assertEqual(len(future['history']['findings']), 4)
        self.assertEqual(len(future['current-source']['findings']), 4)
        self.assertIn('telegram-bot-api-token', [f['RuleID'] for f in future['history']['findings']])
        self.assertTrue(set(f['Fingerprint'] for f in first['history']['findings']).isdisjoint(
            f['Fingerprint'] for f in future['history']['findings']))


if __name__ == '__main__':
    unittest.main()
