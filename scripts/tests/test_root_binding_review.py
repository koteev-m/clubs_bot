"""Offline authority regressions; no network, real keys, stage or crypto claims.

The API transcript and ciphertext vector below are deliberately synthetic. They
exercise metadata/source comparisons and private publication; backend OpenPGP
tests belong to the separate crypto suite. Binding comparisons use the real
corrected executor, never a fixture validator.
"""
import argparse
import ast
import base64
import contextlib
import copy
import datetime
import hashlib
import io
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import threading
import types
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
REFERENCE_ROOT = ROOT
if __name__ == '__main__':
    parser = argparse.ArgumentParser(add_help=False)
    parser.add_argument('--reference-root', type=Path, default=ROOT)
    options, remaining = parser.parse_known_args()
    REFERENCE_ROOT = options.reference_root.resolve()
    sys.argv = [sys.argv[0], *remaining]


def source_module(path):
    """Execute selected source bytes directly; no sys.path or cached imports."""
    module = types.ModuleType('root_review_fixture_' + path.stem)
    module.__file__ = str(path)
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(descriptor, 'rb') as source:
        raw = source.read()
    exec(compile(raw, str(path), 'exec'), module.__dict__)
    return module


review = source_module(ROOT / 'scripts/deploy/root_binding_review.py')
executor = source_module(REFERENCE_ROOT / 'scripts/deploy/corrected-stage-release.py')
NOW = 1800000000
CANARY = 'CLB91_SYNTHETIC_PRIVATE_CANARY'
FINGERPRINT = 'B' * 40
SOURCE = 'c' * 40


def iso(value):
    return datetime.datetime.fromtimestamp(value, datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')


def archive(entries):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, 'w', compression=zipfile.ZIP_STORED) as zipped:
        for name, data in entries:
            zipped.writestr(name, data)
    return stream.getvalue()


class AuthenticatedApiFixture:
    """Frozen GET responses, standing in for the caller's authenticated API.

    The verifier itself runs normally. Source records and expected source bytes
    are independent copies so a forged response cannot rewrite the expectation.
    """
    def __init__(self):
        self.expected = dict(repository=review.REPOSITORY, workflow=review.WORKFLOW,
            run_id='1001', attempt='1', source_revision=SOURCE)
        self.source_bytes = {name: ('# independent approved fixture: ' + name + '\n').encode()
                             for name in review.PRODUCER_PATHS}
        # This is only a framing vector, not an assertion of OpenPGP validity.
        self.ciphertext = b'\x84\xb4' + bytes(range(256))
        self.zip = archive([('binding.pgp', self.ciphertext)])
        self.routes = {
            'actions/runs/1001/attempts/1': dict(id=1001, run_attempt=1, run_number=700,
                event='workflow_dispatch', head_branch='main', head_sha=SOURCE,
                path=review.WORKFLOW, status='completed', conclusion='success',
                repository=dict(full_name=review.REPOSITORY),
                head_repository=dict(full_name=review.REPOSITORY), workflow_id=900),
            'actions/workflows/corrected-stage-release.yml': dict(id=900, path=review.WORKFLOW),
            'actions/runs/1001/attempts/1/jobs?per_page=100': dict(total_count=1, jobs=[dict(
                name='deployment-principal-corrected-stage', run_id=1001, conclusion='success',
                steps=[dict(name=name, conclusion='success', started_at=iso(NOW-20), completed_at=iso(NOW-1)) for name in (
                    'Execute bounded corrected helper', 'Publish encrypted binding evidence')])]),
            'actions/artifacts/3001': dict(id=3001, name='clb91-binding-1001-1', expired=False,
                size_in_bytes=len(self.zip), digest='sha256:' + hashlib.sha256(self.zip).hexdigest(),
                created_at=iso(NOW - 10), expires_at=iso(NOW + 3600),
                workflow_run=dict(id=1001, head_sha=SOURCE, head_branch='main')),
            'actions/artifacts/3001/zip': self.zip,
        }
        self.routes.update({'contents/' + path + '?ref=' + SOURCE:
            dict(encoding='base64', content=base64.b64encode(data).decode())
            for path, data in self.source_bytes.items()})
        self.calls = []

    def __call__(self, route):
        self.calls.append(route)
        if route not in self.routes:
            raise AssertionError('unexpected offline API route')
        return copy.deepcopy(self.routes[route])

    def verify(self, **kwargs):
        return review.verify_artifact(self, kwargs.get('expected', self.expected), '3001',
            kwargs.get('source_bytes', self.source_bytes), now=kwargs.get('now', NOW))

    def replace_archive(self, data):
        self.routes['actions/artifacts/3001/zip'] = data
        self.routes['actions/artifacts/3001'].update(size_in_bytes=len(data),
            digest='sha256:' + hashlib.sha256(data).hexdigest())


def complete_binding():
    implementation = dict(revision=SOURCE, blob=executor.IMPLEMENTATION_BLOB,
        sha256=executor.IMPLEMENTATION_SHA256, size=executor.IMPLEMENTATION_SIZE,
        path=executor.HELPER_PATH, mode='100644', type='blob')
    incident = dict(executor.INCIDENT)
    binding = dict(version=1, incident=dict(owner=incident['RELEASE_OWNER'], environment='stage',
        revision=incident['EXPECTED_REVISION'], image=incident['IMAGE_DIGEST']),
        implementation=copy.deepcopy(implementation), principal=dict(name='deployment-fixture', uid=1234),
        compose=dict(path=executor.COMPOSE_PATH, project=CANARY, service='app'),
        backing='mount-v2:' + hashlib.sha256(CANARY.encode()).hexdigest(),
        objects={name: [1, index + 2] for index, name in enumerate((
            '/', 'parent', 'root', 'state', 'results', 'ledger', 'application_lock', 'operation_lock'))},
        configuration={name: hashlib.sha256((CANARY + name).encode()).hexdigest()
            for name in ('main', 'override', 'release', 'dotenv', 'resolved')})
    return binding, implementation, incident


def reviewed_fixture():
    api = AuthenticatedApiFixture()
    ciphertext, receipt = api.verify()
    binding, implementation, incident = complete_binding()
    provenance = {name: receipt[name] for name in (
        'repository', 'workflow', 'run_id', 'run_number', 'attempt', 'source_revision')}
    value = review.envelope(binding, provenance, FINGERPRINT, now=NOW - 10)
    review.verify_envelope(value, receipt, FINGERPRINT, executor.validate_binding,
        implementation, incident, executor.COMPOSE_PATH, now=NOW)
    return value, receipt, implementation, incident


def approved_fixture():
    value, receipt, implementation, incident = reviewed_fixture()
    approved = review.make_approval(value, receipt, '701', 'koteev-m', True, now=NOW)
    env = dict(CLB91_REVIEWED_ROOT_APPROVAL=review.encoded(approved).decode(),
        GITHUB_RUN_NUMBER='701', GITHUB_RUN_ATTEMPT='1', GITHUB_SHA=SOURCE,
        SSH_USER='deployment-fixture', GITHUB_REPOSITORY=review.REPOSITORY,
        GITHUB_EVENT_NAME='workflow_dispatch', GITHUB_REF='refs/heads/main',
        GITHUB_JOB='execute', GITHUB_RUN_ID='1002',
        GITHUB_WORKFLOW_REF=review.REPOSITORY+'/'+review.WORKFLOW+'@refs/heads/main')
    return approved, env, implementation, incident


class Authority(unittest.TestCase):
    def refused(self, operation, reason=None):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            with self.assertRaises((review.Refused, executor.Rejected)) as caught:
                operation()
        self.assertEqual(out.getvalue() + err.getvalue(), '')
        self.assertNotIn(CANARY, str(caught.exception))
        if reason is not None:
            self.assertEqual(str(caught.exception), reason)

    def test_decode_rejects_ambiguous_unbounded_and_non_json_input(self):
        for raw in (b'', b' ' * (review.LIMIT + 1), b'{"format":2,"format":1}',
                    b'{"value":NaN}', b'{"value":Infinity}', b'\xff', b'{', None):
            with self.subTest(kind=type(raw).__name__):
                self.refused(lambda: review.decode(raw))
        self.assertEqual(review.decode('{"format":2}'), {'format': 2})

    def test_producer_requires_exact_dispatch_identity(self):
        env = dict(GITHUB_REPOSITORY=review.REPOSITORY, GITHUB_EVENT_NAME='workflow_dispatch',
            GITHUB_REF='refs/heads/main', GITHUB_JOB='execute',
            GITHUB_WORKFLOW_REF=review.REPOSITORY + '/' + review.WORKFLOW + '@refs/heads/main',
            GITHUB_RUN_ID='1001', GITHUB_RUN_NUMBER='700', GITHUB_RUN_ATTEMPT='1', GITHUB_SHA=SOURCE)
        produced = review.producer(env)
        self.assertEqual(produced['source_revision'], SOURCE)
        for name, value in (('GITHUB_REPOSITORY', 'other/repository'), ('GITHUB_EVENT_NAME', 'push'),
            ('GITHUB_REF', 'refs/heads/other'), ('GITHUB_JOB', 'validate'),
            ('GITHUB_WORKFLOW_REF', review.REPOSITORY + '/other.yml@refs/heads/main'),
            ('GITHUB_RUN_ID', '01'), ('GITHUB_RUN_NUMBER', '0'), ('GITHUB_RUN_ATTEMPT', '01'),
            ('GITHUB_SHA', 'd' * 39)):
            self.refused(lambda: review.producer(env | {name: value}), 'PRODUCER')

    def test_authenticated_artifact_checks_source_and_publishes_only_ciphertext_identity(self):
        api = AuthenticatedApiFixture()
        data, receipt = api.verify()
        self.assertEqual(data, api.ciphertext)
        self.assertEqual(receipt['run_id'], '1001')
        self.assertEqual(receipt['attempt'], '1')
        self.assertEqual(receipt['source_revision'], SOURCE)
        self.assertEqual(receipt['ciphertext_sha256'], hashlib.sha256(data).hexdigest())
        self.assertEqual(set(receipt), {'repository','workflow','run_id','attempt','source_revision',
            'artifact_id','ciphertext_sha256','run_number','created_at','expires_at'})
        self.assertNotIn(CANARY.encode(), review.encoded(receipt) + data)
        self.assertEqual({path for path in api.calls if path.startswith('contents/')}, {
            'contents/' + path + '?ref=' + SOURCE for path in review.PRODUCER_PATHS})

    def test_run_workflow_job_attempt_and_step_substitution_refuse(self):
        run_route = 'actions/runs/1001/attempts/1'
        for field, value in (('id', 1002), ('run_attempt', 2), ('event', 'push'),
            ('head_branch', 'other'), ('head_sha', 'd' * 40), ('path', 'other.yml'),
            ('status', 'in_progress'), ('conclusion', 'failure'),
            ('repository', {'full_name':'other/repository'}),
            ('head_repository', {'full_name':'other/fork'})):
            api = AuthenticatedApiFixture(); api.routes[run_route][field] = value
            self.refused(api.verify, 'PRODUCER')
            self.assertNotIn('actions/artifacts/3001/zip', api.calls)
        for variant in ('workflow_id', 'pagination', 'duplicate_job', 'job_run', 'job_failed', 'step_missing', 'step_failed'):
            api = AuthenticatedApiFixture()
            jobs = api.routes['actions/runs/1001/attempts/1/jobs?per_page=100']
            if variant == 'workflow_id': api.routes['actions/workflows/corrected-stage-release.yml']['id'] = 901
            elif variant == 'pagination': jobs['total_count'] = 2
            elif variant == 'duplicate_job': jobs['jobs'].append(copy.deepcopy(jobs['jobs'][0])); jobs['total_count'] = 2
            elif variant == 'job_run': jobs['jobs'][0]['run_id'] = 1002
            elif variant == 'job_failed': jobs['jobs'][0]['conclusion'] = 'cancelled'
            elif variant == 'step_missing': jobs['jobs'][0]['steps'].pop()
            else: jobs['jobs'][0]['steps'][1]['conclusion'] = 'failure'
            self.refused(api.verify, 'PRODUCER')

    def test_immutable_source_inventory_missing_extra_and_substitution_refuse(self):
        api = AuthenticatedApiFixture()
        source = dict(api.source_bytes); source.pop(review.PRODUCER_PATHS[0])
        self.refused(lambda: api.verify(source_bytes=source), 'PRODUCER')
        api = AuthenticatedApiFixture()
        self.refused(lambda: api.verify(source_bytes=api.source_bytes | {'extra.py': b'extra'}), 'PRODUCER')
        for path in review.PRODUCER_PATHS:
            api = AuthenticatedApiFixture()
            api.routes['contents/' + path + '?ref=' + SOURCE]['content'] = base64.b64encode(CANARY.encode()).decode()
            self.refused(api.verify, 'PRODUCER')
        api = AuthenticatedApiFixture()
        api.routes['contents/' + review.PRODUCER_PATHS[0] + '?ref=' + SOURCE]['encoding'] = 'none'
        self.refused(api.verify, 'PRODUCER')

    def test_artifact_identity_digest_expiry_and_archive_shape_refuse(self):
        for field, value in (('id', 3002), ('name', 'clb91-binding-1001-2'), ('expired', True),
            ('workflow_run', dict(id=1002, head_sha=SOURCE, head_branch='main')),
            ('size_in_bytes', 0), ('size_in_bytes', True), ('digest', 'sha256:' + '0' * 64)):
            api = AuthenticatedApiFixture(); api.routes['actions/artifacts/3001'][field] = value
            self.refused(api.verify, 'ARTIFACT')
        for field, value in (('created_at', iso(NOW - review.TTL - 1)),
                            ('created_at', iso(NOW + 301)), ('expires_at', iso(NOW))):
            api = AuthenticatedApiFixture(); api.routes['actions/artifacts/3001'][field] = value
            if field == 'created_at':
                created = review.timestamp(value)
                upload = api.routes['actions/runs/1001/attempts/1/jobs?per_page=100']['jobs'][0]['steps'][1]
                upload.update(started_at=iso(created-1), completed_at=iso(created+1))
            self.refused(api.verify, 'EXPIRED')
        api = AuthenticatedApiFixture()
        api.routes['actions/artifacts/3001']['created_at'] = iso(NOW-21)
        self.refused(api.verify, 'PRODUCER')
        for data in (b'not a zip', archive([('other.pgp', b'x' * 65)]),
            archive([('../binding.pgp', b'x' * 65)]), archive([('binding.pgp', b'x' * 64)]),
            archive([('binding.pgp', b'x' * 65), ('approval.json', CANARY.encode())])):
            api = AuthenticatedApiFixture(); api.replace_archive(data)
            self.refused(api.verify, 'ARTIFACT')

    def test_complete_format2_envelope_uses_original_binding_validator(self):
        value, receipt, implementation, incident = reviewed_fixture()
        self.assertIn(CANARY.encode(), review.encoded(value))
        self.assertEqual(review.verify_envelope(value, receipt, FINGERPRINT, executor.validate_binding,
            implementation, incident, executor.COMPOSE_PATH, now=NOW), value)
        self.assertEqual(set(value['binding']), {'version','incident','implementation','principal',
            'compose','backing','objects','configuration'})

    def test_partial_or_tampered_binding_never_becomes_reviewed(self):
        for field in ('implementation', 'principal', 'compose', 'backing', 'objects', 'configuration', 'incident'):
            value, receipt, implementation, incident = reviewed_fixture()
            del value['binding'][field]
            self.refused(lambda: review.verify_envelope(value, receipt, FINGERPRINT,
                executor.validate_binding, implementation, incident, executor.COMPOSE_PATH, now=NOW))
        for field, name, replacement in (
            ('incident','owner','other'), ('incident','revision','d'*40),
            ('implementation','sha256','0'*64), ('principal','uid',0), ('principal','uid',True),
            ('principal','name','root'), ('compose','path','/other/private'), ('compose','service','db'),
            ('configuration','dotenv','bad'), ('objects','application_lock',[1,True])):
            value, receipt, implementation, incident = reviewed_fixture()
            value['binding'][field][name] = replacement
            self.refused(lambda: review.verify_envelope(value, receipt, FINGERPRINT,
                executor.validate_binding, implementation, incident, executor.COMPOSE_PATH, now=NOW))

    def test_envelope_receiver_producer_format_and_time_mismatch_refuse(self):
        for field, replacement in (('format',1), ('format',True), ('receiver','A'*40),
            ('issued_at',NOW+301), ('expires_at',NOW), ('expires_at',NOW+review.TTL+1)):
            value, receipt, implementation, incident = reviewed_fixture(); value[field] = replacement
            self.refused(lambda: review.verify_envelope(value, receipt, FINGERPRINT,
                executor.validate_binding, implementation, incident, executor.COMPOSE_PATH, now=NOW))
        for field, replacement in (('attempt','2'), ('source_revision','d'*40), ('run_id','1002'),
                                   ('repository','other/repository'), ('run_number','699')):
            value, receipt, implementation, incident = reviewed_fixture(); value['producer'][field] = replacement
            self.refused(lambda: review.verify_envelope(value, receipt, FINGERPRINT,
                executor.validate_binding, implementation, incident, executor.COMPOSE_PATH, now=NOW), 'PRODUCER')

    def test_secret_required_no_legacy_or_variable_alias_fallback(self):
        approved, env, implementation, incident = approved_fixture()
        invoke = lambda current: review.approval(current, executor.validate_binding,
            implementation, incident, executor.COMPOSE_PATH, now=NOW)
        self.assertEqual(invoke(env), approved)
        for legacy in ('AUTHORIZATION', 'CLB82_AUTHORIZED_ROOT_BINDING'):
            self.refused(lambda: invoke(env | {legacy: CANARY}), 'LEGACY_AUTHORITY_FORBIDDEN')
        absent = {k:v for k,v in env.items() if k != 'CLB91_REVIEWED_ROOT_APPROVAL'}
        self.refused(lambda: invoke(absent), 'APPROVAL_REQUIRED')
        for alias in ('CLB91_REVIEWED_ROOT_APPROVAL_VAR', 'REVIEWED_ROOT_APPROVAL',
                      'CLB82_AUTHORIZED_ROOT_BINDING'):
            self.refused(lambda: invoke(absent | {alias: review.encoded(approved).decode()}))
        for version in (1, True):
            changed = dict(approved, format=version)
            self.refused(lambda: invoke(env | {'CLB91_REVIEWED_ROOT_APPROVAL':review.encoded(changed).decode()}), 'APPROVAL_INVALID')

    def test_human_confirmation_execution_identity_and_expiry_are_mandatory(self):
        value, receipt, _, _ = reviewed_fixture()
        for reviewer, confirmed in (('other-reviewer',True), ('koteev-m',False), ('koteev-m',1)):
            self.refused(lambda: review.make_approval(value,receipt,'701',reviewer,confirmed,now=NOW), 'APPROVAL_REQUIRED')
        for number in ('700', '699', '01'):
            self.refused(lambda: review.make_approval(value,receipt,number,'koteev-m',True,now=NOW), 'APPROVAL_INVALID')
        approved, env, implementation, incident = approved_fixture()
        for name, replacement in (('GITHUB_RUN_NUMBER','702'), ('GITHUB_RUN_ATTEMPT','2'),
                                   ('GITHUB_SHA','d'*40), ('SSH_USER','other-principal')):
            self.refused(lambda: review.approval(env | {name:replacement}, executor.validate_binding,
                implementation, incident, executor.COMPOSE_PATH, now=NOW))
        self.refused(lambda: review.approval(env,executor.validate_binding,implementation,
            incident,executor.COMPOSE_PATH,now=approved['expires_at']), 'EXPIRED')

    def test_approval_incomplete_binding_decision_and_evidence_tamper_refuse(self):
        for variant in ('partial_binding', 'unapproved', 'wrong_reviewer', 'wrong_artifact',
                        'wrong_producer', 'wrong_attempt', 'extended_expiry', 'extra_field'):
            approved, env, implementation, incident = approved_fixture()
            if variant == 'partial_binding': approved['binding'].pop('configuration')
            elif variant == 'unapproved': approved['decision'] = 'reject'
            elif variant == 'wrong_reviewer': approved['reviewer'] = 'other-reviewer'
            elif variant == 'wrong_artifact': approved['evidence']['artifact_id'] = '0'
            elif variant == 'wrong_producer': approved['evidence']['source_revision'] = 'd'*40
            elif variant == 'wrong_attempt': approved['execution']['attempt'] = '2'
            elif variant == 'extended_expiry': approved['expires_at'] = approved['evidence']['expires_at'] + 1
            else: approved['auth'] = CANARY
            env['CLB91_REVIEWED_ROOT_APPROVAL'] = review.encoded(approved).decode()
            self.refused(lambda: review.approval(env,executor.validate_binding,implementation,
                incident,executor.COMPOSE_PATH,now=NOW))


class PrivatePublication(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name).resolve()
        self.directory.chmod(0o700)
        # This is the existing actual boundary validator, not a fixture bypass.
        descriptor = executor.open_canonical_root(str(self.directory))
        os.close(descriptor)

    def write(self, name, data):
        return review.write_private(str(self.directory), name, data, executor.open_canonical_root)

    def test_exclusive_private_publication_replay_and_symlink(self):
        value, _, _, _ = reviewed_fixture(); raw = review.encoded(value)
        self.write('review.json', raw)
        target = self.directory/'review.json'
        self.assertEqual(target.read_bytes(), raw)
        self.assertEqual(stat.S_IMODE(target.stat().st_mode), 0o600)
        with self.assertRaisesRegex(review.Refused, '^REPLAY$'): self.write('review.json', b'replacement')
        self.assertEqual(target.read_bytes(), raw)
        (self.directory/'approval.json').symlink_to(target)
        with self.assertRaisesRegex(review.Refused, '^REPLAY$'): self.write('approval.json', b'replacement')
        self.assertEqual(target.read_bytes(), raw)

    def test_concurrent_publication_has_exactly_one_winner(self):
        barrier = threading.Barrier(2); results = []; guard = threading.Lock()
        def writer(data):
            barrier.wait(timeout=5)
            try: self.write('approval.json', data); result = ('published', data)
            except review.Refused as failure: result = (str(failure), None)
            with guard: results.append(result)
        threads = [threading.Thread(target=writer,args=(value,)) for value in (b'first',b'second')]
        for thread in threads: thread.start()
        for thread in threads: thread.join(timeout=5); self.assertFalse(thread.is_alive())
        self.assertEqual(sorted(result[0] for result in results), ['REPLAY','published'])
        winner = next(data for category,data in results if category == 'published')
        self.assertEqual((self.directory/'approval.json').read_bytes(), winner)

    def test_short_write_failure_cleanup_and_canary_not_published(self):
        data = CANARY.encode(); original = os.write
        with patch.object(review.os,'write',side_effect=lambda fd,value: original(fd,value[:1])):
            self.write('review.json', data)
        self.assertEqual((self.directory/'review.json').read_bytes(), data)
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out),contextlib.redirect_stderr(err):
            with patch.object(review.os,'write',side_effect=OSError(CANARY)),self.assertRaises(OSError):
                self.write('approval.json', data)
        self.assertFalse((self.directory/'approval.json').exists())
        self.assertEqual(out.getvalue()+err.getvalue(),'')

    def test_file_close_failure_still_attempts_private_directory_close(self):
        original_open, original_close = os.open, os.close
        held, attempts = {}, []
        injected = False
        def root(path):
            descriptor = executor.open_canonical_root(path)
            held.setdefault('base', descriptor)
            return descriptor
        def opened(path, *args, **kwargs):
            descriptor = original_open(path, *args, **kwargs)
            if path == 'review.json': held['file'] = descriptor
            return descriptor
        def closed(descriptor):
            nonlocal injected
            original_close(descriptor)
            if descriptor == held.get('file') and not injected:
                injected = True
                attempts.append('file')
                raise OSError(CANARY)
            if descriptor == held.get('base'): attempts.append('base')
        out, err = io.StringIO(), io.StringIO()
        try:
            with contextlib.redirect_stdout(out),contextlib.redirect_stderr(err):
                with patch.object(review.os,'open',side_effect=opened),patch.object(review.os,'close',side_effect=closed):
                    with self.assertRaises((OSError,review.Refused)):
                        review.write_private(str(self.directory),'review.json',CANARY.encode(),root)
        finally:
            # Never retain the directory FD when testing an unsafe old finally.
            if 'base' in held and 'base' not in attempts: original_close(held['base'])
        self.assertTrue(injected)
        self.assertEqual(attempts[:2],['file','base'])
        self.assertFalse((self.directory/'review.json').exists())
        self.assertEqual(out.getvalue()+err.getvalue(),'')

    def test_directory_close_failure_revokes_own_approval(self):
        original_close = os.close
        held, attempts = {}, []
        injected = False
        def root(path):
            descriptor = executor.open_canonical_root(path)
            held.setdefault('base', descriptor)
            return descriptor
        def closed(descriptor):
            nonlocal injected
            original_close(descriptor)
            if descriptor == held.get('base') and not injected:
                injected = True
                attempts.append('base')
                raise OSError(CANARY)
            attempts.append('other')
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out),contextlib.redirect_stderr(err):
            with patch.object(review.os,'close',side_effect=closed):
                with self.assertRaisesRegex(review.Refused,'^CLEANUP$'):
                    review.write_private(str(self.directory),'approval.json',CANARY.encode(),root)
        self.assertTrue(injected)
        self.assertIn('base',attempts)
        self.assertFalse((self.directory/'approval.json').exists())
        self.assertEqual(out.getvalue()+err.getvalue(),'')

    def test_private_root_mode_names_and_bounds_refuse_without_residue(self):
        for name in ('../approval.json', '.env', 'plaintext.txt'):
            with self.assertRaisesRegex(review.Refused,'^PRIVATE_BOUNDARY$'): self.write(name,CANARY.encode())
        self.directory.chmod(0o755)
        with self.assertRaisesRegex(review.Refused,'^PRIVATE_BOUNDARY$'): self.write('review.json',CANARY.encode())
        self.directory.chmod(0o700)
        with self.assertRaises(review.Refused): self.write('review.json',b'x'*(review.LIMIT+1))
        self.assertFalse((self.directory/'review.json').exists())


def cli_source():
    """Startup flags select source-only fixtures, not a Python runtime proof."""
    original = sys.flags
    class SourceFlags:
        isolated = True
        no_site = True
        def __getattr__(self, name): return getattr(original, name)
    with patch.object(sys,'flags',SourceFlags()):
        return source_module(ROOT/'scripts/deploy/review-root-binding.py')


class CliTransactions(unittest.TestCase):
    """Real transaction/authority comparisons, synthetic API and decrypt only.

    The crypto fixture deliberately makes no backend, key or authentication
    assertion. The fixture transcript still passes the actual independent
    source comparisons, complete-binding validator, leases and publication.
    """
    setUp = PrivatePublication.setUp

    def prepare(self):
        with patch.dict(os.environ,{},clear=True): self.cli = cli_source()
        self.api = AuthenticatedApiFixture()
        self.value, self.receipt, _, _ = reviewed_fixture()
        self.document = review.encoded(dict(envelope=self.value,evidence=self.receipt))
        for name, data in (('review.json',self.document),('profile.json',b'{}'),('public.asc',b'fixture public bytes')):
            path = self.directory/name
            path.write_bytes(data);path.chmod(0o600)
        self.events = []

    @contextlib.contextmanager
    def transaction(self, action='approve', confirm=True):
        cli, owner = self.cli, self
        class FixtureCrypto:
            def __init__(self, profile, public_key, fingerprint, capture, safe_root, directory):
                owner.assertEqual((profile,public_key,fingerprint,directory),
                    (b'{}',b'fixture public bytes',FINGERPRINT,str(owner.directory)))
                owner.events.append('crypto-enter')
            def __enter__(self): return self
            def __exit__(self,*_): owner.events.append('crypto-cleanup')
            def decrypt(self, ciphertext, keyring):
                owner.assertEqual(ciphertext,owner.api.ciphertext)
                owner.assertEqual(keyring,str(owner.directory/'fixture-keyring'))
                owner.events.append('decrypt')
                return review.encoded(owner.value)
        def authenticated_api(token, expected, artifact_id):
            self.assertEqual(token,'FixtureAuthToken_OfflineOnly')
            self.assertEqual(expected,self.api.expected)
            self.assertEqual(artifact_id,'3001')
            self.events.append('api')
            return self.api
        def approved_source(revision):
            self.assertEqual(revision,SOURCE)
            return copy.deepcopy(self.api.source_bytes)
        def confirmation(receipt, run_number):
            self.assertEqual(receipt,self.receipt);self.assertEqual(run_number,'701')
            self.events.append('confirmation')
            if not confirm: raise cli.review.Refused('APPROVAL_REQUIRED')
        arguments = ['review-root-binding.py',action,'--directory',str(self.directory),
            '--run-id','1001','--attempt','1','--source-revision',SOURCE,'--artifact-id','3001',
            '--implementation',SOURCE+':'+executor.IMPLEMENTATION_BLOB+':'+executor.IMPLEMENTATION_SHA256,
            '--profile',str(self.directory/'profile.json'),'--public-key',str(self.directory/'public.asc'),
            '--fingerprint',FINGERPRINT,'--keyring',str(self.directory/'fixture-keyring'),'--run-number','701']
        previous_umask = os.umask(0o077)
        try:
            with contextlib.ExitStack() as stack:
                stack.enter_context(patch.dict(os.environ,{'GITHUB_TOKEN':'FixtureAuthToken_OfflineOnly'},clear=True))
                stack.enter_context(patch.object(sys,'argv',arguments))
                stack.enter_context(patch.object(sys,'platform','darwin'))
                stack.enter_context(patch.object(cli,'GitHub',side_effect=authenticated_api))
                stack.enter_context(patch.object(cli,'approved_source',side_effect=approved_source))
                stack.enter_context(patch.object(cli,'confirmation',side_effect=confirmation))
                stack.enter_context(patch.object(cli.review,'Crypto',FixtureCrypto))
                stack.enter_context(patch.object(cli.review.time,'time',return_value=NOW))
                # Runtime setup belongs to actual isolated/backend tests; these
                # fixtures must not irreversibly lower this test process limit.
                stack.enter_context(patch.object(cli.resource,'setrlimit'))
                stack.enter_context(patch.object(cli.signal,'signal'))
                yield cli
        finally: os.umask(previous_umask)

    def assert_private_failure(self, operation, exception):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out),contextlib.redirect_stderr(err):
            with self.assertRaises(exception): operation()
        self.assertFalse((self.directory/'approval.json').exists())
        self.assertEqual((self.directory/'review.json').read_bytes(),self.document)
        self.assertEqual(out.getvalue()+err.getvalue(),'')

    def test_lock_final_identity_failure_revokes_published_approval(self):
        self.prepare()
        with self.transaction() as cli:
            original = cli.review.write_private
            def substituted_after_write(*args):
                identity = original(*args)
                self.assertTrue((self.directory/'approval.json').is_file())
                self.events.append('published')
                lock = self.directory/'.review.lock'
                lock.unlink();lock.write_bytes(b'replacement');lock.chmod(0o600)
                return identity
            with patch.object(cli.review,'write_private',side_effect=substituted_after_write):
                self.assert_private_failure(cli.main,cli.review.Refused)
        self.assertIn('published',self.events)
        self.assertIn('decrypt',self.events)

    def test_cancellation_after_publication_revokes_approval(self):
        self.prepare()
        with self.transaction() as cli:
            original = cli.decide
            def interrupted_after_decide(args, publication):
                result = original(args,publication)
                self.assertEqual(result,'APPROVED_PRIVATE')
                self.assertTrue((self.directory/'approval.json').is_file())
                self.assertEqual(len(publication),1)
                self.events.append('published')
                raise cli.runner.CaptureInterrupted()
            with patch.object(cli,'decide',side_effect=interrupted_after_decide):
                self.assert_private_failure(cli.main,cli.runner.CaptureInterrupted)
        self.assertIn('published',self.events)

    def test_cancellation_at_public_footer_revokes_returned_approval(self):
        self.prepare()
        # Execute the actual final source block against the selected CLI
        # module. No copied transaction/footer implementation can hide a bug.
        path = ROOT/'scripts/deploy/review-root-binding.py'
        footer = ast.parse(path.read_bytes(),filename=str(path)).body[-1]
        self.assertIsInstance(footer,ast.If)
        program = compile(ast.Module(body=[footer],type_ignores=[]),str(path),'exec')
        out, err = io.StringIO(), io.StringIO()
        with self.transaction() as cli:
            original_main, original_mask = cli.main, cli.signal.pthread_sigmask
            returned = False
            def main(publication):
                nonlocal returned
                result = original_main(publication)
                self.assertEqual(result,'APPROVED_PRIVATE')
                self.assertEqual(len(publication),1)
                self.assertTrue((self.directory/'approval.json').is_file())
                returned = True
                return result
            def mask(how, watched):
                if returned and how == cli.signal.SIG_BLOCK:
                    raise cli.runner.CaptureInterrupted()
                return original_mask(how,watched)
            with contextlib.redirect_stdout(out),contextlib.redirect_stderr(err):
                with patch.object(cli,'main',side_effect=main),patch.object(cli.signal,'pthread_sigmask',side_effect=mask):
                    with patch.dict(cli.__dict__,{'__name__':'__main__'}):
                        with self.assertRaises(SystemExit) as failure: exec(program,cli.__dict__)
        self.assertTrue(returned)
        self.assertEqual(failure.exception.code,1)
        self.assertFalse((self.directory/'approval.json').exists())
        self.assertEqual((self.directory/'review.json').read_bytes(),self.document)
        self.assertEqual(out.getvalue(),'clb91-review:v=2 result=blocked category=INPUT\n')
        self.assertEqual(err.getvalue(),'')

    def test_decrypt_without_human_confirmation_never_approves(self):
        self.prepare()
        with self.transaction(confirm=False) as cli:
            self.assert_private_failure(cli.main,cli.review.Refused)
        self.assertEqual(self.events,['api','crypto-enter','decrypt','crypto-cleanup','confirmation'])

    def test_ambient_tls_environment_refuses_before_api_or_decrypt(self):
        for name in ('SSL_CERT_FILE','SSL_CERT_DIR','SSLKEYLOGFILE','OPENSSL_CONF',
                     'OPENSSL_CONF_INCLUDE','OPENSSL_MODULES','REQUESTS_CA_BUNDLE','CURL_CA_BUNDLE'):
            with self.subTest(name=name),patch.dict(os.environ,{name:CANARY},clear=True):
                # Startup exits before loading the runner, API or crypto. This
                # is source selection, not a synthetic successful TLS exchange.
                with patch.object(os,'open',wraps=os.open) as opened:
                    with self.assertRaises(SystemExit) as failure: cli_source()
                self.assertEqual(str(failure.exception),'clb91-review:v=2 result=blocked category=PRODUCER')
                self.assertEqual(opened.call_count,1)
        self.prepare()
        with patch.dict(os.environ,{'SSLKEYLOGFILE':CANARY},clear=True):
            with patch.object(self.cli.runner,'capture') as capture,patch.object(self.cli.review,'Crypto') as crypto:
                with self.assertRaisesRegex(self.cli.review.Refused,'^PRODUCER$'):
                    self.cli.GitHub('FixtureAuthToken_OfflineOnly',self.api.expected,'3001')
            capture.assert_not_called();crypto.assert_not_called()

    def test_github_child_environment_and_total_capture_bound(self):
        self.prepare()
        records = []
        def capture(argv, payload, **kwargs):
            records.append((argv,payload,kwargs));return 0,b'{"fixture":true}'
        token = 'FixtureAuthToken_OfflineOnly'
        with patch.dict(os.environ,{'GITHUB_TOKEN':token,'HTTPS_PROXY':CANARY,'PATH':CANARY},clear=True):
            with patch.object(self.cli.runner,'capture',side_effect=capture):
                client = self.cli.GitHub(token,self.api.expected,'3001')
                self.assertEqual(client('actions/runs/1001/attempts/1'),{'fixture':True})
                with self.assertRaisesRegex(self.cli.review.Refused,'^PRODUCER$'):
                    client('actions/runs/other/attempts/1')
        self.assertEqual(len(records),1)
        argv,payload,options = records[0]
        self.assertEqual(argv[:5],[sys.executable,'-I','-S','-B','-c'])
        self.assertNotIn(token,' '.join(argv));self.assertNotIn(CANARY,' '.join(argv))
        self.assertEqual(review.decode(payload),dict(token=token,route='actions/runs/1001/attempts/1'))
        self.assertEqual(options,dict(env={'PATH':'/usr/bin:/bin','LC_ALL':'C'},timeout=30,limit=1048576))
        self.assertFalse(set(self.cli.TLS_OVERRIDES)&set(options['env']))
        self.assertNotIn('GITHUB_TOKEN',options['env'])


def helper_namespace(phase='claim'):
    """Compile actual embedded methods without FD-consuming shell/entrypoint.

    These tests prove deadline/claim decisions under an explicit synthetic
    retained context and clock. They do not prove Linux runtime or stage trust.
    """
    path = ROOT/'scripts/deploy/remote-compose-release.sh'
    raw = path.read_bytes()
    source = raw.decode().split("<<'CLB82_BOUND_PYTHON' || true\n",1)[1].split('\nCLB82_BOUND_PYTHON',1)[0]
    tree = ast.parse(source,filename=str(path))
    definitions = [node for node in tree.body if isinstance(node,(ast.Import,ast.ImportFrom,
        ast.FunctionDef,ast.ClassDef,ast.Assign)) and not (isinstance(node,ast.Assign) and
        any(isinstance(target,ast.Name) and target.id in ('FUNCTIONS','context') for target in node.targets))]
    namespace = {}
    args = ['helper',executor.INCIDENT['RELEASE_OWNER'],'stage',executor.COMPOSE_PATH,
            executor.INCIDENT['EXPECTED_REVISION'],executor.INCIDENT['IMAGE_DIGEST'],phase]
    with patch.object(sys,'argv',args):
        exec(compile(ast.Module(body=definitions,type_ignores=[]),str(path),'exec'),namespace)
    return namespace, raw


class HelperAuthority(unittest.TestCase):
    """Actual authority methods; synthetic retained context and private clock."""
    setUp = PrivatePublication.setUp
    marker = '.clb82-resume-start-33468965282-1.consumed'

    def prepare(self):
        self.helper, self.snapshot = helper_namespace()
        self.approved, self.env, _, _ = approved_fixture()
        # Bind the fixture's complete approval to actual selected helper bytes.
        # The source revision remains explicitly fabricated offline evidence.
        implementation = dict(revision=SOURCE,
            blob=hashlib.sha1(('blob '+str(len(self.snapshot))+'\0').encode()+self.snapshot).hexdigest(),
            sha256=hashlib.sha256(self.snapshot).hexdigest(),size=len(self.snapshot),
            path=executor.HELPER_PATH,mode='100644',type='blob')
        self.approved['binding']['implementation'] = copy.deepcopy(implementation)
        # A narrower accepted deadline leaves room for a coherent attempted
        # extension inside the evidence TTL; durable claim equality must reject it.
        self.approved['expires_at'] = NOW+120
        self.env.update(executor.INCIDENT,ACTION='resume-start',COMPOSE_PATH=executor.COMPOSE_PATH,
            CLB91_REVIEWED_ROOT_APPROVAL=review.encoded(self.approved).decode())
        self.control = dict(version=2,implementation=implementation,
            binding=copy.deepcopy(self.approved['binding']),
            authority=review.private_authority(copy.deepcopy(self.approved)),
            authorization=hashlib.sha256(review.encoded(self.approved)).hexdigest(),
            token='d'*64,principal=self.env['SSH_USER'])
        self.clock = [NOW]
        self.now_patch = patch.object(self.helper['time'],'time',side_effect=lambda:self.clock[0])
        self.now_patch.start();self.addCleanup(self.now_patch.stop)
        bound = self.helper['BoundContext'];self.ctx = bound.__new__(bound)
        self.ctx.control = self.control
        self.ctx.pin = self.control['binding']
        self.ctx.binding_digest = self.helper['sha'](self.helper['canonical'](self.ctx.pin))
        self.ctx.incident = copy.deepcopy(self.approved['binding']['incident'])
        self.ctx.phase = 'claim'
        self.ctx.owner = executor.INCIDENT['RELEASE_OWNER']
        self.ctx.compose = executor.COMPOSE_PATH
        self.ctx.revision = executor.INCIDENT['EXPECTED_REVISION']
        self.ctx.image = executor.INCIDENT['IMAGE_DIGEST']
        self.ctx.active = self.ctx.completed = self.ctx.signaled = False
        fd = executor.open_canonical_root(str(self.directory))
        self.addCleanup(os.close,fd)
        self.ctx.directories = {'root':fd,'compose':fd}
        self.ctx.ready = lambda:None
        self.ctx.check_edges = lambda:None
        self.ctx.check_evidence = lambda:None
        self.ctx.value = lambda name:'migration_completed'
        def private_record(area, name, limit):
            self.assertEqual(area,'root');self.assertEqual(name,self.marker)
            raw = (self.directory/name).read_bytes()
            self.assertLessEqual(len(raw),limit)
            return raw
        self.ctx.read = private_record
        self.ctx.operation_result = lambda *args:self.fail('unexpected operation write')
        self.ctx.worker = lambda *args:self.fail('unexpected worker invocation')
        # Positive authority control uses the actual method without replacement.
        self.ctx.check_authority()

    def test_control_v1_refuses_before_any_private_context_read(self):
        helper, _ = helper_namespace()
        binding, implementation, incident = complete_binding()
        control = dict(version=1,implementation=implementation,binding=binding,
            authorization='d'*64,token='e'*64,principal='deployment-fixture',authority={})
        with patch.object(helper['os'],'open') as opened,patch.object(helper['subprocess'],'check_output') as principal:
            with patch.object(helper['BoundContext'],'open_context') as capture:
                with self.assertRaisesRegex(RuntimeError,'^bound context rejected$'):
                    helper['BoundContext'](incident['RELEASE_OWNER'],'stage',executor.COMPOSE_PATH,
                        incident['EXPECTED_REVISION'],incident['IMAGE_DIGEST'],'claim',control)
        opened.assert_not_called();principal.assert_not_called();capture.assert_not_called()

    def test_claim_ready_crosses_deadline_before_consumed_file_creation(self):
        self.prepare()
        self.ctx.ready = lambda:self.clock.__setitem__(0,self.approved['expires_at'])
        with patch.object(self.helper['os'],'open') as opened:
            with self.assertRaisesRegex(RuntimeError,'^bound context rejected$'): self.ctx.claim()
        opened.assert_not_called()
        self.assertFalse((self.directory/self.marker).exists())

    def test_valid_claim_then_expiry_retains_marker_without_resume_worker(self):
        self.prepare()
        self.assertEqual(self.ctx.claim(),b'release-authorization:v=1 consumed=yes\n')
        saved = (self.directory/self.marker).read_bytes()
        self.assertEqual(json.loads(saved),self.ctx.claim_record())
        self.assertEqual(stat.S_IMODE((self.directory/self.marker).stat().st_mode),0o600)
        self.ctx.phase = 'resume-start'
        self.clock[0] = self.approved['expires_at']
        with patch.object(self.ctx,'worker') as worker,patch.object(self.ctx,'operation_result') as written:
            with self.assertRaisesRegex(RuntimeError,'^bound context rejected$'): self.ctx.resume()
        worker.assert_not_called();written.assert_not_called()
        self.assertEqual((self.directory/self.marker).read_bytes(),saved)
        self.assertFalse(self.ctx.active)

    def test_private_claim_rejects_coherent_deadline_source_or_run_change(self):
        self.prepare();self.ctx.claim()
        self.ctx.phase = 'resume-start'
        saved = (self.directory/self.marker).read_bytes()
        original = copy.deepcopy(self.control['authority'])
        for changed in ('deadline','source','run'):
            with self.subTest(changed=changed):
                authority = copy.deepcopy(original)
                if changed == 'deadline': authority['expires_at'] += 1
                elif changed == 'source':
                    authority['execution']['source_revision'] = 'e'*40
                    authority['evidence']['source_revision'] = 'e'*40
                else: authority['execution']['run_number'] = '702'
                self.control['authority'] = authority
                self.ctx.check_authority()  # Coherent metadata alone cannot rewrite the durable claim.
                self.assertNotEqual(json.loads(saved),self.ctx.claim_record())
                with patch.object(self.ctx,'worker') as worker,patch.object(self.ctx,'operation_result') as written:
                    with self.assertRaisesRegex(RuntimeError,'^bound context rejected$'): self.ctx.resume()
                worker.assert_not_called();written.assert_not_called()
                self.assertEqual((self.directory/self.marker).read_bytes(),saved)
        self.control['authority'] = original

    def test_expiry_blocks_candidate_transition_and_compose_up_before_writes(self):
        self.prepare()
        self.ctx.phase = 'resume-start'
        self.ctx.active = True
        self.ctx.project = 'fixture'
        self.ctx.resolved_capture = self.ctx.env_capture = '/dev/null'
        self.ctx.config_fds = []
        self.ctx.docker_config_fd = self.ctx.directories['root']
        self.ctx.docker_environment = {'PATH':'/usr/bin:/bin','LC_ALL':'C'}
        self.clock[0] = self.approved['expires_at']
        with patch.object(self.ctx,'replace',create=True) as replace,patch.object(self.helper['subprocess'],'run') as run:
            operations = (
                lambda:self.ctx.rpc(['transition','migration_completed','candidate_start_begun']),
                lambda:self.ctx.rpc(['compose','up','-d','--no-deps','app']),
                lambda:self.ctx.compose_call(['up','-d','--no-deps','app']))
            for operation in operations:
                with self.assertRaisesRegex(RuntimeError,'^bound context rejected$'): operation()
        replace.assert_not_called();run.assert_not_called()

    def test_runner_expiry_after_claim_reconciles_once_without_resume_attempt(self):
        self.prepare()
        latest = source_module(ROOT/'scripts/deploy/corrected-stage-release.py')
        calls = []
        status = (b'release-status:v=1 status_available=yes owner_match=yes revision_match=yes digest_match=yes '
            b'checkpoint=migration_completed operation_result=remote_failure migration_evidence=present app_state=absent '
            b'abort_permitted=no resume_permitted=yes failure_category=none\n')
        def remote(snapshot, argv, timeout, control):
            self.assertEqual(snapshot,self.snapshot);self.assertEqual(control,self.control)
            name = argv[-1];calls.append(name)
            self.assertEqual(timeout,90)
            if name == 'inspect':
                return 0,status+b'corrected-binding-candidate:v=1 '+review.encoded(self.control['binding'])+b'\n'
            if name == 'claim':
                self.clock[0] = self.approved['expires_at']
                return 0,b'release-authorization:v=1 consumed=yes\n'
            self.assertEqual(name,'reconcile')
            return 0,status
        public, err = io.StringIO(),io.StringIO()
        with contextlib.redirect_stdout(public),contextlib.redirect_stderr(err):
            with self.assertRaisesRegex(latest._review.Refused,'^EXPIRED$'):
                latest.execute(self.env,self.snapshot,remote,self.control)
        self.assertEqual(calls,['inspect','claim','reconcile'])
        self.assertNotIn('attempted=yes',public.getvalue())
        self.assertNotIn(CANARY,public.getvalue())
        self.assertEqual(err.getvalue(),'')


if __name__ == '__main__':
    unittest.main(verbosity=2)
