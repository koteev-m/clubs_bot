package com.example.bot.testing

import org.h2.api.ErrorCode
import org.h2.jdbcx.JdbcDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Releases only the in-memory database named by this fixture's own data source. */
internal fun shutdownOwnedH2Database(
    source: JdbcDataSource,
    database: Database? = null,
) {
    require(source.getURL().startsWith("jdbc:h2:mem:"))
    try {
        database?.let { TransactionManager.closeAndUnregister(it) }
    } finally {
        source.connection.use { connection ->
            connection.createStatement().use { it.execute("SHUTDOWN") }
        }
    }
}

/** A failed migration/seed must not leave its newly created in-memory database registered. */
internal fun <T> prepareOwnedH2Database(
    source: JdbcDataSource,
    setup: (register: (Database) -> Unit) -> T,
): T {
    var ownedDatabase: Database? = null
    return try {
        setup { database ->
            check(ownedDatabase == null || ownedDatabase === database)
            ownedDatabase = database
        }
    } catch (failure: Throwable) {
        try {
            shutdownOwnedH2Database(source, ownedDatabase)
        } catch (cleanup: Throwable) {
            if (cleanup !== failure) failure.addSuppressed(cleanup)
        }
        throw failure
    }
}

/** IFEXISTS prevents this probe from accidentally recreating a database after teardown. */
internal fun assertOwnedH2DatabaseClosed(url: String) {
    require(url.startsWith("jdbc:h2:mem:"))
    val failure =
        assertFailsWith<SQLException> {
            DriverManager.getConnection("$url;IFEXISTS=TRUE", "sa", "").use { }
        }
    assertEquals(ErrorCode.DATABASE_NOT_FOUND_WITH_IF_EXISTS_1, failure.errorCode)
}
