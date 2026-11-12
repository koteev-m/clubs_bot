"""Offline TARGET protocol acceptance; no production Kotlin, database or actual Telegram identity."""
import ast
import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from urllib.parse import parse_qsl

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'scripts/tests/target_identity_reference.py'
spec = importlib.util.spec_from_file_location('target_identity_reference', SCRIPT)
model = importlib.util.module_from_spec(spec)
spec.loader.exec_module(model)


class TargetIdentityContractTest(unittest.TestCase):
    def setUp(self):
        self.fixture = model.load_fixture()
        self.state = copy.deepcopy(self.fixture['state'])
        self.cases = {case['id']: case for case in self.fixture['cases']}

    def result(self, name):
        return model.run_case(self.state, self.cases[name])

    def test_all_68_literal_scenario_oracles_and_atomic_denials(self):
        self.assertEqual(len(self.cases), 68)
        original = copy.deepcopy(self.fixture)
        for case in self.cases.values():
            with self.subTest(case=case['id']):
                r = model.run_case(self.state, case)
                self.assertEqual(r['outcomes'], case['expected_outcomes'])
                self.assertEqual({k: sorted(v) for k, v in r['TARGET'].items()}, case['expected_permissions'])
                self.assertTrue(r['denials_atomic'])
                self.assertEqual(r['external_writes'], 0)
        self.assertEqual(original, self.fixture)
        self.assertEqual(original['state'], self.state)

    def test_hmac_independent_openssl_golden_vector_and_signature_coverage(self):
        raw = model.sign_identity(7, 'canonical', 1000000)
        golden = {'algorithm': 'HMAC-SHA256', 'digest': '2b75c717562ab9cfa7984725122760746775d6f9dd6e55bac20ce7787935f5af'}
        self.assertEqual(dict(parse_qsl(raw))['hash'], golden['digest'])
        self.assertEqual(model.verify_identity(raw, 'canonical', 1000000), (7, 1000300))
        for invalid in (raw.replace('synthetic-signature', 'changed'), raw + '&user=%7B%22id%22%3A7%7D', raw + '&field=%ZZ'):
            with self.subTest(raw_kind=len(invalid)):
                with self.assertRaises(model.Denied):
                    model.verify_identity(invalid, 'canonical', 1000000)
        with self.assertRaises(model.Denied):
            model.verify_identity(raw, 'foreign', 1000000)

    def test_identity_exact_freshness_boundary_and_positive_integer_uid(self):
        for age in (-1, 300, 301):
            with self.subTest(age=age), self.assertRaises(model.Denied):
                model.verify_identity(model.sign_identity(7, 'canonical', 1000000 - age), 'canonical', 1000000)
        for user in (True, 0, -1, 2**63, '7'):
            with self.subTest(user=user), self.assertRaises(model.Denied):
                model.verify_identity(model.sign_identity(user, 'canonical', 1000000), 'canonical', 1000000)
        self.assertEqual(model.verify_identity(model.sign_identity(7, 'canonical', 999701), 'canonical', 1000000)[0], 7)

    def test_unknown_null_cutoff_does_not_become_zero(self):
        state = copy.deepcopy(self.state); cache = {}
        case = self.cases['club-manager-appoints-promoter']
        state, _ = model.transact(state, case['operations'][0], cache)
        state['memberships']['c1/7']['floor'] = None
        before = copy.deepcopy(state)
        after, outcome = model.transact(state, case['operations'][1], cache)
        self.assertEqual(outcome, 'FRONTIER_UNKNOWN')
        self.assertEqual(after, before)

    def test_unclaimed_link_fenced_without_any_admission_after_platform_restore(self):
        r = self.result('unclaimed-other-club-without-admission-after-platform-restore')
        self.assertNotIn('c1/B/7', r['state']['admissions'])
        self.assertEqual(r['state']['memberships']['c1/7'], dict(active=True, version=3, floor=1))
        self.assertEqual(r['state']['invites']['i1']['status'], 'ISSUED')
        self.assertEqual(r['outcomes'][-1], 'INVITATION_FENCED')
        self.assertEqual(r['TARGET']['c2/Z/7'], r['CURRENT']['c2/Z/7'])

    def test_unclaimed_before_full_disable_has_its_own_club_fence(self):
        r = self.result('unclaimed-before-full-disable')
        fence = r['state']['admissions']['c1/A/7']
        self.assertEqual((fence['status'], fence['epoch'], fence['floor']), ('DISABLED', 2, 2))
        self.assertEqual(r['state']['memberships']['c1/7']['active'], True)
        self.assertEqual(r['state']['invites']['pending']['claimant'], None)

    def test_staff_full_disable_requires_an_existing_admission(self):
        before = copy.deepcopy(self.state)
        with self.assertRaisesRegex(model.Denied, 'ADMISSION_REQUIRED'):
            model.event(self.state, {'op': 'full_disable', 'client': 'c1', 'club': 'A', 'user': 7})
        self.assertEqual(before, self.state)

    def test_reacquired_issuer_role_cannot_adopt_original_authority_reference(self):
        r = self.result('claimed-before-issuer-revoke')
        state = r['state']
        source = copy.deepcopy(state['sources']['cmA'])
        source.update(status='ACTIVE', version=1)
        state['sources']['new-cm-source'] = source  # a separate explicit external regrant in artificial values
        before = copy.deepcopy(state)
        command = self.cases['claimed-before-issuer-revoke']['operations'][-1]
        after, outcome = model.transact(state, command, {})
        self.assertEqual(outcome, 'ISSUER_SOURCE_CHANGED')
        self.assertEqual(after, before)
        self.assertEqual(after['sources']['cmA']['status'], 'REVOKED')

    def test_readmission_uses_new_ids_epoch_ack_and_keeps_delegation_terminal(self):
        r = self.result('explicit-readmission-new-source-old-tombstones')
        s = r['state']
        self.assertEqual(s['sources']['target-i1']['status'], 'REVOKED')
        self.assertEqual(s['sources']['target-readmit']['status'], 'ACTIVE')
        self.assertEqual(s['sources']['target-readmit']['epoch'], 3)
        self.assertEqual(s['delegations']['calendar-old']['status'], 'REVOKED')
        self.assertNotIn('client.club.calendar.write', r['TARGET']['c1/A/7'])
        self.assertNotIn('client.application.manage', r['TARGET']['c1/A/7'])
        failed = self.result('readmission-requires-exact-regrant-ack')
        self.assertNotIn('target-readmit', failed['state']['sources'])
        self.assertEqual(failed['state']['admissions']['c1/A/7']['status'], 'DISABLED')

    def test_source_revoke_preserves_other_lawful_shared_permission_source(self):
        r = self.result('additional-role-then-revoke-one-source')
        self.assertEqual(r['TARGET']['c1/A/7']['night.read'], ['ROLE:target-entry'])
        self.assertEqual(r['state']['sources']['target-i1']['status'], 'REVOKED')
        self.assertEqual(r['state']['sources']['target-entry']['status'], 'ACTIVE')
        self.assertEqual(r['state']['admissions']['c1/A/7']['epoch'], 1)

    def test_replay_after_new_epoch_returns_current_state_without_reactivation(self):
        case = self.cases['old-receipt-after-new-epoch']
        before = model.run_case(self.state, dict(case, operations=case['operations'][:-1]))
        after = self.result(case['id'])
        self.assertEqual(before['state'], after['state'])
        self.assertEqual(after['outcomes'][-1], 'REPLAY_CURRENT')
        self.assertEqual(after['state']['sources']['target-i1']['status'], 'REVOKED')
        self.assertEqual(after['TARGET']['c1/A/7']['night.read'], ['ROLE:target-readmit'])

    def test_late_audit_failure_rolls_back_consumption_source_fence_and_receipt(self):
        case = self.cases['late-audit-failure-no-partial-issue']
        state = copy.deepcopy(self.state); cache = {}
        for op in case['operations'][:2]:
            state, outcome = model.transact(state, op, cache)
            self.assertEqual(outcome, 'COMMITTED')
        before = copy.deepcopy(state)
        state, outcome = model.transact(state, case['operations'][2], cache)
        self.assertEqual(outcome, 'AUDIT_FAILURE_ROLLBACK')
        self.assertEqual(state, before)
        self.assertNotIn('target-i1', state['sources'])
        self.assertNotIn('c1/A/7', state['admissions'])
        state, outcome = model.transact(state, case['operations'][3], cache)
        self.assertEqual(outcome, 'COMMITTED')
        self.assertEqual(len(state['receipts']), 3)
        self.assertEqual(state['invites']['i1']['status'], 'CONSUMED')

    def test_old_confirm_envelope_cannot_bypass_a_refreshed_claim_version(self):
        case = self.cases['same-account-refresh-is-not-rebinding']
        state = copy.deepcopy(self.state); cache = {}
        for op in case['operations'][:2]:
            state, _ = model.transact(state, op, cache)
        confirm = case['operations'][-1]
        model.packet(state, confirm, cache)  # original confirmed-view CAS before refreshed proof
        state, _ = model.event(state, dict(case['operations'][2], seconds=1))
        state, _ = model.transact(state, case['operations'][3], cache)
        before = copy.deepcopy(state)
        after, outcome = model.transact(state, confirm, cache)
        self.assertEqual(outcome, 'VERSION_CONFLICT')
        self.assertEqual(after, before)
        fresh = dict(confirm, key='fresh-confirm-after-claim-refresh')
        after, outcome = model.transact(state, fresh, cache)
        self.assertEqual(outcome, 'COMMITTED')
        self.assertEqual(after['invites']['i1']['status'], 'CONSUMED')

    def test_dependency_revoke_fences_pending_invite_and_requires_explicit_overlap_ack(self):
        state = self.result('delegation-is-separate-from-role-invitation')['state']; cache = {}
        template = self.cases['owner-appoints-club-manager']['operations']
        issue = dict(template[0], invite='pending-cm', key='issue-pending-cm', mode='ADDITIONAL', target=7)
        claim = dict(template[1], invite='pending-cm', key='claim-pending-cm')
        confirm = dict(template[2], invite='pending-cm', key='confirm-pending-cm')
        for op in (issue, claim):
            state, result = model.transact(state, op, cache); self.assertEqual(result, 'COMMITTED')
        before = state['admissions']['c1/A/7']['version']
        state, _ = model.event(state, {'op':'source_revoke','client':'c1','club':'A','user':3,'source':'cmA'})
        self.assertEqual(state['admissions']['c1/A/7']['version'], before + 1)
        self.assertEqual(state['sources']['target-i1']['status'], 'ACTIVE')
        self.assertEqual(state['delegations']['calendar']['status'], 'REVOKED')
        state, result = model.transact(state, confirm, cache); self.assertEqual(result, 'INVITATION_FENCED')
        for op in (dict(issue, invite='fresh-cm', key='issue-fresh-cm'), dict(claim, invite='fresh-cm', key='claim-fresh-cm')):
            state, result = model.transact(state, op, cache); self.assertEqual(result, 'COMMITTED')
        fresh = dict(confirm, invite='fresh-cm', key='confirm-fresh-cm')
        state, result = model.transact(state, fresh, cache); self.assertEqual(result, 'REGRANT_ACK_REQUIRED')
        explicit = dict(fresh, key='confirmed-overlap-ack', regrant_ack=['client.club.calendar.read','client.club.calendar.write'])
        state, result = model.transact(state, explicit, cache); self.assertEqual(result, 'COMMITTED')
        self.assertEqual(state['delegations']['calendar']['status'], 'REVOKED')

    def test_same_key_cannot_change_frozen_intent_even_before_a_commit(self):
        op = copy.deepcopy(self.cases['club-manager-appoints-promoter']['operations'][0])
        cache = {}
        model.packet(self.state, op, cache)
        op.update(role='ENTRANCE_MANAGER', policy='ENTRANCE_MANAGER')
        after, outcome = model.transact(self.state, op, cache)
        self.assertEqual(outcome, 'INTENT_DENIED')
        self.assertEqual(after, self.state)

    def test_hash_at_rest_cannot_be_presented_as_invitation_secret(self):
        case = self.cases['club-manager-appoints-promoter']; cache = {}
        state, _ = model.transact(self.state, case['operations'][0], cache)
        op = dict(case['operations'][1], secret=state['invites']['i1']['secret_hash'])
        after, outcome = model.transact(state, op, cache)
        self.assertEqual(outcome, 'SECRET_CONSUMED_OR_INVALID')
        self.assertEqual(after, state)

    def test_explicit_reissue_preserves_already_terminal_invitation_record(self):
        case = self.cases['reissue-rotates-id-seq-and-secret']
        for terminal in ('REVOKED', 'EXPIRED'):
            with self.subTest(terminal=terminal):
                state, outcome = model.transact(self.state, case['operations'][0], {})
                self.assertEqual(outcome, 'COMMITTED')
                state['invites']['i1']['status'] = terminal
                state['invites']['i1']['version'] += 1
                before = copy.deepcopy(state['invites']['i1'])
                after, outcome = model.transact(state, case['operations'][1], {})
                self.assertEqual(outcome, 'COMMITTED')
                self.assertEqual(after['invites']['i1'], before)
                self.assertEqual(after['invites']['i2']['seq'], 2)
                self.assertNotEqual(after['invites']['i1']['secret_hash'], after['invites']['i2']['secret_hash'])

    def test_no_raw_identity_secret_or_envelope_persisted_or_rendered(self):
        report = model.render(self.fixture)
        for case in self.cases.values():
            state = model.run_case(self.state, case)['state']
            data = json.dumps(state)
            for marker in ('offline-only-invitation:', 'offline-fixture:', 'offline-intent-envelope', 'auth_date=', 'synthetic-signature'):
                self.assertNotIn(marker, data)
                self.assertNotIn(marker, report)

    def test_finance_requires_club_and_shift_and_does_not_create_network_access(self):
        granted = self.result('finance-exact-shift-no-neighbor-club')
        self.assertEqual(granted['TARGET']['c1/A/7']['finance.report.read'], ['ROLE:target-i1'])
        self.assertEqual(granted['TARGET']['c1/B/7'], {})
        self.assertEqual(self.result('finance-denies-other-shift')['TARGET']['c1/A/7'], {})
        self.assertEqual(self.result('finance-requires-explicit-selector')['outcomes'], ['SELECTOR_DENIED'])

    def test_effective_permissions_revalidate_parent_client_and_mapping(self):
        r = self.result('club-manager-appoints-promoter')
        state = r['state']
        self.assertTrue(model.effective(state, 'c1/A/7'))
        state['clubs']['A'] = 'c2'
        self.assertEqual(model.effective(state, 'c1/A/7'), {})
        state['clubs']['A'] = 'c1'
        state['clients']['c1']['active'] = False
        self.assertEqual(model.effective(state, 'c1/A/7'), {})

    def test_evidence_fingerprints_fail_closed_without_touching_repository(self):
        for mutation, error in (('algorithm', 'SOURCE_DRIFT'), ('digest', 'SOURCE_DRIFT'), ('missing', 'SOURCE_SET_DRIFT')):
            value = copy.deepcopy(self.fixture)
            if mutation == 'missing':
                value['source_sha256'].pop(model.CONTRACT)
            else:
                value['source_sha256'][model.CONTRACT][mutation] = 'invalid'
            with tempfile.TemporaryDirectory(prefix='clb-target-reference-') as directory:
                path = Path(directory) / 'artificial.json'; path.write_text(json.dumps(value))
                with patch.object(model, 'FIXTURE', path):
                    with self.assertRaisesRegex(model.Denied, error):
                        model.load_fixture()

    def test_reference_has_no_apply_db_network_or_production_execution_surface(self):
        tree = ast.parse(SCRIPT.read_text())
        allowed = {'argparse', 'copy', 'hashlib', 'hmac', 'json', 'pathlib', 're', 'urllib.parse'}
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                self.assertTrue(all(alias.name in allowed for alias in node.names))
            if isinstance(node, ast.ImportFrom):
                self.assertIn(node.module, allowed)
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute):
                self.assertNotIn(node.func.attr, {'write_text', 'write_bytes', 'connect', 'execute', 'cursor', 'unlink', 'system'})
        result = subprocess.run([sys.executable, '-I', '-S', '-B', str(SCRIPT), '--apply'], capture_output=True, text=True)
        self.assertEqual(result.returncode, 2)

    def test_cli_report_is_deterministic_and_preserves_bundled_fixture(self):
        before = model.FIXTURE.read_bytes()
        result = subprocess.run([sys.executable, '-I', '-S', '-B', str(SCRIPT), '--format', 'markdown'], capture_output=True, check=True, text=True)
        self.assertEqual(result.stdout, model.render(self.fixture))
        self.assertEqual(model.render(self.fixture), model.render(self.fixture))
        self.assertEqual(before, model.FIXTURE.read_bytes())
        self.assertIn('PROPOSED', result.stdout)


if __name__ == '__main__':
    unittest.main()
