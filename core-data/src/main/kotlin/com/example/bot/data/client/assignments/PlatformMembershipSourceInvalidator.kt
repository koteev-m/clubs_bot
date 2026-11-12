package com.example.bot.data.client.assignments

import com.example.bot.data.client.onboarding.ClubStaffAccess
import com.example.bot.data.client.onboarding.DbRow
import com.example.bot.data.client.onboarding.OnboardingSession
import com.example.bot.data.client.onboarding.fail
import com.example.bot.data.client.onboarding.long

/** No policy/ownership provider is consulted for revocation; platform authority is checked by its caller. */
internal class PlatformMembershipSourceInvalidator(
    private val session: OnboardingSession,
    private val clientId: Long,
    private val userId: Long,
    private val restoring: Boolean,
) {
    private val sql get() = session.sql

    fun apply(): Set<Long> {
        session.owner()
        if (sql.connection.autoCommit) fail("invalid_transition")
        session.client(clientId, lock = true)
        if (!hasState()) return emptySet()
        lockSources()
        val snapshot = AssignmentSnapshot(sql, clientId, AssignmentPolicyCatalog(), session.now)
        val admissions = snapshot.admissions.filter { it.long("user_id") == userId }
        val allRoles = snapshot.roles.filter { it.long("user_id") == userId }
        val allDelegations = snapshot.delegations.filter { it.involves(userId) }
        val controlled = (admissions + allRoles + allDelegations).map { it.long("club_id") }.toSet()
        val roles = allRoles.filter { it["status"] == "ACTIVE" }
        val delegations = allDelegations.filter { it["status"] == "ACTIVE" }
        val activeScopes = (roles + delegations.filter { it.long("user_id") == userId }).map { it.scope() }.toSet()
        val fences = admissions.filter { !restoring || it["status"] != "DISABLED" || it.scope() in activeScopes }
        if (roles.isNotEmpty() || delegations.isNotEmpty() || fences.isNotEmpty()) {
            revoke(snapshot, roles, delegations, fences)
        }
        return controlled
    }

    private fun revoke(
        snapshot: AssignmentSnapshot,
        roles: List<DbRow>,
        delegations: List<DbRow>,
        fences: List<DbRow>,
    ) {
        val actorId = session.userId ?: fail("forbidden")
        val action = if (restoring) "client.staff.platform_restore_fence" else "client.staff.platform_membership_revoke"
        val audit = PlatformMembershipSourceAudit(session, clientId, userId, action)
        val auditId = audit.begin(snapshot)
        val receipt = AssignmentReceipt(actorId, session.actor.connectionKey, session.correlation)
        val writer = AssignmentSourceWriter(snapshot, receipt, auditId, action)
        val affected = writer.revoke(roles, delegations, action)
        // Independent recipient roles/memberships stay intact; only lost delegation dependencies bump revision.
        writer.bump(affected - fences.map { it.scope() }.toSet())
        fences.forEach { disableFence(it, action) }
        audit.complete(auditId, snapshot)
    }

    private fun disableFence(
        fence: DbRow,
        reason: String,
    ) {
        val clubId = fence.long("club_id")
        val watermark =
            maxOf(fence.long("revoked_through_invitation_id"), ClubStaffAccess(session).invitationFence(clubId))
        if (sql.update(
                "UPDATE client_club_access_state SET status='DISABLED',admission_epoch=admission_epoch+1," +
                    "version=version+1,revoked_through_invitation_id=?,compatibility_manifest=NULL," +
                    "compatibility_hash=NULL,updated_at=?,revoked_at=?,revoked_by=?,revoke_reason=? " +
                    "WHERE client_id=? AND club_id=? AND user_id=? AND version=? AND admission_epoch=? AND status=?",
                watermark,
                session.now,
                session.now,
                session.userId,
                reason,
                clientId,
                clubId,
                userId,
                fence.long("version"),
                fence.long("admission_epoch"),
                fence["status"],
            ) != 1
        ) {
            fail("version_conflict")
        }
    }

    private fun hasState(): Boolean =
        sql.row(
            "SELECT 1 AS present WHERE " +
                "EXISTS (SELECT 1 FROM client_club_access_state WHERE client_id=? AND user_id=?) OR " +
                "EXISTS (SELECT 1 FROM client_role_assignments WHERE client_id=? AND user_id=?) OR " +
                "EXISTS (SELECT 1 FROM client_permission_delegations WHERE client_id=? " +
                "AND (user_id=? OR issuer_user_id=?))",
            clientId,
            userId,
            clientId,
            userId,
            clientId,
            userId,
            userId,
        ) != null

    private fun lockSources() {
        // Same actor -> client -> sorted fence/source order as the inactive assignment writer.
        sql.rows(
            "SELECT user_id FROM client_club_access_state WHERE client_id=? ORDER BY club_id,user_id FOR UPDATE",
            clientId,
        )
        sql.rows("SELECT id FROM client_role_assignments WHERE client_id=? ORDER BY id FOR UPDATE", clientId)
        sql.rows("SELECT id FROM client_permission_delegations WHERE client_id=? ORDER BY id FOR UPDATE", clientId)
    }
}

private fun DbRow.involves(userId: Long): Boolean = long("user_id") == userId || long("issuer_user_id") == userId
