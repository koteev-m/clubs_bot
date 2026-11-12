package com.example.bot.data.client.bindings

import com.example.bot.data.client.onboarding.fail
import java.sql.Connection

/** Inert until verify in the existing mutation transaction. No connection acquisition or commit here. */
class JdbcControlHeadLease(
    private val schema: BindingStorageSchema,
    private val expected: MaintenanceControlHead,
) : AutoCloseable {
    val snapshot: MaintenanceControlHead get() = expected

    private var bound: Connection? = null
    private var transaction: String? = null
    private var closed = false
    private var started: Long? = null

    fun verify(connection: Connection): MaintenanceControlHead {
        if (closed || connection.isClosed || connection.autoCommit) fail("forbidden")
        if (bound != null && bound !== connection) fail("forbidden")
        if (transaction != null && transaction != transactionId(connection)) fail("forbidden")
        val initial = started ?: System.nanoTime().also { started = it }
        if (System.nanoTime() - initial >= CONTROL_ATTEMPT_NANOS) fail("forbidden")
        // Reapply after savepoint rollback or caller GUC changes, including on repeated verification.
        configureControlTransaction(connection, schema)
        val lock = if (connection.metaData.databaseProductName == "PostgreSQL") "FOR SHARE" else "FOR UPDATE"
        val head = readControlHead(connection, schema, expected.scope.id, lock) ?: fail("forbidden")
        if (head != expected || head.status != ControlPublicationStatus.ENABLED) fail("forbidden")
        val currentTransaction = transactionId(connection) ?: fail("forbidden")
        if (transaction != null && transaction != currentTransaction) fail("forbidden")
        bound = connection
        transaction = currentTransaction
        return head
    }

    override fun close() {
        closed = true
        bound = null
    }

    private fun transactionId(connection: Connection): String? {
        val sql =
            if (connection.metaData.databaseProductName == "PostgreSQL") {
                "SELECT pg_catalog.txid_current()"
            } else {
                "SELECT TRANSACTION_ID()"
            }
        return connection.createStatement().use { query ->
            query.executeQuery(sql).use { result ->
                check(result.next())
                result.getString(1)
            }
        }
    }
}

internal fun readControlHead(
    connection: Connection,
    schema: BindingStorageSchema,
    id: java.util.UUID,
    lock: String,
): MaintenanceControlHead? {
    // Also safe for standalone autocommit metadata reads, where SET LOCAL cannot span the SELECT.
    val equality = if (connection.metaData.databaseProductName == "PostgreSQL") "OPERATOR(pg_catalog.=)" else "="
    return connection
        .prepareStatement(
            "SELECT id,root_id,authority_connection_id,version,generation,status,manifest_id,manifest_digest " +
                "FROM ${schema.quoted}.target_maintenance_control_heads WHERE id $equality ? $lock",
        ).use { query ->
            query.setObject(1, id)
            query.executeQuery().use { result ->
                if (!result.next()) return@use null
                val actualId = result.getObject("id", java.util.UUID::class.java)
                if (actualId != id) fail("forbidden")
                val head =
                    MaintenanceControlHead(
                        MaintenanceControlScope(
                            actualId,
                            result.getObject("root_id", java.util.UUID::class.java),
                            result.getObject("authority_connection_id", java.util.UUID::class.java),
                        ),
                        result.getLong("version"),
                        result.getLong("generation"),
                        ControlPublicationStatus.valueOf(result.getString("status")),
                        result.getObject("manifest_id", java.util.UUID::class.java),
                        result.getString("manifest_digest"),
                    )
                if (result.next()) fail("forbidden")
                head
            }
        }
}
