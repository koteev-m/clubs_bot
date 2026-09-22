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
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
HERE = ROOT / 'scripts/tests/linux-semantic'

def load(name, file):
    spec = importlib.util.spec_from_file_location(name, HERE / file)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

ci = load('amd64_ci', 'run-amd64-ci.py')
downloads = load('amd64_downloads', 'prepare-amd64-downloads.py')


class HarnessTest(unittest.TestCase):
    def test_workflow_contract_and_existing_jobs_unchanged(self):
        raw = (ROOT / '.github/workflows/tests.yml').read_bytes()
        original = raw.split(b'\n  amd64-runtime-prototype:\n')[0]
        self.assertEqual(hashlib.sha256(original).hexdigest(),
                         '010df3d58cf1ab89ef3978c517f3e6c124649cb656f918c4674aa30c2a319a60')
        # Psych is only the independent YAML test oracle on runner, never the
        # semantic runtime; no custom constructors or project execution.
        result = subprocess.run(['ruby', '-ryaml', '-rjson', '-e',
            'puts JSON.generate(YAML.safe_load(STDIN.read, aliases: true))'],
            input=raw, capture_output=True, check=True)
        workflow = json.loads(result.stdout)
        self.assertEqual(set(workflow['jobs']), {'unit-tests', 'integration-tests', 'amd64-runtime-prototype'})
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
        self.assertEqual(actual, ['${{ runner.temp }}/clb91-amd64/evidence/' + n for n in ci.EVIDENCE])
        new_raw = raw[len(original):].decode()
        for forbidden in ('secrets.', 'environment:', 'continue-on-error', 'qemu', 'binfmt', 'sudo'):
            self.assertNotIn(forbidden, new_raw.lower())

    def test_selfcheck_composition(self):
        text = (ROOT / 'scripts/selfcheck-quality-gates.sh').read_text()
        command = 'python3 -B "$ROOT_DIR/scripts/tests/test_amd64_ci_harness.py"'
        self.assertEqual(text.count(command), 1)
        self.assertIn('python3 -B "$ROOT_DIR/scripts/tests/test_gitleaks_runtime_allowlist.py"', text)
        self.assertLess(text.index(command), text.index('validate_keyless_action_inventory()'))

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
        self.assertEqual(len(lock['packages']), 61)
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
            for name in ci.ORIGINAL:
                (folder / name).write_bytes((ROOT / 'scripts/deploy' / name).read_bytes())
            candidate = (HERE / 'amd64-runtime-candidate.json').read_bytes()
            diff = ci.adapt(export, candidate)
            self.assertEqual(diff.count('\n+++ b/scripts/deploy/'), 3)
            self.assertEqual((folder / 'stage-compose-env-semantic-runtime.json').read_bytes(), candidate)
            self.assertIn("platform.machine() == 'x86_64'", (folder / 'stage-compose-env-semantic-operation.py').read_text())
            self.assertIn('/usr/lib/x86_64-linux-gnu/ruby/3.2.0', (folder / 'stage-compose-env-file-plan.py').read_text())
            with self.assertRaisesRegex(ValueError, 'precondition'):
                ci.adapt(export, candidate)
        for name, expected in ci.ORIGINAL.items():
            self.assertEqual(ci.digest((ROOT / 'scripts/deploy' / name).read_bytes()), expected)

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
