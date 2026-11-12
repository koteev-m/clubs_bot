"""Opt-in disposable contract probes, independent psql processes, never a provider.

Runs unchanged V078 on MINIMAL synthetic prerequisite tables, not a Flyway upgrade
or Kotlin integration test. The control head and command function are test models.
Only loopback PostgreSQL 16 / clb_client_test is supported. No credentials accepted.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import queue
import re
import subprocess
import threading
import time
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[2]
V078 = ROOT / 'core-data/src/main/resources/db/migration/postgresql/V078__inactive_target_bindings.sql'
FIXTURE = ROOT / 'scripts/tests/fixtures/role-target-lease-intent-probe.sql'
PORT = None
EVENTS = []


class SqlFailure(Exception):
    def __init__(self, lines):
        self.lines = lines
        self.state = next((m.group(1) for s in lines if
                           (m := re.search(r'ERROR:\s+([A-Z0-9]{5}):', s))), None)
        super().__init__('\n'.join(lines))


class Psql:
    def __init__(self, schema, label):
        self.name = 'lease-probe-' + label + '-' + uuid.uuid4().hex[:8]
        env = dict(os.environ, PGAPPNAME=self.name, PGCONNECT_TIMEOUT='3', PGSSLMODE='disable')
        # Clear libpq overrides. No inherited service/password/host/connection options.
        env = {k: v for k, v in env.items() if not k.startswith('PG') or
               k in ('PGAPPNAME', 'PGCONNECT_TIMEOUT', 'PGSSLMODE')}
        self.process = subprocess.Popen(
            ['psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1', '-v', 'VERBOSITY=verbose',
             '-h', '127.0.0.1', '-p', str(PORT), '-U', 'clb_client_test', '-d', 'clb_client_test'],
            env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, text=True, bufsize=1)
        self.lines = queue.Queue()
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()
        self.pending = None
        self.run(f'SET search_path=pg_catalog,"{schema}",pg_temp; '
                 "SET statement_timeout='8s'; SET lock_timeout='5s';")
        self.pid = int(self.run('SELECT pg_backend_pid();')[-1])
        EVENTS.append({'process': self.name, 'os_pid': self.process.pid, 'backend_pid': self.pid})

    def _read(self):
        for line in self.process.stdout:
            self.lines.put(line.rstrip('\n'))
        self.lines.put(None)

    def start(self, sql):
        assert self.pending is None
        self.pending = 'done_' + uuid.uuid4().hex
        self.process.stdin.write(sql + '\n\\echo ' + self.pending + '\n')
        self.process.stdin.flush()

    def finish(self):
        result = []
        marker = self.pending
        try:
            while True:
                line = self.lines.get(timeout=12)
                if line == marker:
                    return result
                if line is None:
                    error = SqlFailure(result)
                    EVENTS.append({'process': self.name, 'sqlstate': error.state,
                                   'failure': result})
                    raise error
                result.append(line)
        finally:
            self.pending = None

    def run(self, sql):
        self.start(sql)
        return self.finish()

    def close(self):
        if self.process.poll() is None:
            self.process.terminate()
        self.process.wait(timeout=10)
        self.reader.join(timeout=2)
        self.process.stdin.close()
        self.process.stdout.close()


class LeaseIntentPostgresProbe(unittest.TestCase):
    def setUp(self):
        self.schema = 'lease_' + uuid.uuid4().hex
        self.sessions = []
        self.monitor = self.session('monitor')
        self.assertEqual(self.monitor.run('SHOW server_version_num;')[-1][:2], '16')
        before, after = FIXTURE.read_text().replace('__SCHEMA__', self.schema).split('-- V078_BOUNDARY')
        migration = V078.read_text().replace('${flyway:defaultSchema}', self.schema)
        self.monitor.run('BEGIN;\n' + before + '\n' + migration + '\n' + after + '\nCOMMIT;')
        self.monitor.run(f"""BEGIN;
INSERT INTO {self.schema}.target_bot_connections(id,telegram_bot_id,kind,provenance_id,created_by,created_audit_id,last_audit_id)
 VALUES('00000000-0000-0000-0000-000000000010',70,'PLATFORM','00000000-0000-0000-0000-000000000012',1,1,1);
INSERT INTO {self.schema}.target_bot_bindings(id,root_id,provenance_id,created_audit_id)
 VALUES('00000000-0000-0000-0000-000000000011','00000000-0000-0000-0000-000000000010','00000000-0000-0000-0000-000000000012',1);
UPDATE {self.schema}.target_bot_connections SET current_binding_id='00000000-0000-0000-0000-000000000011';
COMMIT;""")

    def tearDown(self):
        for session in reversed(self.sessions[1:]):
            session.close()
        try:
            self.monitor.run(f'DROP SCHEMA "{self.schema}" CASCADE;')
        finally:
            self.monitor.close()

    def session(self, name):
        result = Psql(self.schema, name)
        self.sessions.append(result)
        return result

    def scalar(self, sql):
        return self.monitor.run(sql)[-1]

    def wait_locked(self, session):
        deadline = time.monotonic() + 4
        while time.monotonic() < deadline:
            rows = self.monitor.run("SELECT wait_event_type FROM pg_stat_activity "
                                    f"WHERE pid={session.pid};")
            if rows == ['Lock']:
                EVENTS.append({'wait_observed': session.name, 'backend_pid': session.pid})
                return
            time.sleep(.02)
        self.fail('No actual PostgreSQL lock wait: ' + session.name)

    def failure(self, session, sql, expected, message=None):
        with self.assertRaises(SqlFailure) as caught:
            session.run(sql)
        self.assertEqual(caught.exception.state, expected)
        if message:
            self.assertIn(message, str(caught.exception))

    def call(self, key, operation, version=1, head=1, failure=''):
        # All arguments are fixed synthetic test literals, never user input.
        assert re.fullmatch('[a-z0-9_-]+', key)
        assert operation in ('DISABLE', 'REVOKE', 'RETIRE')
        assert failure in ('', 'audit', 'receipt')
        return (f"SELECT {self.schema}.probe_maintain('{key}','{operation}',"
                f"{version},{head},'{failure}');")

    def lock_root(self, session):
        session.run(f'BEGIN; SELECT version FROM {self.schema}.target_bot_connections FOR UPDATE;')

    def test_two_revokes(self):
        self.ordered_commands('REVOKE', 'REVOKE')

    def test_disable_then_revoke(self):
        self.ordered_commands('DISABLE', 'REVOKE')

    def test_revoke_then_disable(self):
        self.ordered_commands('REVOKE', 'DISABLE')

    def attach_material(self):
        self.monitor.run(f"""BEGIN;
SELECT id FROM {self.schema}.target_bot_connections FOR UPDATE;
INSERT INTO {self.schema}.target_credential_materials(id,root_id,created_audit_id)
 VALUES('00000000-0000-0000-0000-000000000020','00000000-0000-0000-0000-000000000010',1);
INSERT INTO {self.schema}.target_credential_revisions(id,root_id,material_id,revision,verified_bot_id,attestation_id,created_audit_id)
 VALUES('00000000-0000-0000-0000-000000000021','00000000-0000-0000-0000-000000000010',
 '00000000-0000-0000-0000-000000000020',1,70,'00000000-0000-0000-0000-000000000022',1);
UPDATE {self.schema}.target_bot_bindings SET credential_ref='00000000-0000-0000-0000-000000000021';
COMMIT;""")

    def test_retirement_then_revoke(self):
        self.attach_material()
        self.ordered_commands('RETIRE', 'REVOKE')

    def test_revoke_then_retirement(self):
        self.attach_material()
        self.ordered_commands('REVOKE', 'RETIRE')

    def ordered_commands(self, first_op, second_op):
        version = int(self.scalar(f'SELECT version FROM {self.schema}.target_bot_connections;'))
        first, second = self.session('first'), self.session('second')
        self.assertNotEqual(first.process.pid, second.process.pid)
        first.run('BEGIN;')
        self.assertEqual(first.run(self.call('first', first_op, version)), ['APPLIED'])
        second.start('BEGIN; ' + self.call('second', second_op, version) + ' COMMIT;')
        self.wait_locked(second)
        first.run('COMMIT;')
        with self.assertRaises(SqlFailure) as caught:
            second.finish()
        self.assertIn('stale_cas', str(caught.exception))
        self.assertEqual(self.scalar(f'SELECT count(*) FROM {self.schema}.target_binding_commands;'), '1')

    def test_authority_fence_waits_through_commit_including_root_wait(self):
        root, command, publisher = self.session('root'), self.session('command'), self.session('publisher')
        self.lock_root(root)
        command.start('BEGIN; ' + self.call('pending', 'DISABLE') + ' COMMIT;')
        self.wait_locked(command)
        publisher.start(f'BEGIN; UPDATE {self.schema}.probe_control_head SET enabled=FALSE,generation=generation+1; COMMIT;')
        self.wait_locked(publisher)
        root.run('COMMIT;')
        self.assertIn('APPLIED', command.finish())
        publisher.finish()
        self.failure(self.session('old-replay'), self.call('pending', 'DISABLE'), 'P0001', 'authority_denied')

    def test_publisher_first_waiter_revalidates_current_authority(self):
        publisher, command = self.session('publisher'), self.session('waiter')
        publisher.run(f'BEGIN; UPDATE {self.schema}.probe_control_head SET enabled=FALSE,generation=generation+1;')
        command.start('BEGIN; ' + self.call('old', 'DISABLE') + ' COMMIT;')
        self.wait_locked(command)
        publisher.run('COMMIT;')
        with self.assertRaises(SqlFailure) as caught:
            command.finish()
        self.assertIn('authority_denied', str(caught.exception))
        self.assertEqual(self.scalar(f'SELECT count(*) FROM {self.schema}.target_binding_commands;'), '0')

    def test_two_process_shared_holders_block_publisher_until_both_release(self):
        one, two, publisher = self.session('share-one'), self.session('share-two'), self.session('exclusive')
        for holder in (one, two):
            holder.run(f'BEGIN; SELECT generation FROM {self.schema}.probe_control_head FOR SHARE;')
        publisher.start(f'UPDATE {self.schema}.probe_control_head SET generation=generation+1;')
        self.wait_locked(publisher)
        one.run('COMMIT;')
        self.wait_locked(publisher)
        two.run('ROLLBACK;')
        publisher.finish()
        self.assertEqual(self.scalar(f'SELECT generation FROM {self.schema}.probe_control_head;'), '2')

    def test_old_replica_bundle_cannot_follow_changed_control_generation(self):
        self.monitor.run(f'UPDATE {self.schema}.probe_control_head SET bundle_revision=2,generation=generation+1;')
        self.failure(self.session('old-bundle'), self.call('replica', 'DISABLE', head=2),
                     'P0001', 'authority_denied')

    def test_actor_remap_before_waiter_and_after_committed_command(self):
        remap, command = self.session('remap'), self.session('actor-waiter')
        remap.run(f'BEGIN; UPDATE {self.schema}.users SET telegram_user_id=9 WHERE id=1;')
        command.start('BEGIN; ' + self.call('remapped', 'DISABLE') + ' COMMIT;')
        self.wait_locked(command)
        remap.run('COMMIT;')
        with self.assertRaises(SqlFailure) as caught:
            command.finish()
        self.assertIn('actor_remapped', str(caught.exception))
        self.monitor.run(f'UPDATE {self.schema}.users SET telegram_user_id=7 WHERE id=1;')
        first, later = self.session('actor-first'), self.session('remap-later')
        first.run('BEGIN; ' + self.call('valid', 'DISABLE'))
        later.start(f'UPDATE {self.schema}.users SET telegram_user_id=9 WHERE id=1;')
        self.wait_locked(later)
        first.run('COMMIT;')
        later.finish()

    def test_actual_child_guard_nowait_and_child_first_fences_service(self):
        root = self.session('root-held')
        self.lock_root(root)
        self.failure(self.session('direct-child'),
                     f"UPDATE {self.schema}.target_bot_bindings SET status='DISABLED';", '55P03')
        root.run('COMMIT;')
        child, command = self.session('child-first'), self.session('service-later')
        child.run(f"BEGIN; UPDATE {self.schema}.target_bot_bindings SET status='DISABLED';")
        command.start('BEGIN; ' + self.call('stale', 'REVOKE') + ' COMMIT;')
        self.wait_locked(command)
        child.run('COMMIT;')
        with self.assertRaises(SqlFailure) as caught:
            command.finish()
        self.assertIn('stale_cas', str(caught.exception))
        self.assertEqual(self.scalar(f'SELECT version||\'/\'||generation FROM {self.schema}.target_bot_connections;'), '2/2')

    def test_mapping_aba_hostile_schema_and_rollback(self):
        other = self.session('mapping')
        other.run(f'CREATE TEMP TABLE clients(id BIGINT,target_mapping_epoch BIGINT); '
                  f'INSERT INTO pg_temp.clients VALUES(101,900); SET search_path=pg_temp,public,pg_catalog; '
                  f'BEGIN; DELETE FROM {self.schema}.client_clubs WHERE client_id=101; '
                  f'INSERT INTO {self.schema}.client_clubs(client_id,club_id) VALUES(101,1001); COMMIT;')
        self.assertEqual(self.scalar(f'SELECT target_mapping_epoch FROM {self.schema}.clients WHERE id=101;'), '3')
        self.assertEqual(other.run('SELECT target_mapping_epoch FROM pg_temp.clients;'), ['900'])
        other.run(f'BEGIN; DELETE FROM {self.schema}.client_clubs WHERE client_id=101; ROLLBACK;')
        self.assertEqual(self.scalar(f'SELECT epoch FROM {self.schema}.target_client_mapping_epochs WHERE client_id=101;'), '3')

    def test_audit_receipt_failure_and_lost_response_replay(self):
        for failure in ('audit', 'receipt'):
            self.failure(self.session(failure), self.call('same', 'DISABLE', failure=failure),
                         'P0001', 'synthetic_' + failure + '_failure')
            self.assertEqual(self.scalar(f'SELECT version FROM {self.schema}.target_bot_connections;'), '1')
            self.assertEqual(self.scalar(f'SELECT count(*) FROM {self.schema}.target_binding_commands;'), '0')
        self.assertEqual(self.monitor.run(self.call('same', 'DISABLE')), ['APPLIED'])
        self.assertEqual(self.session('restart-replay').run(self.call('same', 'DISABLE')), ['REPLAY_CURRENT'])
        version = int(self.scalar(f'SELECT version FROM {self.schema}.target_bot_connections;'))
        self.monitor.run(self.call('later', 'REVOKE', version))
        self.assertEqual(self.session('old-receipt').run(self.call('same', 'DISABLE')), ['REPLAY_STALE'])

    def test_crash_rolls_back_and_new_process_cannot_use_old_generation(self):
        crashed = self.session('crash-holder')
        crashed.run('BEGIN; ' + self.call('uncommitted', 'DISABLE'))
        self.monitor.run(f'SELECT pg_terminate_backend({crashed.pid});')
        self.assertEqual(self.scalar(f'SELECT version FROM {self.schema}.target_bot_connections;'), '1')
        self.assertEqual(self.scalar(f'SELECT count(*) FROM {self.schema}.target_binding_commands;'), '0')
        self.monitor.run(f'UPDATE {self.schema}.probe_control_head SET generation=generation+1;')
        self.failure(self.session('restarted-stale'), self.call('restart', 'DISABLE'), 'P0001', 'authority_denied')
        self.assertEqual(self.session('restarted-fresh').run(self.call('restart', 'DISABLE', head=2)), ['APPLIED'])

    def test_cancellation_rolls_back_waiting_command(self):
        root, command = self.session('cancel-root'), self.session('cancel-waiter')
        self.lock_root(root)
        command.start('BEGIN; ' + self.call('cancel', 'DISABLE') + ' COMMIT;')
        self.wait_locked(command)
        self.monitor.run(f'SELECT pg_cancel_backend({command.pid});')
        with self.assertRaises(SqlFailure) as caught:
            command.finish()
        self.assertEqual(caught.exception.state, '57014')
        root.run('COMMIT;')
        self.assertEqual(self.scalar(f'SELECT count(*) FROM {self.schema}.target_binding_commands;'), '0')

    def test_real_serialization_failure_then_new_attempt_loses_authority(self):
        first, peer = self.session('serializable'), self.session('peer')
        first.run(f'BEGIN ISOLATION LEVEL SERIALIZABLE; SELECT value FROM {self.schema}.probe_deadlock WHERE id=1;')
        peer.run(f'UPDATE {self.schema}.probe_deadlock SET value=value+1 WHERE id=1;')
        self.failure(first, f'UPDATE {self.schema}.probe_deadlock SET value=value+1 WHERE id=1;', '40001')
        self.monitor.run(f'UPDATE {self.schema}.probe_control_head SET enabled=FALSE,generation=generation+1;')
        self.failure(self.session('new-attempt'), self.call('retry', 'DISABLE'), 'P0001', 'authority_denied')

    def test_real_deadlock_aborts_one_whole_transaction(self):
        one, two = self.session('deadlock-one'), self.session('deadlock-two')
        one.run(f'BEGIN; UPDATE {self.schema}.probe_deadlock SET value=value+1 WHERE id=1;')
        two.run(f'BEGIN; UPDATE {self.schema}.probe_deadlock SET value=value+1 WHERE id=2;')
        one.start(f'UPDATE {self.schema}.probe_deadlock SET value=value+1 WHERE id=2; COMMIT;')
        self.wait_locked(one)
        two.start(f'UPDATE {self.schema}.probe_deadlock SET value=value+1 WHERE id=1; COMMIT;')
        errors = []
        for session in (one, two):
            try:
                session.finish()
            except SqlFailure as failure:
                errors.append(failure.state)
        self.assertEqual(errors, ['40P01'])
        self.assertEqual(self.scalar(f'SELECT sum(value) FROM {self.schema}.probe_deadlock;'), '2')
        self.monitor.run(f'UPDATE {self.schema}.probe_control_head SET enabled=FALSE,generation=generation+1;')
        self.failure(self.session('deadlock-retry'), self.call('retry', 'DISABLE'), 'P0001', 'authority_denied')

    def test_active_and_terminal_resurrection_still_rejected(self):
        self.failure(self.session('active'), f"UPDATE {self.schema}.target_bot_bindings SET status='ACTIVE';", '23514')
        self.monitor.run(self.call('terminal', 'REVOKE'))
        self.failure(self.session('resurrection'),
                     f"UPDATE {self.schema}.target_bot_bindings SET status='DISABLED',revoked_at=NULL,revoked_audit_id=NULL;", '23514')


def main():
    global PORT
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', required=True, type=int)
    parser.add_argument('--output', required=True, type=Path)
    arguments = parser.parse_args()
    if not 1024 < arguments.port < 65536:
        parser.error('Only an explicit disposable loopback high port is allowed')
    PORT = arguments.port
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(LeaseIntentPostgresProbe))
    report = {'scope': 'disposable contract model; unchanged V078; not production providers/JVM/Flyway',
              'tests': result.testsRun, 'failures': len(result.failures), 'errors': len(result.errors),
              'events': EVENTS, 'inputs': {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                                          for p in (V078, FIXTURE, Path(__file__))}}
    arguments.output.write_text(json.dumps(report, indent=2) + '\n')
    raise SystemExit(0 if result.wasSuccessful() else 1)


if __name__ == '__main__':
    main()
