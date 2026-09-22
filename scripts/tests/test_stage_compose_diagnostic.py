#!/usr/bin/env python3
"""CLB-91 source-oracle, real FD/lock and synthetic transport regression tests."""
import ast
import contextlib
import errno
import hashlib
import hmac
import importlib.util
import itertools
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


FLOW_REVIEW_CASES = (
    b'services:\n  app:\n    labels: ["\n    env_file: .env\n    image: PRIVATE"]\n',
    b'services:\n  app:\n    env_file: [.env\n',
)


def safe_yaml_oracle(data):
    # Test-only independent parser already required by repository validators.
    # Synthetic bytes only; no custom constructors/classes, aliases or files.
    ruby = '''require "yaml"; require "json"
begin
  value = YAML.safe_load(STDIN.read, permitted_classes: [], permitted_symbols: [], aliases: false)
  puts JSON.generate({valid: true, value: value})
rescue Psych::SyntaxError
  puts JSON.generate({valid: false})
end
'''
    child = subprocess.run(['ruby', '-e', ruby], input=data, capture_output=True, timeout=10)
    assert child.returncode == 0 and child.stderr == b'' and len(child.stdout) <= 65536
    return json.loads(child.stdout)


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


def captured_sources():
    # Test-only complete source snapshot; production obtains this from Git.
    sources = {path: (ROOT / path).read_bytes() for path in (*runner.LOCAL_SOURCES, runner.REMOTE_PATH)}
    assert all(hashlib.sha256(sources[path]).hexdigest() == digest for path, digest in runner.SOURCE_PINS.items())
    return types.MappingProxyType(sources)


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


class EnvFileStructureTest(unittest.TestCase):
    def observe(self, data, bucket, *shapes):
        result = operation.env_file_structure(data)
        self.assertEqual(result, dict(env_file_occurrences=bucket, env_file_shapes=tuple(sorted(shapes))))
        body = operation.complete(operation.scan(data), {key: 'pass' for key in operation.STATIC_FIELDS}, result)
        self.assertEqual(operation.parse_body(body, 0), (body.decode().strip(), 0))
        self.assertNotIn(b'PRIVATE', body)
        return result

    def test_canonical_zero_and_exact_mapping_branch_applicability(self):
        for data in (b'', (ROOT / 'docker-compose.yml').read_bytes(), b'# env_file: .env\n',
                     b'  - env_file: PRIVATE\n', b'  Env_file: PRIVATE\n', b'  "env_file": PRIVATE\n',
                     b'  env_file:PRIVATE\n'):
            with self.subTest(data=data[:50]):
                self.observe(data, 'zero')
        self.observe(b'services:\n  app:\n    env_file: .env\n', 'one',
                     'service/app/scalar/canonical_dotenv')

    def test_fixed_known_services_and_never_disclose_other_service_names(self):
        for name in ('app', 'db', 'caddy', 'PRIVATE_SERVICE'):
            with self.subTest(name=name):
                expected = name if name in ('app', 'db', 'caddy') else 'other'
                self.observe(f'services:\n  {name}:\n    env_file: ./PRIVATE\n'.encode(), 'one',
                             f'service/{expected}/scalar/other_or_unknown')

    def test_environment_collision_and_other_scopes_are_not_reference_claims(self):
        cases = ((b'services:\n  app:\n    environment:\n      env_file: .env\n',
                  'environment/app/scalar/not_applicable'),
                 (b'services:\n  caddy:\n    labels:\n      env_file: .env\n',
                  'other/caddy/scalar/not_applicable'),
                 (b'volumes:\n  PRIVATE:\n    env_file: .env\n', 'other/none/scalar/not_applicable'),
                 (b'env_file: .env\n', 'other/none/scalar/not_applicable'),
                 (b'services:\n  env_file: .env\n', 'other/none/scalar/not_applicable'))
        for data, shape in cases:
            with self.subTest(shape=shape):
                self.observe(data, 'one', shape)

    def test_literal_classes_do_not_resolve_interpolation_or_arbitrary_paths(self):
        for literal in ('.env', './.env', '".env"', "'./.env'", '.env # PRIVATE comment', '"./.env" # PRIVATE'):
            with self.subTest(literal=literal):
                self.observe(f'services:\n  app:\n    env_file: {literal}\n'.encode(), 'one',
                             'service/app/scalar/canonical_dotenv')
        for literal in ('/.env', '../.env', '/opt/clubs-bot-stage/.env', '${PRIVATE}',
                        'PRIVATE.env', '.env/PRIVATE', '.env#PRIVATE', '".\\u0065nv"', '.env PRIVATE'):
            with self.subTest(literal=literal):
                self.observe(f'services:\n  app:\n    env_file: {literal}\n'.encode(), 'one',
                             'service/app/scalar/other_or_unknown')

    def test_sequence_mapping_empty_and_unsupported_object_forms(self):
        base = b'services:\n  app:\n    env_file:'
        cases = ((b' [.env, "./.env"]\n', 'sequence/canonical_dotenv'),
                 (b' # PRIVATE\n      - .env\n      - "./.env" # comment\n', 'sequence/canonical_dotenv'),
                 (b' [.env, PRIVATE]\n', 'sequence/other_or_unknown'),
                 (b' []\n', 'sequence/other_or_unknown'),
                 (b'\n      path: .env\n      required: false\n', 'mapping/other_or_unknown'),
                 (b'\n', 'empty/other_or_unknown'))
        for suffix, tail in cases:
            with self.subTest(suffix=suffix):
                self.observe(base + suffix, 'one', 'service/app/' + tail)
        for suffix in (b' {path: .env}\n', b'\n      - path: .env\n        required: false\n',
                       b'\n    - .env\n', b' [.env # PRIVATE, .env]\n'):
            self.observe(base + suffix, 'one', operation.UNKNOWN_SHAPE)

    def test_multiple_dedup_sorted_and_no_partial_location_when_outline_ambiguous(self):
        data = (b'services:\n  caddy:\n    environment:\n      env_file: PRIVATE\n'
                b'  app:\n    env_file: .env\n  db:\n    env_file: [.env]\n')
        self.observe(data, 'multiple', 'environment/caddy/scalar/not_applicable',
                     'service/app/scalar/canonical_dotenv', 'service/db/sequence/canonical_dotenv')
        self.observe(b'services:\n  PRIVATE_A:\n    env_file: .env\n  PRIVATE_B:\n    env_file: .env\n',
                     'multiple', 'service/other/scalar/canonical_dotenv')
        self.observe(data + b'PRIVATE: |\n  env_file: .env\n', 'multiple', operation.UNKNOWN_SHAPE)
        self.observe(b'services:\n  app:\n    env_file: .env\n    env_file: PRIVATE\n',
                     'multiple', operation.UNKNOWN_SHAPE)

    def test_unsupported_utf8_whitespace_scalar_and_indentation_remain_unresolved(self):
        good = b'services:\n  app:\n    env_file: .env\n'
        bad = (b'\xff\n' + good, b'\xef\xbb\xbf' + good, good.replace(b'    env_file', b'\t env_file'),
               good.replace(b'\n', b'\v'), good.replace(b'\n', '\u2028'.encode()),
               good.replace(b': .env', ':\u00a0.env'.encode()),
               good + b'  other:\n   image: PRIVATE\n    env_file: .env\n',
               good + b'  app:\n    image: PRIVATE\n', good + b'  other: "PRIVATE\n',
               good + b'---\n', good + b'  other: &PRIVATE value\n',
               good + b'  other: !PRIVATE value\n', good + b'  other: *PRIVATE\n')
        for data in bad:
            with self.subTest(data=data[:60]):
                bucket = 'multiple' if data.count(b'env_file:') > 1 else 'one'
                self.observe(data, bucket, operation.UNKNOWN_SHAPE)
        self.observe(good.replace(b'\n', b'\r\n'), 'one', 'service/app/scalar/canonical_dotenv')
        self.observe(good + b'#\tPRIVATE\n', 'one', 'service/app/scalar/canonical_dotenv')

    def test_count_equivalence_bounded_generated_corpus(self):
        rng = random.Random(62529462)
        atoms = [b'env_file: .env', b'env_file:PRIVATE', b'- env_file: PRIVATE', b'"env_file": PRIVATE',
                 b'Env_file: PRIVATE', b'# env_file: PRIVATE', b'\xff', b'app:', b'environment:']
        for _ in range(750):
            lines = [rng.choice((b'', b'  ', b'    ', b'\t')) + rng.choice(atoms)
                     for _ in range(rng.randrange(1, 12))]
            data = b'\n'.join(lines)
            expected = sum(bool(re.fullmatch(rb'env_file:(?:\s+(.*))?', line.strip())) for line in lines)
            observed = operation.env_file_structure(data)
            self.assertEqual(observed['env_file_occurrences'], 'zero' if expected == 0 else 'one' if expected == 1 else 'multiple')
            self.assertEqual('key_env_file' in operation.scan(data)['violations'], expected > 0)
            self.assertEqual(original_accepts(data), not operation.scan(data)['violations'])

    def test_processing_bounds_and_no_truncated_complete(self):
        self.observe(b'#' * 65536, 'zero')
        for data in (b'#' * 65537, b'  x: y\n' * (operation.STRUCTURE_LINES + 1),
                     b'\n'.join(b' ' * i + b'x:' for i in range(operation.STRUCTURE_DEPTH + 1))):
            with self.assertRaisesRegex(operation.Unavailable, 'bounds'):
                operation.env_file_structure(data)
        data = b'services:\n'
        for number, suffix in enumerate((b' .env', b' PRIVATE', b' [.env]', b' [PRIVATE]',
                                        b'\n      path: .env', b'', b' .env', b' .env', b' .env')):
            # Six distinct service/other forms, plus three environment contexts.
            if number < 6:
                data += f'  PRIVATE_{number}:\n    env_file:'.encode() + suffix + b'\n'
            else:
                name = ('app', 'db', 'caddy')[number - 6]
                data += f'  {name}:\n    environment:\n      env_file: PRIVATE\n'.encode()
        with self.assertRaisesRegex(operation.Unavailable, 'bounds'):
            operation.env_file_structure(data)


class FlowOutlineTest(unittest.TestCase):
    observe = EnvFileStructureTest.observe

    def test_reviewer_multiline_flow_scalar_has_no_service_env_file(self):
        data = FLOW_REVIEW_CASES[0]
        parsed = safe_yaml_oracle(data)
        self.assertTrue(parsed['valid'])
        app = parsed['value']['services']['app']
        self.assertEqual(set(app), {'labels'})
        self.assertEqual(len(app['labels']), 1)
        self.assertIsInstance(app['labels'][0], str)
        self.assertNotIn('env_file', app)
        self.assertEqual(operation.scan(data), dict(violations=('key_env_file',),
                                                  mapping_details=(), top_level_details=()))
        self.observe(data, 'one', operation.UNKNOWN_SHAPE)

    def test_reviewer_unclosed_sequence_is_unresolved_not_resolved_sequence(self):
        data = FLOW_REVIEW_CASES[1]
        self.assertFalse(safe_yaml_oracle(data)['valid'])
        self.assertEqual(operation.scan(data)['violations'], ('key_env_file',))
        self.observe(data, 'one', operation.UNKNOWN_SHAPE)
        self.observe(b'env_file: [.env\n', 'one', operation.UNKNOWN_SHAPE)

    def test_multiline_single_double_and_nested_flow_scalar_oracle(self):
        for opening, closing in ((b'["', b'"]'), (b"['", b"']"),
                                 (b'[["', b'"]]'), (b"[['", b"']]"),
                                 (b'["escaped \\"', b'"]'), (b"['doubled ''", b"']")):
            for service in (b'app', b'db'):
                data = (b'services:\n  ' + service + b':\n    labels: ' + opening +
                        b'\n    env_file: .env\n    image: PRIVATE' + closing + b'\n')
                with self.subTest(opening=opening, service=service):
                    parsed = safe_yaml_oracle(data)
                    self.assertTrue(parsed['valid'])
                    self.assertEqual(set(parsed['value']['services'][service.decode()]), {'labels'})
                    self.assertEqual(operation.scan(data)['violations'], ('key_env_file',))
                    self.observe(data, 'one', operation.UNKNOWN_SHAPE)

    def test_ambiguity_before_after_and_multiple_occurrences_discards_every_tuple(self):
        real = b'    env_file: .env\n'
        hidden = b'    labels: ["\n    env_file: PRIVATE\n    image: PRIVATE"]\n'
        for fragment in (real + hidden, hidden + real):
            self.observe(b'services:\n  app:\n' + fragment, 'multiple', operation.UNKNOWN_SHAPE)
        for fragment in (real + b'    labels: [PRIVATE\n', b'    labels: [PRIVATE\n' + real):
            self.observe(b'services:\n  app:\n' + fragment, 'one', operation.UNKNOWN_SHAPE)
        # No collision is still zero lexical matches, never proof of semantic absence.
        self.observe(b'services:\n  app:\n    labels: ["\n', 'zero')

    def test_unclosed_mismatched_nested_delimiters_quotes_escapes_and_comments(self):
        bad = (b'[.env', b'[.env}', b'[{PRIVATE}]', b'[[.env]]', b'[".env]', b"['.env]",
               b'["PRIVATE\\"]', b'["PRIVATE\\q"]', b'["PRIVATE\\u12"]', b'["PRIVATE\\U00110000"]',
               b'["PRIVATE\\ud800"]', b'[".env" PRIVATE]', b'[.env] PRIVATE', b'[.env]]',
               b'[.env # PRIVATE]', b'[.env, # PRIVATE]', b'[, .env]', b'[.env,, .env]',
               b'[PRIVATE: value]', b'[!PRIVATE .env]', b'[*PRIVATE]', b'[&PRIVATE .env]',
               b'"PRIVATE\\', b'"PRIVATE\\q"', b"'PRIVATE", b'"PRIVATE"junk')
        for value in bad:
            with self.subTest(value=value):
                data = b'services:\n  app:\n    env_file: .env\n    labels: ' + value + b'\n'
                self.observe(data, 'one', operation.UNKNOWN_SHAPE)

    def test_supported_quotes_brackets_hashes_and_escapes_preserve_attribution(self):
        values = (b'["PRIVATE[{}]#", ".env"]', b"['PRIVATE]#', 'it''s [literal]']",
                  b'["PRIVATE\\\"[", "PRIVATE\\\\", "PRIVATE\\u005b", "PRIVATE\\x23"]',
                  br'[CMD-SHELL, "echo \"PRIVATE]#\"", "http://fixture.invalid/path"]',
                  b'[PRIVATE#literal, .env] # PRIVATE [" unclosed comment',
                  b'[.env,]', b'[]', b'"PRIVATE[{}]#" # [PRIVATE',
                  b"'it''s PRIVATE[{}]#'", b'PRIVATE [literal', b"PRIVATE's [literal",
                  b'PRIVATE # ["unclosed comment')
        for value in values:
            with self.subTest(value=value):
                data = b'services:\n  app:\n    env_file: .env\n    labels: ' + value + b'\n'
                parsed = safe_yaml_oracle(data)
                self.assertTrue(parsed['valid'])
                self.assertEqual(parsed['value']['services']['app']['env_file'], '.env')
                self.observe(data, 'one', 'service/app/scalar/canonical_dotenv')
        for value, reference in ((b'[.env, "./.env"]', 'canonical_dotenv'),
                                 (b'[".env,PRIVATE"]', 'other_or_unknown'),
                                 (b'[".env#PRIVATE"]', 'other_or_unknown')):
            data = b'services:\n  app:\n    env_file: ' + value + b'\n'
            self.assertTrue(safe_yaml_oracle(data)['valid'])
            self.observe(data, 'one', 'service/app/sequence/' + reference)
        data = b'services:\n  app:\n    environment:\n      env_file: "PRIVATE[\\\"#"\n'
        self.assertTrue(safe_yaml_oracle(data)['valid'])
        self.observe(data, 'one', 'environment/app/scalar/not_applicable')


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
        data = b'services:\n  app:\n    env_file: PRIVATE\n'
        self.body = operation.complete(operation.scan(data), self.statuses, operation.env_file_structure(data))

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
        shapes = tuple(sorted(sorted(self.allowed_shapes() - {operation.UNKNOWN_SHAPE},
                                     key=lambda s: (-len(s), s))[:operation.SHAPE_LIMIT]))
        maximum = operation.complete(lexical, {key: 'not_evaluated' for key in operation.STATIC_FIELDS},
                                     dict(env_file_occurrences='multiple', env_file_shapes=shapes))
        self.assertLessEqual(len(maximum), 2048)
        self.assertLessEqual(len(self.frame(maximum)), 4096)
        self.assertEqual(runner.parse_result(self.frame(maximum), 0, self.nonce, operation)[1], 0)
        self.assertNotIn(b'PRIVATE', self.body)

    @staticmethod
    def allowed_shapes():
        # Independent finite public grammar, including states not produced by
        # today's fixtures. Keep the validator closed when new enums are added.
        shapes = {operation.UNKNOWN_SHAPE}
        for scope, service, form in itertools.product(('service', 'environment', 'other'),
                                                      ('app', 'db', 'caddy', 'other', 'none'),
                                                      ('scalar', 'sequence', 'mapping', 'empty')):
            if service == 'none' and scope != 'other':
                continue
            references = ('other_or_unknown',) if scope == 'service' else ('not_applicable',)
            if scope == 'service' and form in ('scalar', 'sequence'):
                references += ('canonical_dotenv',)
            shapes.update('/'.join((scope, service, form, reference)) for reference in references)
        return shapes

    def test_exhaustive_structure_tuple_grammar_and_authenticated_fields(self):
        allowed = self.allowed_shapes()
        for parts in itertools.product(('service', 'environment', 'other', 'unresolved', 'PRIVATE'),
                                       ('app', 'db', 'caddy', 'other', 'none', 'unknown', 'PRIVATE'),
                                       ('scalar', 'sequence', 'mapping', 'empty', 'unresolved', 'PRIVATE'),
                                       ('canonical_dotenv', 'other_or_unknown', 'not_applicable', 'PRIVATE')):
            shape = '/'.join(parts)
            with self.subTest(shape=shape):
                self.assertEqual(operation.valid_env_file_shape(shape), shape in allowed)
                body = re.sub(rb'env_file_shapes=[^ ]+', b'env_file_shapes=' + shape.encode(), self.body)
                if shape in allowed:
                    self.assertEqual(runner.parse_result(self.frame(body), 0, self.nonce, operation),
                                     (body.decode().strip(), 0))
                else:
                    with self.assertRaises((ValueError, operation.Unavailable)):
                        runner.parse_result(self.frame(body), 0, self.nonce, operation)
        changed = self.frame(self.body).replace(b'env_file_occurrences=one', b'env_file_occurrences=multiple')
        with self.assertRaises((ValueError, operation.Unavailable)):
            runner.parse_result(changed, 0, self.nonce, operation)

    def test_structure_field_cardinality_version_order_and_cross_field_constraints(self):
        shape = b'service/app/scalar/other_or_unknown'
        bad = [self.body.replace(b'v=2', b'v=1'),
               self.body.replace(b'env_file_occurrences=one ', b''),
               self.body.replace(b'env_file_occurrences=one', b'env_file_occurrences=1'),
               self.body.replace(b'env_file_occurrences=one', b'env_file_occurrences=zero'),
               self.body.replace(b'env_file_occurrences=one', b'env_file_occurrences=one env_file_occurrences=one'),
               self.body.replace(b'key_env_file', b'none').replace(b'subset=invalid', b'subset=clear'),
               self.body.replace(shape, b'none'), self.body.replace(shape, shape + b',' + shape),
               self.body.replace(shape, shape + b',environment/app/scalar/not_applicable'),
               self.body.replace(b'violations=key_env_file', b'violations=key_env_file,PRIVATE')]
        many = self.body.replace(b'occurrences=one', b'occurrences=multiple')
        bad += [many.replace(shape, shape + b',' + operation.UNKNOWN_SHAPE.encode()),
                many.replace(shape, ','.join(sorted(self.allowed_shapes() - {operation.UNKNOWN_SHAPE})[:9]).encode()),
                many.replace(shape, b'service/db/scalar/canonical_dotenv,' + shape)]
        ordered = b'env_file_occurrences=one env_file_shapes=' + shape
        bad.append(self.body.replace(ordered, b'env_file_shapes=' + shape + b' env_file_occurrences=one'))
        for body in bad:
            with self.subTest(body=body[:160]), self.assertRaises((ValueError, operation.Unavailable)):
                runner.parse_result(self.frame(body), 0, self.nonce, operation)

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


class AcquisitionTest(unittest.TestCase):
    def failure(self, phase, number, *, cleanup_failure=False):
        fixture = Fixture()
        original_close = os.close
        failed = []
        try:
            with fixture.patches():
                opening, fstating, reading, stating, scanning = os.open, os.fstat, os.read, os.stat, os.scandir
                target = '.env' if phase == 'dotenv_open' else 'docker-compose.override.yml'
                target_fds = set()
                def fail():
                    failed.append(True)
                    raise OSError(number, 'PRIVATE acquisition error')
                def open_file(path, *args, **kwargs):
                    if path == target and phase in ('override_open', 'dotenv_open', 'unproven_loop'):
                        fail()
                    fd = opening(path, *args, **kwargs)
                    if path == target: target_fds.add(fd)
                    return fd
                def fstat_file(fd):
                    if phase == 'fstat' and fd in target_fds: fail()
                    return fstating(fd)
                def read_file(fd, count):
                    if phase == 'read' and fd in target_fds: fail()
                    return reading(fd, count)
                def stat_file(path, *args, **kwargs):
                    if phase == 'stat' and path == target and target_fds: fail()
                    return stating(path, *args, **kwargs)
                def inventory(fd):
                    if phase == 'inventory': fail()
                    if phase == 'inventory_iteration':
                        class BrokenIterator:
                            def __enter__(self): return self
                            def __exit__(self, *unused): pass
                            def __iter__(self): return self
                            def __next__(self): fail()
                        return BrokenIterator()
                    return scanning(fd)
                def close(fd):
                    original_close(fd)
                    if cleanup_failure: raise OSError(errno.EIO, 'PRIVATE cleanup')
                with patch.object(operation.os, 'open', side_effect=open_file), \
                     patch.object(operation.os, 'fstat', side_effect=fstat_file), \
                     patch.object(operation.os, 'read', side_effect=read_file), \
                     patch.object(operation.os, 'stat', side_effect=stat_file), \
                     patch.object(operation.os, 'scandir', side_effect=inventory), \
                     patch.object(operation.os, 'close', side_effect=close):
                    body = operation.diagnose(pwd.getpwuid(os.geteuid()).pw_name, lambda: False)
            self.assertTrue(failed, 'fault was not reached')
            for fd in fixture.allocated:
                with self.assertRaises(OSError): fixture.original_fstat(fd)
            return body
        finally:
            fixture.close()

    def test_unexpected_open_errno_matrix_never_means_absent_or_invalid(self):
        for phase in ('override_open', 'dotenv_open'):
            for number in (errno.EIO, errno.EACCES, errno.EPERM, errno.EMFILE, errno.ENFILE,
                           errno.ENOMEM, errno.ENOTDIR, errno.ELOOP):
                with self.subTest(phase=phase, errno=number):
                    self.assertEqual(self.failure(phase, number), operation.unavailable('io'))

    def test_metadata_read_inventory_errors_and_interruption_close_all_resources(self):
        for phase in ('fstat', 'read', 'stat', 'inventory', 'inventory_iteration'):
            for number in (errno.EIO, errno.EINTR):
                with self.subTest(phase=phase, errno=number):
                    expected = 'interrupted' if number == errno.EINTR else 'io'
                    self.assertEqual(self.failure(phase, number), operation.unavailable(expected))
        # ENOENT after a successful open is not optional absence. The FD was
        # acquired; inability to fstat/read it makes the report unavailable.
        for phase in ('fstat', 'read'):
            self.assertEqual(self.failure(phase, errno.ENOENT), operation.unavailable('io'))
        self.assertEqual(self.failure('read', errno.EIO, cleanup_failure=True), operation.unavailable('cleanup'))

    def test_observed_rejected_symlink_is_revalidated_before_complete(self):
        fixture = Fixture()
        try:
            path = fixture.paths['compose'] / '.env'
            path.unlink()
            path.symlink_to(fixture.paths['compose'] / 'referenced-secret')
            original = operation.ReadOnlyCapture.capture_optional_metadata
            def substitute(context):
                result = original(context)
                self.assertEqual(result, 'invalid')
                path.unlink()
                fixture.write_path(path, b'PRIVATE new metadata target')
                return result
            with patch.object(operation.ReadOnlyCapture, 'capture_optional_metadata', substitute):
                self.assertEqual(fixture.invoke(), operation.unavailable('identity'))
        finally:
            fixture.close()


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
        for path in (runner.REMOTE_PATH, runner.RUNNER_PATH):
            new = (ROOT / path).read_text()
            copied = new[new.index('class CaptureResult:'):new.index('# END EXACT CORRECTED CAPTURE PRIMITIVES')]
            self.assertEqual(copied, original)
        transport = runner.load_transport(captured_sources(), [False])
        self.assertEqual(transport.capture_result.__code__.co_filename, str(ROOT / 'scripts/deploy/corrected-stage-release.py'))
        self.assertEqual(transport.pinned_hosts.__wrapped__.__code__.co_filename, str(ROOT / 'scripts/deploy/corrected-stage-release.py'))

    def test_source_closure_compiles_and_verifies_all_before_project_execution(self):
        sources = captured_sources()
        for path in (*runner.SOURCE_PINS, runner.REMOTE_PATH):
            bad = dict(sources)
            del bad[path]
            with self.subTest(missing=path), patch.object(runner, 'exec', create=True) as execute:
                with self.assertRaises(ValueError):
                    runner.load_transport(bad, [False])
                execute.assert_not_called()
        for path in runner.SOURCE_PINS:
            for value in (b'print("PRIVATE canary")', b'', 'not bytes', b'x' * 65537):
                bad = dict(sources, **{path: value})
                with self.subTest(path=path, kind=type(value)), patch.object(runner, 'exec', create=True) as execute:
                    with self.assertRaises(ValueError):
                        runner.load_transport(bad, [False])
                    execute.assert_not_called()
        with patch.object(runner, 'exec', create=True) as execute:
            with self.assertRaises(InterruptedError):
                runner.load_transport(sources, [True])
            execute.assert_not_called()
        # A compile/readiness failure in the last compiled module still cannot
        # execute an earlier dependency. The production pins stay unchanged.
        real_compile = compile
        def compile_failure(source, filename, *args, **kwargs):
            if isinstance(source, ast.Module):
                raise MemoryError('PRIVATE resource error')
            return real_compile(source, filename, *args, **kwargs)
        with patch.object(runner, 'compile', side_effect=compile_failure, create=True), \
             patch.object(runner, 'exec', create=True) as execute:
            with self.assertRaises(MemoryError):
                runner.load_transport(sources, [False])
            execute.assert_not_called()

    def test_fixed_source_adapter_preserves_other_ast_and_never_reopens_modules(self):
        sources = captured_sources()
        tree = ast.parse(sources[runner.TRANSPORT_PATH])
        calls = [node.args[0].value for node in ast.walk(tree) if isinstance(node, ast.Call)
                 and isinstance(node.func, ast.Name) and node.func.id == 'source_module']
        self.assertEqual(sorted(calls), ['release_authority', 'release_private_root'])
        # Dependencies have no transitive project import or source loader.
        for path in runner.DEPENDENCY_PATHS:
            dependency = ast.parse(sources[path])
            imports = {alias.name.split('.')[0] for node in ast.walk(dependency) if isinstance(node, ast.Import)
                       for alias in node.names}
            imports |= {node.module.split('.')[0] for node in ast.walk(dependency) if isinstance(node, ast.ImportFrom)}
            self.assertLessEqual(imports, sys.stdlib_module_names)
            self.assertFalse(any(isinstance(node, ast.Call) and isinstance(node.func, ast.Name)
                                 and node.func.id in ('exec', 'eval', '__import__', 'source_module')
                                 for node in ast.walk(dependency)))
        tree.body = [node for node in tree.body if not (isinstance(node, ast.FunctionDef) and node.name == 'source_module')]
        observed = []
        real_compile = compile
        def compiling(source, filename, *args, **kwargs):
            if isinstance(source, ast.Module): observed.append(ast.dump(source))
            return real_compile(source, filename, *args, **kwargs)
        with patch.object(runner.os, 'open', side_effect=AssertionError('project source reopened')), \
             patch.object(runner, 'compile', side_effect=compiling, create=True):
            transport = runner.load_transport(sources, [False])
            self.assertTrue(callable(transport.pinned_hosts))
            with self.assertRaises(ValueError): transport.source_module('unapproved')
        self.assertEqual(observed, [ast.dump(tree)])

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
        transport = runner.load_transport(captured_sources(), [False])
        source = (ROOT / runner.REMOTE_PATH).read_bytes()
        body = operation.complete(operation.scan(b''), {key: 'pass' for key in operation.STATIC_FIELDS},
                                  operation.env_file_structure(b''))
        for sig in runner.WATCHED:
            self.addCleanup(signal.signal, sig, signal.getsignal(sig))
        for failure in ('transport', 'protocol', 'cleanup', 'cancel', 'signal_pin', 'signal_close'):
            calls = []
            cleanup_calls = []
            cancelled = [False]
            with tempfile.TemporaryFile() as pin:
                @contextlib.contextmanager
                def pinned(env):
                    try:
                        if failure == 'signal_pin': os.kill(os.getpid(), signal.SIGTERM)
                        yield pin.fileno(), '/PRIVATE_PIN'
                    finally:
                        cleanup_calls.append(True)
                        if failure == 'cleanup':
                            raise OSError('PRIVATE cleanup')
                        if failure == 'cancel':
                            cancelled[0] = True
                        if failure == 'signal_close': os.kill(os.getpid(), signal.SIGTERM)

                def capture(argv, payload, **kwargs):
                    calls.append((argv, kwargs))
                    nonce = payload[:32]
                    tag = hmac.new(nonce, body, hashlib.sha256).hexdigest().encode()
                    output = b'clb91-compose-auth:v=1 tag=' + tag + b' ' + body
                    if failure == 'protocol':
                        output += b'PRIVATE extra\n'
                    return transport.CaptureResult(0, output, 'capture_timeout' if failure == 'transport' else None)

                with patch.object(runner, 'load_transport', return_value=transport), patch.object(runner, 'snapshot', return_value=captured_sources()), \
                     patch.object(transport, 'pinned_hosts', pinned), patch.object(transport, 'capture_result', side_effect=capture):
                    line, code = runner.main(dict(self.env, SSH_KNOWN_HOSTS='PRIVATE', PATH=os.defpath), [], cancelled)
            self.assertEqual(code, 1)
            self.assertIn('result=unavailable', line)
            self.assertNotIn('PRIVATE', line)
            self.assertEqual(cleanup_calls, [True])
            self.assertEqual(len(calls), 0 if failure == 'signal_pin' else 1)
            if calls:
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
raise "inventory: expected 27, actual #{paths.length}" unless paths.length == 27;
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
        self.private_env = dict(PATH=os.environ.get('PATH', os.defpath), HOME=str(self.root),
                                TMPDIR=str(self.root), RUNNER_TEMP=str(self.root), LC_ALL='C')
        self.git_env = dict(self.private_env, GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_SYSTEM=os.devnull,
                            GIT_TERMINAL_PROMPT='0', GIT_AUTHOR_NAME='Disposable Fixture',
                            GIT_AUTHOR_EMAIL='fixture@example.invalid', GIT_COMMITTER_NAME='Disposable Fixture',
                            GIT_COMMITTER_EMAIL='fixture@example.invalid')
        self.git('-c', 'init.templateDir=', 'init', '-q')
        self.git('config', 'core.hooksPath', os.devnull)
        self.git('add', 'scripts')
        self.git('commit', '-q', '-m', 'synthetic production path fixture')
        sha = self.git('rev-parse', 'HEAD').decode().strip()
        subprocess.run(['ssh-keygen', '-q', '-t', 'ed25519', '-N', '', '-f', str(self.root / 'key')],
                       env=self.private_env, cwd=self.root, check=True, capture_output=True, timeout=10)
        public = (self.root / 'key.pub').read_text().split()
        self.env = dict(self.private_env, GITHUB_REPOSITORY='koteev-m/clubs_bot', GITHUB_EVENT_NAME='workflow_dispatch',
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
        script = '''#!/usr/bin/env -S PYTHON -I -S -B
import os, sys, shlex, subprocess
assert sys.flags.isolated and sys.flags.no_site and sys.dont_write_bytecode
with open(CALLS, 'ab') as stream: stream.write(b'invoked\\n')
command = shlex.split(sys.argv[-1])
assert command[-6:-1] == ['python3', '-I', '-S', '-B', '-c']
env = dict(os.environ, CLB91_SYNTHETIC_REMOTE='1', HOME=HOME_LITERAL)
argv = [PYTHON_LITERAL, '-I', '-S', '-B', '-c', command[-1]]
BEHAVIOR
'''.replace('PYTHON_LITERAL', repr(sys.executable)).replace('PYTHON', sys.executable).replace('CALLS', repr(str(self.calls))).replace('HOME_LITERAL', repr(str(self.root)))
        behaviors = {
            'normal': 'os.execve(argv[0], argv, env)',
            'startup': "os.write(1, b'PRIVATE startup spoof\\n'); os.execve(argv[0], argv, env)",
            'bad_auth': "data=sys.stdin.buffer.read(); data=b'x'*32+data[32:]; result=subprocess.run(argv,input=data,env=env,stdout=subprocess.PIPE); os.write(1,result.stdout); sys.exit(result.returncode)",
            'wrong_exit': "result=subprocess.run(argv,input=sys.stdin.buffer.read(),env=env,stdout=subprocess.PIPE); os.write(1,result.stdout); sys.exit(1)",
        }
        Fixture.write_path(self.bin / 'ssh', script.replace('BEHAVIOR', behaviors[behavior]).encode(), 0o700)

    def invoke(self, *args, env=None):
        return subprocess.run([sys.executable, '-I', '-S', '-B', str(self.repo / runner.RUNNER_PATH), *args],
                              env=env or self.env, cwd=self.root, capture_output=True, timeout=30)

    def commit_injection(self, text, *, local=False):
        if local:
            path = self.repo / runner.RUNNER_PATH
            source = (ROOT / runner.RUNNER_PATH).read_text()
            entry = "if __name__ == '__main__':\n    os.umask"
            self.assertEqual(source.count(entry), 1)
            path.write_text(source.replace(entry, text + '\n' + entry))
        else:
            self.operation_path.write_text((ROOT / runner.REMOTE_PATH).read_text() + self.injection + text)
        self.git('add', 'scripts')
        self.git('commit', '-q', '-m', 'disposable adversarial source fixture')
        self.env['GITHUB_SHA'] = self.git('rev-parse', 'HEAD').decode().strip()

    def test_each_source_tamper_executes_no_canary_and_submits_no_ssh(self):
        marker = self.root / 'project-canary'
        self.env['SYNTHETIC_CREDENTIAL'] = 'PRIVATE_SYNTHETIC_CREDENTIAL'
        canary = ('\nfrom pathlib import Path\nimport os\nPath(' + repr(str(marker)) +
                  ').write_text(os.environ["SYNTHETIC_CREDENTIAL"])\nprint("PRIVATE_EXECUTED_CANARY")\n').encode()
        for path in runner.SOURCE_PINS:
            original = (self.repo / path).read_bytes()
            for args in ((), ('--validate',)):
                with self.subTest(path=path, args=args):
                    (self.repo / path).write_bytes(original + canary)
                    result = self.invoke(*args)
                    self.assertEqual((result.returncode, result.stdout, result.stderr),
                                     (1, operation.unavailable('request'), b''))
                    self.assertFalse(marker.exists(), 'project code ran before the whole closure was verified')
                    self.assertFalse(self.calls.exists())
            (self.repo / path).write_bytes(original)

    def test_snapshot_errors_and_cancellation_precede_every_project_exec(self):
        marker = self.root / 'project-executed'
        # Instrument the trusted, fixture-committed bootstrap itself, not a
        # project module: every exec by the bootstrap would create this marker.
        observer = '''
import builtins as _builtins
def exec(*args, **kwargs):
    Path(MARKER).write_text('PRIVATE project execution')
    return _builtins.exec(*args, **kwargs)
'''.replace('MARKER', repr(str(marker)))
        injections = {
            'open_io': '''
_original_open = os.open
def _failed_open(path, *args, **kwargs):
    if str(path).endswith('release_authority.py'): raise OSError(5, 'PRIVATE acquisition')
    return _original_open(path, *args, **kwargs)
os.open = _failed_open
''',
            'git_incomplete': '''
_original_capture = capture_result
def capture_result(argv, *args, **kwargs):
    result = _original_capture(argv, *args, **kwargs)
    if 'ls-tree' in argv and argv[-1].endswith('release_authority.py'): result.output = b''
    return result
''',
            'git_error': '''
_original_capture = capture_result
def capture_result(argv, *args, **kwargs):
    result = _original_capture(argv, *args, **kwargs)
    if 'cat-file' in argv: result.failure = 'capture_output_limit'
    return result
''',
            'cancel_after_capture': '''
_original_capture = capture_result
def capture_result(argv, *args, **kwargs):
    result = _original_capture(argv, *args, **kwargs)
    if 'cat-file' in argv: os.kill(os.getpid(), signal.SIGTERM)
    return result
''',
            'cancel_during_capture': '''
_original_spawn = subprocess.Popen
def _spawn(*args, **kwargs):
    child = _original_spawn(*args, **kwargs)
    os.kill(os.getpid(), signal.SIGTERM)
    return child
subprocess.Popen = _spawn
''',
        }
        for kind, injection in injections.items():
            with self.subTest(kind=kind):
                self.commit_injection(observer + injection, local=True)
                result = self.invoke()
                reason = 'interrupted' if kind.startswith('cancel') else 'request'
                self.assertEqual((result.returncode, result.stdout, result.stderr), (1, operation.unavailable(reason), b''))
                self.assertFalse(marker.exists())
                self.assertFalse(self.calls.exists())

    def test_post_snapshot_path_replacement_executes_only_captured_bytes(self):
        self.assert_post_snapshot_path_replacement()

    def test_post_snapshot_path_replacement_with_linux_shebang_argv(self):
        self.assert_post_snapshot_path_replacement(linux_shebang=True)

    def assert_post_snapshot_path_replacement(self, *, linux_shebang=False):
        marker = self.root / 'race-canary'
        canary = 'from pathlib import Path\nPath(' + repr(str(marker)) + ').write_text("PRIVATE")\nprint("PRIVATE_RACE_CANARY")\n'
        injection = '''
_original_snapshot = snapshot
def snapshot(env, cancelled):
    sources = _original_snapshot(env, cancelled)
    for path in SOURCE_PINS:
        replacement = ROOT / (path + '.replacement')
        replacement.write_text(CANARY)
        os.replace(replacement, ROOT / path)
    return sources
'''.replace('CANARY', repr(canary))
        if linux_shebang:
            # Existing repository parity pattern: Linux execve passes a script
            # interpreter ONE optional shebang argument. Exercise that exact
            # argv on every host, without claiming a native Linux kernel run.
            # Only the disposable ssh wrapper's exec boundary is substituted;
            # payload, pass_fds, bootstrap, HMAC and consumer remain real.
            injection += '''
_original_spawn = subprocess.Popen
def _linux_script_spawn(argv, *args, **kwargs):
    if argv[0] == 'ssh':
        wrapper = SSH_WRAPPER
        interpreter, optional = Path(wrapper).read_text().splitlines()[0][2:].split(' ', 1)
        argv = [interpreter, optional, wrapper, *argv[1:]]
    return _original_spawn(argv, *args, **kwargs)
subprocess.Popen = _linux_script_spawn
'''.replace('SSH_WRAPPER', repr(str(self.bin / 'ssh')))
        self.commit_injection(injection, local=True)
        result = self.invoke()
        self.assertEqual((result.returncode, result.stderr), (0, b''), result.stdout)
        self.assertEqual(fields(result.stdout)['result'], 'complete')
        self.assertEqual(result.stdout, operation.complete(operation.scan(b''),
                         {field: 'pass' for field in operation.STATIC_FIELDS}, operation.env_file_structure(b'')))
        self.assertNotIn(b'PRIVATE', result.stdout)
        self.assertFalse(marker.exists())
        self.assertEqual(self.calls.read_bytes(), b'invoked\n')
        # Subsequent validation observes drift and cannot reuse that snapshot.
        result = self.invoke()
        self.assertEqual((result.returncode, result.stdout, result.stderr), (1, operation.unavailable('request'), b''))
        self.assertEqual(self.calls.read_bytes(), b'invoked\n')
        self.assertFalse(marker.exists())

    def test_acquisition_failures_through_bootstrap_consumer_and_exit(self):
        # Every fault is injected only in the disposable remote source. The
        # production bootstrap, transport capture, HMAC and parser execute.
        faults = {
            'override_open': "_old=os.open\ndef _fail(path,*a,**k):\n    if path=='docker-compose.override.yml': raise OSError(5,'PRIVATE EIO')\n    return _old(path,*a,**k)\nos.open=_fail",
            'dotenv_open': "_old=os.open\ndef _fail(path,*a,**k):\n    if path=='.env': raise OSError(5,'PRIVATE EIO')\n    return _old(path,*a,**k)\nos.open=_fail",
            'fstat_after_open': "_old=os.fstat\ndef _fail(fd):\n    value=_old(fd)\n    if value.st_ino==os.stat(_root+'/opt/clubs-bot-stage/.env').st_ino: raise OSError(5,'PRIVATE EIO')\n    return value\nos.fstat=_fail",
            'read': "_old=os.read\ndef _fail(fd,n):\n    if os.fstat(fd).st_ino==os.stat(_root+'/opt/clubs-bot-stage/docker-compose.override.yml').st_ino: raise OSError(5,'PRIVATE EIO')\n    return _old(fd,n)\nos.read=_fail",
            'inventory': "def _fail(*a,**k): raise OSError(5,'PRIVATE EIO')\nos.scandir=_fail",
            'after_static_results': "_old=StaticRecords.evaluate\ndef _fail(self,field):\n    if field=='result_record': raise OSError(5,'PRIVATE EIO')\n    return _old(self,field)\nStaticRecords.evaluate=_fail",
            'interrupted_read': "def _fail(*a,**k): raise InterruptedError('PRIVATE interrupted')\nos.read=_fail",
            'cleanup': "_old=os.close\ndef _fail(fd):\n    _old(fd)\n    raise OSError(5,'PRIVATE cleanup')\nos.close=_fail",
        }
        for kind, code in faults.items():
            with self.subTest(kind=kind):
                self.commit_injection("\nif os.environ.get('CLB91_SYNTHETIC_REMOTE') == '1':\n" +
                                      '\n'.join('    ' + line for line in code.splitlines()) + '\n')
                result = self.invoke()
                reason = 'cleanup' if kind == 'cleanup' else 'interrupted' if kind == 'interrupted_read' else 'io'
                self.assertEqual((result.returncode, result.stdout, result.stderr), (1, operation.unavailable(reason), b''))
        self.assertEqual(self.calls.read_bytes().splitlines(), [b'invoked'] * len(faults))

    def test_expected_missing_and_invalid_metadata_remain_complete_through_consumer(self):
        (self.fixture.paths['compose'] / '.env').unlink()
        (self.fixture.paths['compose'] / 'docker-compose.override.yml').unlink()
        (self.fixture.paths['state'] / 'owner').chmod(0o644)
        result = self.invoke()
        self.assertEqual((result.returncode, result.stderr), (0, b''), result.stdout)
        body = fields(result.stdout)
        self.assertEqual(body['result'], 'complete')
        self.assertEqual(body['dotenv_metadata'], 'absent')
        self.assertEqual(body['static_inputs'], 'invalid')
        for key in ('managed_override', 'prior_override', 'retained_identity'):
            self.assertEqual(body[key], 'not_evaluated')
        self.assertEqual(body['migration_records'], 'pass')

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

    def test_structural_evidence_through_real_bootstrap_with_no_extra_reads_or_writes(self):
        # Only the base bytes vary. Every invocation uses the unchanged single
        # capture/locks/recheck/cleanup path and authenticated whole-body consumer.
        cases = (
            (b'services:\n  app:\n    env_file: .env\n', 'one', 'service/app/scalar/canonical_dotenv'),
            (b'services:\n  app:\n    env_file:\n      - ./PRIVATE\n', 'one', 'service/app/sequence/other_or_unknown'),
            (b'services:\n  app:\n    environment:\n      env_file: PRIVATE\n', 'one', 'environment/app/scalar/not_applicable'),
            (b'services:\n  app:\n    env_file: .env\n  db:\n    env_file: PRIVATE\n', 'multiple',
             'service/app/scalar/canonical_dotenv,service/db/scalar/other_or_unknown'),
            (b'\xff\n  env_file: PRIVATE\n', 'one', operation.UNKNOWN_SHAPE),
            ((ROOT / 'docker-compose.yml').read_bytes(), 'zero', 'none'))
        # Fail if any new code reads .env or a referenced file; also prohibit
        # mutation syscalls, allowing only the existing read-only os.open path.
        self.commit_injection('''
if os.environ.get('CLB91_SYNTHETIC_REMOTE') == '1':
    _read = os.read
    _private_inodes = {os.stat(_root + '/opt/clubs-bot-stage/' + p).st_ino
                       for p in ('.env', 'referenced-secret')}
    def _checked_read(fd, count):
        assert os.fstat(fd).st_ino not in _private_inodes
        return _read(fd, count)
    os.read = _checked_read
    def _mutation(*a, **k): raise AssertionError('PRIVATE mutation')
    for _name in ('chmod', 'fchmod', 'chown', 'fchown', 'rename', 'replace', 'unlink', 'fsync', 'mkdir'):
        setattr(os, _name, _mutation)
''')
        for data, bucket, shapes in cases:
            with self.subTest(bucket=bucket, shapes=shapes):
                Fixture.write_path(self.fixture.paths['base'], data, 0o644)
                before = {str(p): (p.read_bytes(), p.stat().st_mode) for p in self.fixture.root.rglob('*') if p.is_file()}
                result = self.invoke()
                self.assertEqual((result.returncode, result.stderr), (0, b''), result.stdout)
                body = fields(result.stdout)
                self.assertEqual((body['env_file_occurrences'], body['env_file_shapes']), (bucket, shapes))
                self.assertTrue(all(body[key] == 'pass' for key in operation.STATIC_FIELDS))
                self.assertNotIn(b'PRIVATE', result.stdout)
                self.assertEqual(before, {str(p): (p.read_bytes(), p.stat().st_mode)
                                         for p in self.fixture.root.rglob('*') if p.is_file()})
        self.assertEqual(self.calls.read_bytes().splitlines(), [b'invoked'] * len(cases))

    def test_structural_bound_failure_never_publishes_partial_complete(self):
        # Stay within the existing file limit while exceeding the outline bound.
        Fixture.write_path(self.fixture.paths['base'], b'x:\n' * (operation.STRUCTURE_LINES + 1), 0o644)
        result = self.invoke()
        self.assertEqual((result.returncode, result.stdout, result.stderr), (1, operation.unavailable('bounds'), b''))
        self.assertEqual(self.calls.read_bytes(), b'invoked\n')

    def test_flow_ambiguity_authenticated_result_preserves_lexical_evidence(self):
        cases = (*FLOW_REVIEW_CASES,
                 b'services:\n  app:\n    env_file: .env\n    labels: ["PRIVATE\n',
                 b'services:\n  app:\n    labels: [\'\n    env_file: PRIVATE\n    image: PRIVATE\']\n')
        for data in cases:
            with self.subTest(data=data[:55]):
                Fixture.write_path(self.fixture.paths['base'], data, 0o644)
                before = {str(p): (p.read_bytes(), p.stat().st_mode) for p in self.fixture.root.rglob('*') if p.is_file()}
                result = self.invoke()
                self.assertEqual((result.returncode, result.stderr), (0, b''), result.stdout)
                body = fields(result.stdout)
                self.assertEqual((body['result'], body['subset'], body['violations']),
                                 ('complete', 'invalid', 'key_env_file'))
                self.assertEqual((body['env_file_occurrences'], body['env_file_shapes']), ('one', operation.UNKNOWN_SHAPE))
                self.assertTrue(all(body[key] == 'pass' for key in operation.STATIC_FIELDS))
                self.assertEqual(result.stdout, operation.complete(operation.scan(data),
                                 {key: 'pass' for key in operation.STATIC_FIELDS}, operation.env_file_structure(data)))
                self.assertNotIn(b'PRIVATE', result.stdout)
                self.assertEqual(before, {str(p): (p.read_bytes(), p.stat().st_mode)
                                         for p in self.fixture.root.rglob('*') if p.is_file()})
        self.assertEqual(self.calls.read_bytes().splitlines(), [b'invoked'] * len(cases))

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
