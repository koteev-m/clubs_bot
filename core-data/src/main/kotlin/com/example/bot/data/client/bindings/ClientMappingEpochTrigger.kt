package com.example.bot.data.client.bindings

import java.sql.Connection

/** Mirrors V078 row-level epoch updates, including FK cascades. Does not change membership/grants. */
class ClientMappingEpochTrigger : BindingH2Trigger() {
    override fun fire(
        connection: Connection,
        oldRow: Array<Any?>?,
        newRow: Array<Any?>?,
    ) {
        if (table == "clients") {
            client(connection, oldRow, newRow)
        } else {
            mappings(connection, oldRow, newRow)
        }
    }

    private fun client(
        connection: Connection,
        old: Array<Any?>?,
        new: Array<Any?>?,
    ) {
        if (old == null) {
            val next = number(checkNotNull(new), "target_mapping_epoch")
            if (next != 1L) reject()
            set(new, "target_mapping_epoch", advance(connection, number(new, "id"), 1))
            return
        }
        val previous = number(old, "target_mapping_epoch")
        if (new == null) {
            if (previous == Long.MAX_VALUE) reject()
            advance(connection, number(old, "id"), previous + 1)
            return
        }
        updateClient(connection, old, new, previous)
    }

    private fun updateClient(
        connection: Connection,
        old: Array<Any?>,
        new: Array<Any?>,
        previous: Long,
    ) {
        if (!same(old, new, "id")) {
            if (previous == Long.MAX_VALUE) reject()
            advance(connection, number(old, "id"), previous + 1)
            set(new, "target_mapping_epoch", advance(connection, number(new, "id"), 1))
            return
        }
        val next = number(new, "target_mapping_epoch")
        if (next != previous && (previous == Long.MAX_VALUE || next != previous + 1)) reject()
        if (next != previous || !same(old, new, "is_active", "archived_at", "lifecycle_version")) {
            if (previous == Long.MAX_VALUE) reject()
            set(new, "target_mapping_epoch", advance(connection, number(old, "id"), previous + 1))
        }
    }

    private fun advance(
        connection: Connection,
        client: Long,
        minimum: Long,
    ): Long {
        val head = qualified("target_client_mapping_epochs")
        connection
            .prepareStatement(
                "MERGE INTO $head h USING (VALUES(CAST(? AS BIGINT),CAST(? AS BIGINT))) v(client_id,initial_epoch) " +
                    "ON h.client_id=v.client_id WHEN MATCHED THEN UPDATE SET epoch=h.epoch+1 " +
                    "WHEN NOT MATCHED THEN INSERT(client_id,epoch) VALUES(v.client_id,v.initial_epoch)",
            ).use { statement ->
                statement.setLong(1, client)
                statement.setLong(2, minimum)
                statement.executeUpdate()
            }
        return connection.prepareStatement("SELECT epoch FROM $head WHERE client_id=?").use { statement ->
            statement.setLong(1, client)
            statement.executeQuery().use {
                check(it.next())
                it.getLong(1)
            }
        }
    }

    private fun mappings(
        connection: Connection,
        old: Array<Any?>?,
        new: Array<Any?>?,
    ) {
        if (old != null && new != null && same(old, new, "client_id", "club_id")) return
        val clients =
            listOfNotNull(
                old?.let {
                    number(it, "client_id")
                },
                new?.let { number(it, "client_id") },
            ).distinct().sorted()
        connection
            .prepareStatement(
                "UPDATE ${qualified("clients")} SET target_mapping_epoch=target_mapping_epoch+1 WHERE id=?",
            ).use { statement ->
                clients.forEach { client ->
                    statement.setLong(1, client)
                    statement.executeUpdate()
                }
            }
    }
}
