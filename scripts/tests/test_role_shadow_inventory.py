"""Behavioral checks for the offline shadow tool, not production RBAC tests."""

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
SCRIPT = ROOT / "scripts/role_shadow_inventory.py"
SPEC = importlib.util.spec_from_file_location("role_shadow_inventory", SCRIPT)
shadow = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(shadow)


class RoleShadowInventoryTest(unittest.TestCase):
    def setUp(self):
        self.fixture = shadow.load_fixture()
        self.original = copy.deepcopy(self.fixture)
        self.report = shadow.build_report(self.fixture)

    def scenario(self, name):
        return next(s for s in self.report["scenarios"] if s["id"] == name)

    def difference(self, name, scope):
        return next(d for d in self.scenario(name)["DIFFERENCE"] if d.get("scope") == scope)

    def test_all_requested_profiles_preserve_old_scenarios_and_use_eight_roles(self):
        self.assertEqual(len(self.report["scenarios"]), 46)
        self.assertEqual([s["id"] for s in self.report["scenarios"][:24]], self.fixture["historical_24_scenario_ids"])
        roles = {t["role"] for s in self.fixture["scenarios"] for t in s["targets"]}
        self.assertEqual(roles, set(shadow.ROLE_IDS))
        self.assertEqual(len(roles), 8)
        self.assertNotIn("NETWORK_OWNER", roles)
        self.assertNotIn("CLUB_OWNER", roles)
        for s in self.report["scenarios"]:
            self.assertTrue({"CURRENT", "PROPOSED", "DIFFERENCE", "RISK"} <= s.keys())

    def test_report_does_not_mutate_any_input_or_authority(self):
        self.assertEqual(self.fixture, self.original)
        self.assertEqual(self.report["writes"], 0)
        self.assertTrue(self.report["actual_authority_unchanged"])
        self.assertEqual(self.report["snapshot_before_sha256"], self.report["snapshot_after_sha256"])
        for s in self.report["scenarios"]:
            for d in s["DIFFERENCE"]:
                if "scope" in d:
                    self.assertEqual(d["applied_additions"], [])
                    self.assertEqual(d["applied_removals"], [])

    def test_repeat_is_byte_deterministic(self):
        self.assertEqual(json.dumps(self.report, sort_keys=True), json.dumps(shadow.build_report(self.fixture), sort_keys=True))

    def test_cancel_and_rollback_cannot_restore_tombstones_or_replay_commands(self):
        for mode in ("cancel", "rollback"):
            result = shadow.build_report(self.fixture, lifecycle=mode)
            self.assertEqual(result["scenarios"], self.report["scenarios"])
            self.assertEqual(result["snapshot_after_sha256"], self.report["snapshot_before_sha256"])
        self.assertEqual(self.fixture, self.original)

    def test_no_apply_or_unknown_lifecycle(self):
        with self.assertRaisesRegex(ValueError, "no apply"):
            shadow.build_report(self.fixture, lifecycle="apply")

    def test_floor_manager_shows_specific_configuration_loss_and_operation_intents(self):
        d = self.difference("floor-manager-name-is-not-package-mapping", [101, 1001, 4])
        self.assertEqual(d["would_remove_existing_tokens"], sorted(shadow.PREFIX + x for x in shadow.PACKAGES["MANAGER"]))
        self.assertEqual(d["would_add_existing_tokens"], [])
        self.assertIn("table.seat.booked", d["new_policy_action_intents"])
        self.assertNotIn("entrance.arrival.record", d["new_policy_action_intents"])

    def test_entry_package_read_is_not_scanning_authority(self):
        s = self.scenario("entrance-manager-read-is-not-checkin")
        self.assertEqual([g["permission_code"] for g in s["CURRENT"]["active_client_tokens_with_read_prerequisite"]], ["client.club.read"])
        self.assertIn("night_pass.validate", s["PROPOSED"][0]["action_intents"])
        self.assertIn("TECHNICAL_PERMISSION_CONTRACT_PROPOSED", s["PROPOSED"][0]["blockers"])
        self.assertTrue(any("DEC-010/011/020/021" in g for g in s["PROPOSED"][0]["policy_gates"]))

    def test_finance_templates_not_money_and_finance_role_cannot_seat(self):
        s = self.scenario("finance-manager-template-is-not-money")
        self.assertIn("client.club.finance.write", [g["permission_code"] for g in s["CURRENT"]["active_client_tokens_with_read_prerequisite"]])
        actions = s["PROPOSED"][0]["action_intents"]
        self.assertIn("shift.close", actions)
        self.assertIn("table.top_up.record", actions)
        self.assertNotIn("table.seat.booked", actions)
        self.assertTrue(any("DEC-012" in g for g in s["PROPOSED"][0]["policy_gates"]))

    def test_business_owner_binding_does_not_adopt_legacy_owner_or_team_package(self):
        proposals = self.scenario("network-owner-finite-clubs-creator-only-one")["PROPOSED"]
        self.assertTrue(all(p["role"] == "BUSINESS_OWNER" for p in proposals))
        self.assertTrue(all("VERIFIED_BUSINESS_OWNERSHIP_BINDING_REQUIRED_NOT_LEGACY_OWNER" in p["blockers"] for p in proposals))
        self.assertTrue(all(p["existing_token_intent"] == [] for p in proposals))
        self.assertTrue(all("staff.network_manager.assign" in p["accepted_product_actions"] for p in proposals))
        self.assertTrue(all(p["scope"][0] == 101 and p["scope"][1] in (1001, 1002) for p in proposals))

    def test_proposed_catalogue_never_promotes_approval_platform_ownership_or_super_right(self):
        for role, entries in self.report["catalogue"].items():
            with self.subTest(role=role):
                self.assertTrue(all(a["status"] == "PROPOSED" and a["row"] < len(shadow.ROW_ACTIONS) for a in entries))
                self.assertFalse(any(a["action"].startswith(("platform.", "ownership.", "super_right.")) for a in entries))
        self.assertTrue(self.report["technical_model"]["target_role_ids_are_report_only"])
        owner = self.scenario("network-owner-finite-clubs-creator-only-one")
        self.assertEqual(owner["CURRENT"]["raw"]["creators_projection"], [dict(client_id=101, club_id=1001, owner_user_id=1)])

    def test_network_manager_can_appoint_club_manager_but_no_peer_config_or_money(self):
        for p in self.scenario("network-manager-finite-assigned-set")["PROPOSED"]:
            self.assertEqual(p["existing_token_intent"], [])
            self.assertIn("staff.roster.redacted.read", p["action_intents"])
            self.assertNotIn("staff.packages.read", p["action_intents"])
            self.assertIn("staff.club_manager.assign", p["accepted_product_actions"])
            self.assertNotIn("staff.network_manager.assign", p["action_intents"])
            self.assertNotIn("shift.close", p["action_intents"])

    def test_independent_clients_do_not_cross_combine(self):
        s = self.scenario("two-independent-clients")
        a, b = s["PROPOSED"]
        self.assertEqual(a["scope"], [101, 1001, 8])
        self.assertEqual(b["scope"], [102, 2001, 8])
        self.assertNotIn("entrance.arrival.record", a["action_intents"])
        self.assertNotIn("table.seat.booked", b["action_intents"])

    def test_multiple_targets_union_only_within_exact_scope(self):
        name = "multiple-assignments-exact-tuple-only"
        a = self.difference(name, [101, 1001, 9])
        b = self.difference(name, [101, 1002, 9])
        self.assertEqual(a["roles"], ["ENTRANCE_MANAGER", "FLOOR_MANAGER"])
        self.assertEqual(a["would_add_existing_tokens"], [])
        self.assertEqual(len(a["would_remove_existing_tokens"]), 5)
        self.assertEqual(len(b["would_add_existing_tokens"]), 8)
        self.assertEqual(b["retained_existing_tokens"], ["client.club.read"])
        # Attached support permission stays bound to assignment 902 in club 3002.
        raw = self.scenario(name)["CURRENT"]["raw"]
        self.assertEqual({p["user_role_id"] for p in raw["user_role_permissions"]}, {902})

    def test_revoked_tokens_are_visible_and_never_reactivated(self):
        s = self.scenario("revoked-grants-old-writer-resurrection")
        d = self.difference(s["id"], [101, 1001, 10])
        self.assertEqual(d["restoration_attempts"], ["client.club.calendar.write", "client.club.profile.write"])
        self.assertIn("TOMBSTONE_REQUIRES_EXPLICIT_NEW_ADMISSION", s["RISK"])
        self.assertEqual(s["CURRENT"]["raw"]["client_club_staff"][0]["revoked_through_invitation_id"], 1000)

    def test_inactive_membership_preserves_stored_rows_but_no_current_tokens(self):
        s = self.scenario("inactive-membership-active-stored-grants")
        self.assertEqual(len(s["CURRENT"]["raw"]["client_club_grants"]), 9)
        self.assertEqual(s["CURRENT"]["active_client_tokens_with_read_prerequisite"], [])
        self.assertIn("MEMBERSHIP_INACTIVE_OR_MISSING", s["RISK"])

    def test_inactive_staff_does_not_mask_actual_active_grants(self):
        s = self.scenario("inactive-staff-active-grants-contradiction")
        self.assertEqual(len(s["CURRENT"]["active_client_tokens_with_read_prerequisite"]), 5)
        self.assertIn("INACTIVE_STAFF_WITH_ACTIVE_GRANTS_NOT_AUTHORITY_DENIAL", s["RISK"])
        self.assertIn("INACTIVE_STAFF_NEW_ADMISSION_REQUIRED", s["RISK"])

    def test_active_write_with_inactive_read_cannot_be_used_as_current_authority(self):
        s = self.scenario("active-write-with-inactive-read")
        self.assertEqual(s["CURRENT"]["active_client_tokens_with_read_prerequisite"], [])
        self.assertEqual(len(s["CURRENT"]["raw"]["client_club_grants"]), 5)

    def test_client_revoke_does_not_claim_legacy_revoke(self):
        s = self.scenario("client-only-revoke-legacy-access-remains")
        self.assertEqual(s["CURRENT"]["active_client_tokens_with_read_prerequisite"], [])
        self.assertEqual(s["CURRENT"]["raw"]["user_roles"][0]["id"], 1201)
        self.assertIn("LEGACY_MANUAL_MAPPING_REQUIRED", s["RISK"])

    def test_duplicate_global_rows_and_exact_ids_not_deduplicated(self):
        s = self.scenario("duplicate-legacy-global-owner-no-network-adoption")
        self.assertEqual([r["id"] for r in s["CURRENT"]["raw"]["user_roles"]], [1301, 1302])
        self.assertIn("LEGACY_GLOBAL_BYPASS_NOT_NETWORK_AUTHORITY", s["RISK"])
        self.assertEqual(s["CURRENT"]["active_client_tokens_with_read_prerequisite"], [])

    def test_client_is_invalid_legacy_scope_not_client_membership(self):
        s = self.scenario("negative-input-legacy-client-scope-invalid")
        self.assertIn("INVALID_LEGACY_SCOPE_OR_ROLE", s["RISK"])
        self.assertEqual(s["CURRENT"]["active_client_tokens_with_read_prerequisite"], [])

    def test_no_target_is_inferred_from_old_roles_or_invitations(self):
        for name in ("old-staff-invitation-no-target-assignment", "same-technical-role-no-explicit-target"):
            s = self.scenario(name)
            self.assertEqual(s["PROPOSED"], [])
            self.assertEqual(s["DIFFERENCE"], [])
        self.fixture["scenarios"][0]["targets"][0]["role"] = "ENTRY_MANAGER"
        s = shadow.build_report(self.fixture)["scenarios"][0]
        self.assertEqual(s["PROPOSED"], [])
        self.assertIn("UNKNOWN_TARGET_ROLE_NO_NAME_MAPPING", s["RISK"])

    def test_accepted_expired_invitation_does_not_remove_grants(self):
        s = self.scenario("accepted-invitation-expiry-does-not-revoke")
        self.assertEqual(s["CURRENT"]["raw"]["invitations_projection"][0]["status"], "accepted")
        self.assertEqual(len(s["CURRENT"]["active_client_tokens_with_read_prerequisite"]), 1)
        self.assertIn("OLD_INVITATION_NOT_NEW_ROLE_AUTHORITY", s["RISK"])

    def test_global_all_clubs_and_cross_client_targets_blocked(self):
        s = self.scenario("cross-client-and-global-target-rejected")
        self.assertEqual(len(s["PROPOSED"]), 1)
        self.assertIn("CLIENT_CLUB_MAPPING_MISMATCH", s["RISK"])
        self.assertIn("NO_GLOBAL_OR_ALL_CLUBS_OR_IMPLICIT_SCOPE", s["RISK"])
        self.fixture["scenarios"][0]["targets"][0]["club_ids"] = []
        self.assertEqual(shadow.build_report(self.fixture)["scenarios"][0]["PROPOSED"], [])

    def test_inactive_and_archived_client(self):
        s = self.scenario("inactive-and-archived-client")
        self.assertEqual(s["CURRENT"]["active_client_tokens_with_read_prerequisite"], [])
        self.assertTrue(all("CLIENT_INACTIVE_OR_ARCHIVED" in p["blockers"] for p in s["PROPOSED"]))

    def test_bigint_id_not_rounded(self):
        s = self.scenario("bigint-identity-not-rounded")
        self.assertEqual(s["user_id"], 9007199254740993)
        self.assertEqual(s["PROPOSED"][0]["scope"][2], 9007199254740993)

    def test_guest_self_has_no_staff_platform_or_ownership_permissions(self):
        s = self.scenario("guest-personal-data-only")
        p = s["PROPOSED"][0]
        self.assertEqual(p["scope"], "SELF:19")
        self.assertEqual(set(p["action_intents"]), {"storefront.view", "self.records.read", "self.subscription.write", "self.booking.hold", "self.booking.confirm", "self.booking.cancel"})
        self.assertEqual(s["DIFFERENCE"][0]["new_policy_action_intents"], p["action_intents"])
        self.assertTrue(s["DIFFERENCE"][0]["intent_only"])
        self.assertEqual(s["DIFFERENCE"][0]["applied_additions"], [])
        self.assertIn("GUEST_OWNERSHIP_STOREFRONT_AND_CUTOVER_GATES", s["RISK"])
        self.assertIn("TECHNICAL_PERMISSION_CONTRACT_PROPOSED", s["RISK"])

    def test_old_writer_exact_loss_and_resurrection_blast_radius(self):
        s = self.scenario("revoked-grants-old-writer-resurrection")
        p = s["writer_preview"][0]
        self.assertEqual(p["would_reactivate_tombstones"], ["client.club.calendar.write", "client.club.profile.write"])
        self.assertEqual(p["hypothetical_future_actions_lost"], ["entrance.arrival.record"])
        self.assertFalse(p["executed"])
        owner = shadow.writer_preview(self.fixture["snapshot"], 1, dict(client_id=101, club_id=1001, package="MANAGER"))
        self.assertIn("client.club.team.manage", owner["would_disable"])
        self.assertEqual(self.fixture, self.original)

    def test_disable_preview_only_affects_exact_tuple(self):
        p = shadow.writer_preview(self.fixture["snapshot"], 8, dict(client_id=101, club_id=1001, package=None))
        self.assertEqual(len(p["would_disable"]), 5)
        self.assertEqual(p["scope"], [101, 1001, 8])
        self.assertEqual(self.fixture, self.original)

    def test_old_writer_upgrade_shows_all_new_rows_separately_from_tombstones(self):
        p = shadow.writer_preview(self.fixture["snapshot"], 5, dict(client_id=101, club_id=1001, package="CLUB_ADMIN"))
        self.assertEqual(p["would_create"], sorted(shadow.PREFIX + x for x in shadow.PACKAGES["CLUB_ADMIN"] if x != "read"))
        self.assertEqual(p["would_reactivate_tombstones"], [])
        self.assertEqual(p["would_disable"], [])
        self.assertEqual(self.fixture, self.original)

    def test_invitation_ids_are_global_unique_and_staff_links_are_exact(self):
        for change in ("duplicate", "wrong_reference"):
            with self.subTest(change=change):
                f = copy.deepcopy(self.fixture)
                if change == "duplicate":
                    f["snapshot"]["invitations_projection"].append(f["snapshot"]["invitations_projection"][0].copy())
                else:
                    f["snapshot"]["client_club_staff"][0]["invitation_id"] = 802
                with self.assertRaises(ValueError): shadow.build_report(f)

    def test_new_club_in_client_does_not_expand_any_explicit_target(self):
        self.fixture["snapshot"]["client_clubs"].append(dict(client_id=101, club_id=1006))
        r = shadow.build_report(self.fixture)
        for s in r["scenarios"]:
            for p in s["PROPOSED"]:
                if isinstance(p["scope"], list):
                    self.assertNotEqual(p["scope"][1], 1006)
        invitation_only = next(s for s in r["scenarios"] if s["user_id"] == 15)
        self.assertEqual(invitation_only["CURRENT"]["active_client_tokens_with_read_prerequisite"], [])

    def test_stale_snapshot_fences_version_mapping_and_revocation_changes(self):
        expected = shadow.digest(self.fixture["snapshot"])
        for table, field, value in (("client_memberships", "version", 9), ("client_club_staff", "revoked_through_invitation_id", 9999), ("client_club_grants", "is_active", False), ("client_clubs", "client_id", 102)):
            with self.subTest(table=table):
                changed = copy.deepcopy(self.fixture)
                changed["snapshot"][table][0][field] = value
                r = shadow.build_report(changed, expected_snapshot_digest=expected)
                self.assertTrue(all("STALE_BASELINE" in s["RISK"] for s in r["scenarios"]))
                self.assertTrue(r["actual_authority_unchanged"])

    def test_ambiguous_creator_and_foreign_source_mapping_require_decision(self):
        self.fixture["snapshot"]["creators_projection"].append(dict(client_id=101, club_id=1001, owner_user_id=2))
        self.fixture["scenarios"][0]["targets"][0]["source_legacy_ids"] = [1201]
        s = shadow.build_report(self.fixture)["scenarios"][0]
        self.assertIn("AMBIGUOUS_CREATOR_PROVENANCE", s["RISK"])
        self.assertIn("UNKNOWN_OR_FOREIGN_SOURCE_ASSIGNMENT", s["RISK"])

    def test_unsupported_current_operation_code_is_not_treated_as_real_grant(self):
        g = dict(self.fixture["snapshot"]["client_club_grants"][0], permission_code="entrance.arrival.record")
        self.fixture["snapshot"]["client_club_grants"].append(g)
        s = shadow.build_report(self.fixture)["scenarios"][0]
        self.assertIn("UNSUPPORTED_CURRENT_CLIENT_PERMISSION", s["RISK"])
        self.assertNotIn(g, s["CURRENT"]["active_client_tokens_with_read_prerequisite"])

    def test_fixture_safety_and_source_drift_fail_closed(self):
        for mutate in (
            lambda f: f.update(synthetic_only=False),
            lambda f: f.update(source_revision="0" * 40),
            lambda f: f["source_sha256"].pop(shadow.PROPOSAL),
            lambda f: f["source_sha256"].update({shadow.PROPOSAL: "0" * 64}),
            lambda f: f["source_sha256"][shadow.PROPOSAL].update(algorithm="MD5"),
            lambda f: f["source_sha256"][shadow.PROPOSAL].update(digest="0" * 64),
            lambda f: f["snapshot"]["client_club_grants"][0].update(secret="forbidden"),
            lambda f: f["snapshot"]["client_club_grants"][0].update(is_active="false"),
            lambda f: f["snapshot"]["client_club_grants"].append(f["snapshot"]["client_club_grants"][0].copy()),
            lambda f: f["snapshot"]["user_roles"][0].update(id=True),
        ):
            with self.subTest(mutate=mutate):
                changed = copy.deepcopy(self.fixture)
                mutate(changed)
                with self.assertRaises(ValueError): shadow.build_report(changed)

    def test_source_coupled_writer_and_current_package_contract(self):
        source = (ROOT / "core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubStaffAccess.kt").read_text()
        self.assertIn("UPDATE client_club_grants SET is_active=FALSE,updated_at=? WHERE client_id=? AND club_id=? AND user_id=?", source)
        self.assertIn("staffPackage(role).forEach", source)
        self.assertIn("SET is_active=TRUE", source)
        schema = (ROOT / "core-data/src/main/resources/db/migration/postgresql/V075__client_club_staff.sql").read_text()
        for code in shadow.CLIENT_CODES:
            self.assertIn("'" + code + "'", schema)
        self.assertEqual(len(shadow.CLIENT_CODES), 10)

    def test_eight_role_hierarchy_is_accepted_but_permission_bindings_are_proposed(self):
        catalogue = self.report["catalogue"]
        accepted = lambda role: {a["action"] for a in catalogue[role] if a["product_status"] == "ACCEPTED"}
        self.assertIn("staff.network_manager.assign", accepted("BUSINESS_OWNER"))
        self.assertIn("staff.network_manager.revoke", accepted("BUSINESS_OWNER"))
        self.assertIn("staff.club_manager.assign", accepted("NETWORK_MANAGER"))
        self.assertNotIn("staff.network_manager.assign", accepted("NETWORK_MANAGER"))
        self.assertNotIn("staff.club_manager.revoke", accepted("NETWORK_MANAGER"))
        self.assertTrue({"staff.floor_manager.assign", "staff.entrance_manager.assign", "staff.finance_manager.assign", "staff.promoter.assign", "configuration.delegate"} <= accepted("CLUB_MANAGER"))
        self.assertFalse(any(a["action"].startswith("staff.") for a in catalogue["FLOOR_MANAGER"]))
        self.assertTrue(all(a["status"] == "PROPOSED" for entries in catalogue.values() for a in entries))
        recovery = next(a for a in catalogue["BUSINESS_OWNER"] if a["action"] == "business.emergency_recovery")
        self.assertEqual(recovery["product_status"], "PROPOSED")
        self.assertNotIn("PLATFORM_OWNER", shadow.ROLE_IDS)

    def test_same_business_owner_second_club_needs_no_data_transfer_or_auto_grants(self):
        one = self.scenario("business-owner-single-club")
        two = self.scenario("same-business-owner-second-club-no-grant-inheritance")
        self.assertEqual(one["user_id"], two["user_id"])
        self.assertEqual(one["CURRENT"], two["CURRENT"])
        self.assertEqual(len(one["PROPOSED"]), 1)
        self.assertEqual(len(two["PROPOSED"]), 2)
        self.assertTrue(all(p["role"] == "BUSINESS_OWNER" and p["existing_token_intent"] == [] for p in two["PROPOSED"]))
        self.assertEqual(two["shadow_access_probes"][0]["PROPOSED"], "ALLOW_IN_MODEL")
        self.assertEqual([g["club_id"] for g in two["CURRENT"]["raw"]["client_club_grants"]], [1001])
        self.assertTrue(self.report["actual_authority_unchanged"])

    def test_network_manager_optional_and_read_limited_to_assigned_clubs(self):
        owner_model = next(s for s in self.fixture["scenarios"] if s["id"] == "business-owner-single-club")["policy_fixture"]
        self.assertFalse(any(a["role"] == "NETWORK_MANAGER" for a in owner_model["assignments"]))
        probes = self.scenario("optional-network-manager-finite-subset")["shadow_access_probes"]
        self.assertEqual([p["PROPOSED"] for p in probes], ["ALLOW_IN_MODEL", "DENY_IN_MODEL"])

    def test_financial_scope_and_capability_are_both_required(self):
        probes = self.scenario("club-manager-finance-same-club-only")["shadow_access_probes"]
        self.assertEqual([p["PROPOSED"] for p in probes], ["ALLOW_IN_MODEL", "DENY_IN_MODEL", "DENY_IN_MODEL"])
        without = self.scenario("club-assignment-without-finance-capability")["shadow_access_probes"][0]
        self.assertEqual(without["PROPOSED"], "DENY_IN_MODEL")
        self.assertIn("NO_EXACT_ACTIVE_ASSIGNMENT_AND_CAPABILITY", without["deny_reasons"])

    def test_all_financial_surfaces_deny_neighbour_club_and_other_client_even_with_membership(self):
        f = copy.deepcopy(self.fixture)
        f["snapshot"]["client_memberships"].append(dict(client_id=102, user_id=52, is_active=True, version=1))
        s = next(s for s in f["scenarios"] if s["id"] == "club-manager-finance-same-club-only")
        s["policy_fixture"]["assignments"][0]["permissions"] = list(shadow.FINANCIAL_SURFACES.values())
        for surface, capability in shadow.FINANCIAL_SURFACES.items():
            for client, club, expected in ((101, 1001, "ALLOW_IN_MODEL"), (101, 1002, "DENY_IN_MODEL"), (102, 2001, "DENY_IN_MODEL")):
                with self.subTest(surface=surface, client=client, club=club):
                    p = dict(kind="finance", capability=capability, surface=surface, client_id=client, club_id=club, signed_identity=True, shift_closed=True)
                    result = shadow.shadow_access(f["snapshot"], s, p)
                    self.assertEqual(result["PROPOSED"], expected)
                    self.assertEqual(result["DIFFERENCE"], {"grant_effects": [], "api_calls": 0})

    def test_financial_final_totals_require_closed_shift_without_caller_opt_in(self):
        s = next(s for s in self.fixture["scenarios"] if s["id"] == "business-owner-single-club")
        p = dict(kind="finance", capability="finance.closed_totals.read", client_id=101, club_id=1001, signed_identity=True)
        result = shadow.shadow_access(self.fixture["snapshot"], s, p)
        self.assertIn("CLOSED_SHIFT_REQUIRED_FOR_FINAL_TOTALS", result["deny_reasons"])
        p.update(capability="finance.report.read", surface="exports")
        self.assertIn("FINANCIAL_SURFACE_CAPABILITY_MISMATCH", shadow.shadow_access(self.fixture["snapshot"], s, p)["deny_reasons"])

    def test_manager_configuration_delegation_is_exact_and_gives_no_staff_or_finance(self):
        s = self.scenario("manager-granular-configuration-delegation")
        self.assertEqual(s["PROPOSED"][0]["existing_token_intent"], ["client.club.profile.write", "client.club.read"])
        self.assertEqual([p["PROPOSED"] for p in s["shadow_access_probes"]], ["ALLOW_IN_MODEL", "DENY_IN_MODEL", "DENY_IN_MODEL"])
        self.assertNotIn("shift.close", s["PROPOSED"][0]["action_intents"])
        self.assertNotIn("staff.disable.club", s["PROPOSED"][0]["action_intents"])

    def test_delegation_cannot_exceed_issuer_permissions_or_survive_inactive_issuer(self):
        for change in ("issuer_inactive", "issuer_no_permission", "missing_supporting_read", "issuer_read_revoked"):
            with self.subTest(change=change):
                f = copy.deepcopy(self.fixture)
                s = next(s for s in f["scenarios"] if s["id"] == "manager-granular-configuration-delegation")
                if change == "issuer_inactive": s["policy_fixture"]["assignments"][0]["is_active"] = False
                if change == "issuer_no_permission": s["policy_fixture"]["assignments"][0]["permissions"] = ["configuration.delegate"]
                if change == "missing_supporting_read": s["policy_fixture"]["delegations"][0]["permissions"] = ["client.club.profile.write"]
                if change == "issuer_read_revoked": s["policy_fixture"]["assignments"][0]["permissions"].remove("client.club.read")
                self.assertEqual(shadow.shadow_access(f["snapshot"], s, s["access_probes"][0])["PROPOSED"], "DENY_IN_MODEL")

    def test_explicit_finite_financial_shifts_never_turn_empty_into_all_shifts(self):
        s = copy.deepcopy(next(s for s in self.fixture["scenarios"] if s["id"] == "combined-staff-assignments-before-revoke"))
        fin = next(a for a in s["policy_fixture"]["assignments"] if a["role"] == "FINANCE_MANAGER")
        p = dict(s["access_probes"][1], shift_id=7001)
        fin["shift_ids"] = [7001]
        self.assertEqual(shadow.shadow_access(self.fixture["snapshot"], s, p)["PROPOSED"], "ALLOW_IN_MODEL")
        for shift_ids, shift in (([7001], 7002), ([7001], None), ([], 7001), ([None], None)):
            with self.subTest(shift_ids=shift_ids, shift=shift):
                fin["shift_ids"] = shift_ids
                p["shift_id"] = shift
                self.assertEqual(shadow.shadow_access(self.fixture["snapshot"], s, p)["PROPOSED"], "DENY_IN_MODEL")

    def test_financial_catalogue_distinguishes_manager_minimum_from_ledger(self):
        catalogue = self.report["catalogue"]
        manager = {a["action"] for a in catalogue["FLOOR_MANAGER"] if a["row"] == 5}
        finance = {a["action"] for a in catalogue["FINANCE_MANAGER"] if a["row"] == 5}
        self.assertEqual(manager, {"finance.table_minimum.read"})
        self.assertEqual(finance, {"finance.ledger.read", "finance.deposits.read", "finance.operations.read"})
        view = next(a for a in catalogue["FLOOR_MANAGER"] if a["action"] == "attribution.seating.read")
        self.assertEqual(view["product_status"], "PROPOSED")

    def test_promoter_three_of_five_creation_and_own_booking_read(self):
        s = self.scenario("promoter-assigned-three-of-five")
        self.assertEqual([p["PROPOSED"] for p in s["shadow_access_probes"]], ["ALLOW_IN_MODEL"] * 4 + ["DENY_IN_MODEL"] * 2)
        denied = self.scenario("promoter-fourth-and-fifth-club-denied")["shadow_access_probes"]
        self.assertTrue(all(p["PROPOSED"] == "DENY_IN_MODEL" for p in denied))
        self.assertEqual(s["CURRENT"]["active_client_tokens_with_read_prerequisite"], [])
        self.assertIn("booking.promoter.create", s["PROPOSED"][0]["accepted_product_actions"])
        self.assertNotIn("shift.close", s["PROPOSED"][0]["action_intents"])

    def test_promoter_booking_checks_every_required_guard_and_trusted_actor(self):
        s = next(s for s in self.fixture["scenarios"] if s["id"] == "promoter-assigned-three-of-five")
        for field in ("signed_identity", "night_available", "rules_valid", "table_available", "hold_invariants", "confirmed", "idempotency_ready", "audit_ready"):
            with self.subTest(field=field):
                p = dict(s["access_probes"][0]); p[field] = False
                self.assertEqual(shadow.shadow_access(self.fixture["snapshot"], s, p)["PROPOSED"], "DENY_IN_MODEL")
        p = dict(s["access_probes"][0], claimed_promoter_user_id=56)
        self.assertIn("UNTRUSTED_PROMOTER_BODY", shadow.shadow_access(self.fixture["snapshot"], s, p)["deny_reasons"])
        p = dict(s["access_probes"][0], resource_club_id=1004)
        self.assertIn("RESOURCE_SCOPE_MISMATCH", shadow.shadow_access(self.fixture["snapshot"], s, p)["deny_reasons"])

    def test_promoter_needs_active_assignment_membership_and_business_binding(self):
        for change in ("missing_permission", "membership_inactive", "business_mismatch"):
            with self.subTest(change=change):
                f = copy.deepcopy(self.fixture)
                s = next(s for s in f["scenarios"] if s["id"] == "promoter-assigned-three-of-five")
                if change == "missing_permission": s["policy_fixture"]["assignments"][0]["permissions"] = []
                if change == "membership_inactive":
                    next(m for m in f["snapshot"]["client_memberships"] if m["user_id"] == 55)["is_active"] = False
                if change == "business_mismatch": s["policy_fixture"]["assignments"][0]["business_id"] = "foreign-business"
                self.assertEqual(shadow.shadow_access(f["snapshot"], s, s["access_probes"][0])["PROPOSED"], "DENY_IN_MODEL")
        self.assertTrue(all(p["PROPOSED"] == "DENY_IN_MODEL" for p in self.scenario("promoter-assignment-revoked")["shadow_access_probes"]))

    def test_promoter_booking_permission_never_grants_financial_reporting(self):
        f = copy.deepcopy(self.fixture)
        s = next(s for s in f["scenarios"] if s["id"] == "promoter-assigned-three-of-five")
        s["policy_fixture"]["assignments"][0]["permissions"].append("finance.report.read")
        p = dict(kind="finance", capability="finance.report.read", client_id=101, club_id=1001, signed_identity=True)
        self.assertEqual(shadow.shadow_access(f["snapshot"], s, p)["PROPOSED"], "DENY_IN_MODEL")

    def test_assignment_revoke_delegation_revoke_and_full_disable_are_distinct(self):
        expected = {
            "combined-staff-assignments-before-revoke": ["ALLOW_IN_MODEL", "ALLOW_IN_MODEL"],
            "revoke-one-role-preserves-independent-finance": ["DENY_IN_MODEL", "ALLOW_IN_MODEL"],
            "revoke-one-delegation-preserves-other-role": ["DENY_IN_MODEL", "ALLOW_IN_MODEL"],
            "full-club-disable-distinct-from-one-revoke": ["DENY_IN_MODEL", "DENY_IN_MODEL"],
        }
        for name, outcomes in expected.items():
            with self.subTest(name=name):
                self.assertEqual([p["PROPOSED"] for p in self.scenario(name)["shadow_access_probes"]], outcomes)
        s = copy.deepcopy(next(s for s in self.fixture["scenarios"] if s["id"] == "combined-staff-assignments-before-revoke"))
        issuer = next(a for a in s["policy_fixture"]["assignments"] if a["id"] == "cm-3")
        issuer["permissions"].remove("client.club.read")
        self.assertEqual([shadow.shadow_access(self.fixture["snapshot"], s, p)["PROPOSED"] for p in s["access_probes"]], ["DENY_IN_MODEL", "ALLOW_IN_MODEL"])
        self.assertEqual(self.fixture, self.original)

    def test_attribution_source_and_financial_mode_are_independent(self):
        club = self.scenario("attribution-club-source-is-not-free")["attribution_preview"]
        promoter = self.scenario("attribution-promoter-is-not-payer")["attribution_preview"]
        funded = self.scenario("same-promoter-source-different-financial-mode")["attribution_preview"]
        self.assertEqual((club["CURRENT"]["source_kind"], club["CURRENT"]["financial_mode"]), ("CLUB", "BILL"))
        self.assertEqual((promoter["CURRENT"]["source_kind"], promoter["CURRENT"]["financial_mode"]), ("PROMOTER", "DEPOSIT"))
        self.assertEqual(promoter["CURRENT"]["promoter_assignment_id"], funded["CURRENT"]["promoter_assignment_id"])
        for a in (club, promoter, funded):
            self.assertEqual(a["DIFFERENCE"]["money_effects"], [])
            self.assertEqual(a["DIFFERENCE"]["grant_effects"], [])
            self.assertEqual(a["status"], "PROPOSED")

    def test_attribution_picker_excludes_foreign_revoked_and_inactive_membership(self):
        wrong = self.scenario("attribution-foreign-promoter-body-denied")["attribution_preview"]
        self.assertEqual(wrong["eligible_promoter_assignment_ids"], ["promoter-55", "promoter-56"])
        self.assertIn("INELIGIBLE_OR_FOREIGN_PROMOTER_ASSIGNMENT", wrong["RISK"])
        revoked = self.scenario("attribution-revoked-promoter-new-selection-denied")["attribution_preview"]
        self.assertEqual(revoked["eligible_promoter_assignment_ids"], ["promoter-56"])
        self.assertIn("INELIGIBLE_OR_FOREIGN_PROMOTER_ASSIGNMENT", revoked["RISK"])
        f = copy.deepcopy(self.fixture)
        next(m for m in f["snapshot"]["client_memberships"] if m["user_id"] == 56)["is_active"] = False
        s = next(s for s in f["scenarios"] if s["id"] == "attribution-foreign-promoter-body-denied")
        self.assertEqual(shadow.attribution_preview(f["snapshot"], s)["eligible_promoter_assignment_ids"], ["promoter-55"])

    def test_attribution_cancel_no_show_and_revoke_preserve_history(self):
        for ending in ("cancelled", "no_show"):
            a = self.scenario("attribution-" + ending + "-history-preserved")["attribution_preview"]
            self.assertEqual(a["counts_proposed"], dict(bookings_created=1, active_bookings=0, arrived=0, seated=0))
            self.assertEqual(a["historical_assignment_preserved"], "promoter-55")
        revoked = self.scenario("attribution-promoter-revoke-keeps-history")["attribution_preview"]
        self.assertEqual(revoked["CURRENT"]["source_kind"], "PROMOTER")
        self.assertEqual(revoked["historical_assignment_preserved"], "promoter-55")
        self.assertNotIn("promoter-55", revoked["eligible_promoter_assignment_ids"])

    def test_attribution_move_retry_and_entrance_are_not_double_counted(self):
        a = self.scenario("attribution-table-move-and-command-retry-no-double-count")["attribution_preview"]
        self.assertEqual(a["counts_proposed"], dict(bookings_created=1, active_bookings=1, arrived=1, seated=1))
        without_entrance = self.scenario("attribution-club-source-is-not-free")["attribution_preview"]
        self.assertEqual(without_entrance["counts_proposed"]["arrived"], 0)
        self.assertEqual(without_entrance["counts_proposed"]["seated"], 1)

    def test_attribution_correction_cannot_overwrite_origin_scope_or_financial_terms(self):
        f = copy.deepcopy(self.fixture)
        s = next(s for s in f["scenarios"] if s["id"] == "attribution-club-source-is-not-free")
        s["attribution_fixture"]["candidate"].update(financial_mode="FREE", origin=dict(actor_user_id=999))
        a = shadow.attribution_preview(f["snapshot"], s)
        self.assertIn("ATTRIBUTION_CANNOT_CHANGE_ORIGIN_SCOPE_OR_FINANCIAL_TERMS", a["RISK"])
        self.assertIn("EXPLICIT_AUTHORIZED_CORRECTION_REASON_VERSION_AUDIT_REQUIRED", a["RISK"])
        self.assertEqual(a["DIFFERENCE"]["applied_corrections"], 0)
        self.assertTrue(a["origin_preserved"])
        self.assertFalse(a["candidate_origin_unchanged"])
        self.assertEqual(s["attribution_fixture"]["current"]["financial_mode"], "BILL")

    def test_attribution_unknown_conflict_and_shared_enum_fail_closed(self):
        s = copy.deepcopy(next(s for s in self.fixture["scenarios"] if s["id"] == "attribution-club-source-is-not-free"))
        s["attribution_fixture"]["candidate"].update(source_kind="DEPOSIT", financial_mode="PROMOTER")
        event = dict(s["attribution_fixture"]["events"][0], kind="ARRIVED")
        s["attribution_fixture"]["events"].append(event)
        a = shadow.attribution_preview(self.fixture["snapshot"], s)
        self.assertIn("SOURCE_KIND_NOT_FINANCIAL_MODE", a["RISK"])
        self.assertIn("FINANCIAL_MODE_MUST_BE_INDEPENDENT", a["RISK"])
        self.assertIn("CONFLICTING_ATTRIBUTION_COMMAND", a["RISK"])

    def test_no_old_target_owner_name_is_automatically_adopted(self):
        for old in ("NETWORK_OWNER", "CLUB_OWNER", "OWNER", "GLOBAL_ADMIN"):
            f = copy.deepcopy(self.fixture)
            f["scenarios"][0]["targets"][0]["role"] = old
            s = shadow.build_report(f)["scenarios"][0]
            self.assertEqual(s["PROPOSED"], [])
            self.assertIn("UNKNOWN_TARGET_ROLE_NO_NAME_MAPPING", s["RISK"])

    def test_read_only_static_surface_and_compilation(self):
        source = SCRIPT.read_text()
        tree = ast.parse(source)
        compile(source, str(SCRIPT), "exec")
        imports = {n.names[0].name for n in ast.walk(tree) if isinstance(n, ast.Import)}
        imports |= {n.module for n in ast.walk(tree) if isinstance(n, ast.ImportFrom)}
        self.assertEqual(imports, {"argparse", "hashlib", "json", "pathlib"})
        forbidden = {"write_text", "write_bytes", "unlink", "rename", "replace", "mkdir", "rmdir", "open", "connect", "execute", "executescript", "exec", "eval", "getenv", "apply"}
        for n in ast.walk(tree):
            if isinstance(n, ast.Call):
                name = n.func.attr if isinstance(n.func, ast.Attribute) else n.func.id if isinstance(n.func, ast.Name) else None
                self.assertNotIn(name, forbidden)

    def test_cli_disposable_cwd_no_output_files_and_no_database_apply_flags(self):
        with tempfile.TemporaryDirectory(prefix="clb-role-shadow-") as directory:
            base = [sys.executable, "-I", "-S", "-B", str(SCRIPT)]
            result = subprocess.run(base, cwd=directory, capture_output=True, text=True, check=True)
            self.assertEqual(json.loads(result.stdout)["writes"], 0)
            self.assertEqual(list(Path(directory).iterdir()), [])
            for flag in ("--apply", "--database-url", "--input", "--output"):
                result = subprocess.run(base + [flag, "synthetic"], cwd=directory, capture_output=True, text=True)
                self.assertEqual(result.returncode, 2)
            self.assertEqual(list(Path(directory).iterdir()), [])


if __name__ == "__main__":
    unittest.main()
