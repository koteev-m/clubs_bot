#!/usr/bin/env python3
"""CLB-91 source-oracle, real FD/lock and synthetic transport regression tests."""
import ast
import contextlib
import hashlib
import hmac
import importlib.util
import json
import os
from pathlib import Path
import pwd
import random
import re
import signal
import stat
import struct
import subprocess
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]


def load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts/deploy' / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


operation = load('stage-compose-diagnostic-operation')
runner = load('stage-compose-diagnostic')
HELPER = (ROOT / 'scripts/deploy/remote-compose-release.sh').read_bytes()
assert len(HELPER) == 183462 and hashlib.sha256(HELPER).hexdigest() == '54839fcfa543888cc4613d2af480d85aee07ab8dd6b4a2bf879e64d501577809'
EMBEDDED = HELPER.decode().split("<<'CLB82_BOUND_PYTHON' || true\n", 1)[1].split('\nCLB82_BOUND_PYTHON', 1)[0]
ORIGINAL_TREE = ast.parse(EMBEDDED)
ORIGINAL_CLASS = next(n for n in ORIGINAL_TREE.body if isinstance(n, ast.ClassDef) and n.name == 'BoundContext')


def scope(method, guard):
    function = next(n for n in ORIGINAL_CLASS.body if isinstance(n, ast.FunctionDef) and n.name == method)
    return next(n for n in function.body if isinstance(n, ast.With) and isinstance(n.items[0].context_expr, ast.Call)
                and n.items[0].context_expr.args[0].value == guard).body


def rejected(ok):
    if not ok:
        raise operation.Invalid()


LEXICAL_ORACLE = compile(ast.Module(body=scope('capture_configuration', 'compose_subset'), type_ignores=[]),
                         '<exact-approved-lexical-scope>', 'exec')


def original_accepts(data):
    try:
        exec(LEXICAL_ORACLE, {'main': data, 're': re, 'check': rejected})
        return True
    except (operation.Invalid, UnicodeError):
        return False


def fields(body):
    return dict(item.split('=', 1) for item in body.decode().strip().split(' ')[1:])


def record(values):
    return '\n'.join(key + '=' + value for key, value in values.items()).encode()


MOUNT_VALUES = ('ext4', '/dev/synthetic', '/', '/synthetic')
MOUNT_OUTPUT = (' '.join(key + '="' + value + '"' for key, value in
                       zip(('FSTYPE', 'SOURCE', 'FSROOT', 'TARGET'), MOUNT_VALUES)) + '\n').encode()
MOUNT_FINGERPRINT = 'mount-v2:' + operation.sha(('clubs-bot-mount-fingerprint-version=2\n' + '\n'.join(
    key + '_SHA256=' + operation.sha(value.encode()) for key, value in
    zip(('FSTYPE', 'SOURCE', 'FSROOT', 'TARGET'), MOUNT_VALUES))).encode())


def fixture_temporary_directory(prefix):
    # Same RUNNER_TEMP preference as the existing status suite. In hosted Linux,
    # the system /tmp ancestor is intentionally not a valid private pin root.
    parent = Path(os.environ.get('RUNNER_TEMP') or tempfile.gettempdir()).resolve()
    if parent.is_relative_to(ROOT):
        raise RuntimeError('disposable fixtures must be outside the checkout')
    return tempfile.TemporaryDirectory(prefix=prefix, dir=parent)


class Fixture:
    def __init__(self):
        self.temp = fixture_temporary_directory('clb91-diagnostic-')
        self.root = Path(self.temp.name).resolve()
        assert not self.root.is_relative_to(ROOT)
        self.paths = {'/': self.root}
        for key, parent, name in (('opt', '/', 'opt'), ('compose', 'opt', 'clubs-bot-stage'),
            ('parent', 'compose', '.clubs-bot-release-state'), ('root', 'parent', 'stage'),
            ('state', 'root', 'clubs-bot-schema-stage.lock'), ('results', 'root', 'clubs-bot-schema-stage.results'),
            ('ledger', 'root', 'clubs-bot-schema-stage.migration-ledgers')):
            self.paths[key] = self.paths[parent] / name
            self.paths[key].mkdir(mode=0o700)
        self.paths['application_lock'] = self.paths['parent'] / 'application.lock'
        self.paths['operation_lock'] = self.paths['results'] / 'operation.lock'
        self.paths['base'] = self.paths['compose'] / 'docker-compose.yml'
        for key in ('application_lock', 'operation_lock'):
            self.write_path(self.paths[key], b'')
        self.write_path(self.paths['base'], b'services:\n  app:\n    image: PRIVATE_BASE_VALUE\n', 0o644)
        self.project = 'clubs-bot-stage'
        self.path_hash = operation.sha(operation.COMPOSE_PATH.encode())
        override = f'# clubs-bot-managed-quiesced-release\n# revision: {operation.REVISION}\nservices:\n  app:\n    image: {operation.IMAGE}\n'.encode()
        self.data = {
            ('parent', 'application.binding'): record(dict(binding_version='3', environment='stage', compose_path_hash=self.path_hash,
                mount_fingerprint_version='2', mount_fingerprint=MOUNT_FINGERPRINT, compose_project=self.project, compose_service='app')),
            ('compose', 'docker-compose.override.yml'): override,
            ('state', 'docker-compose.release.yml'): override,
            ('state', 'prior-override'): b'absent',
        }
        values = dict(owner=operation.OWNER, expected_revision=operation.REVISION, image_digest=operation.IMAGE,
            compose_path_hash=self.path_hash, checkpoint='candidate_start_begun', prior_override_exists='no',
            prior_override_sha256=operation.sha(b'absent'), old_app_digest='unknown', old_app_revision='unknown',
            old_container_hash='unknown', old_image_id_hash='unknown', old_started_at_hash='unknown', old_restart_count='unknown',
            compose_project=self.project, compose_service='app', candidate_override_sha256=operation.sha(override),
            migration_image_digest=operation.IMAGE, migration_image_id='sha256:' + 'a' * 64)
        self.data.update({('state', key): value.encode() for key, value in values.items()})
        common = dict(owner=operation.OWNER, environment='stage', expected_revision=operation.REVISION, image_digest=operation.IMAGE,
            compose_path_hash=self.path_hash, operation='migration', invocation_fingerprint=operation.sha(
                f'v1|stage|{operation.OWNER}|{operation.REVISION}|{operation.IMAGE}|{self.path_hash}'.encode()))
        self.data[('ledger', operation.OWNER + '.ledger')] = record(dict(common, ledger_version='1', state='completed', result='completed',
            completion_checkpoint='migration_completed', created_epoch='1', completed_epoch='2'))
        self.data[('ledger', operation.OWNER + '.outcome')] = record(dict(common, outcome_version='1', state='succeeded',
            bounded_result='migration_succeeded', completion_checkpoint='migration_process_succeeded', recorded_epoch='2'))
        self.data[('results', operation.OWNER + '.result')] = record(dict(result_version='1', owner=operation.OWNER, requested_operation='start',
            checkpoint_before='migration_completed', checkpoint_after='candidate_start_begun', result='remote_failure',
            failure_category='candidate_start_failed', expected_revision=operation.REVISION, image_digest=operation.IMAGE,
            compose_path_hash=self.path_hash))
        for (directory, name), data in self.data.items():
            self.write_path(self.paths[directory] / name, data)
        self.write_path(self.paths['compose'] / '.env', b'COMPOSE_FILE=PRIVATE_DOTENV_CANARY\n')
        self.write_path(self.paths['compose'] / 'referenced-secret', b'PRIVATE_REFERENCED_CANARY')
        self.original_open, self.original_fstat, self.original_stat, self.original_read = os.open, os.fstat, os.stat, os.read
        self.root_inode = self.root.stat().st_ino
        self.opens, self.reads, self.allocated, self.mount_calls, self.lock_calls = [], [], [], [], []

    @staticmethod
    def write_path(path, data, mode=0o600):
        path.write_bytes(data)
        path.chmod(mode)

    def inventories(self):
        return {key: set(os.listdir(self.paths[key])) for key in ('parent', 'root', 'state', 'ledger')}

    def close(self):
        self.temp.cleanup()

    @contextlib.contextmanager
    def patches(self, *, meta=None, after_open=None, after_read=None, close=None, mount=None):
        def metadata(value):
            result = {key: getattr(value, key) for key in ('st_dev', 'st_ino', 'st_uid', 'st_gid', 'st_mode',
                'st_nlink', 'st_size', 'st_mtime_ns', 'st_ctime_ns')}
            if result['st_ino'] == self.root_inode:
                result['st_uid'] = 0
            if meta:
                meta(result)
            return types.SimpleNamespace(**result)

        def opening(path, flags, *args, **kwargs):
            if flags & (os.O_CREAT | os.O_TRUNC | os.O_APPEND) or flags & os.O_ACCMODE != os.O_RDONLY:
                raise AssertionError('target write-capable open')
            self.opens.append(str(path))
            fd = self.original_open(self.root if path == '/' else path, flags, *args, **kwargs)
            self.allocated.append(fd)
            if after_open:
                after_open(path, fd)
            return fd

        def reading(fd, count):
            inode = self.original_fstat(fd).st_ino
            self.reads.append((inode, count))
            dotenv = self.paths['compose'] / '.env'
            if dotenv.exists() and inode == self.original_stat(dotenv).st_ino:
                raise AssertionError('dotenv content read')
            data = self.original_read(fd, count)
            if after_read:
                after_read(fd, data)
            return data

        def mounted(argv, *args, **kwargs):
            self.mount_calls.append((argv, kwargs))
            assert argv[:6] == ['findmnt', '--noheadings', '--pairs', '--output', 'FSTYPE,SOURCE,FSROOT,TARGET', '--target']
            assert kwargs['limit'] == 4096 and kwargs['timeout'] <= 2 and len(kwargs['pass_fds']) == 1
            return mount(argv, kwargs) if mount else operation.CaptureResult(0, MOUNT_OUTPUT)

        real_flock = operation.fcntl.flock
        def flock(fd, flags):
            self.lock_calls.append((self.original_fstat(fd).st_ino, flags))
            return real_flock(fd, flags)

        with contextlib.ExitStack() as stack:
            stack.enter_context(patch.object(operation.os, 'open', side_effect=opening))
            stack.enter_context(patch.object(operation.os, 'fstat', side_effect=lambda fd: metadata(self.original_fstat(fd))))
            stack.enter_context(patch.object(operation.os, 'stat', side_effect=lambda *a, **k: metadata(self.original_stat(*a, **k))))
            stack.enter_context(patch.object(operation.os, 'read', side_effect=reading))
            stack.enter_context(patch.object(operation.fcntl, 'flock', side_effect=flock))
            stack.enter_context(patch.object(operation, 'capture_result', side_effect=mounted))
            if close:
                stack.enter_context(patch.object(operation.os, 'close', side_effect=close))
            yield

    def invoke(self, **kwargs):
        cancelled = kwargs.pop('cancelled', lambda: False)
        with self.patches(**kwargs):
            body = operation.diagnose(pwd.getpwuid(os.geteuid()).pw_name, cancelled)
        for fd in self.allocated:
            try:
                self.original_fstat(fd)
            except OSError:
                continue
            raise AssertionError('leaked target FD')
        return body


class LexicalTest(unittest.TestCase):
    CASES = {
        'utf8_invalid': b'\xff', 'tab_active_line': b'\tservices:', 'document_prefix': b'---',
        'directive_prefix': b'%YAML 1.2', 'tag_prefix': b'  key: !tag x', 'anchor_prefix': b'  key: &a x',
        'alias_prefix': b'  - *a', 'flow_mapping_prefix': b'  key: {a: b}',
        'flow_sequence_line_prefix': b'[a,b]', 'mapping_syntax': b'key:no-space',
        'block_scalar_value': b'  key: >', 'top_level_key': b'x-extension:',
        **{'key_' + key: ('  ' + key + ': x').encode() for key in operation.DENIED_KEYS},
    }

    def test_all_22_categories_and_full_aggregate(self):
        self.assertEqual(len(operation.BASIC), 22)
        self.assertEqual(set(self.CASES), operation.BASIC)
        for category, data in self.CASES.items():
            with self.subTest(category=category):
                self.assertIn(category, operation.scan(data)['violations'])
        result = operation.scan(b'\n'.join(self.CASES.values()) * 2)
        self.assertEqual(tuple(sorted(operation.BASIC)), result['violations'])

    def test_branch_applicability_and_quoted_literals(self):
        for data in (b'', b'\n #\tignored\n', b'  key: [a,b]', b'  - [a,b]', b'  - |literal',
                     b'  - env_file: value', b'  key: "&quoted"', b"  key: '*quoted'", b'  -  &not_prefix',
                     b'services:\n  app:\n    environment:\n      NORMAL: value', b'  Env_file: value'):
            with self.subTest(data=data):
                self.assertTrue(original_accepts(data))
                self.assertEqual((), operation.scan(data)['violations'])
        result = operation.scan(b'\tinclude: &a\n  environment:\n    env_file: harmless\n  last: >\n')
        self.assertEqual(set(result['violations']), {'tab_active_line', 'key_include', 'anchor_prefix',
            'top_level_key', 'key_env_file', 'block_scalar_value'})

    def test_fixed_details_and_invalid_utf8_are_lossless_private_only(self):
        samples = {b'\xef\xbb\xbfservices:': 'bom', b'"services":': 'quoted_key', b"'x': v": 'quoted_key',
                   b'<<: *a': 'merge_key', b'x-broken': 'x_extension', b'broken': 'other'}
        for data, detail in samples.items():
            self.assertIn(detail, operation.scan(data)['mapping_details'])
        self.assertEqual(('x_extension',), operation.scan(b'x-meta: value')['top_level_details'])
        raw = b'\xffPRIVATE\n  env_file: NEVER_READ\n'
        self.assertIn('utf8_invalid', operation.scan(raw)['violations'])
        self.assertIn('key_env_file', operation.scan(raw)['violations'])
        self.assertNotIn('PRIVATE', repr(operation.scan(raw)))

    def test_acceptance_equivalence_against_exact_original_scope(self):
        # Oracle is compiled from the unchanged production helper scope, never
        # from a copy of the new scanner. Includes strip/splitlines positions.
        rng = random.Random(91)
        keys = ['services', 'volumes', 'version', 'name', 'networks', 'app', 'env_file', 'Env_file', 'x-meta', '<<', '"quoted"', '\ufeffservices']
        values = ['', 'value', '[a,b]', '{x:y}', '&a', '*a', '!tag', '|', '>', '"&literal"', 'x:y', '\tvalue', '\ud7ff']
        corpus = [b'', (ROOT / 'docker-compose.yml').read_bytes()]
        for _ in range(2500):
            lines = []
            for _ in range(rng.randrange(1, 5)):
                lines.append(rng.choice(['', ' ', '  ', '\t', '\u00a0', '- ', '  - ', '# ']) +
                             rng.choice(keys) + rng.choice([':', ': ', ':\t', ' : ', '']) + rng.choice(values))
            corpus.append(rng.choice(['\n', '\r\n', '\v', '\u2028']).join(lines).encode('utf-8'))
        for data in corpus:
            self.assertEqual(original_accepts(data), not operation.scan(data)['violations'], repr(data))

    def test_empty_scope_is_clear_not_a_compose_validity_claim_and_bounds(self):
        self.assertTrue(original_accepts(b''))
        self.assertEqual((), operation.scan(b'')['violations'])
        self.assertEqual((), operation.scan(b'#' * 65536)['violations'])
        with self.assertRaises(operation.Unavailable):
            operation.scan(b'#' * 65537)


class StaticOracleTest(unittest.TestCase):
    def setUp(self):
        self.fixture = Fixture()
        self.addCleanup(self.fixture.close)

    def oracle(self, field, data, inventories):
        definitions = [n for n in ORIGINAL_TREE.body if isinstance(n, ast.FunctionDef) and n.name == 'unique']
        definitions += [n for n in ORIGINAL_CLASS.body if isinstance(n, ast.FunctionDef) and n.name in ('record', 'value')]
        namespace = dict(check=rejected)
        exec(compile(ast.Module(body=definitions, type_ignores=[]), '<exact-approved-record-readers>', 'exec'), namespace)
        def read(directory, name, limit=4096):
            value = data[(directory, name)]
            rejected(len(value) <= limit)
            return value
        ctx = types.SimpleNamespace(owner=operation.OWNER, revision=operation.REVISION, image=operation.IMAGE,
            path_hash=self.fixture.path_hash, project=self.fixture.project, read=read, state_keys=operation.STATE_KEYS,
            directories={key: key for key in inventories}, config_hashes={'override': operation.sha(data.get(('compose', 'docker-compose.override.yml'), b''))})
        for name in ('record', 'value'):
            setattr(ctx, name, types.MethodType(namespace[name], ctx))
        method, guard = ('capture_configuration', 'override') if field == 'override' else ('check_evidence', field)
        code = compile(ast.Module(body=scope(method, guard), type_ignores=[]), '<exact-approved-static-scope>', 'exec')
        try:
            exec(code, dict(self=ctx, check=rejected, sha=operation.sha, re=re,
                            os=types.SimpleNamespace(listdir=lambda key: list(inventories[key]))))
            return True
        except (operation.Invalid, UnicodeError, KeyError):
            return False

    def test_exact_predicate_differential_and_independent_joint_failures(self):
        f = self.fixture
        variants = [dict(f.data)]
        for key in f.data:
            for bad in (b'', b'PRIVATE_CANARY\n', b'\xff', f.data[key] + b'\n', f.data[key].replace(b'completed', b'started')):
                variants.append({**f.data, key: bad})
        for data in variants:
            checks = operation.StaticRecords(data, f.inventories(), f.project)
            for field in ('retained_layout', 'retained_identity', 'retained_checkpoint', 'prior_override', 'migration_records', 'result_record'):
                self.assertEqual(self.oracle(field, data, f.inventories()), checks.evaluate(field) == 'pass', field)
            self.assertEqual(self.oracle('override', data, f.inventories()),
                             checks.evaluate('managed_override') == checks.evaluate('managed_release') == 'pass')
        data = {**f.data, ('state', 'owner'): b'wrong', ('state', 'checkpoint'): b'wrong',
                ('results', operation.OWNER + '.result'): b'malformed'}
        checks = operation.StaticRecords(data, f.inventories(), f.project)
        self.assertEqual([checks.evaluate(key) for key in ('retained_identity', 'retained_checkpoint', 'result_record')], ['invalid'] * 3)
        self.assertEqual(checks.evaluate('migration_records'), 'pass')

    def test_inventory_and_record_exactness_and_dependency_not_evaluated(self):
        f = self.fixture
        for field, key in [('retained_layout', 'state'), ('retained_layout', 'root'), ('migration_records', 'ledger')]:
            inventory = f.inventories(); inventory[key].add('untrusted-extra')
            checks = operation.StaticRecords(f.data, inventory, f.project)
            self.assertFalse(self.oracle(field, f.data, inventory))
            self.assertEqual(checks.evaluate(field), 'invalid')
        for field, dependencies in operation.DEPENDENCIES.items():
            for key in dependencies:
                data = dict(f.data); data.pop(key)
                self.assertEqual(operation.StaticRecords(data, f.inventories(), f.project).evaluate(field), 'not_evaluated')
        data = {**f.data, ('state', 'prior_override_exists'): b'yes'}
        data.pop(('state', 'old_app_digest'))
        self.assertEqual(operation.StaticRecords(data, f.inventories(), f.project).evaluate('prior_override'), 'not_evaluated')

    def test_prior_override_yes_exact_variants_and_duplicate_record_keys(self):
        f = self.fixture
        for revision_line in ('', '# revision: ' + operation.REVISION + '\n'):
            prior = ('# clubs-bot-managed-quiesced-release\n' + revision_line + 'services:\n  app:\n    image: ' + operation.IMAGE + '\n').encode()
            data = {**f.data, ('state', 'prior_override_exists'): b'yes', ('state', 'prior-override'): prior,
                    ('state', 'prior_override_sha256'): operation.sha(prior).encode(),
                    ('state', 'old_app_digest'): operation.IMAGE.encode(), ('state', 'old_app_revision'): operation.REVISION.encode()}
            self.assertTrue(self.oracle('prior_override', data, f.inventories()))
            self.assertEqual(operation.StaticRecords(data, f.inventories(), f.project).evaluate('prior_override'), 'pass')
        key = ('results', operation.OWNER + '.result')
        duplicate = f.data[key].replace(b'requested_operation=start', b'owner=' + operation.OWNER.encode())
        data = {**f.data, key: duplicate}
        self.assertFalse(self.oracle('result_record', data, f.inventories()))
        self.assertEqual(operation.StaticRecords(data, f.inventories(), f.project).evaluate('result_record'), 'invalid')


class FilesystemTest(unittest.TestCase):
    def setUp(self):
        self.f = Fixture()
        self.addCleanup(self.f.close)

    def test_real_fd_shared_locks_complete_invalid_and_no_target_writes(self):
        f = self.f
        f.write_path(f.paths['base'], b'\tinclude: &PRIVATE_CANARY\n  env_file: referenced-secret\n  build: .\n', 0o644)
        before = {path: (path.read_bytes(), stat.S_IMODE(path.stat().st_mode)) for path in f.root.rglob('*') if path.is_file()}
        with contextlib.ExitStack() as stack:
            for name in ('chmod', 'fchmod', 'chown', 'fchown', 'truncate', 'ftruncate', 'rename', 'replace', 'unlink', 'mkdir', 'fsync'):
                stack.enter_context(patch.object(operation.os, name, side_effect=AssertionError('target mutation')))
            body = f.invoke()
        result = fields(body)
        self.assertEqual(result['result'], 'complete')
        self.assertEqual(result['subset'], 'invalid')
        self.assertIn('key_env_file', result['violations'])
        self.assertTrue(all(result[key] == 'pass' for key in operation.STATIC_FIELDS))
        self.assertNotIn(b'PRIVATE', body)
        self.assertEqual(f.opens.count('docker-compose.yml'), 1)
        self.assertNotIn('referenced-secret', f.opens)
        self.assertNotIn('old_app_digest', f.opens)
        self.assertEqual(len(f.mount_calls), 16)
        self.assertEqual(f.lock_calls, [(f.paths[key].stat().st_ino, operation.fcntl.LOCK_SH | operation.fcntl.LOCK_NB)
                                      for key in ('application_lock', 'operation_lock')])
        self.assertEqual(before, {path: (path.read_bytes(), stat.S_IMODE(path.stat().st_mode)) for path in before})

    def test_base_valid_modes_and_read_based_exact_limit(self):
        for mode in (0o600, 0o644):
            for size in (0, 65536, 65537):
                with self.subTest(mode=mode, size=size):
                    f = Fixture()
                    try:
                        f.write_path(f.paths['base'], b'#' * size, mode)
                        result = fields(f.invoke())
                        self.assertEqual(result['result'], 'complete' if size <= 65536 else 'unavailable')
                        self.assertEqual(f.opens.count('docker-compose.yml'), 1)
                        if size > 65536:
                            self.assertEqual(result['reason'], 'bounds')
                            self.assertNotIn('docker-compose.override.yml', f.opens)
                    finally:
                        f.close()

    def test_base_symlink_directory_fifo_hardlink_and_mode_block_before_dependents(self):
        for kind in ('symlink', 'directory', 'fifo', 'hardlink', 'mode'):
            with self.subTest(kind=kind):
                f = Fixture()
                try:
                    base = f.paths['base']
                    if kind == 'mode':
                        base.chmod(0o664)
                    elif kind == 'hardlink':
                        os.link(base, f.root / 'hardlink')
                    else:
                        base.unlink()
                        if kind == 'symlink': base.symlink_to(f.paths['compose'] / 'referenced-secret')
                        elif kind == 'directory': base.mkdir()
                        else: os.mkfifo(base)
                    self.assertEqual(fields(f.invoke())['result'], 'unavailable')
                    self.assertNotIn('docker-compose.override.yml', f.opens)
                finally:
                    f.close()

    def test_base_owner_device_and_directory_guards(self):
        for attribute in ('st_uid', 'st_dev'):
            f = Fixture()
            try:
                inode = f.paths['base'].stat().st_ino
                def meta(value):
                    if value['st_ino'] == inode: value[attribute] += 100
                self.assertEqual(fields(f.invoke(meta=meta))['result'], 'unavailable')
                self.assertNotIn('docker-compose.override.yml', f.opens)
            finally:
                f.close()
        for target in ('opt', 'compose', 'parent', 'state', 'results', 'ledger'):
            f = Fixture()
            try:
                f.paths[target].chmod(0o777)
                self.assertEqual(fields(f.invoke())['result'], 'unavailable')
                self.assertEqual(f.reads, [])
            finally:
                f.close()

    def test_missing_malformed_and_both_busy_lock_boundaries(self):
        for key in ('application_lock', 'operation_lock'):
            for kind in ('missing', 'mode', 'hardlink', 'busy', 'shared'):
                with self.subTest(key=key, kind=kind):
                    f = Fixture(); held = None
                    try:
                        path = f.paths[key]
                        if kind == 'missing': path.unlink()
                        elif kind == 'mode': path.chmod(0o644)
                        elif kind == 'hardlink': os.link(path, f.root / 'lock-neighbor')
                        else:
                            held = os.open(path, os.O_RDONLY)
                            operation.fcntl.flock(held, (operation.fcntl.LOCK_SH if kind == 'shared' else operation.fcntl.LOCK_EX) | operation.fcntl.LOCK_NB)
                        result = fields(f.invoke())
                        self.assertEqual(result['result'], 'complete' if kind == 'shared' else 'unavailable')
                        if kind == 'busy': self.assertEqual(result['reason'], 'busy')
                        if kind != 'shared': self.assertEqual(f.reads, [])
                    finally:
                        if held is not None: os.close(held)
                        f.close()

    def test_rename_in_place_and_edge_drift_before_after_read(self):
        for phase in ('after_open', 'rename_after_read', 'in_place_after_read'):
            f = Fixture(); changed = [False]
            try:
                inode = f.paths['base'].stat().st_ino
                def change():
                    if changed[0]: return
                    changed[0] = True
                    if phase == 'in_place_after_read':
                        with f.paths['base'].open('ab') as stream: stream.write(b'\n  build: late')
                    else:
                        neighbor = f.paths['compose'] / 'replacement'
                        f.write_path(neighbor, b'services:\n', 0o644)
                        os.replace(neighbor, f.paths['base'])
                def opened(path, fd):
                    if path == 'docker-compose.yml': change()
                def read(fd, data):
                    if f.original_fstat(fd).st_ino == inode: change()
                result = fields(f.invoke(**({'after_open': opened} if phase == 'after_open' else {'after_read': read})))
                self.assertEqual(result['result'], 'unavailable')
                self.assertEqual(result['reason'], 'identity')
                self.assertNotIn('docker-compose.override.yml', f.opens)
            finally:
                f.close()

    def test_record_metadata_missing_and_independent_failures(self):
        f = self.f
        (f.paths['state'] / 'owner').unlink()
        (f.paths['results'] / (operation.OWNER + '.result')).chmod(0o644)
        f.write_path(f.paths['state'] / 'checkpoint', b'invalid')
        body = fields(f.invoke())
        self.assertEqual(body['result'], 'complete')
        self.assertEqual(body['static_inputs'], 'invalid')
        self.assertEqual(body['retained_identity'], 'not_evaluated')
        self.assertEqual(body['retained_layout'], 'invalid')
        self.assertEqual(body['retained_checkpoint'], 'invalid')
        self.assertEqual(body['result_record'], 'not_evaluated')
        self.assertEqual(body['migration_records'], 'pass')

    def test_optional_dotenv_metadata_only_absent_and_unsafe(self):
        for kind in ('absent', 'symlink', 'mode'):
            f = Fixture()
            try:
                path = f.paths['compose'] / '.env'
                if kind == 'mode': path.chmod(0o644)
                else:
                    path.unlink()
                    if kind == 'symlink': path.symlink_to(f.paths['compose'] / 'referenced-secret')
                body = fields(f.invoke())
                self.assertEqual(body['result'], 'complete')
                self.assertEqual(body['dotenv_metadata'], 'absent' if kind == 'absent' else 'invalid')
            finally:
                f.close()

    def test_inventory_record_and_total_bounds_never_publish_partial_complete(self):
        for kind in ('inventory', 'record', 'total'):
            f = Fixture()
            try:
                if kind == 'inventory':
                    for number in range(65): (f.paths['parent'] / ('entry-' + str(number))).touch()
                if kind == 'record': f.write_path(f.paths['state'] / 'owner', b'x' * 4097)
                with patch.object(operation, 'TOTAL_LIMIT', 1 if kind == 'total' else operation.TOTAL_LIMIT):
                    body = fields(f.invoke())
                self.assertEqual(body, {'result': 'unavailable', 'reason': 'bounds'})
            finally:
                f.close()

    def test_backing_predicate_failure_and_final_mount_drift(self):
        for output in (b'', b'bad', MOUNT_OUTPUT.replace(b'ext4', b'nfs'), MOUNT_OUTPUT.replace(b'/dev/synthetic', b'bad\\escape'),
                       MOUNT_OUTPUT.replace(b'FSROOT="/"', b'FSROOT="relative"')):
            f = Fixture()
            try:
                result = fields(f.invoke(mount=lambda *unused: operation.CaptureResult(0, output)))
                self.assertEqual(result['result'], 'unavailable')
                self.assertNotIn('docker-compose.yml', f.opens)
            finally:
                f.close()
        count = [0]
        def mount(*unused):
            count[0] += 1
            return operation.CaptureResult(0, MOUNT_OUTPUT if count[0] <= 8 else MOUNT_OUTPUT.replace(b'synthetic', b'changed'))
        self.assertEqual(fields(self.f.invoke(mount=mount)), {'result': 'unavailable', 'reason': 'backing'})

    def test_cancellation_and_cleanup_failures_cannot_publish_complete(self):
        for phase in ('before', 'read', 'last_close', 'close_error'):
            f = Fixture(); cancel = [phase == 'before']; original_close = os.close; closed = []
            try:
                def read(fd, data): cancel[0] = True
                def close(fd):
                    original_close(fd); closed.append(fd)
                    if phase == 'last_close' and len(closed) == len(f.allocated): cancel[0] = True
                    if phase == 'close_error' and len(closed) == 1: raise OSError('PRIVATE exception')
                result = fields(f.invoke(cancelled=lambda: cancel[0], close=close,
                                         **({'after_read': read} if phase == 'read' else {})))
                self.assertEqual(result['result'], 'unavailable')
                self.assertEqual(result['reason'], 'cleanup' if phase == 'close_error' else 'interrupted')
                self.assertNotIn('PRIVATE', str(result))
            finally:
                f.close()


    def test_lock_window_replacement_and_final_snapshot_revalidation(self):
        for phase in ('before_locks', 'between_locks', 'final_snapshot'):
            f = Fixture()
            changed = [False]
            mount_calls = [0]
            real_flock = operation.fcntl.flock
            try:
                def replace_state():
                    if not changed[0]:
                        changed[0] = True
                        os.rename(f.paths['state'], f.paths['root'] / 'moved-state')
                        f.paths['state'].mkdir(mode=0o700)
                def opened(path, fd):
                    if path == 'operation.lock' and phase == 'before_locks':
                        replace_state()
                def locked(fd, flags):
                    result = real_flock(fd, flags)
                    if phase == 'between_locks':
                        replace_state()
                    return result
                def mounted(*unused):
                    mount_calls[0] += 1
                    if phase == 'final_snapshot' and mount_calls[0] == 16:
                        for key in ('application_lock', 'operation_lock'):
                            held = f.original_open(f.paths[key], os.O_RDONLY)
                            try:
                                with self.assertRaises(BlockingIOError):
                                    real_flock(held, operation.fcntl.LOCK_EX | operation.fcntl.LOCK_NB)
                            finally:
                                os.close(held)
                        with f.paths['base'].open('ab') as stream:
                            stream.write(b'\n  build: late')
                    return operation.CaptureResult(0, MOUNT_OUTPUT)
                with patch.object(operation.fcntl, 'flock', side_effect=locked):
                    result = fields(f.invoke(after_open=opened, mount=mounted))
                self.assertEqual(result, {'result': 'unavailable', 'reason': 'identity'})
                if phase != 'final_snapshot':
                    self.assertEqual(f.reads, [])
            finally:
                f.close()


class ProtocolTest(unittest.TestCase):
    def setUp(self):
        self.nonce = b'n' * 32
        self.statuses = {key: 'pass' for key in operation.STATIC_FIELDS}
        self.body = operation.complete(operation.scan(b'services:\n  app:\n    env_file: PRIVATE\n'), self.statuses)

    def frame(self, body, nonce=None):
        return b'clb91-compose-auth:v=1 tag=' + hmac.new(nonce or self.nonce, body, hashlib.sha256).hexdigest().encode() + b' ' + body

    def test_complete_invalid_and_unavailable_exit_mapping(self):
        self.assertEqual(runner.parse_result(self.frame(self.body), 0, self.nonce, operation), (self.body.decode().strip(), 0))
        for reason in operation.REASONS:
            body = operation.unavailable(reason)
            self.assertEqual(runner.parse_result(self.frame(body), 1, self.nonce, operation), (body.decode().strip(), 1))
        for body, code in ((self.body, 1), (operation.unavailable('io'), 0), (self.body, 255)):
            with self.assertRaises((ValueError, operation.Unavailable)):
                runner.parse_result(self.frame(body), code, self.nonce, operation)

    def test_authentication_replay_duplicate_unknown_reordered_extra_and_startup(self):
        parts = self.body.split(b' ')
        reordered = b' '.join(parts[:2] + [parts[3], parts[2]] + parts[4:])
        bad_bodies = [reordered, self.body.replace(b'subset=invalid', b'subset=clear'),
            self.body.replace(b'result=complete', b'result=unknown'), self.body.replace(b'key_env_file', b'PRIVATE_KEY'),
            self.body.replace(b'key_env_file', b'key_env_file,key_env_file'), self.body.replace(b'pass', b'unknown', 1),
            self.body.replace(b'\n', b' extra=PRIVATE\n'), self.body.replace(b'\n', b'\r\n'),
            self.body.replace(b'static_inputs=pass', b'static_inputs=pass static_inputs=pass')]
        raws = [self.frame(body) for body in bad_bodies] + [self.body, b'PRIVATE startup\n' + self.frame(self.body),
            self.frame(self.body) * 2, self.frame(self.body) + b'\n', self.frame(self.body, b'x' * 32), b'x' * 4097]
        for raw in raws:
            with self.subTest(raw=raw[:50]), self.assertRaises((ValueError, operation.Unavailable)):
                runner.parse_result(raw, 0, self.nonce, operation)

    def test_full_enum_contract_fits_measured_public_private_bounds(self):
        lexical = dict(violations=tuple(sorted(operation.BASIC)), mapping_details=tuple(sorted(operation.DETAILS)),
                       top_level_details=tuple(sorted(operation.DETAILS)))
        maximum = operation.complete(lexical, {key: 'not_evaluated' for key in operation.STATIC_FIELDS})
        self.assertLessEqual(len(maximum), 2048)
        self.assertLessEqual(len(self.frame(maximum)), 4096)
        self.assertEqual(runner.parse_result(self.frame(maximum), 0, self.nonce, operation)[1], 0)
        self.assertNotIn(b'PRIVATE', self.body)

    def test_final_local_handoff_pending_signal_beats_cached_complete(self):
        written = []
        with patch.object(runner.signal, 'pthread_sigmask'), patch.object(runner.signal, 'sigpending', return_value={signal.SIGTERM}), \
             patch.object(runner.os, 'write', side_effect=lambda fd, data: written.append(data) or len(data)):
            code = runner.publish(self.body.decode().strip(), 0, [False])
        self.assertEqual(code, 1)
        self.assertEqual(written, [operation.unavailable('interrupted')])

    def test_remote_bootstrap_rejects_source_mismatch_without_raw_output(self):
        source = (ROOT / runner.REMOTE_PATH).read_bytes()
        control = json.dumps(dict(principal=pwd.getpwuid(os.geteuid()).pw_name, sha256='0' * 64)).encode()
        payload = self.nonce + struct.pack('!I', len(control)) + control + source
        result = subprocess.run([sys.executable, '-I', '-S', '-B', '-c', runner.BOOTSTRAP], input=payload,
                                capture_output=True, timeout=10)
        self.assertEqual((result.returncode, result.stdout, result.stderr), (1, b'', b''))


class RunnerTest(unittest.TestCase):
    def setUp(self):
        self.env = dict(GITHUB_REPOSITORY='koteev-m/clubs_bot', GITHUB_EVENT_NAME='workflow_dispatch',
            GITHUB_REF='refs/heads/main', GITHUB_REF_TYPE='branch', REPOSITORY_DEFAULT_BRANCH='main',
            APP_ENV='stage', CONFIRMATION=runner.CONFIRMATION, GITHUB_RUN_ATTEMPT='1', GITHUB_SHA='a' * 40,
            GITHUB_WORKFLOW_REF='koteev-m/clubs_bot/' + runner.WORKFLOW_PATH + '@refs/heads/main',
            SSH_USER='synthetic', SSH_HOST='fixture.invalid', SSH_PORT='22', COMPOSE_PATH=operation.COMPOSE_PATH)

    def test_fixture_root_rejects_checkout_before_any_creation(self):
        with patch.dict(os.environ, {'RUNNER_TEMP': str(ROOT)}), patch.object(tempfile, 'TemporaryDirectory') as allocate:
            with self.assertRaisesRegex(RuntimeError, 'outside the checkout'):
                fixture_temporary_directory('must-not-create-')
            allocate.assert_not_called()

    def test_request_first_attempt_and_fixed_target_boundary(self):
        runner.validate(self.env)
        runner.validate_target(self.env)
        for key in ('GITHUB_REPOSITORY', 'GITHUB_EVENT_NAME', 'GITHUB_REF', 'GITHUB_REF_TYPE',
                    'REPOSITORY_DEFAULT_BRANCH', 'APP_ENV', 'CONFIRMATION', 'GITHUB_RUN_ATTEMPT',
                    'GITHUB_SHA', 'GITHUB_WORKFLOW_REF'):
            for value in ('', 'other', '2'):
                with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                    runner.validate(dict(self.env, **{key: value}))
        for key, value in (('COMPOSE_PATH', '/other'), ('SSH_USER', 'root'), ('SSH_USER', 'hookah-staging'),
                           ('SSH_USER', 'user; id'), ('SSH_HOST', '-oProxyCommand=id'), ('SSH_HOST', 'a\nb'),
                           ('SSH_PORT', '0'), ('SSH_PORT', '65536'), ('SSH_PORT', '22 -F other')):
            with self.assertRaises(ValueError):
                runner.validate_target(dict(self.env, **{key: value}))

    def test_hardened_transport_and_capture_are_exact_corrected_primitives(self):
        source = (ROOT / 'scripts/deploy/corrected-stage-release.py').read_text()
        vectors = [node for node in ast.walk(ast.parse(source)) if isinstance(node, ast.List) and node.elts
                   and isinstance(node.elts[0], ast.Constant) and node.elts[0].value == 'ssh']
        self.assertEqual(len(vectors), 1)
        expected = eval(compile(ast.Expression(vectors[0]), '<exact-corrected-ssh>', 'eval'),
                        {'env': self.env, 'reference': '/PRIVATE_PIN', 'command': 'unused'})
        actual = runner.ssh_argv(self.env, '/PRIVATE_PIN')
        self.assertEqual(actual[:-1], expected[:-1])
        self.assertIn('test "$(id -un)" = synthetic', actual[-1])
        self.assertIn('test "$(id -u)" != 0', actual[-1])
        original = source[source.index('class CaptureResult:'):source.index('# Fixed transport contract only;')]
        new = (ROOT / runner.REMOTE_PATH).read_text()
        copied = new[new.index('class CaptureResult:'):new.index('# END EXACT CORRECTED CAPTURE PRIMITIVES')]
        self.assertEqual(copied, original)
        transport = runner.load_transport()
        self.assertEqual(transport.capture_result.__code__.co_filename, str(ROOT / 'scripts/deploy/corrected-stage-release.py'))
        self.assertEqual(transport.pinned_hosts.__wrapped__.__code__.co_filename, str(ROOT / 'scripts/deploy/corrected-stage-release.py'))

    def test_actual_capture_output_timeout_and_process_cleanup(self):
        success = operation.capture_result([sys.executable, '-I', '-S', '-B', '-c', 'print("bounded")'], limit=8)
        self.assertEqual((success.code, success.output, success.failure), (0, b'bounded\n', None))
        overflow = operation.capture_result([sys.executable, '-I', '-S', '-B', '-c', 'print("x" * 4097)'], limit=4096)
        self.assertEqual((overflow.code, overflow.output, overflow.failure), (125, b'', 'capture_output_limit'))
        timeout = operation.capture_result([sys.executable, '-I', '-S', '-B', '-c', 'import time; time.sleep(30)'], timeout=.1)
        self.assertEqual((timeout.code, timeout.failure), (124, 'capture_timeout'))
        real_spawn = subprocess.Popen
        def cancelled_spawn(*args, **kwargs):
            child = real_spawn(*args, **kwargs)
            operation._capture_cancellation[0] = True
            return child
        with patch.object(operation.subprocess, 'Popen', side_effect=cancelled_spawn):
            cancelled = operation.capture_result([sys.executable, '-I', '-S', '-B', '-c', 'print("not complete")'])
        self.assertEqual(cancelled.failure, 'capture_interrupted')

    def test_one_transport_and_cleanup_failure_reject_cached_complete(self):
        transport = runner.load_transport()
        source = (ROOT / runner.REMOTE_PATH).read_bytes()
        body = operation.complete(operation.scan(b''), {key: 'pass' for key in operation.STATIC_FIELDS})
        for sig in runner.WATCHED:
            self.addCleanup(signal.signal, sig, signal.getsignal(sig))
        for failure in ('transport', 'protocol', 'cleanup', 'cancel'):
            calls = []
            cancelled = [False]
            with tempfile.TemporaryFile() as pin:
                @contextlib.contextmanager
                def pinned(env):
                    yield pin.fileno(), '/PRIVATE_PIN'
                    if failure == 'cleanup':
                        raise OSError('PRIVATE cleanup')
                    if failure == 'cancel':
                        cancelled[0] = True

                def capture(argv, payload, **kwargs):
                    calls.append((argv, kwargs))
                    nonce = payload[:32]
                    tag = hmac.new(nonce, body, hashlib.sha256).hexdigest().encode()
                    output = b'clb91-compose-auth:v=1 tag=' + tag + b' ' + body
                    if failure == 'protocol':
                        output += b'PRIVATE extra\n'
                    return transport.CaptureResult(0, output, 'capture_timeout' if failure == 'transport' else None)

                with patch.object(runner, 'load_transport', return_value=transport), patch.object(runner, 'snapshot', return_value=source), \
                     patch.object(transport, 'pinned_hosts', pinned), patch.object(transport, 'capture_result', side_effect=capture):
                    line, code = runner.main(dict(self.env, SSH_KNOWN_HOSTS='PRIVATE', PATH=os.defpath), [], cancelled)
            self.assertEqual(code, 1)
            self.assertIn('result=unavailable', line)
            self.assertNotIn('PRIVATE', line)
            self.assertEqual(len(calls), 1)
            self.assertEqual(calls[0][1]['limit'], 4096)
            self.assertEqual(set(calls[0][1]['env']), {'PATH', 'LC_ALL'})

    def test_workflow_exact_security_contract_and_negative_mutations(self):
        ruby = '''require "validate-workflow-capabilities";
w,_=WorkflowCapabilityPolicy.load_workflow(Pathname.new(ARGV[0]),StageComposeDiagnosticWorkflow::PATH);
case ARGV[1]
when "path"; w["on"]["workflow_dispatch"]["inputs"]["path"]={"type"=>"string"}
when "action"; w["on"]["workflow_dispatch"]["inputs"]["action"]={"type"=>"string"}
when "trigger"; w["on"]["push"]={}
when "environment"; w["jobs"]["diagnose"]["environment"]="prod"
when "concurrency"; w["concurrency"]["group"]="other"
when "cancel"; w["concurrency"]["cancel-in-progress"]=true
when "credentials"; s=w["jobs"]["diagnose"]["steps"]; s[1],s[2]=s[2],s[1]
when "write"; w["permissions"]["contents"]="write"
when "command"; w["jobs"]["diagnose"]["steps"][-1]["run"]="arbitrary command"
when "checkout"; w["jobs"]["diagnose"]["steps"][0]["with"]["ref"]="main"
when "validation"; w["jobs"]["diagnose"]["steps"].delete_at(1)
when "confirmation"; w["on"]["workflow_dispatch"]["inputs"]["confirmation"]["default"]="automatic"
end
StageComposeDiagnosticWorkflow.validate(WorkflowCapabilityPolicy,w)
'''
        for mutation in ('none', 'path', 'action', 'trigger', 'environment', 'concurrency', 'cancel',
                         'credentials', 'write', 'command', 'checkout', 'validation', 'confirmation'):
            child = subprocess.run(['ruby', '-I', str(ROOT / 'scripts'), '-e', ruby, str(ROOT), mutation],
                                   capture_output=True, text=True, timeout=10)
            self.assertEqual(child.returncode == 0, mutation == 'none', child.stderr)

    def test_validator_selfcheck_topology_and_visible_inventory(self):
        selfcheck = (ROOT / 'scripts/selfcheck-quality-gates.sh').read_text()
        self.assertEqual(selfcheck.count('python3 "$ROOT_DIR/scripts/tests/test_stage_compose_diagnostic.py"'), 1)
        self.assertEqual(selfcheck.count('python3 "$ROOT_DIR/scripts/tests/test_stage_compose_mode_repair.py"'), 1)
        for name in ('test_read_only_release_status.py', 'test_corrected_stage_release.py', 'test_quiesced_release_state.py'):
            self.assertIn(name, selfcheck)
        ruby = '''require "validate-workflow-yaml"; require "validate-workflow-capabilities";
root=Pathname.new(ARGV[0]); paths=WorkflowYamlSafety.visible_workflows(root);
raise "inventory" unless paths.length == 24;
raise "yaml" unless WorkflowYamlSafety.run(root) == 0;
WorkflowCapabilityPolicy.run(root);
'''
        child = subprocess.run(['ruby', '-I', str(ROOT / 'scripts'), '-e', ruby, str(ROOT)], capture_output=True, text=True, timeout=30)
        self.assertEqual(child.returncode, 0, child.stderr)


class EndToEndTest(unittest.TestCase):
    """Actual Git snapshot -> pin -> substitute ssh -> bootstrap -> FD capture -> HMAC parser.

    Only synthetic root ownership/path and Linux findmnt output are substituted.
    This is explicitly not hosted Linux filesystem evidence. Fixture source is
    committed only in disposable Git repositories; checkout bytes never change.
    """
    def setUp(self):
        self.fixture = Fixture()
        self.addCleanup(self.fixture.close)
        self.temp = fixture_temporary_directory('clb91-e2e-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve()
        self.repo, self.bin = self.root / 'repo', self.root / 'bin'
        self.repo.mkdir()
        self.bin.mkdir()
        self.calls = self.root / 'transport-calls'
        self.injection = '''
# Disposable fixture injection, excluded from the production Git objects.
if os.environ.get('CLB91_SYNTHETIC_REMOTE') == '1':
    import types as _types
    _root = ROOT_LITERAL
    _root_inode = os.stat(_root).st_ino
    _real_open, _real_fstat, _real_stat = os.open, os.fstat, os.stat
    def _metadata(value):
        fields = {key: getattr(value, key) for key in ('st_dev', 'st_ino', 'st_uid', 'st_gid', 'st_mode',
                  'st_nlink', 'st_size', 'st_mtime_ns', 'st_ctime_ns')}
        if fields['st_ino'] == _root_inode: fields['st_uid'] = 0
        return _types.SimpleNamespace(**fields)
    def _opening(path, flags, *args, **kwargs):
        assert flags & os.O_ACCMODE == os.O_RDONLY and not flags & (os.O_CREAT | os.O_TRUNC | os.O_APPEND)
        return _real_open(_root if path == '/' else path, flags, *args, **kwargs)
    os.open = _opening
    os.fstat = lambda fd: _metadata(_real_fstat(fd))
    os.stat = lambda *args, **kwargs: _metadata(_real_stat(*args, **kwargs))
    def _findmnt(argv, *args, **kwargs):
        assert argv[:6] == ['findmnt', '--noheadings', '--pairs', '--output', 'FSTYPE,SOURCE,FSROOT,TARGET', '--target']
        return CaptureResult(0, MOUNT_LITERAL)
    capture_result = _findmnt
'''.replace('ROOT_LITERAL', repr(str(self.fixture.root))).replace('MOUNT_LITERAL', repr(MOUNT_OUTPUT))
        for path in runner.LOCAL_SOURCES:
            destination = self.repo / path
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes((ROOT / path).read_bytes())
        self.operation_path = self.repo / runner.REMOTE_PATH
        self.operation_path.write_text((ROOT / runner.REMOTE_PATH).read_text() + self.injection)
        self.git_env = dict(os.environ, GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_SYSTEM=os.devnull,
                            GIT_TERMINAL_PROMPT='0', GIT_AUTHOR_NAME='Disposable Fixture',
                            GIT_AUTHOR_EMAIL='fixture@example.invalid', GIT_COMMITTER_NAME='Disposable Fixture',
                            GIT_COMMITTER_EMAIL='fixture@example.invalid')
        self.git('-c', 'init.templateDir=', 'init', '-q')
        self.git('config', 'core.hooksPath', os.devnull)
        self.git('add', 'scripts')
        self.git('commit', '-q', '-m', 'synthetic production path fixture')
        sha = self.git('rev-parse', 'HEAD').decode().strip()
        subprocess.run(['ssh-keygen', '-q', '-t', 'ed25519', '-N', '', '-f', str(self.root / 'key')],
                       check=True, capture_output=True, timeout=10)
        public = (self.root / 'key.pub').read_text().split()
        self.env = dict(os.environ, GITHUB_REPOSITORY='koteev-m/clubs_bot', GITHUB_EVENT_NAME='workflow_dispatch',
            GITHUB_REF='refs/heads/main', GITHUB_REF_TYPE='branch', REPOSITORY_DEFAULT_BRANCH='main',
            APP_ENV='stage', CONFIRMATION=runner.CONFIRMATION, GITHUB_RUN_ATTEMPT='1', GITHUB_SHA=sha,
            GITHUB_WORKFLOW_REF='koteev-m/clubs_bot/' + runner.WORKFLOW_PATH + '@refs/heads/main',
            SSH_USER=pwd.getpwuid(os.geteuid()).pw_name, SSH_HOST='fixture.invalid', SSH_PORT='22',
            COMPOSE_PATH=operation.COMPOSE_PATH, SSH_KNOWN_HOSTS='fixture.invalid ' + ' '.join(public[:2]) + '\n',
            TMPDIR=str(self.root), PATH=str(self.bin) + os.pathsep + os.environ['PATH'])
        self.transport()

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.repo), *args], env=self.git_env, stderr=subprocess.DEVNULL)

    def transport(self, behavior='normal'):
        script = '''#!PYTHON -I -S -B
import os, sys, shlex, subprocess
with open(CALLS, 'ab') as stream: stream.write(b'invoked\\n')
command = shlex.split(sys.argv[-1])
assert command[-6:-1] == ['python3', '-I', '-S', '-B', '-c']
env = dict(os.environ, CLB91_SYNTHETIC_REMOTE='1')
argv = [PYTHON_LITERAL, '-I', '-S', '-B', '-c', command[-1]]
BEHAVIOR
'''.replace('PYTHON_LITERAL', repr(sys.executable)).replace('PYTHON', sys.executable).replace('CALLS', repr(str(self.calls)))
        behaviors = {
            'normal': 'os.execve(argv[0], argv, env)',
            'startup': "os.write(1, b'PRIVATE startup spoof\\n'); os.execve(argv[0], argv, env)",
            'bad_auth': "data=sys.stdin.buffer.read(); data=b'x'*32+data[32:]; result=subprocess.run(argv,input=data,env=env,stdout=subprocess.PIPE); os.write(1,result.stdout); sys.exit(result.returncode)",
            'wrong_exit': "result=subprocess.run(argv,input=sys.stdin.buffer.read(),env=env,stdout=subprocess.PIPE); os.write(1,result.stdout); sys.exit(1)",
        }
        Fixture.write_path(self.bin / 'ssh', script.replace('BEHAVIOR', behaviors[behavior]).encode(), 0o700)

    def invoke(self, *args, env=None):
        return subprocess.run([sys.executable, '-I', '-S', '-B', str(self.repo / runner.RUNNER_PATH), *args],
                              env=env or self.env, capture_output=True, timeout=30)

    def test_full_synthetic_path_aggregate_static_records_no_leak(self):
        Fixture.write_path(self.fixture.paths['base'], b'services:\n  app:\n    env_file: referenced-secret\n    label_file: PRIVATE\n    build: PRIVATE\n', 0o644)
        Fixture.write_path(self.fixture.paths['state'] / 'checkpoint', b'PRIVATE_CHECKPOINT')
        startup = self.root / 'startup'
        startup.mkdir()
        (startup / 'sitecustomize.py').write_text('raise RuntimeError("PRIVATE startup injection")\n')
        (startup / 'hashlib.py').write_text('raise RuntimeError("PRIVATE module injection")\n')
        self.env['PYTHONPATH'] = str(startup)
        self.env['PYTHONSTARTUP'] = str(startup / 'sitecustomize.py')
        before = {str(path): path.read_bytes() for path in self.fixture.root.rglob('*') if path.is_file()}
        result = self.invoke()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        body = fields(result.stdout)
        self.assertEqual(body['result'], 'complete')
        self.assertEqual(body['violations'], 'key_build,key_env_file,key_label_file')
        self.assertEqual(body['retained_checkpoint'], 'invalid')
        self.assertEqual(body['result_record'], 'pass')
        self.assertEqual(result.stderr, b'')
        self.assertNotIn(b'PRIVATE', result.stdout)
        self.assertEqual(self.calls.read_bytes(), b'invoked\n')
        self.assertEqual(before, {str(path): path.read_bytes() for path in self.fixture.root.rglob('*') if path.is_file()})

    def test_authenticated_path_rejects_raw_startup_wrong_nonce_and_exit_mismatch(self):
        for behavior in ('startup', 'bad_auth', 'wrong_exit'):
            self.transport(behavior)
            result = self.invoke()
            self.assertEqual((result.returncode, result.stdout, result.stderr), (1, operation.unavailable('protocol'), b''))
        self.assertEqual(self.calls.read_bytes().splitlines(), [b'invoked'] * 3)

    def test_git_source_binding_and_initial_attempt_fail_before_transport(self):
        good = self.invoke('--validate')
        self.assertEqual((good.returncode, good.stdout), (0, b'compose-diagnostic-validation:v=1 result=ok\n'))
        for env in (dict(self.env, GITHUB_SHA='a' * 40), dict(self.env, GITHUB_RUN_ATTEMPT='2')):
            result = self.invoke(env=env)
            self.assertEqual((result.returncode, result.stdout), (1, operation.unavailable('request')))
        # Working-copy remote-operation changes cannot become the uploaded source.
        self.operation_path.write_text('raise RuntimeError("PRIVATE unreviewed source")\n')
        self.assertEqual(self.invoke().returncode, 0)
        local = self.repo / runner.RUNNER_PATH
        local.write_bytes(local.read_bytes() + b'\n# unreviewed local bytes\n')
        result = self.invoke()
        self.assertEqual((result.returncode, result.stdout), (1, operation.unavailable('request')))
        self.assertEqual(self.calls.read_bytes(), b'invoked\n')

    def test_remote_final_signal_handoff_beats_complete(self):
        injected = '''
if os.environ.get('CLB91_SYNTHETIC_REMOTE') == '1':
    _real_mask = signal.pthread_sigmask
    def _pending(how, signals):
        result = _real_mask(how, signals)
        os.kill(os.getpid(), signal.SIGTERM)
        return result
    signal.pthread_sigmask = _pending
'''
        with self.operation_path.open('a') as stream:
            stream.write(injected)
        self.git('add', 'scripts')
        self.git('commit', '-q', '-m', 'synthetic final cancellation injection')
        self.env['GITHUB_SHA'] = self.git('rev-parse', 'HEAD').decode().strip()
        result = self.invoke()
        self.assertEqual((result.returncode, result.stdout, result.stderr), (1, operation.unavailable('interrupted'), b''))
        self.assertEqual(self.calls.read_bytes(), b'invoked\n')


if __name__ == '__main__':
    unittest.main(verbosity=2)
