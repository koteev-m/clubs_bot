#!/usr/bin/env python3
"""LOCAL ONLY: protocol/ordering controls and separately labelled real component tests."""
import contextlib
import io
import json
import os
from pathlib import Path
import runpy
import tempfile
import types
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
h = runpy.run_path(str(HERE / 'helper.py'))
CANARY = 'CLB191_SYNTHETIC_SECRET_CANARY'


def fixture(op, case='remove'):
    environment = '    environment:\n      A: ${A}\n' if case == 'remove' else ''
    base = 'services:\n  app:\n    image: fixture:local\n    env_file: [.env]\n' + environment
    override = ('# clubs-bot-managed-quiesced-release\n# revision: ' + op.D.REVISION +
                '\nservices:\n  app:\n    image: ' + op.D.IMAGE + '\n')
    return json.dumps(dict(format=1, base=base, dotenv='A=' + CANARY + '\n',
        override=override, interpolation={'A': 'different'} if case == 'different' else {})).encode()


class Controls(unittest.TestCase):
    def setUp(self):
        self.op = h['sources'](ROOT / 'scripts/deploy')

    def test_source_hash_symlink_and_parent_escape(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td).resolve()
            for name in h['PINS']:
                (root/name).write_bytes((ROOT/'scripts/deploy'/name).read_bytes())
            for name in h['PINS']:
                raw = (root/name).read_bytes()
                (root/name).write_bytes(raw + b'\n')
                with self.assertRaises(h['Refused']): h['sources'](root)
                (root/name).write_bytes(raw)
            target = root / next(iter(h['PINS']))
            target.unlink(); target.symlink_to(ROOT/'scripts/deploy'/target.name)
            with self.assertRaises(OSError): h['sources'](root)
            link = root/'link'; link.symlink_to(ROOT/'scripts/deploy')
            with self.assertRaises(h['Refused']): h['sources'](link)

    def test_protocol_bounds_order_duplicates_and_fields(self):
        raw = fixture(self.op)
        good = h['parse'](raw)
        cases = [b'', raw[:-1], b'x'*(h['LIMIT']+1), b'[]',
                 raw.replace(b'"format": 1', b'"format": 1, "format": 1'),
                 json.dumps(dict(reversed(list(good.items())))).encode()]
        for key in good:
            data = dict(good); del data[key]; cases.append(json.dumps(data).encode())
        for key, value in [('format', True), ('format', 2), ('dotenv', 'x'*65537),
                           ('base', []), ('interpolation', {'A': 1})]:
            data = dict(good); data[key] = value; cases.append(json.dumps(data).encode())
        for data in cases:
            with self.subTest(case=cases.index(data)):
                with self.assertRaises((h['Refused'], ValueError)): h['parse'](data)

    def test_runtime_failure_never_reads_and_always_closes(self):
        # Fault injection proves call ordering, NOT runtime acceptance or semantics.
        calls = []
        stages = ['open', 'private_root', 'require_compatible', 'ruby_probe', 'recheck']
        for failure in stages:
            class Runtime:
                def __init__(self, cancelled): pass
                def __getattr__(self, name):
                    def invoke(*args):
                        calls.append(name)
                        if name == failure: raise RuntimeError('injected')
                    return invoke
            op = types.SimpleNamespace(Runtime=Runtime)
            def read(): self.fail('input read before failed gate')
            with self.assertRaises(RuntimeError): h['execute'](op, read, lambda: False)
            self.assertEqual(calls[-1], 'close')

    def test_pipe_type_eof_bounds_and_caller_ownership(self):
        read, write = os.pipe()
        raw = fixture(self.op)
        try:
            os.write(write, raw); os.close(write); write = None
            self.assertEqual(h['read_input'](read), raw)
            os.fstat(read)  # borrowed FD remains owned by caller
        finally:
            os.close(read)
            if write is not None: os.close(write)
        with tempfile.TemporaryFile() as stream:
            with self.assertRaises(h['Refused']): h['read_input'](stream.fileno())
        read, write = os.pipe()
        try:
            os.write(write, b'12345'); os.close(write); write = None
            with patch.dict(h['read_input'].__globals__, LIMIT=4):
                with self.assertRaises(h['Refused']): h['read_input'](read)
        finally:
            os.close(read)
            if write is not None: os.close(write)

    def test_launcher_isolation_policy_and_bounded_canary_capture(self):
        launcher = runpy.run_path(str(HERE/'run-local.py'))
        argv = launcher['container_argv']('clb191-test', Path('/disposable'),
                                           'sha256:' + '0'*64, 'synthetic-code')
        for flag in ('--log-driver=none', '--pull=never', '--read-only', '--network=none',
                     '--user=1000:1000', '--cap-drop=ALL', '--security-opt=no-new-privileges'):
            self.assertIn(flag, argv)
        mounts = [argv[i+1] for i, item in enumerate(argv) if item == '--mount']
        self.assertEqual(mounts, ['type=bind,src=/disposable,dst=/source,readonly'])
        self.assertNotIn('--privileged', argv)
        self.assertFalse(any('seccomp=' in arg or 'ptrace' in arg for arg in argv))
        for output, code in [(CANARY.encode(), 0), (CANARY.encode(), 1)]:
            summary = launcher['summarize']('remove', self.op.D.CaptureResult(code, output))
            self.assertNotIn(CANARY, json.dumps(summary))
            self.assertFalse(summary['equivalent'] or summary['different'] or summary['refused'])
        public = ('compose-env-file-plan:v=1 result=equivalent strategy=remove '
                  'scope=snapshot future=requires_recheck application=not_authorized\n').encode()
        self.assertTrue(launcher['summarize']('remove', self.op.D.CaptureResult(0, public))['equivalent'])
        self.assertFalse(launcher['summarize']('explicit', self.op.D.CaptureResult(0, public))['equivalent'])
        self.assertFalse(launcher['summarize']('different', self.op.D.CaptureResult(1,
            b'clb191-local:v=1 result=refused\n'))['different'])
        import sys
        result = self.op.D.capture_result([sys.executable, '-c',
            'import os; os.write(1, b"' + CANARY + '" * 1000); os.write(2, b"' + CANARY + '")'],
            timeout=5, limit=512, env={'PATH': '/usr/bin:/bin'})
        self.assertEqual(result.failure, 'capture_output_limit')
        self.assertEqual(result.output, b'')
        result = self.op.D.capture_result([sys.executable, '-c', 'import time; time.sleep(5)'],
                                          timeout=.1, limit=512, env={'PATH': '/usr/bin:/bin'})
        self.assertEqual(result.failure, 'capture_timeout')

    def test_interruption_closes_without_reader(self):
        class Runtime:
            def __init__(self, cancelled): self.cancelled = cancelled
            def open(self):
                if self.cancelled(): raise InterruptedError()
            def close(self): calls.append('close')
        calls = []
        with self.assertRaises(InterruptedError):
            h['execute'](types.SimpleNamespace(Runtime=Runtime), lambda: self.fail('read'), lambda: True)
        self.assertEqual(calls, ['close'])


@unittest.skipUnless(os.environ.get('CLB191_COMPONENT_TEST') == '1',
                     'explicit Linux real-planner component run only; not full runtime acceptance')
class RealComponent(unittest.TestCase):
    def test_real_semantics_redaction_memfd_and_cleanup(self):
        op = h['sources'](ROOT/'scripts/deploy')
        # These are planner-only component results. Runtime.open is NOT bypassed
        # in the executable helper; emulation must still refuse that entrypoint.
        before = set(os.listdir('/proc/self/fd'))
        for case, strategy in [('remove', 'remove'), ('explicit', 'explicit')]:
            out, err = io.StringIO(), io.StringIO()
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                result = h['evaluate'](op, fixture(op, case))
            self.assertIn('result=equivalent strategy=' + strategy, result)
            self.assertNotIn(CANARY, result + out.getvalue() + err.getvalue())
            self.assertEqual(out.getvalue()+err.getvalue(), '')
            self.assertEqual(list(Path(h['TEMP']).iterdir()), [])
            self.assertEqual(list(Path(h['CANONICAL']).iterdir()), [])
        with self.assertRaises(op.P.Refused) as caught:
            h['evaluate'](op, fixture(op, 'different'))
        self.assertEqual(caught.exception.reason, 'different')
        self.assertEqual(set(os.listdir('/proc/self/fd')), before)
        sealed = op.P.SealedInputs()
        try:
            path = sealed.add(CANARY.encode())
            fd = sealed.fds[0]
            self.assertEqual(os.fstat(fd).st_uid, os.getuid())
            self.assertEqual(os.fstat(fd).st_mode & 0o777, 0o600)
            with self.assertRaises(OSError): os.write(fd, b'corrupt')
            self.assertEqual(Path(path).read_bytes(), CANARY.encode())
        finally: sealed.close()
        self.assertFalse(Path(path).exists())

    def test_missing_canonical_and_environment_control(self):
        op = h['sources'](ROOT/'scripts/deploy')
        data = json.loads(fixture(op)); data['interpolation'] = {'LD_PRELOAD': CANARY}
        with self.assertRaises(op.P.Refused): h['evaluate'](op, json.dumps(data).encode())
        with patch.dict(h['evaluate'].__globals__, CANONICAL='/missing-clb191'):
            with self.assertRaises(op.P.Refused): h['evaluate'](op, fixture(op))


if __name__ == '__main__': unittest.main(verbosity=2)
