package com.example.bot.data.client.assignments

import com.example.bot.data.client.onboarding.DbRow
import com.example.bot.data.client.onboarding.fail
import com.example.bot.data.client.onboarding.long

internal class AssignmentAuthority(
    private val snapshot: AssignmentSnapshot,
    val actorId: Long,
    private val ownership: AssignmentOwnershipProvider,
    private val platformOwnerId: Long?,
) {
    fun protect(scope: StaffScope) {
        val subject =
            snapshot.sql.row("SELECT telegram_user_id FROM users WHERE id=?", scope.userId) ?: fail("not_found")
        if (scope.userId == actorId ||
            subject.long("telegram_user_id") == platformOwnerId ||
            ownership.protectedSubject(snapshot.sql.connection, scope) != false
        ) {
            fail("forbidden")
        }
    }

    fun manage(
        scope: StaffScope,
        target: StaffRole,
        operation: String,
    ): String {
        val owner = owner(scope)
        if (owner != null && target in setOf(StaffRole.NETWORK_MANAGER, StaffRole.CLUB_MANAGER)) return "OWNER:$owner"
        val source =
            actorRoles(scope).firstOrNull { row ->
                val role = StaffRole.valueOf(checkNotNull(row["role_code"]))
                val hierarchy =
                    role == StaffRole.NETWORK_MANAGER &&
                        target == StaffRole.CLUB_MANAGER ||
                        role == StaffRole.CLUB_MANAGER &&
                        target in LOWER_STAFF_ROLES
                hierarchy &&
                    snapshot
                        .liveRole(
                            row,
                        )?.permissions
                        ?.contains("client.staff.$operation.${target.managementSuffix()}") ==
                    true
            } ?: fail("forbidden")
        return "ASSIGNMENT:${source["id"]}:${source["version"]}:${source["admission_epoch"]}"
    }

    fun delegate(
        scope: StaffScope,
        issuer: DbRow,
        policy: DelegationPolicy,
    ) {
        val exactIssuer =
            issuer.long("user_id") == actorId &&
                issuer.long("club_id") == scope.clubId &&
                issuer.long("client_id") == scope.clientId
        val manager = issuer["role_code"] == StaffRole.CLUB_MANAGER.name && issuer.selector().kind == SelectorKind.CLUB
        val execution =
            snapshot.liveRole(issuer)?.permissions?.containsAll(policy.permissions + policy.authority) == true
        if (!exactIssuer || !manager || !execution) {
            fail("forbidden")
        }
    }

    fun revokeDelegation(
        scope: StaffScope,
        delegation: DbRow,
        policy: DelegationPolicy,
    ): String {
        val owner = owner(scope)
        if (owner != null &&
            ownership.canRevokeDelegation(snapshot.sql.connection, actorId, scope)
        ) {
            return "OWNER:$owner"
        }
        val issuer = snapshot.roles.singleOrNull { it["id"] == delegation["issuer_assignment_id"] } ?: fail("forbidden")
        delegate(scope, issuer, policy)
        return "ASSIGNMENT:${issuer["id"]}:${issuer["version"]}:${issuer["admission_epoch"]}"
    }

    fun disable(
        scope: StaffScope,
        replay: Boolean = false,
    ): String {
        val owner = owner(scope)
        if (owner != null && ownership.canDisable(snapshot.sql.connection, actorId, scope)) return "OWNER:$owner"
        val issuer =
            actorRoles(scope).firstOrNull {
                it["role_code"] == StaffRole.CLUB_MANAGER.name &&
                    snapshot.liveRole(it)?.permissions?.contains("client.staff.disable.club") == true
            } ?: fail("forbidden")
        val targetRoles = snapshot.roles.filter { it.scope() == scope && it["status"] == "ACTIVE" }
        if (targetRoles.isEmpty() &&
            !replay ||
            targetRoles.any { StaffRole.valueOf(checkNotNull(it["role_code"])) !in LOWER_STAFF_ROLES }
        ) {
            fail("forbidden")
        }
        return "ASSIGNMENT:${issuer["id"]}:${issuer["version"]}:${issuer["admission_epoch"]}"
    }

    private fun owner(scope: StaffScope): String? =
        ownership.ownerReference(snapshot.sql.connection, actorId, scope.clientId, scope.clubId)?.also {
            if (it.isBlank() || it.length > 1000) fail("forbidden")
        }

    private fun actorRoles(scope: StaffScope): List<DbRow> =
        snapshot.roles.filter {
            it.long("user_id") == actorId &&
                it.long("club_id") == scope.clubId &&
                it.selector().kind == SelectorKind.CLUB &&
                snapshot.liveRole(it) != null
        }

    companion object {
        val LOWER_STAFF_ROLES =
            setOf(StaffRole.FLOOR_MANAGER, StaffRole.ENTRANCE_MANAGER, StaffRole.FINANCE_MANAGER, StaffRole.PROMOTER)
    }
}
