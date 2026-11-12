package com.example.bot.data.client.bindings

import java.sql.Connection

/** Every ordinary head DML appends its exact snapshot in the same transaction. */
class ControlHeadHistoryTrigger : BindingH2Trigger() {
    override fun fire(
        connection: Connection,
        oldRow: Array<Any?>?,
        newRow: Array<Any?>?,
    ) {
        val row = checkNotNull(newRow)
        connection
            .prepareStatement(
                "INSERT INTO ${qualified("target_maintenance_publications")} " +
                    "(control_id,version,generation,status,manifest_id,manifest_digest,audit_id) " +
                    "VALUES (?,?,?,?,?,?,?)",
            ).use { statement ->
                listOf("id", "version", "generation", "status", "manifest_id", "manifest_digest", "last_audit_id")
                    .forEachIndexed { index, field -> statement.setObject(index + 1, value(row, field)) }
                statement.executeUpdate()
            }
    }
}
