package com.example.bot.data.client.assignments

import com.example.bot.data.client.onboarding.DbRow
import com.example.bot.data.client.onboarding.fail
import com.example.bot.data.client.onboarding.long

internal class AssignmentAdmissionWriter(
    private val snapshot: AssignmentSnapshot,
    private val actorId: Long,
    private val sources: AssignmentSourceWriter,
) {
    fun admit(command: AssignmentCommand.Admit) {
        val scope = command.scope
        val member = snapshot.memberships[scope.userId]
        val membershipVersion = member?.long("version") ?: 1L
        if (member == null) {
            snapshot.sql.update(
                "INSERT INTO client_memberships(client_id,user_id,is_active,version,created_at,updated_at) " +
                    "VALUES (?,?,TRUE,1,?,?)",
                scope.clientId,
                scope.userId,
                snapshot.now,
                snapshot.now,
            )
        }
        val fence = snapshot.admission(scope)
        if (fence == null) {
            snapshot.sql.update(
                "INSERT INTO client_club_access_state(client_id,club_id,user_id,status,mode,admission_epoch,version," +
                    "membership_version,created_at,updated_at) VALUES (?,?,?,'ACTIVE','SHADOW',1,1,?,?,?)",
                scope.clientId,
                scope.clubId,
                scope.userId,
                membershipVersion,
                snapshot.now,
                snapshot.now,
            )
        } else {
            if (snapshot.sql.update(
                    "UPDATE client_club_access_state SET status='ACTIVE'," +
                        "admission_epoch=admission_epoch+1,version=version+1," +
                        "membership_version=?,compatibility_manifest=NULL,compatibility_hash=NULL,updated_at=?," +
                        "revoked_at=NULL,revoked_by=NULL,revoke_reason=NULL " +
                        "WHERE client_id=? AND club_id=? AND user_id=? " +
                        "AND version=? AND admission_epoch=? AND status IN ('PENDING','DISABLED') AND mode='SHADOW'",
                    membershipVersion,
                    snapshot.now,
                    scope.clientId,
                    scope.clubId,
                    scope.userId,
                    fence.long("version"),
                    fence.long("admission_epoch"),
                ) != 1
            ) {
                fail("version_conflict")
            }
        }
    }

    fun disable(
        command: AssignmentCommand.Disable,
        targetInvitationWatermark: Long,
    ) {
        val scope = command.scope
        val roles = snapshot.roles.filter { it.scope() == scope && it["status"] == "ACTIVE" }
        val delegations = snapshot.delegations.filter { it.scope() == scope && it["status"] == "ACTIVE" }
        val affected = sources.revoke(roles, delegations, command.reason)
        sources.bump(affected - scope)
        val fence = snapshot.admission(scope) ?: fail("invalid_transition")
        val issued =
            snapshot.sql
                .row(
                    "SELECT MAX(id) AS watermark FROM client_invitations WHERE client_id=? AND club_id=?",
                    scope.clientId,
                    scope.clubId,
                )?.get("watermark")
                ?.toLong() ?: 0L
        val watermark = maxOf(issued, fence.long("revoked_through_invitation_id"), targetInvitationWatermark)
        disableFence(scope, fence, watermark, command.reason)
    }

    private fun disableFence(
        scope: StaffScope,
        fence: DbRow,
        watermark: Long,
        reason: String,
    ) {
        if (snapshot.sql.update(
                "UPDATE client_club_access_state SET status='DISABLED'," +
                    "admission_epoch=admission_epoch+1,version=version+1," +
                    "revoked_through_invitation_id=?,compatibility_manifest=NULL,compatibility_hash=NULL," +
                    "updated_at=?,revoked_at=?,revoked_by=?,revoke_reason=? " +
                    "WHERE client_id=? AND club_id=? AND user_id=? AND version=? AND admission_epoch=?",
                watermark,
                snapshot.now,
                snapshot.now,
                actorId,
                reason,
                scope.clientId,
                scope.clubId,
                scope.userId,
                fence.long("version"),
                fence.long("admission_epoch"),
            ) != 1
        ) {
            fail("version_conflict")
        }
    }
}
