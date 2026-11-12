package com.example.bot.data.client.assignments

import com.example.bot.data.client.onboarding.DbRow
import com.example.bot.data.client.onboarding.OnboardingSession
import com.example.bot.data.client.onboarding.long
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** One source-tail event accompanies the unchanged legacy member/staff audit in the same transaction. */
internal class PlatformMembershipSourceAudit(
    private val session: OnboardingSession,
    private val clientId: Long,
    private val userId: Long,
    private val action: String,
) {
    private val sql get() = session.sql

    fun begin(snapshot: AssignmentSnapshot): Long =
        sql.insert(
            "INSERT INTO audit_log(created_at,actor_user_id,subject_user_id,entity_type,entity_id," +
                "action,fingerprint,metadata_json) VALUES (?,?,?,'CLIENT_ONBOARDING',?,?,?,?)",
            session.now,
            session.userId,
            userId,
            userId,
            action,
            "platform-source-" + UUID.randomUUID(),
            metadata(snapshot, snapshot).toString(),
        )

    fun complete(
        id: Long,
        before: AssignmentSnapshot,
    ) {
        val after = AssignmentSnapshot(sql, clientId, AssignmentPolicyCatalog(), session.now)
        check(
            sql.update("UPDATE audit_log SET metadata_json=? WHERE id=?", metadata(before, after).toString(), id) == 1,
        )
    }

    private fun metadata(
        before: AssignmentSnapshot,
        after: AssignmentSnapshot,
    ): JsonObject =
        buildJsonObject {
            put("clientId", clientId)
            put("subjectId", userId)
            put("commandCorrelation", session.correlation)
            put("operation", action)
            put("authority", "CONFIGURED_PLATFORM_OWNER")
            put("before", before.state())
            put("after", after.state())
        }

    private fun AssignmentSnapshot.state(): JsonObject {
        val dependent = delegations.filter { it.long("user_id") == userId || it.long("issuer_user_id") == userId }
        val recipients = dependent.map { it.scope() }.toSet()
        return buildJsonObject {
            put("admissions", safeRows(admissions.filter { it.long("user_id") == userId || it.scope() in recipients }))
            put("roles", safeRows(roles.filter { it.long("user_id") == userId }))
            put("delegations", safeRows(dependent))
        }
    }

    private fun safeRows(rows: List<DbRow>): JsonArray = JsonArray(rows.map(::safeRow))

    private fun safeRow(row: DbRow): JsonObject =
        buildJsonObject { row.filterKeys { it in SAFE_FIELDS }.forEach { (key, value) -> put(key, value) } }

    companion object {
        private val SAFE_FIELDS =
            setOf(
                "id",
                "client_id",
                "club_id",
                "user_id",
                "status",
                "version",
                "admission_epoch",
                "membership_version",
                "role_code",
                "issuer_user_id",
                "issuer_assignment_id",
                "recipient_assignment_id",
                "issuer_admission_epoch",
                "revoked_through_invitation_id",
                "policy_version",
                "policy_hash",
            )
    }
}
