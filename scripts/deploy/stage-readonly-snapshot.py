#!/usr/bin/env -S python3 -I -S -B
"""One protected stage snapshot, one SSH transport, no retries or remote writes."""
import base64
import hashlib
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
import types

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = '.github/workflows/stage-readonly-snapshot.yml'
COLLECTOR = 'scripts/deploy/stage-readonly-snapshot-collector.py'
LOCK = 'scripts/deploy/shared_staging_flock.py'
PIN = 'scripts/deploy/release_ssh_pin.py'
SELF = 'scripts/deploy/stage-readonly-snapshot.py'
PROCESS = 'scripts/deploy/snapshot_process.py'
CONFIRMATION = 'CLB_STAGE_PROTECTED_READONLY_SNAPSHOT:read-only'
LIMIT = 49152
SOURCE_LIMIT = 65536
REMOTE_COMMAND = '/usr/bin/python3 -I -S -B -'


class Refused(Exception):
    pass


class CleanupRefused(Refused):
    pass


class Cancelled(BaseException):
    pass


def need(value):
    if not value:
        raise Refused()


def process_module():
    path = Path(__file__).with_name('snapshot_process.py')
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(fd, 'rb') as stream:
        need(stat.S_ISREG(os.fstat(stream.fileno()).st_mode))
        raw = stream.read(SOURCE_LIMIT + 1)
        need(0 < len(raw) <= SOURCE_LIMIT)
    return module(raw, 'snapshot_process')


def capture(args, data=b'', *, env=None, fds=(), timeout=90, limit=LIMIT, _processor=None):
    process = _processor if _processor is not None else process_module()
    try:
        return process.capture(args, data, env=env, fds=fds, timeout=timeout, limit=limit)
    except process.CleanupFailure:
        raise CleanupRefused() from None
    except process.ProcessFailure:
        raise Refused() from None


def validate(env):
    need(env.get('GITHUB_REPOSITORY') == 'koteev-m/clubs_bot'
        and env.get('GITHUB_EVENT_NAME') == 'workflow_dispatch'
        and env.get('GITHUB_REF') == 'refs/heads/main'
        and env.get('GITHUB_REF_TYPE') == 'branch'
        and env.get('REPOSITORY_DEFAULT_BRANCH') == 'main'
        and env.get('GITHUB_RUN_ATTEMPT') == '1'
        and re.fullmatch('[0-9a-f]{40}', env.get('GITHUB_SHA', ''))
        and env.get('GITHUB_WORKFLOW_REF') == 'koteev-m/clubs_bot/' + WORKFLOW + '@refs/heads/main'
        and env.get('APP_ENV') == 'stage' and env.get('CONFIRMATION') == CONFIRMATION
        and env.get('COMPOSE_PATH') == '/opt/clubs-bot-stage')


def sources(env):
    git_env = {'PATH': '/usr/bin:/bin', 'LC_ALL': 'C', 'GIT_NO_REPLACE_OBJECTS': '1',
               'GIT_OPTIONAL_LOCKS': '0', 'GIT_LITERAL_PATHSPECS': '1'}
    def git(*args, limit=SOURCE_LIMIT):
        return capture(['/usr/bin/git', '--no-replace-objects', '-C', str(ROOT), *args],
                       env=git_env, timeout=10, limit=limit)
    sha = env['GITHUB_SHA']
    need(git('rev-parse', 'HEAD') == (sha + '\n').encode('ascii'))
    result = {}
    for path in (COLLECTOR, LOCK, PIN, SELF, PROCESS):
        entry = git('ls-tree', '-z', sha, '--', path)
        match = re.fullmatch(rb'100644 blob ([0-9a-f]{40})\t' + re.escape(path.encode()) + b'\x00', entry)
        need(match is not None)
        raw = git('cat-file', 'blob', match[1].decode())
        need(0 < len(raw) <= SOURCE_LIMIT)
        need(hashlib.sha1(f'blob {len(raw)}\0'.encode() + raw).hexdigest().encode() == match[1])
        fd = os.open(ROOT / path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(fd, 'rb') as stream:
            need(stat.S_ISREG(os.fstat(stream.fileno()).st_mode))
            need(stream.read(SOURCE_LIMIT + 1) == raw)
        result[path] = raw
    return result


def module(raw, name):
    value = types.ModuleType(name)
    exec(compile(raw, name, 'exec'), value.__dict__)
    return value


def payload(source):
    # No host/env/command/selector parameters cross this boundary. Reviewed Git
    # source is the sole program; remote stdin is not a caller command interface.
    lock = base64.b64encode(source[LOCK]).decode('ascii')
    collector = base64.b64encode(source[COLLECTOR]).decode('ascii')
    process = base64.b64encode(source[PROCESS]).decode('ascii')
    return ("import base64,types\n"
        "lock=types.ModuleType('snapshot_lock')\n"
        f"exec(compile(base64.b64decode('{lock}'),'snapshot_lock','exec'),lock.__dict__)\n"
        "process=types.ModuleType('snapshot_process')\n"
        f"exec(compile(base64.b64decode('{process}'),'snapshot_process','exec'),process.__dict__)\n"
        "collector=types.ModuleType('snapshot_collector')\n"
        "collector.PROCESS_MODULE=process\n"
        f"exec(compile(base64.b64decode('{collector}'),'snapshot_collector','exec'),collector.__dict__)\n"
        "raise SystemExit(collector.main(lock.HostLock))\n").encode('ascii')


def ssh_args(env, pin_fd):
    return ['/usr/bin/ssh', '-F', '/dev/null', '-o', 'BatchMode=yes',
        '-o', 'StrictHostKeyChecking=yes', '-o', f'UserKnownHostsFile=/proc/self/fd/{pin_fd}',
        '-o', 'GlobalKnownHostsFile=/dev/null', '-o', 'KnownHostsCommand=none',
        '-o', 'VerifyHostKeyDNS=no', '-o', 'ProxyCommand=none', '-o', 'ProxyJump=none',
        '-o', 'PermitLocalCommand=no', '-o', 'ForwardAgent=no', '-o', 'ClearAllForwardings=yes',
        '-o', 'PasswordAuthentication=no', '-o', 'KbdInteractiveAuthentication=no',
        '-o', 'PreferredAuthentications=publickey', '-o', 'IdentityFile=none', '-o', 'CertificateFile=none',
        '-o', 'GSSAPIAuthentication=no', '-o', 'HostbasedAuthentication=no',
        '-o', 'HostKeyAlgorithms=ssh-ed25519', '-o', 'UpdateHostKeys=no',
        '-o', 'ControlMaster=no', '-o', 'ControlPath=none', '-o', 'ControlPersist=no',
        '-o', 'CanonicalizeHostname=no', '-o', 'RequestTTY=no', '-o', 'ConnectTimeout=15', '-o', 'ConnectionAttempts=1',
        '-o', 'ServerAliveInterval=5', '-o', 'ServerAliveCountMax=2',
        '-p', env['SSH_PORT'], env['SSH_USER'] + '@' + env['SSH_HOST'], REMOTE_COMMAND]


def unique(pairs):
    value = {}
    for key, item in pairs:
        need(key not in value)
        value[key] = item
    return value


TRIGGER_TABLES = {
    'target_client_epoch_guard': 'clients', 'target_mapping_epoch_bump': 'client_clubs',
    'target_mapping_truncate_guard': 'client_clubs', 'target_bot_connections_guard': 'target_bot_connections',
    'target_bot_bindings_guard': 'target_bot_bindings', 'target_control_guard': 'target_maintenance_control_heads',
    'target_publication_guard': 'target_maintenance_publications'}
TARGET_REVISION = 'aed1e5831ca807f1c8ab2d58dbc952efc899207b'
TARGET_DIGEST = 'sha256:72cac72d8c9ffefd927910d8704d0f5b668694e462a2bd7b66b0b0eaeaaf7af3'


def shape(value, required, optional=()):
    need(type(value) is dict and set(required) <= set(value) <= set(required) | set(optional))


def integer(value, low, high):
    need(type(value) is int and low <= value <= high)


def boolean(value):
    need(type(value) is bool)


def digest(value, size=64, prefix=''):
    need(type(value) is str and re.fullmatch(re.escape(prefix) + '[0-9a-f]{' + str(size) + '}', value))


def http(value):
    integer(value, 100, 599)


def database_schema(value):
    need(type(value) is dict)
    if value.get('availability') == 'unknown':
        shape(value, ('availability',))
        return
    if value.get('availability') == 'not_found':
        shape(value, ('availability', 'catalog_validation'))
        need(value['catalog_validation'] == 'canonical_absence')
        return
    fields = ('availability', 'schema', 'catalog_validation', 'transaction_read_only', 'history',
        'latest_version', 'pending_target', 'failed_history', 'relevant_triggers', 'inflight_migration')
    shape(value, fields)
    need(value['availability'] == 'observed' and value['schema'] == 'public'
         and value['catalog_validation'] == 'locked_heap_table' and value['transaction_read_only'] is True
         and value['inflight_migration'] == 'unknown')
    history = value['history']
    need(type(history) is list and 0 < len(history) <= 160)
    ranks, versions, applied = set(), set(), set()
    failed = False
    for row in history:
        shape(row, ('rank', 'version', 'checksum', 'success'))
        integer(row['rank'], -1, 2147483647)
        need(row['rank'] not in ranks)
        ranks.add(row['rank'])
        version = row['version']
        if version is not None:
            need(type(version) is str and re.fullmatch(r'[0-9]{1,3}(?:\.[0-9]{1,3}){0,3}', version))
            need(version not in versions)
            versions.add(version)
        if row['checksum'] is not None:
            integer(row['checksum'], -2147483648, 2147483647)
        boolean(row['success'])
        failed |= not row['success']
        if row['success'] and version is not None and version.isdigit():
            applied.add(int(version))
    need([r['rank'] for r in history] == sorted(ranks))
    need(value['latest_version'] == (max(applied) if applied else None))
    if value['latest_version'] is not None:
        integer(value['latest_version'], 0, 999)
    need(type(value['pending_target']) is list
         and all(type(v) is int for v in value['pending_target'])
         and value['pending_target'] == [v for v in (77, 78, 79) if v not in applied])
    boolean(value['failed_history'])
    need(value['failed_history'] == failed)
    triggers = value['relevant_triggers']
    need(type(triggers) is list and len(triggers) <= len(TRIGGER_TABLES))
    seen = set()
    for row in triggers:
        shape(row, ('name', 'table', 'enabled'))
        name = row['name']
        need(type(name) is str and name in TRIGGER_TABLES and name not in seen)
        need(row['table'] == TRIGGER_TABLES[name] and row['enabled'] in ('O', 'D', 'R', 'A'))
        seen.add(name)


def release_schema(value):
    boundary = ('fsm_authority', 'migration_ledger_integrity', 'unfinished_release', 'backup_recovery')
    need(type(value) is dict and all(value.get(k) == 'unknown' for k in boundary))
    if value.get('artifact_observation') == 'unknown':
        shape(value, ('artifact_observation', *boundary))
        return
    shape(value, ('artifact_observation', *boundary, 'active_state', 'checkpoint', 'anchor_present',
                  'retained_ledgers', 'outcome_files'))
    need(value['artifact_observation'] == 'observed')
    boolean(value['anchor_present'])
    integer(value['retained_ledgers'], 0, 128)
    integer(value['outcome_files'], 0, 128)
    need(value['retained_ledgers'] + value['outcome_files'] <= 128)
    if value['active_state'] == 'none_observed':
        need(value['checkpoint'] == 'not_read')
    else:
        need(value['active_state'] == 'present' and value['checkpoint'] in
            ('claimed', 'prepared', 'override_published', 'app_quiesced', 'migration_started',
             'migration_completed', 'candidate_started', 'completed', 'aborted'))


def neighbor_schema(value, clubs):
    need(type(value) is dict and value.get('identity_authority') == 'unknown' and value.get('isolation') == 'unknown')
    if set(value) == {'identity_authority', 'isolation'}:
        return
    shape(value, ('identity_authority', 'isolation', 'observed_other_containers',
        'shared_named_volume_or_network_observed', 'candidate_container_identity_sha256'),
        ('loopback_8080_health_http',))
    need(clubs.get('availability') == 'observed')
    integer(value['observed_other_containers'], 0, 30)
    boolean(value['shared_named_volume_or_network_observed'])
    hashes = value['candidate_container_identity_sha256']
    need(type(hashes) is list and len(hashes) <= value['observed_other_containers'])
    for h in hashes:
        digest(h)
    need(len(set(hashes)) == len(hashes)
         and not set(hashes) & {clubs['container_identity_sha256'], clubs['db_container_identity_sha256']})
    if not value['observed_other_containers']:
        need(not value['shared_named_volume_or_network_observed'])
    if 'loopback_8080_health_http' in value:
        http(value['loopback_8080_health_http'])


def clubs_schema(value):
    need(type(value) is dict)
    if value.get('availability') == 'unknown':
        shape(value, ('availability',))
        return
    shape(value, ('availability', 'project', 'service', 'container_identity_sha256',
        'db_container_identity_sha256', 'status', 'container_health', 'running', 'revision', 'image_digest',
        'target_revision_matches', 'target_digest_matches', 'health_http', 'ready_http', 'compose_status', 'compose_config'))
    need(value['availability'] == 'observed' and value['project'] == 'clubs-bot-stage' and value['service'] == 'app'
         and value['compose_status'] == 'exact_two_service_labels_observed' and value['compose_config'] == 'not_evaluated')
    for field in ('container_identity_sha256', 'db_container_identity_sha256'):
        digest(value[field])
    need(value['container_identity_sha256'] != value['db_container_identity_sha256'])
    digest(value['revision'], 40)
    digest(value['image_digest'], prefix='sha256:')
    boolean(value['running'])
    need(value['status'] in ('created', 'running', 'paused', 'restarting', 'removing', 'exited', 'dead')
         and value['container_health'] in ('none', 'starting', 'healthy', 'unhealthy'))
    need(value['running'] == (value['status'] in ('running', 'paused', 'restarting')))
    for field, expected in (('target_revision_matches', value['revision'] == TARGET_REVISION),
                            ('target_digest_matches', value['image_digest'] == TARGET_DIGEST)):
        boolean(value[field])
        need(value[field] == expected)
    http(value['health_http'])
    http(value['ready_http'])


def sanitize(raw):
    need(0 < len(raw) <= LIMIT and raw.endswith(b'\n') and raw.count(b'\n') == 1)
    result = json.loads(raw, object_pairs_hook=unique)
    need(type(result) is dict and result.get('protocol') == 'clb-stage-snapshot-v1'
         and result.get('environment') == 'stage' and result.get('stage_ready') is False)
    basic = ('protocol', 'environment', 'result', 'stage_ready')
    if result.get('result') == 'unavailable':
        shape(result, basic, ('reason',))
        if 'reason' in result:
            need(result['reason'] == 'cleanup')
    else:
        shape(result, (*basic, 'target_revision', 'target_digest', 'shared_host_lock', 'clubs', 'database', 'release', 'neighbor'))
        need(result['result'] == 'observed_with_unknowns' and result['target_revision'] == TARGET_REVISION
             and result['target_digest'] == TARGET_DIGEST and result['shared_host_lock'] == 'held_existing_exclusive')
        clubs_schema(result['clubs'])
        database_schema(result['database'])
        release_schema(result['release'])
        neighbor_schema(result['neighbor'], result['clubs'])
        if result['clubs']['availability'] == 'unknown':
            need(result['database'] == {'availability': 'unknown'}
                 and result['neighbor'] == {'identity_authority': 'unknown', 'isolation': 'unknown'})
    return json.dumps(result, sort_keys=True, separators=(',', ':'), ensure_ascii=True)


def run(env):
    validate(env)
    source = sources(env)
    pin = module(source[PIN], 'release_pin').validate(env)
    # Anonymous runner memory file; never persisted on stage, no pin provisioning.
    fd = os.memfd_create('clb-stage-snapshot-pin', os.MFD_CLOEXEC | os.MFD_ALLOW_SEALING)
    try:
        import fcntl
        os.fchmod(fd, 0o600)
        need(os.write(fd, pin) == len(pin))
        fcntl.fcntl(fd, fcntl.F_ADD_SEALS, fcntl.F_SEAL_WRITE | fcntl.F_SEAL_GROW |
                    fcntl.F_SEAL_SHRINK | fcntl.F_SEAL_SEAL)
        os.lseek(fd, 0, os.SEEK_SET)
        child_env = {'PATH': '/usr/bin:/bin', 'LC_ALL': 'C',
                     'SSH_AUTH_SOCK': env.get('SSH_AUTH_SOCK', '')}
        raw = capture(ssh_args(env, fd), payload(source), env=child_env, fds=(fd,),
                      _processor=module(source[PROCESS], 'snapshot_process'))
        return sanitize(raw)
    finally:
        os.close(fd)


def main():
    def interrupted(_sig, _frame):
        raise Cancelled()
    for sig in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP):
        signal.signal(sig, interrupted)
    try:
        need(sys.flags.isolated and sys.flags.no_site and sys.platform == 'linux')
        need(sys.argv[1:] in ([], ['--validate']))
        validate(os.environ)
        if sys.argv[1:]:
            print('stage-readonly-snapshot:request-valid')
        else:
            result = run(os.environ)
            print(result)
            if json.loads(result)['result'] == 'unavailable':
                return 2
        return 0
    except CleanupRefused:
        print('stage-readonly-snapshot:cleanup-unavailable', file=sys.stderr)
        return 2
    except (Exception, Cancelled):
        print('stage-readonly-snapshot:unavailable', file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
