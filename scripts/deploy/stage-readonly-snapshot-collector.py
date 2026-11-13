"""Fixed stage observations only. Injected LOCK is the existing shared-host guard.

No CLI, credentials, application rows, helper execution, files created or writes.
Filesystem atime, DB statistics and access/audit logs may change through reads.
A successful observation is not release admission or backup/recovery authority.
"""
import hashlib
import json
import os
from pathlib import Path
import types
import re
import selectors
import signal
import stat
import struct
import subprocess
import time

ROOT = '/opt/clubs-bot-stage'
PROJECT = 'clubs-bot-stage'
DOCKER = '/usr/bin/docker'
CURL = '/usr/bin/curl'
ENV = {'PATH': '/usr/bin:/bin', 'LC_ALL': 'C', 'HOME': '/nonexistent',
       'DOCKER_HOST': 'unix:///var/run/docker.sock', 'DOCKER_CONFIG': '/nonexistent'}
TARGET_REVISION = 'aed1e5831ca807f1c8ab2d58dbc952efc899207b'
TARGET_DIGEST = 'sha256:72cac72d8c9ffefd927910d8704d0f5b668694e462a2bd7b66b0b0eaeaaf7af3'
IMAGE = 'ghcr.io/koteev-m/clubs_bot/app-bot'
LIMIT = 49152
# No Env, Args, command, healthcheck log, labels dump, mount source or host path.
FORMAT = '''{"id":{{json .Id}},"image":{{json .Image}},"status":{{json .State.Status}},"running":{{json .State.Running}},"health":{{if .State.Health}}{{json .State.Health.Status}}{{else}}"none"{{end}},"project":{{json (index .Config.Labels "com.docker.compose.project")}},"service":{{json (index .Config.Labels "com.docker.compose.service")}},"root":{{json (index .Config.Labels "com.docker.compose.project.working_dir")}},"revision":{{json (index .Config.Labels "org.opencontainers.image.revision")}},"networks":{{json .NetworkSettings.Networks}},"ports":{{json .NetworkSettings.Ports}},"volumes":[{{range $i,$m := .Mounts}}{{if $i}},{{end}}{{json $m.Name}}{{end}}]}'''
# Docker network values are parsed privately; no IP/MAC/container name is emitted.
TRIGGERS = ('target_client_epoch_guard', 'target_mapping_epoch_bump',
            'target_mapping_truncate_guard', 'target_bot_connections_guard',
            'target_bot_bindings_guard', 'target_control_guard', 'target_publication_guard')
SQL = r"""BEGIN TRANSACTION ISOLATION LEVEL READ COMMITTED READ ONLY;
SET LOCAL search_path = pg_catalog;
SET LOCAL statement_timeout = '3000ms';
SET LOCAL lock_timeout = '500ms';
SET LOCAL idle_in_transaction_session_timeout = '3000ms';
SET LOCAL row_security = off;
SET LOCAL enable_indexscan = off;
SET LOCAL enable_indexonlyscan = off;
SET LOCAL enable_bitmapscan = off;
SET LOCAL enable_tidscan = off;
SET LOCAL max_parallel_workers_per_gather = 0;
-- PG16.11/16.14 schema rename does not respect the relation's ACCESS SHARE lock.
-- Refuse mutable namespace contexts, including other roles inheriting ownership.
-- Administrative grants/role changes during the read are outside this contract.
SELECT COALESCE((SELECT c.oid FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n
 ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relname='flyway_schema_history'),0) AS oid,
 COALESCE((SELECT oid FROM pg_catalog.pg_namespace WHERE nspname='public'),0) AS namespace_oid,
 EXISTS (SELECT FROM pg_catalog.pg_namespace n WHERE n.nspname='public'
  AND pg_catalog.has_schema_privilege(n.oid,'USAGE')
  AND NOT EXISTS (SELECT FROM pg_catalog.pg_roles r WHERE NOT r.rolsuper
   AND pg_catalog.pg_has_role(r.oid,n.nspowner,'USAGE')
   AND pg_catalog.has_database_privilege(r.oid,pg_catalog.current_database(),'CREATE'))) AND
 (SELECT NOT rolsuper AND NOT rolcreaterole AND NOT rolcreatedb FROM pg_catalog.pg_roles WHERE rolname=CURRENT_USER)
 AND CURRENT_USER='clubs' AND pg_catalog.current_database()='clubs' AS context,
 EXISTS (SELECT FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
  WHERE n.nspname='public' AND c.relname='flyway_schema_history' AND (c.relkind = 'r' AND c.relpersistence = 'p'
 AND c.relam = (SELECT oid FROM pg_catalog.pg_am WHERE amname = 'heap')
 AND NOT c.relrowsecurity AND NOT c.relforcerowsecurity AND NOT c.relhasrules
 AND NOT c.relispartition AND NOT c.relhassubclass
 AND NOT EXISTS (SELECT FROM pg_catalog.pg_inherits WHERE inhrelid=c.oid OR inhparent=c.oid)
 AND NOT EXISTS (SELECT FROM pg_catalog.pg_rewrite WHERE ev_class=c.oid)
 AND NOT EXISTS (SELECT FROM pg_catalog.pg_policy WHERE polrelid=c.oid)
 AND NOT EXISTS (SELECT FROM pg_catalog.pg_trigger WHERE tgrelid=c.oid AND NOT tgisinternal)
 AND pg_catalog.has_table_privilege(c.oid,'SELECT')
 AND (SELECT pg_catalog.array_agg(a.attname::pg_catalog.text ORDER BY a.attnum)
      FROM pg_catalog.pg_attribute a WHERE a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped)
     = ARRAY['installed_rank','version','description','type','script','checksum','installed_by','installed_on','execution_time','success']
 AND NOT EXISTS (SELECT FROM pg_catalog.pg_attribute a
      WHERE a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped AND
       (a.attgenerated<>'' OR a.attidentity<>'' OR a.attndims<>0 OR a.attinhcount<>0 OR
        (a.attname,a.atttypid,a.atttypmod) NOT IN
        (('installed_rank',23,-1),('version',1043,54),('description',1043,204),('type',1043,24),
         ('script',1043,1004),('checksum',23,-1),('installed_by',1043,104),('installed_on',1114,-1),
         ('execution_time',23,-1),('success',16,-1))))
 AND NOT EXISTS (SELECT FROM pg_catalog.pg_index i JOIN pg_catalog.pg_class ix ON ix.oid=i.indexrelid
      JOIN pg_catalog.pg_am am ON am.oid=ix.relam
      WHERE i.indrelid=c.oid AND (i.indexprs IS NOT NULL OR i.indpred IS NOT NULL OR am.amname<>'btree'
       OR EXISTS (SELECT FROM pg_catalog.unnest(i.indclass::pg_catalog.oid[]) x(oid)
          JOIN pg_catalog.pg_opclass op ON op.oid=x.oid JOIN pg_catalog.pg_namespace ns ON ns.oid=op.opcnamespace
          WHERE ns.nspname<>'pg_catalog')))
)) AS safe
\gset pre_
\if :pre_context
SELECT (:pre_oid::pg_catalog.oid <> 0::pg_catalog.oid) AS found
\gset
\if :found
\if :pre_safe
-- ONLY avoids recursively locking any child objects. LOCK never executes SELECT.
LOCK TABLE ONLY public.flyway_schema_history IN ACCESS SHARE MODE;
SELECT COALESCE((SELECT c.oid FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n
 ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relname='flyway_schema_history'),0) AS oid,
 COALESCE((SELECT oid FROM pg_catalog.pg_namespace WHERE nspname='public'),0) AS namespace_oid,
 EXISTS (SELECT FROM pg_catalog.pg_namespace n WHERE n.nspname='public'
  AND pg_catalog.has_schema_privilege(n.oid,'USAGE')
  AND NOT EXISTS (SELECT FROM pg_catalog.pg_roles r WHERE NOT r.rolsuper
   AND pg_catalog.pg_has_role(r.oid,n.nspowner,'USAGE')
   AND pg_catalog.has_database_privilege(r.oid,pg_catalog.current_database(),'CREATE'))) AND
 (SELECT NOT rolsuper AND NOT rolcreaterole AND NOT rolcreatedb FROM pg_catalog.pg_roles WHERE rolname=CURRENT_USER)
 AND CURRENT_USER='clubs' AND pg_catalog.current_database()='clubs' AS context,
 EXISTS (SELECT FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
  WHERE n.nspname='public' AND c.relname='flyway_schema_history' AND (c.relkind = 'r' AND c.relpersistence = 'p'
 AND c.relam = (SELECT oid FROM pg_catalog.pg_am WHERE amname = 'heap')
 AND NOT c.relrowsecurity AND NOT c.relforcerowsecurity AND NOT c.relhasrules
 AND NOT c.relispartition AND NOT c.relhassubclass
 AND NOT EXISTS (SELECT FROM pg_catalog.pg_inherits WHERE inhrelid=c.oid OR inhparent=c.oid)
 AND NOT EXISTS (SELECT FROM pg_catalog.pg_rewrite WHERE ev_class=c.oid)
 AND NOT EXISTS (SELECT FROM pg_catalog.pg_policy WHERE polrelid=c.oid)
 AND NOT EXISTS (SELECT FROM pg_catalog.pg_trigger WHERE tgrelid=c.oid AND NOT tgisinternal)
 AND pg_catalog.has_table_privilege(c.oid,'SELECT')
 AND (SELECT pg_catalog.array_agg(a.attname::pg_catalog.text ORDER BY a.attnum)
      FROM pg_catalog.pg_attribute a WHERE a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped)
     = ARRAY['installed_rank','version','description','type','script','checksum','installed_by','installed_on','execution_time','success']
 AND NOT EXISTS (SELECT FROM pg_catalog.pg_attribute a
      WHERE a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped AND
       (a.attgenerated<>'' OR a.attidentity<>'' OR a.attndims<>0 OR a.attinhcount<>0 OR
        (a.attname,a.atttypid,a.atttypmod) NOT IN
        (('installed_rank',23,-1),('version',1043,54),('description',1043,204),('type',1043,24),
         ('script',1043,1004),('checksum',23,-1),('installed_by',1043,104),('installed_on',1114,-1),
         ('execution_time',23,-1),('success',16,-1))))
 AND NOT EXISTS (SELECT FROM pg_catalog.pg_index i JOIN pg_catalog.pg_class ix ON ix.oid=i.indexrelid
      JOIN pg_catalog.pg_am am ON am.oid=ix.relam
      WHERE i.indrelid=c.oid AND (i.indexprs IS NOT NULL OR i.indpred IS NOT NULL OR am.amname<>'btree'
       OR EXISTS (SELECT FROM pg_catalog.unnest(i.indclass::pg_catalog.oid[]) x(oid)
          JOIN pg_catalog.pg_opclass op ON op.oid=x.oid JOIN pg_catalog.pg_namespace ns ON ns.oid=op.opcnamespace
          WHERE ns.nspname<>'pg_catalog')))
)) AS safe
\gset locked_
SELECT (:pre_oid::pg_catalog.oid = :locked_oid::pg_catalog.oid)
 AND (:pre_namespace_oid::pg_catalog.oid = :locked_namespace_oid::pg_catalog.oid)
 AND :'locked_context'::pg_catalog.bool AND :'locked_safe'::pg_catalog.bool AS exact
\gset
\if :exact
SELECT pg_catalog.json_build_object(
 'outcome','observed','catalog_validation','locked_heap_table',
 'readonly',pg_catalog.current_setting('transaction_read_only'),'role_safe',true,
 'triggers',(SELECT pg_catalog.json_agg(pg_catalog.json_build_object(
   'name',t.tgname,'table',c.relname,'enabled',t.tgenabled))
   FROM pg_catalog.pg_trigger t JOIN pg_catalog.pg_class c ON c.oid=t.tgrelid
   JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
   WHERE n.nspname='public' AND NOT t.tgisinternal AND t.tgname=ANY(ARRAY[
    'target_client_epoch_guard','target_mapping_epoch_bump','target_mapping_truncate_guard',
    'target_bot_connections_guard','target_bot_bindings_guard','target_control_guard','target_publication_guard'])));
-- Direct relation COPY avoids planner expression/predicate hooks on new indexes.
-- Only four builtin column types, no query, filename, PROGRAM or external file.
COPY public.flyway_schema_history(installed_rank,version,checksum,success) TO STDOUT WITH (FORMAT binary);
\else
SELECT '{"outcome":"unknown"}'::pg_catalog.json;
\endif
\else
SELECT '{"outcome":"unknown"}'::pg_catalog.json;
\endif
\else
SELECT '{"outcome":"not_found"}'::pg_catalog.json;
\endif
\else
SELECT '{"outcome":"unknown"}'::pg_catalog.json;
\endif
ROLLBACK;
"""


class Unavailable(Exception):
    pass


class CleanupUnavailable(Unavailable):
    pass


class Cancelled(BaseException):
    pass


def need(ok):
    if not ok:
        raise Unavailable()


def unique(pairs):
    value = {}
    for key, item in pairs:
        need(key not in value)
        value[key] = item
    return value


def decode(raw):
    need(len(raw) <= LIMIT)
    try:
        return json.loads(raw, object_pairs_hook=unique)
    except (ValueError, UnicodeError):
        raise Unavailable() from None


def identity(value):
    return hashlib.sha256(value.encode('utf-8')).hexdigest()


def database_wire(raw):
    """Private psql frame: one catalog JSON line, then bounded binary table COPY.

    Binary lengths distinguish SQL NULL from any varchar content without unsafe
    delimiter parsing. No COPY filename/PROGRAM or COPY FROM exists.
    """
    need(type(raw) is bytes and len(raw) <= LIMIT)
    header, separator, data = raw.partition(b'\n')
    need(separator)
    value = decode(header)
    if type(value) is dict and set(value) == {'outcome'}:
        need(value['outcome'] in ('unknown', 'not_found') and not data)
        return value
    need(type(value) is dict and set(value) == {'outcome','catalog_validation','readonly','role_safe','triggers'}
         and value['outcome'] == 'observed' and value['catalog_validation'] == 'locked_heap_table'
         and value['readonly'] == 'on' and value['role_safe'] is True)
    signature = b'PGCOPY\n\xff\r\n\x00' + struct.pack('!ii', 0, 0)
    need(data.startswith(signature))
    offset = len(signature)
    rows = []
    def take(length):
        nonlocal offset
        need(0 <= length <= len(data)-offset)
        result=data[offset:offset+length];offset+=length
        return result
    while True:
        fields=struct.unpack('!h',take(2))[0]
        if fields == -1:
            need(offset == len(data))
            break
        need(fields == 4 and len(rows) < 160)
        values=[]
        for index in range(4):
            length=struct.unpack('!i',take(4))[0]
            if length == -1:
                need(index in (1,2));values.append(None);continue
            need(length >= 0 and (length == 4 if index in (0,2) else length <= 200 if index == 1 else length == 1))
            field=take(length)
            if index in (0,2): values.append(struct.unpack('!i',field)[0])
            elif index == 1: values.append(field.decode('utf-8'))
            else:
                need(field in (b'\x00',b'\x01'));values.append(field == b'\x01')
        rows.append(dict(zip(('rank','version','checksum','success'),values)))
    value['history']=rows
    return value


class Commands:
    """Bounded subprocess groups, no shell and no inherited credential environment."""
    def __init__(self):
        self.deadline = time.monotonic() + 55

    def run(self, args, data=b''):
        need(time.monotonic() < self.deadline)
        process = globals().get('PROCESS_MODULE')
        if process is None:  # local tests; the SSH payload injects captured Git bytes
            path = Path(__file__).with_name('snapshot_process.py')
            fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
            with os.fdopen(fd, 'rb') as stream:
                need(stat.S_ISREG(os.fstat(stream.fileno()).st_mode))
                raw = stream.read(65537)
                need(0 < len(raw) <= 65536)
            process = types.ModuleType('snapshot_process')
            exec(compile(raw, 'snapshot_process', 'exec'), process.__dict__)
        try:
            return process.capture(args, data, env=ENV, timeout=min(7, self.deadline-time.monotonic()), limit=LIMIT)
        except process.CleanupFailure:
            raise CleanupUnavailable() from None
        except process.ProcessFailure:
            raise Unavailable() from None

    def containers(self):
        raw = self.run([DOCKER, 'ps', '--all', '--quiet', '--no-trunc'])
        ids = raw.decode('ascii').splitlines()
        need(len(ids) <= 32 and len(set(ids)) == len(ids))
        need(all(re.fullmatch('[0-9a-f]{64}', value) for value in ids))
        return [decode(self.run([DOCKER, 'container', 'inspect', '--format', FORMAT, value])) for value in sorted(ids)]

    def image(self, value):
        need(re.fullmatch('sha256:[0-9a-f]{64}', value))
        return decode(self.run([DOCKER, 'image', 'inspect', '--format',
            '{"id":{{json .Id}},"revision":{{json (index .Config.Labels "org.opencontainers.image.revision")}},"digests":{{json .RepoDigests}}}', value]))

    def health(self, endpoint, port=8081):
        need(endpoint in ('health', 'ready') and port in (8080, 8081))
        raw = self.run([CURL, '--silent', '--output', '/dev/null', '--write-out', '%{http_code}',
                       '--max-time', '2', '--noproxy', '*', '--proto', '=http',
                       f'http://127.0.0.1:{port}/{endpoint}'])
        need(re.fullmatch(rb'[1-5][0-9]{2}', raw))
        return int(raw)

    def database(self, container):
        need(re.fullmatch('[0-9a-f]{64}', container))
        # Existing non-superuser app role over its existing local socket. No
        # password lookup, env dump, superuser fallback or grants. -w never prompts.
        raw = self.run([DOCKER, 'exec', '--interactive', '--user', 'postgres', container,
            '/usr/bin/env', '-i', 'PATH=/usr/local/bin:/usr/bin:/bin',
            'PGOPTIONS=-c default_transaction_read_only=on -c statement_timeout=3000 -c lock_timeout=500 -c idle_in_transaction_session_timeout=3000',
            '/usr/local/bin/psql', '-X', '-q', '-A', '-t', '-w', '-U', 'clubs', '-d', 'clubs',
            '--set=ON_ERROR_STOP=1'], SQL.encode('ascii'))
        return database_wire(raw)


def container_ok(value):
    need(type(value) is dict and re.fullmatch('[0-9a-f]{64}', value.get('id', '')))
    need(re.fullmatch('sha256:[0-9a-f]{64}', value.get('image', '')))
    need(value.get('status') in ('created', 'running', 'paused', 'restarting', 'removing', 'exited', 'dead'))
    need(type(value.get('running')) is bool and value.get('health') in ('none', 'starting', 'healthy', 'unhealthy'))
    need(type(value.get('networks')) is dict and type(value.get('ports')) is dict)
    need(type(value.get('volumes')) is list and all(v is None or type(v) is str for v in value['volumes']))
    return value


def resources(value):
    # Internal comparison only. No private resource names or network values exported.
    return (set(value['networks']), set(v for v in value['volumes'] if v))


def summarize_database(value):
    if type(value) is dict and set(value) == {'outcome'}:
        need(value['outcome'] in ('unknown', 'not_found'))
        return ({'availability': 'not_found', 'catalog_validation': 'canonical_absence'}
                if value['outcome'] == 'not_found' else {'availability': 'unknown'})
    need(type(value) is dict and set(value) == {'outcome', 'catalog_validation', 'readonly', 'role_safe', 'history', 'triggers'}
         and value['outcome'] == 'observed' and value['catalog_validation'] == 'locked_heap_table'
         and value['readonly'] == 'on' and value['role_safe'] is True)
    history = value['history']
    need(type(history) is list and 0 < len(history) <= 160)
    ranks = set()
    versions = set()
    for row in history:
        need(type(row) is dict and set(row) == {'rank', 'version', 'checksum', 'success'})
        need(type(row['rank']) is int and row['rank'] not in ranks)
        ranks.add(row['rank'])
        version = row['version']
        need(version is None or (type(version) is str and re.fullmatch(r'[0-9]{1,3}(?:\.[0-9]{1,3}){0,3}', version)))
        need(row['checksum'] is None or (type(row['checksum']) is int and -2147483648 <= row['checksum'] <= 2147483647))
        need(type(row['success']) is bool)
        if version is not None:
            need(version not in versions)
            versions.add(version)
    triggers = value['triggers'] or []
    need(type(triggers) is list and len(triggers) <= len(TRIGGERS))
    clean = []
    for row in triggers:
        need(type(row) is dict and set(row) == {'name', 'table', 'enabled'})
        need(row['name'] in TRIGGERS and row['table'] in ('clients', 'client_clubs', 'target_bot_connections',
             'target_bot_bindings', 'target_maintenance_control_heads', 'target_maintenance_publications'))
        need(row['enabled'] in ('O', 'D', 'R', 'A'))
        clean.append(row)
    applied = {int(row['version']) for row in history
               if row['success'] and row['version'] is not None and row['version'].isdigit()}
    failed = any(not row['success'] for row in history)
    return {'availability': 'observed', 'schema': 'public', 'transaction_read_only': True,
        'catalog_validation': 'locked_heap_table', 'history': sorted(history, key=lambda row: row['rank']),
        'latest_version': max(applied) if applied else None,
        'pending_target': [v for v in (77, 78, 79) if v not in applied],
        'failed_history': failed, 'relevant_triggers': clean,
        'inflight_migration': 'unknown'}


class Files:
    """Finite no-follow metadata reads; no .env/Compose parsing or backup access."""
    def __init__(self):
        self.fds = []
        self.edges = []

    def directory(self, path):
        parent = os.open('/', os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        self.fds.append(parent)
        for name in path.split('/')[1:]:
            child = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent)
            self.fds.append(child)
            s = os.fstat(child)
            need(s.st_uid in (0, os.geteuid()) and not s.st_mode & 0o022)
            self.edges.append((parent, name, child, (s.st_dev, s.st_ino, s.st_mtime_ns, s.st_ctime_ns)))
            parent = child
        return parent

    def names(self, fd):
        values = []
        with os.scandir(fd) as entries:
            for entry in entries:
                need(len(values) < 128)
                values.append(entry.name)
        return values

    def record(self, parent, name):
        fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent)
        self.fds.append(fd)
        before = os.fstat(fd)
        need(stat.S_ISREG(before.st_mode) and before.st_nlink == 1 and before.st_uid in (0, os.geteuid())
             and stat.S_IMODE(before.st_mode) == 0o600 and before.st_size <= 4096)
        raw = os.read(fd, 4097)
        after = os.fstat(fd)
        need((before.st_dev, before.st_ino, before.st_mode, before.st_uid, before.st_size,
              before.st_mtime_ns, before.st_ctime_ns) ==
             (after.st_dev, after.st_ino, after.st_mode, after.st_uid, after.st_size,
              after.st_mtime_ns, after.st_ctime_ns) and len(raw) == before.st_size)
        return raw

    def observe(self):
        parent = self.directory(ROOT + '/.clubs-bot-release-state')
        stage = self.directory(ROOT + '/.clubs-bot-release-state/stage')
        names = self.names(stage)
        prefix = 'clubs-bot-schema-stage'
        possible = (prefix + '.lock', prefix + '.finalizing')
        state = 'none_observed'
        checkpoint = 'not_read'
        if any(v in names for v in possible):
            need(sum(v in names for v in possible) == 1)
            state = 'present'
            selected = next(v for v in possible if v in names)
            directory = self.directory(ROOT + '/.clubs-bot-release-state/stage/' + selected)
            checkpoint = self.record(directory, 'checkpoint').decode('ascii').strip()
            need(checkpoint in ('claimed', 'prepared', 'override_published', 'app_quiesced',
                 'migration_started', 'migration_completed', 'candidate_started', 'completed', 'aborted'))
        ledger = self.directory(ROOT + '/.clubs-bot-release-state/stage/' + prefix + '.migration-ledgers')
        entries = self.names(ledger)
        need(all(re.fullmatch(r'[0-9]+-[0-9]+\.(?:ledger|outcome)', v) for v in entries))
        # Stat only: do not deserialize historical owner/revision/paths/identifiers.
        for name in entries:
            info = os.stat(name, dir_fd=ledger, follow_symlinks=False)
            need(stat.S_ISREG(info.st_mode) and info.st_nlink == 1 and info.st_uid in (0, os.geteuid())
                 and stat.S_IMODE(info.st_mode) == 0o600 and info.st_size <= 4096)
        for p, name, fd, before in self.edges:
            now = os.stat(name, dir_fd=p, follow_symlinks=False)
            need((now.st_dev, now.st_ino, now.st_mtime_ns, now.st_ctime_ns) == before)
        return {'artifact_observation': 'observed', 'active_state': state, 'checkpoint': checkpoint,
            'anchor_present': 'active-candidate.anchor' in self.names(parent),
            'retained_ledgers': sum(v.endswith('.ledger') for v in entries),
            'outcome_files': sum(v.endswith('.outcome') for v in entries),
            'fsm_authority': 'unknown', 'migration_ledger_integrity': 'unknown',
            'unfinished_release': 'unknown', 'backup_recovery': 'unknown'}

    def close(self):
        for fd in reversed(self.fds):
            os.close(fd)


def observe(commands, files):
    result = {'protocol': 'clb-stage-snapshot-v1', 'environment': 'stage',
        'result': 'observed_with_unknowns', 'stage_ready': False,
        'target_revision': TARGET_REVISION, 'target_digest': TARGET_DIGEST,
        'shared_host_lock': 'held_existing_exclusive',
        'clubs': {'availability': 'unknown'}, 'database': {'availability': 'unknown'},
        'release': {'artifact_observation': 'unknown', 'fsm_authority': 'unknown',
            'migration_ledger_integrity': 'unknown', 'unfinished_release': 'unknown', 'backup_recovery': 'unknown'},
        'neighbor': {'identity_authority': 'unknown', 'isolation': 'unknown'}}
    try:
        inventory = [container_ok(v) for v in commands.containers()]
        selected = [v for v in inventory if v.get('project') == PROJECT]
        need(len(selected) == 2 and {v.get('service') for v in selected} == {'app', 'db'})
        need(all(v.get('root') == ROOT for v in selected))
        app = next(v for v in selected if v['service'] == 'app')
        db = next(v for v in selected if v['service'] == 'db')
        image = commands.image(app['image'])
        need(image.get('id') == app['image'] and re.fullmatch('[0-9a-f]{40}', image.get('revision', '')))
        digests = image.get('digests')
        need(type(digests) is list)
        digests = [v[len(IMAGE)+1:] for v in digests if type(v) is str and v.startswith(IMAGE + '@')]
        need(len(digests) == 1 and re.fullmatch('sha256:[0-9a-f]{64}', digests[0]))
        bindings = app['ports'].get('8080/tcp')
        need(bindings == [{'HostIp': '127.0.0.1', 'HostPort': '8081'}])
        result['clubs'] = {'availability': 'observed', 'project': PROJECT,
            'service': 'app', 'container_identity_sha256': identity(app['id']),
            'db_container_identity_sha256': identity(db['id']), 'status': app['status'],
            'container_health': app['health'], 'running': app['running'],
            'revision': image['revision'], 'image_digest': digests[0],
            'target_revision_matches': image['revision'] == TARGET_REVISION,
            'target_digest_matches': digests[0] == TARGET_DIGEST,
            'health_http': commands.health('health'), 'ready_http': commands.health('ready'),
            'compose_status': 'exact_two_service_labels_observed', 'compose_config': 'not_evaluated'}
        others = [v for v in inventory if v.get('project') != PROJECT]
        shared = False
        for neighbor in others:
            shared |= any(bool(a & b) for v in selected for a, b in zip(resources(v), resources(neighbor)))
        result['neighbor'] = {'identity_authority': 'unknown', 'isolation': 'unknown',
            'observed_other_containers': len(others), 'shared_named_volume_or_network_observed': shared,
            'candidate_container_identity_sha256': [identity(v['id']) for v in others
                if v['ports'].get('8080/tcp') == [{'HostIp': '127.0.0.1', 'HostPort': '8080'}]]}
        try:
            result['neighbor']['loopback_8080_health_http'] = commands.health('health', 8080)
        except CleanupUnavailable:
            raise
        except (Unavailable, OSError, ValueError):
            pass  # Absent neighbor HTTP is not evidence against the Clubs observation.
        try:
            result['database'] = summarize_database(commands.database(db['id']))
        except CleanupUnavailable:
            raise
        except (Unavailable, OSError, ValueError, TypeError, KeyError):
            pass
        # Re-read exact metadata, including container/image identity and resources.
        need(inventory == [container_ok(v) for v in commands.containers()])
    except CleanupUnavailable:
        raise
    except (Unavailable, OSError, ValueError, TypeError, KeyError):
        result['clubs'] = {'availability': 'unknown'}
        result['database'] = {'availability': 'unknown'}
        result['neighbor'] = {'identity_authority': 'unknown', 'isolation': 'unknown'}
    try:
        result['release'] = files.observe()
    except (Unavailable, OSError, ValueError, UnicodeError):
        pass
    return result


def main(lock_type):
    def interrupted(_sig, _frame):
        raise Cancelled()
    for sig in (signal.SIGINT, signal.SIGTERM, signal.SIGHUP, signal.SIGALRM):
        signal.signal(sig, interrupted)
    signal.alarm(65)
    files = Files()
    try:
        with lock_type() as lock:
            lock.check()
            result = observe(Commands(), files)
            lock.check()
            raw = json.dumps(result, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode('ascii')
            need(len(raw) <= LIMIT)
            print(raw.decode('ascii'))
            return 0
    except CleanupUnavailable:
        print('{"environment":"stage","protocol":"clb-stage-snapshot-v1","result":"unavailable","stage_ready":false,"reason":"cleanup"}')
        return 0  # Deliver sanitized refusal; runner marks the workflow unsuccessful.
    except Cancelled:
        return 2
    except Exception:
        # No exception messages/paths/raw Docker, SQL or SSH stderr.
        print('{"environment":"stage","protocol":"clb-stage-snapshot-v1","result":"unavailable","stage_ready":false}')
        return 2
    finally:
        files.close()
        signal.alarm(0)
