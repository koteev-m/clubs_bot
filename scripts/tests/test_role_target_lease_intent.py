"""Bounded PROPOSED contract acceptance; not a production authority provider."""
import copy
import hashlib
import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
INVENTORY = ROOT / 'scripts/tests/fixtures/role-target-lease-intent-inventory.json'


def cyclic(edges):
    pending = set(node for edge in edges for node in edge)
    while pending:
        ready = {node for node in pending if not any(b == node and a in pending for a, b in edges)}
        if not ready:
            return True
        pending -= ready
    return False


def admit(state):
    """Literal admission model only. Signed HMAC proof is supplied, never manufactured here."""
    if not state['verified'] or not 0 <= state['age'] < 300:
        return 'AUTHENTICATION_DENIED'
    if state['bot'] != state['trusted_bot']:
        return 'AUTHENTICATION_DENIED'
    if (not state['head_enabled'] or state['head_generation'] != state['expected_head'] or
            state['local_bundle'] != state['head_bundle']):
        return 'CONTEXT_DENIED'
    if not state['owner'] > 0 or not state['actor'] == state['owner'] == state['head_owner']:
        return 'AUTHORITY_DENIED'
    if state['kind'] != 'PLATFORM' or state['client'] is not None:
        return 'SCOPE_DENIED'
    selection = state['server_manifest'].get((state['root'], state['incarnation']))
    if selection is None or state['operation'] not in selection:
        return 'INTENT_DENIED'
    if state['expected_fence'] != state['current_fence']:
        return 'STALE'
    if state['status'] not in ('PENDING', 'DISABLED'):
        return 'STATE_DENIED'
    if state['operation'] == 'RETIRE_SELECTED_MATERIAL' and (
            state['material'] != state['selected_material'] or state['retired']):
        return 'MATERIAL_DENIED'
    return 'ADMITTED_MODEL_ONLY'


class LeaseIntentContractTest(unittest.TestCase):
    def setUp(self):
        self.inventory = json.loads(INVENTORY.read_text())
        self.state = dict(verified=True, age=1, bot='synthetic-live', trusted_bot='synthetic-live',
                          head_enabled=True, head_generation=1, expected_head=1,
                          local_bundle='bundle-1', head_bundle='bundle-1', owner=7, head_owner=7,
                          actor=7, kind='PLATFORM', client=None, root='root-a', incarnation='inc-a',
                          operation='DISABLE', expected_fence=(1, 1), current_fence=(1, 1),
                          status='PENDING', material='m1', selected_material='m1', retired=False,
                          server_manifest={('root-a', 'inc-a'): frozenset(
                              ('DISABLE', 'REVOKE', 'RETIRE_SELECTED_MATERIAL'))})

    def test_literal_positive_and_negative_admission_cases(self):
        cases = [({}, 'ADMITTED_MODEL_ONLY'), ({'age': 299}, 'ADMITTED_MODEL_ONLY'),
                 ({'verified': False}, 'AUTHENTICATION_DENIED'), ({'age': 300}, 'AUTHENTICATION_DENIED'),
                 ({'age': -1}, 'AUTHENTICATION_DENIED'), ({'bot': 'other'}, 'AUTHENTICATION_DENIED'),
                 ({'head_enabled': False}, 'CONTEXT_DENIED'), ({'expected_head': 2}, 'CONTEXT_DENIED'),
                 ({'local_bundle': 'old-replica'}, 'CONTEXT_DENIED'), ({'actor': 8}, 'AUTHORITY_DENIED'),
                 ({'owner': 0}, 'AUTHORITY_DENIED'), ({'head_owner': 8}, 'AUTHORITY_DENIED'),
                 ({'kind': 'BRANDED'}, 'SCOPE_DENIED'), ({'client': 101}, 'SCOPE_DENIED'),
                 ({'root': 'foreign'}, 'INTENT_DENIED'), ({'incarnation': 'old'}, 'INTENT_DENIED'),
                 ({'server_manifest': {}}, 'INTENT_DENIED'), ({'operation': 'ACTIVE'}, 'INTENT_DENIED'),
                 ({'current_fence': (2, 2)}, 'STALE'), ({'status': 'REVOKED'}, 'STATE_DENIED'),
                 ({'status': 'ACTIVE'}, 'STATE_DENIED'),
                 ({'operation': 'RETIRE_SELECTED_MATERIAL'}, 'ADMITTED_MODEL_ONLY'),
                 ({'operation': 'RETIRE_SELECTED_MATERIAL', 'retired': True}, 'MATERIAL_DENIED'),
                 ({'operation': 'RETIRE_SELECTED_MATERIAL', 'material': 'other'}, 'MATERIAL_DENIED')]
        for patch, expected in cases:
            with self.subTest(patch=patch):
                self.assertEqual(admit(dict(self.state, **patch)), expected)

    def test_pin_does_not_authorize_and_retry_cannot_refresh_pins_or_authority(self):
        for patch in ({'verified': False}, {'head_enabled': False}, {'actor': 8},
                      {'current_fence': (3, 3)}, {'head_generation': 2}):
            self.assertNotEqual(admit(dict(self.state, **patch)), 'ADMITTED_MODEL_ONLY')

    def test_process_local_configuration_alone_is_not_shared_authority(self):
        stale_replica = dict(self.state, head_owner=8)
        self.assertEqual(stale_replica['actor'], stale_replica['owner'])
        self.assertEqual(admit(stale_replica), 'AUTHORITY_DENIED')

    def test_model_does_not_mutate_state_or_create_permission(self):
        before = copy.deepcopy(self.state)
        self.assertEqual(admit(self.state), 'ADMITTED_MODEL_ONLY')
        self.assertEqual(self.state, before)
        self.assertNotIn('permissions', self.state)

    def test_inventory_exact_sources_and_unique_rows(self):
        rows = self.inventory['writers']
        self.assertEqual([row['id'] for row in rows], [f'W{i:02}' for i in range(1, 24)])
        paths = {path for row in rows for path in row['paths']}
        self.assertEqual(paths, set(self.inventory['source_sha256']))
        for path, checksum in self.inventory['source_sha256'].items():
            with self.subTest(path=path):
                self.assertEqual(checksum['algorithm'], 'SHA-256')
                self.assertEqual(hashlib.sha256((ROOT / path).read_bytes()).hexdigest(), checksum['digest'])
        for row in rows:
            for field in ('entry', 'locks', 'fence', 'participation'):
                self.assertTrue(row[field], (row['id'], field))

    def test_relevant_literal_sql_and_exposed_writers_are_classified(self):
        # Discovery is an alarm, not a proof against arbitrary dynamic SQL or future writers.
        tables = (r'users|user_roles|user_role_permissions|clients|client_clubs|client_memberships|'
                  r'client_membership_grants|client_club_grants|client_club_staff|'
                  r'client_club_access_state|client_role_assignments|client_permission_delegations|'
                  r'target_\w+')
        sql = re.compile(r'\b(?:INSERT\s+INTO|UPDATE|DELETE\s+FROM)\s+(?:' + tables + r')\b', re.I)
        exposed = re.compile(r'\b(?:UsersTable|Users|UserRolesTable|Clubs|ClientClubMappings)\s*\.'
                             r'\s*(?:insert\w*|update|delete\w*)\s*[({]', re.S)
        roots = ('core-data/src/main', 'app-bot/src/main')
        inputs = [path for base in roots for path in (ROOT / base).rglob('*')
                  if path.suffix in ('.kt', '.sql')]
        inputs.append(ROOT / 'scripts/seed-demo.sql')
        found = {str(path.relative_to(ROOT)) for path in inputs
                 if sql.search(path.read_text()) or exposed.search(path.read_text())}
        self.assertFalse(found - set(self.inventory['source_sha256']), sorted(found))

    def test_blocking_graph_is_acyclic_and_reversed_edges_are_not_waits(self):
        edges = self.inventory['blocking_order']
        self.assertFalse(cyclic(edges))
        self.assertTrue(cyclic(edges + [['actor', 'head']]))
        self.assertTrue(cyclic(edges + [['child', 'root']]))
        self.assertTrue(cyclic(edges + [['client', 'actor']]))
        self.assertEqual(self.inventory['nonblocking_reverse_edges'], [['child', 'root'], ['client', 'actor']])

    def test_transaction_and_intent_bound_interfaces_keep_missing_provider_default_denied(self):
        app = ROOT / 'app-bot/src/main/kotlin/com/example/bot/plugins/target/TargetCommitTimeMaintenanceAdapter.kt'
        source = app.read_text()
        self.assertIn('fun current(connection: Connection): MaintenanceMutationState?', source)
        self.assertIn('fun acquire(request: MaintenanceMutationRequest): MaintenanceMutationStateLease?', source)
        self.assertIn('override fun identity(connection: Connection)', source)
        self.assertIn('MaintenanceMutationLeaseSource { null }', source)
        self.assertIn('lease.current(connection)', source)
        self.assertIn(
            'MaintenanceMutationRequest(normalized,maintenanceHash(key),pin.actorTelegramId,pin.contextRevision',
            re.sub(r'\s+', '', source),
        )

    def test_active_and_legacy_boundaries_are_preserved_in_unchanged_guards(self):
        pg = (ROOT / 'core-data/src/main/resources/db/migration/postgresql/V078__inactive_target_bindings.sql').read_text()
        self.assertIn("IF NEW.status='ACTIVE' THEN", pg)
        self.assertIn('FOR UPDATE NOWAIT', pg)
        self.assertIn('SET search_path = pg_catalog, "${flyway:defaultSchema}", pg_temp', pg)
        self.assertNotIn('SECURITY DEFINER', pg)


if __name__ == '__main__':
    unittest.main()
