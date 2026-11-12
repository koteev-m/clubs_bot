package com.example.bot.data.client.bindings

import java.sql.Connection

/** Trusted operator resolution and limits, local to this transaction on the supplied connection. */
internal fun configureControlTransaction(
    connection: Connection,
    schema: BindingStorageSchema,
) {
    check(!connection.autoCommit)
    if (connection.metaData.databaseProductName == "PostgreSQL") {
        connection.createStatement().use { statement ->
            statement.execute("SET LOCAL search_path=pg_catalog,${schema.quoted},pg_temp")
            statement.execute("SET LOCAL lock_timeout='2s'")
            statement.execute("SET LOCAL statement_timeout='5s'")
            statement.execute("SET LOCAL idle_in_transaction_session_timeout='10s'")
        }
    }
}

internal const val CONTROL_ATTEMPT_NANOS = 10_000_000_000L
