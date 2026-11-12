package com.example.bot.data.client.bindings

import java.sql.Connection

/** H2 parity for immutable root identity, inactive-only bindings, terminal history and material fences. */
class TargetMetadataGuardTrigger : BindingH2Trigger() {
    override fun fire(
        connection: Connection,
        oldRow: Array<Any?>?,
        newRow: Array<Any?>?,
    ) {
        val child = lockChild(connection, oldRow, newRow)
        if (newRow == null) {
            if (table != "target_binding_clubs" ||
                status(connection, "target_bot_bindings", value(checkNotNull(oldRow), "binding_id")) !in NONTERMINAL
            ) {
                reject()
            }
            child?.let { bump(connection, it) }
            return
        }
        when (table) {
            "target_client_mapping_epochs" -> epochHead(oldRow, newRow)
            "target_binding_commands" -> if (oldRow != null) reject()
            "target_bot_connections" -> root(connection, oldRow, newRow)
            "target_bot_bindings" -> binding(oldRow, newRow)
            "target_credential_materials" -> material(oldRow, newRow)
            "target_credential_revisions" -> {
                if (oldRow != null ||
                    status(connection, "target_credential_materials", value(newRow, "material_id")) != "AVAILABLE"
                ) {
                    reject()
                }
            }
            "target_binding_clubs" -> {
                if (oldRow != null ||
                    status(connection, "target_bot_bindings", value(newRow, "binding_id")) !in NONTERMINAL
                ) {
                    reject()
                }
            }
        }
        child?.let { bump(connection, it) }
    }

    /** NOWAIT closes the child→root wait edge against the service's root→child order. */
    private fun lockChild(
        connection: Connection,
        old: Array<Any?>?,
        new: Array<Any?>?,
    ): Any? {
        if (table !in CHILD_TABLES) return null
        val row = old ?: checkNotNull(new)
        val root = checkNotNull(value(row, "root_id"))
        val initialized =
            connection
                .prepareStatement(
                    "SELECT current_binding_id FROM ${qualified(
                        "target_bot_connections",
                    )} WHERE id=? FOR UPDATE NOWAIT",
                ).use { statement ->
                    statement.setObject(1, root)
                    statement.executeQuery().use { result ->
                        if (!result.next()) reject()
                        result.getObject(1) != null
                    }
                }
        val bootstrap = table == "target_bot_bindings" && old == null && new != null
        if (!initialized && !bootstrap) reject()
        return root.takeIf { initialized } // Unselected Create bootstrap has no current receipt.
    }

    private fun bump(
        connection: Connection,
        root: Any,
    ) {
        connection
            .prepareStatement(
                "UPDATE ${qualified("target_bot_connections")} " +
                    "SET version=version+1,generation=generation+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",
            ).use { statement ->
                statement.setObject(1, root)
                if (statement.executeUpdate() != 1) reject()
            }
    }

    private fun epochHead(
        old: Array<Any?>?,
        new: Array<Any?>,
    ) {
        if (old == null) return
        if (number(old, "epoch") == Long.MAX_VALUE) reject()
        if (!same(old, new, "client_id") || number(new, "epoch") != number(old, "epoch") + 1) reject()
    }

    private fun root(
        connection: Connection,
        old: Array<Any?>?,
        new: Array<Any?>,
    ) {
        if (old == null) {
            if (number(new, "version") != 1L ||
                number(new, "generation") != 1L ||
                value(new, "current_binding_id") != null
            ) {
                reject()
            }
            return
        }
        if (!same(
                old,
                new,
                "id",
                "telegram_bot_id",
                "kind",
                "client_id",
                "provenance_id",
                "created_by",
                "created_audit_id",
                "created_at",
            )
        ) {
            reject()
        }
        if (value(old, "current_binding_id") != value(new, "current_binding_id") &&
            status(connection, "target_bot_bindings", value(new, "current_binding_id")) != "PENDING"
        ) {
            reject()
        }
        val bootstrap =
            value(old, "current_binding_id") == null &&
                value(new, "current_binding_id") != null &&
                number(old, "version") == 1L &&
                number(old, "generation") == 1L &&
                number(new, "version") == 1L &&
                number(new, "generation") == 1L
        if (bootstrap) return
        val version = number(old, "version")
        val generation = number(old, "generation")
        if (version == Long.MAX_VALUE || generation == Long.MAX_VALUE) reject()
        if (number(new, "version") != version + 1 ||
            number(new, "generation") != generation + 1 ||
            value(new, "current_binding_id") == null
        ) {
            reject()
        }
        if (value(old, "current_binding_id") != value(new, "current_binding_id") &&
            status(connection, "target_bot_bindings", value(old, "current_binding_id")) != "REVOKED"
        ) {
            reject()
        }
    }

    private fun binding(
        old: Array<Any?>?,
        new: Array<Any?>,
    ) {
        if (value(new, "status") == "ACTIVE") reject()
        if (old != null &&
            (
                value(old, "status") == "REVOKED" ||
                    !same(old, new, "id", "root_id", "provenance_id", "created_audit_id", "created_at")
            )
        ) {
            reject()
        }
    }

    private fun material(
        old: Array<Any?>?,
        new: Array<Any?>,
    ) {
        if (old == null) {
            if (value(new, "status") != "AVAILABLE") reject()
        } else if (value(old, "status") == "RETIRED" ||
            value(new, "status") != "RETIRED" ||
            !same(old, new, "id", "root_id", "created_audit_id", "created_at")
        ) {
            reject()
        }
    }

    private companion object {
        val CHILD_TABLES =
            setOf(
                "target_bot_bindings",
                "target_credential_materials",
                "target_credential_revisions",
                "target_binding_clubs",
            )
        val NONTERMINAL = setOf("PENDING", "DISABLED")
    }
}
