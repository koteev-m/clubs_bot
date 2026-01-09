package com.example.bot.data.migration

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.time.OffsetDateTime

class FlywayH2SmokeTest {
    private val resourcesToClose = mutableListOf<AutoCloseable>()
    private val baseTime = OffsetDateTime.parse("2024-01-01T12:00:00+00:00")

    @AfterEach
    fun tearDown() {
        resourcesToClose.reversed().forEach { runCatching { it.close() } }
        resourcesToClose.clear()
    }

    @Test
    fun `h2 migrations run with json defaults`() {
        val jdbcUrl =
            "jdbc:h2:mem:flyway-h2;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false"
        migrateAndTrack(jdbcUrl, "sa", "", "org.h2.Driver", "h2", resourcesToClose)

        withConnection(resourcesToClose) { connection ->
            assertUuidDefault(connection)
            assertJsonColumnType(connection, expectedType = "json")
            assertGuestListLimitRemoved(connection)
            assertCheckinsSchema(connection)
            assertCheckinsConstraintEnforced(connection)
            assertGuestListStatusConstraintH2(connection)
            assertGuestListStatuses(connection, baseTime)
        }
    }

    @Test
    fun `h2 migrations create support tables`() {
        val jdbcUrl =
            "jdbc:h2:mem:flyway-h2-support;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false"
        migrateAndTrack(jdbcUrl, "sa", "", "org.h2.Driver", "h2", resourcesToClose)

        withConnection(resourcesToClose) { connection ->
            assertTableExists(connection, "tickets")
            assertTableExists(connection, "ticket_messages")
        }
    }

    private fun assertTableExists(connection: Connection, tableName: String) {
        connection.prepareStatement(
            """
            SELECT 1
            FROM INFORMATION_SCHEMA.TABLES
            WHERE lower(TABLE_NAME) = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, tableName.lowercase())
            statement.executeQuery().use { resultSet ->
                check(resultSet.next()) { "Expected table $tableName to exist" }
            }
        }
    }
}
