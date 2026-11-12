#!/usr/bin/env python3
"""PROPOSED offline reference transactions on bundled artificial values only.

No production imports, DB, network, SQL or apply mode. CURRENT/TARGET refer to
hypothetical states of the proposed contract, not deployed authorization.
"""

import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
FIXTURE = ROOT / "scripts/tests/fixtures/role-assignment-contract.json"
CONTRACT = "docs/product/ROLE_ASSIGNMENT_CONTRACT_PROPOSED.md"
EVIDENCE_SOURCES = (CONTRACT, "docs/product/PRODUCT_SPEC.md", "docs/product/ROLE_WORKSPACES_PROPOSED.md",
    "core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubStaffAccess.kt",
    "core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubTeamOperations.kt",
    "core-data/src/main/kotlin/com/example/bot/data/client/onboarding/StaffInvitationOperations.kt",
    "core-data/src/main/kotlin/com/example/bot/data/client/onboarding/InvitationOperations.kt",
    "core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClientAccessOperations.kt",
    "core-data/src/main/kotlin/com/example/bot/data/client/onboarding/DirectClubOperations.kt",
    "core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubOnboardingOperations.kt",
    "core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubPreparationOperations.kt",
    "core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClientLifecycleOperations.kt",
    "core-data/src/main/kotlin/com/example/bot/data/client/onboarding/JdbcClientOnboardingRepository.kt",
    "core-data/src/main/kotlin/com/example/bot/data/client/onboarding/OnboardingSql.kt",
    "core-data/src/main/kotlin/com/example/bot/data/promoter/PromoterAdminRepositoryImpl.kt",
    "scripts/seed-demo.sql", "scripts/seed-demo.sh",
    "core-data/src/main/kotlin/com/example/bot/data/client/assignments/AssignmentAdmissionWriter.kt",
    "core-data/src/main/kotlin/com/example/bot/data/client/assignments/AssignmentAudit.kt",
    "core-data/src/main/kotlin/com/example/bot/data/client/assignments/AssignmentAuthority.kt",
    "core-data/src/main/kotlin/com/example/bot/data/client/assignments/AssignmentIntent.kt",
    "core-data/src/main/kotlin/com/example/bot/data/client/assignments/AssignmentLifecycle.kt",
    "core-data/src/main/kotlin/com/example/bot/data/client/assignments/AssignmentModel.kt",
    "core-data/src/main/kotlin/com/example/bot/data/client/assignments/AssignmentPolicies.kt",
    "core-data/src/main/kotlin/com/example/bot/data/client/assignments/AssignmentProviders.kt",
    "core-data/src/main/kotlin/com/example/bot/data/client/assignments/AssignmentSnapshot.kt",
    "core-data/src/main/kotlin/com/example/bot/data/client/assignments/AssignmentSourceWriter.kt",
    "core-data/src/main/kotlin/com/example/bot/data/client/assignments/JdbcRoleAssignmentFoundation.kt",
    "core-data/src/main/kotlin/com/example/bot/data/client/onboarding/LegacyClientAuthorityWrites.kt",
    "core-data/src/main/kotlin/com/example/bot/data/client/assignments/PlatformMembershipSourceRevocation.kt",
    "core-data/src/main/kotlin/com/example/bot/data/client/assignments/PlatformMembershipSourceInvalidator.kt",
    "core-data/src/main/kotlin/com/example/bot/data/client/assignments/PlatformMembershipSourceAudit.kt",
    "core-data/src/main/resources/db/migration/h2/V077__client_role_assignment_foundation.sql",
    "core-data/src/main/resources/db/migration/postgresql/V077__client_role_assignment_foundation.sql",
)
STAFF_ROLES = {"NETWORK_MANAGER", "CLUB_MANAGER", "FLOOR_MANAGER", "ENTRANCE_MANAGER", "FINANCE_MANAGER", "PROMOTER"}
LOWER_ROLES = {"FLOOR_MANAGER", "ENTRANCE_MANAGER", "FINANCE_MANAGER", "PROMOTER"}
ROLE_SUFFIX = {"NETWORK_MANAGER": "network_manager", "CLUB_MANAGER": "club_manager", "FLOOR_MANAGER": "manager", "ENTRANCE_MANAGER": "entry_manager", "FINANCE_MANAGER": "finance_manager", "PROMOTER": "promoter"}
CONFIG_PAIRS = {"client.club.calendar.write": "client.club.calendar.read", "client.club.profile.write": "client.club.profile.read"}


class Rejected(Exception):
    pass


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def clone(value):
    return json.loads(json.dumps(value))


def key(client, club, user):
    return f"{client}/{club}/{user}"


def member_key(client, user):
    return f"{client}/{user}"


def common(state, client, club, user):
    return state["clients"].get(str(client), False) is True and state["clubs"].get(str(club)) == client and state["memberships"].get(member_key(client, user), {}).get("active") is True


def policy(state, source):
    value = state["policies"].get(source["policy"])
    return value if value and value["role"] == source["role"] and digest(value) == source["policy_hash"] else None


def role_live(state, source):
    c, club, user = source["client"], source["club"], source["user"]
    fence = state["fences"].get(key(c, club, user), {})
    return source["status"] == "ACTIVE" and source["role"] in STAFF_ROLES and common(state, c, club, user) and fence.get("mode") == "TARGET" and fence.get("active") is True and source["epoch"] == fence.get("epoch") and policy(state, source) is not None


def selector_allows(source, resource):
    selector = source["selector"]
    if selector.get("kind") in ("CLUB", "ALL_SHIFTS_OF_THIS_CLUB"):
        return True
    ids = selector.get("ids", [])
    return selector.get("kind") == "SHIFT_SET" and bool(ids) and all(type(i) is int and i > 0 for i in ids) and resource is not None and resource.get("shift_id") in ids


def delegation_live(state, delegation):
    issuer = state["assignments"].get(delegation["issuer_id"])
    recipient = state["assignments"].get(delegation["recipient_id"])
    if not issuer or not recipient or not role_live(state, issuer) or not role_live(state, recipient):
        return False
    permission = delegation["permission"]
    read = CONFIG_PAIRS.get(permission)
    needed = {permission, read, "client.staff.delegate." + permission.split(".")[-2]}
    return delegation["status"] == "ACTIVE" and read is not None and delegation["policy_hash"] == digest(CONFIG_PAIRS) and issuer["role"] == "CLUB_MANAGER" and recipient["role"] == "FLOOR_MANAGER" and issuer["client"] == recipient["client"] == delegation["client"] and issuer["club"] == recipient["club"] == delegation["club"] and recipient["user"] == delegation["user"] and delegation["issuer_epoch"] == issuer["epoch"] and delegation["epoch"] == recipient["epoch"] and needed <= set(policy(state, issuer)["permissions"]) and (delegation["expires_at"] is None or state["now"] < delegation["expires_at"])


def contributions(state, client, club, user, resource=None):
    """Explain each legal source; never collapse provenance into a mutable array."""
    result = {}
    fence = state["fences"].get(key(client, club, user), {})
    if not common(state, client, club, user) or fence.get("active") is not True:
        return result
    for id, source in state["assignments"].items():
        if (source["client"], source["club"], source["user"]) == (client, club, user) and role_live(state, source) and selector_allows(source, resource):
            for p in policy(state, source)["permissions"]:
                result.setdefault(p, []).append("ROLE:" + id)
    for id, source in state["delegations"].items():
        if (source["client"], source["club"], source["user"]) == (client, club, user) and delegation_live(state, source):
            for p in (source["permission"], CONFIG_PAIRS[source["permission"]]):
                result.setdefault(p, []).append("DELEGATION:" + id)
    for id, source in state["compat"].items():
        if (source["client"], source["club"], source["user"]) == (client, club, user) and source["active"] and source["classification"] == "KEEP_OPAQUE_COMPAT" and source["epoch"] == fence["epoch"]:
            supporting = any(s["client"] == client and s["club"] == club and s["user"] == user and s["permission"] == "client.club.read" and s["active"] and s["classification"] == "KEEP_OPAQUE_COMPAT" and s["epoch"] == fence["epoch"] for s in state["compat"].values())
            if not source["permission"].startswith("client.club.") or source["permission"] == "client.club.read" or supporting:
                result.setdefault(source["permission"], []).append("COMPAT:" + id)
    return {p: sorted(ids) for p, ids in sorted(result.items())}


def actor_roles(state, actor, client, club):
    return [(s["role"], set(policy(state, s)["permissions"])) for s in state["assignments"].values() if s["user"] == actor and s["client"] == client and s["club"] == club and role_live(state, s)]


def owner(state, actor, client, club):
    return common(state, client, club, actor) and any(o["user"] == actor and o["client"] == client and club in o["clubs"] and o["verified"] is True for o in state["ownership"])


def may_manage(state, actor, client, club, user, role, verb):
    if actor == user or role not in STAFF_ROLES:
        return False
    if any(o["user"] == user and o["client"] == client and club in o["clubs"] and o["verified"] is True for o in state["ownership"]):
        return False
    if owner(state, actor, client, club):
        return role in {"NETWORK_MANAGER", "CLUB_MANAGER"} or verb == "disable"
    for issuer_role, held in actor_roles(state, actor, client, club):
        if issuer_role == "NETWORK_MANAGER" and role == "CLUB_MANAGER" and "client.staff." + verb + ".club_manager" in held:
            return True
        if issuer_role == "CLUB_MANAGER" and role in LOWER_ROLES:
            code = "client.staff.assign." + ROLE_SUFFIX[role] if verb == "assign" else "client.staff.revoke.lower" if verb == "revoke" else "client.staff.disable.club"
            if code in held:
                return True
    return False


def tombstones(state, client, club, user):
    result = set()
    for s in state["assignments"].values():
        if (s["client"], s["club"], s["user"]) == (client, club, user) and s["status"] == "REVOKED":
            p = policy(state, s)
            if p: result.update(p["permissions"])
    for s in state["delegations"].values():
        if (s["client"], s["club"], s["user"]) == (client, club, user) and s["status"] == "REVOKED":
            result.update((s["permission"], CONFIG_PAIRS.get(s["permission"])))
    result.update(s["permission"] for s in state["compat"].values() if (s["client"], s["club"], s["user"]) == (client, club, user) and not s["active"])
    return result


def validate_command(state, op):
    if op.get("signed_identity") is not True:
        raise Rejected("SIGNED_IDENTITY_REQUIRED")
    c, club, user, actor = op["client"], op["club"], op["user"], op["actor"]
    first_admission = op["kind"] == "ADMIT" and member_key(c, user) not in state["memberships"]
    if state["clubs"].get(str(club)) != c or (member_key(c, user) not in state["memberships"] and not first_admission):
        raise Rejected("EXACT_CLIENT_CLUB_MEMBERSHIP_REQUIRED")
    if any(s["active"] and s["classification"] != "KEEP_OPAQUE_COMPAT" for s in state["compat"].values() if (s["client"], s["club"], s["user"]) == (c, club, user)):
        raise Rejected("AMBIGUOUS_COMPAT_SOURCE_BLOCKS_TARGET")
    platform = actor == state["platform_owner"]
    if op["kind"] in ("PLATFORM_REVOKE", "MEMBERSHIP_RESTORE"):
        if not platform: raise Rejected("PLATFORM_AUTHORITY_REQUIRED")
    elif not common(state, c, club, actor):
        raise Rejected("CURRENT_ACTOR_AUTHORITY_DENIED")
    if op["kind"] not in ("PLATFORM_REVOKE", "MEMBERSHIP_RESTORE") and not first_admission and not common(state, c, club, user):
        raise Rejected("TARGET_MEMBERSHIP_OR_MAPPING_DENIED")
    if op["kind"] == "LEGACY_WRITE":
        raise Rejected("LEGACY_WRITER_BLOCKED_IN_TARGET")
    if op["kind"] in ("GRANT", "ADMIT", "REPLACE"):
        p = state["policies"].get(op["policy"])
        if not p or not may_manage(state, actor, c, club, user, p["role"], "assign"):
            raise Rejected("CURRENT_MANAGEMENT_AUTHORITY_DENIED")
    if op["kind"] == "REVOKE_ROLE":
        source = source_for(state, op, "assignments")
        if not may_manage(state, actor, c, club, user, source["role"], "revoke"):
            raise Rejected("CURRENT_MANAGEMENT_AUTHORITY_DENIED")
    if op["kind"] in ("DELEGATE", "REVOKE_DELEGATION"):
        issuer_id = op.get("issuer_id") if op["kind"] == "DELEGATE" else source_for(state, op, "delegations")["issuer_id"]
        issuer = state["assignments"].get(issuer_id)
        if not issuer or issuer["user"] != actor or not role_live(state, issuer):
            raise Rejected("CURRENT_DELEGATION_AUTHORITY_DENIED")
    if op["kind"] == "DISABLE" and not owner(state, actor, c, club):
        if not any(role == "CLUB_MANAGER" and "client.staff.disable.club" in held for role, held in actor_roles(state, actor, c, club)):
            raise Rejected("CURRENT_DISABLE_AUTHORITY_DENIED")


def source_for(state, op, table):
    source = state[table].get(op["source_id"])
    if not source or (source["client"], source["club"], source["user"]) != (op["client"], op["club"], op["user"]):
        raise Rejected("EXACT_SOURCE_OWNERSHIP_REQUIRED")
    return source


def new_assignment(state, op, fence):
    value = state["policies"].get(op["policy"])
    if not value or value["role"] not in STAFF_ROLES or not may_manage(state, op["actor"], op["client"], op["club"], op["user"], value["role"], "assign"):
        raise Rejected("HIERARCHY_OR_COMPLETE_PACKAGE_DENIED")
    if op["new_id"] in state["assignments"]:
        raise Rejected("SOURCE_ID_TERMINAL_OR_EXISTS")
    selector = op.get("selector", {"kind": "CLUB"})
    if selector["kind"] not in ("CLUB", "ALL_SHIFTS_OF_THIS_CLUB", "SHIFT_SET") or (selector["kind"] != "CLUB" and value["role"] != "FINANCE_MANAGER") or (selector["kind"] == "SHIFT_SET" and (not selector.get("ids") or any(type(i) is not int or i <= 0 for i in selector["ids"]) or len(selector["ids"]) != len(set(selector["ids"])))):
        raise Rejected("INVALID_SELECTOR")
    requested = set(value["permissions"])
    needed = requested & (tombstones(state, op["client"], op["club"], op["user"]) - set(contributions(state, op["client"], op["club"], op["user"])))
    if not needed <= set(op.get("regrant_ack", [])):
        raise Rejected("EXPLICIT_REGRANT_ACK_REQUIRED")
    state["assignments"][op["new_id"]] = {"client": op["client"], "club": op["club"], "user": op["user"], "role": value["role"], "policy": op["policy"], "policy_hash": digest(value), "selector": selector, "status": "ACTIVE", "version": 1, "epoch": fence["epoch"]}


def invalidate_dependents(state, source_ids, main_scope):
    recipients = set()
    for d in state["delegations"].values():
        if d["status"] == "ACTIVE" and (d["recipient_id"] in source_ids or d["issuer_id"] in source_ids):
            d.update(status="REVOKED", version=d["version"] + 1)
            recipients.add(key(d["client"], d["club"], d["user"]))
    for scope in sorted(recipients):
        if scope != main_scope: state["fences"][scope]["version"] += 1


def disable_sources(state, client, club, user):
    fence = state["fences"][key(client, club, user)]
    fence.update(active=False, epoch=fence["epoch"] + 1,
                 revoked_through_invitation_id=max(fence.get("revoked_through_invitation_id", 0), state["invitation_high_water"]))
    affected = set()
    for id, s in state["assignments"].items():
        if (s["client"], s["club"], s["user"]) == (client, club, user):
            affected.add(id)
            if s["status"] == "ACTIVE": s.update(status="REVOKED", version=s["version"] + 1)
    invalidate_dependents(state, affected, key(client, club, user))
    for s in state["compat"].values():
        if (s["client"], s["club"], s["user"]) == (client, club, user): s["active"] = False


def preview_command(current, op):
    """Return a simulated value; callers never receive an external mutation API."""
    original = clone(current)
    tentative = clone(current)
    try:
        validate_command(tentative, op)
        intent = {k: v for k, v in op.items() if k not in ("audit_failure", "receipt_failure")}
        receipt_key = str(op["actor"]) + ":" + digest(op["command_ref"])
        receipt = tentative["receipts"].get(receipt_key)
        if receipt:
            if receipt["intent_hash"] != digest(intent): raise Rejected("IDEMPOTENCY_CONFLICT")
            return original, "CURRENT_STATE_REPLAY_NO_EXECUTION"
        if op["kind"] in ("CANCEL", "ROLLBACK_PREVIEW"):
            return original, "NO_EXECUTION_NO_RESURRECTION"
        c, club, user = op["client"], op["club"], op["user"]
        fence = tentative["fences"].get(key(c, club, user))
        if not fence or (fence["epoch"], fence["version"]) != (op["expected_epoch"], op["expected_version"]):
            raise Rejected("STALE_FENCE_CONFLICT")
        if op["kind"] not in ("ADMIT", "MEMBERSHIP_RESTORE", "PLATFORM_REVOKE") and not fence["active"]:
            raise Rejected("FULL_DISABLE_FENCE_DENIED")
        if op["kind"] == "GRANT":
            new_assignment(tentative, op, fence)
        elif op["kind"] == "REPLACE":
            source = source_for(tentative, op, "assignments")
            if source["status"] != "ACTIVE" or source["version"] != op["source_version"] or not may_manage(tentative, op["actor"], c, club, user, source["role"], "revoke"):
                raise Rejected("REVOKED_OR_STALE_REPLACE_DENIED")
            source.update(status="REVOKED", version=source["version"] + 1)
            invalidate_dependents(tentative, {op["source_id"]}, key(c, club, user))
            new_assignment(tentative, op, fence)
        elif op["kind"] == "REVOKE_ROLE":
            source = source_for(tentative, op, "assignments")
            if source["status"] != "ACTIVE" or source["version"] != op["source_version"] or not may_manage(tentative, op["actor"], c, club, user, source["role"], "revoke"):
                raise Rejected("SOURCE_REVOKE_DENIED")
            source.update(status="REVOKED", version=source["version"] + 1)
            invalidate_dependents(tentative, {op["source_id"]}, key(c, club, user))
        elif op["kind"] == "DELEGATE":
            issuer = tentative["assignments"].get(op["issuer_id"])
            recipient = tentative["assignments"].get(op["recipient_id"])
            if not issuer or not recipient or issuer["user"] != op["actor"] or op["actor"] == user or (recipient["client"], recipient["club"], recipient["user"]) != (c, club, user) or op["new_id"] in tentative["delegations"]:
                raise Rejected("EXACT_DELEGATION_OWNERSHIP_REQUIRED")
            if (issuer["version"], recipient["version"]) != (op["issuer_version"], op["recipient_version"]):
                raise Rejected("STALE_DELEGATION_SOURCE_VERSION")
            delegation = {"client": c, "club": club, "user": user, "issuer_id": op["issuer_id"], "recipient_id": op["recipient_id"], "permission": op["permission"], "policy_hash": digest(CONFIG_PAIRS), "status": "ACTIVE", "version": 1, "epoch": fence["epoch"], "issuer_epoch": issuer["epoch"], "expires_at": op.get("expires_at")}
            if not delegation_live(tentative, delegation): raise Rejected("DELEGATION_AUTHORITY_DENIED")
            needed = {op["permission"], CONFIG_PAIRS[op["permission"]]} & (tombstones(tentative, c, club, user) - set(contributions(tentative, c, club, user)))
            if not needed <= set(op.get("regrant_ack", [])): raise Rejected("EXPLICIT_REGRANT_ACK_REQUIRED")
            tentative["delegations"][op["new_id"]] = delegation
        elif op["kind"] == "REVOKE_DELEGATION":
            source = source_for(tentative, op, "delegations")
            issuer = tentative["assignments"].get(source["issuer_id"])
            if source["status"] != "ACTIVE" or source["version"] != op["source_version"] or not issuer or not role_live(tentative, issuer) or issuer["user"] != op["actor"] or op["actor"] == user:
                raise Rejected("DELEGATION_REVOKE_DENIED")
            source.update(status="REVOKED", version=source["version"] + 1)
        elif op["kind"] == "DISABLE":
            active = [s for s in tentative["assignments"].values() if (s["client"], s["club"], s["user"]) == (c, club, user) and s["status"] == "ACTIVE"]
            opaque = [s for s in tentative["compat"].values() if (s["client"], s["club"], s["user"]) == (c, club, user) and s["active"]]
            protected = op["actor"] == user or any(o["user"] == user and o["client"] == c and club in o["clubs"] and o["verified"] for o in tentative["ownership"])
            owner_disable = owner(tentative, op["actor"], c, club) and not protected
            lower_only = bool(active) and not opaque and not protected and all(may_manage(tentative, op["actor"], c, club, user, s["role"], "disable") for s in active)
            if not owner_disable and not lower_only: raise Rejected("FULL_DISABLE_HIERARCHY_DENIED")
            disable_sources(tentative, c, club, user)
        elif op["kind"] == "ADMIT":
            if fence["active"] or op.get("invitation_protocol") != "TARGET_V1" or op.get("invitation_epoch") != fence["epoch"] or op.get("invitation_id", 0) <= fence.get("revoked_through_invitation_id", 0) or op.get("confirmed") is not True or op.get("claimed_user") != user:
                raise Rejected("NEW_TARGET_ADMISSION_REQUIRED")
            if member_key(c, user) not in tentative["memberships"]:
                tentative["memberships"][member_key(c, user)] = {"active": True, "version": 1}
            fence.update(active=True, epoch=fence["epoch"] + 1)
            new_assignment(tentative, op, fence)
        elif op["kind"] == "PLATFORM_REVOKE":
            tentative["memberships"][member_key(c, user)]["active"] = False
            tentative["memberships"][member_key(c, user)]["version"] += 1
            for scope in list(tentative["fences"]):
                sc, cl, u = map(int, scope.split("/"))
                if (sc, u) == (c, user):
                    disable_sources(tentative, sc, cl, user)
                    if cl != club: tentative["fences"][scope]["version"] += 1
        elif op["kind"] == "MEMBERSHIP_RESTORE":
            tentative["memberships"][member_key(c, user)]["active"] = True
            tentative["memberships"][member_key(c, user)]["version"] += 1
        else:
            raise Rejected("UNSUPPORTED_OPERATION")
        fence["version"] += 1
        if op.get("audit_failure"): raise Rejected("AUDIT_FAILURE_ROLLBACK")
        tentative["audits"].append({"kind": op["kind"], "client": c, "club": club, "user": user})
        if op.get("receipt_failure"): raise Rejected("RECEIPT_FAILURE_ROLLBACK")
        tentative["receipts"][receipt_key] = {"intent_hash": digest(intent)}
        return tentative, "COMMITTED_IN_MODEL_ONLY"
    except Rejected as error:
        return original, str(error)


def report_case(base, case):
    base_hash = digest(base)
    current = clone(base)
    for table, records in case.get("overrides", {}).items(): current[table].update(clone(records))
    before = clone(current)
    statuses = []
    for op in case["operations"]:
        current, status = preview_command(current, op)
        statuses.append(status)
    scopes = case.get("observe", ["101/1001/7"])
    explain = lambda state: {scope: contributions(state, *map(int, scope.split("/"))) for scope in scopes}
    old, target = explain(before), explain(current)
    success = {"COMMITTED_IN_MODEL_ONLY", "CURRENT_STATE_REPLAY_NO_EXECUTION", "NO_EXECUTION_NO_RESURRECTION"}
    return {"id": case["id"], "CURRENT": old, "TARGET": target,
            "authority_revisions": {scope: {"CURRENT": before["fences"].get(scope), "TARGET": current["fences"].get(scope)} for scope in scopes},
            "DIFFERENCE": {scope: {"added": sorted(set(target[scope]) - set(old[scope])), "removed": sorted(set(old[scope]) - set(target[scope])),
                                   "source_changes": {p: {"before": old[scope].get(p, []), "after": target[scope].get(p, [])} for p in sorted(set(old[scope]) | set(target[scope])) if old[scope].get(p, []) != target[scope].get(p, [])}} for scope in scopes},
            "outcomes": statuses, "RISK": case.get("risks", []) + [s for s in statuses if s not in success], "target_state": current, "external_writes": 0,
            "input_unchanged": digest(base) == base_hash, "semantics": "PROPOSED_REFERENCE_NOT_PRODUCTION"}


def load_fixture():
    value = json.loads(FIXTURE.read_text())
    if value.get("synthetic_only") is not True or value.get("schema") != "CLB-role-assignment-reference-v1": raise ValueError("synthetic marker required")
    if set(value["source_sha256"]) != set(EVIDENCE_SOURCES): raise ValueError("source fingerprint set required")
    for name, expected in value["source_sha256"].items():
        if not isinstance(expected, dict) or set(expected) != {"algorithm", "digest"} or expected["algorithm"] != "SHA-256":
            raise ValueError("explicit public checksum required: " + name)
        if hashlib.sha256((ROOT / name).read_bytes()).hexdigest() != expected["digest"]: raise ValueError("source drift: " + name)
    return value


def markdown(report):
    lines = ["# Role assignment contract — synthetic reference", "", "**Reference policies remain PROPOSED; architecture is ACCEPTED for inactive local foundation. CURRENT/TARGET are hypothetical states; zero DB/API/external writes.**", "",
             "Source revision is the base; checksums bind the local unpublished overlay. Previous baselines are preserved.",
             "Interleavings model serial outcomes and CAS conflicts; they are not PostgreSQL lock/concurrency tests.",
             "Actual disposable DB evidence and implementation limits: [foundation report](role-assignment-foundation.md).", ""]
    for case in report:
        visible = {k: v for k, v in case.items() if k != "target_state"}
        lines += ["## " + case["id"], "", "```json", json.dumps(visible, sort_keys=True, indent=2), "```", ""]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--format", choices=("json", "markdown"), default="json")
    args = parser.parse_args()
    fixture = load_fixture()
    report = [report_case(fixture["state"], c) for c in fixture["cases"]]
    print(markdown(report) if args.format == "markdown" else json.dumps(report, sort_keys=True, indent=2))


if __name__ == "__main__":
    main()
