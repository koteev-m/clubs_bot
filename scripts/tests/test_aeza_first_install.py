#!/usr/bin/env python3
"""Synthetic filesystem/Engine and Linux process tests; no real secrets.

ComposeRuntimeTest is opt-in and uses only the local Engine for a networkless JVM probe.
"""
import importlib.util
import copy
import http.server
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.parse
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('initial', ROOT / 'scripts/deploy/aeza-initial/first-install.py')
M = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(M)
ENV = dict(CLB_PG_IMAGE='postgres@sha256:' + 'd' * 64, CLB_PLATFORM='linux/arm64', CLB_APP_IMAGE='ghcr.io/koteev-m/clubs_bot/app-bot@sha256:' + 'a' * 64,
           CLB_REVISION='b' * 40, CLB_DB_PASSWORD='synthetic_db_' + 'x' * 32,
           CLB_BOT_TOKEN='123456:' + 's' * 32, CLB_WEBHOOK_SECRET='s' * 32,
           CLB_OWNER_ID='123456', CLB_QR_SECRET='q' * 32, CLB_PHONE_KEY='p' * 32,
           CLB_HQ_CHAT_ID='-101', CLB_CLUB1_CHAT_ID='-102', CLB_CLUB2_CHAT_ID='-103',
           CLB_CLUB3_CHAT_ID='-104', CLB_CLUB4_CHAT_ID='-105')

# Synthetic fixtures only; never load an operator's runtime configuration.
CHAT_INPUTS = {'HQ_CHAT_ID': 'CLB_HQ_CHAT_ID', 'CLUB1_CHAT_ID': 'CLB_CLUB1_CHAT_ID',
               'CLUB2_CHAT_ID': 'CLB_CLUB2_CHAT_ID', 'CLUB3_CHAT_ID': 'CLB_CLUB3_CHAT_ID',
               'CLUB4_CHAT_ID': 'CLB_CLUB4_CHAT_ID'}
# Observed from the official v2.40.3 binary's NetworkCreate requests against a
# disposable mock Engine. Compose2403NetworkTest reproduces these independent
# oracles; production derives them from the pinned model, never from inspect.
NETWORK_HASHES_2403 = {
    'application': '86012741d9e831314e108f1f81f59e446d1bfbe94689cea3acd77a12425a1074',
    'database': 'ae1875c10af3d0ede096342b10e1ce24c90b2513271395b7d4b07705a24a9d3f',
}


def fixture(root):
    root.mkdir(mode=0o700)
    for name in ('docker-compose.yml', '10-clubs-role.sql'):
        (root / name).write_bytes((M.TEMPLATE / name).read_bytes())
        (root / name).chmod(0o644)
    (root / '.env').write_text(''.join(k + '=' + v + '\n' for k, v in ENV.items()))
    (root / '.env').chmod(0o600)
    (root / 'secrets').mkdir(mode=0o700)
    for name, content in [('postgres_password', 'synthetic_admin_' + 'a' * 32)]:
        (root / 'secrets' / name).write_text(content)
        (root / 'secrets' / name).chmod(0o600)


class Engine(M.Install):
    def __init__(self, root):
        super().__init__(root)
        self.containers, self.networks, self.volumes, self.calls = [], [], [], []
        self.validator = b'schema-validation:v=1 result=compatible\n'
        self.invalid = None
        self.phase_failure = None
        self.stopped = False

    def ids(self, kind):
        return list({'container': self.containers, 'network': self.networks, 'volume': self.volumes}[kind])

    def inspect(self, kind, name):
        if kind == 'image':
            image = dict(Id='sha256:' + 'a' * 64, Os='linux', Architecture='arm64', RepoDigests=[name],
                         Config={'Env': ['PG_MAJOR=16'], 'Labels': {'org.opencontainers.image.revision': ENV['CLB_REVISION']}, 'User': '10001:10001'})
            if name.startswith('sha256:'):
                image['RepoDigests'] = [ENV['CLB_APP_IMAGE']]
            if self.invalid == 'platform':
                image['Architecture'] = 'amd64'
            if self.invalid == 'revision':
                image['Config']['Labels'] = {}
            if self.invalid == 'null_labels':
                image['Config']['Labels'] = None
            if self.invalid == 'pg_version':
                image['Config']['Env'] = ['PG_MAJOR=17']
            if self.invalid == 'digest':
                image['RepoDigests'] = []
            return image
        if kind == 'network':
            return dict(Internal=True, Labels={'com.docker.compose.project': M.PROJECT})
        service = 'db' if name == 'd' * 64 else 'app'
        app = service == 'app'
        return dict(Id=name, Image='sha256:' + 'a' * 64,
                    Config={'Image': ENV['CLB_APP_IMAGE'] if app else ENV['CLB_PG_IMAGE'],
                            'Labels': {'com.docker.compose.project': M.PROJECT, 'com.docker.compose.service': service,
                                       'com.docker.compose.oneoff': 'False'}},
                    State={'Running': not (app and self.stopped), 'Health': {'Status': 'unhealthy' if app and self.invalid == 'health' else 'healthy'}, 'StartedAt': '2026-10-19T00:00:00Z'},
                    RestartCount=0, HostConfig={'PortBindings': {'8080/tcp': [{'HostIp': '127.0.0.1', 'HostPort': '8081'}]} if app else {}},
                    NetworkSettings={'Networks': {M.PROJECT + '_database': {}}},
                    Mounts=[{'Destination': '/var/lib/postgresql/data', 'Type': 'volume', 'Name': M.PROJECT + '_pgdata'}])

    def run(self, args, timeout=30):
        self.calls.append(args)
        if args[:2] == ['docker', 'stop']:
            self.stopped = True
        if args[:2] == ['docker', 'compose']:
            if 'run' in args:
                if self.phase_failure == 'validator':
                    raise M.Refused('command_failed')
                if self.phase_failure == 'timeout':
                    raise subprocess.TimeoutExpired('synthetic', timeout)
                return self.validator
            if 'up' in args:
                if args[-1] == 'db':
                    self.containers = ['d' * 64]
                    self.networks = ['network-id']
                    self.volumes = [M.PROJECT + '_pgdata']
                else:
                    self.containers.append('c' * 64)
                    if self.phase_failure == 'start':
                        raise M.Refused('command_failed')
        return b''


class FirstInstallTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='clb91-first-install-')
        self.root = Path(self.temp.name).resolve() / 'application'
        fixture(self.root)
        self.engine = Engine(self.root)

    def tearDown(self):
        self.temp.cleanup()

    def test_order_and_real_baseline_contract_without_release_state(self):
        self.engine.database()
        self.engine.application()
        self.engine.verify()
        calls = self.engine.calls
        validate = next(i for i, a in enumerate(calls) if '/opt/app/bin/app-bot-validate-schema' in a)
        start = next(i for i, a in enumerate(calls) if 'up' in a and a[-1] == 'app')
        self.assertLess(validate, start)
        self.assertFalse((self.root / '.clubs-bot-release-state').exists())
        text = json.dumps(calls)
        for forbidden in ('sudo', 'ssh', 'pull always', 'migrate-and-validate', 'repair', 'baseline', 'caddy'):
            self.assertNotIn(forbidden, text)
        self.assertIn('baseline-observed', {p.name for p in self.engine.state.iterdir()})

    def test_bad_schema_output_never_starts(self):
        for output in (b'', b'schema-validation:v=1 result=rejected\n', b'schema-validation:v=1 result=compatible\nextra\n'):
            with self.subTest(output=output):
                with tempfile.TemporaryDirectory() as tmp:
                    root = Path(tmp).resolve() / 'root'
                    fixture(root)
                    e = Engine(root)
                    e.database()
                    e.validator = output
                    with self.assertRaises(M.Refused):
                        e.application()
                    self.assertFalse(any('up' in a and a[-1] == 'app' for a in e.calls))
                    with self.assertRaises(M.Refused):
                        e.application()

    def test_validator_exit_or_timeout_keeps_partial_attempt(self):
        self.engine.database()
        self.engine.phase_failure = 'validator'
        with self.assertRaises(M.Refused):
            self.engine.application()
        self.assertEqual(self.engine.containers, ['d' * 64])
        with self.assertRaises(M.Refused):
            self.engine.application()

    def test_validator_timeout(self):
        self.engine.database()
        self.engine.phase_failure = 'timeout'
        with self.assertRaises(subprocess.TimeoutExpired):
            self.engine.application()
        self.assertEqual(self.engine.containers, ['d' * 64])

    def test_missing_old_state_is_required(self):
        (self.root / '.clubs-bot-release-state').mkdir()
        with self.assertRaisesRegex(M.Refused, 'foreign_or_release_state'):
            Engine(self.root)

    def test_override_refused(self):
        (self.root / 'docker-compose.override.yml').write_text('services: {}')
        with self.assertRaises(M.Refused):
            Engine(self.root)

    def test_partial_db_refused(self):
        self.engine.state.mkdir()
        with self.assertRaises(M.Refused):
            self.engine.database()
        self.assertEqual(self.engine.calls, [])

    def test_repeated_db_refused(self):
        self.engine.database()
        with self.assertRaises(M.Refused):
            self.engine.database()

    def test_existing_project_refused(self):
        self.engine.containers = ['unowned']
        with self.assertRaisesRegex(M.Refused, 'existing_project'):
            self.engine.database()

    def test_existing_app_refused(self):
        self.engine.database()
        self.engine.containers.append('partial-app')
        with self.assertRaises(M.Refused):
            self.engine.application()

    def test_resource_continuity(self):
        self.engine.database()
        self.engine.volumes.append('foreign-volume')
        with self.assertRaisesRegex(M.Refused, 'resource_continuity'):
            self.engine.application()

    def test_image_contract(self):
        for invalid in ('platform', 'revision', 'null_labels', 'digest', 'pg_version'):
            with self.subTest(invalid=invalid):
                self.engine.invalid = invalid
                with self.assertRaises(M.Refused):
                    self.engine.database()
                self.assertFalse(self.engine.state.exists())

    def test_bad_configuration(self):
        original = (self.root / '.env').read_text()
        for before, after in [(ENV['CLB_APP_IMAGE'], 'image:latest'), (ENV['CLB_REVISION'], 'main'),
                              (ENV['CLB_PLATFORM'], 'linux/unknown'), (ENV['CLB_DB_PASSWORD'], 'short'),
                              (ENV['CLB_BOT_TOKEN'], '${SECRET}'), (ENV['CLB_OWNER_ID'], '0')]:
            with self.subTest(before=before):
                (self.root / '.env').write_text(original.replace(before, after))
                with self.assertRaises(M.Refused):
                    Engine(self.root)
        (self.root / '.env').write_text(original + 'FLYWAY_MODE=migrate\n')
        with self.assertRaises(M.Refused):
            Engine(self.root)

    def test_config_change_after_db(self):
        self.engine.database()
        (self.root / '.env').write_text((self.root / '.env').read_text().replace('q' * 32, 'z' * 32))
        other = Engine(self.root)
        with self.assertRaisesRegex(M.Refused, 'configuration_changed'):
            other.application()

    def test_required_chat_ids_and_signed_long_boundaries(self):
        original = (self.root / '.env').read_text()
        for key in CHAT_INPUTS.values():
            line = key + '=' + ENV[key] + '\n'
            with self.subTest(key=key, missing=True):
                (self.root / '.env').write_text(original.replace(line, ''))
                with self.assertRaisesRegex(M.Refused, '^runtime_config$'):
                    Engine(self.root)
            for value in ('', '0', '-0', '+0', '000', '1.5', '1e3', 'NaN', '--1', '+',
                          ' 1', '1 ', '１', '9223372036854775808', '-9223372036854775809',
                          '1' * 100, '${CLB_OWNER_ID}'):
                with self.subTest(key=key, invalid=value):
                    (self.root / '.env').write_text(original.replace(line, key + '=' + value + '\n'))
                    with self.assertRaises(M.Refused) as refused:
                        Engine(self.root)
                    self.assertIn(str(refused.exception), ('runtime_config', 'chat_id'))
                    self.assertFalse(self.engine.state.exists())
            for value in ('-9223372036854775808', '9223372036854775807', '-1', '1', '+1', '-0001'):
                with self.subTest(key=key, valid=value):
                    (self.root / '.env').write_text(original.replace(line, key + '=' + value + '\n'))
                    self.assertEqual(M.configuration(self.root)[0][key], value)

    def test_template_covers_actual_required_appconfig_inputs(self):
        compose = (M.TEMPLATE / 'docker-compose.yml').read_text()
        app_env = compose.split('  app:\n', 1)[1].split('    environment:\n', 1)[1].split('    networks:', 1)[0]
        supplied = set(re.findall(r'^      ([A-Z][A-Z0-9_]*):', app_env, re.M))
        source = (ROOT / 'core-domain/src/main/kotlin/com/example/bot/config/AppConfig.kt').read_text()
        required = set(re.findall(r'\benv(?:Required|Long|Int|Bool)\("([A-Z0-9_]+)"\)', source))
        self.assertTrue(set(CHAT_INPUTS) <= required)
        self.assertFalse(required - supplied, 'AppConfig has required inputs absent from initial Compose')
        self.assertIn('MINI_APP_URL', supplied)  # required conditionally for stage/prod
        for runtime, private in CHAT_INPUTS.items():
            self.assertIn('      ' + runtime + ': ${' + private + ':?required}\n', app_env)
        example = (M.TEMPLATE / 'runtime.env.example').read_text()
        self.assertEqual(set(re.findall(r'^([A-Z0-9_]+)=', example, re.M)), M.KEYS)
        self.assertEqual(set(ENV), M.KEYS)

    def test_chat_config_change_invalidates_database_binding(self):
        self.engine.database()
        original = (self.root / '.env').read_text()
        for key in CHAT_INPUTS.values():
            (self.root / '.env').write_text(original.replace(key + '=' + ENV[key], key + '=-901'))
            other = Engine(self.root)
            with self.assertRaisesRegex(M.Refused, '^configuration_changed$'):
                other.application()
            self.assertEqual(other.calls, [])

    def test_failed_install_with_updated_chat_config_cannot_be_replayed(self):
        self.engine.database()
        self.engine.phase_failure = 'start'
        with self.assertRaisesRegex(M.Refused, '^command_failed$'):
            self.engine.application()
        before = {p.name: p.read_bytes() for p in self.engine.state.iterdir()}
        self.assertEqual(set(before), {'database-attempt', 'database-ready', 'application-attempt', 'schema-compatible'})
        path = self.root / '.env'
        path.write_text(path.read_text().replace('CLB_HQ_CHAT_ID=-101', 'CLB_HQ_CHAT_ID=-901'))
        other = Engine(self.root)
        for phase in ('database', 'application', 'verify'):
            with self.subTest(phase=phase):
                with self.assertRaisesRegex(M.Refused, '^partial_or_repeated_install$'):
                    getattr(other, phase)()
                self.assertEqual(other.calls, [])
                self.assertEqual(before, {p.name: p.read_bytes() for p in self.engine.state.iterdir()})

    def test_secrets_private_and_no_symlink(self):
        (self.root / '.env').chmod(0o644)
        with self.assertRaises(M.Refused):
            Engine(self.root)
        (self.root / '.env').chmod(0o600)
        target = self.root / 'secrets/postgres_password'
        target.unlink()
        target.symlink_to(self.root / '.env')
        with self.assertRaises(M.Refused):
            Engine(self.root)

    def test_changed_template_refused(self):
        path = self.root / 'docker-compose.yml'
        path.write_text(path.read_text().replace('127.0.0.1:8081', '0.0.0.0:8081'))
        with self.assertRaisesRegex(M.Refused, 'template_changed'):
            Engine(self.root)

    def test_confirmed_unhealthy_app_stops_only_new_app_and_blocks_retry(self):
        self.engine.database()
        self.engine.invalid = 'health'
        with self.assertRaises(M.Refused):
            self.engine.application()
        self.assertTrue(self.engine.stopped)
        self.assertEqual([a for a in self.engine.calls if a[:2] == ['docker', 'stop']],
                         [['docker', 'stop', '--time', '10', 'c' * 64]])
        self.assertFalse((self.engine.state / 'baseline-observed').exists())
        with self.assertRaises(M.Refused):
            self.engine.application()

    def test_nonzero_docker_start_is_unknown_and_never_stops_or_retries(self):
        self.engine.database()
        self.engine.phase_failure = 'start'
        with self.assertRaisesRegex(M.Refused, '^command_failed$'):
            self.engine.application()
        self.assertFalse(self.engine.stopped)
        self.assertFalse(any(a[:2] == ['docker', 'stop'] for a in self.engine.calls))
        self.assertTrue((self.engine.state / 'application-attempt').exists())
        with self.assertRaisesRegex(M.Refused, '^partial_or_repeated_install$'):
            self.engine.application()

    def test_unlabelled_name_collision_is_refused(self):
        original = self.engine.run
        def run(args, timeout=30):
            if args[:3] == ['docker', 'volume', 'ls']:
                return (M.PROJECT + '_pgdata\n').encode()
            return original(args, timeout)
        self.engine.run = run
        with self.assertRaisesRegex(M.Refused, 'resource_name_collision'):
            self.engine.database()
        self.assertFalse(self.engine.state.exists())

    def test_actual_fsm_old_app_collector(self):
        source = (ROOT / 'scripts/deploy/remote-compose-release.sh').read_text()
        function = source.split('collect_old_app_evidence() {', 1)[1].split('\n}\n', 1)[0]
        functions = source.split('validate_revision() {', 1)[1].split('validate_checkpoint() {', 1)[0]
        script = 'validate_revision() {' + functions
        script += '''
current_compose_container_id() { printf '%s' "$TEST_CONTAINER"; }
image_digest() { printf '%s' "$TEST_IMAGE"; }
image_revision() { printf '%s' "$TEST_REVISION"; }
inspect_container_value() {
 case "$1" in
  *State.Running*) printf true ;;
  *'{{.Image}}'*) printf 'sha256:%064d' 1 ;;
  *compose.project*) printf clubs-bot-stage ;;
  *compose.service*) printf app ;;
  *State.StartedAt*) printf '2026-10-19T00:00:00Z' ;;
  *RestartCount*) printf 0 ;;
  *) return 1 ;;
 esac
}
collect_old_app_evidence() {''' + function + '\n}\ncollect_old_app_evidence /synthetic\n'
        env = dict(os.environ, TEST_CONTAINER='c' * 64, TEST_IMAGE=ENV['CLB_APP_IMAGE'], TEST_REVISION=ENV['CLB_REVISION'])
        for updates, expected in [({}, 0), ({'TEST_CONTAINER': ''}, 1),
                                  ({'TEST_REVISION': ''}, 1), ({'TEST_IMAGE': 'clubs-bot@sha256:' + 'a' * 64}, 1)]:
            with self.subTest(updates=updates):
                result = subprocess.run(['bash', '-c', script], env=env | updates, capture_output=True)
                self.assertEqual(result.returncode, expected, result.stderr)

    def test_admin_password_must_be_separate(self):
        (self.root / 'secrets/postgres_password').write_text(ENV['CLB_DB_PASSWORD'])
        with self.assertRaises(M.Refused):
            Engine(self.root)

    def test_raw_compose_root_matches_actual_release_admission(self):
        source = (ROOT / 'scripts/deploy/remote-compose-release.sh').read_text()
        functions = source.split('validate_compose_path() {', 1)[1].split('\npath_owner_id() {', 1)[0]
        script = 'validate_compose_path() {' + functions + '\nvalidate_compose_path "$1"\n'
        spaced = self.root.parent / 'Clubs Bot'
        unicode = self.root.parent / 'Clubs_Бот'
        for path in (spaced, unicode):
            fixture(path)
        link = self.root.parent / 'alias'
        link.symlink_to(self.root, target_is_directory=True)
        parent_link = self.root.parent / 'parent-alias'
        parent_link.symlink_to(self.root.parent, target_is_directory=True)
        raw = str(self.root)
        cases = [raw, str(spaced), str(unicode), str(link), str(parent_link / self.root.name),
                 raw + '/', raw + '/.', raw + '/..', raw + '//',
                 str(self.root.parent) + '//application',
                 str(self.root.parent) + '/./application', raw + '/../application',
                 'relative/root', '/', raw + '/bad:component', raw + '/bad\\component',
                 raw + '\n', raw + '/bad;component']
        for value in cases:
            with self.subTest(value=value):
                expected = value == raw
                result = subprocess.run(['bash', '-c', script, 'test', value], capture_output=True)
                self.assertEqual(result.returncode == 0, expected)
                with mock.patch.object(M.Install, 'run', side_effect=AssertionError('must not execute')):
                    if expected:
                        self.assertEqual(M.Install(value).root, self.root)
                    else:
                        with self.assertRaisesRegex(M.Refused, '^root_path$'):
                            M.Install(value)
                self.assertFalse(self.engine.state.exists())


def recovery_fixture(root):
    fixture(root)
    values = ENV | {'CLB_REVISION': M.ORIGINAL_REVISION}
    target = root.parent / 'recovery.env'
    target.write_text(''.join(k + '=' + v + '\n' for k, v in values.items()))
    target.chmod(0o600)
    (root / '.env').write_text(''.join(k + '=' + v + '\n' for k, v in values.items() if k not in M.CHAT_KEYS))
    (root / 'docker-compose.yml').write_bytes(M.original_compose())
    engine = RecoveryEngine(root)
    engine.state.mkdir(mode=0o700)
    for name in M.ORIGINAL_MARKERS:
        value = ({'id': 'd' * 64, 'networks': ['b' * 64], 'volumes': [M.PROJECT + '_pgdata']}
                 if name == 'database-ready' else {'binding': engine.binding})
        engine.mark(name, value)
    return engine, target


class RecoveryEngine(Engine):
    """Synthetic stopped install; actual recipe writes and validates real fixture bytes."""
    def __init__(self, root):
        M.Install.__init__(self, root, recovery=True)
        self.host_lock = mock.Mock()
        self.containers = ['d' * 64, 'c' * 64]
        self.networks = ['b' * 64, 'e' * 64]
        self.volumes = [M.PROJECT + '_pgdata']
        self.calls = []
        self.compose_version = b'2.40.3\n'
        self.model_networks = {'application': {'name': M.PROJECT + '_application', 'ipam': {}},
                               'database': {'name': M.PROJECT + '_database', 'ipam': {}, 'internal': True}}
        self.invalid = self.phase_failure = None
        self.validator = b'schema-validation:v=1 result=compatible\n'
        self.objects = {}
        for suffix, nid in [('database', 'b' * 64), ('application', 'e' * 64)]:
            name = M.PROJECT + '_' + suffix
            self.objects[('network', name)] = dict(Id=nid, Name=name, Internal=suffix == 'database', Driver='bridge',
                Labels={'com.docker.compose.project': M.PROJECT, 'com.docker.compose.network': suffix,
                        'com.docker.compose.version': '2.40.3',
                        'com.docker.compose.config-hash': NETWORK_HASHES_2403[suffix]}, Options={}, IPAM={})
        name = M.PROJECT + '_pgdata'
        self.objects[('volume', name)] = dict(Name=name, Driver='local', Mountpoint='/synthetic/' + name,
            CreatedAt='2026-10-22T00:00:00Z', Options=None,
            Labels={'com.docker.compose.project': M.PROJECT, 'com.docker.compose.volume': 'pgdata'})
        self.objects[('container', 'd' * 64)] = self.container('d' * 64, 'db')
        self.objects[('container', 'c' * 64)] = self.container('c' * 64, 'app', stopped=True)
        self.foreign = {'container': 'foreign-id', 'volume': 'foreign-volume', 'network': 'foreign-network'}

    def rendered_environment(self):
        # Small fixture parser, NOT the production validator. The opt-in tests
        # independently compare this fixture model against actual Compose output.
        text = (self.root / 'docker-compose.yml').read_text().split('  app:\n', 1)[1]
        text = text.split('    environment:\n', 1)[1].split('    networks:', 1)[0]
        env = M.runtime_values((self.root / '.env').read_bytes(), legacy='CLB_HQ_CHAT_ID=' not in (self.root / '.env').read_text())
        result = {}
        for line in text.splitlines():
            if not line.strip() or line.strip().startswith('#'):
                continue
            key, value = line.strip().split(': ', 1)
            value = value.strip("'")
            value = re.sub(r'\$\{([^:}]+):\?[^}]*}', lambda m: env[m[1]], value)
            result[key] = value
        return result

    def container(self, cid, service, stopped=False):
        app = service == 'app'
        result = dict(Id=cid, Image='sha256:' + ('a' if app else 'd') * 64,
            Name='/' + M.PROJECT + '-' + service + '-1', Created='2026-10-22T00:00:00Z',
            Config={'Image': self.env['CLB_APP_IMAGE' if app else 'CLB_PG_IMAGE'],
                    'User': '10001:10001' if app else '',
                    'Env': ['PATH=/synthetic'] + [k + '=' + v for k, v in self.rendered_environment().items()] if app else ['PG_MAJOR=16'],
                    'Labels': {'com.docker.compose.project': M.PROJECT, 'com.docker.compose.service': service,
                               'com.docker.compose.oneoff': 'False', 'com.docker.compose.project.working_dir': str(self.root),
                               'com.docker.compose.project.config_files': str(self.root / 'docker-compose.yml')}},
            State={'Status': 'exited' if stopped else 'running', 'Running': not stopped, 'Restarting': False,
                   'Health': {'Status': 'healthy'}, 'StartedAt': '2026-10-22T00:00:00Z',
                   'FinishedAt': '2026-10-22T00:01:00Z' if stopped else '0001-01-01T00:00:00Z'},
            RestartCount=22 if stopped else 0,
            HostConfig={'PortBindings': {'8080/tcp': [{'HostIp': '127.0.0.1', 'HostPort': '8081'}]} if app else {},
                        'ReadonlyRootfs': app, 'CapDrop': ['ALL'] if app else None, 'CapAdd': None, 'Privileged': False,
                        'SecurityOpt': ['no-new-privileges:true'], 'Binds': None, 'PidMode': '', 'IpcMode': 'private',
                        'Devices': [], 'DeviceRequests': None,
                        'Tmpfs': {'/tmp': '', '/var/cache/app': 'uid=10001,gid=10001,mode=0700'},
                        'RestartPolicy': {'Name': 'unless-stopped', 'MaximumRetryCount': 0}},
            NetworkSettings={'Networks': {M.PROJECT + '_database': {'NetworkID': 'b' * 64}}},
            Mounts=[] if app else [{'Destination': '/var/lib/postgresql/data', 'Type': 'volume', 'Name': M.PROJECT + '_pgdata'}])
        if app:
            result['NetworkSettings']['Networks'][M.PROJECT + '_application'] = {'NetworkID': 'e' * 64}
        return result

    def inspect(self, kind, name):
        if kind == 'image':
            result = super().inspect(kind, name)
            result['Config']['Labels'] = {'org.opencontainers.image.revision': M.ORIGINAL_REVISION}
            result['Config']['Env'] = ['PG_MAJOR=16'] if name == self.env['CLB_PG_IMAGE'] else ['PATH=/synthetic']
            result['Id'] = 'sha256:' + ('d' if name == self.env['CLB_PG_IMAGE'] else 'a') * 64
            return result
        return copy.deepcopy(self.objects[(kind, name)])

    def run(self, args, timeout=30):
        self.calls.append(args)
        if args == ['docker', 'compose', 'version', '--short']:
            return self.compose_version
        if args[-3:] == ['config', '--format', 'json']:
            return json.dumps({'name': M.PROJECT, 'networks': self.model_networks,
                               'services': {'db': {}, 'app': {'environment': self.rendered_environment()}}}).encode()
        if 'run' in args:
            if self.phase_failure == 'validator':
                raise M.Refused('command_failed')
            return self.validator
        if 'up' in args:
            assert args[-1] == 'app' and '--no-deps' in args and '--force-recreate' in args
            self.containers = ['d' * 64, 'f' * 64]
            self.objects.pop(('container', 'c' * 64))
            self.objects[('container', 'f' * 64)] = self.container('f' * 64, 'app')
            if self.phase_failure:
                if self.phase_failure == 'timeout':
                    raise subprocess.TimeoutExpired('synthetic', timeout)
                if self.phase_failure == 'interrupt':
                    raise KeyboardInterrupt()
                raise M.Refused(self.phase_failure)
        return b''


class RecoveryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='clb91-recovery-test-')
        self.root = Path(self.temp.name).resolve() / 'root'
        self.engine, self.target = recovery_fixture(self.root)
        self.old_markers = {p.name: (p.stat().st_ino, p.read_bytes()) for p in self.engine.state.iterdir()}
        self.old_env = (self.root / '.env').read_bytes()
        self.old_binding = self.engine.binding
        self.sleep = mock.patch.object(M.time, 'sleep')
        self.sleep_mock = self.sleep.start()

    def tearDown(self):
        self.sleep.stop()
        self.temp.cleanup()

    def recover(self):
        self.engine.recover_application(self.target, 'c' * 64, 'd' * 64)

    def assert_history(self):
        for name, value in self.old_markers.items():
            path = self.engine.state / name
            self.assertEqual((path.stat().st_ino, path.read_bytes()), value)

    def assert_no_effects(self):
        self.assertEqual((self.root / '.env').read_bytes(), self.old_env)
        self.assertFalse((self.engine.state / M.RECOVERY_ATTEMPT).exists())
        self.assertFalse(any('up' in a or 'run' in a for a in self.engine.calls))
        self.assert_history()

    def test_success_preserves_history_and_verify_uses_both_bindings(self):
        resources = self.engine.recovery_resources()
        foreign = copy.deepcopy(self.engine.foreign)
        self.recover()
        self.assert_history()
        self.assertEqual(self.engine.recovery_resources(), resources)
        self.assertEqual(self.engine.foreign, foreign)
        self.assertNotEqual(self.engine.binding, self.old_binding)
        receipt = self.engine.record(M.RECOVERY_ATTEMPT)
        self.assertEqual(receipt['old_binding'], self.old_binding)
        self.assertEqual(receipt['new_binding'], M.configuration(self.root)[1])
        self.assertEqual(receipt['old_env'].encode(), self.old_env)
        self.assertEqual((self.engine.state / M.RECOVERY_ATTEMPT).stat().st_mode & 0o777, 0o600)
        self.sleep_mock.assert_called_once_with(5)
        self.engine.verify()
        fresh = M.Install(self.root)
        fresh.run, fresh.inspect, fresh.ids = self.engine.run, self.engine.inspect, self.engine.ids
        fresh.verify()
        self.assertEqual(set(p.name for p in self.engine.state.iterdir()), set(M.ORIGINAL_MARKERS) | {M.RECOVERY_ATTEMPT, 'baseline-observed'})
        mutations = [a for a in self.engine.calls if 'up' in a or 'run' in a]
        self.assertEqual(len(mutations), 2)
        self.assertIn('/opt/app/bin/app-bot-validate-schema', mutations[0])
        self.assertEqual(mutations[1][-1], 'app')
        self.assertIn('--force-recreate', mutations[1])
        self.assertEqual(sum(a[0] == 'curl' for a in self.engine.calls), 6)
        self.assertFalse(any(a[:2] in (['docker', 'stop'], ['docker', 'rm']) or 'down' in a for a in self.engine.calls))

    def test_missing_invalid_chats_and_unrelated_values_refused_before_attempt(self):
        original = self.target.read_text()
        cases = [original.replace('CLB_HQ_CHAT_ID=-101\n', ''), original.replace('=-101', '=0'),
                 original.replace('=-101', '=9223372036854775808'), original.replace('=-101', '=not-a-number')]
        for key in M.KEYS - M.CHAT_KEYS:
            cases.append(original.replace(key + '=' + (M.ORIGINAL_REVISION if key == 'CLB_REVISION' else ENV[key]), key + '=changed'))
        for raw in cases:
            with self.subTest(case=len(raw)):
                self.target.write_text(raw)
                with self.assertRaises(M.Refused):
                    self.recover()
                self.assert_no_effects()

    def test_valid_but_different_secrets_images_and_revision_refused(self):
        original = self.target.read_text()
        for key, value in [('CLB_DB_PASSWORD', 'z' * 48), ('CLB_QR_SECRET', 'z' * 48),
                           ('CLB_PHONE_KEY', 'z' * 48), ('CLB_WEBHOOK_SECRET', 'z' * 48),
                           ('CLB_BOT_TOKEN', '999:' + 'z' * 32), ('CLB_OWNER_ID', '999'),
                           ('CLB_PG_IMAGE', 'postgres@sha256:' + 'e' * 64),
                           ('CLB_APP_IMAGE', 'ghcr.io/koteev-m/clubs_bot/app-bot@sha256:' + 'e' * 64),
                           ('CLB_REVISION', 'e' * 40), ('CLB_PLATFORM', 'linux/amd64')]:
            with self.subTest(key=key):
                self.target.write_text(re.sub('^' + key + '=.*$', key + '=' + value, original, flags=re.M))
                with self.assertRaisesRegex(M.Refused, 'recovery_config_delta'):
                    self.recover()
                self.assert_no_effects()

    def test_unproven_old_binding_and_original_marker_tampering_refused(self):
        path = self.engine.state / 'schema-compatible'
        path.write_text(json.dumps({'binding': '0' * 64}))
        with self.assertRaisesRegex(M.Refused, 'recovery_old_binding'):
            self.recover()
        self.assertFalse((self.engine.state / M.RECOVERY_ATTEMPT).exists())

    def test_published_original_template_is_pinned(self):
        self.assertEqual(M.digest(M.original_compose()), M.ORIGINAL_COMPOSE_SHA256)
        path = self.root / 'docker-compose.yml'
        original = path.read_bytes()
        for before, after in [(b'8081', b'8082'), (b'pgdata:', b'other:'), (b'networks: [database]', b'networks: [application]')]:
            path.write_bytes(original.replace(before, after))
            with self.assertRaisesRegex(M.Refused, 'template_changed'):
                M.Install(self.root, recovery=True)
        path.write_bytes(original)

    def test_wrong_or_running_app_and_changed_security_are_refused(self):
        key = ('container', 'c' * 64)
        original = copy.deepcopy(self.engine.objects[key])
        changes = [('Id', None, 'e' * 64), ('State', 'Running', True), ('State', 'Restarting', True),
                   ('State', 'Status', 'created'), ('Config', 'Image', ENV['CLB_PG_IMAGE']),
                   ('Config', 'Env', ['PRIVATE=wrong']), ('Config', 'User', '0:0'),
                   ('Config', 'Cmd', ['unexpected']), ('HostConfig', 'ReadonlyRootfs', False),
                   ('HostConfig', 'Privileged', True), ('HostConfig', 'PortBindings', {}),
                   ('NetworkSettings', 'Networks', {})]
        for outer, inner, value in changes:
            with self.subTest(field=(outer, inner)):
                self.engine.objects[key] = copy.deepcopy(original)
                if inner is None:
                    self.engine.objects[key][outer] = value
                else:
                    self.engine.objects[key][outer][inner] = value
                with self.assertRaises(M.Refused):
                    self.recover()
                self.assert_no_effects()

    def test_wrong_labels_and_foreign_project_containers_are_not_touched(self):
        self.engine.objects[('container', 'c' * 64)]['Config']['Labels']['com.docker.compose.project'] = 'foreign'
        with self.assertRaisesRegex(M.Refused, 'recovery_app_identity'):
            self.recover()
        self.assert_no_effects()

    def test_unhealthy_restarted_or_changed_database_refused(self):
        key = ('container', 'd' * 64)
        original = copy.deepcopy(self.engine.objects[key])
        for field, value in [('Id', 'e' * 64), ('RestartCount', 1), ('Image', 'sha256:' + 'e' * 64),
                             ('Mounts', []), ('NetworkSettings', {'Networks': {}}),
                             ('State', original['State'] | {'Health': {'Status': 'unhealthy'}}),
                             ('State', original['State'] | {'Restarting': True})]:
            with self.subTest(field=field):
                self.engine.objects[key] = copy.deepcopy(original)
                self.engine.objects[key][field] = value
                with self.assertRaises(M.Refused):
                    self.recover()
                self.assert_no_effects()

    def test_changed_volume_network_and_inventory_refused(self):
        for kind, name, field, value in [('volume', M.PROJECT + '_pgdata', 'Name', 'other'),
                                        ('network', M.PROJECT + '_database', 'Id', 'f' * 64),
                                        ('network', M.PROJECT + '_database', 'Internal', False)]:
            with self.subTest(kind=kind, field=field):
                original = copy.deepcopy(self.engine.objects[(kind, name)])
                self.engine.objects[(kind, name)][field] = value
                with self.assertRaises(M.Refused):
                    self.recover()
                self.assert_no_effects()
                self.engine.objects[(kind, name)] = original
        self.engine.containers.append('foreign')
        with self.assertRaisesRegex(M.Refused, 'recovery_inventory'):
            self.recover()
        self.assert_no_effects()

    def test_stopped_identity_is_observed_twice_before_attempt(self):
        def changed(_seconds):
            self.engine.objects[('container', 'c' * 64)]['RestartCount'] += 1
        self.sleep_mock.side_effect = changed
        with self.assertRaisesRegex(M.Refused, 'recovery_app_changed'):
            self.recover()
        self.assert_no_effects()

    def test_normal_application_and_second_recovery_remain_forbidden(self):
        with self.assertRaisesRegex(M.Refused, 'partial_or_repeated_install'):
            self.engine.application()
        self.recover()
        count = len(self.engine.calls)
        for operation in (self.recover, self.engine.application, self.engine.database):
            with self.assertRaises(M.Refused):
                operation()
        self.assertEqual(len(self.engine.calls), count)
        with self.assertRaises(M.Refused):
            M.Install(self.root, recovery=True)
        self.assert_history()

    def test_verify_detects_old_and_new_evidence_tampering(self):
        self.recover()
        path = self.engine.state / M.RECOVERY_ATTEMPT
        original = path.read_bytes()
        for field in ('old_binding', 'new_binding', 'old_env', 'new_env_sha256'):
            receipt = json.loads(original)
            receipt[field] = 'invalid'
            path.write_text(json.dumps(receipt))
            with self.assertRaises(M.Refused):
                self.engine.verify()
        path.write_bytes(original)
        self.engine.verify()

    def test_interruptions_at_each_file_boundary_forbid_replay(self):
        for boundary in ('attempt', '.env', 'docker-compose.yml', 'baseline-observed'):
            with self.subTest(boundary=boundary), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp).resolve() / 'root'
                e, target = recovery_fixture(root)
                mark, replace = e.mark, e.replace_recovery_file
                def interrupted_mark(name, value):
                    mark(name, value)
                    if (boundary == 'attempt' and name == M.RECOVERY_ATTEMPT) or name == boundary:
                        raise KeyboardInterrupt()
                def interrupted_replace(name, data, mode):
                    replace(name, data, mode)
                    if name == boundary:
                        raise KeyboardInterrupt()
                e.mark, e.replace_recovery_file = interrupted_mark, interrupted_replace
                with self.assertRaises(KeyboardInterrupt):
                    e.recover_application(target, 'c' * 64, 'd' * 64)
                calls = len(e.calls)
                with self.assertRaises(M.Refused):
                    e.recover_application(target, 'c' * 64, 'd' * 64)
                self.assertEqual(len(e.calls), calls)
                self.assertTrue((e.state / M.RECOVERY_ATTEMPT).exists())

    def test_docker_error_timeout_unknown_interrupt_never_replayed_or_contained(self):
        for failure in ('command_failed', 'command_timeout', 'UNKNOWN', 'timeout', 'interrupt', 'validator'):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as tmp:
                e, target = recovery_fixture(Path(tmp).resolve() / 'root')
                e.phase_failure = failure
                with self.assertRaises((M.Refused, subprocess.TimeoutExpired, KeyboardInterrupt)):
                    e.recover_application(target, 'c' * 64, 'd' * 64)
                self.assertTrue((e.state / M.RECOVERY_ATTEMPT).exists())
                self.assertFalse((e.state / 'baseline-observed').exists())
                count = len(e.calls)
                with self.assertRaises(M.Refused):
                    e.recover_application(target, 'c' * 64, 'd' * 64)
                self.assertEqual(len(e.calls), count)
                self.assertFalse(any(a[:2] in (['docker', 'stop'], ['docker', 'rm']) for a in e.calls))

    def test_schema_rejection_does_not_replace_app(self):
        self.engine.validator = b'schema-validation:v=1 result=rejected\n'
        with self.assertRaisesRegex(M.Refused, 'schema_rejected'):
            self.recover()
        self.assertEqual(self.engine.containers, ['d' * 64, 'c' * 64])
        self.assertTrue((self.engine.state / M.RECOVERY_ATTEMPT).exists())
        self.assertFalse(any('up' in a for a in self.engine.calls))
        self.assert_history()

    def test_app_restarted_after_attempt_prevents_configuration_replacement(self):
        original = self.engine.mark
        def mark(name, value):
            original(name, value)
            if name == M.RECOVERY_ATTEMPT:
                self.engine.objects[('container', 'c' * 64)]['State']['Running'] = True
        self.engine.mark = mark
        with self.assertRaisesRegex(M.Refused, 'recovery_app_not_stopped'):
            self.recover()
        self.assertEqual((self.root / '.env').read_bytes(), self.old_env)
        self.assertTrue((self.engine.state / M.RECOVERY_ATTEMPT).exists())
        self.assertFalse(any('run' in a or 'up' in a for a in self.engine.calls))

    def test_db_change_during_validation_prevents_app_replacement(self):
        original = self.engine.run
        def run(args, timeout=30):
            result = original(args, timeout)
            if 'run' in args:
                self.engine.objects[('container', 'd' * 64)]['State']['StartedAt'] = 'changed'
            return result
        self.engine.run = run
        with self.assertRaisesRegex(M.Refused, 'recovery_resources_changed'):
            self.recover()
        self.assertFalse(any('up' in a for a in self.engine.calls))
        self.assert_history()

    def test_unhealthy_or_restarting_replacement_never_gets_baseline(self):
        original = self.engine.run
        def run(args, timeout=30):
            result = original(args, timeout)
            if 'up' in args:
                self.engine.objects[('container', 'f' * 64)]['RestartCount'] = 1
            return result
        self.engine.run = run
        with self.assertRaisesRegex(M.Refused, 'baseline_health'):
            self.recover()
        self.assertFalse((self.engine.state / 'baseline-observed').exists())
        self.assertTrue((self.engine.state / M.RECOVERY_ATTEMPT).exists())
        self.assertFalse(any(a[:2] == ['docker', 'stop'] for a in self.engine.calls))

    def test_partial_receipt_and_unknown_marker_cannot_be_replayed(self):
        path = self.engine.state / M.RECOVERY_ATTEMPT
        path.write_bytes(b'{')
        path.chmod(0o600)
        with self.assertRaisesRegex(M.Refused, 'partial_or_repeated_install'):
            self.recover()
        self.assertEqual(self.engine.calls, [])
        self.assertEqual((self.root / '.env').read_bytes(), self.old_env)

    def assert_network_refusal_without_effects(self):
        db = copy.deepcopy(self.engine.objects[('container', 'd' * 64)])
        volume = copy.deepcopy(self.engine.objects[('volume', M.PROJECT + '_pgdata')])
        inventory = (list(self.engine.containers), list(self.engine.networks), list(self.engine.volumes))
        with self.assertRaises(M.Refused):
            self.recover()
        self.assert_no_effects()
        self.assertEqual(db, self.engine.objects[('container', 'd' * 64)])
        self.assertEqual(volume, self.engine.objects[('volume', M.PROJECT + '_pgdata')])
        self.assertEqual(inventory, (self.engine.containers, self.engine.networks, self.engine.volumes))
        self.assertEqual(set(p.name for p in self.engine.state.iterdir()), set(M.ORIGINAL_MARKERS))
        self.assertFalse(any(token in a for a in self.engine.calls for token in ('run', 'up', 'stop', 'rm', 'down', '--force-recreate')))

    def test_network_hash_wrong_empty_missing_and_swapped_on_each_network(self):
        for suffix, expected in NETWORK_HASHES_2403.items():
            labels = self.engine.objects[('network', M.PROJECT + '_' + suffix)]['Labels']
            other = NETWORK_HASHES_2403['database' if suffix == 'application' else 'application']
            for value in ('0' * 64, '', None, 'invalid', other):
                with self.subTest(network=suffix, value=value):
                    if value is None:
                        labels.pop('com.docker.compose.config-hash', None)
                    else:
                        labels['com.docker.compose.config-hash'] = value
                    self.assert_network_refusal_without_effects()
            labels['com.docker.compose.config-hash'] = expected

    def test_network_labels_and_full_id_required_for_each_network(self):
        for suffix in NETWORK_HASHES_2403:
            item = self.engine.objects[('network', M.PROJECT + '_' + suffix)]
            original = copy.deepcopy(item)
            for label in ('project', 'network', 'version'):
                with self.subTest(network=suffix, label=label):
                    item['Labels']['com.docker.compose.' + label] = 'wrong'
                    self.assert_network_refusal_without_effects()
                    item.update(copy.deepcopy(original))
            item['Id'] = item['Id'][:12]
            self.assert_network_refusal_without_effects()
            item.update(original)

    def test_network_hash_does_not_accept_other_compose_versions(self):
        for version in (b'5.5.1\n', b'2.40.2\n', b'2.40.4\n', b'2.40.3-custom\n', b'', b'2.40.3\nextra\n'):
            with self.subTest(version=version):
                self.engine.compose_version = version
                self.assert_network_refusal_without_effects()

    def test_network_hash_model_must_be_exact_and_supported(self):
        original = copy.deepcopy(self.engine.model_networks)
        for suffix in NETWORK_HASHES_2403:
            for key, value in [('name', 'other'), ('driver', 'bridge'), ('internal', False),
                               ('ipam', {'driver': 'default'}), ('labels', {'unreviewed': 'label'}),
                               ('enable_ipv6', True), ('external', True)]:
                if suffix == 'application' and key == 'internal':
                    value = True
                with self.subTest(network=suffix, field=key):
                    self.engine.model_networks = copy.deepcopy(original)
                    self.engine.model_networks[suffix][key] = value
                    self.assert_network_refusal_without_effects()
        self.engine.model_networks = copy.deepcopy(original)
        self.engine.model_networks['extra'] = {'name': 'extra'}
        self.assert_network_refusal_without_effects()

    def test_expected_network_hashes_match_independent_compose_2403_oracle(self):
        expected = {M.PROJECT + '_' + key: value for key, value in NETWORK_HASHES_2403.items()}
        self.assertEqual(self.engine.recovery_network_hashes(), expected)
        self.recover()
        self.assertEqual(self.engine.recovery_network_hashes(), expected)
        self.engine.verify()


@unittest.skipUnless(os.environ.get('CLB91_COMPOSE_2403_BINARY'), 'opt-in official Compose 2.40.3 binary')
class Compose2403NetworkTest(unittest.TestCase):
    """Actual Compose code, disposable HTTP Engine fixture; NEVER a real Docker socket.

    NetworkCreate is recorded in memory. Container creation and network deletion
    are always denied. This exercises reconciliation/hash serialization without
    touching any existing Engine or installing/replacing the local Compose plugin.
    """
    def setUp(self):
        self.binary = Path(os.environ['CLB91_COMPOSE_2403_BINARY']).resolve()
        # Published docker/compose v2.40.3 release asset digests (GitHub API).
        self.assertIn(M.digest(self.binary.read_bytes()), (
            '8cd7eb5f95bacb536cc407111662e2c205d67d9abfea5dcb8400be8418db60d1',  # darwin-aarch64
            'd26373b19e89160546d15407516cc59f453030d9bc5b43ba7faf16f7b4980137',  # linux-aarch64
        ))
        self.temp = tempfile.TemporaryDirectory(prefix='clb91-compose-2403-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name).resolve() / 'root'
        self.engine, self.target = recovery_fixture(self.root)
        self.requests, self.created, self.networks = [], {}, {}
        fixture_case = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass

            def respond(self):
                path = urllib.parse.urlsplit(self.path).path
                if path.startswith('/v1.'):
                    path = '/' + path.split('/', 2)[2]
                body = json.loads(self.rfile.read(int(self.headers.get('Content-Length', '0'))) or b'{}')
                fixture_case.requests.append((self.command, path))
                status, output = 200, {}
                if path == '/_ping':
                    output = None
                elif path == '/version':
                    output = {'ApiVersion': '1.51', 'MinAPIVersion': '1.24', 'Os': 'linux', 'Arch': 'arm64'}
                elif path == '/info':
                    output = {'ID': 'synthetic', 'ServerVersion': '28.5.1', 'OSType': 'linux',
                              'Architecture': 'aarch64', 'Swarm': {'LocalNodeState': 'inactive'}}
                elif path.startswith('/images/') and path.endswith('/json') and self.command == 'GET':
                    output = {'Id': 'sha256:' + 'a' * 64, 'Os': 'linux', 'Architecture': 'arm64', 'Size': 1,
                              'Config': {'Env': [], 'Labels': {}, 'Cmd': [], 'Entrypoint': []},
                              'RepoTags': [], 'RepoDigests': []}
                elif path in ('/containers/json', '/networks') and self.command == 'GET':
                    output = []
                elif path.startswith('/networks/') and self.command == 'GET':
                    output = fixture_case.networks.get(path[len('/networks/'):])
                    if output is None:
                        status, output = 404, {'message': 'synthetic missing network'}
                elif path == '/networks/create' and self.command == 'POST':
                    name = body['Name']
                    fixture_case.created[name] = body
                    output = {'Id': ('b' if name.endswith('_database') else 'e') * 64, 'Warning': ''}
                    status = 201
                elif path == '/volumes/' + M.PROJECT + '_pgdata' and self.command == 'GET':
                    output = fixture_case.engine.inspect('volume', M.PROJECT + '_pgdata')
                else:
                    # A nonzero CLI result at this boundary is EXPECTED: no
                    # container or database ever exists behind this mock API.
                    status, output = 403, {'message': 'synthetic mutation boundary'}
                self.send_response(status)
                self.send_header('Content-Type', 'application/json')
                self.send_header('API-Version', '1.51')
                self.send_header('OSType', 'linux')
                self.end_headers()
                if self.command != 'HEAD':
                    self.wfile.write(b'OK' if output is None else json.dumps(output).encode())

            do_HEAD = do_GET = do_POST = do_DELETE = respond

        self.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.close_server)
        self.env = {'PATH': os.environ['PATH'], 'HOME': self.temp.name,
                    'DOCKER_HOST': 'tcp://127.0.0.1:' + str(self.server.server_port)}
        self.assertEqual(self.command(['version', '--short']).stdout, b'2.40.3\n')

    def close_server(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)
        self.assertFalse(self.thread.is_alive())

    def command(self, args):
        return subprocess.run([str(self.binary), *args], env=self.env,
                              capture_output=True, timeout=20)

    def reconcile(self):
        result = self.command(self.engine.compose[2:] + ['run', '--rm', '--no-deps', '--pull', 'never',
                                                       '--entrypoint', '/synthetic', 'app'])
        self.assertNotEqual(result.returncode, 0, 'fixture must stop at its denied mutation boundary')

    def test_exact_binary_model_and_networkcreate_hashes_before_and_after_fix(self):
        original_run = self.engine.run
        def run(args, timeout=30):
            if args[:2] == ['docker', 'compose']:
                result = self.command(args[2:])
                self.assertEqual(result.returncode, 0, 'read-only Compose command must succeed')
                return result.stdout
            return original_run(args, timeout)
        self.engine.run = run
        for corrected in (False, True):
            with self.subTest(corrected=corrected):
                if corrected:
                    (self.root / '.env').write_bytes(self.target.read_bytes())
                    (self.root / 'docker-compose.yml').write_bytes((M.TEMPLATE / 'docker-compose.yml').read_bytes())
                self.requests.clear()
                expected = self.engine.recovery_network_hashes()
                self.assertFalse(any(method in ('POST', 'DELETE') for method, _ in self.requests))
                self.reconcile()
                self.assertEqual(set(self.created), set(expected))
                for name, network in self.created.items():
                    self.assertEqual(network['Labels']['com.docker.compose.config-hash'], expected[name])
                    suffix = name.removeprefix(M.PROJECT + '_')
                    self.assertEqual(expected[name], NETWORK_HASHES_2403[suffix])
                    self.assertEqual(network['Labels']['com.docker.compose.version'], '2.40.3')
                self.assertIn(('POST', '/containers/create'), self.requests)

    def test_exact_binary_matching_hashes_avoid_network_reconciliation_mutations(self):
        self.networks = {M.PROJECT + '_' + key: self.engine.inspect('network', M.PROJECT + '_' + key)
                         for key in NETWORK_HASHES_2403}
        self.reconcile()
        self.assertEqual([r for r in self.requests if r[0] in ('POST', 'DELETE')], [('POST', '/containers/create')])

    def test_exact_binary_wrong_hash_can_remove_either_network_even_with_no_deps(self):
        for suffix in NETWORK_HASHES_2403:
            with self.subTest(network=suffix):
                self.networks = {M.PROJECT + '_' + key: self.engine.inspect('network', M.PROJECT + '_' + key)
                                 for key in NETWORK_HASHES_2403}
                name = M.PROJECT + '_' + suffix
                self.networks[name]['Labels']['com.docker.compose.config-hash'] = '0' * 64
                self.requests.clear()
                self.reconcile()
                self.assertEqual([r for r in self.requests if r[0] in ('POST', 'DELETE')], [('DELETE', '/networks/' + name)])


@unittest.skipUnless(os.environ.get('CLB91_COMPOSE_TEST') == '1', 'opt-in local Compose/JVM contract')
class ComposeRuntimeTest(unittest.TestCase):
    """Real Compose rendering; optional packaged AppConfig, no DB or integrations."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='clb91-compose-runtime-')
        self.root = Path(self.temp.name).resolve() / 'root'
        fixture(self.root)

    def tearDown(self):
        self.temp.cleanup()

    def render(self):
        # Explicit file and synthetic environment; no daemon needed by `config`.
        command = ['docker', 'compose', '--project-directory', str(self.root),
                   '--env-file', str(self.root / '.env'), '-f', str(self.root / 'docker-compose.yml'),
                   'config', '--format', 'json']
        clean_env = {k: os.environ[k] for k in ('PATH', 'HOME') if k in os.environ}
        result = subprocess.run(command, env=clean_env, capture_output=True, timeout=20)
        self.assertEqual(result.returncode, 0, 'synthetic Compose rendering failed')
        return json.loads(result.stdout)

    def test_recovery_old_new_compose_delta_is_exactly_five_environment_inputs(self):
        new = self.render()
        (self.root / 'docker-compose.yml').write_bytes(M.original_compose())
        old = self.render()
        expected = copy.deepcopy(old)
        expected['services']['app']['environment'].update({runtime: ENV[private] for runtime, private in CHAT_INPUTS.items()})
        self.assertEqual(new, expected)
        # Independently validate the synthetic Engine's model used by recovery tests.
        with tempfile.TemporaryDirectory() as tmp:
            engine, target = recovery_fixture(Path(tmp).resolve() / 'root')
            self.assertEqual(engine.rendered_environment(), old['services']['app']['environment'])
            (engine.root / '.env').write_bytes(target.read_bytes())
            (engine.root / 'docker-compose.yml').write_bytes((M.TEMPLATE / 'docker-compose.yml').read_bytes())
            self.assertEqual(engine.rendered_environment(), new['services']['app']['environment'])

    def test_rendered_contract_and_database_isolation(self):
        config = self.render()
        self.assertEqual(set(config['services']), {'db', 'app'})
        db, app = config['services']['db'], config['services']['app']
        self.assertEqual(db['image'], ENV['CLB_PG_IMAGE'])
        self.assertFalse(db.get('ports'))
        self.assertEqual(set(db['networks']), {'database'})
        self.assertEqual(db['environment'], {'POSTGRES_DB': 'clubs', 'POSTGRES_USER': 'postgres',
                         'POSTGRES_PASSWORD_FILE': '/run/secrets/postgres_password',
                         'CLB_INIT_PASSWORD': ENV['CLB_DB_PASSWORD'], 'POSTGRES_INITDB_ARGS': '--auth-host=scram-sha-256'})
        self.assertEqual([(v['type'], v['source'], v['target']) for v in db['volumes']],
                         [('volume', 'pgdata', '/var/lib/postgresql/data'),
                          ('bind', str(self.root / '10-clubs-role.sql'), '/docker-entrypoint-initdb.d/10-clubs-role.sql')])
        self.assertTrue(db['volumes'][1]['read_only'])
        self.assertEqual(set(config['volumes']), {'pgdata'})
        self.assertEqual(config['volumes']['pgdata']['name'], 'clubs-bot-stage_pgdata')
        self.assertTrue(config['networks']['database']['internal'])
        self.assertEqual(set(config['networks']), {'database', 'application'})
        self.assertEqual(set(app['networks']), {'database', 'application'})
        self.assertEqual(len(app['ports']), 1)
        port = app['ports'][0]
        self.assertEqual((port['host_ip'], str(port['published']), port['target']), ('127.0.0.1', '8081', 8080))
        self.assertTrue(app['read_only'])
        self.assertEqual(app['user'], '10001:10001')
        self.assertEqual(app['cap_drop'], ['ALL'])
        self.assertEqual(app['security_opt'], ['no-new-privileges:true'])
        self.assertFalse(app.get('volumes'))
        self.assertFalse(app.get('secrets'))
        for runtime, private in CHAT_INPUTS.items():
            self.assertEqual(app['environment'][runtime], ENV[private])
        for key in ('TELEGRAM_USE_POLLING', 'NOTIFICATIONS_ENABLED', 'USE_NOTIFY_SENDER',
                    'OPS_NOTIFY_ENABLED', 'REFUND_WORKER_ENABLED', 'FLYWAY_BASELINE_ON_MIGRATE',
                    'FLYWAY_OUT_OF_ORDER'):
            self.assertEqual(app['environment'][key], 'false')
        self.assertEqual(app['environment']['FLYWAY_MODE'], 'validate')

    @unittest.skipUnless(os.environ.get('CLB91_RUNTIME_IMAGE'), 'requires an already-present local Clubs image')
    def test_actual_packaged_appconfig_and_nonfatal_file_appender_failure(self):
        # Synthetic reflection probe: no Application.module(), sockets, DB or real config.
        context = subprocess.run(['docker', 'context', 'show'], capture_output=True, text=True, check=True).stdout.strip()
        endpoint = subprocess.run(['docker', 'context', 'inspect', context, '--format', '{{.Endpoints.docker.Host}}'],
                                  capture_output=True, text=True, check=True).stdout.strip()
        self.assertEqual(context, 'desktop-linux')
        self.assertTrue(endpoint.startswith('unix://'))
        self.assertFalse(os.environ.get('DOCKER_HOST'))
        image = os.environ['CLB91_RUNTIME_IMAGE']
        self.assertRegex(image, r'^sha256:[0-9a-f]{64}$')
        probe = self.root.parent / 'RuntimeContractProbe.java'
        probe.write_text('''
public class RuntimeContractProbe {
    public static void main(String[] args) throws Exception {
        Class<?> configType = Class.forName("com.example.bot.config.AppConfig");
        Object companion = configType.getField("Companion").get(null);
        Object config = companion.getClass().getMethod("fromEnv").invoke(companion);
        if (!configType.getMethod("getProfile").invoke(config).toString().equals("STAGE")) throw new AssertionError();
        if (!configType.getMethod("getRunMode").invoke(config).toString().equals("WEBHOOK")) throw new AssertionError();
        Object hq = configType.getMethod("getHq").invoke(config);
        if (!hq.getClass().getMethod("getChatId").invoke(hq).equals(Long.valueOf(System.getenv("HQ_CHAT_ID")))) throw new AssertionError();
        java.util.List<?> clubs = (java.util.List<?>) configType.getMethod("getClubs").invoke(config);
        if (clubs.size() != 4) throw new AssertionError();
        for (int i = 0; i < clubs.size(); i++) {
            Object club = clubs.get(i);
            if (!club.getClass().getMethod("getChatId").invoke(club).equals(Long.valueOf(System.getenv("CLUB" + (i + 1) + "_CHAT_ID")))) throw new AssertionError();
        }
        Class<?> factory = Class.forName("org.slf4j.LoggerFactory");
        Object logger = factory.getMethod("getLogger", String.class).invoke(null, "ROOT");
        Object file = logger.getClass().getMethod("getAppender", String.class).invoke(logger, "FILE");
        Object console = logger.getClass().getMethod("getAppender", String.class).invoke(logger, "CONSOLE");
        if ((Boolean) file.getClass().getMethod("isStarted").invoke(file)) throw new AssertionError();
        if (!(Boolean) console.getClass().getMethod("isStarted").invoke(console)) throw new AssertionError();
        System.out.println("runtime-contract:AppConfig=PASS FILE=not-started CONSOLE=started");
    }
}
''')
        result = subprocess.run(['javac', '--release', '21', str(probe)], capture_output=True, timeout=30)
        self.assertEqual(result.returncode, 0, 'synthetic JVM probe compilation failed')
        self.root.parent.chmod(0o755)
        (self.root.parent / 'RuntimeContractProbe.class').chmod(0o644)
        env = self.render()['services']['app']['environment']
        # Pin local context and deny all network access; the source tree is not mounted.
        command = ['docker', '--context', context, 'run', '--rm', '--pull', 'never', '--network', 'none',
                   '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
                   '--user', '10001:10001', '--memory', '256m', '--pids-limit', '64',
                   '--tmpfs', '/tmp:rw,nosuid,nodev,size=32m', '--workdir', '/opt/app',
                   '--mount', 'type=bind,src=' + str(self.root.parent) + ',dst=/probe,readonly']
        for key, value in env.items():
            command += ['--env', key + '=' + value]
        command += ['--entrypoint', '/opt/java/openjdk/bin/java', image, '-cp', '/opt/app/lib/*:/probe', 'RuntimeContractProbe']
        result = subprocess.run(command, capture_output=True, timeout=45)
        output = result.stdout + result.stderr
        self.assertEqual(result.returncode, 0, 'isolated runtime contract probe failed')
        self.assertIn(b'java.io.FileNotFoundException: logs/app.log', output)
        self.assertIn(b'runtime-contract:AppConfig=PASS FILE=not-started CONSOLE=started', output)


class SubprocessBoundaryTest(unittest.TestCase):
    """Real child processes and pipes, using Install.run rather than Engine.run."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='clb91-subprocess-')
        self.root = Path(self.temp.name).resolve() / 'root'
        fixture(self.root)
        self.install = M.Install(self.root)

    def tearDown(self):
        self.temp.cleanup()

    def command(self, source):
        return [sys.executable, '-I', '-S', '-B', '-c', source]

    def run_child(self, source, timeout=5):
        handlers = {s: signal.getsignal(s) for s in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP)}
        try:
            return self.install.run(self.command(source), timeout)
        finally:
            self.assertEqual(handlers, {s: signal.getsignal(s) for s in handlers})

    def test_small_success_and_nonzero_refusal(self):
        self.assertEqual(self.run_child("import os; os.write(1,b'ok'); os.write(2,b'private')"), b'ok')
        with self.assertRaisesRegex(M.Refused, '^command_failed$'):
            self.run_child("import os; os.write(2,b'private-secret'); raise SystemExit(7)")

    def test_each_stream_bound_and_exact_stderr_reproduction(self):
        self.assertEqual((M.STDOUT_LIMIT, M.STDERR_LIMIT), (1_048_576, 1_048_576))
        for fd, limit in ((1, M.STDOUT_LIMIT), (2, M.STDERR_LIMIT)):
            with self.subTest(fd=fd):
                source = f'import os; data=b"s"*{limit};\nwhile data: data=data[os.write({fd},data):]'
                self.assertEqual(self.run_child(source), b's' * limit if fd == 1 else b'')
                # Exactly 1,048,577 bytes of stderr previously succeeded.
                with self.assertRaisesRegex(M.Refused, '^output_limit$'):
                    self.run_child(source + f'\nos.write({fd},b"x")')

    def test_simultaneous_full_streams_do_not_deadlock(self):
        source = f'''
import os, threading
def write(fd, limit):
    data = b'x' * limit
    while data:
        data = data[os.write(fd, data):]
threads = [threading.Thread(target=write, args=pair) for pair in [(1,{M.STDOUT_LIMIT}), (2,{M.STDERR_LIMIT})]]
for thread in threads: thread.start()
for thread in threads: thread.join()
'''
        self.assertEqual(self.run_child(source), b'x' * M.STDOUT_LIMIT)
        with self.assertRaisesRegex(M.Refused, '^output_limit$'):
            self.run_child(source.replace(f'(2,{M.STDERR_LIMIT})', f'(2,{M.STDERR_LIMIT + 1})'))

    def assert_not_running(self, pids):
        deadline = time.monotonic() + 3
        while True:
            alive = []
            for pid in pids:
                result = subprocess.run(['ps', '-p', str(pid), '-o', 'stat='], capture_output=True)
                state = result.stdout.strip()
                # Orphaned killed descendants may await the host init's reaping;
                # zombies cannot execute or hold open our pipes.
                if state and not state.startswith(b'Z'):
                    alive.append(pid)
            if not alive or time.monotonic() >= deadline:
                self.assertEqual(alive, [])
                return
            time.sleep(0.02)

    def tree_source(self, action, close_pipes=False):
        # Both descendants stay in the dedicated group, including when their
        # leader exits first. They ignore TERM to require unconditional cleanup.
        pids = self.root.parent / 'pids'
        source = f'''
import os, signal, time
from pathlib import Path
signal.signal(signal.SIGTERM, signal.SIG_IGN)
child = os.fork()
if child == 0:
    grandchild = os.fork()
    if grandchild == 0:
        if {close_pipes!r}:
            os.close(1); os.close(2)
        time.sleep(30)
        os._exit(0)
    record = Path({str(pids) + '.tmp'!r})
    record.write_text(str(os.getppid())+' '+str(os.getpid())+' '+str(grandchild))
    record.rename({str(pids)!r})
    if {close_pipes!r}:
        os.close(1); os.close(2)
    time.sleep(30)
    os._exit(0)
while not Path({str(pids)!r}).exists(): time.sleep(0.005)
{action}
'''
        return source, pids

    def test_timeout_kills_group_with_both_open_and_closed_pipes(self):
        for close_pipes in (False, True):
            with self.subTest(close_pipes=close_pipes):
                source, pids = self.tree_source('os.close(1); os.close(2); time.sleep(30)', close_pipes)
                with self.assertRaisesRegex(M.Refused, '^command_timeout$'):
                    self.run_child(source, timeout=0.5)
                self.assert_not_running([int(p) for p in pids.read_text().split()])
                pids.unlink()

    def test_interrupt_signals_kill_group(self):
        for signum in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
            with self.subTest(signum=signum):
                source, pids = self.tree_source(f'os.kill(os.getppid(), {int(signum)}); time.sleep(30)')
                with self.assertRaisesRegex(M.Refused, '^interrupted$'):
                    self.run_child(source)
                self.assert_not_running([int(p) for p in pids.read_text().split()])
                pids.unlink()

    def test_overflow_kills_group_during_reading(self):
        for fd in (1, 2):
            with self.subTest(fd=fd):
                source, pids = self.tree_source(f'while True: os.write({fd}, b"private-canary" * 4096)')
                start = time.monotonic()
                with self.assertRaisesRegex(M.Refused, '^output_limit$'):
                    self.run_child(source)
                self.assertLess(time.monotonic() - start, 3)
                self.assert_not_running([int(p) for p in pids.read_text().split()])
                pids.unlink()

    def test_exited_leader_does_not_leave_descendants(self):
        for close_pipes in (False, True):
            with self.subTest(close_pipes=close_pipes):
                source, pids = self.tree_source('os._exit(0)', close_pipes)
                if close_pipes:
                    self.assertEqual(self.run_child(source), b'')
                else:
                    with self.assertRaisesRegex(M.Refused, '^command_timeout$'):
                        self.run_child(source, timeout=0.5)
                self.assert_not_running([int(p) for p in pids.read_text().split()])
                pids.unlink()

    def test_phase_deadline_prevents_spawn(self):
        self.install.deadline = time.monotonic() - 1
        with mock.patch.object(M.subprocess, 'Popen', side_effect=AssertionError('must not spawn')):
            with self.assertRaisesRegex(M.Refused, '^phase_deadline$'):
                self.run_child('pass')

    def test_unsupported_process_semantics_refuse_before_spawn(self):
        with mock.patch.object(M.sys, 'platform', 'darwin'), \
                mock.patch.object(M.subprocess, 'Popen', side_effect=AssertionError('must not spawn')):
            with self.assertRaisesRegex(M.Refused, '^command_platform$'):
                self.run_child('pass')

    def test_real_overflow_and_timeout_never_start_app_or_allow_retry(self):
        for action, reason in ((f'os.write(1,b"s"*{M.STDOUT_LIMIT+1})', 'output_limit'),
                               (f'os.write(2,b"s"*{M.STDERR_LIMIT+1})', 'output_limit'),
                               ('time.sleep(30)', 'command_timeout'),
                               ('os.kill(os.getppid(), signal.SIGTERM); time.sleep(30)', 'interrupted')):
            with self.subTest(reason=reason), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp).resolve() / 'root'
                fixture(root)
                engine = Engine(root)
                engine.database()
                fake_run = engine.run
                def run(args, timeout=30):
                    if '/opt/app/bin/app-bot-validate-schema' in args:
                        return M.Install.run(engine, self.command('import os, time, signal; ' + action), 0.5)
                    return fake_run(args, timeout)
                engine.run = run
                with self.assertRaisesRegex(M.Refused, '^' + reason + '$'):
                    engine.application()
                self.assertFalse(any('up' in a and a[-1] == 'app' for a in engine.calls))
                self.assertEqual(engine.containers, ['d' * 64])
                self.assertEqual({p.name for p in engine.state.iterdir()},
                                 {'database-attempt', 'database-ready', 'application-attempt'})
                with self.assertRaisesRegex(M.Refused, '^partial_or_repeated_install$'):
                    engine.application()

    def test_real_app_start_overflow_is_unknown_without_automatic_stop(self):
        engine = Engine(self.root)
        engine.database()
        fake_run = engine.run

        def run(args, timeout=30):
            result = fake_run(args, timeout)
            if 'up' in args and args[-1] == 'app':
                return M.Install.run(engine, self.command(f'import os; os.write(2,b"s"*{M.STDERR_LIMIT+1})'))
            return result

        engine.run = run
        with self.assertRaisesRegex(M.Refused, '^output_limit$'):
            engine.application()
        self.assertFalse(engine.stopped)
        self.assertEqual([a for a in engine.calls if a[:2] == ['docker', 'stop']], [])
        self.assertFalse((engine.state / 'baseline-observed').exists())

    def test_cli_rejects_raw_paths_and_never_publishes_private_output(self):
        import shutil
        bundle = self.root.parent / 'bundle'
        shutil.copytree(M.TEMPLATE, bundle / 'aeza-initial')
        lockfile = self.root.parent / 'shared.lock'
        lockfile.touch(mode=0o660)
        lockfile.chmod(0o660)
        (bundle / 'shared_staging_flock.py').write_text(
            (M.TEMPLATE.parent / 'shared_staging_flock.py').read_text().replace(
                '/run/lock/shared-staging-docker.lock', str(lockfile)).replace(
                    'LOCK_OWNER_UID = 0', f'LOCK_OWNER_UID = {os.getuid()}'))
        bin_dir = self.root.parent / 'bin'
        bin_dir.mkdir()
        docker = bin_dir / 'docker'
        self.install.child_env['PATH'] = str(bin_dir)
        for source, reason in ((f'os.write(1,b"secret-canary"); os.write(2,b"s"*{M.STDERR_LIMIT+1})', 'output_limit'),
                               ('os.write(2,b"private-error-secret"); raise SystemExit(7)', 'command_failed'),
                               ('os.kill(os.getppid(), signal.SIGINT); time.sleep(30)', 'interrupted')):
            with self.subTest(reason=reason):
                docker.write_text('#!' + sys.executable + '\nimport os, time, signal\n' + source + '\n')
                docker.chmod(0o700)
                command = [sys.executable, '-I', '-S', '-B', str(bundle / 'aeza-initial/first-install.py'),
                           'check', '--root', str(self.root)]
                with tempfile.TemporaryFile() as out, tempfile.TemporaryFile() as err:
                    result = subprocess.run(command, env=self.install.child_env, stdout=out, stderr=err, timeout=5)
                    self.assertEqual(result.returncode, 1)
                    self.assertLess(out.tell(), 128)
                    self.assertEqual(err.tell(), 0)
                    out.seek(0)
                    self.assertEqual(out.read(128), ('first-install:check:REFUSED:' + reason + '\n').encode())
        # argparse must not convert the string to Path before admission.
        for value in (str(self.root) + '//', str(self.root) + '/.', str(self.root) + '/../root'):
            command = [sys.executable, '-I', '-S', '-B', str(bundle / 'aeza-initial/first-install.py'),
                       'database', '--root', value]
            result = subprocess.run(command, env=self.install.child_env, capture_output=True, timeout=5)
            self.assertEqual(result.returncode, 1)
            self.assertEqual(result.stdout, b'first-install:database:REFUSED:root_path\n')
            self.assertEqual(result.stderr, b'')
            self.assertFalse(self.install.state.exists())



if __name__ == '__main__':
    unittest.main()
