#!/usr/bin/env python3
"""Pinned, fail-closed current-source AND full available-history secret gate.

Only whitelisted finding metadata leaves the temporary scanner directory. Never
forward raw scanner diagnostics, Match/Secret, commit messages or source snippets.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import tempfile
import uuid
from urllib.parse import quote

IMAGE = 'ghcr.io/gitleaks/gitleaks@sha256:cdbb7c955abce02001a9f6c9f602fb195b7fadc1e812065883f695d1eeaba854'
VERSION = '8.28.0'


class ScanError(Exception):
    """Static, non-sensitive diagnostic suitable for the console."""


def git(repo, *args):
    result = subprocess.run(['git', '-C', str(repo), *args], capture_output=True,
                            timeout=30)
    if result.returncode:
        raise ScanError('Git metadata unavailable; scan refused')
    return result.stdout


def repository_state(repo):
    overrides = {'GIT_DIR', 'GIT_WORK_TREE', 'GIT_COMMON_DIR', 'GIT_OBJECT_DIRECTORY',
                 'GIT_ALTERNATE_OBJECT_DIRECTORIES', 'GIT_INDEX_FILE', 'GIT_SHALLOW_FILE',
                 'GIT_CONFIG', 'GIT_REPLACE_REF_BASE', 'GIT_NO_REPLACE_OBJECTS',
                 'GIT_NAMESPACE', 'GIT_CEILING_DIRECTORIES'}
    if any(k in overrides or k.startswith('GIT_CONFIG_') for k in os.environ):
        raise ScanError('Git environment overrides must be unset for this gate')
    if Path(os.fsdecode(git(repo, 'rev-parse', '--show-toplevel')).strip()).resolve() != repo:
        raise ScanError('Expected repository root')
    common = Path(os.fsdecode(git(repo, 'rev-parse', '--path-format=absolute', '--git-common-dir')).strip()).resolve()
    gitdir = Path(os.fsdecode(git(repo, 'rev-parse', '--absolute-git-dir')).strip()).resolve()
    if not gitdir.is_relative_to(common) or not common.is_dir():
        raise ScanError('Unsupported or inaccessible Git metadata layout')
    if (common / 'objects/info/alternates').exists():
        raise ScanError('External object alternates require explicit scanner support')
    if list((common / 'objects/pack').glob('*.promisor')):
        raise ScanError('Partial clone object storage refused')
    if any(row.startswith(b'160000 ') for row in git(repo, 'ls-files', '--stage').splitlines()):
        raise ScanError('Submodule contents require explicit scanner support')
    if git(repo, 'rev-parse', '--is-shallow-repository').strip() != b'false':
        raise ScanError('Shallow history refused; use a complete checkout')
    head = git(repo, 'rev-parse', 'HEAD').decode().strip()
    if git(repo, 'cat-file', '-t', head).strip() != b'commit':
        raise ScanError('HEAD is not a readable commit')
    commits = sorted(git(repo, 'rev-list', '--all').splitlines())
    if not commits:
        raise ScanError('Empty history refused')
    return {'head': head, 'git_dir': str(gitdir), 'common_dir': str(common),
            'reachable_commits': len(commits),
            'history_sha256': hashlib.sha256(b'\n'.join(commits)).hexdigest()}


def candidate_snapshot(repo, destination=None):
    paths = sorted(set(git(repo, 'ls-files', '-z', '--cached', '--others', '--exclude-standard').split(b'\0')) - {b''})
    files = {}
    total = 0
    for raw in paths:
        rel = Path(os.fsdecode(raw))
        if rel.is_absolute() or '..' in rel.parts or '.git' in rel.parts:
            raise ScanError('Unsafe candidate path')
        source = repo / rel
        if not source.exists() and not source.is_symlink():
            continue  # tracked deletion in the current candidate
        if source.is_symlink() or not source.is_file() or source.resolve() != source:
            raise ScanError('Non-regular candidate file requires explicit scanner support')
        data = source.read_bytes()
        files[str(rel)] = hashlib.sha256(data).hexdigest()
        total += len(data)
        if destination is not None:
            target = destination / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
    if not files or not total:
        raise ScanError('Empty current-source input refused')
    digest = hashlib.sha256(json.dumps(files, sort_keys=True).encode()).hexdigest()
    return {'files': len(files), 'bytes': total, 'sha256': digest}


def validate_ignore(path):
    if not path.is_file() or path.is_symlink():
        raise ScanError('Missing regular historical fingerprint file')
    entries = [line.strip() for line in path.read_text().splitlines()
               if line.strip() and not line.lstrip().startswith('#')]
    if len(entries) != len(set(entries)) or any(
            not re.fullmatch(r'[0-9a-f]{40}:[^:\s]+:[a-z0-9-]+:[1-9][0-9]*', e)
            for e in entries):
        raise ScanError('Only unique commit:file:rule:line fingerprints are permitted')


def docker_command(repo, state, snapshot, output, image, entrypoint=None, current=False):
    if image != IMAGE:
        raise ScanError('Scanner image differs from the reviewed pin')
    command = [os.environ.get('DOCKER_BIN', 'docker'), 'run', '--rm', '--read-only',
               '--network=none', '--cap-drop=ALL', '--security-opt=no-new-privileges',
               '--pids-limit=64', '--memory=512m', '--cpus=2',
               '--user', f'{os.getuid()}:{os.getgid()}', '--tmpfs', '/tmp:mode=1777,nosuid,nodev']
    mounts = [(repo, str(repo), True), (snapshot, '/source', True), (output, '/out', False)]
    common = Path(state['common_dir'])
    if not common.is_relative_to(repo):
        mounts.append((common, str(common), True))
    for source, target, readonly in mounts:
        if any(c in str(source) + target for c in ',\n\r'):
            raise ScanError('Unsupported mount path')
        command += ['--mount', f'type=bind,src={source},dst={target}' + (',readonly' if readonly else '')]
    command += ['-w', '/source' if current else str(repo), '-e', 'GIT_CONFIG_COUNT=1',
                '-e', 'GIT_CONFIG_KEY_0=safe.directory', '-e', f'GIT_CONFIG_VALUE_0={repo}',
                '-e', 'GIT_CONFIG_GLOBAL=/dev/null', '-e', 'GIT_CONFIG_SYSTEM=/dev/null',
                '-e', 'GIT_TERMINAL_PROMPT=0', '-e', 'GIT_NO_REPLACE_OBJECTS=1']
    if entrypoint:
        command += ['--entrypoint', entrypoint]
    return command


def container(command, arguments, image=IMAGE):
    name = 'clb-secret-scan-' + uuid.uuid4().hex
    def interrupted(_signum, _frame):
        raise KeyboardInterrupt
    handlers = {sig: signal.signal(sig, interrupted) for sig in (signal.SIGTERM, signal.SIGHUP)}
    try:
        return subprocess.run([*command, '--name', name, image, *arguments],
                              capture_output=True, timeout=180)
    except (subprocess.TimeoutExpired, KeyboardInterrupt):
        # This random name is allocated only to this invocation, never a shared container.
        for sig in handlers:
            signal.signal(sig, signal.SIG_IGN)
        removed = subprocess.run([command[0], 'rm', '-f', name], capture_output=True, timeout=10)
        if removed.returncode:
            raise ScanError('Scanner interrupted; container cleanup unconfirmed; no PASS') from None
        raise ScanError('Scanner interrupted/timed out; owned container removed; no PASS') from None
    finally:
        for sig, handler in handlers.items():
            signal.signal(sig, handler)


PREFLIGHT = r'''
set -eu
test "$(gitleaks version)" = "v$1"
test "$(git rev-parse HEAD)" = "$2"
test "$(git rev-parse --absolute-git-dir)" = "$3"
test "$(git rev-parse --path-format=absolute --git-common-dir)" = "$4"
test "$(git cat-file -t HEAD)" = commit
test "$(git rev-list --all --count)" = "$5"
test "$(git rev-parse --is-shallow-repository)" = false
test -r "$3/HEAD"
test -r "$4/config"
# Inspect the real container mount flags without attempting a source write.
for target in "$PWD" /source "$4"; do
    case "$target" in "$PWD"/*) target="$PWD";; esac
    awk -v target="$target" '$5 == target && $6 ~ /(^|,)ro(,|$)/ {found=1} END {exit !found}' /proc/self/mountinfo
done
printf 'SCANNER_PREFLIGHT_OK\n'
'''


def parse_result(result, report, history):
    if result.returncode not in (0, 1):
        raise ScanError('Scanner execution failed')
    log = (result.stdout + result.stderr).decode('utf-8', 'replace')
    if re.search(r'\b(?:ERR|FTL|PNC)\b|\bfatal:', log):
        raise ScanError('Scanner reported a source/execution error')
    for line in log.splitlines():
        if 'WRN' in line and not re.search(r'\bWRN leaks found: [0-9]+$', line):
            raise ScanError('Scanner reported an unrecognized warning')
    scanned = re.findall(r'\bscanned ~([0-9]+) bytes\b', log)
    commits = re.findall(r'\b([0-9]+) commits scanned\.', log)
    if len(scanned) != 1 or int(scanned[0]) <= 0:
        raise ScanError('Missing or zero scanned-byte coverage')
    if history and (len(commits) != 1 or int(commits[0]) <= 0):
        raise ScanError('Missing or zero history coverage')
    if report.is_symlink() or not report.is_file() or report.stat().st_size > 8 * 1024 * 1024:
        raise ScanError('Missing/invalid scanner report')
    findings = json.loads(report.read_bytes())
    if not isinstance(findings, list) or (result.returncode == 0) != (not findings):
        raise ScanError('Scanner report and exit status disagree')
    warnings = re.findall(r'\bWRN leaks found: ([0-9]+)$', log, flags=re.M)
    if findings and warnings != [str(len(findings))]:
        raise ScanError('Finding count and diagnostics disagree')
    if not findings and ('INF no leaks found' not in log or warnings):
        raise ScanError('Missing clean scan completion')
    safe = []
    for finding in findings:
        if not isinstance(finding, dict):
            raise ScanError('Invalid finding metadata')
        row = {k: finding.get(k) for k in ('RuleID', 'File', 'StartLine', 'Commit', 'Fingerprint')}
        if (not isinstance(row['RuleID'], str) or not re.fullmatch(r'[a-z0-9-]+', row['RuleID'])
                or not isinstance(row['File'], str) or not row['File']
                or Path(row['File']).is_absolute() or '..' in Path(row['File']).parts
                or any(ord(c) < 32 for c in row['File'])
                or type(row['StartLine']) is not int or row['StartLine'] < 1):
            raise ScanError('Invalid finding metadata')
        commit = row['Commit'] or ''
        if not isinstance(commit, str) or (history and not re.fullmatch(r'[0-9a-f]{40}', commit)) or (not history and commit):
            raise ScanError('Unexpected finding scope')
        fingerprint = (commit + ':' if commit else '') + f"{row['File']}:{row['RuleID']}:{row['StartLine']}"
        if row['Fingerprint'] != fingerprint:
            raise ScanError('Invalid finding fingerprint')
        safe.append(row)
    return {'scanned_bytes': int(scanned[0]), 'scanned_commits': int(commits[0]) if history else None,
            'findings': safe, 'scanner_exit': result.returncode}


def write_sarif(path, scope, findings):
    results = [{'ruleId': f['RuleID'], 'level': 'error',
                'message': {'text': 'Potential secret; source content omitted.'},
                'locations': [{'physicalLocation': {'artifactLocation': {'uri': quote(f['File'])},
                                                   'region': {'startLine': f['StartLine']}}}],
                'partialFingerprints': {'gitleaks/v1': f['Fingerprint']}}
               for f in findings]
    sarif = {'version': '2.1.0', '$schema': 'https://json.schemastore.org/sarif-2.1.0.json',
             'runs': [{'tool': {'driver': {'name': 'gitleaks', 'version': VERSION}},
                       'automationDetails': {'id': scope + '/'}, 'results': results}]}
    path.write_text(json.dumps(sarif, indent=2) + '\n')


def scan(repo, output, image=IMAGE):
    repo = repo.resolve()
    state = repository_state(repo)
    output = output.resolve()
    if output.is_relative_to(repo) or output.is_relative_to(Path(state['common_dir'])):
        raise ScanError('Reports must be outside source and Git metadata')
    output.mkdir(mode=0o700, parents=True, exist_ok=False)
    with tempfile.TemporaryDirectory(prefix='clb-secret-scan-') as temporary:
        scratch = Path(temporary).resolve()
        snapshot, raw = scratch / 'source', scratch / 'reports'
        snapshot.mkdir(); raw.mkdir()
        source = candidate_snapshot(repo, snapshot)
        validate_ignore(snapshot / '.gitleaksignore')
        if not (snapshot / '.gitleaks.toml').is_file():
            raise ScanError('Missing explicit scanner configuration')
        command = docker_command(repo, state, snapshot, raw, image, 'sh')
        probe = container(command, ['-c', PREFLIGHT, 'preflight', VERSION, state['head'],
                                   state['git_dir'], state['common_dir'], str(state['reachable_commits'])], image)
        if probe.returncode or probe.stdout.strip() != b'SCANNER_PREFLIGHT_OK':
            raise ScanError('Container cannot verify read-only Git history; scan refused')
        summary = {'image': image, 'version': VERSION, 'repository': state, 'candidate': source,
                   'config_sha256': hashlib.sha256((snapshot / '.gitleaks.toml').read_bytes()).hexdigest(),
                   'ignore_sha256': hashlib.sha256((snapshot / '.gitleaksignore').read_bytes()).hexdigest()}
        for scope in ('current-source', 'history'):
            current = scope == 'current-source'
            command = docker_command(repo, state, snapshot, raw, image, current=True)
            report = raw / (scope + '.json')
            args = ['detect', '--source', '.' if current else str(repo),
                    '--config', '.gitleaks.toml',
                    '--gitleaks-ignore-path', '.gitleaksignore',
                    '--report-format', 'json', '--report-path', '/out/' + report.name,
                    '--redact=100', '--no-banner', '--no-color', '--log-level', 'info']
            if scope == 'current-source':
                args += ['--no-git']
            else:
                args += ['--log-opts=--full-history --all --diff-filter=tuxdb']
            summary[scope] = parse_result(container(command, args, image), report, scope == 'history')
            write_sarif(output / (scope + '.sarif'), scope, summary[scope]['findings'])
        if repository_state(repo) != state or candidate_snapshot(repo) != source:
            raise ScanError('Repository changed during scan; result is not reusable')
        summary['verdict'] = 'FINDINGS' if any(summary[s]['findings'] for s in ('current-source', 'history')) else 'PASS'
        (output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
        return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--image', default=IMAGE)
    args = parser.parse_args()
    output = args.output or Path(tempfile.gettempdir()) / ('clb-secret-result-' + uuid.uuid4().hex)
    try:
        summary = scan(Path(__file__).resolve().parents[1], output, args.image)
        for scope in ('current-source', 'history'):
            result = summary[scope]
            print(f"{scope}: bytes={result['scanned_bytes']} commits={result['scanned_commits']} findings={len(result['findings'])}")
        print(f"Secret scan {summary['verdict']}; sanitized evidence: {output}")
        return 0 if summary['verdict'] == 'PASS' else 1
    except ScanError as error:
        print(f'Secret scan BLOCKED: {error}')
    except (OSError, ValueError, subprocess.SubprocessError):
        print('Secret scan BLOCKED: unavailable or invalid input/tool; no PASS')
    return 2


if __name__ == '__main__':
    raise SystemExit(main())
