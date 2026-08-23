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

    @Test
    fun `V059 preserves every V058 status and adds only RESOLVED`() {
        val jdbcUrl =
            "jdbc:h2:mem:support-resolved-status-upgrade-${UUID.randomUUID()};" +
                "MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false"
        val locations = arrayOf("classpath:db/migration/common", "classpath:db/migration/h2")
        val v058Flyway =
            Flyway
                .configure()
                .dataSource(jdbcUrl, H2_USER, H2_PASSWORD)
                .locations(*locations)
                .target(PRE_RESOLVED_VERSION)
                .cleanDisabled(false)
                .load()
        v058Flyway.clean()
        v058Flyway.migrate()
        assertEquals(
            EXPECTED_PRE_RESOLVED_VERSION,
            v058Flyway
                .info()
                .current()
                ?.version
                ?.toString(),
        )

        DriverManager.getConnection(jdbcUrl, H2_USER, H2_PASSWORD).use { connection ->
            val fixture = insertLegacyFixture(connection)
            val ticketIds =
                listOf(
                    TicketStatus.NEW.wire to
                        insertTicket(
                            connection = connection,
                            clubId = fixture.clubId,
                            userId = fixture.userId,
                            topic = TicketTopic.OTHER.wire,
                            status = TicketStatus.NEW.wire,
                            createdAt = FIXED_INSTANT.plusSeconds(120),
                        ),
                    TicketStatus.OPENED.wire to fixture.openedTicketId,
                    TicketStatus.IN_PROGRESS.wire to
                        insertTicket(
                            connection = connection,
                            clubId = fixture.clubId,
                            userId = fixture.userId,
                            topic = TicketTopic.INVITE.wire,
                            status = TicketStatus.IN_PROGRESS.wire,
                            createdAt = FIXED_INSTANT.plusSeconds(180),
                        ),
                    TicketStatus.ANSWERED.wire to fixture.answeredTicketId,
                    TicketStatus.CLOSED.wire to
                        insertTicket(
                            connection = connection,
                            clubId = fixture.clubId,
                            userId = fixture.userId,
                            topic = TicketTopic.BOOKING.wire,
                            status = TicketStatus.CLOSED.wire,
                            createdAt = FIXED_INSTANT.plusSeconds(240),
                        ),
                )
            val rowsBeforeMigration = ticketIds.map { (_, ticketId) -> readTicketRow(connection, ticketId) }
            val messageBeforeMigration = readMessage(connection, fixture.messageId)

            val v059Flyway =
                Flyway
                    .configure()
                    .dataSource(jdbcUrl, H2_USER, H2_PASSWORD)
                    .locations(*locations)
                    .target(RESOLVED_VERSION)
                    .load()
            assertEquals(1, v059Flyway.migrate().migrationsExecuted)
            assertEquals(
                EXPECTED_RESOLVED_VERSION,
                v059Flyway
                    .info()
                    .current()
                    ?.version
                    ?.toString(),
            )

            assertEquals(
                rowsBeforeMigration,
                ticketIds.map { (_, ticketId) -> readTicketRow(connection, ticketId) },
            )
            assertEquals(messageBeforeMigration, readMessage(connection, fixture.messageId))
            assertEquals(
                ticketIds.map { it.first },
                ticketIds.map { (_, ticketId) -> readTicketStatus(connection, ticketId) },
            )
            assertStatusConstraintAllowsExactly(connection, V059_ALLOWED_STATUSES)
            assertStatusColumnRemainsNotNull(connection)
            assertClubStatusUpdatedIndexRemains(connection)
            assertSupportIndexInventoryRemains(connection)

            val resolvedTicketId =
                insertTicket(
                    connection = connection,
                    clubId = fixture.clubId,
                    userId = fixture.userId,
                    topic = TicketTopic.OTHER.wire,
                    status = RESOLVED_STATUS,
                    createdAt = FIXED_INSTANT.plusSeconds(300),
                )
            assertEquals(RESOLVED_STATUS, readTicketStatus(connection, resolvedTicketId))

            listOf(WAITING_STATUS, "unsupported").forEachIndexed { index, rejectedStatus ->
                assertThrows(SQLException::class.java) {
                    insertTicket(
                        connection = connection,
                        clubId = fixture.clubId,
                        userId = fixture.userId,
                        topic = TicketTopic.OTHER.wire,
                        status = rejectedStatus,
                        createdAt = FIXED_INSTANT.plusSeconds(360L + index),
                    )
                }
            }
            assertThrows(SQLException::class.java) {
                insertTicket(
                    connection = connection,
                    clubId = fixture.clubId,
                    userId = fixture.userId,
                    topic = TicketTopic.OTHER.wire,
                    status = null,
                    createdAt = FIXED_INSTANT.plusSeconds(362),
                )
            }
            assertThrows(SQLException::class.java) {
                insertTicket(
                    connection = connection,
                    clubId = fixture.clubId,
                    userId = fixture.userId,
                    topic = "unsupported",
                    status = RESOLVED_STATUS,
                    createdAt = FIXED_INSTANT.plusSeconds(363),
                )
            }
            assertThrows(SQLException::class.java) {
                insertTicket(
                    connection = connection,
                    clubId = Long.MAX_VALUE,
                    userId = fixture.userId,
                    topic = TicketTopic.OTHER.wire,
                    status = RESOLVED_STATUS,
                    createdAt = FIXED_INSTANT.plusSeconds(364),
                )
            }
            assertThrows(SQLException::class.java) {
                insertMessage(
                    connection = connection,
                    ticketId = Long.MAX_VALUE,
                    senderType = TicketSenderType.GUEST.wire,
                    text = "orphan message must fail after V059",
                    createdAt = FIXED_INSTANT.plusSeconds(365),
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

    private fun readTicketRow(
        connection: Connection,
        ticketId: Long,
    ): LegacyTicketRow =
        connection
            .prepareStatement(
                """
                SELECT id, club_id, user_id, topic, status, created_at, updated_at
                FROM tickets
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, ticketId)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    LegacyTicketRow(
                        id = resultSet.getLong("id"),
                        clubId = resultSet.getLong("club_id"),
                        userId = resultSet.getLong("user_id"),
                        topic = resultSet.getString("topic"),
                        status = resultSet.getString("status"),
                        createdAt = resultSet.getObject("created_at").toString(),
                        updatedAt = resultSet.getObject("updated_at").toString(),
                    ).also { assertFalse(resultSet.next()) }
                }
            }

    private fun assertStatusConstraintAllowsExactly(
        connection: Connection,
        expectedStatuses: List<String>,
    ) {
        val checkClause =
            connection
                .prepareStatement(
                    """
                    SELECT CHECK_CLAUSE
                    FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS
                    WHERE lower(CONSTRAINT_NAME) = 'tickets_status_check'
                    """.trimIndent(),
                ).use { statement ->
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        resultSet.getString("CHECK_CLAUSE").also { assertFalse(resultSet.next()) }
                    }
                }
        val constrainedStatuses =
            Regex("'([^']+)'")
                .findAll(checkClause)
                .map { match -> match.groupValues[1] }
                .toList()
        assertEquals(expectedStatuses, constrainedStatuses)
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

    private fun assertSupportIndexInventoryRemains(connection: Connection) {
        val indexNames =
            connection
                .prepareStatement(
                    """
                    SELECT lower(INDEX_NAME) AS INDEX_NAME
                    FROM INFORMATION_SCHEMA.INDEXES
                    WHERE lower(TABLE_NAME) IN ('tickets', 'ticket_messages')
                      AND lower(INDEX_NAME) IN (
                          'idx_tickets_user_updated_at',
                          'idx_tickets_club_status_updated_at',
                          'idx_ticket_messages_ticket_id_id',
                          'idx_tickets_club_updated_at'
                      )
                    ORDER BY lower(INDEX_NAME)
                    """.trimIndent(),
                ).use { statement ->
                    statement.executeQuery().use { resultSet ->
                        buildList {
                            while (resultSet.next()) {
                                add(resultSet.getString("INDEX_NAME"))
                            }
                        }
                    }
                }
        assertEquals(EXPECTED_SUPPORT_INDEXES.sorted(), indexNames)
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
        private const val PRE_RESOLVED_VERSION = "58"
        private const val EXPECTED_PRE_RESOLVED_VERSION = "058"
        private const val RESOLVED_VERSION = "59"
        private const val EXPECTED_RESOLVED_VERSION = "059"
        private const val RESOLVED_STATUS = "resolved"
        private const val WAITING_STATUS = "waiting"
        private const val LEGACY_MESSAGE_TEXT = "Legacy message survives V057"
        private val V059_ALLOWED_STATUSES =
            listOf("new", "opened", "in_progress", "answered", RESOLVED_STATUS, "closed")
        private val EXPECTED_SUPPORT_INDEXES =
            listOf(
                "idx_tickets_user_updated_at",
                "idx_tickets_club_status_updated_at",
                "idx_ticket_messages_ticket_id_id",
                "idx_tickets_club_updated_at",
            )
        private val FIXED_INSTANT = Instant.parse("2024-05-01T10:00:00Z")
    }
}
