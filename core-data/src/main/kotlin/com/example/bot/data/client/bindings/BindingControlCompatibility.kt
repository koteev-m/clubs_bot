package com.example.bot.data.client.bindings

import com.example.bot.data.client.onboarding.OnboardingSql
import com.example.bot.data.client.onboarding.fail
import java.sql.Connection
import java.util.UUID

/** The old evidence writer must not become an alternate writer for an adopted control scope. */
internal class BindingControlCompatibility(
    private val schema: BindingStorageSchema,
) {
    fun configure(connection: Connection) {
        val setting =
            if (connection.metaData.databaseProductName == "PostgreSQL") {
                "SET LOCAL search_path=pg_catalog,${schema.quoted},pg_temp"
            } else {
                "SET SCHEMA ${schema.quoted}"
            }
        connection.createStatement().use { statement ->
            statement.execute(setting)
            val tables =
                listOf(
                    "users",
                    "audit_log",
                    "target_bot_connections",
                    "target_bot_bindings",
                    "target_binding_commands",
                    "target_binding_clubs",
                    "target_credential_revisions",
                    "target_credential_materials",
                )
            val relations = tables.mapIndexed { index, name -> "${schema.quoted}.$name r$index" }.joinToString(",")
            statement.executeQuery("SELECT 1 FROM $relations WHERE 1=0").close()
        }
    }

    /** Caller holds the root UPDATE lock; control bootstrap's FK lock serializes adoption with this check. */
    fun requireUnmanaged(
        connection: Connection,
        root: UUID,
    ) {
        val names = mutableSetOf<String>()
        val selected = schema.quoted.removeSurrounding("\"")
        connection.metaData.getTables(connection.catalog, null, "%", arrayOf("TABLE", "BASE TABLE")).use { tables ->
            while (tables.next()) {
                if (tables.getString("TABLE_SCHEM") == selected) names.add(tables.getString("TABLE_NAME").lowercase())
            }
        }
        val count = listOf("target_maintenance_control_heads", "target_maintenance_publications").count { it in names }
        if (count == 0) {
            requirePreControlHistory(connection, names)
            return
        }
        if (count != 2) fail("invalid_transition")
        connection
            .prepareStatement(
                "SELECT 1 FROM ${schema.quoted}.target_maintenance_control_heads WHERE root_id=?",
            ).use {
                it.setObject(1, root)
                it.executeQuery().use { result -> if (result.next()) fail("invalid_transition") }
            }
    }

    private fun requirePreControlHistory(
        connection: Connection,
        names: Set<String>,
    ) {
        if ("flyway_schema_history" !in names) fail("invalid_transition")
        val history = "${schema.quoted}.\"flyway_schema_history\""
        val versions =
            OnboardingSql(connection)
                .rows("SELECT \"version\" FROM $history WHERE \"success\"=TRUE AND \"version\" IS NOT NULL")
                .map {
                    it.values
                        .single()
                        ?.substringBefore('.')
                        ?.toIntOrNull() ?: fail("invalid_transition")
                }
        if (versions.isEmpty() || versions.any { it >= CONTROL_SCHEMA_VERSION }) fail("invalid_transition")
    }
}

private const val CONTROL_SCHEMA_VERSION = 79
