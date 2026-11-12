package com.example.bot.data.client.bindings

import java.sql.Connection

/** H2 parity for V079; metadata fencing is not actor authorization. */
class ControlHeadGuardTrigger : BindingH2Trigger() {
    override fun fire(
        connection: Connection,
        oldRow: Array<Any?>?,
        newRow: Array<Any?>?,
    ) {
        val next = newRow ?: reject()
        if (table == "target_maintenance_publications") {
            if (oldRow != null) reject()
            history(connection, next)
        } else if (oldRow == null) {
            initial(connection, next)
        } else {
            updated(oldRow, next)
        }
    }

    private fun initial(
        connection: Connection,
        row: Array<Any?>,
    ) {
        if (number(row, "version") != 1L || number(row, "generation") != 1L || value(row, "status") != "DISABLED") {
            reject()
        }
        connection
            .prepareStatement(
                "SELECT kind,client_id FROM ${qualified("target_bot_connections")} WHERE id=? FOR UPDATE",
            ).use { query ->
                query.setObject(1, value(row, "root_id"))
                query.executeQuery().use { result ->
                    if (!result.next() || result.getString(1) != "PLATFORM" || result.getObject(2) != null) reject()
                }
            }
    }

    private fun updated(
        old: Array<Any?>,
        next: Array<Any?>,
    ) {
        if (!same(old, next, "id", "root_id", "authority_connection_id", "provenance_id", "created_at")) reject()
        val version = number(old, "version")
        val generation = number(old, "generation")
        if (version == Long.MAX_VALUE || generation == Long.MAX_VALUE) reject()
        if (number(next, "version") != version + 1 || number(next, "generation") != generation + 1) reject()
        if (same(old, next, "last_audit_id")) reject()
        if (value(next, "status") == "ENABLED" && value(old, "status") != "DISABLED") reject()
    }

    private fun history(
        connection: Connection,
        row: Array<Any?>,
    ) {
        val fields = listOf("version", "generation", "status", "manifest_id", "manifest_digest", "last_audit_id")
        connection
            .prepareStatement(
                "SELECT ${fields.joinToString(",")} FROM ${qualified("target_maintenance_control_heads")} WHERE id=?",
            ).use { query ->
                query.setObject(1, value(row, "control_id"))
                query.executeQuery().use { result ->
                    if (!result.next()) reject()
                    fields.forEachIndexed { index, field ->
                        val name = if (field == "last_audit_id") "audit_id" else field
                        if (result.getObject(index + 1) != value(row, name)) reject()
                    }
                }
            }
    }
}
