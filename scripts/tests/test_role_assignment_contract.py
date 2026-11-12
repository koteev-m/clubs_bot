"""Synthetic contract acceptance; no SQL/production RBAC is executed."""

import ast
import copy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/tests/role_assignment_reference.py"
spec = importlib.util.spec_from_file_location("assignment_reference", SCRIPT)
model = importlib.util.module_from_spec(spec)
spec.loader.exec_module(model)


class AssignmentContractTest(unittest.TestCase):
    def setUp(self):
        self.fixture = model.load_fixture()
        self.state = self.fixture["state"]
        self.original = copy.deepcopy(self.fixture)
        self.cases = {c["id"]: c for c in self.fixture["cases"]}

    def result(self, id):
        return model.report_case(self.state, self.cases[id])

    def test_all_38_independent_scenario_oracles_and_input_preservation(self):
        self.assertEqual(len(self.cases), 38)
        for id, case in self.cases.items():
            with self.subTest(id=id):
                result = self.result(id)
                self.assertEqual(result["outcomes"], case["expected_outcomes"])
                self.assertEqual(list(result["TARGET"][case["observe"][0]]), case["expected_permissions"])
                self.assertTrue(result["input_unchanged"])
                self.assertEqual(result["external_writes"], 0)
        self.assertEqual(self.fixture, self.original)

    def test_two_role_sources_permission_remains_after_one_revoke(self):
        r = self.result("two-role-sources-revoke-one-permission-remains")
        scope = "101/1001/7"
        self.assertEqual(r["CURRENT"][scope]["night.read"], ["ROLE:a-manager", "ROLE:b-entry"])
        self.assertEqual(r["TARGET"][scope]["night.read"], ["ROLE:b-entry"])
        self.assertNotIn("night.read", r["DIFFERENCE"][scope]["removed"])
        self.assertEqual(r["DIFFERENCE"][scope]["source_changes"]["night.read"]["after"], ["ROLE:b-entry"])

    def test_two_delegation_sources_keep_permission_and_provenance(self):
        r = self.result("two-delegation-sources-revoke-one-keeps-permission")
        p = "client.club.calendar.write"
        self.assertEqual(r["CURRENT"]["101/1001/7"][p], ["DELEGATION:d-one", "DELEGATION:d-two"])
        self.assertEqual(r["TARGET"]["101/1001/7"][p], ["DELEGATION:d-two"])
        self.assertEqual(r["target_state"]["delegations"]["d-one"]["status"], "REVOKED")

    def test_revoke_delegation_never_changes_role_source(self):
        r = self.result("revoke-only-delegation")
        self.assertEqual(r["target_state"]["assignments"]["a-manager"], self.state["assignments"]["a-manager"])
        self.assertEqual(r["target_state"]["delegations"]["d-calendar"]["status"], "REVOKED")
        self.assertNotIn("client.club.calendar.write", r["TARGET"]["101/1001/7"])

    def test_full_disable_epoch_and_other_client_preservation(self):
        r = self.result("full-disable-keeps-other-client")
        self.assertEqual(r["TARGET"]["101/1001/7"], {})
        self.assertEqual(r["TARGET"]["102/2001/7"], r["CURRENT"]["102/2001/7"])
        self.assertEqual(r["target_state"]["fences"]["101/1001/7"], dict(active=False, epoch=2, version=2, mode="TARGET", revoked_through_invitation_id=1000))

    def test_readmission_new_epoch_and_ids_never_restore_old_sources(self):
        r = self.result("fresh-readmission-never-reactivates-old-ids")
        state = r["target_state"]
        self.assertEqual(state["assignments"]["a-manager"]["status"], "REVOKED")
        self.assertEqual(state["assignments"]["new-manager"]["epoch"], 3)
        self.assertEqual(state["delegations"]["d-calendar"]["status"], "REVOKED")
        self.assertEqual(r["TARGET"]["101/1001/7"]["night.read"], ["ROLE:new-manager"])

    def test_old_invitation_and_old_source_id_cannot_restore(self):
        self.assertEqual(self.result("old-staff-invitation-cannot-readmit")["TARGET"]["101/1001/7"], {})
        self.assertEqual(self.result("terminal-source-id-cannot-be-reused")["TARGET"]["101/1001/7"], {})
        case = copy.deepcopy(self.cases["fresh-readmission-never-reactivates-old-ids"])
        case["operations"][1]["invitation_epoch"] = 1
        self.assertEqual(model.report_case(self.state, case)["outcomes"][-1], "NEW_TARGET_ADMISSION_REQUIRED")
        case["operations"][1].update(invitation_epoch=2, invitation_id=500)
        self.assertEqual(model.report_case(self.state, case)["outcomes"][-1], "NEW_TARGET_ADMISSION_REQUIRED")

    def test_initial_admission_creates_missing_but_never_restores_inactive_membership(self):
        id = "initial-target-admission-creates-only-missing-membership"
        r = self.result(id)
        self.assertNotIn("101/11", self.state["memberships"])
        self.assertEqual(r["target_state"]["memberships"]["101/11"], dict(active=True, version=1))
        self.assertNotIn("client.application.manage", r["TARGET"]["101/1001/11"])
        state = copy.deepcopy(self.state);state["memberships"]["101/11"] = dict(active=False, version=2)
        blocked = model.report_case(state, self.cases[id])
        self.assertEqual(blocked["outcomes"], ["TARGET_MEMBERSHIP_OR_MAPPING_DENIED"])
        self.assertEqual(blocked["TARGET"]["101/1001/11"], {})

    def test_regrant_needs_ack_and_keeps_old_tombstone(self):
        self.assertIn("EXPLICIT_REGRANT_ACK_REQUIRED", self.result("revoked-grant-needs-new-explicit-ack")["RISK"])
        r = self.result("explicit-regrant-new-source-old-tombstone-stays-revoked")
        self.assertFalse(r["target_state"]["compat"]["old-calendar"]["active"])
        self.assertEqual(r["TARGET"]["101/1001/7"]["client.club.calendar.write"], ["DELEGATION:d-new"])

    def test_inactive_membership_and_client_gate_all_sources(self):
        r = self.result("inactive-membership-no-target-authority")
        self.assertEqual(r["CURRENT"]["101/1001/7"], {})
        self.assertEqual(r["TARGET"]["101/1001/7"], {})
        state = copy.deepcopy(self.state)
        state["clients"]["101"] = False
        self.assertEqual(model.contributions(state, 101, 1001, 7), {})

    def test_role_change_and_revoke_both_serial_interleavings_fail_stale_command(self):
        after_revoke = self.result("role-change-after-concurrent-revoke-fails")
        self.assertEqual(after_revoke["TARGET"]["101/1001/7"], {})
        after_replace = self.result("revoke-stale-source-after-role-change-keeps-new-source")
        self.assertEqual(after_replace["TARGET"]["101/1001/7"]["night.read"], ["ROLE:new-entry"])
        self.assertEqual(after_replace["target_state"]["assignments"]["a-manager"]["status"], "REVOKED")
        op = copy.deepcopy(self.cases["role-change-after-concurrent-revoke-fails"]["operations"][1])
        op.update(expected_version=2, source_version=2, command_ref="fresh-but-revoked-source")
        target, status = model.preview_command(after_revoke["target_state"], op)
        self.assertEqual(status, "REVOKED_OR_STALE_REPLACE_DENIED")
        self.assertEqual(target, after_revoke["target_state"])

    def test_concurrent_append_conflict_then_retry_preserves_all_sources(self):
        r = self.result("two-concurrent-role-grants-conflict-then-fresh-retry")
        self.assertEqual(r["outcomes"], ["COMMITTED_IN_MODEL_ONLY", "STALE_FENCE_CONFLICT", "COMMITTED_IN_MODEL_ONLY"])
        self.assertEqual(len(r["target_state"]["audits"]), 2)
        self.assertEqual(r["TARGET"]["101/1001/7"]["night.read"], ["ROLE:a-manager", "ROLE:b-entry"])
        self.assertIn("finance.report.read", r["TARGET"]["101/1001/7"])

    def test_promoter_three_clubs_never_five_or_global(self):
        r = self.result("promoter-three-of-five-clubs")
        for club in (1001, 1002, 1003):
            self.assertEqual(set(r["TARGET"][f"101/{club}/10"]), {"booking.promoter.create", "booking.promoter.own.read"})
        for club in (1004, 1005): self.assertEqual(r["TARGET"][f"101/{club}/10"], {})

    def test_financial_read_stays_in_exact_club_and_client(self):
        r = self.result("financial-read-does-not-cross-club")
        self.assertIn("finance.report.read", r["TARGET"]["101/1001/7"])
        self.assertEqual(r["TARGET"]["101/1002/7"], {})
        self.assertEqual(r["TARGET"]["102/2001/7"]["finance.report.read"], ["ROLE:other-client-fin"])
        state = copy.deepcopy(r["target_state"])
        state["assignments"]["fin-a"]["selector"] = dict(kind="SHIFT_SET", ids=[7001])
        self.assertNotIn("finance.report.read", model.contributions(state, 101, 1001, 7, dict(shift_id=7002)))
        self.assertIn("finance.report.read", model.contributions(state, 101, 1001, 7, dict(shift_id=7001)))
        state["assignments"]["fin-a"]["selector"]["ids"] = []
        self.assertNotIn("finance.report.read", model.contributions(state, 101, 1001, 7, dict(shift_id=7001)))

    def test_issuer_revoke_invalidates_delegation_without_revoking_recipient_role(self):
        state = copy.deepcopy(self.state)
        state["delegations"] = copy.deepcopy(self.cases["role-plus-delegation"]["overrides"]["delegations"])
        op = self.cases["replay-after-issuer-loses-authority-denied"]["operations"][1]
        target, status = model.preview_command(state, op)
        self.assertEqual(status, "COMMITTED_IN_MODEL_ONLY")
        self.assertNotIn("client.club.calendar.write", model.contributions(target, 101, 1001, 7))
        self.assertEqual(target["assignments"]["a-manager"]["status"], "ACTIVE")

    def test_delegation_requires_current_issuer_read_execution_version_and_expiry(self):
        case = self.cases["manager-calendar-write-separate-delegation"]
        for change in ("issuer_read", "issuer_write", "version", "expired", "recipient_role"):
            with self.subTest(change=change):
                state = copy.deepcopy(self.state);op = copy.deepcopy(case["operations"][0])
                if change in ("issuer_read", "issuer_write"):
                    policy = state["policies"]["cm-v1"]
                    policy["permissions"].remove("client.club.calendar.read" if change == "issuer_read" else "client.club.calendar.write")
                    state["assignments"]["issuer-cm"]["policy_hash"] = model.digest(policy)
                if change == "version": op["issuer_version"] = 2
                if change == "expired": op["expires_at"] = state["now"]
                if change == "recipient_role":
                    state["assignments"]["a-manager"].update(role="ENTRANCE_MANAGER", policy="entry-v1", policy_hash=model.digest(state["policies"]["entry-v1"]))
                target, status = model.preview_command(state, op)
                self.assertNotEqual(status, "COMMITTED_IN_MODEL_ONLY")
                self.assertEqual(target, state)

    def test_audit_and_receipt_failure_rollback_all_model_changes(self):
        for id in ("audit-failure-rolls-back-source-and-receipt", "receipt-failure-rolls-back-source-and-audit"):
            with self.subTest(id=id): self.assertEqual(self.result(id)["target_state"], self.state)

    def test_release_rollback_and_receipt_replay_never_revive_sources(self):
        self.assertEqual(self.result("release-rollback-preview-after-revoke-does-not-restore")["TARGET"]["101/1001/7"], {})
        r = self.result("old-command-replay-does-not-resurrect-revoked-source")
        self.assertEqual(r["target_state"]["assignments"]["b-entry"]["status"], "REVOKED")
        self.assertEqual(len(r["target_state"]["audits"]), 2)
        self.assertIn("CURRENT_MANAGEMENT_AUTHORITY_DENIED", self.result("replay-after-issuer-loses-authority-denied")["outcomes"])

    def test_replay_conflicting_intent_rejected_without_changes(self):
        op = self.cases["old-command-replay-does-not-resurrect-revoked-source"]["operations"][0]
        state, _ = model.preview_command(self.state, op)
        changed = dict(op, new_id="different-target-id")
        target, status = model.preview_command(state, changed)
        self.assertEqual(status, "IDEMPOTENCY_CONFLICT")
        self.assertEqual(state, target)

    def test_platform_restore_never_restores_staff_sources_other_client_remains(self):
        r = self.result("platform-revoke-restore-membership-only")
        self.assertTrue(r["target_state"]["memberships"]["101/7"]["active"])
        self.assertEqual(r["target_state"]["memberships"]["101/7"]["version"], 3)
        self.assertFalse(r["target_state"]["fences"]["101/1001/7"]["active"])
        self.assertEqual(r["TARGET"]["101/1001/7"], {})
        self.assertEqual(r["TARGET"]["102/2001/7"], r["CURRENT"]["102/2001/7"])

    def test_legacy_writer_and_unknown_provenance_fail_closed_before_changes(self):
        self.assertIn("LEGACY_WRITER_BLOCKED_IN_TARGET", self.result("legacy-writer-on-mixed-assignments-fails-closed")["RISK"])
        self.assertIn("AMBIGUOUS_COMPAT_SOURCE_BLOCKS_TARGET", self.result("ambiguous-active-compat-source-blocks-target-writer")["RISK"])
        self.assertEqual(self.result("policy-version-drift-does-not-expand-existing-assignments")["TARGET"]["101/1001/7"], {})

    def test_self_higher_role_ownership_and_cross_scope_denied(self):
        self.assertIn("CURRENT_MANAGEMENT_AUTHORITY_DENIED", self.result("manager-self-escalation-denied")["RISK"])
        self.assertIn("EXACT_SOURCE_OWNERSHIP_REQUIRED", self.result("foreign-source-id-cannot-be-revoked")["RISK"])
        self.assertIn("DELEGATION_AUTHORITY_DENIED", self.result("delegation-cannot-grant-management-role")["RISK"])
        op = copy.deepcopy(self.cases["two-concurrent-role-grants-conflict-then-fresh-retry"]["operations"][0]);op["user"] = 1
        target, status = model.preview_command(self.state, op)
        self.assertEqual(status, "CURRENT_MANAGEMENT_AUTHORITY_DENIED")
        self.assertEqual(target, self.state)

    def test_full_disable_cannot_remove_higher_independent_role(self):
        state = copy.deepcopy(self.state)
        higher = copy.deepcopy(state["assignments"]["issuer-nw"]);higher["user"] = 7
        state["assignments"]["target-nw"] = higher
        op = self.cases["full-disable-keeps-other-client"]["operations"][0]
        target, status = model.preview_command(state, op)
        self.assertEqual(status, "FULL_DISABLE_HIERARCHY_DENIED")
        self.assertEqual(target, state)

    def test_club_manager_full_disable_cannot_erase_opaque_compat_authority(self):
        state = copy.deepcopy(self.state)
        for p in ("client.club.read", "client.club.team.manage"):
            state["compat"][p] = dict(client=101, club=1001, user=7, permission=p, classification="KEEP_OPAQUE_COMPAT", active=True, epoch=1)
        op = self.cases["full-disable-keeps-other-client"]["operations"][0]
        target, status = model.preview_command(state, op)
        self.assertEqual(status, "FULL_DISABLE_HIERARCHY_DENIED")
        self.assertEqual(target, state)

    def test_verified_owner_full_disable_covers_compatibility_only_subject(self):
        state = copy.deepcopy(self.state)
        state["assignments"]["a-manager"].update(status="REVOKED", version=2)
        for p in ("client.club.read", "client.club.profile.write"):
            state["compat"][p] = dict(client=101, club=1001, user=7, permission=p, classification="KEEP_OPAQUE_COMPAT", active=True, epoch=1)
        self.assertIn("client.club.profile.write", model.contributions(state, 101, 1001, 7))
        op = dict(self.cases["full-disable-keeps-other-client"]["operations"][0], actor=1)
        target, status = model.preview_command(state, op)
        self.assertEqual(status, "COMMITTED_IN_MODEL_ONLY")
        self.assertEqual(model.contributions(target, 101, 1001, 7), {})
        self.assertTrue(all(not s["active"] for s in target["compat"].values()))
        self.assertEqual(target["assignments"]["other-client-fin"], state["assignments"]["other-client-fin"])

    def test_compatibility_active_write_with_inactive_read_stays_dormant(self):
        state = copy.deepcopy(self.state)
        for p, active in (("client.club.read", False), ("client.club.calendar.write", True)):
            state["compat"][p] = dict(client=101, club=1001, user=7, permission=p, classification="KEEP_OPAQUE_COMPAT", active=active, epoch=1)
        self.assertNotIn("client.club.calendar.write", model.contributions(state, 101, 1001, 7))
        op = self.cases["two-concurrent-role-grants-conflict-then-fresh-retry"]["operations"][0]
        target, status = model.preview_command(state, op)
        self.assertEqual(status, "COMMITTED_IN_MODEL_ONLY")
        self.assertNotIn("client.club.calendar.write", model.contributions(target, 101, 1001, 7))

    def test_issuer_revoke_disable_and_platform_revoke_fence_dependent_responses(self):
        for kind in ("REVOKE_ROLE", "DISABLE", "PLATFORM_REVOKE"):
            with self.subTest(kind=kind):
                state = copy.deepcopy(self.state)
                state["delegations"] = copy.deepcopy(self.cases["role-plus-delegation"]["overrides"]["delegations"])
                op = dict(self.cases["replay-after-issuer-loses-authority-denied"]["operations"][1], kind=kind, actor=999 if kind == "PLATFORM_REVOKE" else 1)
                target, status = model.preview_command(state, op)
                self.assertEqual(status, "COMMITTED_IN_MODEL_ONLY")
                self.assertEqual(state["fences"]["101/1001/7"]["version"], 1)
                self.assertEqual(target["fences"]["101/1001/7"]["version"], 2)
                self.assertEqual(target["fences"]["102/2001/7"], state["fences"]["102/2001/7"])
                self.assertEqual(target["assignments"]["a-manager"], state["assignments"]["a-manager"])
                self.assertEqual(target["delegations"]["d-calendar"]["status"], "REVOKED")
                op["audit_failure"] = True
                rolled, failed = model.preview_command(state, op)
                self.assertEqual(failed, "AUDIT_FAILURE_ROLLBACK")
                self.assertEqual(rolled, state)

    def test_owner_role_assignment_does_not_require_configuration_execution(self):
        self.assertEqual(model.contributions(self.state, 101, 1001, 1), {})
        r = self.result("owner-assigns-club-manager-without-execution-config-grants")
        self.assertIn("client.club.calendar.write", r["TARGET"]["101/1001/7"])
        self.assertEqual(model.contributions(r["target_state"], 101, 1001, 1), {})

    def test_shadow_mode_does_not_issue_target_permission(self):
        state = copy.deepcopy(self.state);state["fences"]["101/1001/7"]["mode"] = "SHADOW"
        self.assertEqual(model.contributions(state, 101, 1001, 7), {})

    def test_signed_identity_and_source_fixture_protocol(self):
        op = copy.deepcopy(self.cases["manager-calendar-write-separate-delegation"]["operations"][0]);op["signed_identity"] = False
        target, status = model.preview_command(self.state, op)
        self.assertEqual(status, "SIGNED_IDENTITY_REQUIRED");self.assertEqual(target, self.state)
        self.assertEqual(set(self.fixture["source_sha256"]), set(model.EVIDENCE_SOURCES))
        self.assertNotIn("BUSINESS_OWNER", model.STAFF_ROLES)
        self.assertNotIn("GUEST", model.STAFF_ROLES)

    def test_static_compile_and_no_external_writer_surface(self):
        text = SCRIPT.read_text();tree = ast.parse(text);compile(text, str(SCRIPT), "exec")
        imports = {n.names[0].name for n in ast.walk(tree) if isinstance(n, ast.Import)} | {n.module for n in ast.walk(tree) if isinstance(n, ast.ImportFrom)}
        self.assertEqual(imports, {"argparse", "hashlib", "json", "pathlib"})
        for n in ast.walk(tree):
            if isinstance(n, ast.Call):
                name = n.func.attr if isinstance(n.func, ast.Attribute) else n.func.id if isinstance(n.func, ast.Name) else None
                self.assertNotIn(name, {"write_text", "write_bytes", "unlink", "rename", "mkdir", "connect", "execute", "executescript", "getenv", "exec", "eval"})

    def test_public_checksum_metadata_cannot_disable_source_verification(self):
        original = model.FIXTURE
        try:
            with tempfile.TemporaryDirectory(prefix="clb-checksum-negative-") as directory:
                model.FIXTURE = Path(directory) / "fixture.json"
                for replacement in (
                    {"algorithm": "MD5", "digest": self.fixture["source_sha256"][model.CONTRACT]["digest"]},
                    {"algorithm": "SHA-256", "digest": "0" * 64},
                    {"algorithm": "SHA-256"},
                    "0" * 64,
                ):
                    changed = copy.deepcopy(self.fixture)
                    changed["source_sha256"][model.CONTRACT] = replacement
                    model.FIXTURE.write_text(json.dumps(changed))
                    with self.assertRaises(ValueError): model.load_fixture()
        finally:
            model.FIXTURE = original

    def test_cli_fixed_fixture_stdout_only_and_deterministic(self):
        with tempfile.TemporaryDirectory(prefix="clb-assignment-reference-") as directory:
            base = [sys.executable, "-I", "-S", "-B", str(SCRIPT)]
            first = subprocess.run(base, cwd=directory, capture_output=True, text=True, check=True)
            second = subprocess.run(base, cwd=directory, capture_output=True, text=True, check=True)
            self.assertEqual(first.stdout, second.stdout)
            self.assertEqual(len(json.loads(first.stdout)), 38)
            for flag in ("--apply", "--input", "--database-url", "--output"):
                self.assertEqual(subprocess.run(base + [flag, "synthetic"], cwd=directory, capture_output=True).returncode, 2)
            self.assertEqual(list(Path(directory).iterdir()), [])


if __name__ == "__main__":
    unittest.main()
