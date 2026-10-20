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
KEYS = {'CLB_PG_IMAGE', 'CLB_PLATFORM', 'CLB_APP_IMAGE', 'CLB_REVISION', 'CLB_DB_PASSWORD',
        'CLB_BOT_TOKEN', 'CLB_WEBHOOK_SECRET', 'CLB_OWNER_ID', 'CLB_QR_SECRET', 'CLB_PHONE_KEY'}


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


def configuration(root):
    root = compose_root(root)
    require(root.is_dir() and root.stat().st_uid == os.getuid(), 'root_identity')
    require(not root.stat().st_mode & 0o022, 'root_mode')
    allowed = {'docker-compose.yml', '10-clubs-role.sql', '.env', 'secrets', '.first-install'}
    require(set(p.name for p in root.iterdir()) <= allowed, 'foreign_or_release_state')
    for name in ('docker-compose.yml', '10-clubs-role.sql'):
        require(read_regular(root / name) == (TEMPLATE / name).read_bytes(), 'template_changed')
    raw = read_regular(root / '.env', True)
    env = {}
    for line in raw.decode().splitlines():
        if not line or line.startswith('#'):
            continue
        key, sep, value = line.partition('=')
        require(sep and key in KEYS and key not in env and re.fullmatch(r'[A-Za-z0-9_:/@.+=-]+', value), 'runtime_config')
        env[key] = value
    require(set(env) == KEYS, 'runtime_config')
    require(re.fullmatch(r'postgres@sha256:[0-9a-f]{64}', env['CLB_PG_IMAGE']), 'postgres_reference')
    require(env['CLB_PLATFORM'] in ('linux/amd64', 'linux/arm64'), 'platform')
    require(re.fullmatch(r'ghcr\.io/koteev-m/clubs_bot/[a-z0-9._/-]+@sha256:[0-9a-f]{64}', env['CLB_APP_IMAGE']), 'image_reference')
    require(re.fullmatch(r'[0-9a-f]{40}', env['CLB_REVISION']), 'revision')
    require(re.fullmatch(r'[1-9][0-9]*', env['CLB_OWNER_ID']), 'owner')
    require(re.fullmatch(r'[0-9]+:[A-Za-z0-9_-]{20,}', env['CLB_BOT_TOKEN']), 'token_format')
    for key in ('CLB_DB_PASSWORD', 'CLB_WEBHOOK_SECRET', 'CLB_QR_SECRET', 'CLB_PHONE_KEY'):
        require(32 <= len(env[key]) <= 256, 'secret_shape')
    require(re.fullmatch(r'[A-Za-z0-9_-]{32,64}', env['CLB_WEBHOOK_SECRET']), 'webhook_shape')
    secrets = root / 'secrets'
    require(not secrets.is_symlink() and secrets.is_dir() and secrets.stat().st_uid == os.getuid()
            and stat.S_IMODE(secrets.stat().st_mode) == 0o700, 'secrets_directory')
    require(set(p.name for p in secrets.iterdir()) == {'postgres_password'}, 'secrets_inventory')
    password = env['CLB_DB_PASSWORD']
    admin = read_regular(secrets / 'postgres_password', True).decode().rstrip('\n')
    require(32 <= len(admin) <= 256 and admin != password
            and re.fullmatch(r'[A-Za-z0-9_+=-]+', admin), 'database_credentials')
    # This hash is private local continuity evidence, never a public secret fingerprint.
    binding = hashlib.sha256(raw + admin.encode() + (root / 'docker-compose.yml').read_bytes()).hexdigest()
    return env, binding


class Install:
    def __init__(self, root):
        self.root = root = compose_root(root)
        self.host_lock = None
        self.deadline = time.monotonic() + 300
        self.env, self.binding = configuration(root)
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

    def verify(self):
        self.check_state(('database-attempt', 'database-ready', 'application-attempt', 'schema-compatible', 'baseline-observed'))
        self.image_check()
        require(self.baseline() == self.record('baseline-observed'), 'baseline_changed')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=('check', 'database', 'application', 'verify'))
    parser.add_argument('--root', required=True)
    args = parser.parse_args()
    os.umask(0o077)
    try:
        with _lock_module.HostLock() as lock:
            install = Install(args.root)
            install.host_lock = lock
            if args.phase == 'check':
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
