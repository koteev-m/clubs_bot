package com.example.bot.data.client.bindings

import com.example.bot.data.client.onboarding.DbRow
import com.example.bot.data.client.onboarding.OnboardingSql
import com.example.bot.data.client.onboarding.fail
import com.example.bot.data.client.onboarding.long
import java.util.Locale
import java.util.UUID

internal class BindingRows(
    val sql: OnboardingSql,
) {
    fun current(
        id: UUID,
        exclusive: Boolean,
    ): BindingView? {
        val lock =
            if (!exclusive &&
                sql.connection.metaData.databaseProductName == "PostgreSQL"
            ) {
                " FOR SHARE"
            } else {
                " FOR UPDATE"
            }
        val root = sql.row("SELECT * FROM target_bot_connections WHERE id=?$lock", id)?.bindingColumns() ?: return null
        val bindingId = root["current_binding_id"]?.let(UUID::fromString) ?: fail("invalid_transition")
        val binding =
            sql.row("SELECT * FROM target_bot_bindings WHERE root_id=? AND id=?", id, bindingId)?.bindingColumns()
                ?: fail("invalid_transition")
        val clubs =
            sql
                .rows(
                    "SELECT club_id FROM target_binding_clubs WHERE root_id=? AND binding_id=? ORDER BY club_id",
                    id,
                    bindingId,
                ).map { it.bindingColumns().long("club_id") }
        return BindingView(
            id,
            root.long("telegram_bot_id"),
            BindingKind.valueOf(checkNotNull(root["kind"])),
            root["client_id"]?.toLong(),
            BindingFence(bindingId, root.long("version"), root.long("generation")),
            BindingState.valueOf(checkNotNull(binding["status"])),
            binding["credential_ref"]?.let(UUID::fromString),
            clubs,
        )
    }

    fun receipt(
        actor: Long,
        connectionId: UUID,
        keyHash: String,
    ): DbRow? =
        sql
            .row(
                "SELECT * FROM target_binding_commands WHERE actor_user_id=? " +
                    "AND authority_connection_id=? AND key_hash=?",
                actor,
                connectionId,
                keyHash,
            )?.bindingColumns()

    fun matches(
        receipt: DbRow,
        current: BindingView,
    ): Boolean =
        receipt["root_id"] == current.rootId.toString() &&
            receipt["binding_id"] == current.fence.bindingId.toString() &&
            receipt.long("result_version") == current.fence.version &&
            receipt.long("result_generation") == current.fence.generation

    fun materialAvailable(
        root: UUID,
        material: UUID,
    ): Boolean =
        sql.row(
            "SELECT id FROM target_credential_materials WHERE root_id=? AND id=? AND status='AVAILABLE'",
            root,
            material,
        ) !=
            null
}

/** Local JDBC-label normalization; existing onboarding readers and their behavior are unchanged. */
internal fun DbRow.bindingColumns(): DbRow {
    val normalized = mapKeys { it.key.lowercase(Locale.ROOT) }
    if (normalized.size != size) fail("invalid_transition")
    return normalized
}
