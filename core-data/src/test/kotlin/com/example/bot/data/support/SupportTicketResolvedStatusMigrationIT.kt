package com.example.bot.data.support

import com.example.bot.support.TicketSenderType
import com.example.bot.support.TicketStatus
import com.example.bot.support.TicketTopic
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import testing.RequiresDocker
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@RequiresDocker
@Tag("it")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SupportTicketResolvedStatusMigrationIT {
    @BeforeAll
    fun startContainer() {
        postgres.start()
    }

    @AfterAll
    fun stopContainer() {
        postgres.stop()
    }

    @Test
    fun `V059 preserves every V058 status and adds only RESOLVED`() {
        val migrationDatabaseName = "support_v059_${UUID.randomUUID().toString().replace("-", "")}"
        postgres.createConnection("").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE DATABASE $migrationDatabaseName")
            }
        }
        val migrationJdbcUrl =
            "jdbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/$migrationDatabaseName"
        val locations = arrayOf("classpath:db/migration/common", "classpath:db/migration/postgresql")
        val v058Flyway =
            Flyway
                .configure()
                .dataSource(migrationJdbcUrl, postgres.username, postgres.password)
                .locations(*locations)
                .target(PRE_RESOLVED_VERSION)
                .load()
        v058Flyway.migrate()
        assertEquals(
            EXPECTED_PRE_RESOLVED_VERSION,
            v058Flyway
                .info()
                .current()
                ?.version
                ?.toString(),
        )

        lateinit var fixture: ResolvedMigrationFixture
        lateinit var legacyTickets: List<Pair<String, Long>>
        lateinit var ticketsBeforeMigration: List<ResolvedMigrationTicketRow>
        lateinit var messageBeforeMigration: ResolvedMigrationMessageRow
        migrationConnection(migrationJdbcUrl).use { connection ->
            fixture = insertResolvedMigrationFixture(connection)
            legacyTickets =
                listOf(
                    TicketStatus.NEW.wire to
                        insertResolvedMigrationTicket(
                            connection = connection,
                            clubId = fixture.clubId,
                            userId = fixture.userId,
                            topic = TicketTopic.OTHER.wire,
                            status = TicketStatus.NEW.wire,
                            createdAt = RESOLVED_MIGRATION_CREATED_AT.plusSeconds(120),
                            updatedAt = RESOLVED_MIGRATION_CREATED_AT.plusSeconds(150),
                        ),
                    TicketStatus.OPENED.wire to fixture.openedTicketId,
                    TicketStatus.IN_PROGRESS.wire to
                        insertResolvedMigrationTicket(
                            connection = connection,
                            clubId = fixture.clubId,
                            userId = fixture.userId,
                            topic = TicketTopic.INVITE.wire,
                            status = TicketStatus.IN_PROGRESS.wire,
                            createdAt = RESOLVED_MIGRATION_CREATED_AT.plusSeconds(180),
                            updatedAt = RESOLVED_MIGRATION_CREATED_AT.plusSeconds(210),
                        ),
                    TicketStatus.ANSWERED.wire to fixture.answeredTicketId,
                    TicketStatus.CLOSED.wire to
                        insertResolvedMigrationTicket(
                            connection = connection,
                            clubId = fixture.clubId,
                            userId = fixture.userId,
                            topic = TicketTopic.BOOKING.wire,
                            status = TicketStatus.CLOSED.wire,
                            createdAt = RESOLVED_MIGRATION_CREATED_AT.plusSeconds(240),
                            updatedAt = RESOLVED_MIGRATION_CREATED_AT.plusSeconds(270),
                        ),
                )
            insertResolvedMigrationMessage(
                connection = connection,
                ticketId = fixture.openedTicketId,
                text = V059_MESSAGE_TEXT,
                createdAt = RESOLVED_MIGRATION_CREATED_AT.plusSeconds(300),
            )
            ticketsBeforeMigration =
                legacyTickets.map { (_, ticketId) -> readResolvedMigrationTicket(connection, ticketId) }
            messageBeforeMigration = readResolvedMigrationMessage(connection, fixture.openedTicketId)
        }

        val v059Flyway =
            Flyway
                .configure()
                .dataSource(migrationJdbcUrl, postgres.username, postgres.password)
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

        migrationConnection(migrationJdbcUrl).use { connection ->
            assertEquals(
                ticketsBeforeMigration,
                legacyTickets.map { (_, ticketId) -> readResolvedMigrationTicket(connection, ticketId) },
            )
            assertEquals(messageBeforeMigration, readResolvedMigrationMessage(connection, fixture.openedTicketId))
            assertEquals(
                legacyTickets.map { it.first },
                legacyTickets.map { (_, ticketId) -> readResolvedMigrationTicket(connection, ticketId).status },
            )
            assertEquals(
                V059_ALLOWED_STATUSES,
                readResolvedMigrationStatusConstraintValues(connection, STATUS_CONSTRAINT_NAME),
            )
            assertResolvedMigrationStatusColumnSemantics(connection)
            assertResolvedMigrationClubStatusUpdatedIndex(connection)

            val resolvedTicketId =
                insertResolvedMigrationTicket(
                    connection = connection,
                    clubId = fixture.clubId,
                    userId = fixture.userId,
                    topic = TicketTopic.OTHER.wire,
                    status = RESOLVED_STATUS,
                    createdAt = RESOLVED_MIGRATION_CREATED_AT.plusSeconds(360),
                    updatedAt = RESOLVED_MIGRATION_CREATED_AT.plusSeconds(390),
                )
            assertEquals(RESOLVED_STATUS, readResolvedMigrationTicket(connection, resolvedTicketId).status)

            listOf(WAITING_STATUS, "unsupported").forEachIndexed { index, rejectedStatus ->
                assertThrows(SQLException::class.java) {
                    insertResolvedMigrationTicket(
                        connection = connection,
                        clubId = fixture.clubId,
                        userId = fixture.userId,
                        topic = TicketTopic.OTHER.wire,
                        status = rejectedStatus,
                        createdAt = RESOLVED_MIGRATION_CREATED_AT.plusSeconds(420L + index),
                        updatedAt = RESOLVED_MIGRATION_CREATED_AT.plusSeconds(450L + index),
                    )
                }
            }
            assertThrows(SQLException::class.java) {
                insertResolvedMigrationTicket(
                    connection = connection,
                    clubId = fixture.clubId,
                    userId = fixture.userId,
                    topic = TicketTopic.OTHER.wire,
                    status = null,
                    createdAt = RESOLVED_MIGRATION_CREATED_AT.plusSeconds(422),
                    updatedAt = RESOLVED_MIGRATION_CREATED_AT.plusSeconds(452),
                )
            }
            assertThrows(SQLException::class.java) {
                insertResolvedMigrationTicket(
                    connection = connection,
                    clubId = fixture.clubId,
                    userId = fixture.userId,
                    topic = "unsupported",
                    status = RESOLVED_STATUS,
                    createdAt = RESOLVED_MIGRATION_CREATED_AT.plusSeconds(423),
                    updatedAt = RESOLVED_MIGRATION_CREATED_AT.plusSeconds(453),
                )
            }
            assertThrows(SQLException::class.java) {
                insertResolvedMigrationTicket(
                    connection = connection,
                    clubId = Long.MAX_VALUE,
                    userId = fixture.userId,
                    topic = TicketTopic.OTHER.wire,
                    status = RESOLVED_STATUS,
                    createdAt = RESOLVED_MIGRATION_CREATED_AT.plusSeconds(424),
                    updatedAt = RESOLVED_MIGRATION_CREATED_AT.plusSeconds(454),
                )
            }
            assertThrows(SQLException::class.java) {
                insertResolvedMigrationMessage(
                    connection = connection,
                    ticketId = Long.MAX_VALUE,
                    text = "orphan message must fail after V059",
                    createdAt = RESOLVED_MIGRATION_CREATED_AT.plusSeconds(455),
                )
            }
        }
    }

    @Test
    fun `V059 fails closed when the expected V058 status constraint was renamed`() {
        val driftDatabaseName = "support_v059_drift_${UUID.randomUUID().toString().replace("-", "")}"
        postgres.createConnection("").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE DATABASE $driftDatabaseName")
            }
        }
        val driftJdbcUrl =
            "jdbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/$driftDatabaseName"
        val locations = arrayOf("classpath:db/migration/common", "classpath:db/migration/postgresql")
        val v058Flyway =
            Flyway
                .configure()
                .dataSource(driftJdbcUrl, postgres.username, postgres.password)
                .locations(*locations)
                .target(PRE_RESOLVED_VERSION)
                .load()
        v058Flyway.migrate()
        assertEquals(
            EXPECTED_PRE_RESOLVED_VERSION,
            v058Flyway
                .info()
                .current()
                ?.version
                ?.toString(),
        )

        lateinit var fixture: ResolvedMigrationFixture
        lateinit var ticketsBeforeMigration: List<ResolvedMigrationTicketRow>
        lateinit var messageBeforeMigration: ResolvedMigrationMessageRow
        var ticketCountBeforeMigration = 0L
        var messageCountBeforeMigration = 0L
        migrationConnection(driftJdbcUrl).use { connection ->
            fixture = insertResolvedMigrationFixture(connection)
            insertResolvedMigrationMessage(
                connection = connection,
                ticketId = fixture.openedTicketId,
                text = V059_DRIFT_MESSAGE_TEXT,
                createdAt = RESOLVED_MIGRATION_CREATED_AT.plusSeconds(120),
            )
            ticketsBeforeMigration = readResolvedMigrationRows(connection, fixture)
            messageBeforeMigration = readResolvedMigrationMessage(connection, fixture.openedTicketId)
            ticketCountBeforeMigration = resolvedMigrationTicketCount(connection)
            messageCountBeforeMigration = resolvedMigrationMessageCount(connection)
            connection.createStatement().use { statement ->
                statement.execute(
                    "ALTER TABLE tickets RENAME CONSTRAINT " +
                        "$STATUS_CONSTRAINT_NAME TO $DRIFTED_V059_STATUS_CONSTRAINT",
                )
            }
        }

        val v059Flyway =
            Flyway
                .configure()
                .dataSource(driftJdbcUrl, postgres.username, postgres.password)
                .locations(*locations)
                .target(RESOLVED_VERSION)
                .load()
        assertThrows(FlywayException::class.java) {
            v059Flyway.migrate()
        }
        assertEquals(
            EXPECTED_PRE_RESOLVED_VERSION,
            v059Flyway
                .info()
                .current()
                ?.version
                ?.toString(),
        )

        migrationConnection(driftJdbcUrl).use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT COUNT(*)
                    FROM flyway_schema_history
                    WHERE version = ?
                      AND success = TRUE
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, EXPECTED_RESOLVED_VERSION)
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        assertEquals(0L, resultSet.getLong(1))
                    }
                }

            assertEquals(
                V058_ALLOWED_STATUSES,
                readResolvedMigrationStatusConstraintValues(connection, DRIFTED_V059_STATUS_CONSTRAINT),
            )
            assertEquals(ticketsBeforeMigration, readResolvedMigrationRows(connection, fixture))
            assertEquals(messageBeforeMigration, readResolvedMigrationMessage(connection, fixture.openedTicketId))
            assertEquals(ticketCountBeforeMigration, resolvedMigrationTicketCount(connection))
            assertEquals(messageCountBeforeMigration, resolvedMigrationMessageCount(connection))
            assertThrows(SQLException::class.java) {
                insertResolvedMigrationTicket(
                    connection = connection,
                    clubId = fixture.clubId,
                    userId = fixture.userId,
                    topic = TicketTopic.OTHER.wire,
                    status = RESOLVED_STATUS,
                    createdAt = RESOLVED_MIGRATION_CREATED_AT.plusSeconds(180),
                    updatedAt = RESOLVED_MIGRATION_CREATED_AT.plusSeconds(210),
                )
            }
            assertEquals(ticketsBeforeMigration, readResolvedMigrationRows(connection, fixture))
            assertEquals(messageBeforeMigration, readResolvedMigrationMessage(connection, fixture.openedTicketId))
            assertEquals(ticketCountBeforeMigration, resolvedMigrationTicketCount(connection))
            assertEquals(messageCountBeforeMigration, resolvedMigrationMessageCount(connection))
        }
    }

    private fun migrationConnection(jdbcUrl: String): Connection =
        java.sql.DriverManager.getConnection(jdbcUrl, postgres.username, postgres.password)

    private companion object {
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

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }
}

private fun insertResolvedMigrationFixture(connection: Connection): ResolvedMigrationFixture {
    val clubId =
        connection
            .prepareStatement(
                """
                INSERT INTO clubs (name, description, timezone, admin_channel_id)
                VALUES ('Support resolved migration club', NULL, 'Europe/Moscow', NULL)
                RETURNING id
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    resultSet.getLong(1)
                }
            }
    val userId =
        connection
            .prepareStatement(
                """
                INSERT INTO users (telegram_user_id, username, display_name, phone_e164)
                VALUES (880059001, 'support_resolved_migration_guest', 'Support Resolved Migration Guest', NULL)
                RETURNING id
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    resultSet.getLong(1)
                }
            }
    val openedTicketId =
        insertResolvedMigrationTicket(
            connection = connection,
            clubId = clubId,
            userId = userId,
            topic = TicketTopic.ADDRESS.wire,
            status = TicketStatus.OPENED.wire,
            createdAt = RESOLVED_MIGRATION_CREATED_AT,
            updatedAt = RESOLVED_MIGRATION_CREATED_AT.plusSeconds(30),
        )
    val answeredTicketId =
        insertResolvedMigrationTicket(
            connection = connection,
            clubId = clubId,
            userId = userId,
            topic = TicketTopic.COMPLAINT.wire,
            status = TicketStatus.ANSWERED.wire,
            createdAt = RESOLVED_MIGRATION_CREATED_AT.plusSeconds(60),
            updatedAt = RESOLVED_MIGRATION_CREATED_AT.plusSeconds(90),
        )
    return ResolvedMigrationFixture(clubId, userId, openedTicketId, answeredTicketId)
}

private fun insertResolvedMigrationTicket(
    connection: Connection,
    clubId: Long,
    userId: Long,
    topic: String,
    status: String?,
    createdAt: Instant,
    updatedAt: Instant,
): Long =
    connection
        .prepareStatement(
            """
            INSERT INTO tickets (
                club_id, user_id, booking_id, list_entry_id, topic, status,
                created_at, updated_at, last_agent_id, resolution_rating
            ) VALUES (?, ?, NULL, NULL, ?, ?, ?, ?, NULL, NULL)
            RETURNING id
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, clubId)
            statement.setLong(2, userId)
            statement.setString(3, topic)
            statement.setString(4, status)
            statement.setObject(5, createdAt.atOffset(ZoneOffset.UTC))
            statement.setObject(6, updatedAt.atOffset(ZoneOffset.UTC))
            statement.executeQuery().use { resultSet ->
                assertTrue(resultSet.next())
                resultSet.getLong(1)
            }
        }

private fun insertResolvedMigrationMessage(
    connection: Connection,
    ticketId: Long,
    text: String,
    createdAt: Instant,
): Long =
    connection
        .prepareStatement(
            """
            INSERT INTO ticket_messages (ticket_id, sender_type, text, attachments, created_at)
            VALUES (?, ?, ?, NULL, ?)
            RETURNING id
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, ticketId)
            statement.setString(2, TicketSenderType.GUEST.wire)
            statement.setString(3, text)
            statement.setObject(4, createdAt.atOffset(ZoneOffset.UTC))
            statement.executeQuery().use { resultSet ->
                assertTrue(resultSet.next())
                resultSet.getLong(1)
            }
        }

private fun readResolvedMigrationStatusConstraintValues(
    connection: Connection,
    constraintName: String,
): List<String> {
    val constraintDefinition =
        connection
            .prepareStatement(
                """
                SELECT pg_get_constraintdef(constraint_row.oid)
                FROM pg_constraint constraint_row
                JOIN pg_class table_row ON table_row.oid = constraint_row.conrelid
                WHERE table_row.relname = 'tickets'
                  AND constraint_row.conname = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, constraintName)
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    resultSet.getString(1).also { assertFalse(resultSet.next()) }
                }
            }
    return Regex("'([^']+)'")
        .findAll(constraintDefinition)
        .map { match -> match.groupValues[1] }
        .toList()
}

private fun assertResolvedMigrationStatusColumnSemantics(connection: Connection) {
    connection
        .prepareStatement(
            """
            SELECT is_nullable, column_default
            FROM information_schema.columns
            WHERE table_schema = current_schema()
              AND table_name = 'tickets'
              AND column_name = 'status'
            """.trimIndent(),
        ).use { statement ->
            statement.executeQuery().use { resultSet ->
                assertTrue(resultSet.next())
                assertEquals("NO", resultSet.getString("is_nullable"))
                assertNull(resultSet.getString("column_default"))
                assertFalse(resultSet.next())
            }
        }
}

private fun assertResolvedMigrationClubStatusUpdatedIndex(connection: Connection) {
    val indexDefinition =
        connection
            .prepareStatement(
                """
                SELECT indexdef
                FROM pg_indexes
                WHERE schemaname = current_schema()
                  AND tablename = 'tickets'
                  AND indexname = 'idx_tickets_club_status_updated_at'
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { resultSet ->
                    assertTrue(resultSet.next())
                    resultSet.getString("indexdef").also { assertFalse(resultSet.next()) }
                }
            }
    val normalizedIndexDefinition = indexDefinition.lowercase().replace(Regex("\\s+"), " ")
    assertTrue(normalizedIndexDefinition.endsWith("using btree (club_id, status, updated_at desc)"))
}

private fun readResolvedMigrationRows(
    connection: Connection,
    fixture: ResolvedMigrationFixture,
): List<ResolvedMigrationTicketRow> =
    listOf(fixture.openedTicketId, fixture.answeredTicketId).map { ticketId ->
        readResolvedMigrationTicket(connection, ticketId)
    }

private fun readResolvedMigrationTicket(
    connection: Connection,
    ticketId: Long,
): ResolvedMigrationTicketRow =
    connection
        .prepareStatement(
            """
            SELECT id, club_id, user_id, booking_id, list_entry_id, topic, status,
                   created_at, updated_at, last_agent_id, resolution_rating
            FROM tickets
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, ticketId)
            statement.executeQuery().use { resultSet ->
                assertTrue(resultSet.next())
                resultSet.toResolvedMigrationTicketRow().also { assertFalse(resultSet.next()) }
            }
        }

private fun ResultSet.toResolvedMigrationTicketRow(): ResolvedMigrationTicketRow =
    ResolvedMigrationTicketRow(
        id = getLong("id"),
        clubId = getLong("club_id"),
        userId = getLong("user_id"),
        bookingId = getObject("booking_id", UUID::class.java),
        listEntryId = getObject("list_entry_id")?.let { (it as Number).toLong() },
        topic = getString("topic"),
        status = getString("status"),
        createdAt = getObject("created_at", OffsetDateTime::class.java).toInstant(),
        updatedAt = getObject("updated_at", OffsetDateTime::class.java).toInstant(),
        lastAgentId = getObject("last_agent_id")?.let { (it as Number).toLong() },
        resolutionRating = getObject("resolution_rating")?.let { (it as Number).toInt() },
    )

private fun readResolvedMigrationMessage(
    connection: Connection,
    ticketId: Long,
): ResolvedMigrationMessageRow =
    connection
        .prepareStatement(
            """
            SELECT id, ticket_id, sender_type, text, attachments, created_at
            FROM ticket_messages
            WHERE ticket_id = ?
            ORDER BY id
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, ticketId)
            statement.executeQuery().use { resultSet ->
                assertTrue(resultSet.next())
                ResolvedMigrationMessageRow(
                    id = resultSet.getLong("id"),
                    ticketId = resultSet.getLong("ticket_id"),
                    senderType = resultSet.getString("sender_type"),
                    text = resultSet.getString("text"),
                    attachments = resultSet.getString("attachments"),
                    createdAt = resultSet.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                ).also { assertFalse(resultSet.next()) }
            }
        }

private fun resolvedMigrationTicketCount(connection: Connection): Long = resolvedMigrationCount(connection, "tickets")

private fun resolvedMigrationMessageCount(connection: Connection): Long =
    resolvedMigrationCount(connection, "ticket_messages")

private fun resolvedMigrationCount(
    connection: Connection,
    tableName: String,
): Long =
    connection.prepareStatement("SELECT COUNT(*) FROM $tableName").use { statement ->
        statement.executeQuery().use { resultSet ->
            assertTrue(resultSet.next())
            resultSet.getLong(1)
        }
    }

private data class ResolvedMigrationFixture(
    val clubId: Long,
    val userId: Long,
    val openedTicketId: Long,
    val answeredTicketId: Long,
)

private data class ResolvedMigrationTicketRow(
    val id: Long,
    val clubId: Long,
    val userId: Long,
    val bookingId: UUID?,
    val listEntryId: Long?,
    val topic: String,
    val status: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastAgentId: Long?,
    val resolutionRating: Int?,
)

private data class ResolvedMigrationMessageRow(
    val id: Long,
    val ticketId: Long,
    val senderType: String,
    val text: String,
    val attachments: String?,
    val createdAt: Instant,
)

private const val PRE_RESOLVED_VERSION = "58"
private const val EXPECTED_PRE_RESOLVED_VERSION = "058"
private const val RESOLVED_VERSION = "59"
private const val EXPECTED_RESOLVED_VERSION = "059"
private const val RESOLVED_STATUS = "resolved"
private const val WAITING_STATUS = "waiting"
private const val STATUS_CONSTRAINT_NAME = "tickets_status_check"
private const val DRIFTED_V059_STATUS_CONSTRAINT = "tickets_status_v058_drift_check"
private const val V059_MESSAGE_TEXT = "Legacy message survives V059"
private const val V059_DRIFT_MESSAGE_TEXT = "Legacy message survives failed V059"
private val V058_ALLOWED_STATUSES = listOf("new", "opened", "in_progress", "answered", "closed")
private val V059_ALLOWED_STATUSES = listOf("new", "opened", "in_progress", "answered", RESOLVED_STATUS, "closed")
private val RESOLVED_MIGRATION_CREATED_AT = Instant.parse("2024-05-01T10:00:00Z")
