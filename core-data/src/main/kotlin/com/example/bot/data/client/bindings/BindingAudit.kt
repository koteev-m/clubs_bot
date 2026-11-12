package com.example.bot.data.client.bindings

import com.example.bot.data.client.onboarding.OnboardingSql
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

internal class BindingAudit(
    private val sql: OnboardingSql,
    private val now: Instant,
) {
    fun begin(
        actor: Long,
        command: BindingCommand,
        botId: Long,
        before: BindingView?,
        identity: MaintenanceIdentity,
    ): Long =
        sql.insert(
            "INSERT INTO audit_log(created_at,actor_user_id,entity_type,entity_id,action,fingerprint,metadata_json) " +
                "VALUES (?,?,'CLIENT_ONBOARDING',?,?,?,?)",
            now,
            actor,
            botId,
            "target.binding." + command.javaClass.simpleName.lowercase(),
            "binding-foundation-" + UUID.randomUUID(),
            metadata(command, before, null, identity).toString(),
        )

    fun complete(
        id: Long,
        command: BindingCommand,
        before: BindingView?,
        after: BindingView,
        identity: MaintenanceIdentity,
    ) {
        check(
            sql.update(
                "UPDATE audit_log SET metadata_json=? WHERE id=?",
                metadata(command, before, after, identity).toString(),
                id,
            ) ==
                1,
        )
    }

    private fun metadata(
        command: BindingCommand,
        before: BindingView?,
        after: BindingView?,
        identity: MaintenanceIdentity,
    ): JsonObject =
        buildJsonObject {
            put("mode", "INACTIVE_BINDING_FOUNDATION")
            put("rootId", command.rootId.toString())
            put("intentHash", command.intentHash())
            put("authorityReference", identity.authorityReference.toString())
            put("before", before?.auditView() ?: JsonNull)
            put("after", after?.auditView() ?: JsonNull)
        }
}

private fun BindingView.auditView(): JsonObject =
    buildJsonObject {
        put("bindingId", fence.bindingId.toString())
        put("version", fence.version)
        put("generation", fence.generation)
        put("state", status.name)
        put("credentialReference", credentialReference?.toString())
    }
