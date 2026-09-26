#!/usr/bin/env python3
"""Offline real-Git fixtures. No real foreign repository or journal is accessed."""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'scripts/project-identity-guard.py'
SPEC = importlib.util.spec_from_file_location('project_identity_guard', SCRIPT)
guard = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(guard)


class GuardTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='clubs-identity-fixture-')
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name).resolve()
        # Isolation belongs to fixture setup, never to the production guard.
        self.env = {k: v for k, v in os.environ.items() if not k.startswith('GIT_')}
        self.primary = self.base / 'clubs_bot'
        self.make_repo(self.primary)
        self.worktree = self.base / 'arbitrary-managed-name'
        self.git(self.primary, 'worktree', 'add', '--detach', str(self.worktree))
        self.foreign = self.base / 'synthetic-hookah_bot_ANT'
        self.make_repo(self.foreign, 'git@github.com:koteev-m/hookah_bot_ANT.git')
        self.head = self.git(self.primary, 'rev-parse', 'HEAD').strip()

    def git(self, cwd, *args):
        return subprocess.check_output(
            ['git', '--no-optional-locks', '-c', 'user.name=Fixture',
             '-c', 'user.email=fixture@example.invalid', '-c', 'commit.gpgsign=false',
             '-c', 'core.hooksPath=/dev/null', *args],
            cwd=cwd, env=self.env, stderr=subprocess.PIPE, text=True,
        )

    def make_repo(self, root, remote='https://github.com/koteev-m/clubs_bot.git'):
        root.mkdir(parents=True)
        self.git(root, 'init', '--quiet', '--template=')
        (root / 'AGENTS.md').write_text('# Synthetic clubs fixture instructions\n')
        self.git(root, 'add', 'AGENTS.md')
        self.git(root, 'commit', '--quiet', '-m', 'fixture')
        self.git(root, 'remote', 'add', 'origin', remote)

    def check(self, cwd=None, env=None, **kwargs):
        options = dict(project_id='clubs_bot', task_id='CLB-110', expected_head=None,
                       scope=[], instruction_path=[])
        options.update(kwargs)
        return guard.check(argparse.Namespace(**options), cwd=cwd or self.primary,
                           env=self.env if env is None else env, primary=self.primary)

    def assert_ok(self, result):
        self.assertEqual(result['verdict'], guard.OK, result)

    def assert_stop(self, result, reason=None):
        self.assertEqual(result['verdict'], guard.STOP, result)
        if reason:
            self.assertEqual(result['reason'], reason, result)

    def snapshot(self):
        return {p.relative_to(self.base).as_posix():
                ('link', os.readlink(p)) if p.is_symlink() else
                ('file', hashlib.sha256(p.read_bytes()).hexdigest())
                for p in self.base.rglob('*') if p.is_file() or p.is_symlink()}

    def test_primary_clb_pass_without_invented_revision(self):
        result = self.check()
        self.assert_ok(result)
        self.assertEqual(result['expected_revision_status'], 'NOT_REQUESTED')

    def test_registered_worktree_and_subdirectory_pass(self):
        sub = self.worktree / 'src'
        sub.mkdir()
        (sub / 'AGENTS.override.md').write_text('clubs local override\n')
        result = self.check(cwd=sub)
        self.assert_ok(result)
        self.assertEqual(result['filesystem_instruction_paths'], ['AGENTS.md', 'src/AGENTS.override.md'])

    def test_expected_head_pass_and_mismatch_stop(self):
        self.assert_ok(self.check(expected_head=self.head))
        self.assert_stop(self.check(expected_head='0' * 40), 'expected_head_mismatch')
        self.assert_stop(self.check(expected_head='HEAD'), 'expected_head_mismatch')

    def test_project_and_task_prefix_rejected(self):
        for task in ('HT-110', '', 'CLB-', 'CLB-110\nHT-1', 'CLB-' + 'a' * 1000):
            with self.subTest(task=task[:30]):
                self.assert_stop(self.check(task_id=task), 'task_id_mismatch')
        self.assert_stop(self.check(project_id='Hookah_Tootah'), 'project_id_mismatch')

    def test_foreign_repository_and_foreign_worktree_rejected(self):
        self.assert_stop(self.check(cwd=self.foreign))
        other = self.base / 'foreign-managed'
        self.git(self.foreign, 'worktree', 'add', '--detach', str(other))
        self.assert_stop(self.check(cwd=other), 'foreign_git_dir')

    def test_same_basename_unrelated_clone_rejected_even_with_expected_origin(self):
        other = self.base / 'unrelated/clubs_bot'
        self.make_repo(other)
        self.git(other, 'checkout', '--detach')
        self.assert_stop(self.check(cwd=other))

    def test_copied_worktree_marker_is_not_registration(self):
        fake = self.base / 'unregistered'
        fake.mkdir()
        shutil.copyfile(self.worktree / '.git', fake / '.git')
        shutil.copyfile(self.primary / 'AGENTS.md', fake / 'AGENTS.md')
        self.assert_stop(self.check(cwd=fake), 'unregistered_worktree')

    def test_foreign_common_dir_in_worktree_metadata_rejected(self):
        directory = Path(self.git(self.worktree, 'rev-parse', '--absolute-git-dir').strip())
        (directory / 'commondir').write_text(str(self.foreign / '.git'))
        self.assert_stop(self.check(cwd=self.worktree), 'foreign_common_dir')

    def test_foreign_origin_missing_origin_and_ambiguous_origin_rejected(self):
        self.git(self.primary, 'remote', 'set-url', 'origin', 'git@github.com:koteev-m/hookah_bot_ANT.git')
        self.assert_stop(self.check(), 'origin_mismatch_or_ambiguous')
        self.git(self.primary, 'remote', 'remove', 'origin')
        self.assert_stop(self.check(), 'git_identity_unavailable')
        self.git(self.primary, 'remote', 'add', 'origin', 'https://github.com/koteev-m/clubs_bot')
        self.git(self.primary, 'config', '--add', 'remote.origin.url', 'https://github.com/koteev-m/clubs_bot.git')
        self.assert_stop(self.check(), 'origin_mismatch_or_ambiguous')

    def test_effective_origin_rewrite_and_foreign_pushurl_rejected(self):
        self.git(self.primary, 'config', 'url.https://github.com/koteev-m/hookah_bot_ANT.insteadOf',
                 'https://github.com/koteev-m/clubs_bot.git')
        self.assert_stop(self.check(), 'origin_mismatch_or_ambiguous')
        self.git(self.primary, 'config', '--remove-section', 'url.https://github.com/koteev-m/hookah_bot_ANT')
        self.git(self.primary, 'config', 'remote.origin.pushurl', 'https://github.com/koteev-m/hookah_bot_ANT')
        self.assert_stop(self.check(), 'origin_mismatch_or_ambiguous')

    def test_unsafe_git_path_overrides_stop_before_any_git_process(self):
        overrides = {
            'GIT_DIR': self.foreign / '.git', 'GIT_WORK_TREE': self.foreign,
            'GIT_INDEX_FILE': self.foreign / '.git/index', 'GIT_COMMON_DIR': self.foreign / '.git',
        }
        for key, value in overrides.items():
            with self.subTest(key=key), mock.patch.object(guard, 'git') as invoke:
                self.assert_stop(self.check(env={**self.env, key: str(value)}), 'unsafe_git_override')
                invoke.assert_not_called()

    def test_equivalent_overrides_primary_and_registered_worktree_pass(self):
        for root in (self.primary, self.worktree):
            directory = Path(self.git(root, 'rev-parse', '--absolute-git-dir').strip())
            env = {**self.env, 'GIT_DIR': os.path.relpath(directory, root),
                   'GIT_WORK_TREE': '.', 'GIT_INDEX_FILE': str(directory / 'index'),
                   'GIT_COMMON_DIR': str(self.primary / '.git'), 'GIT_OPTIONAL_LOCKS': '0'}
            with self.subTest(root=root.name):
                self.assert_ok(self.check(cwd=root, env=env))

    def test_empty_and_unmodelled_overrides_rejected(self):
        for key, value in (('GIT_DIR', ''), ('GIT_CONFIG_COUNT', '1'), ('GIT_NAMESPACE', 'foreign'),
                           ('GIT_OBJECT_DIRECTORY', str(self.foreign / '.git/objects')),
                           ('GIT_TRACE', str(self.base / 'must-not-exist'))):
            with self.subTest(key=key):
                self.assert_stop(self.check(env={**self.env, key: value}))
        self.assertFalse((self.base / 'must-not-exist').exists())

    def test_legitimate_git_pager_is_disabled_not_cleared(self):
        marker = self.base / 'pager-must-not-run'
        env = {**self.env, 'GIT_PAGER': f'touch {marker}'}
        before = env.copy()
        self.assert_ok(self.check(env=env))
        self.assertFalse(marker.exists())
        self.assertEqual(env, before)

    def test_foreign_index_symlink_and_hardlink_rejected(self):
        index = self.primary / '.git/index'
        index.unlink()
        index.symlink_to(self.foreign / '.git/index')
        self.assert_stop(self.check(), 'foreign_index')
        index.unlink()
        os.link(self.foreign / '.git/index', index)
        self.assert_stop(self.check(), 'ambiguous_index')

    def test_cross_worktree_index_override_rejected(self):
        self.assert_stop(self.check(cwd=self.worktree, env={
            **self.env, 'GIT_INDEX_FILE': str(self.primary / '.git/index'),
        }), 'unsafe_git_override')

    def test_foreign_instruction_symlink_and_explicit_path_rejected_without_content_read(self):
        foreign_instruction = self.foreign / 'AGENTS.md'
        with mock.patch.object(Path, 'read_text', side_effect=AssertionError('no source reads')):
            self.assert_stop(self.check(instruction_path=[str(foreign_instruction)]), 'foreign_instruction_path')
            (self.primary / 'AGENTS.override.md').symlink_to(foreign_instruction)
            self.assert_stop(self.check(), 'foreign_instruction_path')

    def test_foreign_ancestor_instruction_rejected(self):
        # Registered clubs worktree nested in a synthetic foreign repository.
        nested = self.foreign / 'clubs-managed'
        self.git(self.primary, 'worktree', 'add', '--detach', str(nested))
        self.assert_stop(self.check(cwd=nested), 'foreign_instruction_path')

    def test_explicit_primary_and_registered_worktree_instructions_pass_both_directions(self):
        result = self.check(cwd=self.worktree, instruction_path=[str(self.primary / 'AGENTS.md')])
        self.assert_ok(result)
        self.assertIn('primary/AGENTS.md', result['filesystem_instruction_paths'])
        self.assert_ok(self.check(instruction_path=[str(self.worktree / 'AGENTS.md')]))
        sibling = self.base / 'another-managed-worktree'
        self.git(self.primary, 'worktree', 'add', '--detach', str(sibling))
        self.assert_ok(self.check(cwd=self.worktree, instruction_path=[str(sibling / 'AGENTS.md')]))

    def test_registered_worktree_inside_primary_accepts_clubs_ancestor_instructions(self):
        nested = self.primary / 'managed-worktree'
        self.git(self.primary, 'worktree', 'add', '--detach', str(nested))
        result = self.check(cwd=nested)
        self.assert_ok(result)
        self.assertEqual(result['filesystem_instruction_paths'], ['primary/AGENTS.md', 'AGENTS.md'])

    def test_explicit_copied_unregistered_instructions_and_symlink_escape_stop(self):
        fake = self.base / 'unregistered'
        fake.mkdir()
        shutil.copyfile(self.worktree / '.git', fake / '.git')
        shutil.copyfile(self.worktree / 'AGENTS.md', fake / 'AGENTS.md')
        self.assert_stop(self.check(instruction_path=[str(fake / 'AGENTS.md')]), 'foreign_instruction_path')
        (self.worktree / 'AGENTS.md').unlink()
        (self.worktree / 'AGENTS.md').symlink_to(self.foreign / 'AGENTS.md')
        self.assert_stop(self.check(instruction_path=[str(self.worktree / 'AGENTS.md')]), 'foreign_instruction_path')

    def test_explicit_registered_instruction_owner_metadata_must_still_match(self):
        directory = Path(self.git(self.worktree, 'rev-parse', '--absolute-git-dir').strip())
        (directory / 'gitdir').write_text(str(self.foreign / '.git'))
        self.assert_stop(self.check(instruction_path=[str(self.worktree / 'AGENTS.md')]))

    def test_scope_enumerates_nested_instructions_and_rejects_nested_repository(self):
        scope = self.primary / 'nested'
        scope.mkdir()
        (scope / 'AGENTS.md').write_text('clubs\n')
        (scope / 'AGENTS.override.md').write_text('clubs override\n')
        result = self.check(scope=['nested'])
        self.assert_ok(result)
        self.assertEqual(result['filesystem_instruction_paths'],
                         ['AGENTS.md', 'nested/AGENTS.md', 'nested/AGENTS.override.md'])
        self.git(scope, 'init', '--quiet', '--template=')
        self.assert_stop(self.check(scope=['nested']), 'foreign_instruction_scope')
        self.assert_stop(self.check(scope=[str(self.foreign)]), 'foreign_instruction_scope')

    def test_unowned_ancestor_is_enumerated_but_not_claimed_as_repo_owned(self):
        (self.base / 'AGENTS.md').write_text('global instruction requires agent attestation\n')
        result = self.check()
        self.assert_ok(result)
        self.assertIn('ancestor[0]/AGENTS.md', result['filesystem_instruction_paths'])

    def test_historical_references_and_denylist_do_not_fail(self):
        text = ('DOCUMENTED_INCIDENT_REFERENCE: Hookah_Tootah hookah_bot_ANT HT-19; '
                'historical SSH alias git@hookah; denylist includes foreign names\n')
        (self.primary / 'incident.md').write_text(text)
        (self.primary / 'AGENTS.md').write_text('# clubs policy\n' + text)
        with mock.patch.object(Path, 'read_text', side_effect=AssertionError('no document scanning')):
            self.assert_ok(self.check())

    def test_missing_or_unavailable_identity_fails_closed(self):
        outside = self.base / 'outside'
        outside.mkdir()
        self.assert_stop(self.check(cwd=outside), 'repository_missing')
        (self.primary / 'AGENTS.md').unlink()
        self.assert_stop(self.check(), 'root_instructions_missing')
        with mock.patch.object(guard.subprocess, 'run', side_effect=FileNotFoundError('secret-path')):
            result = self.check()
        self.assert_stop(result, 'identity_unavailable')
        self.assertNotIn('secret-path', json.dumps(result))

    def test_primary_anchor_symlink_rejected(self):
        link = self.base / 'redirected-primary'
        link.symlink_to(self.primary, target_is_directory=True)
        options = argparse.Namespace(project_id='clubs_bot', task_id='CLB-110', expected_head=None,
                                     scope=[], instruction_path=[])
        result = guard.check(options, cwd=self.primary, env=self.env, primary=link)
        self.assert_stop(result, 'primary_root_redirected')

    def test_guard_pass_and_failure_have_no_filesystem_or_environment_effects(self):
        before = self.snapshot()
        environment = self.env.copy()
        original_cwd = Path.cwd()
        self.assert_ok(self.check())
        self.assert_ok(self.check(cwd=self.worktree))
        self.assert_stop(self.check(task_id='HT-1'))
        self.assert_stop(self.check(cwd=self.foreign))
        self.assertEqual(before, self.snapshot())
        self.assertEqual(environment, self.env)
        self.assertEqual(original_cwd, Path.cwd())

    def test_output_is_bounded_and_does_not_echo_sensitive_inputs(self):
        secret = 'credential-MUST-NOT-APPEAR'
        self.git(self.primary, 'remote', 'set-url', 'origin', f'https://user:{secret}@github.com/koteev-m/clubs_bot')
        result = self.check()
        self.assert_stop(result)
        output = json.dumps(result)
        self.assertLess(len(output), 2048)
        self.assertNotIn(secret, output)
        self.assertNotIn(str(self.base), output)

    def test_integration_agent_attestation_then_machine_then_substantive_action(self):
        # This is an orchestration demonstration with declared agent observations,
        # NOT an implementation of platform/session instruction inspection.
        def sequence(active_foreign=False, audit=False, both_named=False,
                     metadata_authorized=False, task='CLB-110', cwd=None):
            events = ['agent_checks_loaded_chain']
            # Foreign *controlling* instructions always stop. Authorized audit
            # metadata may be present as data, never as clubs mutation authority.
            if active_foreign:
                return events + [guard.STOP]
            if audit and not (both_named and metadata_authorized):
                return events + [guard.STOP]
            events.append('agent_attests_explicit_audit_scope' if audit else 'agent_attests_clubs_only')
            result = self.check(cwd=cwd, task_id=task)
            events.append(result['verdict'])
            if result['verdict'] == guard.OK:
                events.append('substantive_action')
            return events

        self.assertEqual(sequence(), ['agent_checks_loaded_chain', 'agent_attests_clubs_only',
                                      guard.OK, 'substantive_action'])
        for kwargs in ({'active_foreign': True}, {'task': 'HT-1'}, {'audit': True},
                       {'active_foreign': True, 'audit': True, 'both_named': True}):
            with self.subTest(kwargs=kwargs):
                self.assertNotIn('substantive_action', sequence(**kwargs))
        self.assertIn('substantive_action', sequence(audit=True, both_named=True,
                      metadata_authorized=True))
        self.assertNotIn('substantive_action', sequence(active_foreign=True, audit=True,
                         both_named=True, metadata_authorized=True))
        self.assertNotIn('substantive_action', sequence(audit=True, both_named=True,
                         metadata_authorized=True, cwd=self.foreign))


class ContractTests(unittest.TestCase):
    def test_synthetic_remote_forms(self):
        accepted = ('https://github.com/koteev-m/clubs_bot',
                    'https://github.com/koteev-m/clubs_bot.git/',
                    'https://github.com:443/koteev-m/clubs_bot.git',
                    'git@github.com:koteev-m/clubs_bot.git',
                    'ssh://git@github.com/koteev-m/clubs_bot.git',
                    'ssh://git@github.com:22/koteev-m/clubs_bot')
        rejected = ('https://github.com/koteev-m/hookah_bot_ANT',
                    'git@hookah:koteev-m/clubs_bot', 'file:///tmp/clubs_bot',
                    'https://github.com.evil/koteev-m/clubs_bot',
                    'https://github.com/koteev-m/clubs_bot?token=secret',
                    'https://github.com/koteev-m/clubs_bot#fragment',
                    'ssh://git@github.com:2222/koteev-m/clubs_bot',
                    'https://user:secret@github.com/koteev-m/clubs_bot',
                    'https://github.com//koteev-m/clubs_bot', '')
        for url in accepted:
            with self.subTest(url=url):
                self.assertEqual(guard.normalize_remote(url), guard.REPOSITORY)
        for url in rejected:
            with self.subTest(url=url):
                self.assertIsNone(guard.normalize_remote(url))

    def test_agent_instruction_contract_and_local_script_link(self):
        instructions = (ROOT / 'AGENTS.md').read_text()
        for term in ('active loaded instruction chain', 'active foreign instructions = 0',
                     'DOCUMENTED_INCIDENT_REFERENCE', 'ACTIVE_FOREIGN_INSTRUCTION',
                     guard.OK, guard.STOP, 'hidden platform/session instruction state',
                     'metadata-level comparison', 'clubs-owned journal', '--expected-head',
                     '--scope', '--instruction-path'):
            self.assertIn(term, instructions)
        self.assertIn('scripts/project-identity-guard.py', instructions)
        self.assertTrue(SCRIPT.is_file())

    def test_cli_bad_arguments_fail_as_json_without_echo(self):
        result = subprocess.run([sys.executable, '-I', '-S', '-B', str(SCRIPT),
                                 '--secret-unknown=must-not-appear'],
                                capture_output=True, text=True, check=False)
        self.assertEqual(result.returncode, 1)
        self.assertEqual(json.loads(result.stdout)['verdict'], guard.STOP)
        self.assertEqual(result.stderr, '')
        self.assertNotIn('must-not-appear', result.stdout)


if __name__ == '__main__':
    unittest.main(verbosity=2)
