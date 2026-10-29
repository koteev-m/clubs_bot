package com.example.bot.testing

import org.h2.jdbcx.JdbcDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

@Isolated("restores the process-global Exposed database registry")
class OwnedH2TestDatabaseTest {
    @Test
    fun `failed setup unregisters only its database and preserves the original failure`() {
        val previousDatabase = TransactionManager.defaultDatabase
        val previousManager = previousDatabase?.let { TransactionManager.managerFor(it) }
        val controlSource = source("control")
        val controlDatabase = Database.connect(controlSource)
        val controlManager = requireNotNull(TransactionManager.managerFor(controlDatabase))
        TransactionManager.defaultDatabase = controlDatabase
        TransactionManager.resetCurrent(controlManager)
        try {
            controlSource.connection.use { connection ->
                connection.createStatement().use { it.execute("CREATE TABLE fixture_control (record_value INTEGER)") }
            }
            val expectedFailures =
                listOf(
                    IllegalStateException("synthetic setup failure after registration"),
                    CancellationException("synthetic setup cancellation after registration"),
                )
            for (expected in expectedFailures) {
                val ownedSource = source("failed")
                lateinit var ownedDatabase: Database
                val actual =
                    assertFailsWith<RuntimeException> {
                        prepareOwnedH2Database(ownedSource) { register ->
                            ownedDatabase = Database.connect(ownedSource).also(register)
                            ownedSource.connection.use { connection ->
                                connection.createStatement().use { statement ->
                                    statement.execute("CREATE TABLE owned_setup (record_value INTEGER)")
                                }
                            }
                            throw expected
                        }
                    }
                assertSame(expected, actual)
                assertEquals(expected.message, actual.message)
                assertOwnedH2DatabaseClosed(ownedSource.getURL())
                assertNull(TransactionManager.managerFor(ownedDatabase))
                assertSame(controlManager, TransactionManager.managerFor(controlDatabase))
                assertSame(controlDatabase, TransactionManager.defaultDatabase)
                transaction(controlDatabase) {
                    exec("SELECT COUNT(*) FROM fixture_control") { rows ->
                        rows.next()
                        assertEquals(0, rows.getInt(1))
                    }
                }
            }
        } finally {
            TransactionManager.defaultDatabase = previousDatabase
            TransactionManager.resetCurrent(previousManager)
            shutdownOwnedH2Database(controlSource, controlDatabase)
        }
    }

    private fun source(suffix: String) =
        JdbcDataSource().apply {
            setURL("jdbc:h2:mem:owned_fixture_${suffix}_${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
            user = "sa"
            password = ""
        }
}
