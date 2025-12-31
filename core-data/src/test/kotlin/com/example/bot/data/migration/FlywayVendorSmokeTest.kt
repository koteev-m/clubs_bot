package com.example.bot.data.migration

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import testing.RequiresDocker
import java.time.OffsetDateTime
import java.math.BigDecimal
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import java.sql.Types
import java.util.UUID

@RequiresDocker
@Tag("it")
class FlywayVendorSmokeTest {
    private val resourcesToClose = mutableListOf<AutoCloseable>()
    private val baseTime = OffsetDateTime.parse("2024-01-01T12:00:00+00:00")

    companion object {
        @JvmStatic
        @BeforeAll
        fun assumeDocker() {
            val dockerAvailable =
                try {
                    DockerClientFactory.instance().client()
                    true
                } catch (_: Throwable) {
                    false
                }
            assumeTrue(dockerAvailable, "Docker is not available on this host; skipping IT.")
        }
    }

    @AfterEach
    fun tearDown() {
        resourcesToClose.reversed().forEach { runCatching { it.close() } }
        resourcesToClose.clear()
    }

    @Test
    fun `h2 migrations run with json defaults`() {
        val jdbcUrl =
            "jdbc:h2:mem:flyway-h2;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false"
        migrate(jdbcUrl, "sa", "", "org.h2.Driver", "h2")
        withConnection { connection ->
            assertUuidDefault(connection)
            assertJsonColumnType(connection, expectedType = "json")
            assertGuestListLimitRemoved(connection)
            assertCheckinsSchema(connection)
            assertGuestListStatuses(connection)
        }
    }

    @Test
    fun `postgres migrations run with jsonb defaults`() {
        val container = PostgreSQLContainer<Nothing>("postgres:16-alpine")
        container.start()
        resourcesToClose += AutoCloseable { container.stop() }

        migrate(container.jdbcUrl, container.username, container.password, container.driverClassName, "postgresql")
        withConnection { connection ->
            assertUuidDefault(connection)
            assertJsonColumnType(connection, expectedType = "jsonb")
            assertGuestListLimitRemovedPostgres(connection)
            assertCheckinsSchemaPostgres(connection)
            assertCheckinsConstraintEnforcedPostgres(connection)
            assertGuestListStatuses(connection)
        }
    }

    private fun migrate(
        jdbcUrl: String,
        user: String,
        password: String,
        driver: String,
        vendor: String,
    ) {
        val flyway =
            Flyway
                .configure()
                .dataSource(jdbcUrl, user, password)
                .locations("classpath:db/migration/common", "classpath:db/migration/$vendor")
                .cleanDisabled(false)
                .load()
        flyway.clean()
        flyway.migrate()

        val dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    this.jdbcUrl = jdbcUrl
                    username = user
                    this.password = password
                    driverClassName = driver
                    maximumPoolSize = 2
                },
            )
        resourcesToClose += dataSource
    }

    private inline fun withConnection(block: (Connection) -> Unit) {
        val dataSource = resourcesToClose.filterIsInstance<HikariDataSource>().last()
        dataSource.connection.use(block)
    }

    private fun assertUuidDefault(connection: Connection) {
        val key = "smoke-" + UUID.randomUUID()
        connection
            .prepareStatement(
                "INSERT INTO payments (provider, currency, amount, status, idempotency_key) VALUES (?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, "stripe")
                statement.setString(2, "USD")
                statement.setBigDecimal(3, BigDecimal("10.00"))
                statement.setString(4, "INITIATED")
                statement.setString(5, key)
                statement.executeUpdate()
            }

        connection.prepareStatement("SELECT id FROM payments WHERE idempotency_key = ?").use { statement ->
            statement.setString(1, key)
            statement.executeQuery().use { rs ->
                require(rs.next()) { "Payment row was not inserted" }
                val value = rs.getString(1)
                UUID.fromString(value)
            }
        }
    }

    private fun assertJsonColumnType(
        connection: Connection,
        expectedType: String,
    ) {
        val metadata = connection.metaData
        val schemaPattern =
            connection.schema ?: when (metadata.databaseProductName.lowercase()) {
                "postgresql" -> "public"
                else -> null
            }
        metadata.getColumns(connection.catalog, schemaPattern, "notifications_outbox", "payload").use { columns ->
            require(columns.next()) { "notifications_outbox.payload column not found" }
            val typeName = columns.getString("TYPE_NAME").lowercase()
            check(typeName == expectedType) {
                "Expected JSON column type $expectedType but was $typeName"
            }
        }
    }

    private fun assertGuestListLimitRemoved(connection: Connection) {
        connection.prepareStatement(
            """
            SELECT 1
            FROM INFORMATION_SCHEMA.COLUMNS
            WHERE lower(table_name) = 'guest_lists' AND lower(column_name) = 'limit'
            """,
        ).use { statement ->
            statement.executeQuery().use { rs ->
                check(!rs.next()) { "legacy column guest_lists.limit should be absent" }
            }
        }

        connection.prepareStatement(
            """
            SELECT is_nullable, column_default
            FROM INFORMATION_SCHEMA.COLUMNS
            WHERE lower(table_name) = 'guest_lists' AND lower(column_name) = 'capacity'
            """,
        ).use { statement ->
            statement.executeQuery().use { rs ->
                check(rs.next()) { "guest_lists.capacity column not found" }
                val nullable = rs.getString("IS_NULLABLE").equals("YES", ignoreCase = true)
                check(!nullable) { "guest_lists.capacity must be NOT NULL" }
            }
        }
    }

    private fun assertCheckinsSchema(connection: Connection) {
        connection.prepareStatement(
            """
            SELECT data_type, character_maximum_length
            FROM INFORMATION_SCHEMA.COLUMNS
            WHERE lower(table_name) = 'checkins' AND lower(column_name) = 'subject_id'
            """,
        ).use { statement ->
            statement.executeQuery().use { rs ->
                check(rs.next()) { "checkins.subject_id column not found" }
                val type = rs.getString("DATA_TYPE")
                check(type.equals("VARCHAR", ignoreCase = true)) {
                    "checkins.subject_id must be VARCHAR but was $type"
                }
                val length = rs.getInt("CHARACTER_MAXIMUM_LENGTH")
                check(length >= 64) { "checkins.subject_id length expected >= 64 but was $length" }
            }
        }

        connection.prepareStatement(
            """
            SELECT check_clause
            FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS
            WHERE lower(constraint_name) = 'checkins_deny_reason_consistency'
            """,
        ).use { statement ->
            statement.executeQuery().use { rs ->
                check(rs.next()) { "checkins_deny_reason_consistency constraint missing" }
            }
        }

        assertCheckinsConstraintEnforced(connection)
    }

    private fun assertCheckinsConstraintEnforced(connection: Connection) {
        val nonDeniedWithReason =
            """
            INSERT INTO checkins (subject_type, subject_id, method, result_status, deny_reason)
            VALUES ('GUEST_LIST_ENTRY', '1', 'QR', 'ARRIVED', 'x')
            """.trimIndent()

        assertThrows<SQLException> {
            connection.createStatement().use { statement -> statement.executeUpdate(nonDeniedWithReason) }
        }

        val deniedWithoutReason =
            """
            INSERT INTO checkins (subject_type, subject_id, method, result_status, deny_reason)
            VALUES ('GUEST_LIST_ENTRY', '2', 'QR', 'DENIED', NULL)
            """.trimIndent()

        assertThrows<SQLException> {
            connection.createStatement().use { statement -> statement.executeUpdate(deniedWithoutReason) }
        }
    }

    private fun assertGuestListStatuses(connection: Connection) {
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = true
        try {
            val fixture = insertBaseFixture(connection)

            insertGuestList(connection, fixture, status = "CANCELLED")

            val guestListForEntries = insertGuestList(connection, fixture, status = "ACTIVE")
            insertGuestListEntry(connection, guestListForEntries, status = "ADDED")
            insertGuestListEntry(connection, guestListForEntries, status = "CONFIRMED")

            assertThrows<SQLException> {
                insertGuestListEntry(connection, guestListForEntries, status = "BROKEN_STATUS")
            }

            assertThrows<SQLException> {
                insertGuestList(connection, fixture, status = "BROKEN_STATUS")
            }
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }

    private fun insertBaseFixture(connection: Connection): GuestListFixture {
        val userId = insertUser(connection)
        val clubId = insertClub(connection)
        val eventId = insertEvent(connection, clubId)
        return GuestListFixture(userId = userId, clubId = clubId, eventId = eventId)
    }

    private fun insertUser(connection: Connection): Long =
        connection.prepareStatement(
            """
            INSERT INTO users (username, display_name, telegram_user_id, phone_e164)
            VALUES (?, ?, NULL, NULL)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setString(1, "smoke_user")
            statement.setString(2, "Smoke User")
            statement.executeUpdate()

            statement.generatedKeys.use { keys ->
                check(keys.next()) { "User id not returned" }
                keys.getLong(1)
            }
        }

    private fun insertClub(connection: Connection): Long =
        connection.prepareStatement(
            """
            INSERT INTO clubs (
                name, description, timezone, admin_channel_id, bookings_topic_id, checkin_topic_id, qa_topic_id
            ) VALUES (?, NULL, ?, NULL, NULL, NULL, NULL)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setString(1, "Smoke Club")
            statement.setString(2, "Europe/Moscow")
            statement.executeUpdate()

            statement.generatedKeys.use { keys ->
                check(keys.next()) { "Club id not returned" }
                keys.getLong(1)
            }
        }

    private fun insertEvent(connection: Connection, clubId: Long): Long =
        connection.prepareStatement(
            """
            INSERT INTO events (club_id, title, start_at, end_at, is_special, poster_url)
            VALUES (?, ?, ?, ?, ?, NULL)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, clubId)
            statement.setString(2, "Smoke Event")
            statement.setObject(3, baseTime)
            statement.setObject(4, baseTime.plusHours(2))
            statement.setBoolean(5, false)
            statement.executeUpdate()

            statement.generatedKeys.use { keys ->
                check(keys.next()) { "Event id not returned" }
                keys.getLong(1)
            }
        }

    private fun insertGuestList(connection: Connection, fixture: GuestListFixture, status: String): Long =
        connection.prepareStatement(
            """
            INSERT INTO guest_lists (
                club_id, event_id, owner_type, owner_user_id, title, capacity,
                arrival_window_start, arrival_window_end, status
            ) VALUES (?, ?, 'ADMIN', ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS,
        ).use { statement ->
            statement.setLong(1, fixture.clubId)
            statement.setLong(2, fixture.eventId)
            statement.setLong(3, fixture.userId)
            statement.setString(4, "Smoke list ${UUID.randomUUID()}")
            statement.setInt(5, 10)
            statement.setNull(6, Types.TIMESTAMP_WITH_TIMEZONE)
            statement.setNull(7, Types.TIMESTAMP_WITH_TIMEZONE)
            statement.setString(8, status)
            statement.executeUpdate()

            statement.generatedKeys.use { keys ->
                check(keys.next()) { "Guest list id not returned" }
                keys.getLong(1)
            }
        }

    private fun insertGuestListEntry(connection: Connection, guestListId: Long, status: String) {
        connection.prepareStatement(
            """
            INSERT INTO guest_list_entries (guest_list_id, full_name, display_name, status)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, guestListId)
            statement.setString(2, "Smoke Guest")
            statement.setString(3, "Smoke Guest")
            statement.setString(4, status)
            statement.executeUpdate()
        }
    }

    private data class GuestListFixture(
        val userId: Long,
        val clubId: Long,
        val eventId: Long,
    )

    private fun assertGuestListLimitRemovedPostgres(connection: Connection) {
        connection.prepareStatement(
            """
            SELECT 1
            FROM information_schema.columns
            WHERE table_schema = current_schema()
              AND table_name = 'guest_lists'
              AND column_name = 'limit'
            """,
        ).use { statement ->
            statement.executeQuery().use { rs ->
                check(!rs.next()) { "legacy column guest_lists.limit should be absent" }
            }
        }

        connection.prepareStatement(
            """
            SELECT is_nullable
            FROM information_schema.columns
            WHERE table_schema = current_schema()
              AND table_name = 'guest_lists'
              AND column_name = 'capacity'
            """,
        ).use { statement ->
            statement.executeQuery().use { rs ->
                check(rs.next()) { "guest_lists.capacity column not found" }
                val nullable = rs.getString("is_nullable").equals("YES", ignoreCase = true)
                check(!nullable) { "guest_lists.capacity must be NOT NULL" }
            }
        }
    }

    private fun assertCheckinsSchemaPostgres(connection: Connection) {
        connection.prepareStatement(
            """
            SELECT data_type
            FROM information_schema.columns
            WHERE table_schema = current_schema()
              AND table_name = 'checkins'
              AND column_name = 'subject_id'
            """,
        ).use { statement ->
            statement.executeQuery().use { rs ->
                check(rs.next()) { "checkins.subject_id column not found" }
                val type = rs.getString("data_type")
                check(type.equals("text", ignoreCase = true)) { "checkins.subject_id must be TEXT but was $type" }
            }
        }

        connection.prepareStatement(
            """
            SELECT 1
            FROM information_schema.check_constraints
            WHERE constraint_schema = current_schema()
              AND constraint_name = 'checkins_deny_reason_consistency'
            """,
        ).use { statement ->
            statement.executeQuery().use { rs ->
                check(rs.next()) { "checkins_deny_reason_consistency constraint missing" }
            }
        }
    }

    private fun assertCheckinsConstraintEnforcedPostgres(connection: Connection) {
        val previousAutoCommit = connection.autoCommit
        connection.autoCommit = true
        try {
            val nonDeniedWithReason =
                """
                INSERT INTO checkins (subject_type, subject_id, method, result_status, deny_reason, occurred_at)
                VALUES ('GUEST_LIST_ENTRY', '1', 'QR', 'ARRIVED', 'x', now())
                """.trimIndent()
            assertThrows<SQLException> {
                connection.createStatement().use { statement -> statement.executeUpdate(nonDeniedWithReason) }
            }

            val deniedWithoutReason =
                """
                INSERT INTO checkins (subject_type, subject_id, method, result_status, deny_reason, occurred_at)
                VALUES ('GUEST_LIST_ENTRY', '2', 'QR', 'DENIED', NULL, now())
                """.trimIndent()
            assertThrows<SQLException> {
                connection.createStatement().use { statement -> statement.executeUpdate(deniedWithoutReason) }
            }
        } finally {
            connection.autoCommit = previousAutoCommit
        }
    }
}
