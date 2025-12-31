package com.example.bot.data.migration

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.sql.Connection
import java.sql.SQLException
import java.sql.Statement
import java.sql.Types
import java.time.OffsetDateTime
import java.util.UUID

internal fun migrateAndTrack(
    jdbcUrl: String,
    user: String,
    password: String,
    driver: String,
    vendor: String,
    resourcesToClose: MutableList<AutoCloseable>,
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

internal inline fun withConnection(
    resourcesToClose: MutableList<AutoCloseable>,
    block: (Connection) -> Unit,
) {
    val dataSource = resourcesToClose.filterIsInstance<HikariDataSource>().last()
    dataSource.connection.use(block)
}

internal fun assertUuidDefault(connection: Connection) {
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

internal fun assertJsonColumnType(
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

internal fun assertGuestListLimitRemoved(connection: Connection) {
    connection.prepareStatement(
        """
        SELECT 1
        FROM INFORMATION_SCHEMA.COLUMNS
        WHERE lower(TABLE_NAME) = 'guest_lists' AND lower(COLUMN_NAME) = 'limit'
        """,
    ).use { statement ->
        statement.executeQuery().use { rs ->
            check(!rs.next()) { "legacy column guest_lists.limit should be absent" }
        }
    }

    connection.prepareStatement(
        """
        SELECT IS_NULLABLE, COLUMN_DEFAULT
        FROM INFORMATION_SCHEMA.COLUMNS
        WHERE lower(TABLE_NAME) = 'guest_lists' AND lower(COLUMN_NAME) = 'capacity'
        """,
    ).use { statement ->
        statement.executeQuery().use { rs ->
            check(rs.next()) { "guest_lists.capacity column not found" }
            val nullable = rs.getString("IS_NULLABLE").equals("YES", ignoreCase = true)
            check(!nullable) { "guest_lists.capacity must be NOT NULL" }
        }
    }
}

internal fun assertCheckinsSchema(connection: Connection) {
    connection.prepareStatement(
        """
        SELECT DATA_TYPE, CHARACTER_MAXIMUM_LENGTH
        FROM INFORMATION_SCHEMA.COLUMNS
        WHERE lower(TABLE_NAME) = 'checkins' AND lower(COLUMN_NAME) = 'subject_id'
        """,
        ).use { statement ->
            statement.executeQuery().use { rs ->
                check(rs.next()) { "checkins.subject_id column not found" }
                val type = rs.getString("DATA_TYPE")
                val isVarchar = type.equals("VARCHAR", ignoreCase = true) ||
                    type.equals("CHARACTER VARYING", ignoreCase = true)
                check(isVarchar) { "checkins.subject_id must be VARCHAR but was $type" }
                val length = rs.getInt("CHARACTER_MAXIMUM_LENGTH")
                check(length >= 64) { "checkins.subject_id length expected >= 64 but was $length" }
            }
        }

    connection.prepareStatement(
        """
        SELECT CHECK_CLAUSE
        FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS
        WHERE lower(CONSTRAINT_NAME) = 'checkins_deny_reason_consistency'
        """,
    ).use { statement ->
        statement.executeQuery().use { rs ->
            check(rs.next()) { "checkins_deny_reason_consistency constraint missing" }
        }
    }

    assertCheckinsConstraintEnforced(connection)
}

internal fun assertCheckinsConstraintEnforced(connection: Connection) {
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

internal fun assertGuestListStatuses(connection: Connection, baseTime: OffsetDateTime) {
    val previousAutoCommit = connection.autoCommit
    connection.autoCommit = true
    try {
        val fixture = insertBaseFixture(connection, baseTime)

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

internal fun insertBaseFixture(connection: Connection, baseTime: OffsetDateTime): GuestListFixture {
    val userId = insertUser(connection)
    val clubId = insertClub(connection)
    val eventId = insertEvent(connection, clubId, baseTime)
    return GuestListFixture(userId = userId, clubId = clubId, eventId = eventId)
}

internal fun insertUser(connection: Connection): Long =
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

internal fun insertClub(connection: Connection): Long =
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

internal fun insertEvent(connection: Connection, clubId: Long, baseTime: OffsetDateTime): Long =
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

internal fun insertGuestList(connection: Connection, fixture: GuestListFixture, status: String): Long =
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

internal fun insertGuestListEntry(connection: Connection, guestListId: Long, status: String) {
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

internal data class GuestListFixture(
    val userId: Long,
    val clubId: Long,
    val eventId: Long,
)

internal fun assertGuestListLimitRemovedPostgres(connection: Connection) {
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

internal fun assertCheckinsSchemaPostgres(connection: Connection) {
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

internal fun assertCheckinsConstraintEnforcedPostgres(connection: Connection) {
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
