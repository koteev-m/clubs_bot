#!/usr/bin/env python3
"""Actual Linux Compose/procfs tests; synthetic bytes only, no Engine/socket."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('semantic_tests', HERE/'test_stage_compose_env_file_plan.py')
sem = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sem)
p = sem.planner


@unittest.skipUnless(sys.platform == 'linux', 'requires real Linux procfs/Compose; Darwin is not parity')
class LinuxContextTest(sem.SemanticTest):
    """Run every existing real semantic and typed-normalizer control via memfd."""
    def setUp(self):
        super().setUp()
        self.canonical_temp = tempfile.TemporaryDirectory(dir=self.root.parent)
        self.addCleanup(self.canonical_temp.cleanup)
        self.canonical = Path(self.canonical_temp.name)
        self.original_prepare = p.prepare

    def prepare(self, *args, **kwargs):
        def adapted(*values, **options):
            return self.original_prepare(*values, **options, canonical_directory=str(self.canonical))
        with patch.object(p, 'prepare', adapted):
            return super().prepare(*args, **kwargs)

    def direct(self, content, dotenv):
        (self.canonical/'.env').write_bytes(dotenv)
        (self.canonical/'docker-compose.yml').write_bytes(content)
        (self.canonical/'override.yml').write_bytes(sem.OVERRIDE)
        env = dict(PATH='/usr/bin:/bin', HOME=str(self.root), DOCKER_CONFIG=str(self.root),
                   DOCKER_HOST='unix://'+str(self.root/'no-daemon.sock'))
        result = subprocess.run([sem.COMPOSE, '--project-name', 'clb91-private-test',
            '--project-directory', str(self.canonical), '--env-file', str(self.canonical/'.env'),
            '-f', str(self.canonical/'docker-compose.yml'), '-f', str(self.canonical/'override.yml'),
            'config', '--format', 'json'], env=env, capture_output=True, timeout=10)
        self.assertEqual((result.returncode, result.stderr), (0, b''))
        return json.loads(result.stdout)

    def test_projection_fidelity_replacement_deletion_and_kernel_read_audit(self):
        content = sem.base(references='    env_file: [".env", \'./.env\', .env]\n')
        dotenv = b'A=private_original\nEXTRA="two $$ dollars"\n'
        expected = self.direct(content, dotenv)
        self.assertEqual(expected['services']['caddy']['volumes'][0]['source'], str(self.canonical/'Caddyfile'))
        real_capture = p.CAPTURE
        for change in ('replace', 'delete'):
            models = []
            traced = []
            def observed(argv, payload=b'', **options):
                command = argv[5:]
                if command[0] != sem.COMPOSE or command[-3:] != ['config', '--format', 'json']:
                    return real_capture(argv, payload, **options)
                if not models:
                    if change == 'replace':
                        (self.canonical/'.env').write_bytes(b'A=REPLACEMENT_CANARY\nEXTRA=wrong\n')
                    else:
                        (self.canonical/'.env').unlink(missing_ok=True)
                    # Original base/override contents are also inaccessible to config.
                    (self.canonical/'docker-compose.yml').write_bytes(b'INVALID_SOURCE_CANARY')
                    (self.canonical/'override.yml').unlink(missing_ok=True)
                with tempfile.TemporaryDirectory(dir=self.root.parent) as trace_root:
                    log = Path(trace_root)/'trace'
                    result = real_capture(['strace', '-f', '-qq', '-e', 'trace=%file', '-o', str(log),
                                           *argv], payload, **options)
                    trace = log.read_text()
                    if os.environ.get('CLB91_SYNTHETIC_TRACE_EVIDENCE') == '1':
                        # Synthetic fixtures only. Preserve the actual audit before
                        # TemporaryDirectory cleanup, including on assertion failure.
                        self.assertLessEqual(len(trace.encode()), 256 * 1024)
                        print('CLB91_SYNTHETIC_TRACE ' + json.dumps(
                            {'change': change, 'index': len(traced), 'trace': trace}))
                    traced.append(trace)
                    # Kernel trace, not a matching-value inference: no open of any
                    # original configuration content after capture. FD paths must occur.
                    self.assertIn('/proc/', trace)
                    for line in trace.splitlines():
                        if 'openat(' in line or 'open(' in line or 'openat2(' in line:
                            for name in ('.env', 'docker-compose.yml', 'override.yml'):
                                self.assertNotIn('"'+str(self.canonical/name)+'"', line)
                                self.assertNotIn('"'+name+'"', line)
                    self.assertEqual((result.code, result.failure), (0, None))
                    models.append(json.loads(result.output))
                    return result
            with self.subTest(change=change), patch.object(p, 'CAPTURE', observed):
                plan = self.original_prepare(content, dotenv, sem.OVERRIDE, interpolation={},
                    project='clb91-private-test', compose=sem.COMPOSE, temporary_root=str(self.root),
                    canonical_directory=str(self.canonical))
            self.assertEqual(plan.strategy, 'explicit')
            self.assertNotIn(b'/proc/', plan.candidate)
            self.assertNotIn(b'private_original', plan.candidate)
            self.assertTrue(sem.independently_equal(models[0], expected))
            self.assertTrue(sem.independently_equal(models[0], models[2]))
            self.assertTrue(sem.independently_equal(models[0], models[-2]))
            self.assertTrue(sem.independently_equal(models[2], models[-1]))
            self.assertEqual(len(traced), 5)

    def test_sealed_repeated_opens_offsets_lifetime_and_cleanup(self):
        held = p.SealedInputs()
        try:
            path = held.add(b'SYNTHETIC_ONLY')
            self.assertEqual(os.read(held.fds[0], 4), b'SYNT')
            for _ in range(3):
                with open(path, 'rb') as stream:
                    self.assertEqual(stream.read(), b'SYNTHETIC_ONLY')
            self.assertEqual(os.lseek(held.fds[0], 0, os.SEEK_CUR), 4)
            with self.assertRaises(OSError):
                os.write(held.fds[0], b'no')
        finally:
            held.close()
        self.assertFalse(Path(path).exists())
        self.assertEqual(held.fds, [])

    def test_lost_fd_and_cleanup_failure_never_return_success(self):
        real_add, real_close = p.SealedInputs.add, p.SealedInputs.close
        def lose(instance, data):
            path = real_add(instance, data)
            if len(instance.fds) == 3:
                os.close(instance.fds.pop())
            return path
        def cleanup(instance):
            real_close(instance)
            raise p.Refused('cleanup')
        for method, replacement in (('add', lose), ('close', cleanup)):
            with self.subTest(method=method), patch.object(p.SealedInputs, method, replacement):
                with self.assertRaises(p.Refused):
                    self.prepare(sem.base(), b'A=fixture\n')

    def test_projection_only_ast_references_not_other_dotenv_text(self):
        content = sem.base(references='    env_file: [.env, "./.env"] # .env\n',
                           extras='    labels:\n      note: "Unicode е .env [same]"\n')
        env = dict(PATH='/usr/bin:/bin', HOME=str(self.root))
        outline = json.loads(p.capture(['/usr/bin/ruby', '-e', p.OUTLINE], env, content))
        result = p.project_dotenv(content, outline, '/proc/123/fd/9')
        self.assertEqual(result.count(b'/proc/123/fd/9'), 2)
        self.assertIn(b'# .env', result)
        self.assertIn('Unicode е .env [same]'.encode(), result)
        self.assertEqual(content, sem.base(references='    env_file: [.env, "./.env"] # .env\n',
                           extras='    labels:\n      note: "Unicode е .env [same]"\n'))


if __name__ == '__main__':
    unittest.main(verbosity=2)
