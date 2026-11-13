#!/usr/bin/env python3
"""Portable CI wiring/failure controls, not a native semantic execution claim."""
import hashlib
import importlib.util
import json
import lzma
import shutil
import signal
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from copy import deepcopy

ROOT = Path(__file__).resolve().parents[2]
HERE = ROOT / 'scripts/tests/linux-semantic'

def load(name, file):
    spec = importlib.util.spec_from_file_location(name, HERE / file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

ci = load('amd64_ci', 'run-amd64-ci.py')
downloads = load('amd64_downloads', 'prepare-amd64-downloads.py')
GCC_WARNINGS = frozenset(('array-bounds', 'discarded-qualifiers', 'format',
    'format-overflow', 'format-truncation', 'implicit-function-declaration',
    'incompatible-pointer-types', 'int-conversion', 'maybe-uninitialized',
    'restrict', 'return-type', 'sign-compare', 'sizeof-pointer-memaccess',
    'stringop-overflow', 'stringop-truncation', 'type-limits',
    'uninitialized', 'unused-but-set-variable', 'unused-function',
    'unused-parameter', 'unused-variable', 'none', 'other', 'UNKNOWN'))


def bounded_clb195_gcc(stderr):
    """Do not relay arbitrary portable-test stderr into the Lint log."""
    match = re.fullmatch(rb'CLB195_GCC_DIAG '
        rb'source=(adapter\.c|core-tests\.c|generated_contract\.h|UNKNOWN) '
        rb'line=(0|[1-9][0-9]{0,5}) warning=([a-z-]+|UNKNOWN)\n', stderr)
    if match is None:
        return 'CLB195_GCC_DIAG source=UNKNOWN line=0 warning=UNKNOWN'
    source, line, warning = match.groups()
    if warning.decode('ascii') not in GCC_WARNINGS or ((source == b'UNKNOWN') != (line == b'0')):
        return 'CLB195_GCC_DIAG source=UNKNOWN line=0 warning=UNKNOWN'
    return match[0].decode('ascii').strip()


class LogsPolicyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        raw = (ROOT / '.github/workflows/tests.yml').read_bytes()
        cls.workflow = json.loads(subprocess.run(['ruby', '-ryaml', '-rjson', '-e',
            'puts JSON.generate(YAML.safe_load(STDIN.read, aliases: true))'],
            input=raw, capture_output=True, check=True, timeout=10).stdout)

    def validate(self, workflow):
        return subprocess.run(['ruby', '-I', str(ROOT / 'scripts'),
            '-rvalidate-workflow-capabilities', '-rjson', '-e',
            'TestsWorkflow.validate(WorkflowCapabilityPolicy, JSON.parse(STDIN.read))'],
            input=json.dumps(workflow), capture_output=True, text=True, timeout=10)

    def test_exact_job_contract_rejects_skips_bypasses_and_unbounded_time(self):
        self.assertEqual(self.validate(self.workflow).returncode, 0)
        mutations = []
        for name in ('logs-policy', 'tests-required'):
            for key, value in (('if', 'false'), ('continue-on-error', True),
                               ('timeout-minutes', 0), ('timeout-minutes', 360),
                               ('needs', []), ('permissions', {'contents': 'write'})):
                changed = deepcopy(self.workflow)
                changed['jobs'][name][key] = value
                mutations.append(changed)
            changed = deepcopy(self.workflow)
            del changed['jobs'][name]
            mutations.append(changed)
        for key in ('if', 'needs'):
            changed = deepcopy(self.workflow)
            del changed['jobs']['tests-required'][key]
            mutations.append(changed)
        for name in ('logs-policy', 'tests-required'):
            step_index = -2 if name == 'logs-policy' else 0
            for key, value in (('if', 'false'), ('continue-on-error', True),
                               ('run', 'exit 0\n')):
                changed = deepcopy(self.workflow)
                changed['jobs'][name]['steps'][step_index][key] = value
                mutations.append(changed)
        for mutation in ('skip-tests', 'IT-inputs', 'no-setup-guard', 'no-H2-env'):
            changed = deepcopy(self.workflow)
            job = changed['jobs']['logs-policy']
            if mutation == 'skip-tests':
                job['steps'][-2]['run'] = job['steps'][-2]['run'].replace(
                    ':app-bot:checkLogsPolicy', ':app-bot:checkLogsPolicy -x test')
            elif mutation == 'IT-inputs':
                job['steps'][-2]['run'] += './gradlew test -PrunIT=true\n'
            elif mutation == 'no-setup-guard':
                del job['steps'][8]
            else:
                del job['steps'][-2]['env']['FLYWAY_LOCATIONS']
            mutations.append(changed)
        for i, workflow in enumerate(mutations):
            with self.subTest(mutation=i):
                self.assertNotEqual(self.validate(workflow).returncode, 0)

    def test_actual_aggregate_requires_success_for_every_worker(self):
        job = self.workflow['jobs']['tests-required']
        self.assertEqual(job['if'], 'always()')
        workers = {'unit-tests', 'integration-tests', 'logs-policy'}
        self.assertEqual(set(job['needs']), workers)
        body = job['steps'][0]['run']
        success = {name: {'result': 'success'} for name in workers}

        def run(value):
            return subprocess.run(['bash', '-e', '-c', body], capture_output=True,
                env=dict(os.environ, TESTS_NEEDS_JSON=json.dumps(value)), timeout=5)

        self.assertEqual(run(success).returncode, 0)
        for worker in workers:
            for result in ('failure', 'cancelled', 'skipped', 'timed_out', 'unknown', None):
                with self.subTest(worker=worker, result=result):
                    changed = deepcopy(success)
                    changed[worker]['result'] = result
                    self.assertNotEqual(run(changed).returncode, 0)
            for value in ({}, None, 'success'):
                changed = deepcopy(success)
                changed[worker] = value
                self.assertNotEqual(run(changed).returncode, 0)
        for value in ({}, [], None, dict(success, unexpected={'result': 'success'})):
            self.assertNotEqual(run(value).returncode, 0)

    def test_actual_scan_command_propagates_unsuccessful_attempts(self):
        # Execute the actual workflow shell with controlled commands, not Gradle
        # or a live daemon. A successful --stop must not hide a failed scan.
        body = self.workflow['jobs']['logs-policy']['steps'][-2]['run']
        with tempfile.TemporaryDirectory(prefix='clb569-logs-command-') as directory:
            root = Path(directory)
            gradle = root / 'gradlew'
            gradle.write_text('#!/bin/sh\nprintf "%s\\n" "$*" >> "$FIXTURE_CALLS"\n'
                              'if [ "$1" = "--stop" ]; then exit 0; fi\n'
                              'exit "$FIXTURE_EXIT"\n')
            gradle.chmod(0o700)
            (root / 'sleep').write_text('#!/bin/sh\nexit 0\n')
            (root / 'sleep').chmod(0o700)
            calls = root / 'calls'
            for code in (0, 1, 124, 143):
                with self.subTest(exit=code):
                    calls.write_text('')
                    child = subprocess.run(['bash', '-e', '-c', body], cwd=root,
                        env=dict(os.environ, PATH=str(root) + os.pathsep + os.environ['PATH'],
                                 FIXTURE_CALLS=str(calls), FIXTURE_EXIT=str(code)),
                        capture_output=True, timeout=5)
                    self.assertEqual(child.returncode == 0, code == 0)
                    attempts = [line for line in calls.read_text().splitlines() if line != '--stop']
                    self.assertEqual(len(attempts), 1 if code == 0 else 2)
                    self.assertTrue(all(':app-bot:checkLogsPolicy' in line for line in attempts))

    def test_gradle_test_and_MDC_prerequisites_remain(self):
        text = (ROOT / 'app-bot/build.gradle.kts').read_text()
        block = text.split('tasks.register<LogsPolicyScanTask>("checkLogsPolicy") {', 1)[1].split(
            '\n// ----------------------------------------------------------------------', 1)[0]
        self.assertIn('dependsOn("test")', block)
        self.assertIn('layout.projectDirectory.dir("src")', block)
        self.assertIn('rootProject.layout.projectDirectory.dir("miniapp")', block)


class HarnessTest(unittest.TestCase):
    def test_workflow_contract_and_existing_jobs_unchanged(self):
        raw = (ROOT / '.github/workflows/tests.yml').read_bytes()
        original = raw.split(b'\n  amd64-runtime-prototype:\n')[0]
        historical = (HERE / 'native-adapter/ci/tests.yml.candidate').read_bytes()
        self.assertEqual(hashlib.sha256(historical.split(b'\n  amd64-runtime-prototype:\n')[0]).hexdigest(),
                         '010df3d58cf1ab89ef3978c517f3e6c124649cb656f918c4674aa30c2a319a60')
        # Psych is only the independent YAML test oracle on runner, never the
        # semantic runtime; no custom constructors or project execution.
        result = subprocess.run(['ruby', '-ryaml', '-rjson', '-e',
            'puts JSON.generate(YAML.safe_load(STDIN.read, aliases: true))'],
            input=raw, capture_output=True, check=True)
        workflow = json.loads(result.stdout)
        self.assertEqual(set(workflow['jobs']), {'unit-tests', 'integration-tests', 'logs-policy',
                                                'tests-required', 'amd64-runtime-prototype'})
        old = json.loads(subprocess.run(['ruby', '-ryaml', '-rjson', '-e',
            'puts JSON.generate(YAML.safe_load(STDIN.read, aliases: true))'],
            input=historical, capture_output=True, check=True).stdout)
        self.assertEqual(workflow['jobs']['unit-tests'], old['jobs']['unit-tests'])
        integration = old['jobs']['integration-tests']
        moved = next(step for step in integration['steps'] if step['name'].startswith('Logs policy scan'))
        integration['steps'].remove(moved)
        self.assertEqual(workflow['jobs']['integration-tests'], integration)
        self.assertEqual(workflow['jobs']['logs-policy']['steps'][-2], moved)
        self.assertEqual({k: v for k, v in workflow.items() if k != 'jobs'},
                         {k: v for k, v in old.items() if k != 'jobs'})
        job = workflow['jobs']['amd64-runtime-prototype']
        self.assertEqual(job['if'], "github.event_name == 'workflow_dispatch'")
        self.assertEqual(job['runs-on'], 'ubuntu-24.04')
        self.assertEqual(job['permissions'], {'contents': 'read'})
        self.assertEqual(job['timeout-minutes'], 75)
        self.assertGreater(job['timeout-minutes'] * 60, 900 + 600 + 4 * 600 + 5 * 60)
        self.assertNotIn('environment', job)
        self.assertNotIn('continue-on-error', job)
        steps = job['steps']
        self.assertEqual(steps[0]['with'], {'ref': '${{ github.sha }}', 'persist-credentials': False})
        self.assertEqual(steps[0]['uses'], 'actions/checkout@692973e3d937129bcbf40652eb9f2f61becf3332')
        self.assertEqual(steps[-1]['uses'], 'actions/upload-artifact@b4b15b8c7c6ac21ea08fcf65892d2ee8f75cf882')
        self.assertEqual(steps[-1]['if'], "always() && steps.evidence.outcome == 'success'")
        self.assertEqual(steps[-1]['with']['retention-days'], 3)
        actual = steps[-1]['with']['path'].splitlines()
        self.assertEqual(actual, [
            '${{ runner.temp }}/clb91-adapter-inputs/evidence/status.json',
            *('${{ runner.temp }}/clb91-native-adapter/evidence/' + n for n in
              ('environment.json', 'result.json', 'commands.json', 'compiler.json')),
            '${{ runner.temp }}/clb191-isolated-helper/result.json',
            '${{ runner.temp }}/clb192-handoff/result.json',
            '${{ runner.temp }}/clb195-namespace/result.json',
        ])
        # This is a candidate native adapter experiment, not the old 191/4 suite.
        # Explicit preparation/namespace markers do not themselves grant permission.
        prefix = 'scripts/tests/linux-semantic/native-adapter/'
        self.assertEqual(len(steps), 9)
        self.assertEqual(steps[1]['run'], 'python3 -I -S -B ' + prefix + 'ci/test-recipe.py')
        self.assertEqual(steps[2]['run'], 'python3 -I -S -B ' + prefix +
            'ci/prepare-native-inputs.py "${{ runner.temp }}/clb91-adapter-inputs" '
            '--authorized-external-preparation')
        self.assertEqual(steps[3]['run'], 'python3 -I -S -B ' + prefix +
            'tests/native-driver.py --runtime-tar '
            '"${{ runner.temp }}/clb91-adapter-inputs/runtime/native/rootfs.tar" '
            '--work "${{ runner.temp }}/clb91-native-adapter" --authorized-native-test')
        self.assertEqual(steps[4]['run'], 'python3 -I -S -B scripts/tests/linux-semantic/isolated-helper/native-ci.py '
            '--prepared "${{ runner.temp }}/clb91-adapter-inputs" '
            '--output "${{ runner.temp }}/clb191-isolated-helper"')
        self.assertNotIn('if', steps[4])
        self.assertNotIn('continue-on-error', steps[4])
        self.assertEqual(steps[5]['run'], 'python3 -I -S -B scripts/tests/linux-semantic/isolated-helper/native-handoff-ci.py '
            '--prepared "${{ runner.temp }}/clb91-adapter-inputs" '
            '--output "${{ runner.temp }}/clb192-handoff"')
        self.assertNotIn('if', steps[5])
        self.assertNotIn('continue-on-error', steps[5])
        self.assertEqual(steps[6]['run'], 'python3 -I -S -B scripts/tests/linux-semantic/namespace-bootstrap/native-ci.py '
            '--prepared "${{ runner.temp }}/clb91-adapter-inputs" '
            '--output "${{ runner.temp }}/clb195-namespace"')
        self.assertEqual(steps[6]['timeout-minutes'], 15)
        self.assertNotIn('if', steps[6])
        self.assertNotIn('continue-on-error', steps[6])
        self.assertEqual(steps[7]['run'], 'python3 -I -S -B ' + prefix +
            'ci/check-artifacts.py "${{ runner.temp }}"')
        self.assertEqual(steps[7]['if'], 'always()')
        self.assertEqual(steps[7]['id'], 'evidence')
        self.assertEqual(steps[-1]['with']['if-no-files-found'], 'error')
        new_raw = raw[len(original):].decode()
        for forbidden in ('secrets.', 'environment:', 'continue-on-error', 'qemu', 'binfmt', 'sudo'):
            self.assertNotIn(forbidden, new_raw.lower())

    def test_clb191_native_contract_controls(self):
        result = subprocess.run([sys.executable, '-I', '-S', '-B',
            str(HERE / 'isolated-helper/test-native-ci.py')], capture_output=True, timeout=30)
        self.assertEqual(result.returncode, 0, 'CLB-191 native contract controls failed')

    def test_clb192_native_contract_controls(self):
        result = subprocess.run([sys.executable, '-I', '-S', '-B',
            str(HERE / 'isolated-helper/test-native-handoff.py')], capture_output=True, timeout=30)
        self.assertEqual(result.returncode, 0, 'CLB-192 native contract controls failed')

    def test_clb195_namespace_portable_controls(self):
        sample=b'CLB195_GCC_DIAG source=adapter.c line=123 warning=unused-variable\n'
        self.assertEqual(bounded_clb195_gcc(sample),sample.decode().strip())
        self.assertEqual(bounded_clb195_gcc(sample+b'PRIVATE_CANARY'),
                         'CLB195_GCC_DIAG source=UNKNOWN line=0 warning=UNKNOWN')
        result = subprocess.run([sys.executable, '-I', '-S', '-B',
            str(HERE / 'namespace-bootstrap/test-bootstrap.py')], capture_output=True, timeout=90)
        self.assertEqual(result.returncode, 0,
                         'CLB-195 namespace controls failed: '+bounded_clb195_gcc(result.stderr))

    def test_selfcheck_composition(self):
        text = (ROOT / 'scripts/selfcheck-quality-gates.sh').read_text()
        command = 'python3 -B "$ROOT_DIR/scripts/tests/test_amd64_ci_harness.py"'
        self.assertEqual(text.count(command), 1)
        self.assertIn('python3 -B "$ROOT_DIR/scripts/tests/test_gitleaks_runtime_allowlist.py"', text)
        self.assertLess(text.index(command), text.index('validate_keyless_action_inventory()'))
        self.assertIn("$'unit-tests\\nintegration-tests\\nlogs-policy\\ntests-required\\namd64-runtime-prototype'", text)
        self.assertNotIn("$'unit-tests\\nintegration-tests'", text)
        self.assertIn('assert_tests_workflow_contract_rejected "tests-native-$native_mutation"', text)

    def test_architecture_fail_closed(self):
        ci.native_guard('Linux', 'x86_64', 'X64', 'x86_64')
        for values in (('Darwin', 'x86_64', 'X64', 'x86_64'), ('Linux', 'aarch64', 'X64', 'x86_64'),
                       ('Linux', 'x86_64', 'ARM64', 'x86_64'), ('Linux', 'x86_64', 'X64', 'aarch64')):
            with self.subTest(values=values), self.assertRaises(ValueError):
                ci.native_guard(*values)

    def test_locked_inputs_and_strict_comparison(self):
        raw = (HERE / 'amd64-inputs.json').read_bytes()
        self.assertEqual(ci.digest(raw), ci.INPUTS_SHA256)
        lock = json.loads(raw)
        for name, key in (('amd64-runtime-candidate.json', 'runtime_candidate_sha256'),
                          ('amd64-packages.txt', 'installed_packages_sha256')):
            raw = (HERE / name).read_bytes()
            self.assertEqual(ci.digest(raw), lock[key])
            ci.exact(raw, raw, name)
            with self.assertRaisesRegex(ValueError, ':expected'):
                ci.exact(raw, raw + b'\n', name)
        with self.assertRaisesRegex(ValueError, 'expected.*actual'):
            downloads.checked(b'wrong', '0' * 64, 'input')
        with self.assertRaises(ValueError):
            downloads.download('http://example.invalid', 10)
        self.assertEqual(len(lock['packages']), 62)
        self.assertEqual(len(lock['indexes']), 15)

    def test_signed_index_concatenation_and_bad_inputs(self):
        raw = b'first' + b'second'
        compressed = lzma.compress(b'first') + lzma.compress(b'second')
        self.assertEqual(downloads.unpack_index(compressed, len(raw), ci.digest(raw)), raw)
        for size, digest in ((5, ci.digest(raw)), (len(raw), ci.digest(b'first'))):
            with self.assertRaises(ValueError):
                downloads.unpack_index(compressed, size, digest)
        with self.assertRaises((ValueError, lzma.LZMAError, EOFError)):
            downloads.unpack_index(compressed[:-10], len(raw), ci.digest(raw))

    def test_capability_policy_still_rejects_widening(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            shutil.copytree(ROOT / 'scripts', root / 'scripts')
            shutil.copytree(ROOT / '.github', root / '.github')
            (root / 'gradle').mkdir()
            shutil.copyfile(ROOT / 'gradle/verification-metadata.xml', root / 'gradle/verification-metadata.xml')
            subprocess.run(['git', 'init', '-q', str(root)], check=True)
            path = root / '.github/workflows/tests.yml'
            raw = path.read_text()
            prefix, job = raw.split('\n  amd64-runtime-prototype:\n')
            for before, after in (("github.event_name == 'workflow_dispatch'", 'true'),
                                  ('ubuntu-24.04', 'ubuntu-latest'),
                                  ('contents: read', 'contents: write'),
                                  ('    permissions:', '    environment: stage\n    permissions:')):
                with self.subTest(change=after):
                    path.write_text(prefix + '\n  amd64-runtime-prototype:\n' + job.replace(before, after, 1))
                    result = subprocess.run(['ruby', str(root / 'scripts/validate-workflow-capabilities.rb'),
                                             str(root)], capture_output=True)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn(b'amd64-runtime-prototype', result.stderr + result.stdout)

    def test_disposable_exact_three_changes_and_preconditions(self):
        with tempfile.TemporaryDirectory() as directory:
            export = Path(directory)
            folder = export / 'scripts/deploy'
            folder.mkdir(parents=True)
            # This experiment is historical. Keep its original source pins and
            # prove later production edits cannot inherit its native evidence.
            for name in ci.ORIGINAL:
                (folder / name).write_bytes((ROOT / 'scripts/deploy' / name).read_bytes())
            candidate = (HERE / 'amd64-runtime-candidate.json').read_bytes()
            with self.assertRaisesRegex(ValueError, 'integrated source precondition'):
                ci.integrated_diff(export, candidate)
            baseline = 'c4f4ce26b8452b31ee0e27e5511a117baa0a5d06'
            for name in ci.ORIGINAL:
                raw = subprocess.check_output(['git', '-C', str(ROOT), 'show',
                    baseline + ':scripts/deploy/' + name])
                (folder / name).write_bytes(raw)
            candidate = (HERE / 'amd64-runtime-candidate.json').read_bytes()
            actual = {name: (folder / name).read_bytes() for name in ci.ORIGINAL}
            integrated = ci.integrated_diff(export, candidate)
            self.assertEqual(actual, {name: (folder / name).read_bytes() for name in ci.ORIGINAL})
            # Reconstruct only the exact historical inputs; adapt checks all old hashes.
            for name, before, after in (
                ('stage-compose-env-semantic-operation.py', b"platform.machine() == 'x86_64'", b"platform.machine() == 'aarch64'"),
                ('stage-compose-env-file-plan.py', b'/usr/lib/x86_64-linux-gnu/ruby/3.2.0', b'/usr/lib/aarch64-linux-gnu/ruby/3.2.0'),
            ):
                (folder / name).write_bytes(actual[name].replace(before, after))
            (folder / 'stage-compose-env-semantic-runtime.json').write_bytes((HERE / 'arm64-runtime-reference.json').read_bytes())
            diff = ci.adapt(export, candidate)
            self.assertEqual(diff, integrated)
            self.assertEqual(actual, {name: (folder / name).read_bytes() for name in ci.ORIGINAL})
            self.assertEqual(diff.count('\n+++ b/scripts/deploy/'), 3)
            self.assertEqual((folder / 'stage-compose-env-semantic-runtime.json').read_bytes(), candidate)
            self.assertIn("platform.machine() == 'x86_64'", (folder / 'stage-compose-env-semantic-operation.py').read_text())
            self.assertIn('/usr/lib/x86_64-linux-gnu/ruby/3.2.0', (folder / 'stage-compose-env-file-plan.py').read_text())
            with self.assertRaisesRegex(ValueError, 'precondition'):
                ci.adapt(export, candidate)
            for name in ci.ORIGINAL:
                with self.subTest(changed=name):
                    (folder / name).write_bytes(actual[name] + b'\n')
                    with self.assertRaises(ValueError):
                        ci.integrated_diff(export, candidate)
                    (folder / name).write_bytes(actual[name])

    def test_container_isolation_and_fixed_suites(self):
        argv = ci.container('test:image', 'true', mounts=((Path('/disposable'), '/source'),), fixtures=True)
        for flag in ('--read-only', '--network=none', '--cap-drop=ALL', '--security-opt=no-new-privileges'):
            self.assertIn(flag, argv)
        self.assertEqual(argv[argv.index('--user') + 1], '1000:1000')
        self.assertEqual(argv[argv.index('--platform') + 1], 'linux/amd64')
        for forbidden in ('privileged', 'unconfined', 'SYS_PTRACE', 'docker.sock', str(ROOT)):
            self.assertNotIn(forbidden, ' '.join(argv))
        self.assertEqual([s[0] for s in ci.SUITES], ['runtime', 'planner', 'context', 'semantic'])

    def test_real_cancellation_stops_scheduling_but_ordinary_failure_collects(self):
        for cancelled in (False, True):
            with self.subTest(cancelled=cancelled), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                marker = root / 'second-command'
                first = ('import os, signal, time; os.kill(os.getppid(), signal.SIGTERM); time.sleep(5)'
                         if cancelled else 'raise SystemExit(17)')
                commands = iter(([sys.executable, '-c', first],
                    [sys.executable, '-c', 'from pathlib import Path; Path(' + repr(str(marker)) + ').write_text("ran")']))
                previous = signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
                status = {}
                try:
                    with patch.object(ci, 'SUITES', (('runtime', 'first.py'), ('planner', 'second.py'))), \
                            patch.object(ci, 'container', side_effect=lambda *a, **k: next(commands)):
                        self.assertEqual(ci.run_suites('unused', root, root, status), 1)
                finally:
                    signal.signal(signal.SIGTERM, previous)
                self.assertEqual(status['runtime'], 130 if cancelled else 17)
                self.assertEqual(marker.exists(), not cancelled)
                self.assertEqual('planner' in status, not cancelled)
                self.assertEqual(json.loads((root / 'status.json').read_bytes()), status)

    def test_real_exit_timeout_start_failure_and_artifact_bounds(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            log = root / 'planner.log'
            self.assertEqual(ci.run_logged([sys.executable, '-c', 'raise SystemExit(17)'], log, 5), 17)
            self.assertEqual(ci.run_logged(['/no/such/clb91-tool'], log, 5), 127)
            self.assertEqual(ci.run_logged([sys.executable, '-c', 'import time; time.sleep(5)'], log, .01), 124)
            ci.bounded_artifacts(root)
            with patch.object(ci, 'LIMIT', 3):
                log.write_bytes(b'large')
                with self.assertRaises(ValueError):
                    ci.bounded_artifacts(root)
            log.unlink()
            (root / 'unknown.deb').write_bytes(b'forbidden')
            with self.assertRaises(ValueError):
                ci.bounded_artifacts(root)


if __name__ == '__main__':
    unittest.main(verbosity=2)
