package com.example.bot.data.support

import com.example.bot.support.TicketSenderType
import com.example.bot.support.TicketStatus
import com.example.bot.support.TicketTopic
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Statement
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class SupportTicketStatusMigrationH2Test {
    @Test
    fun `V057 preserves legacy support statuses and allows NEW`() {
        val jdbcUrl =
            "jdbc:h2:mem:support-status-upgrade-${UUID.randomUUID()};" +
                "MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false"
        val locations = arrayOf("classpath:db/migration/common", "classpath:db/migration/h2")
        val legacyFlyway =
            Flyway
                .configure()
                .dataSource(jdbcUrl, H2_USER, H2_PASSWORD)
                .locations(*locations)
                .target(LEGACY_VERSION)
                .cleanDisabled(false)
                .load()
        legacyFlyway.clean()
        legacyFlyway.migrate()

        DriverManager.getConnection(jdbcUrl, H2_USER, H2_PASSWORD).use { connection ->
            val fixture = insertLegacyFixture(connection)
            val rowsBeforeMigration = readLegacyRows(connection, fixture)
            val messageBeforeMigration = readMessage(connection, fixture.messageId)
            assertEquals(fixture.openedTicketId, messageBeforeMigration.ticketId)

            val latestFlyway =
                Flyway
                    .configure()
                    .dataSource(jdbcUrl, H2_USER, H2_PASSWORD)
                    .locations(*locations)
                    .target(EXPECTED_VERSION)
                    .load()
            assertEquals(1, latestFlyway.migrate().migrationsExecuted)
            val currentVersion =
                latestFlyway
                    .info()
                    .current()
                    ?.version
                    ?.toString()
            assertEquals(EXPECTED_VERSION, currentVersion)

            assertEquals(rowsBeforeMigration, readLegacyRows(connection, fixture))
            assertEquals(messageBeforeMigration, readMessage(connection, fixture.messageId))
            assertStatusColumnRemainsNotNull(connection)
            assertClubStatusUpdatedIndexRemains(connection)

            val allowedStatuses =
                listOf(
                    TicketStatus.NEW,
                    TicketStatus.OPENED,
                    TicketStatus.IN_PROGRESS,
                    TicketStatus.ANSWERED,
                    TicketStatus.CLOSED,
                )
            allowedStatuses.forEachIndexed { index, status ->
                val ticketId =
                    insertTicket(
                        connection = connection,
                        clubId = fixture.clubId,
                        userId = fixture.userId,
                        topic = TicketTopic.OTHER.wire,
                        status = status.wire,
                        createdAt = FIXED_INSTANT.plusSeconds(120L + index),
                    )
                assertEquals(status.wire, readTicketStatus(connection, ticketId))
            }

            assertThrows(SQLException::class.java) {
                insertTicket(
                    connection = connection,
                    clubId = fixture.clubId,
                    userId = fixture.userId,
                    topic = TicketTopic.OTHER.wire,
                    status = "unsupported",
                    createdAt = FIXED_INSTANT.plusSeconds(180),
                )
            }
            assertThrows(SQLException::class.java) {
                insertTicket(
                    connection = connection,
                    clubId = fixture.clubId,
                    userId = fixture.userId,
                    topic = TicketTopic.OTHER.wire,
                    status = null,
                    createdAt = FIXED_INSTANT.plusSeconds(181),
                )
            }
            assertThrows(SQLException::class.java) {
                insertTicket(
                    connection = connection,
                    clubId = fixture.clubId,
                    userId = fixture.userId,
                    topic = "unsupported",
                    status = TicketStatus.OPENED.wire,
                    createdAt = FIXED_INSTANT.plusSeconds(182),
                )
            }
            assertThrows(SQLException::class.java) {
                insertMessage(
                    connection = connection,
                    ticketId = Long.MAX_VALUE,
                    senderType = TicketSenderType.GUEST.wire,
                    text = "orphan message must fail",
                    createdAt = FIXED_INSTANT.plusSeconds(183),
                )
            }
        }
    }

    private fun insertLegacyFixture(connection: Connection): LegacyFixture {
        val clubId =
            connection
                .prepareStatement(
                    """
                    INSERT INTO clubs (name, description, timezone, admin_channel_id)
                    VALUES (?, NULL, 'Europe/Moscow', NULL)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setString(1, "H2 support migration club ${UUID.randomUUID()}")
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        assertTrue(keys.next())
                        keys.getLong(1)
                    }
                }
        val userId =
            connection
                .prepareStatement(
                    """
                    INSERT INTO users (telegram_user_id, username, display_name, phone_e164)
                    VALUES (?, 'support_h2_migration_guest', 'Support H2 Migration Guest', NULL)
                    """.trimIndent(),
                    Statement.RETURN_GENERATED_KEYS,
                ).use { statement ->
                    statement.setLong(1, 880_057_002L)
                    statement.executeUpdate()
                    statement.generatedKeys.use { keys ->
                        assertTrue(keys.next())
                        keys.getLong(1)
                    }
                }
        val openedTicketId =
            insertTicket(
                connection = connection,
                clubId = clubId,
                userId = userId,
                topic = TicketTopic.ADDRESS.wire,
                status = TicketStatus.OPENED.wire,
                createdAt = FIXED_INSTANT,
            )
        val answeredTicketId =
            insertTicket(
                connection = connection,
                clubId = clubId,
                userId = userId,
                topic = TicketTopic.COMPLAINT.wire,
                status = TicketStatus.ANSWERED.wire,
                createdAt = FIXED_INSTANT.plusSeconds(60),
            )
        val messageId =
            insertMessage(
                connection = connection,
                ticketId = openedTicketId,
                senderType = TicketSenderType.GUEST.wire,
                text = LEGACY_MESSAGE_TEXT,
                createdAt = FIXED_INSTANT.plusSeconds(90),
            )
        return LegacyFixture(
            clubId = clubId,
            userId = userId,
            openedTicketId = openedTicketId,
            answeredTicketId = answeredTicketId,
            messageId = messageId,
        )
    }

    private fun insertTicket(
        connection: Connection,
        clubId: Long,
        userId: Long,
        topic: String,
        status: String?,
        createdAt: Instant,
    ): Long =
        connection
            .prepareStatement(
                """
                INSERT INTO tickets (
                    club_id, user_id, booking_id, list_entry_id, topic, status,
                    created_at, updated_at, last_agent_id, resolution_rating
                ) VALUES (?, ?, NULL, NULL, ?, ?, ?, ?, NULL, NULL)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, clubId)
                statement.setLong(2, userId)
                statement.setString(3, topic)
                statement.setString(4, status)
                statement.setObject(5, createdAt.atOffset(ZoneOffset.UTC))
                statement.setObject(6, createdAt.plusSeconds(30).atOffset(ZoneOffset.UTC))
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    assertTrue(keys.next())
                    keys.getLong(1)
                }
            }

    private fun insertMessage(
        connection: Connection,
        ticketId: Long,
        senderType: String,
        text: String,
        createdAt: Instant,
    ): Long =
        connection
            .prepareStatement(
                """
                INSERT INTO ticket_messages (ticket_id, sender_type, text, attachments, created_at)
                VALUES (?, ?, ?, NULL, ?)
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS,
            ).use { statement ->
                statement.setLong(1, ticketId)
                statement.setString(2, senderType)
                statement.setString(3, text)
                statement.setObject(4, createdAt.atOffset(ZoneOffset.UTC))
                statement.executeUpdate()
                statement.generatedKeys.use { keys ->
                    assertTrue(keys.next())
                    keys.getLong(1)
                }
            }

    private fun readTicketStatus(
        connection: Connection,
        ticketId: Long,
    ): String =
        connection
            .prepareStatement("SELECT status FROM tickets WHERE id = ?")
            .use { statement ->
                statement.setLong(1, ticketId)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    resultSet.getString(1).also { assertFalse(resultSet.next()) }
                }
            }

    private fun readMessage(
        connection: Connection,
        messageId: Long,
    ): LegacyMessageRow =
        connection
            .prepareStatement(
                """
                SELECT id, ticket_id, sender_type, text, attachments, created_at
                FROM ticket_messages
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, messageId)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    LegacyMessageRow(
                        id = resultSet.getLong("id"),
                        ticketId = resultSet.getLong("ticket_id"),
                        senderType = resultSet.getString("sender_type"),
                        text = resultSet.getString("text"),
                        attachments = resultSet.getString("attachments"),
                        createdAt = resultSet.getObject("created_at").toString(),
                    ).also { assertFalse(resultSet.next()) }
                }
            }

    private fun readLegacyRows(
        connection: Connection,
        fixture: LegacyFixture,
    ): List<LegacyTicketRow> =
        connection
            .prepareStatement(
                """
                SELECT id, club_id, user_id, topic, status, created_at, updated_at
                FROM tickets
                WHERE id IN (?, ?)
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, fixture.openedTicketId)
                statement.setLong(2, fixture.answeredTicketId)
                statement.executeQuery().use { resultSet ->
                    buildList {
                        while (resultSet.next()) {
                            add(
                                LegacyTicketRow(
                                    id = resultSet.getLong("id"),
                                    clubId = resultSet.getLong("club_id"),
                                    userId = resultSet.getLong("user_id"),
                                    topic = resultSet.getString("topic"),
                                    status = resultSet.getString("status"),
                                    createdAt = resultSet.getObject("created_at").toString(),
                                    updatedAt = resultSet.getObject("updated_at").toString(),
                                ),
                            )
                        }
                    }
                }
            }

    private fun assertStatusColumnRemainsNotNull(connection: Connection) {
        connection
            .prepareStatement(
                """
                SELECT IS_NULLABLE, COLUMN_DEFAULT
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE lower(TABLE_NAME) = 'tickets'
                  AND lower(COLUMN_NAME) = 'status'
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    assertEquals("NO", resultSet.getString("IS_NULLABLE"))
                    assertNull(resultSet.getString("COLUMN_DEFAULT"))
                    assertFalse(resultSet.next())
                }
            }
    }

    private fun assertClubStatusUpdatedIndexRemains(connection: Connection) {
        val indexColumns =
            connection
                .prepareStatement(
                    """
                    SELECT COLUMN_NAME, ORDERING_SPECIFICATION
                    FROM INFORMATION_SCHEMA.INDEX_COLUMNS
                    WHERE lower(INDEX_NAME) = 'idx_tickets_club_status_updated_at'
                    ORDER BY ORDINAL_POSITION
                    """.trimIndent(),
                ).use { statement ->
                    statement.executeQuery().use { resultSet ->
                        buildList {
                            while (resultSet.next()) {
                                add(
                                    resultSet.getString("COLUMN_NAME") to
                                        resultSet.getString("ORDERING_SPECIFICATION"),
                                )
                            }
                        }
                    }
                }
        assertEquals(
            listOf(
                "club_id" to "ASC",
                "status" to "ASC",
                "updated_at" to "DESC",
            ),
            indexColumns,
        )
    }

    private data class LegacyFixture(
        val clubId: Long,
        val userId: Long,
        val openedTicketId: Long,
        val answeredTicketId: Long,
        val messageId: Long,
    )

    private data class LegacyTicketRow(
        val id: Long,
        val clubId: Long,
        val userId: Long,
        val topic: String,
        val status: String,
        val createdAt: String,
        val updatedAt: String,
    )

    private data class LegacyMessageRow(
        val id: Long,
        val ticketId: Long,
        val senderType: String,
        val text: String,
        val attachments: String?,
        val createdAt: String,
    )

    companion object {
        private const val H2_USER = "sa"
        private const val H2_PASSWORD = ""
        private const val LEGACY_VERSION = "56"
        private const val EXPECTED_VERSION = "057"
        private const val LEGACY_MESSAGE_TEXT = "Legacy message survives V057"
        private val FIXED_INSTANT = Instant.parse("2024-05-01T10:00:00Z")
    }
}
