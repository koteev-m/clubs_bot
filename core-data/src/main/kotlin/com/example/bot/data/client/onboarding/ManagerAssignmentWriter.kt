package com.example.bot.data.client.onboarding

import com.example.bot.data.client.assignments.AssignmentCommand
import com.example.bot.data.client.assignments.AssignmentPolicyCatalog
import com.example.bot.data.client.assignments.AssignmentReceipt
import com.example.bot.data.client.assignments.AssignmentSnapshot
import com.example.bot.data.client.assignments.AssignmentSourceWriter
import com.example.bot.data.client.assignments.StaffRole
import com.example.bot.data.client.assignments.StaffScope
import com.example.bot.data.client.assignments.activeManagerSourcesSql
import com.example.bot.data.client.assignments.admissionVersion
import com.example.bot.data.client.assignments.managerPolicy
import com.example.bot.data.client.assignments.scope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** Actor and exact client locks belong to the calling onboarding transaction. */
internal class ManagerAssignmentWriter(
    private val session: OnboardingSession,
) {
    private val sql get() = session.sql
    private val actor get() = checkNotNull(session.userId)

    fun list(
        client: Long,
        club: Long,
    ): List<DbRow> =
        sql.rows(
            "SELECT a.*,u.display_name,u.username,u.telegram_user_id FROM client_role_assignments a " +
                "JOIN client_manager_invitations i ON i.source_id=a.id AND i.client_id=a.client_id AND " +
                "i.club_id=a.club_id " +
                "AND i.role_code=a.role_code AND i.claimant_user_id=a.user_id AND i.status='accepted' " +
                "JOIN users u ON u.id=a.user_id WHERE a.client_id=? AND a.club_id=? AND " +
                "a.status='ACTIVE' ORDER BY a.created_at,a.id",
            client,
            club,
        )

    fun active(row: DbRow): Boolean {
        val source =
            sql.row(
                "$activeManagerSourcesSql AND a.id=? AND a.client_id=? AND a.club_id=? AND a.user_id=?",
                session.now,
                UUID.fromString(row["source_id"]),
                row.long("client_id"),
                row.long("club_id"),
                row.long("claimant_user_id"),
            )
        return source != null
    }

    fun requireRecipient(
        row: DbRow,
        user: Long,
    ) {
        val client = row.long("client_id")
        val club = row.long("club_id")
        val telegram =
            sql
                .row(
                    "SELECT telegram_user_id FROM users WHERE id=?",
                    user,
                )?.long("telegram_user_id") ?: fail("not_found")
        if (session.isBusinessOwner(client, user) ||
            user == row.long("inviter_user_id") ||
            session.ownerId == telegram
        ) {
            fail("not_found")
        }
        val member = membership(client, user)
        if (member != null && !member.active()) fail("not_found")
        if (row["status"] != "accepted" &&
            sql.row(
                "SELECT id FROM client_manager_invitation_fences WHERE client_id=? AND user_id=? " +
                    "AND (club_id IS NULL OR club_id=?) AND through_invitation_id>=? LIMIT 1",
                client,
                user,
                club,
                row.long("id"),
            ) != null
        ) {
            fail("not_found")
        }
    }

    fun accept(
        row: DbRow,
        issuer: String,
    ) {
        val client = row.long("client_id")
        val club = row.long("club_id")
        val user = row.long("claimant_user_id")
        val role = StaffRole.valueOf(checkNotNull(row["role_code"]))
        val policy = managerPolicy(role)
        if (row["policy_hash"] != policy.reference.hash ||
            row.long("policy_version") != policy.version
        ) {
            fail("invalid_transition")
        }
        val scope = StaffScope(client, club, user)
        val fence = admission(client, club, user)
        val oldMember = membership(client, user)
        if (oldMember != null && !oldMember.active()) fail("invalid_transition")
        if (fence == null) {
            initialize(scope, oldMember)
        } else {
            if (fence["mode"] != "TARGET" || !managedAdmission(scope)) fail("invalid_transition")
            if (fence["status"] != "ACTIVE" || fence["membership_version"] != oldMember?.get("version")) {
                // Platform disable/restore terminates its sources. A subsequent explicit, current invitation
                // may readmit only a known manager tuple with no surviving authority and a fresh membership.
                if (fence["status"] != "DISABLED" ||
                    oldMember == null ||
                    hasActiveSources(scope)
                ) {
                    fail("invalid_transition")
                }
                sql.update(
                    "UPDATE client_club_access_state SET status='ACTIVE',admission_epoch=admission_epoch+1," +
                        "version=version+1," +
                        "membership_version=?,updated_at=?,revoked_at=NULL,revoked_by=NULL,revoke_reason=NULL " +
                        "WHERE client_id=? AND club_id=? AND user_id=?",
                    oldMember.long("version"),
                    session.now,
                    client,
                    club,
                    user,
                )
            }
        }
        val snapshot = AssignmentSnapshot(sql, client, AssignmentPolicyCatalog(listOf(policy)), session.now)
        if (!snapshot.admitted(scope) || !snapshot.registered(policy.permissions)) fail("invalid_transition")
        if (snapshot.roles.any {
                it.scope() == scope &&
                    it["role_code"] == role.name &&
                    it["status"] == "ACTIVE"
            }
        ) {
            fail("invalid_transition")
        }
        val id = UUID.randomUUID()
        val audit = auditId(scope, "client.manager.confirm", id, "Manager invitation confirmed", row.long("id"))
        val writer = writer(snapshot, audit, issuer)
        writer.issue(
            AssignmentCommand.IssueRole(
                scope,
                checkNotNull(snapshot.admission(scope)).admissionVersion(),
                id,
                role,
                policy.reference,
                reason = "Manager invitation confirmed",
                confirmed = true,
            ),
        )
        sql.update(
            "UPDATE client_manager_invitations SET status='accepted',source_id=?," +
                "verification_code=NULL,version=version+1 WHERE id=?",
            id,
            row.long("id"),
        )
    }

    fun canRevoke(
        client: Long,
        source: DbRow,
    ): Boolean {
        val user = source.long("user_id")
        return user != actor &&
            source["telegram_user_id"] != session.ownerId?.toString() &&
            !session.isBusinessOwner(client, user)
    }

    fun revoke(
        client: Long,
        club: Long,
        body: JsonObject,
        roles: Set<StaffRole>,
    ) {
        body.only(
            listOf("action", "expectedVersion", "assignmentId", "expectedAssignmentVersion", "reason", "confirmed"),
        )
        if (body["confirmed"] != JsonPrimitive(true)) fail("validation_error")
        val reason = body.text("reason", MAX_REASON, required = true)
        val id = body.string("assignmentId") ?: fail("validation_error")
        val source =
            list(client, club).singleOrNull { it["id"] == id && roles.any { role -> role.name == it["role_code"] } }
                ?: fail("not_found")
        if (source.long("version") != body.id("expectedAssignmentVersion")) fail("version_conflict")
        val user = source.long("user_id")
        if (!canRevoke(client, source)) fail("forbidden")
        val snapshot = AssignmentSnapshot(sql, client, AssignmentPolicyCatalog(), session.now)
        val audit = auditId(StaffScope(client, club, user), "client.manager.revoke", UUID.fromString(id), reason)
        val writer = writer(snapshot, audit, "REVOKE")
        val affected = writer.revoke(listOf(source), emptyList(), reason)
        writer.bump(affected)
        affected.forEach { sql.fenceManagerInvitations(client, it.userId, it.clubId) }
    }

    private fun initialize(
        scope: StaffScope,
        member: DbRow?,
    ) {
        val (client, club, user) = scope
        // An opaque historical package cannot safely be classified or merged with a new TARGET admission.
        // Leave the entire tuple unchanged until its independent grants are explicitly reconciled.
        if (sql.row(
                "SELECT 1 AS present WHERE EXISTS(SELECT 1 FROM client_club_grants WHERE client_id=? AND " +
                    "club_id=? AND user_id=?) " +
                    "OR EXISTS(SELECT 1 FROM client_club_staff WHERE client_id=? AND club_id=? AND user_id=?) " +
                    "OR EXISTS(SELECT 1 FROM user_roles r WHERE r.user_id=? AND (r.scope_type='GLOBAL' OR " +
                    "(r.scope_type='CLUB' AND r.scope_club_id=?)) AND (r.role_code<>'GUEST' OR " +
                    "EXISTS(SELECT 1 FROM user_role_permissions p WHERE p.user_role_id=r.id)))",
                client,
                club,
                user,
                client,
                club,
                user,
                user,
                club,
            ) != null
        ) {
            fail("invalid_transition")
        }
        if (member == null) {
            sql.update(
                "INSERT INTO client_memberships(client_id,user_id,is_active,version,created_at," +
                    "updated_at) VALUES (?,?,TRUE,1,?,?)",
                client,
                user,
                session.now,
                session.now,
            )
        }
        sql.update(
            "INSERT INTO client_club_access_state(client_id,club_id,user_id,status,mode," +
                "membership_version,created_at,updated_at) " +
                "VALUES (?,?,?,'ACTIVE','TARGET',?,?,?)",
            client,
            club,
            user,
            member?.long("version") ?: 1L,
            session.now,
            session.now,
        )
    }

    private fun membership(
        client: Long,
        user: Long,
    ) = sql.row("SELECT * FROM client_memberships WHERE client_id=? AND user_id=?", client, user)

    private fun admission(
        client: Long,
        club: Long,
        user: Long,
    ) = sql.row(
        "SELECT * FROM client_club_access_state WHERE client_id=? AND club_id=? AND user_id=?",
        client,
        club,
        user,
    )

    private fun managedAdmission(scope: StaffScope) =
        sql.row(
            "SELECT 1 AS present WHERE EXISTS(SELECT 1 FROM client_manager_invitations " +
                "WHERE client_id=? AND club_id=? AND claimant_user_id=? AND status='accepted')",
            scope.clientId,
            scope.clubId,
            scope.userId,
        ) !=
            null

    private fun hasActiveSources(scope: StaffScope) =
        sql.row(
            "SELECT 1 AS present WHERE EXISTS(SELECT 1 FROM client_role_assignments " +
                "WHERE client_id=? AND club_id=? AND user_id=? AND status='ACTIVE') OR EXISTS(SELECT 1 " +
                "FROM client_permission_delegations " +
                "WHERE client_id=? AND club_id=? AND (user_id=? OR issuer_user_id=?) AND status='ACTIVE')",
            scope.clientId,
            scope.clubId,
            scope.userId,
            scope.clientId,
            scope.clubId,
            scope.userId,
            scope.userId,
        ) != null

    private fun writer(
        snapshot: AssignmentSnapshot,
        audit: Long,
        ref: String,
    ) = AssignmentSourceWriter(
        snapshot,
        AssignmentReceipt(actor, session.actor.connectionKey, session.correlation),
        audit,
        ref,
    )

    private fun auditId(
        scope: StaffScope,
        action: String,
        id: UUID,
        reason: String,
        invitation: Long? = null,
    ): Long {
        val metadata =
            buildJsonObject {
                put("clientId", scope.clientId.toString())
                put("sourceId", id.toString())
                put("reason", reason)
                put("invitationId", invitation?.toString())
                put("commandCorrelation", session.correlation)
            }
        return sql.insert(
            "INSERT INTO audit_log(created_at,actor_user_id,subject_user_id,club_id,entity_type," +
                "entity_id,action,fingerprint,metadata_json) " +
                "VALUES (?,?,?,?,?,?,?,?,?)",
            session.now,
            actor,
            scope.userId,
            scope.clubId,
            "CLIENT_ONBOARDING",
            scope.clubId,
            action,
            "manager-" + UUID.randomUUID(),
            metadata.toString(),
        )
    }

    private companion object {
        const val MAX_REASON = 500
    }
}

/** Runs under the client lock shared with every manager invitation issue/claim/confirm/revoke. */
internal fun OnboardingSql.fenceManagerInvitations(
    clientId: Long,
    userId: Long,
    clubId: Long? = null,
) {
    update(
        "INSERT INTO client_manager_invitation_fences(client_id,user_id,club_id,through_invitation_id) " +
            "SELECT CAST(? AS BIGINT),CAST(? AS BIGINT),CAST(? AS BIGINT),latest.id " +
            "FROM (SELECT MAX(id) AS id FROM client_manager_invitations WHERE client_id=?) latest " +
            "WHERE latest.id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM client_manager_invitation_fences f " +
            "WHERE f.client_id=? AND f.user_id=? AND f.club_id IS NOT DISTINCT FROM CAST(? AS BIGINT) " +
            "AND f.through_invitation_id>=latest.id)",
        clientId,
        userId,
        clubId,
        clientId,
        clientId,
        userId,
        clubId,
    )
}
