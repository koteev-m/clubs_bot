package com.example.bot.data.support

import com.example.bot.support.SupportServiceError
import com.example.bot.support.SupportServiceResult
import com.example.bot.support.TicketSenderType
import com.example.bot.support.TicketStatus
import com.example.bot.support.TicketTopic
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
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
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@RequiresDocker
@Tag("it")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SupportTicketCreationIT {
    private lateinit var dataSource: HikariDataSource
    private lateinit var database: Database
    private lateinit var legacyFixture: LegacyFixture
    private lateinit var legacyRowsBeforeMigration: List<PersistedTicketRow>
    private var migrationsExecuted = 0
    private var currentMigrationVersion = ""

    private val fixedInstant = Instant.parse("2024-06-01T12:00:00Z")
    private val fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC)

    @BeforeAll
    fun migrateFromLegacySchema() {
        postgres.start()
        val locations = arrayOf("classpath:db/migration/common", "classpath:db/migration/postgresql")
        val legacyFlyway =
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations(*locations)
                .target(LEGACY_VERSION)
                .cleanDisabled(false)
                .load()
        legacyFlyway.clean()
        legacyFlyway.migrate()

        postgres.createConnection("").use { connection ->
            legacyFixture = insertLegacyFixture(connection)
            legacyRowsBeforeMigration = readLegacyRows(connection, legacyFixture)
        }

        val latestFlyway =
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations(*locations)
                .load()
        migrationsExecuted = latestFlyway.migrate().migrationsExecuted
        currentMigrationVersion =
            latestFlyway
                .info()
                .current()
                ?.version
                ?.toString()
                .orEmpty()

        dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = postgres.jdbcUrl
                    driverClassName = postgres.driverClassName
                    username = postgres.username
                    password = postgres.password
                    maximumPoolSize = 3
                },
            )
        database = Database.connect(dataSource)
    }

    @BeforeEach
    fun resetCreatedRows() {
        dropFailureTrigger()
        dataSource.connection.use { connection ->
            connection.prepareStatement("DELETE FROM ticket_messages").use { statement ->
                statement.executeUpdate()
            }
            connection
                .prepareStatement("DELETE FROM tickets WHERE id <> ? AND id <> ?")
                .use { statement ->
                    statement.setLong(1, legacyFixture.openedTicketId)
                    statement.setLong(2, legacyFixture.answeredTicketId)
                    statement.executeUpdate()
                }
        }
    }

    @AfterEach
    fun removeFailureTrigger() {
        dropFailureTrigger()
    }

    @AfterAll
    fun closeDatabase() {
        if (::dataSource.isInitialized) {
            dataSource.close()
        }
        postgres.stop()
    }

    @Test
    fun `V057 preserves legacy rows and status column semantics`() {
        assertEquals(1, migrationsExecuted)
        assertEquals(EXPECTED_VERSION, currentMigrationVersion)

        dataSource.connection.use { connection ->
            assertEquals(legacyRowsBeforeMigration, readLegacyRows(connection, legacyFixture))

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

            val constraintDefinition =
                connection
                    .prepareStatement(
                        """
                        SELECT pg_get_constraintdef(constraint_row.oid)
                        FROM pg_constraint constraint_row
                        JOIN pg_class table_row ON table_row.oid = constraint_row.conrelid
                        WHERE table_row.relname = 'tickets'
                          AND constraint_row.conname = 'tickets_status_check'
                        """.trimIndent(),
                    ).use { statement ->
                        statement.executeQuery().use { resultSet ->
                            assertTrue(resultSet.next())
                            resultSet.getString(1).also { assertFalse(resultSet.next()) }
                        }
                    }
            listOf("new", "opened", "in_progress", "answered", "closed").forEach { status ->
                assertTrue(constraintDefinition.contains(status))
            }

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
            assertTrue(
                normalizedIndexDefinition.endsWith(
                    "using btree (club_id, status, updated_at desc)",
                ),
            )
        }
    }

    @Test
    fun `V057 fails closed when the expected legacy status constraint was renamed`() {
        val driftDatabaseName = "support_v057_drift_${UUID.randomUUID().toString().replace("-", "")}"
        postgres.createConnection("").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE DATABASE $driftDatabaseName")
            }
        }
        val driftJdbcUrl =
            "jdbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/$driftDatabaseName"
        val locations = arrayOf("classpath:db/migration/common", "classpath:db/migration/postgresql")
        val legacyFlyway =
            Flyway
                .configure()
                .dataSource(driftJdbcUrl, postgres.username, postgres.password)
                .locations(*locations)
                .target(LEGACY_VERSION)
                .load()

        legacyFlyway.migrate()
        assertEquals(
            EXPECTED_LEGACY_VERSION,
            legacyFlyway
                .info()
                .current()
                ?.version
                ?.toString(),
        )

        lateinit var fixture: LegacyFixture
        lateinit var ticketsBeforeMigration: List<PersistedTicketRow>
        lateinit var messageBeforeMigration: PersistedMessageRow
        var ticketCountBeforeMigration = 0L
        var messageCountBeforeMigration = 0L
        driftConnection(driftJdbcUrl).use { connection ->
            fixture = insertLegacyFixture(connection)
            insertLegacyMessage(
                connection = connection,
                ticketId = fixture.openedTicketId,
                text = DRIFT_MESSAGE_TEXT,
                createdAt = LEGACY_CREATED_AT.plusSeconds(120),
            )
            ticketsBeforeMigration = readLegacyRows(connection, fixture)
            messageBeforeMigration = readOnlyMessage(connection, fixture.openedTicketId)
            ticketCountBeforeMigration = ticketCount(connection)
            messageCountBeforeMigration = messageCount(connection)

            connection.createStatement().use { statement ->
                statement.execute(
                    "ALTER TABLE tickets RENAME CONSTRAINT " +
                        "tickets_status_check TO $DRIFTED_STATUS_CONSTRAINT",
                )
            }
        }

        val latestFlyway =
            Flyway
                .configure()
                .dataSource(driftJdbcUrl, postgres.username, postgres.password)
                .locations(*locations)
                .load()
        assertThrows(FlywayException::class.java) {
            latestFlyway.migrate()
        }
        assertEquals(
            EXPECTED_LEGACY_VERSION,
            latestFlyway
                .info()
                .current()
                ?.version
                ?.toString(),
        )

        driftConnection(driftJdbcUrl).use { connection ->
            connection
                .prepareStatement(
                    """
                    SELECT COUNT(*)
                    FROM flyway_schema_history
                    WHERE version = ?
                      AND success = TRUE
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, EXPECTED_VERSION)
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        assertEquals(0L, resultSet.getLong(1))
                    }
                }

            val driftedConstraintDefinition =
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
                        statement.setString(1, DRIFTED_STATUS_CONSTRAINT)
                        statement.executeQuery().use { resultSet ->
                            assertTrue(resultSet.next())
                            resultSet.getString(1).also { assertFalse(resultSet.next()) }
                        }
                    }
            listOf("opened", "in_progress", "answered", "closed").forEach { status ->
                assertTrue(driftedConstraintDefinition.contains(status))
            }
            assertFalse(driftedConstraintDefinition.contains("'new'"))

            assertEquals(ticketsBeforeMigration, readLegacyRows(connection, fixture))
            assertEquals(messageBeforeMigration, readOnlyMessage(connection, fixture.openedTicketId))
            assertEquals(ticketCountBeforeMigration, ticketCount(connection))
            assertEquals(messageCountBeforeMigration, messageCount(connection))

            assertThrows(SQLException::class.java) {
                insertLegacyTicket(
                    connection = connection,
                    clubId = fixture.clubId,
                    userId = fixture.userId,
                    topic = TicketTopic.OTHER.wire,
                    status = TicketStatus.NEW.wire,
                    createdAt = LEGACY_CREATED_AT.plusSeconds(180),
                    updatedAt = LEGACY_CREATED_AT.plusSeconds(210),
                )
            }
            assertEquals(ticketsBeforeMigration, readLegacyRows(connection, fixture))
            assertEquals(messageBeforeMigration, readOnlyMessage(connection, fixture.openedTicketId))
            assertEquals(ticketCountBeforeMigration, ticketCount(connection))
            assertEquals(messageCountBeforeMigration, messageCount(connection))
        }
    }

    @Test
    fun `service persists one NEW ticket and its exact initial GUEST message`() =
        runBlocking {
            val service = SupportServiceImpl(SupportRepository(database, fixedClock))

            val result =
                service.createTicket(
                    clubId = legacyFixture.clubId,
                    userId = legacyFixture.userId,
                    bookingId = null,
                    listEntryId = null,
                    topic = TicketTopic.LOST_FOUND,
                    text = NORMALIZED_QUESTION,
                    attachments = null,
                )

            assertTrue(result is SupportServiceResult.Success)
            val created = (result as SupportServiceResult.Success).value
            assertEquals(legacyFixture.clubId, created.ticket.clubId)
            assertEquals(legacyFixture.userId, created.ticket.userId)
            assertEquals(TicketTopic.LOST_FOUND, created.ticket.topic)
            assertEquals(TicketStatus.NEW, created.ticket.status)
            assertNull(created.ticket.bookingId)
            assertNull(created.ticket.listEntryId)
            assertNull(created.ticket.lastAgentId)
            assertNull(created.ticket.resolutionRating)
            assertEquals(fixedInstant, created.ticket.createdAt)
            assertEquals(fixedInstant, created.ticket.updatedAt)
            assertEquals(created.ticket.id, created.initialMessage.ticketId)
            assertEquals(TicketSenderType.GUEST, created.initialMessage.senderType)
            assertEquals(NORMALIZED_QUESTION, created.initialMessage.text)
            assertNull(created.initialMessage.attachments)
            assertEquals(fixedInstant, created.initialMessage.createdAt)

            dataSource.connection.use { connection ->
                assertEquals(legacyRowsBeforeMigration.size + 1L, ticketCount(connection))
                assertEquals(1L, messageCount(connection))

                val storedTicket = readTicket(connection, created.ticket.id)
                assertEquals(legacyFixture.clubId, storedTicket.clubId)
                assertEquals(legacyFixture.userId, storedTicket.userId)
                assertNull(storedTicket.bookingId)
                assertNull(storedTicket.listEntryId)
                assertEquals(TicketTopic.LOST_FOUND.wire, storedTicket.topic)
                assertEquals(TicketStatus.NEW.wire, storedTicket.status)
                assertEquals(fixedInstant, storedTicket.createdAt)
                assertEquals(fixedInstant, storedTicket.updatedAt)
                assertNull(storedTicket.lastAgentId)
                assertNull(storedTicket.resolutionRating)

                val storedMessage = readOnlyMessage(connection, created.ticket.id)
                assertEquals(created.initialMessage.id, storedMessage.id)
                assertEquals(created.ticket.id, storedMessage.ticketId)
                assertEquals(TicketSenderType.GUEST.wire, storedMessage.senderType)
                assertEquals(NORMALIZED_QUESTION, storedMessage.text)
                assertNull(storedMessage.attachments)
                assertEquals(fixedInstant, storedMessage.createdAt)
            }

            val freshlyRead = SupportServiceImpl(SupportRepository(database, fixedClock)).getTicket(created.ticket.id)
            assertNotNull(freshlyRead)
            assertEquals(TicketStatus.NEW, freshlyRead?.status)
            assertEquals(TicketTopic.LOST_FOUND, freshlyRead?.topic)
        }

    @Test
    fun `initial message failure returns typed error and rolls back the complete transaction`() {
        installFailureTrigger()
        val ticketsBefore = dataSource.connection.use(::ticketCount)
        val messagesBefore = dataSource.connection.use(::messageCount)
        val service = SupportServiceImpl(SupportRepository(database, fixedClock))

        val result =
            try {
                runBlocking {
                    service.createTicket(
                        clubId = legacyFixture.clubId,
                        userId = legacyFixture.userId,
                        bookingId = null,
                        listEntryId = null,
                        topic = TicketTopic.OTHER,
                        text = FAILURE_SENTINEL,
                        attachments = null,
                    )
                }
            } finally {
                dropFailureTrigger()
            }

        assertTrue(result is SupportServiceResult.Failure)
        val failure = result as SupportServiceResult.Failure
        assertEquals(SupportServiceError.PersistenceFailure, failure.error)
        val renderedFailure = result.toString()
        assertFalse(renderedFailure.contains(FAILURE_SENTINEL, ignoreCase = true))
        assertFalse(renderedFailure.contains("PSQLException", ignoreCase = true))
        assertFalse(renderedFailure.contains("ticket_messages", ignoreCase = true))
        assertFalse(renderedFailure.contains(FAILURE_TRIGGER_NAME, ignoreCase = true))

        dataSource.connection.use { connection ->
            assertEquals(ticketsBefore, ticketCount(connection))
            assertEquals(messagesBefore, messageCount(connection))
            assertEquals(0L, ticketCountByTopic(connection, TicketTopic.OTHER.wire))
            assertEquals(0L, messageCountByText(connection, FAILURE_SENTINEL))
        }
    }

    private fun insertLegacyFixture(connection: Connection): LegacyFixture {
        val clubId =
            connection
                .prepareStatement(
                    """
                    INSERT INTO clubs (name, description, timezone, admin_channel_id)
                    VALUES ('Support migration club', NULL, 'Europe/Moscow', NULL)
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
                    VALUES (880057001, 'support_migration_guest', 'Support Migration Guest', NULL)
                    RETURNING id
                    """.trimIndent(),
                ).use { statement ->
                    statement.executeQuery().use { resultSet ->
                        assertTrue(resultSet.next())
                        resultSet.getLong(1)
                    }
                }
        val openedTicketId =
            insertLegacyTicket(
                connection = connection,
                clubId = clubId,
                userId = userId,
                topic = TicketTopic.ADDRESS.wire,
                status = TicketStatus.OPENED.wire,
                createdAt = LEGACY_CREATED_AT,
                updatedAt = LEGACY_CREATED_AT.plusSeconds(30),
            )
        val answeredTicketId =
            insertLegacyTicket(
                connection = connection,
                clubId = clubId,
                userId = userId,
                topic = TicketTopic.COMPLAINT.wire,
                status = TicketStatus.ANSWERED.wire,
                createdAt = LEGACY_CREATED_AT.plusSeconds(60),
                updatedAt = LEGACY_CREATED_AT.plusSeconds(90),
            )
        return LegacyFixture(
            clubId = clubId,
            userId = userId,
            openedTicketId = openedTicketId,
            answeredTicketId = answeredTicketId,
        )
    }

    private fun insertLegacyTicket(
        connection: Connection,
        clubId: Long,
        userId: Long,
        topic: String,
        status: String,
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

    private fun driftConnection(jdbcUrl: String): Connection =
        java.sql.DriverManager.getConnection(jdbcUrl, postgres.username, postgres.password)

    private fun readLegacyRows(
        connection: Connection,
        fixture: LegacyFixture,
    ): List<PersistedTicketRow> =
        connection
            .prepareStatement(
                """
                SELECT id, club_id, user_id, booking_id, list_entry_id, topic, status,
                       created_at, updated_at, last_agent_id, resolution_rating
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
                            add(resultSet.toTicketRow())
                        }
                    }
                }
            }

    private fun readTicket(
        connection: Connection,
        ticketId: Long,
    ): PersistedTicketRow =
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
                    resultSet.toTicketRow().also { assertFalse(resultSet.next()) }
                }
            }

    private fun ResultSet.toTicketRow(): PersistedTicketRow =
        PersistedTicketRow(
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

    private fun installFailureTrigger() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE OR REPLACE FUNCTION $FAILURE_FUNCTION_NAME()
                    RETURNS trigger AS ${'$'}${'$'}
                    BEGIN
                        IF NEW.text = '$FAILURE_SENTINEL' THEN
                            RAISE EXCEPTION '$FAILURE_SENTINEL';
                        END IF;
                        RETURN NEW;
                    END;
                    ${'$'}${'$'} LANGUAGE plpgsql
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TRIGGER $FAILURE_TRIGGER_NAME
                    AFTER INSERT ON ticket_messages
                    FOR EACH ROW EXECUTE FUNCTION $FAILURE_FUNCTION_NAME()
                    """.trimIndent(),
                )
            }
        }
    }

    private fun dropFailureTrigger() {
        if (!::dataSource.isInitialized) return
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP TRIGGER IF EXISTS $FAILURE_TRIGGER_NAME ON ticket_messages")
                statement.execute("DROP FUNCTION IF EXISTS $FAILURE_FUNCTION_NAME()")
            }
        }
    }

    private fun ticketCount(connection: Connection): Long =
        connection.prepareStatement("SELECT COUNT(*) FROM tickets").use { statement ->
            statement.executeQuery().use { resultSet ->
                assertTrue(resultSet.next())
                resultSet.getLong(1)
            }
        }

    private fun messageCount(connection: Connection): Long =
        connection.prepareStatement("SELECT COUNT(*) FROM ticket_messages").use { statement ->
            statement.executeQuery().use { resultSet ->
                assertTrue(resultSet.next())
                resultSet.getLong(1)
            }
        }

    private fun ticketCountByTopic(
        connection: Connection,
        topic: String,
    ): Long =
        connection.prepareStatement("SELECT COUNT(*) FROM tickets WHERE topic = ?").use { statement ->
            statement.setString(1, topic)
            statement.executeQuery().use { resultSet ->
                assertTrue(resultSet.next())
                resultSet.getLong(1)
            }
        }

    private fun messageCountByText(
        connection: Connection,
        text: String,
    ): Long =
        connection.prepareStatement("SELECT COUNT(*) FROM ticket_messages WHERE text = ?").use { statement ->
            statement.setString(1, text)
            statement.executeQuery().use { resultSet ->
                assertTrue(resultSet.next())
                resultSet.getLong(1)
            }
        }

    companion object {
        private const val LEGACY_VERSION = "56"
        private const val EXPECTED_LEGACY_VERSION = "056"
        private const val EXPECTED_VERSION = "057"
        private const val NORMALIZED_QUESTION = "Где найти гардероб?"
        private const val FAILURE_SENTINEL = "forced-support-message-v057-sentinel"
        private const val FAILURE_FUNCTION_NAME = "reject_support_message_v057"
        private const val FAILURE_TRIGGER_NAME = "reject_support_message_v057_trigger"
        private const val DRIFTED_STATUS_CONSTRAINT = "tickets_status_legacy_drift_check"
        private const val DRIFT_MESSAGE_TEXT = "Legacy message survives failed V057"
        private val LEGACY_CREATED_AT = Instant.parse("2024-05-01T10:00:00Z")

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

private fun readOnlyMessage(
    connection: Connection,
    ticketId: Long,
): PersistedMessageRow =
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
                PersistedMessageRow(
                    id = resultSet.getLong("id"),
                    ticketId = resultSet.getLong("ticket_id"),
                    senderType = resultSet.getString("sender_type"),
                    text = resultSet.getString("text"),
                    attachments = resultSet.getString("attachments"),
                    createdAt = resultSet.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                ).also { assertFalse(resultSet.next()) }
            }
        }

private fun insertLegacyMessage(
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

private data class LegacyFixture(
    val clubId: Long,
    val userId: Long,
    val openedTicketId: Long,
    val answeredTicketId: Long,
)

private data class PersistedTicketRow(
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

private data class PersistedMessageRow(
    val id: Long,
    val ticketId: Long,
    val senderType: String,
    val text: String,
    val attachments: String?,
    val createdAt: Instant,
)
