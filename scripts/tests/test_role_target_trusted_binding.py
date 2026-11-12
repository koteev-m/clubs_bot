"""Synthetic acceptance of PROPOSED binding semantics, not SQL or real credential evidence."""
import copy
import json
from pathlib import Path
import tempfile
import unittest

from scripts.tests import trusted_binding_reference as model


class TrustedBindingContractTest(unittest.TestCase):
    def setUp(self):
        self.fixture = model.load_fixture()
        self.initial = self.fixture['state']
        self.cases = {c['id']: c for c in self.fixture['cases']}

    def result(self, name):
        return model.run_case(self.initial, self.cases[name])

    def test_all_literal_oracles_are_bounded_atomic_and_do_not_change_legacy_permissions(self):
        original = copy.deepcopy(self.fixture)
        self.assertEqual(len(self.cases), 60)
        for case in self.fixture['cases']:
            with self.subTest(case=case['id']):
                result = self.result(case['id'])
                self.assertEqual(result['outcomes'], case['expected_outcomes'])
                self.assertEqual(result['mutations'], case['expected_mutations'])
                self.assertTrue(result['denied_atomic'])
                self.assertEqual(result['external_writes'], 0)
                self.assertEqual(result['state']['legacy_permissions'], self.initial['legacy_permissions'])
        self.assertEqual(self.fixture, original)

    def test_platform_is_multi_client_and_platform_identity_is_not_target_authority(self):
        result = self.result('platform-two-independent-clients')
        root = result['state']['connections']['platform']
        self.assertIsNone(root['client'])
        self.assertIsNone(result['state']['bindings'][root['current_binding']]['clubs'])
        self.assertEqual({x['pins']['client'] for x in result['state']['mutations']}, {'c1', 'c2'})
        self.assertEqual(self.result('platform-identity-without-authority')['mutations'], 0)

    def test_branded_new_club_does_not_expand_finite_set_or_other_client(self):
        result = self.result('new-club-never-inherits-branded-scope')
        self.assertIn('D', result['state']['mappings'])
        self.assertEqual(result['state']['bindings']['brand-first']['clubs'], ['A', 'B'])
        self.assertEqual(self.result('branded-other-client')['mutations'], 0)
        self.assertEqual(self.result('branded-neighbor-club')['mutations'], 0)

    def test_aba_same_created_at_is_distinguished_only_by_durable_epoch(self):
        result = self.result('same-created-at-mapping-aba')
        self.assertEqual(result['state']['mappings'], self.initial['mappings'])
        self.assertEqual(result['state']['clients']['c1']['mapping_epoch'], 3)
        self.assertEqual(result['state']['clients']['c2'], self.initial['clients']['c2'])
        self.assertEqual(result['outcomes'][-1], 'STALE_PROOF')

    def test_rotation_retires_old_revision_and_failure_keeps_committed_disable(self):
        result = self.result('token-rotation-no-overlap')
        self.assertEqual(result['state']['bindings']['brand-first']['credential_ref'], 'ref-b2')
        self.assertIn('ref-b1', result['state']['retired_credentials'])
        self.assertEqual(result['state']['connections']['brand']['generation'], 3)
        failed = self.result('rotation-finish-audit-failure-stays-disabled')['state']
        self.assertEqual(failed['bindings']['brand-first']['status'], 'DISABLED')
        self.assertEqual(failed['connections']['brand']['generation'], 2)
        self.assertNotIn('maintenance:brand:99:finish', failed['receipts'])

    def test_new_reference_cannot_reintroduce_retired_secret_material(self):
        result = self.result('retired-material-under-new-reference')
        self.assertEqual(result['outcomes'][-1], 'CREDENTIAL_DENIED')
        state = result['state']
        self.assertEqual(state['bindings']['brand-first']['status'], 'DISABLED')
        old = state['credential_attestations']['ref-b1']['material']
        alias = state['credential_attestations']['ref-b1-alias']['material']
        self.assertEqual(old, alias)
        self.assertIn(old, state['retired_materials'])

    def test_terminal_binding_new_incarnation_preserves_root_identity_and_tombstone(self):
        state = self.result('replacement-keeps-old-tombstone-new-binding-id')['state']
        self.assertEqual(state['bindings']['brand-first']['status'], 'REVOKED')
        self.assertEqual(state['bindings']['brand-second']['status'], 'ACTIVE')
        self.assertEqual(state['connections']['brand']['current_binding'], 'brand-second')
        self.assertEqual(state['connections']['brand']['generation'], 4)
        self.assertEqual(state['connections']['brand']['bot'], self.initial['connections']['brand']['bot'])
        self.assertEqual(state['connections']['brand']['client'], 'c1')

    def test_both_ordered_conflicts_preserve_linearization_boundary(self):
        for earlier, later in [('confirm-before-revoke', 'revoke-before-confirm'),
                               ('confirm-before-scope-update', 'scope-update-before-confirm')]:
            self.assertEqual(self.result(earlier)['mutations'], 1)
            self.assertEqual(self.result(later)['mutations'], 0)
        self.assertEqual(self.result('two-concurrent-revokes')['state']['connections']['brand']['generation'], 2)

    def test_current_only_replays_never_reexecute_or_restore_authority(self):
        replay = self.result('target-replay-current-no-double-issuance')['state']
        self.assertEqual(len(replay['mutations']), 1)
        self.assertEqual(len(replay['audit']), 1)
        lifecycle = self.result('same-command-replay-does-not-bump-generation')['state']
        self.assertEqual(lifecycle['connections']['brand']['generation'], 2)
        self.assertEqual(len(lifecycle['audit']), 1)
        self.assertEqual(self.result('old-maintenance-receipt-after-reactivation')['outcomes'][-1], 'STALE_RECEIPT')

    def test_audit_failure_restores_all_binding_receipt_and_audit_state(self):
        state = self.result('revoke-audit-failure-exact-rollback')['state']
        self.assertEqual(state, self.initial)
        self.assertEqual(self.result('target-audit-failure-exact-rollback')['state'], self.initial)

    def test_faults_fail_closed_and_cancellation_propagates_without_publishing(self):
        state = copy.deepcopy(self.initial)
        state['fault'] = 'cancel'
        cache = {}
        op = self.cases['cancellation-does-not-publish-proof']['operations'][-1]
        before = copy.deepcopy(state)
        with self.assertRaises(model.Cancelled):
            model.transact(state, op, cache)
        self.assertEqual(state, before)
        self.assertEqual(cache, {})
        for name in ['source-missing', 'database-failure-after-preflight', 'configuration-failure', 'mapping-writer-coverage-unknown']:
            self.assertEqual(self.result(name)['mutations'], 0)

    def test_existing_signed_identity_validator_is_reused_with_strict_ttl(self):
        self.assertIs(model.identity.verify_identity, __import__('scripts.tests.target_identity_reference', fromlist=['verify_identity']).verify_identity)
        for name in ['strict-300-second-expiry', 'future-date-denied', 'wrong-bot-key', 'expires-after-wait']:
            self.assertEqual(self.result(name)['mutations'], 0)
        self.assertEqual(self.result('identity-only-without-staff')['outcomes'], ['IDENTITY_ONLY', 'ACTOR_DENIED'])

    def test_unknown_epoch_and_counter_overflow_never_become_zero(self):
        for epoch in (None, 0, True):
            state = copy.deepcopy(self.initial)
            state['clients']['c1']['mapping_epoch'] = epoch
            _, outcome = model.transact(state, self.cases['platform-exact-private-club-intent']['operations'][0], {})
            self.assertEqual(outcome, 'FENCE_UNKNOWN')
        for generation in (None, 0, True):
            state = copy.deepcopy(self.initial)
            state['connections']['brand']['generation'] = generation
            op = copy.deepcopy(self.cases['two-concurrent-revokes']['operations'][0])
            _, outcome = model.transact(state, op, {})
            self.assertEqual(outcome, 'FENCE_UNKNOWN')
        state = copy.deepcopy(self.initial)
        state['clients']['c1']['mapping_epoch'] = model.MAX_COUNTER
        after, outcome = model.transact(state, {'op': 'event', 'action': 'mapping_aba', 'client': 'c1', 'club': 'A'}, {})
        self.assertEqual(outcome, 'FENCE_OVERFLOW')
        self.assertEqual(after, state)

    def test_lifecycle_overflow_and_reused_binding_id_are_denied_atomically(self):
        state = copy.deepcopy(self.initial)
        state['connections']['brand'].update(version=model.MAX_COUNTER, generation=model.MAX_COUNTER)
        op = {'op': 'maintenance', 'action': 'revoke', 'root': 'brand', 'key': 'synthetic-overflow',
              'version': model.MAX_COUNTER, 'generation': model.MAX_COUNTER}
        after, outcome = model.transact(state, op, {})
        self.assertEqual((after, outcome), (state, 'FENCE_OVERFLOW'))
        result = self.result('revoked-never-reactivates')['state']
        op.update(action='replace', version=2, generation=2, new_binding='brand-first', credential_ref='ref-b2')
        after, outcome = model.transact(result, op, {})
        self.assertEqual((after, outcome), (result, 'ID_REUSE'))

    def test_source_pins_and_proposed_status_fail_closed(self):
        with tempfile.TemporaryDirectory(prefix='clb-binding-fixture-') as directory:
            path = Path(directory) / 'fixture.json'
            for change, expected in [('status', 'FIXTURE_STATUS'), ('pin', 'SOURCE_DRIFT'), ('source_set', 'EVIDENCE_SET')]:
                fixture = copy.deepcopy(self.fixture)
                if change == 'status':
                    fixture['status'] = 'ACCEPTED'
                elif change == 'pin':
                    fixture['source_sha256'][model.CONTRACT]['digest'] = '0' * 64
                else:
                    fixture['source_sha256'].pop(model.CONTRACT)
                path.write_text(json.dumps(fixture))
                with self.assertRaisesRegex(model.Denied, expected):
                    model.load_fixture(path)


if __name__ == '__main__':
    unittest.main()
