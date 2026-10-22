#!/usr/bin/env python3
"""Bounded first-install recipe, NOT a privileged/restricted executor.

Use only after separate authority for the target root/Engine has been granted.
No SSH, restore, migration, pull, ingress, sudo, release-state or automatic recovery.
"""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import selectors
import signal
import stat
import subprocess
import sys
import time

# Resolve only the reviewed sibling bundle; isolated Python ignores PYTHONPATH.
_lock_spec = importlib.util.spec_from_file_location(
    'shared_staging_flock', Path(__file__).resolve().parents[1] / 'shared_staging_flock.py')
_lock_module = importlib.util.module_from_spec(_lock_spec)
_lock_spec.loader.exec_module(_lock_module)

PROJECT = 'clubs-bot-stage'
TEMPLATE = Path(__file__).resolve().parent
STDOUT_LIMIT = 1024 * 1024
STDERR_LIMIT = 1024 * 1024
CHAT_KEYS = {'CLB_HQ_CHAT_ID', 'CLB_CLUB1_CHAT_ID', 'CLB_CLUB2_CHAT_ID',
             'CLB_CLUB3_CHAT_ID', 'CLB_CLUB4_CHAT_ID'}
KEYS = {'CLB_PG_IMAGE', 'CLB_PLATFORM', 'CLB_APP_IMAGE', 'CLB_REVISION', 'CLB_DB_PASSWORD',
        'CLB_BOT_TOKEN', 'CLB_WEBHOOK_SECRET', 'CLB_OWNER_ID', 'CLB_QR_SECRET', 'CLB_PHONE_KEY'} | CHAT_KEYS
ORIGINAL_REVISION = '77e0635a4e43df03eb1bbec7acf91bf832966aa3'
ORIGINAL_COMPOSE_SHA256 = '204807d80a65980a043ce0bf367ed2d4dc4db185a0a2877f4d855b00f7cc8fcd'
ORIGINAL_SQL_SHA256 = '333da4091f5a3b8c20441f69ce478687f11650f5ddcae44dd4be4d00891dab21'
ORIGINAL_MARKERS = ('database-attempt', 'database-ready', 'application-attempt', 'schema-compatible')
RECOVERY_ATTEMPT = 'application-recovery-attempt'
RECOVERY_COMPOSE_VERSION = '2.40.3'


class Refused(Exception):
    pass


def require(value, reason):
    if not value:
        raise Refused(reason)


def read_regular(path, private=False):
    info = path.lstat()
    require(stat.S_ISREG(info.st_mode) and info.st_uid == os.getuid(), 'file_identity')
    require(stat.S_IMODE(info.st_mode) in ((0o600,) if private else (0o600, 0o644)), 'file_mode')
    require(info.st_size <= 65536, 'file_size')
    return path.read_bytes()


def compose_root(value):
    # Match remote-compose-release.sh's validate_compose_path before Path can
    # normalize repeated separators, trailing separators or dot components.
    raw = os.fspath(value)
    require(re.fullmatch(r'/[A-Za-z0-9._/-]+', raw)
            and all(part not in ('', '.', '..') for part in raw[1:].split('/'))
            and raw == os.path.realpath(raw), 'root_path')
    root = Path(raw)
    require(all(not p.is_symlink() for p in (root, *root.parents)), 'root_path')
    return root


def digest(data):
    return hashlib.sha256(data).hexdigest()


def original_compose():
    # Reconstruct ONLY the five-line fix; the published hash proves all other
    # bytes, including ports, storage and security controls, stayed unchanged.
    data = (TEMPLATE / 'docker-compose.yml').read_bytes()
    for key in sorted(CHAT_KEYS):
        line = ('      ' + key.removeprefix('CLB_') + ': ${' + key + ':?required}\n').encode()
        require(data.count(line) == 1, 'recovery_template')
        data = data.replace(line, b'')
    require(digest(data) == ORIGINAL_COMPOSE_SHA256
            and digest((TEMPLATE / '10-clubs-role.sql').read_bytes()) == ORIGINAL_SQL_SHA256,
            'recovery_template')
    return data


def recovery_bundle():
    return {name: digest(path.read_bytes()) for name, path in (
        ('first-install.py', Path(__file__).resolve()), ('docker-compose.yml', TEMPLATE / 'docker-compose.yml'),
        ('10-clubs-role.sql', TEMPLATE / '10-clubs-role.sql'),
        ('shared_staging_flock.py', TEMPLATE.parent / 'shared_staging_flock.py'))}


def runtime_values(raw, legacy=False):
    keys = KEYS - CHAT_KEYS if legacy else KEYS
    env = {}
    for line in raw.decode().splitlines():
        if not line or line.startswith('#'):
            continue
        key, sep, value = line.partition('=')
        require(sep and key in keys and key not in env and re.fullmatch(r'[A-Za-z0-9_:/@.+=-]+', value), 'runtime_config')
        env[key] = value
    require(set(env) == keys, 'runtime_config')
    require(re.fullmatch(r'postgres@sha256:[0-9a-f]{64}', env['CLB_PG_IMAGE']), 'postgres_reference')
    require(env['CLB_PLATFORM'] in ('linux/amd64', 'linux/arm64'), 'platform')
    require(re.fullmatch(r'ghcr\.io/koteev-m/clubs_bot/[a-z0-9._/-]+@sha256:[0-9a-f]{64}', env['CLB_APP_IMAGE']), 'image_reference')
    require(re.fullmatch(r'[0-9a-f]{40}', env['CLB_REVISION']), 'revision')
    require(re.fullmatch(r'[1-9][0-9]*', env['CLB_OWNER_ID']), 'owner')
    for key in CHAT_KEYS if not legacy else ():
        # AppConfig.envLong uses Kotlin Long. No unverified zero placeholders:
        # disabled notification flags are not a complete integration boundary.
        value = env[key]
        require(re.fullmatch(r'[+-]?[0-9]{1,19}', value)
                and -(2**63) <= int(value) <= 2**63 - 1 and int(value) != 0, 'chat_id')
    require(re.fullmatch(r'[0-9]+:[A-Za-z0-9_-]{20,}', env['CLB_BOT_TOKEN']), 'token_format')
    for key in ('CLB_DB_PASSWORD', 'CLB_WEBHOOK_SECRET', 'CLB_QR_SECRET', 'CLB_PHONE_KEY'):
        require(32 <= len(env[key]) <= 256, 'secret_shape')
    require(re.fullmatch(r'[A-Za-z0-9_-]{32,64}', env['CLB_WEBHOOK_SECRET']), 'webhook_shape')
    return env


def configuration(root, legacy=False):
    root = compose_root(root)
    require(root.is_dir() and root.stat().st_uid == os.getuid(), 'root_identity')
    require(not root.stat().st_mode & 0o022, 'root_mode')
    allowed = {'docker-compose.yml', '10-clubs-role.sql', '.env', 'secrets', '.first-install'}
    require(set(p.name for p in root.iterdir()) <= allowed, 'foreign_or_release_state')
    compose = original_compose() if legacy else (TEMPLATE / 'docker-compose.yml').read_bytes()
    require(read_regular(root / 'docker-compose.yml') == compose, 'template_changed')
    require(read_regular(root / '10-clubs-role.sql') == (TEMPLATE / '10-clubs-role.sql').read_bytes(), 'template_changed')
    raw = read_regular(root / '.env', True)
    env = runtime_values(raw, legacy)
    secrets = root / 'secrets'
    require(not secrets.is_symlink() and secrets.is_dir() and secrets.stat().st_uid == os.getuid()
            and stat.S_IMODE(secrets.stat().st_mode) == 0o700, 'secrets_directory')
    require(set(p.name for p in secrets.iterdir()) == {'postgres_password'}, 'secrets_inventory')
    password = env['CLB_DB_PASSWORD']
    admin = read_regular(secrets / 'postgres_password', True).decode().rstrip('\n')
    require(32 <= len(admin) <= 256 and admin != password
            and re.fullmatch(r'[A-Za-z0-9_+=-]+', admin), 'database_credentials')
    # This hash is private local continuity evidence, never a public secret fingerprint.
    binding = digest(raw + admin.encode() + compose)
    return env, binding


class Install:
    def __init__(self, root, *, recovery=False):
        self.root = root = compose_root(root)
        self.host_lock = None
        self.deadline = time.monotonic() + 300
        self.env, self.binding = configuration(root, legacy=recovery)
        self.state = root / '.first-install'
        # The operator controls the trusted CLI/context. This is not OS confinement.
        self.child_env = {k: os.environ[k] for k in ('PATH', 'HOME') if k in os.environ}
        self.compose = ['docker', 'compose', '--project-name', PROJECT, '--project-directory', str(root),
                        '--env-file', str(root / '.env'), '-f', str(root / 'docker-compose.yml')]

    def run(self, args, timeout=30):
        # AEZA is Linux. Its waitid/group signalling semantics are required:
        # Darwin can reject killpg on a zombie-only group with EPERM, which must
        # not be ignored as if cleanup of arbitrary descendants were confirmed.
        require(sys.platform == 'linux', 'command_platform')
        if self.host_lock is not None:
            self.host_lock.check()
        remaining = self.deadline - time.monotonic()
        require(remaining > 0, 'phase_deadline')
        timeout = min(timeout, remaining)
        deadline = time.monotonic() + timeout
        output = bytearray()
        counts = {'stdout': 0, 'stderr': 0}
        limits = {'stdout': STDOUT_LIMIT, 'stderr': STDERR_LIMIT}
        interrupted = False

        def interrupt(_signum, _frame):
            # Defer raising until Popen has returned its owned PID. Also lets
            # cleanup finish if more signals arrive while killing/reaping.
            nonlocal interrupted
            interrupted = True

        handlers = {}
        process = None
        try:
            for signum in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
                handlers[signum] = signal.signal(signum, interrupt)
            with selectors.DefaultSelector() as reader:
                try:
                    process = subprocess.Popen(args, env=self.child_env, stdin=subprocess.DEVNULL,
                                               stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                               start_new_session=True, bufsize=0,
                                               pass_fds=(() if self.host_lock is None else (self.host_lock.fd,)))
                    for name, stream in (('stdout', process.stdout), ('stderr', process.stderr)):
                        os.set_blocking(stream.fileno(), False)
                        reader.register(stream, selectors.EVENT_READ, name)
                    while True:
                        require(not interrupted, 'interrupted')
                        if self.host_lock is not None:
                            self.host_lock.check()
                        remaining = deadline - time.monotonic()
                        require(remaining > 0, 'command_timeout')
                        for key, _ in reader.select(min(remaining, 0.05)):
                            name = key.data
                            # Read at most the remaining allowance plus one
                            # refusal byte; stderr is counted, never retained.
                            chunk = os.read(key.fd, min(65536, limits[name] - counts[name] + 1))
                            if not chunk:
                                reader.unregister(key.fileobj)
                                continue
                            counts[name] += len(chunk)
                            require(counts[name] <= limits[name], 'output_limit')
                            if name == 'stdout':
                                output.extend(chunk)
                        # Observe without reaping: retain the leader PID until
                        # its process group has been killed, avoiding PGID reuse.
                        exited = os.waitid(os.P_PID, process.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT)
                        if exited is not None and not reader.get_map():
                            break
                finally:
                    if process is not None:
                        try:
                            # Dedicated session/group only, including children
                            # left behind after leader exit. No raw diagnostics.
                            try:
                                os.killpg(process.pid, signal.SIGKILL)
                            except ProcessLookupError:
                                pass
                            process.wait(timeout=5)
                        except (OSError, subprocess.TimeoutExpired):
                            raise Refused('command_cleanup') from None
                        finally:
                            process.stdout.close()
                            process.stderr.close()
            require(not interrupted, 'interrupted')
            require(process.returncode == 0, 'command_failed')
            if self.host_lock is not None:
                self.host_lock.check()
            return bytes(output)
        finally:
            for signum, handler in handlers.items():
                signal.signal(signum, handler)

    def inspect(self, kind, name):
        values = json.loads(self.run(['docker', kind, 'inspect', name]))
        require(isinstance(values, list) and len(values) == 1, 'inspect_ambiguous')
        return values[0]

    def ids(self, kind):
        args = {'container': ['ps', '-aq', '--no-trunc'], 'network': ['network', 'ls', '-q', '--no-trunc'],
                'volume': ['volume', 'ls', '-q']}[kind]
        return self.run(['docker', *args, '--filter', 'label=com.docker.compose.project=' + PROJECT]).decode().split()

    def image_check(self):
        for reference in (self.env['CLB_APP_IMAGE'], self.env['CLB_PG_IMAGE']):
            image = self.inspect('image', reference)
            require(image['Os'] + '/' + image['Architecture'] == self.env['CLB_PLATFORM'], 'image_platform')
            require(reference in image['RepoDigests'], 'image_digest')
            if reference == self.env['CLB_PG_IMAGE']:
                require('PG_MAJOR=16' in image['Config']['Env'], 'postgres_version')
            if reference == self.env['CLB_APP_IMAGE']:
                require((image['Config'].get('Labels') or {}).get('org.opencontainers.image.revision') == self.env['CLB_REVISION'], 'image_revision')
                require(image['Config']['User'] == '10001:10001', 'image_user')
        self.run(self.compose + ['config', '--quiet'])

    def mark(self, name, value):
        # Exclusive file creation: any interrupted attempt requires reconciliation.
        with (self.state / name).open('x') as stream:
            os.chmod(stream.name, 0o600)
            json.dump(value, stream, sort_keys=True)
            stream.flush()
            os.fsync(stream.fileno())
        fd = os.open(self.state, os.O_RDONLY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)

    def record(self, name):
        return json.loads(read_regular(self.state / name, True))

    def check_state(self, names):
        info = self.state.lstat()
        require(stat.S_ISDIR(info.st_mode) and info.st_uid == os.getuid()
                and stat.S_IMODE(info.st_mode) == 0o700, 'state_identity')
        require(set(p.name for p in self.state.iterdir()) == set(names), 'partial_or_repeated_install')
        require(self.record('database-attempt')['binding'] == self.binding, 'configuration_changed')

    def database(self):
        require(not os.path.lexists(self.state), 'partial_or_repeated_install')
        for kind in ('container', 'network', 'volume'):
            require(not self.ids(kind), 'existing_project')
        # Refuse names even if labels were stripped or belong to someone else.
        for kind, names in (('network', [PROJECT + '_database', PROJECT + '_application']),
                            ('volume', [PROJECT + '_pgdata'])):
            listing = self.run(['docker', kind, 'ls', '--format', '{{.Name}}']).decode().splitlines()
            require(not set(names).intersection(listing), 'resource_name_collision')
        self.image_check()
        self.state.mkdir(mode=0o700)
        fd = os.open(self.root, os.O_RDONLY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)
        self.mark('database-attempt', {'binding': self.binding})
        self.run(self.compose + ['up', '-d', '--no-deps', '--pull', 'never', '--wait', '--wait-timeout', '90', 'db'], 110)
        ids = self.ids('container')
        require(len(ids) == 1, 'database_inventory')
        db = self.inspect('container', ids[0])
        self.check_db(db)
        self.mark('database-ready', {'id': db['Id'], 'networks': self.ids('network'), 'volumes': self.ids('volume')})

    def check_db(self, db):
        labels = db['Config']['Labels']
        require(labels.get('com.docker.compose.project') == PROJECT
                and labels.get('com.docker.compose.service') == 'db', 'database_identity')
        require(db['State']['Running'] and db['State']['Health']['Status'] == 'healthy', 'database_health')
        require(db['Config']['Image'] == self.env['CLB_PG_IMAGE'] and not db['HostConfig']['PortBindings'], 'database_config')
        require(set(db['NetworkSettings']['Networks']) == {PROJECT + '_database'}, 'database_network')
        network = self.inspect('network', PROJECT + '_database')
        require(network['Internal'] and network['Labels'].get('com.docker.compose.project') == PROJECT, 'database_network')
        mounts = [m for m in db['Mounts'] if m['Destination'] == '/var/lib/postgresql/data']
        require(len(mounts) == 1 and mounts[0]['Type'] == 'volume'
                and mounts[0]['Name'] == PROJECT + '_pgdata', 'database_volume')

    def application(self):
        self.check_state(('database-attempt', 'database-ready'))
        saved = self.record('database-ready')
        require(self.ids('container') == [saved['id']], 'partial_or_existing_app')
        require(sorted(self.ids('network')) == sorted(saved['networks'])
                and self.ids('volume') == saved['volumes'], 'resource_continuity')
        self.check_db(self.inspect('container', saved['id']))
        self.image_check()
        self.mark('application-attempt', {'binding': self.binding})
        # No validator service in Compose: ordinary FSM expects exactly one app image.
        output = self.run(self.compose + ['run', '--rm', '--no-deps', '--pull', 'never',
                                          '--entrypoint', '/opt/app/bin/app-bot-validate-schema', 'app'], 75)
        require(output == b'schema-validation:v=1 result=compatible\n', 'schema_rejected')
        require(self.ids('container') == [saved['id']], 'partial_or_existing_app')
        require(configuration(self.root)[1] == self.binding, 'configuration_changed')
        self.mark('schema-compatible', {'binding': self.binding})
        try:
            self.run(self.compose + ['up', '-d', '--no-deps', '--pull', 'never', '--wait', '--wait-timeout', '90', 'app'], 110)
            baseline = self.baseline()
            self.mark('baseline-observed', baseline)
        except BaseException as exc:
            # Transport/process ambiguity is UNKNOWN: no automatic Docker stop.
            if not isinstance(exc, Refused) or str(exc) in (
                    'interrupted', 'command_timeout', 'phase_deadline', 'output_limit', 'command_cleanup', 'command_failed'):
                raise
            # Best effort containment; never remove or adopt the ambiguous bootstrap.
            # A killed process/Engine disconnect still requires operator reconciliation.
            self.stop_failed_app(saved['id'])
            raise

    def stop_failed_app(self, database_id):
        for cid in self.ids('container'):
            if cid == database_id:
                continue
            item = self.inspect('container', cid)
            labels = item['Config']['Labels']
            require(labels.get('com.docker.compose.project') == PROJECT
                    and labels.get('com.docker.compose.service') == 'app'
                    and labels.get('com.docker.compose.oneoff') == 'False'
                    and item['Config']['Image'] == self.env['CLB_APP_IMAGE'], 'failed_app_stop_unconfirmed')
            self.run(['docker', 'stop', '--time', '10', cid], 20)
            require(not self.inspect('container', cid)['State']['Running'], 'failed_app_stop_unconfirmed')

    def baseline(self):
        saved = self.record('database-ready')
        ids = self.ids('container')
        require(len(ids) == 2 and saved['id'] in ids, 'baseline_inventory')
        self.check_db(self.inspect('container', saved['id']))
        app = self.inspect('container', next(i for i in ids if i != saved['id']))
        labels = app['Config']['Labels']
        require(labels.get('com.docker.compose.project') == PROJECT
                and labels.get('com.docker.compose.service') == 'app'
                and labels.get('com.docker.compose.oneoff') == 'False', 'baseline_identity')
        require(app['Config']['Image'] == self.env['CLB_APP_IMAGE'], 'baseline_image')
        require(app['Image'] == self.inspect('image', self.env['CLB_APP_IMAGE'])['Id'], 'baseline_image')
        # This is the exact RepoDigests[0] used by collect_old_app_evidence.
        image = self.inspect('image', app['Image'])
        require(image['RepoDigests'][0] == self.env['CLB_APP_IMAGE']
                and (image['Config'].get('Labels') or {}).get('org.opencontainers.image.revision') == self.env['CLB_REVISION'], 'fsm_image_metadata')
        require(app['State']['Running'] and app['State']['Health']['Status'] == 'healthy'
                and app['RestartCount'] == 0, 'baseline_health')
        require(app['HostConfig']['PortBindings'] == {'8080/tcp': [{'HostIp': '127.0.0.1', 'HostPort': '8081'}]}, 'baseline_port')
        for path in ('ready', 'health'):
            self.run(self.compose + ['exec', '-T', 'app', 'curl', '-fsS', '--max-time', '3', 'http://127.0.0.1:8080/' + path], 10)
        return {'id': app['Id'], 'image': app['Image'], 'revision': self.env['CLB_REVISION'],
                'started_at': app['State']['StartedAt'], 'restart_count': app['RestartCount']}

    def original_evidence(self):
        return {name: digest(read_regular(self.state / name, True)) for name in ORIGINAL_MARKERS}

    def recovery_network_hashes(self):
        # run --no-deps still calls Create/ensureNetworks in Compose v2.40.3.
        # Do not let reconciliation discover a divergent network after receipt.
        require(self.run(['docker', 'compose', 'version', '--short'])
                == (RECOVERY_COMPOSE_VERSION + '\n').encode(), 'recovery_compose_version')
        model = json.loads(self.run(self.compose + ['config', '--format', 'json']))
        expected = {key: {'name': PROJECT + '_' + key, 'ipam': {}}
                    for key in ('application', 'database')}
        expected['database']['internal'] = True
        require(model['name'] == PROJECT and model['networks'] == expected, 'recovery_network_model')
        # Exact bounded NetworkConfig JSON, NOT arbitrary Compose JSON hashing:
        # compose-go v2.9.1 struct order is name, ipam, internal; empty IPAM is a
        # struct and survives omitempty. prepareNetworks adds CustomLabels, which
        # have json:"-" and do not enter NetworkHash. No driver default is added.
        # Unsupported fields/topology are rejected above, never approximated.
        # Sources: docker/compose v2.40.3 pkg/compose/{create,hash}.go and
        # compose-spec/compose-go v2.9.1 types/types.go (NetworkConfig/IPAMConfig).
        return {PROJECT + '_' + key: digest(json.dumps(value, separators=(',', ':')).encode())
                for key, value in expected.items()}

    def recovery_resources(self):
        """Stable identities only; hashes keep inspected private config out of receipts."""
        expected_hashes = self.recovery_network_hashes()
        saved = self.record('database-ready')
        db = self.inspect('container', saved['id'])
        self.check_db(db)
        require(db['Id'] == saved['id'] and db['State']['Status'] == 'running'
                and not db['State']['Restarting'] and db['RestartCount'] == 0, 'recovery_database')
        require(db['Image'] == self.inspect('image', self.env['CLB_PG_IMAGE'])['Id'], 'recovery_database_image')
        networks = {}
        for suffix in ('database', 'application'):
            name = PROJECT + '_' + suffix
            item = self.inspect('network', name)
            require(re.fullmatch('[0-9a-f]{64}', item['Id'])
                    and item['Name'] == name and item['Internal'] == (suffix == 'database')
                    and item['Driver'] == 'bridge'
                    and item['Labels'] == {'com.docker.compose.project': PROJECT,
                                           'com.docker.compose.network': suffix,
                                           'com.docker.compose.version': RECOVERY_COMPOSE_VERSION,
                                           'com.docker.compose.config-hash': expected_hashes[name]}, 'recovery_network')
            networks[name] = {key: item[key] for key in ('Id', 'Name', 'Internal', 'Driver', 'Labels', 'Options', 'IPAM')}
        network_ids = {n['Id'] for n in networks.values()}
        require(len(network_ids) == 2 and set(self.ids('network')) == network_ids
                and networks[PROJECT + '_database']['Id'] in saved['networks']
                and len(saved['networks']) == len(set(saved['networks']))
                and set(saved['networks']) <= network_ids, 'recovery_network_inventory')
        name = PROJECT + '_pgdata'
        require(self.ids('volume') == saved['volumes'] == [name], 'recovery_volume_inventory')
        volume = self.inspect('volume', name)
        require(volume['Name'] == name and volume['Driver'] == 'local'
                and volume['Labels'].get('com.docker.compose.project') == PROJECT
                and volume['Labels'].get('com.docker.compose.volume') == 'pgdata', 'recovery_volume')
        volume = {key: volume[key] for key in ('Name', 'Driver', 'Mountpoint', 'CreatedAt', 'Labels', 'Options')}
        stable_db = {key: db[key] for key in ('Id', 'Image', 'Created', 'Name', 'Config', 'HostConfig', 'Mounts')}
        stable_db['started_at'] = db['State']['StartedAt']
        stable_db['networks'] = {k: v['NetworkID'] for k, v in db['NetworkSettings']['Networks'].items()}
        require(stable_db['networks'] == {PROJECT + '_database': networks[PROJECT + '_database']['Id']}, 'recovery_database_network')
        return {'db': digest(json.dumps(stable_db, sort_keys=True).encode()), 'networks': networks, 'volume': volume}

    def recovery_app(self, cid, environment, resources, *, stopped):
        require(re.fullmatch('[0-9a-f]{64}', cid), 'recovery_app_id')
        app = self.inspect('container', cid)
        labels = app['Config']['Labels']
        require(app['Id'] == cid and labels.get('com.docker.compose.project') == PROJECT
                and labels.get('com.docker.compose.service') == 'app'
                and labels.get('com.docker.compose.oneoff') == 'False'
                and labels.get('com.docker.compose.project.working_dir') == str(self.root)
                and labels.get('com.docker.compose.project.config_files') == str(self.root / 'docker-compose.yml'), 'recovery_app_identity')
        image = self.inspect('image', self.env['CLB_APP_IMAGE'])
        require(app['Config']['Image'] == self.env['CLB_APP_IMAGE'] and app['Image'] == image['Id']
                and (image['Config'].get('Labels') or {}).get('org.opencontainers.image.revision') == ORIGINAL_REVISION,
                'recovery_app_image')
        require(all(app['Config'].get(key) == image['Config'].get(key)
                    for key in ('Entrypoint', 'Cmd', 'WorkingDir')), 'recovery_app_command')
        expected_env = dict(value.split('=', 1) for value in image['Config']['Env'])
        expected_env.update(environment)
        actual_env = app['Config']['Env']
        require(len(actual_env) == len(expected_env)
                and dict(value.split('=', 1) for value in actual_env) == expected_env, 'recovery_app_environment')
        host = app['HostConfig']
        require(app['Config']['User'] == '10001:10001' and host['ReadonlyRootfs']
                and host['CapDrop'] == ['ALL'] and not host['CapAdd'] and not host['Privileged']
                and host['SecurityOpt'] in (['no-new-privileges:true'], ['no-new-privileges'])
                and not host['Binds'] and not app['Mounts']
                and not host['PidMode'] and host['IpcMode'] == 'private'
                and not host['Devices'] and not host['DeviceRequests']
                and host['Tmpfs'] == {'/tmp': '', '/var/cache/app': 'uid=10001,gid=10001,mode=0700'}
                and host['RestartPolicy'] == {'Name': 'unless-stopped', 'MaximumRetryCount': 0}
                and host['PortBindings'] == {'8080/tcp': [{'HostIp': '127.0.0.1', 'HostPort': '8081'}]}, 'recovery_app_controls')
        require({k: v['NetworkID'] for k, v in app['NetworkSettings']['Networks'].items()}
                == {k: v['Id'] for k, v in resources['networks'].items()}, 'recovery_app_networks')
        state = app['State']
        require(not state['Restarting'], 'recovery_app_state')
        if stopped:
            require(state['Status'] == 'exited' and not state['Running']
                    and state['FinishedAt'] and not state['FinishedAt'].startswith('0001-'), 'recovery_app_not_stopped')
        else:
            require(state['Status'] == 'running' and state['Running']
                    and state['Health']['Status'] == 'healthy' and app['RestartCount'] == 0, 'recovery_app_health')
        stable = {key: app[key] for key in ('Id', 'Image', 'Created', 'Name', 'Config', 'HostConfig', 'Mounts', 'RestartCount')}
        stable['state'] = {key: state[key] for key in ('Status', 'Running', 'Restarting', 'StartedAt', 'FinishedAt')}
        return digest(json.dumps(stable, sort_keys=True).encode())

    def recovery_host_health(self):
        for path in ('ready', 'health'):
            self.run(['curl', '-fsS', '--max-time', '3', 'http://127.0.0.1:8081/' + path], 10)

    def recovery_environment(self):
        model = json.loads(self.run(self.compose + ['config', '--format', 'json']))
        require(set(model['services']) == {'app', 'db'}, 'recovery_compose')
        return model['services']['app']['environment']

    def recovery_guard(self, receipt, old_environment, stopped_fingerprint):
        require(self.host_lock is not None, 'recovery_lock')
        self.host_lock.check()
        require(recovery_bundle() == receipt['bundle'], 'recovery_bundle_changed')
        require(self.original_evidence() == receipt['original_markers'], 'recovery_history_changed')
        require(set(self.ids('container')) == {receipt['app_id'], receipt['db_id']}, 'recovery_inventory')
        resources = self.recovery_resources()
        require(resources == receipt['resources'], 'recovery_resources_changed')
        require(self.recovery_app(receipt['app_id'], old_environment, resources, stopped=True)
                == stopped_fingerprint, 'recovery_app_changed')

    def replace_recovery_file(self, name, data, mode):
        # The attempt is already durable. An interrupted pending file is retained;
        # neither this entrypoint nor ordinary phases will resume it automatically.
        pending = self.state / ('recovery-' + name + '.pending')
        with pending.open('xb') as stream:
            os.fchmod(stream.fileno(), mode)
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(pending, self.root / name)
        for directory in (self.root, self.state):
            fd = os.open(directory, os.O_RDONLY)
            try:
                os.fsync(fd)
            finally:
                os.close(fd)

    def recover_application(self, recovery_env, app_id, db_id):
        self.check_state(ORIGINAL_MARKERS)
        require(self.env['CLB_REVISION'] == ORIGINAL_REVISION, 'recovery_revision')
        require(self.record('application-attempt') == self.record('schema-compatible')
                == self.record('database-attempt') == {'binding': self.binding}, 'recovery_old_binding')
        require(re.fullmatch('[0-9a-f]{64}', db_id) and db_id == self.record('database-ready')['id']
                and db_id != app_id, 'recovery_database_id')
        target = compose_root(recovery_env)
        require(not target.is_relative_to(self.root) and not target.is_relative_to(TEMPLATE), 'recovery_input_path')
        raw = read_regular(target, True)
        old_raw = read_regular(self.root / '.env', True)
        require(len(raw) <= 16384 and len(old_raw) <= 16384, 'recovery_input_size')
        new_env = runtime_values(raw)
        require({k: v for k, v in new_env.items() if k not in CHAT_KEYS} == self.env, 'recovery_config_delta')
        self.image_check()
        old_environment = self.recovery_environment()
        resources = self.recovery_resources()
        fingerprint = self.recovery_app(app_id, old_environment, resources, stopped=True)
        admin = read_regular(self.root / 'secrets/postgres_password', True).decode().rstrip('\n').encode()
        new_compose = (TEMPLATE / 'docker-compose.yml').read_bytes()
        receipt = {'source_revision': ORIGINAL_REVISION, 'old_env': old_raw.decode(),
                   'old_binding': self.binding, 'new_binding': digest(raw + admin + new_compose),
                   'new_env_sha256': digest(raw), 'new_compose_sha256': digest(new_compose),
                   'app_id': app_id, 'db_id': db_id, 'resources': resources,
                   'stopped_app': fingerprint, 'bundle': recovery_bundle(),
                   'original_markers': self.original_evidence()}
        require(len(json.dumps(receipt, sort_keys=True).encode()) <= 65536, 'recovery_evidence_size')
        self.recovery_guard(receipt, old_environment, fingerprint)
        time.sleep(5)
        self.recovery_guard(receipt, old_environment, fingerprint)
        require(configuration(self.root, legacy=True)[1] == self.binding, 'configuration_changed')
        # One-use authorization/evidence precedes EVERY filesystem/Engine change.
        # Even an incomplete marker prevents replay; no catch/retry/rollback here.
        self.mark(RECOVERY_ATTEMPT, receipt)
        self.recovery_guard(receipt, old_environment, fingerprint)
        self.replace_recovery_file('.env', raw, 0o600)
        self.recovery_guard(receipt, old_environment, fingerprint)
        self.replace_recovery_file('docker-compose.yml', new_compose, 0o644)
        self.env, self.binding = configuration(self.root)
        require(self.binding == receipt['new_binding'], 'configuration_changed')
        self.image_check()
        self.recovery_guard(receipt, old_environment, fingerprint)
        output = self.run(self.compose + ['run', '--rm', '--no-deps', '--pull', 'never',
                                          '--entrypoint', '/opt/app/bin/app-bot-validate-schema', 'app'], 75)
        require(output == b'schema-validation:v=1 result=compatible\n', 'schema_rejected')
        self.recovery_guard(receipt, old_environment, fingerprint)
        require(configuration(self.root)[1] == receipt['new_binding'], 'configuration_changed')
        self.run(self.compose + ['up', '-d', '--no-deps', '--force-recreate', '--pull', 'never',
                                 '--wait', '--wait-timeout', '90', 'app'], 110)
        baseline = self.baseline()
        require(baseline['id'] != app_id and self.recovery_resources() == resources, 'recovery_resources_changed')
        self.recovery_app(baseline['id'], self.recovery_environment(), resources, stopped=False)
        self.recovery_host_health()
        require(configuration(self.root)[1] == receipt['new_binding']
                and self.original_evidence() == receipt['original_markers'], 'configuration_changed')
        self.host_lock.check()
        self.mark('baseline-observed', baseline)

    def recovery_history(self):
        # Used ONLY by read-only verify, never as admission to another attempt.
        current_binding = self.binding
        receipt = self.record(RECOVERY_ATTEMPT)
        require(receipt['bundle'] == recovery_bundle(), 'recovery_bundle_changed')
        old_raw = receipt['old_env'].encode()
        old_env = runtime_values(old_raw, legacy=True)
        admin = read_regular(self.root / 'secrets/postgres_password', True).decode().rstrip('\n').encode()
        old_binding = digest(old_raw + admin + original_compose())
        require(receipt['source_revision'] == old_env['CLB_REVISION'] == ORIGINAL_REVISION
                and old_binding == receipt['old_binding'], 'recovery_old_binding')
        # Reuse all original strict state predicates with the reconstructed B0.
        self.binding = old_binding
        try:
            self.check_state((*ORIGINAL_MARKERS, RECOVERY_ATTEMPT, 'baseline-observed'))
        finally:
            self.binding = current_binding
        require(self.original_evidence() == receipt['original_markers']
                and self.record('application-attempt') == self.record('schema-compatible')
                == {'binding': old_binding}, 'recovery_history_changed')
        require({k: v for k, v in self.env.items() if k not in CHAT_KEYS} == old_env
                and current_binding == receipt['new_binding']
                and digest(read_regular(self.root / '.env', True)) == receipt['new_env_sha256']
                and digest(read_regular(self.root / 'docker-compose.yml')) == receipt['new_compose_sha256'], 'recovery_new_binding')
        require(receipt['db_id'] == self.record('database-ready')['id']
                and receipt['app_id'] != self.record('baseline-observed')['id']
                and self.recovery_resources() == receipt['resources'], 'recovery_resources_changed')
        return receipt

    def verify(self):
        receipt = None
        if os.path.lexists(self.state / RECOVERY_ATTEMPT):
            receipt = self.recovery_history()
        else:
            self.check_state((*ORIGINAL_MARKERS, 'baseline-observed'))
        self.image_check()
        require(self.baseline() == self.record('baseline-observed'), 'baseline_changed')
        if receipt is not None:
            self.recovery_app(self.record('baseline-observed')['id'], self.recovery_environment(),
                              receipt['resources'], stopped=False)
            self.recovery_host_health()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=('check', 'database', 'application', 'verify', 'recover-application'))
    parser.add_argument('--root', required=True)
    parser.add_argument('--recovery-env')
    parser.add_argument('--stopped-app-id')
    parser.add_argument('--database-id')
    args = parser.parse_args()
    os.umask(0o077)
    try:
        recovery = args.phase == 'recover-application'
        require(all((args.recovery_env, args.stopped_app_id, args.database_id)) if recovery
                else not any((args.recovery_env, args.stopped_app_id, args.database_id)), 'recovery_arguments')
        with _lock_module.HostLock() as lock:
            install = Install(args.root, recovery=True) if recovery else Install(args.root)
            install.host_lock = lock
            if recovery:
                install.recover_application(args.recovery_env, args.stopped_app_id, args.database_id)
            elif args.phase == 'check':
                install.image_check()
            else:
                getattr(install, args.phase)()
            lock.check()
        print('first-install:' + args.phase + ':PASS')
        return 0
    except (Refused, _lock_module.Refused, OSError, ValueError, KeyError, IndexError, TypeError, subprocess.TimeoutExpired, KeyboardInterrupt) as exc:
        # Never echo Engine/Compose output, environment values, SQL or exceptions.
        reason = str(exc) if isinstance(exc, (Refused, _lock_module.Refused)) else 'unavailable'
        print('first-install:' + args.phase + ':REFUSED:' + reason)
        return 1


if __name__ == '__main__':
    sys.exit(main())
