package com.example.bot.data.client.assignments

import com.example.bot.data.client.onboarding.OnboardingSql
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

internal class AssignmentAudit(
    private val sql: OnboardingSql,
    private val actorId: Long,
    private val correlation: String,
    private val scope: StaffScope,
    private val command: AssignmentCommand,
    private val authorityReference: String,
    private val now: Instant,
    private val targetInvitationId: Long? = null,
) {
    fun begin(before: AssignmentView): Long =
        sql.insert(
            "INSERT INTO audit_log(created_at,actor_user_id,subject_user_id,club_id," +
                "entity_type,entity_id,action,fingerprint,metadata_json) " +
                "VALUES (?,?,?,?,?,?,?,?,?)",
            now,
            actorId,
            scope.userId,
            scope.clubId,
            "CLIENT_ONBOARDING",
            scope.clubId,
            "client.role_foundation." + command.javaClass.simpleName.lowercase(),
            "role-foundation-" + UUID.randomUUID(),
            metadata(before, before).toString(),
        )

    fun complete(
        id: Long,
        before: AssignmentView,
        after: AssignmentView,
    ) {
        val completed = JsonObject(metadata(before, after) + ("affectedSources" to affectedSources(id)))
        check(
            sql.update("UPDATE audit_log SET metadata_json=? WHERE id=?", completed.toString(), id) == 1,
        )
    }

    private fun metadata(
        before: AssignmentView,
        after: AssignmentView,
    ): JsonObject =
        buildJsonObject {
            put("clientId", scope.clientId)
            put("clubId", scope.clubId)
            put("subjectId", scope.userId)
            put("mode", "INACTIVE_FOUNDATION")
            put("commandCorrelation", correlation)
            put("intentHash", command.intentHash())
            put("authorityReference", authorityReference)
            put("targetInvitationId", targetInvitationId)
            put("reason", command.reason)
            put("before", before.auditView())
            put("after", after.auditView())
        }

    private fun affectedSources(auditId: Long): JsonArray {
        val roles =
            sql.rows(
                "SELECT id,club_id,user_id,status,version FROM client_role_assignments " +
                    "WHERE client_id=? AND last_audit_id=?",
                scope.clientId,
                auditId,
            )
        val delegations =
            sql.rows(
                "SELECT id,club_id,user_id,status,version FROM client_permission_delegations " +
                    "WHERE client_id=? AND last_audit_id=?",
                scope.clientId,
                auditId,
            )
        return JsonArray(
            (roles + delegations).map { row ->
                buildJsonObject { row.forEach { (key, value) -> put(key, value) } }
            },
        )
    }
}

private fun AssignmentView.auditView(): JsonObject =
    buildJsonObject {
        put("epoch", admission?.epoch)
        put("revision", admission?.version)
        put("membershipVersion", membershipVersion)
        put("sources", buildJsonObject { sources.toSortedMap().forEach { (id, status) -> put(id.toString(), status) } })
        put(
            "contributions",
            JsonArray(
                contributions.map {
                    buildJsonObject {
                        put("kind", it.kind.name)
                        put("source", it.sourceId.toString())
                        put("permission", it.permission)
                        put("policyVersion", it.policy.version)
                        put("policyHash", it.policy.hash)
                        put("selector", it.selector.kind.name)
                        put("shifts", JsonArray(it.selector.shifts.map(::JsonPrimitive)))
                    }
                },
            ),
        )
    }
