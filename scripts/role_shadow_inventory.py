#!/usr/bin/env python3
"""Offline, synthetic-only comparison. No DB, network, grant writer or apply mode.

CURRENT contains stored authority evidence and a bounded client-token projection,
not a claim that every API/domain guard allows the operation. PROPOSED is matrix
intent, never effective authority. The bundled fixture is the only CLI input.
"""

import argparse
import hashlib
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "scripts/tests/fixtures/role-shadow-inventory.json"
PROPOSAL = "docs/product/ROLE_WORKSPACES_PROPOSED.md"
REVISION = "5550b944990d8c1c93c0a5012d31db209f93ea6d"
EVIDENCE_SOURCES = (
    PROPOSAL, "docs/product/PRODUCT_SPEC.md", "docs/product/OPEN_DECISIONS.md",
    "docs/product/PRODUCT_ROADMAP.md", "docs/ops/owner-team-contract.md", "docs/ops/owner-manager-contract.md",
    "core-data/src/main/kotlin/com/example/bot/data/security/Role.kt",
    "core-data/src/main/resources/db/migration/postgresql/V1__init.sql",
    "core-data/src/main/resources/db/migration/postgresql/V058__user_role_permissions.sql",
    "core-data/src/main/resources/db/migration/postgresql/V061__client_context.sql",
    "core-data/src/main/resources/db/migration/postgresql/V062__client_onboarding_a.sql",
    "core-data/src/main/resources/db/migration/postgresql/V075__client_club_staff.sql",
    "core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubStaffAccess.kt",
    "core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubTeamOperations.kt",
    "core-data/src/main/kotlin/com/example/bot/data/client/onboarding/StaffInvitationOperations.kt",
    "core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClientAccessOperations.kt",
    "core-data/src/main/kotlin/com/example/bot/data/client/onboarding/OnboardingSql.kt",
    "core-data/src/main/kotlin/com/example/bot/data/client/onboarding/JdbcClientOnboardingRepository.kt",
    "core-security/src/main/kotlin/com/example/bot/security/rbac/RbacHelpers.kt",
    "app-bot/src/main/kotlin/com/example/bot/plugins/PrivateClubContainment.kt",
    "core-data/src/main/kotlin/com/example/bot/data/booking/BookingTables.kt",
    "core-data/src/main/kotlin/com/example/bot/data/booking/core/BookingCoreModels.kt",
    "core-data/src/main/kotlin/com/example/bot/data/booking/core/CanonicalBookingTransaction.kt",
    "core-data/src/main/kotlin/com/example/bot/data/booking/core/BookingRepository.kt",
    "core-data/src/main/kotlin/com/example/bot/data/promo/PromoRepositories.kt",
    "core-data/src/main/kotlin/com/example/bot/data/promoter/PromoterAdminRepositoryImpl.kt",
)
ROLE_IDS = (
    "BUSINESS_OWNER", "NETWORK_MANAGER", "CLUB_MANAGER",
    "FLOOR_MANAGER", "ENTRANCE_MANAGER", "FINANCE_MANAGER", "PROMOTER", "GUEST",
)
PREFIX = "client.club."
PACKAGES = {
    "ENTRY_MANAGER": ("read",),
    "MANAGER": ("read", "profile.write", "layout.write", "calendar.write", "booking_rules.write"),
    "CLUB_ADMIN": (
        "read", "profile.write", "layout.write", "calendar.write", "booking_rules.write",
        "finance.read", "finance.write", "modules.read", "modules.write",
    ),
}
CLIENT_CODES = frozenset(PREFIX + suffix for suffix in (*PACKAGES["CLUB_ADMIN"], "team.manage"))
LEGACY_ROLES = frozenset(("OWNER", "GLOBAL_ADMIN", "HEAD_MANAGER", "CLUB_ADMIN", "MANAGER", "ENTRY_MANAGER", "PROMOTER", "GUEST"))

# One tuple per existing matrix row; labels beyond CLIENT_CODES are report-only
# actions, NOT proposed production permission_code values or a new role registry.
ROW_ACTIONS = (
    ("storefront.view",), ("preparation.overview.read", "night.aggregates.read"),
    ("network.aggregates.compare",), ("finance.closed_totals.read", "finance.report.read"),
    ("finance.ledger.read", "finance.deposits.read", "finance.operations.read"), ("floor.tables.read",), ("entrance.entries.search",),
    ("booking.promoter.own.read",), ("self.records.read", "self.subscription.write"),
    (PREFIX + "read",), (PREFIX + "profile.write",), (PREFIX + "layout.write",),
    (PREFIX + "calendar.write", "calendar.materialize"), (PREFIX + "booking_rules.write",),
    tuple(PREFIX + x for x in ("finance.read", "finance.write", "modules.read", "modules.write")),
    ("preparation.self.start", "preparation.submit"),
    ("staff.network_manager.assign", "staff.network_manager.revoke"),
    ("staff.club_manager.assign",), ("staff.club_manager.revoke",),
    ("staff.floor_manager.assign", "staff.entrance_manager.assign", "staff.finance_manager.assign", "staff.promoter.assign"),
    ("configuration.delegate",), ("assignment.revoke.one", "staff.disable.club"),
    ("staff.roster.redacted.read",),
    ("self.booking.hold", "self.booking.confirm", "self.booking.cancel"),
    ("booking.arrival_retention.extend",), ("table.seat.booked", "table.seat.walk_in"),
    ("table.release", "booking.no_show", "table.sales.stop", "table.sales.undo"),
    ("night_pass.validate", "entrance.arrival.record", "entrance.denial.record"),
    ("promoter.own_lists.read", "promoter.own_outcomes.read", "promoter.own_list.create", "promoter.own_list.append", "promoter.own_list.import", "promoter.own_invitation.issue"),
    ("booking.promoter.create",), ("shift.people.write", "shift.bracelets.write", "shift.revenue.write"),
    ("table.deposit.record", "table.top_up.record", "table.security_service.record"), ("shift.close",),
    ("attribution.seating.read",), ("attribution.correction.request",), ("attribution.correction.approve",),
    ("audit.own_operations.read",), ("business.emergency_recovery",), (),
)
GATES = {
    "network": "verified business ownership/explicit finite assignments; no membership inheritance",
    "entrance": "DEC-010/011/020/021; list/pass separate from accepted promoter booking",
    "floor": "accepted operation matrix; canonical night/booking/table; DEC-012/021/029",
    "money": "DEC-012 + explicit monetary/ledger/allocations contract",
    "promoter": "DEC-006 accepted assigned scope; canonical DEC-008/009/018 guards; lists DEC-010 separate",
    "data": "redacted roster/ledger/PII/history data contract",
    "hierarchy": "accepted appointment hierarchy; proposed exact capability/admission/revoke contract",
    "finance": "server exact club/shift scope AND financial capability; no booking-to-finance inheritance",
    "attribution": "accepted source != financial terms; correction/rewards/UI contract PROPOSED",
    "recovery": "separate safe emergency contract PROPOSED; no GLOBAL bypass",
}
FINANCIAL_SURFACES = {
    "reports": "finance.report.read", "revenue": "finance.revenue.read", "ledger": "finance.ledger.read",
    "closed_totals": "finance.closed_totals.read", "deposits": "finance.deposits.read",
    "operations": "finance.operations.read", "aggregates": "finance.aggregates.read",
    "exports": "finance.exports.read", "analytics": "finance.analytics.read",
    "related_private": "finance.private.read", "table_minimum": "finance.table_minimum.read",
}
TABLE_KEYS = {
    "clients": {"id", "is_active", "archived_at"},
    "client_clubs": {"client_id", "club_id"},
    "creators_projection": {"client_id", "club_id", "owner_user_id"},
    "client_memberships": {"client_id", "user_id", "is_active", "version"},
    "client_membership_grants": {"client_id", "user_id", "permission_code", "is_active", "version"},
    "client_club_grants": {"client_id", "user_id", "club_id", "permission_code", "is_active"},
    "client_club_staff": {"client_id", "club_id", "user_id", "role_code", "is_active", "version", "invitation_id", "revoked_through_invitation_id"},
    "user_roles": {"id", "user_id", "role_code", "scope_type", "scope_club_id"},
    "user_role_permissions": {"user_role_id", "permission_code"},
    "invitations_projection": {"id", "client_id", "club_id", "purpose", "staff_role", "status", "claimant_user_id", "version", "expires_at"},
    "commands_projection": {"actor_user_id", "client_id", "result_type", "result_id"},
    "audit_projection": {"id", "actor_user_id", "club_id", "action"},
}


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()).hexdigest()


def load_fixture():
    value = json.loads(FIXTURE.read_text())
    validate_fixture(value)
    return value


def validate_fixture(value):
    if value.get("synthetic_only") is not True or value.get("schema") != "CLB-role-shadow-fixture-v1":
        raise ValueError("synthetic fixture marker required")
    if value.get("source_revision") != REVISION:
        raise ValueError("source revision mismatch")
    if set(value["snapshot"]) != set(TABLE_KEYS):
        raise ValueError("unsupported snapshot tables")
    for table, keys in TABLE_KEYS.items():
        for row in value["snapshot"][table]:
            if set(row) != keys:
                raise ValueError("unsupported projection fields: " + table)
            if "is_active" in row and type(row["is_active"]) is not bool:
                raise ValueError("active must be boolean")
            for key, number in row.items():
                if (key == "id" or key.endswith("_id") or key == "version") and number is not None:
                    if type(number) is not int or number < (0 if key == "revoked_through_invitation_id" else 1):
                        raise ValueError("invalid identifier/version")
    identities = {
        "clients": ("id",), "client_clubs": ("club_id",),
        "client_memberships": ("client_id", "user_id"),
        "client_membership_grants": ("client_id", "user_id", "permission_code"),
        "client_club_grants": ("client_id", "user_id", "club_id", "permission_code"),
        "client_club_staff": ("client_id", "club_id", "user_id"), "user_roles": ("id",),
        "user_role_permissions": ("user_role_id", "permission_code"),
        "invitations_projection": ("id",), "audit_projection": ("id",),
    }
    for table, keys in identities.items():
        identities_seen = [tuple(row[k] for k in keys) for row in value["snapshot"][table]]
        if len(identities_seen) != len(set(identities_seen)):
            raise ValueError("duplicate identity: " + table)
    for staff in value["snapshot"]["client_club_staff"]:
        if not any(i["id"] == staff["invitation_id"] and i["client_id"] == staff["client_id"] and i["club_id"] == staff["club_id"] for i in value["snapshot"]["invitations_projection"]):
            raise ValueError("staff invitation reference mismatch")
    if set(value["source_sha256"]) != set(EVIDENCE_SOURCES):
        raise ValueError("complete source fingerprint set required")
    for relative, expected in value["source_sha256"].items():
        if not isinstance(expected, dict) or set(expected) != {"algorithm", "digest"} or expected["algorithm"] != "SHA-256":
            raise ValueError("explicit public checksum required: " + relative)
        path = ROOT / relative
        if path.resolve().is_relative_to(ROOT) is False or hashlib.sha256(path.read_bytes()).hexdigest() != expected["digest"]:
            raise ValueError("source evidence drift: " + relative)


def matrix_catalogue():
    section = (ROOT / PROPOSAL).read_text().split("<!-- ROLE_MATRIX_CURRENT -->", 1)[1].split("<!-- /ROLE_MATRIX_CURRENT -->", 1)[0]
    rows = section.strip().splitlines()[2:]
    if len(rows) != len(ROW_ACTIONS):
        raise ValueError("matrix row count changed")
    catalogue = {role: [] for role in ROLE_IDS}
    for number, (line, actions) in enumerate(zip(rows, ROW_ACTIONS), 1):
        cells = [x.strip() for x in line.split("|")[1:-1]]
        if len(cells) != len(ROLE_IDS) + 1:
            raise ValueError("matrix shape changed")
        for role, cell in zip(ROLE_IDS, cells[1:]):
            if cell == "—":
                continue
            if cell not in ("A", "P", "AD") or not actions:
                raise ValueError("unknown matrix cell")
            selected_actions = ("finance.table_minimum.read",) if number == 5 and role == "FLOOR_MANAGER" else actions
            for action in selected_actions:
                catalogue[role].append({"action": action, "row": number, "cell": cell,
                                        "product_status": "ACCEPTED" if cell in ("A", "AD") else "PROPOSED",
                                        "requires_delegation": cell == "AD", "status": "PROPOSED"})
    return catalogue


def client_context(snapshot, user, client, club):
    reasons = []
    if not any(c["id"] == client and c["is_active"] and c["archived_at"] is None for c in snapshot["clients"]):
        reasons.append("CLIENT_INACTIVE_OR_ARCHIVED")
    if not any(m["client_id"] == client and m["user_id"] == user and m["is_active"] for m in snapshot["client_memberships"]):
        reasons.append("MEMBERSHIP_INACTIVE_OR_MISSING")
    if not any(m["client_id"] == client and m["club_id"] == club for m in snapshot["client_clubs"]):
        reasons.append("CLIENT_CLUB_MAPPING_MISMATCH")
    return reasons


def current_inventory(snapshot, user):
    raw = {table: [dict(row) for row in snapshot[table] if row.get("user_id", row.get("claimant_user_id", row.get("actor_user_id"))) == user]
           for table in ("client_memberships", "client_membership_grants", "client_club_grants", "client_club_staff", "user_roles", "invitations_projection", "commands_projection", "audit_projection")}
    ids = {row["id"] for row in raw["user_roles"]}
    raw["user_role_permissions"] = [dict(row) for row in snapshot["user_role_permissions"] if row["user_role_id"] in ids]
    raw["creators_projection"] = [dict(row) for row in snapshot["creators_projection"] if row["owner_user_id"] == user]
    effective = []
    for row in raw["client_club_grants"]:
        client, club = row["client_id"], row["club_id"]
        read = any(g["client_id"] == client and g["club_id"] == club and g["permission_code"] == PREFIX + "read" and g["is_active"] for g in raw["client_club_grants"])
        if row["permission_code"] in CLIENT_CODES and row["is_active"] and read and not client_context(snapshot, user, client, club):
            effective.append(dict(row))
    return {"raw": raw, "active_client_tokens_with_read_prerequisite": effective,
            "active_client_membership_tokens": [dict(g) for g in raw["client_membership_grants"] if g["is_active"] and any(m["client_id"] == g["client_id"] and m["is_active"] for m in raw["client_memberships"]) and any(c["id"] == g["client_id"] and c["is_active"] and c["archived_at"] is None for c in snapshot["clients"])],
            "endpoint_authorization_unverified": True,
            "legacy_authority": "opaque; exact assignment IDs/attached permissions retained, no client adoption"}


def writer_preview(snapshot, user, probe):
    package = probe["package"]
    if package is not None and package not in PACKAGES:
        raise ValueError("unknown existing package")
    replacement = {PREFIX + p for p in PACKAGES[package]} if package else set()
    rows = [g for g in snapshot["client_club_grants"] if (g["client_id"], g["club_id"], g["user_id"]) == (probe["client_id"], probe["club_id"], user)]
    return {"scope": [probe["client_id"], probe["club_id"], user], "package": package,
            "would_disable": sorted(g["permission_code"] for g in rows if g["is_active"] and g["permission_code"] not in replacement),
            "would_reactivate_tombstones": sorted(g["permission_code"] for g in rows if not g["is_active"] and g["permission_code"] in replacement),
            "would_create": sorted(replacement - {g["permission_code"] for g in rows}),
            "hypothetical_future_actions_lost": sorted(probe.get("future_actions", [])),
            "future_actions_not_valid_in_current_schema": True,
            "cutover": "BLOCKED_ALL_WRITERS_CONTRACT", "executed": False}


def scenario_report(snapshot, scenario, catalogue, stale=False):
    user = scenario["user_id"]
    current = current_inventory(snapshot, user)
    risks, proposed, differences = [], [], []
    if stale:
        risks.append("STALE_BASELINE")
    if any(g["permission_code"] not in CLIENT_CODES for g in current["raw"]["client_club_grants"]):
        risks.append("UNSUPPORTED_CURRENT_CLIENT_PERMISSION")
    for row in current["raw"]["user_roles"]:
        valid = row["role_code"] in LEGACY_ROLES and ((row["scope_type"] == "GLOBAL" and row["scope_club_id"] is None) or (row["scope_type"] == "CLUB" and row["scope_club_id"] is not None))
        risks.append("LEGACY_MANUAL_MAPPING_REQUIRED" if valid else "INVALID_LEGACY_SCOPE_OR_ROLE")
        if row["role_code"] in ("OWNER", "GLOBAL_ADMIN", "HEAD_MANAGER"):
            risks.append("LEGACY_GLOBAL_BYPASS_NOT_NETWORK_AUTHORITY")
    for staff in current["raw"]["client_club_staff"]:
        matching = [g for g in current["raw"]["client_club_grants"] if g["client_id"] == staff["client_id"] and g["club_id"] == staff["club_id"] and g["is_active"]]
        if not staff["is_active"] and matching:
            risks.append("INACTIVE_STAFF_WITH_ACTIVE_GRANTS_NOT_AUTHORITY_DENIAL")
    for target in scenario["targets"]:
        role = target["role"]
        if role not in catalogue:
            risks.append("UNKNOWN_TARGET_ROLE_NO_NAME_MAPPING")
            continue
        if target["scope"] == "SELF" and role == "GUEST":
            actions = sorted({a["action"] for a in catalogue[role]})
            blockers = ["TECHNICAL_PERMISSION_CONTRACT_PROPOSED", "GUEST_OWNERSHIP_STOREFRONT_AND_CUTOVER_GATES", "SELF_CURRENT_AUTHORIZATION_UNVERIFIED"]
            proposed.append({"role": role, "scope": "SELF:" + str(user), "status": "PROPOSED",
                             "action_intents": actions, "blockers": blockers})
            differences.append({"scope": "SELF:" + str(user), "roles": [role], "intent_only": True,
                                "would_add_existing_tokens": [], "would_remove_existing_tokens": [],
                                "retained_existing_tokens": [], "restoration_attempts": [],
                                "new_policy_action_intents": actions, "applied_additions": [], "applied_removals": []})
            risks.extend(blockers)
            continue
        if target["scope"] != "CLUBS" or not target.get("club_ids") or len(target["club_ids"]) != len(set(target["club_ids"])):
            risks.append("NO_GLOBAL_OR_ALL_CLUBS_OR_IMPLICIT_SCOPE")
            continue
        client = target["client_id"]
        for club in target["club_ids"]:
            blockers = ["TECHNICAL_PERMISSION_CONTRACT_PROPOSED", *client_context(snapshot, user, client, club)]
            owners = {c["owner_user_id"] for c in snapshot["creators_projection"] if c["client_id"] == client and c["club_id"] == club}
            if len(owners) > 1:
                blockers.append("AMBIGUOUS_CREATOR_PROVENANCE")
            staff = [s for s in current["raw"]["client_club_staff"] if s["client_id"] == client and s["club_id"] == club]
            if staff and not staff[0]["is_active"]:
                blockers.append("INACTIVE_STAFF_NEW_ADMISSION_REQUIRED")
            selected = [a for a in catalogue[role] if not a["requires_delegation"] or a["action"] in target.get("delegated_permissions", [])]
            actions = {a["action"] for a in selected}
            rows = {a["row"] for a in selected}
            policy_gates = {GATES[k] for k, numbers in {
                "network": {3, 17, 18}, "entrance": {7, 28}, "floor": {6, 25, 26, 27},
                "money": {31, 32, 33}, "promoter": {8, 29, 30}, "data": {4, 5, 9, 23, 37},
                "hierarchy": {17, 18, 19, 20, 21, 22, 23}, "finance": {4, 5},
                "attribution": {34, 35, 36}, "recovery": {38},
            }.items() if rows & numbers}
            tokens = actions & CLIENT_CODES
            if role == "BUSINESS_OWNER":
                blockers.append("VERIFIED_BUSINESS_OWNERSHIP_BINDING_REQUIRED_NOT_LEGACY_OWNER")
            raw_rows = [g for g in current["raw"]["client_club_grants"] if g["client_id"] == client and g["club_id"] == club]
            tombstones = {g["permission_code"] for g in raw_rows if not g["is_active"]}
            restoration = tokens & tombstones
            if restoration:
                blockers.append("TOMBSTONE_REQUIRES_EXPLICIT_NEW_ADMISSION")
            if any(i["club_id"] == club and i["client_id"] == client and i["purpose"] == "staff" for i in current["raw"]["invitations_projection"]):
                blockers.append("OLD_INVITATION_NOT_NEW_ROLE_AUTHORITY")
            if stale:
                blockers.append("STALE_BASELINE")
            proposed.append({"role": role, "scope": [client, club, user], "status": "PROPOSED",
                             "existing_token_intent": sorted(tokens), "action_intents": sorted(actions - CLIENT_CODES),
                             "accepted_product_actions": sorted({a["action"] for a in selected if a["product_status"] == "ACCEPTED"}),
                             "blockers": sorted(set(blockers)), "policy_gates": sorted(policy_gates), "permission_matrix_accepted": False})
            risks.extend(blockers)
            if role in ("BUSINESS_OWNER", "NETWORK_MANAGER"):
                risks.append("NETWORK_MODEL_AND_FINITE_ASSIGNMENTS_REQUIRED")
            sources = set(target.get("source_legacy_ids", []))
            if sources - {r["id"] for r in current["raw"]["user_roles"]}:
                risks.append("UNKNOWN_OR_FOREIGN_SOURCE_ASSIGNMENT")
            if sources:
                differences.append({"scope_change_candidate": {"legacy": [r for r in current["raw"]["user_roles"] if r["id"] in sources],
                                                            "target_scope": [client, club, user]},
                                    "disposition": "MANUAL_ONLY_LEGACY_RETAINED_NO_ADOPTION"})
    # Union ONLY explicit target assignments on the same exact tuple. Never mix
    # a role from club A with the scope or tokens of club B / another client.
    scopes = sorted({tuple(p["scope"]) for p in proposed if isinstance(p["scope"], list)})
    for scope in scopes:
        scoped = [p for p in proposed if p["scope"] == list(scope)]
        tokens = {x for p in scoped for x in p["existing_token_intent"]}
        actions = {x for p in scoped for x in p["action_intents"]}
        before = {g["permission_code"] for g in current["active_client_tokens_with_read_prerequisite"] if (g["client_id"], g["club_id"], user) == scope}
        tombstones = {g["permission_code"] for g in current["raw"]["client_club_grants"] if (g["client_id"], g["club_id"], user) == scope and not g["is_active"]}
        differences.append({"scope": list(scope), "roles": sorted({p["role"] for p in scoped}),
                            "would_add_existing_tokens": sorted(tokens - before), "would_remove_existing_tokens": sorted(before - tokens),
                            "retained_existing_tokens": sorted(tokens & before), "restoration_attempts": sorted(tokens & tombstones),
                            "new_policy_action_intents": sorted(actions), "applied_additions": [], "applied_removals": []})
        if tokens - before or actions:
            risks.append("EXPANSION_REQUIRES_EXPLICIT_DECISION_AND_ISSUANCE")
        if before - tokens:
            risks.append("REPLACEMENT_WOULD_LOSE_CONFIGURATION_ACCESS_KEEP_COMPATIBILITY")
    probes = [writer_preview(snapshot, user, p) for p in scenario.get("writer_probes", [])]
    if probes:
        risks.append("OLD_REPLACE_WRITER_UNSAFE_FOR_MIXED_CAPABILITIES")
    if not scenario["targets"]:
        risks.append("NO_EXPLICIT_TARGET_NO_AUTOMATIC_ROLE_ASSIGNMENT")
    policy_results = [shadow_access(snapshot, scenario, p) for p in scenario.get("access_probes", [])]
    attribution = attribution_preview(snapshot, scenario) if "attribution_fixture" in scenario else None
    for p in policy_results:
        risks.extend(p["deny_reasons"])
    if attribution:
        risks.extend(attribution["RISK"])
    return {"id": scenario["id"], "user_id": user, "CURRENT": current, "PROPOSED": proposed,
            "DIFFERENCE": differences, "RISK": sorted(set(risks)), "writer_preview": probes,
            "shadow_access_probes": policy_results, "attribution_preview": attribution}


def shadow_access(snapshot, scenario, probe):
    """Illustrative future policy only; ALLOW_IN_MODEL never authorizes an API."""
    user = scenario["user_id"]
    client, club = probe["client_id"], probe["club_id"]
    reasons = client_context(snapshot, user, client, club)
    if probe.get("signed_identity") is not True:
        reasons.append("SIGNED_IDENTITY_REQUIRED")
    if (probe.get("resource_client_id", client), probe.get("resource_club_id", club)) != (client, club):
        reasons.append("RESOURCE_SCOPE_MISMATCH")
    model = scenario.get("policy_fixture", {})
    capability = probe["capability"]
    allowed_capabilities = {
        "finance": set(FINANCIAL_SURFACES.values()),
        "booking.create": {"booking.promoter.create"}, "booking.own.read": {"booking.promoter.own.read"},
        "configuration": CLIENT_CODES - {PREFIX + "team.manage"},
    }
    if capability not in allowed_capabilities.get(probe["kind"], set()):
        reasons.append("CAPABILITY_KIND_MISMATCH")
    candidates = [a for a in model.get("assignments", []) if a["user_id"] == user and a["is_active"] is True
                  and a["client_id"] == client and club in a["club_ids"] and capability in a["permissions"]]
    if len({a["id"] for a in model.get("assignments", [])}) != len(model.get("assignments", [])):
        reasons.append("AMBIGUOUS_FUTURE_ASSIGNMENT_IDS")
    if probe["kind"] == "finance":
        if "surface" in probe and FINANCIAL_SURFACES.get(probe["surface"]) != capability:
            reasons.append("FINANCIAL_SURFACE_CAPABILITY_MISMATCH")
        allowed = {"BUSINESS_OWNER", "NETWORK_MANAGER", "CLUB_MANAGER", "FINANCE_MANAGER"}
        if capability == "finance.table_minimum.read":
            allowed.add("FLOOR_MANAGER")
        candidates = [a for a in candidates if a["role"] in allowed and
                      ("shift_ids" not in a or (isinstance(a["shift_ids"], list) and
                       all(type(s) is int and s > 0 for s in a["shift_ids"]) and
                       type(probe.get("shift_id")) is int and probe["shift_id"] in a["shift_ids"]))]
        candidates = [a for a in candidates if a["role"] != "BUSINESS_OWNER" or any(
            b.get("owner_user_id") == user and b["client_id"] == client and club in b["club_ids"] for b in model.get("businesses", []))]
        if (capability == "finance.closed_totals.read" or probe.get("final_only")) and probe.get("shift_closed") is not True:
            reasons.append("CLOSED_SHIFT_REQUIRED_FOR_FINAL_TOTALS")
    elif probe["kind"] in ("booking.create", "booking.own.read"):
        candidates = [a for a in candidates if a["role"] == "PROMOTER" and any(
            b["id"] == a.get("business_id") and b["client_id"] == client and club in b["club_ids"]
            for b in model.get("businesses", []))]
        if probe["kind"] == "booking.create":
            for guard in ("night_available", "rules_valid", "table_available", "hold_invariants", "confirmed", "idempotency_ready", "audit_ready"):
                if probe.get(guard) is not True:
                    reasons.append("BOOKING_GUARD_" + guard.upper())
            if "claimed_promoter_user_id" in probe and probe["claimed_promoter_user_id"] != user:
                reasons.append("UNTRUSTED_PROMOTER_BODY")
        elif probe.get("booking_actor_user_id") != user:
            reasons.append("FOREIGN_BOOKING_NOT_OWNED")
    elif probe["kind"] == "configuration":
        # Delegation is a separate object tied to both live issuer and recipient
        # assignments. A role label or financial permission is not a config grant.
        live = {a["id"]: a for a in model.get("assignments", []) if a["is_active"] is True}
        candidates = [a for a in candidates if a["role"] == "CLUB_MANAGER"]
        delegatable = {PREFIX + x for x in PACKAGES["MANAGER"]}
        for d in model.get("delegations", []):
            issuer, recipient = live.get(d["issuer_assignment_id"]), live.get(d["recipient_assignment_id"])
            if d["is_active"] is True and capability in delegatable and capability in d["permissions"] and issuer and recipient:
                if (d["client_id"], d["club_id"]) == (client, club) and issuer["role"] == "CLUB_MANAGER" and recipient["role"] == "FLOOR_MANAGER" and recipient["user_id"] == user:
                    if "configuration.delegate" in issuer["permissions"] and capability in issuer["permissions"] and PREFIX + "read" in issuer["permissions"] and PREFIX + "read" in d["permissions"] and all(a["client_id"] == client and club in a["club_ids"] and not client_context(snapshot, a["user_id"], client, club) for a in (issuer, recipient)):
                        candidates.append(recipient)
    else:
        reasons.append("UNKNOWN_PROBE_KIND")
    if not candidates:
        reasons.append("NO_EXACT_ACTIVE_ASSIGNMENT_AND_CAPABILITY")
    return {"kind": probe["kind"], "scope": [client, club, user], "capability": capability,
            "CURRENT": "NO_NEW_RUNTIME_AUTHORITY", "PROPOSED": "ALLOW_IN_MODEL" if not reasons else "DENY_IN_MODEL",
            "DIFFERENCE": {"grant_effects": [], "api_calls": 0}, "deny_reasons": sorted(set(reasons)),
            "matched_assignment_ids": sorted({a["id"] for a in candidates}), "permission_status": "PROPOSED"}


def attribution_preview(snapshot, scenario):
    """Compare hypothetical source/history/terms without applying corrections."""
    data = scenario["attribution_fixture"]
    current = data["current"]
    candidate = data.get("candidate", current)
    model = scenario.get("policy_fixture", {})
    client, club = current["client_id"], current["club_id"]
    eligible = [a for a in model.get("assignments", []) if a["role"] == "PROMOTER" and a["is_active"] is True and
                a["client_id"] == client and club in a["club_ids"] and not client_context(snapshot, a["user_id"], client, club)
                and any(b["id"] == a.get("business_id") and b["client_id"] == client and club in b["club_ids"] for b in model.get("businesses", []))]
    risks = ["ATTRIBUTION_CORRECTION_UI_FINANCIAL_MODES_REWARDS_PROPOSED"]
    if candidate["source_kind"] not in ("CLUB", "PROMOTER", "UNKNOWN"):
        risks.append("SOURCE_KIND_NOT_FINANCIAL_MODE")
    if candidate["financial_mode"] in ("CLUB", "PROMOTER"):
        risks.append("FINANCIAL_MODE_MUST_BE_INDEPENDENT")
    reference = candidate.get("promoter_assignment_id")
    if candidate["source_kind"] == "PROMOTER" and candidate != current and reference not in {a["id"] for a in eligible}:
        risks.append("INELIGIBLE_OR_FOREIGN_PROMOTER_ASSIGNMENT")
    if candidate["source_kind"] == "CLUB" and reference is not None:
        risks.append("CLUB_SOURCE_CANNOT_CARRY_PROMOTER_ASSIGNMENT")
    if candidate != current:
        risks.append("EXPLICIT_AUTHORIZED_CORRECTION_REASON_VERSION_AUDIT_REQUIRED")
    if any(current.get(k) != candidate.get(k) for k in ("origin", "booking_id", "client_id", "club_id", "financial_mode")):
        risks.append("ATTRIBUTION_CANNOT_CHANGE_ORIGIN_SCOPE_OR_FINANCIAL_TERMS")
    events = data.get("events", [])
    fingerprints = {}
    for e in events:
        key = (e["subject_id"], e["command_ref"])
        if key in fingerprints and fingerprints[key] != digest(e):
            risks.append("CONFLICTING_ATTRIBUTION_COMMAND")
        fingerprints[key] = digest(e)
    kinds = {e["kind"] for e in events if e["subject_id"] == current["booking_id"]}
    if "NO_SHOW" in kinds and kinds & {"ARRIVED", "SEATED"}:
        risks.append("CONTRADICTORY_NO_SHOW_OUTCOME_REQUIRES_REVIEW")
    return {"CURRENT": dict(current), "PROPOSED": dict(candidate),
            "DIFFERENCE": {"source_changed": current["source_kind"] != candidate["source_kind"] or current.get("promoter_assignment_id") != candidate.get("promoter_assignment_id"),
                           "money_effects": [], "grant_effects": [], "applied_corrections": 0},
            "RISK": sorted(set(risks)), "origin_preserved": True,
            "candidate_origin_unchanged": current.get("origin") == candidate.get("origin"),
            "eligible_promoter_assignment_ids": sorted(a["id"] for a in eligible),
            "counts_proposed": {"bookings_created": int("BOOKED" in kinds), "active_bookings": int("BOOKED" in kinds and not kinds & {"CANCELLED", "NO_SHOW"}),
                                "arrived": int("ARRIVED" in kinds), "seated": int("SEATED" in kinds)},
            "historical_assignment_preserved": current.get("promoter_assignment_id"), "status": "PROPOSED"}


def build_report(fixture, *, lifecycle="preview", expected_snapshot_digest=None):
    validate_fixture(fixture)
    if lifecycle not in ("preview", "cancel", "rollback"):
        raise ValueError("no apply mode")
    snapshot = fixture["snapshot"]
    before = digest(snapshot)
    catalogue = matrix_catalogue()
    scenarios = [scenario_report(snapshot, s, catalogue, expected_snapshot_digest is not None and expected_snapshot_digest != before) for s in fixture["scenarios"]]
    return {"schema": "CLB-role-shadow-report-v1", "synthetic_only": True, "source_revision": REVISION,
            "source_sha256": fixture["source_sha256"], "fixture_sha256": digest(fixture),
            "lifecycle": lifecycle, "writes": 0, "snapshot_before_sha256": before, "snapshot_after_sha256": digest(snapshot),
            "actual_authority_unchanged": before == digest(snapshot), "permission_matrix_status": "PRODUCT_ACCEPTED_DETAILS_PROPOSED",
            "comparison_boundary": "exact client tokens; legacy IDs/scopes/permissions opaque, no API/domain authorization proof",
            "technical_model": {"legacy_roles": sorted(LEGACY_ROLES), "legacy_scopes": ["GLOBAL", "CLUB"],
                                "client_staff_packages": {r: [PREFIX + x for x in p] for r, p in PACKAGES.items()},
                                "club_grant_identity": ["client_id", "user_id", "club_id", "permission_code"],
                                "club_grant_has_surrogate_id_or_version": False,
                                "legacy_assignment_has_active_or_version": False,
                                "target_role_ids_are_report_only": True},
            "catalogue": catalogue, "product_gates": GATES, "scenarios": scenarios}


def markdown_report(report):
    lines = ["# Synthetic role shadow report — eight-role revision", "", "**ACCEPTED product decisions; PROPOSED technical permissions. Synthetic only; no migration.**", "",
             f"Source revision: `{report['source_revision']}`. Writes: **0**.",
             "Source revision is the base; explicit SHA-256 metadata binds the local unpublished overlay.",
             "Actual inactive implementation/DB evidence: [foundation report](role-assignment-foundation.md).",
             f"Snapshot before/after: `{report['snapshot_before_sha256']}`.",
             "CURRENT = exact raw authority evidence + bounded client tokens, not full API authorization.",
             "Product duties carry ACCEPTED/PROPOSED separately. Every technical permission binding is PROPOSED; action labels are not new production codes.",
             "Self actions always require the actor's own objects; club scopes never widen personal ownership.",
             "Legacy assignments remain opaque and preserved; no automatic role mapping.", ""]
    lines += ["Technical legacy roles: " + ", ".join(report["technical_model"]["legacy_roles"]) + ". Legacy scopes: GLOBAL/CLUB; CLIENT is invalid.", ""]
    lines += ["Product gates: " + "; ".join(report["product_gates"].values()) + ".", ""]
    def show(values):
        return ", ".join("`" + str(v) + "`" for v in values) or "∅"

    for scenario in report["scenarios"]:
        raw = scenario["CURRENT"]["raw"]
        lines += ["## " + scenario["id"], "", f"Synthetic user: `{scenario['user_id']}`. **CURRENT:**", ""]
        for row in raw["client_memberships"]:
            lines.append(f"- Membership client {row['client_id']}: active={row['is_active']}, version={row['version']}.")
        for row in raw["client_membership_grants"]:
            lines.append(f"- Membership token {row['client_id']}: `{row['permission_code']}`, active={row['is_active']}.")
        for row in raw["creators_projection"]:
            lines.append(f"- Existing immutable creator evidence: client/club `{row['client_id']}/{row['club_id']}`, owner user `{row['owner_user_id']}`; preserved, not transferred.")
        grant_scopes = sorted({(g["client_id"], g["club_id"]) for g in raw["client_club_grants"]})
        effective = scenario["CURRENT"]["active_client_tokens_with_read_prerequisite"]
        for client, club in grant_scopes:
            rows = [g for g in raw["client_club_grants"] if g["client_id"] == client and g["club_id"] == club]
            active = [g["permission_code"] for g in rows if g["is_active"]]
            inactive = [g["permission_code"] for g in rows if not g["is_active"]]
            tokens = [g["permission_code"] for g in effective if g["client_id"] == client and g["club_id"] == club]
            lines += [f"- client/club `{client}/{club}` stored active: {show(active)}; tombstones: {show(inactive)}; current tokens with read prerequisite: {show(tokens)}."]
        for row in raw["client_club_staff"]:
            lines.append(f"- Staff `{row['client_id']}/{row['club_id']}/{row['user_id']}`: {row['role_code']}, active={row['is_active']}, version={row['version']}, invitation={row['invitation_id']}, revoked-through={row['revoked_through_invitation_id']}.")
        for row in raw["user_roles"]:
            attached = [p["permission_code"] for p in raw["user_role_permissions"] if p["user_role_id"] == row["id"]]
            lines.append(f"- Legacy assignment `{row['id']}`: {row['role_code']}/{row['scope_type']}/{row['scope_club_id']}; attached permissions: {show(attached)}. No automatic client mapping.")
        for row in raw["invitations_projection"]:
            lines.append(f"- Invitation `{row['id']}`: {row['purpose']}/{row['staff_role']}/{row['status']}; expires_at={row['expires_at']}; preserved, not translated.")
        lines += [f"- Commands/audit projections preserved: {len(raw['commands_projection'])}/{len(raw['audit_projection'])}.", "", "**PROPOSED technical bindings (not applied; product statuses separate):**", ""]
        for p in scenario["PROPOSED"]:
            lines += [f"- {p['role']} scope `{p['scope']}`: token intent {show(p.get('existing_token_intent', []))}; action intent {show(p['action_intents'])}; blockers {show(p['blockers'])}; policy gates {show(p.get('policy_gates', []))}."]
            if p.get("accepted_product_actions"):
                lines.append("- ACCEPTED product actions (technical permission still PROPOSED): " + show(p["accepted_product_actions"]) + ".")
        if not scenario["PROPOSED"]:
            lines.append("- ∅ — no explicit valid target; role-name matching is disabled.")
        lines += ["", "**DIFFERENCE (hypothetical replacement only):**", ""]
        for d in scenario["DIFFERENCE"]:
            if "scope" in d:
                lines.append(f"- Scope `{d['scope']}`: +{show(d['would_add_existing_tokens'])}; −{show(d['would_remove_existing_tokens'])}; retained {show(d['retained_existing_tokens'])}; restoration attempts {show(d['restoration_attempts'])}.")
                lines.append(f"- Proposed action intents: {show(d['new_policy_action_intents'])}. Applied additions/removals: ∅/∅.")
            else:
                lines.append("- Scope-change candidate, MANUAL ONLY: `" + json.dumps(d["scope_change_candidate"], sort_keys=True) + "`; legacy authority retained.")
        lines += ["", "**RISK:** " + show(scenario["RISK"]) + "."]
        for p in scenario["writer_preview"]:
            lines.append(f"- Old writer preview `{p['scope']}` → {p['package']}: would disable {show(p['would_disable'])}; would create {show(p['would_create'])}; would reactivate {show(p['would_reactivate_tombstones'])}; hypothetical future actions lost {show(p['hypothetical_future_actions_lost'])}. Not executed; current schema does not admit those future action codes.")
        for p in scenario["shadow_access_probes"]:
            lines.append(f"- Future policy probe {p['kind']} `{p['scope']}` / `{p['capability']}`: **{p['PROPOSED']}**; reasons {show(p['deny_reasons'])}. CURRENT: no new runtime authority; applied grants/API calls: ∅/0.")
        if scenario["attribution_preview"]:
            lines += ["", "Attribution CURRENT / PROPOSED / DIFFERENCE / RISK (all correction/terms details PROPOSED):", "", "```json",
                      json.dumps(scenario["attribution_preview"], ensure_ascii=False, sort_keys=True, indent=2), "```"]
        lines.append("")
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--format", choices=("json", "markdown"), default="json")
    parser.add_argument("--lifecycle", choices=("preview", "cancel", "rollback"), default="preview")
    args = parser.parse_args()
    report = build_report(load_fixture(), lifecycle=args.lifecycle)
    print(markdown_report(report) if args.format == "markdown" else json.dumps(report, ensure_ascii=False, sort_keys=True, indent=2))


if __name__ == "__main__":
    main()
