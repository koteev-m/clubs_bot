#!/usr/bin/env python3
"""Read-only clubs-local identity gate; loaded/session instructions require agent attestation.

No network, repair, source-content scan, or platform-state inspection. The fixed
primary checkout anchors identity; registered worktrees need not share its name.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import stat
import subprocess
from urllib.parse import urlsplit


PRIMARY_ROOT = Path('/Users/maksimmartynov/IdeaProjects/clubs_bot')
PROJECT = 'clubs_bot'
REPOSITORY = 'koteev-m/clubs_bot'
STOP = 'STOP_PROJECT_CONTEXT_CONTAMINATION'
OK = 'PROJECT_IDENTITY_OK'
INSTRUCTIONS = ('AGENTS.override.md', 'AGENTS.md')
PATH_OVERRIDES = ('GIT_DIR', 'GIT_WORK_TREE', 'GIT_INDEX_FILE', 'GIT_COMMON_DIR')


class Rejected(Exception):
    """Only fixed, non-sensitive reason codes reach stdout."""


def require(condition: bool, reason: str) -> None:
    if not condition:
        raise Rejected(reason)


def beneath(path: Path, root: Path) -> bool:
    return path == root or root in path.parents


def present(path: Path) -> bool:
    return path.exists() or path.is_symlink()


def metadata_text(path: Path) -> str:
    require(stat.S_ISREG(path.lstat().st_mode), 'metadata_not_regular')
    with path.open('rb') as stream:
        raw = stream.read(4097)
    require(len(raw) <= 4096, 'metadata_too_large')
    return raw.decode('utf-8').strip()


def repository_root(path: Path) -> Path | None:
    for directory in (path, *path.parents):
        if present(directory / '.git'):
            return directory
    return None


def local_git_dir(top: Path, primary: Path, common: Path) -> Path:
    marker = top / '.git'
    if top == primary:
        require(marker.is_dir() and marker.resolve() == common, 'primary_git_dir')
        return common
    raw = metadata_text(marker)
    require(raw.startswith('gitdir: '), 'worktree_git_marker')
    directory = (top / raw[8:]).resolve(strict=True)
    require(directory.parent == common / 'worktrees', 'foreign_git_dir')
    require((common / 'worktrees').resolve() == common / 'worktrees', 'foreign_worktree_registry')
    require((directory / metadata_text(directory / 'commondir')).resolve() == common,
            'foreign_common_dir')
    require((directory / metadata_text(directory / 'gitdir')).resolve() == marker,
            'unregistered_worktree')
    return directory


def normalize_remote(raw: str) -> str | None:
    """Canonical GitHub HTTPS, scp-style SSH and ssh:// only; no alias inference."""
    if not raw or len(raw) > 1024 or any(c.isspace() for c in raw):
        return None
    if raw.startswith('git@github.com:'):
        path = raw[len('git@github.com:'):]
    else:
        try:
            url = urlsplit(raw)
            if url.hostname != 'github.com' or url.query or url.fragment or url.password:
                return None
            if url.scheme == 'https' and url.username is None and url.port in (None, 443):
                path = url.path.removeprefix('/')
            elif url.scheme == 'ssh' and url.username == 'git' and url.port in (None, 22):
                path = url.path.removeprefix('/')
            else:
                return None
        except ValueError:
            return None
    path = path.removesuffix('/').removesuffix('.git')
    return REPOSITORY if path.lower() == REPOSITORY else None


def git(cwd: Path, env: dict[str, str], *args: str) -> str:
    # Keep the inspected environment. Never clear redirections and retry.
    result = subprocess.run(
        ['git', '--no-optional-locks', '--no-pager', *args], cwd=cwd, env=env,
        capture_output=True, text=True, timeout=10, check=False,
    )
    require(result.returncode == 0, 'git_identity_unavailable')
    require(len(result.stdout) <= 131072, 'git_output_limit')
    return result.stdout.rstrip('\n')


def instruction_paths(top: Path, cwd: Path, scopes: list[str], explicit: list[str],
                      primary: Path, common: Path, registered: list[Path]) -> list[str]:
    """Enumerate filesystem scope only; never open instruction/source contents."""
    directories = set((cwd, *cwd.parents))
    for scope in scopes:
        path = (cwd / scope).resolve(strict=True)
        require(beneath(path, top), 'foreign_instruction_scope')
        if not path.is_dir():
            path = path.parent
        require(repository_root(path) == top, 'foreign_instruction_scope')
        directories.update((path, *path.parents))
    candidates = {directory / name for directory in directories for name in INSTRUCTIONS
                  if present(directory / name)}
    declared = set()
    for raw in explicit:
        path = Path(os.path.abspath(cwd / raw))
        require(path.name in INSTRUCTIONS and present(path), 'unknown_instruction_path')
        candidates.add(path)
        declared.add(path)
    require(any(present(top / name) for name in INSTRUCTIONS), 'root_instructions_missing')
    require(len(candidates) <= 64, 'instruction_count_limit')
    labels = []
    for path in sorted(candidates):
        resolved = path.resolve(strict=True)
        owner = repository_root(path.parent)
        if owner is not None:
            require(beneath(resolved, owner) and repository_root(resolved.parent) == owner,
                    'foreign_instruction_path')
            if owner == top:
                label = path.relative_to(top).as_posix()
            else:
                # Explicitly loaded primary/sibling clubs instructions are valid
                # only with independently checked ownership and registration.
                require(path in declared or path.parent in top.parents, 'foreign_instruction_path')
                require(registered.count(owner) == 1, 'foreign_instruction_path')
                local_git_dir(owner, primary, common)
                prefix = 'primary' if owner == primary else f'worktree[{registered.index(owner)}]'
                label = f'{prefix}/{path.relative_to(owner).as_posix()}'
        else:
            # An unowned ancestor is global filesystem scope, not a repo identity
            # assertion. Arbitrary extra paths and foreign-owned ancestors fail.
            require(path.parent in top.parents and owner is None and resolved == path,
                    'foreign_instruction_path')
            label = f'ancestor[{list(top.parents).index(path.parent)}]/{path.name}'
        require(stat.S_ISREG(resolved.stat().st_mode), 'instruction_not_regular')
        require(len(label) <= 256, 'instruction_path_limit')
        labels.append(label)
    return labels


def check(args: argparse.Namespace, *, cwd: Path, env: dict[str, str],
          primary: Path = PRIMARY_ROOT) -> dict:
    """primary is injectable by isolated fixture tests, never by CLI or environment."""
    report = {
        'project_id': PROJECT, 'task_id': None, 'repo_identity': None,
        **{key: 'NOT_CHECKED' for key in (
            'top_level_status', 'common_dir_status', 'worktree_status', 'remote_status',
            'git_override_status', 'filesystem_instruction_status', 'expected_revision_status',
        )},
        'verdict': STOP,
    }
    stage = 'top_level_status'
    try:
        require(args.project_id == PROJECT, 'project_id_mismatch')
        require(re.fullmatch(r'CLB-[A-Za-z0-9][A-Za-z0-9._-]{0,95}', args.task_id or '') is not None,
                'task_id_mismatch')
        report['task_id'] = args.task_id
        cwd = cwd.resolve(strict=True)
        require(primary.resolve(strict=True) == primary, 'primary_root_redirected')
        common = primary / '.git'
        require(common.is_dir() and common.resolve() == common, 'primary_common_dir_unavailable')
        top = repository_root(cwd)
        require(top is not None, 'repository_missing')
        stage = 'common_dir_status'
        directory = local_git_dir(top, primary, common)
        index = directory / 'index'
        require(index.resolve() == index, 'foreign_index')
        if index.exists():
            require(stat.S_ISREG(index.stat().st_mode) and index.stat().st_nlink == 1,
                    'ambiguous_index')
        stage = 'git_override_status'
        expected = dict(zip(PATH_OVERRIDES, (directory, top, index, common)))
        for key, value in env.items():
            if key in expected:
                require(bool(value) and (cwd / value).resolve() == expected[key], 'unsafe_git_override')
            elif key.startswith('GIT_'):
                # Do not let config/object/discovery/trace overrides change identity
                # or cause trace writes. --no-pager disables the environment's
                # legitimate GIT_PAGER; neither other control changes identity.
                require(key in ('GIT_OPTIONAL_LOCKS', 'GIT_TERMINAL_PROMPT', 'GIT_PAGER'),
                        'unmodelled_git_override')
        report[stage] = 'PASS'
        stage = 'top_level_status'
        require(Path(git(cwd, env, 'rev-parse', '--show-toplevel')).resolve() == top, 'top_level_mismatch')
        require(git(cwd, env, 'rev-parse', '--is-inside-work-tree') == 'true', 'not_worktree')
        report[stage] = 'PASS'
        stage = 'common_dir_status'
        require(Path(git(cwd, env, 'rev-parse', '--path-format=absolute', '--git-common-dir')).resolve() == common,
                'common_dir_mismatch')
        require(Path(git(cwd, env, 'rev-parse', '--absolute-git-dir')).resolve() == directory, 'git_dir_mismatch')
        require(Path(git(cwd, env, 'rev-parse', '--path-format=absolute', '--git-path', 'index')).resolve() == index,
                'index_mismatch')
        report[stage] = 'PASS'
        stage = 'worktree_status'
        records = git(cwd, env, 'worktree', 'list', '--porcelain', '-z').split('\0\0')
        matches = []
        registered = []
        for record in records:
            fields = record.split('\0')
            if fields[0].startswith('worktree '):
                root = Path(fields[0][9:]).resolve()
                if not any(v.startswith(('prunable', 'bare')) for v in fields):
                    registered.append(root)
                if root == top:
                    matches.append(fields)
        require(len(matches) == 1 and not any(v.startswith(('prunable', 'bare')) for v in matches[0]),
                'unregistered_worktree')
        report[stage] = 'PASS'
        stage = 'remote_status'
        for command in (('config', '--get-all', 'remote.origin.url'),
                        ('remote', 'get-url', '--all', 'origin'),
                        ('remote', 'get-url', '--push', '--all', 'origin')):
            urls = git(cwd, env, *command).splitlines()
            require(len(urls) == 1 and normalize_remote(urls[0]) == REPOSITORY, 'origin_mismatch_or_ambiguous')
        report['repo_identity'] = REPOSITORY
        report[stage] = 'PASS'
        stage = 'filesystem_instruction_status'
        report['filesystem_instruction_paths'] = instruction_paths(
            top, cwd, args.scope, args.instruction_path, primary, common, registered,
        )
        report[stage] = 'PASS'
        stage = 'expected_revision_status'
        head = git(cwd, env, 'rev-parse', '--verify', 'HEAD')
        require(re.fullmatch(r'[0-9a-f]{40}|[0-9a-f]{64}', head) is not None, 'head_unavailable')
        if args.expected_head is not None:
            require(args.expected_head == head, 'expected_head_mismatch')
            report[stage] = 'PASS'
        else:
            report[stage] = 'NOT_REQUESTED'
        report['verdict'] = OK
    except (Rejected, OSError, ValueError, RuntimeError, subprocess.SubprocessError) as exc:
        report[stage] = 'FAIL'
        report['reason'] = str(exc) if isinstance(exc, Rejected) else 'identity_unavailable'
    return report


class Parser(argparse.ArgumentParser):
    def error(self, message: str) -> None:
        raise Rejected('invalid_arguments')


def main() -> int:
    parser = Parser(description=__doc__)
    parser.add_argument('--project-id', default=PROJECT)
    parser.add_argument('--task-id', required=True)
    parser.add_argument('--expected-head', '--expected-base', dest='expected_head',
                        help='exact full HEAD object ID, only when specified by the task')
    parser.add_argument('--scope', action='append', default=[],
                        help='additional existing target file/directory within this worktree')
    parser.add_argument('--instruction-path', action='append', default=[],
                        help='additional known loaded repository instruction path (not platform discovery)')
    try:
        args = parser.parse_args()
        report = check(args, cwd=Path.cwd(), env=dict(os.environ))
    except (Rejected, OSError):
        report = {'project_id': PROJECT, 'verdict': STOP, 'reason': 'invalid_or_unavailable_input'}
    print(json.dumps(report, sort_keys=True))
    return 0 if report['verdict'] == OK else 1


if __name__ == '__main__':
    raise SystemExit(main())
