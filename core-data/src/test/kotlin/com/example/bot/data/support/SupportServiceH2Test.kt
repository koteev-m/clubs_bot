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
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

class SupportServiceH2Test {
    private lateinit var dataSource: HikariDataSource
    private lateinit var database: Database
    private lateinit var repository: SupportRepository
    private lateinit var service: SupportServiceImpl

    private val fixedInstant: Instant = Instant.parse("2024-06-01T00:00:00Z")
    private val fixedClock: Clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)

    @BeforeEach
    fun setUp() {
        val dbName = "support-db-${UUID.randomUUID()}"
        val jdbcUrl = "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false"
        dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    this.jdbcUrl = jdbcUrl
                    driverClassName = "org.h2.Driver"
                    username = "sa"
                    password = ""
                    maximumPoolSize = 3
                },
            )

        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration/common", "classpath:db/migration/h2")
            .cleanDisabled(false)
            .load()
            .also { flyway ->
                flyway.clean()
                flyway.migrate()
            }

        database = Database.connect(dataSource)
        repository = SupportRepository(database, fixedClock)
        service = SupportServiceImpl(repository)
    }

    @AfterEach
    fun tearDown() {
        dataSource.close()
    }

    @Test
    fun `create persists one NEW ticket and one initial guest message durably`() =
        runBlocking {
            val userId = insertUser(username = "guest", displayName = "Guest")
            val clubId = insertClub(name = "Aurora")
            val normalizedQuestion = "How do I find the entrance?"

            val result =
                service.createTicket(
                    clubId = clubId,
                    userId = userId,
                    bookingId = null,
                    listEntryId = null,
                    topic = TicketTopic.ADDRESS,
                    text = normalizedQuestion,
                    attachments = null,
                )

            assertTrue(result is SupportServiceResult.Success)
            val created = (result as SupportServiceResult.Success).value
            assertEquals(clubId, created.ticket.clubId)
            assertEquals(userId, created.ticket.userId)
            assertNull(created.ticket.bookingId)
            assertNull(created.ticket.listEntryId)
            assertEquals(TicketTopic.ADDRESS, created.ticket.topic)
            assertEquals(TicketStatus.NEW, created.ticket.status)
            assertEquals(created.ticket.id, created.initialMessage.ticketId)
            assertEquals(TicketSenderType.GUEST, created.initialMessage.senderType)
            assertEquals(normalizedQuestion, created.initialMessage.text)
            assertNull(created.initialMessage.attachments)
            assertEquals(fixedInstant, created.ticket.createdAt)
            assertEquals(fixedInstant, created.ticket.updatedAt)
            assertEquals(fixedInstant, created.initialMessage.createdAt)

            transaction(database) {
                assertEquals(1L, TicketsTable.selectAll().count())
                assertEquals(1L, TicketMessagesTable.selectAll().count())

                val ticketRow = TicketsTable.selectAll().single()
                assertEquals(clubId, ticketRow[TicketsTable.clubId])
                assertEquals(userId, ticketRow[TicketsTable.userId])
                assertNull(ticketRow[TicketsTable.bookingId])
                assertNull(ticketRow[TicketsTable.listEntryId])
                assertEquals(TicketTopic.ADDRESS.wire, ticketRow[TicketsTable.topic])
                assertEquals(TicketStatus.NEW.wire, ticketRow[TicketsTable.status])
                assertEquals(fixedInstant, ticketRow[TicketsTable.createdAt].toInstant())
                assertEquals(fixedInstant, ticketRow[TicketsTable.updatedAt].toInstant())

                val messageRow = TicketMessagesTable.selectAll().single()
                assertEquals(created.ticket.id, messageRow[TicketMessagesTable.ticketId])
                assertEquals(TicketSenderType.GUEST.wire, messageRow[TicketMessagesTable.senderType])
                assertEquals(normalizedQuestion, messageRow[TicketMessagesTable.text])
                assertNull(messageRow[TicketMessagesTable.attachments])
                assertEquals(fixedInstant, messageRow[TicketMessagesTable.createdAt].toInstant())
            }

            val restartedService = SupportServiceImpl(SupportRepository(database, fixedClock))
            val reloaded = restartedService.getTicket(created.ticket.id)
            assertEquals(created.ticket, reloaded)
        }

    @Test
    fun `NEW coexists with readable legacy OPENED and ANSWERED statuses`() =
        runBlocking {
            val userId = insertUser(username = "guest", displayName = "Guest")
            val clubId = insertClub(name = "Aurora")
            val openedId = createTicket(userId = userId, clubId = clubId, text = "Legacy opened").ticket.id
            val answeredId = createTicket(userId = userId, clubId = clubId, text = "Legacy answered").ticket.id

            seedLegacyStatus(openedId, TicketStatus.OPENED)
            seedLegacyStatus(answeredId, TicketStatus.ANSWERED)

            val restartedService = SupportServiceImpl(SupportRepository(database, fixedClock))
            assertEquals(TicketStatus.OPENED, restartedService.getTicket(openedId)?.status)
            assertEquals(TicketStatus.ANSWERED, restartedService.getTicket(answeredId)?.status)
        }

    @Test
    fun `legacy staff mutations reject NEW without changing tickets or messages`() =
        runBlocking {
            val userId = insertUser(username = "guest", displayName = "Guest")
            val agentId = insertUser(username = "agent", displayName = "Agent")
            val clubId = insertClub(name = "Aurora")

            val assignId = createTicket(userId = userId, clubId = clubId, text = "Assign NEW").ticket.id
            val assignBefore = service.getTicket(assignId)
            val assignResult = service.assign(ticketId = assignId, agentUserId = agentId)
            assertInvalidState(assignResult)
            assertEquals(assignBefore, service.getTicket(assignId))
            assertEquals(1L, messageCount(assignId))

            val replyId = createTicket(userId = userId, clubId = clubId, text = "Reply NEW").ticket.id
            val replyBefore = service.getTicket(replyId)
            val replyResult =
                service.reply(
                    ticketId = replyId,
                    agentUserId = agentId,
                    text = "Legacy reply",
                    attachments = "[]",
                )
            assertInvalidState(replyResult)
            assertEquals(replyBefore, service.getTicket(replyId))
            assertEquals(1L, messageCount(replyId))

            val fromNewId = createTicket(userId = userId, clubId = clubId, text = "Status from NEW").ticket.id
            val fromNewBefore = service.getTicket(fromNewId)
            val fromNewResult = service.setStatus(fromNewId, agentId, TicketStatus.CLOSED)
            assertInvalidState(fromNewResult)
            assertEquals(fromNewBefore, service.getTicket(fromNewId))
            assertEquals(1L, messageCount(fromNewId))

            val toNewId = createTicket(userId = userId, clubId = clubId, text = "Status to NEW").ticket.id
            seedLegacyStatus(toNewId, TicketStatus.OPENED)
            val toNewBefore = service.getTicket(toNewId)
            val toNewResult = service.setStatus(toNewId, agentId, TicketStatus.NEW)
            assertInvalidState(toNewResult)
            assertEquals(toNewBefore, service.getTicket(toNewId))
            assertEquals(1L, messageCount(toNewId))
        }

    @Test
    fun `legacy staff mutations preserve behavior for every legacy status`() =
        runBlocking {
            val userId = insertUser(username = "guest", displayName = "Guest")
            val agentId = insertUser(username = "agent", displayName = "Agent")
            val clubId = insertClub(name = "Aurora")
            val legacyTargets =
                mapOf(
                    TicketStatus.OPENED to TicketStatus.IN_PROGRESS,
                    TicketStatus.IN_PROGRESS to TicketStatus.ANSWERED,
                    TicketStatus.ANSWERED to TicketStatus.CLOSED,
                    TicketStatus.CLOSED to TicketStatus.OPENED,
                )

            legacyTargets.forEach { (currentStatus, targetStatus) ->
                val assignId = createTicket(userId, clubId, "Assign $currentStatus").ticket.id
                seedLegacyStatus(assignId, currentStatus)
                assertEquals(currentStatus, service.getTicket(assignId)?.status)
                val assignResult = service.assign(assignId, agentId)
                assertTrue(assignResult is SupportServiceResult.Success)
                val assignedTicket = (assignResult as SupportServiceResult.Success).value
                assertEquals(TicketStatus.IN_PROGRESS, assignedTicket.status)
                assertEquals(agentId, assignedTicket.lastAgentId)

                val replyId = createTicket(userId, clubId, "Reply $currentStatus").ticket.id
                seedLegacyStatus(replyId, currentStatus)
                val messageCountBefore = messageCount(replyId)
                val replyResult = service.reply(replyId, agentId, "Reply", null)
                assertTrue(replyResult is SupportServiceResult.Success)
                val reply = (replyResult as SupportServiceResult.Success).value
                assertEquals(TicketStatus.ANSWERED, reply.ticket.status)
                assertEquals(agentId, reply.ticket.lastAgentId)
                assertEquals(messageCountBefore + 1, messageCount(replyId))

                val statusId = createTicket(userId, clubId, "Status $currentStatus").ticket.id
                seedLegacyStatus(statusId, currentStatus)
                val statusResult = service.setStatus(statusId, agentId, targetStatus)
                assertTrue(statusResult is SupportServiceResult.Success)
                val updatedTicket = (statusResult as SupportServiceResult.Success).value
                assertEquals(targetStatus, updatedTicket.status)
                assertEquals(agentId, updatedTicket.lastAgentId)
            }
        }

    @Test
    fun `legacy staff mutations distinguish missing tickets from invalid NEW state`() =
        runBlocking {
            val agentId = insertUser(username = "agent", displayName = "Agent")
            val missingTicketId = Long.MAX_VALUE

            assertTicketNotFound(service.assign(missingTicketId, agentId))
            assertTicketNotFound(service.reply(missingTicketId, agentId, "Reply", null))
            assertTicketNotFound(service.setStatus(missingTicketId, agentId, TicketStatus.CLOSED))
            assertTicketNotFound(service.setStatus(missingTicketId, agentId, TicketStatus.NEW))
        }

    @Test
    fun `ticket insert failure returns generic persistence failure without rows or raw detail`() =
        runBlocking {
            val clubId = insertClub(name = "Aurora")
            val rawDetail = "tickets_user_id_fkey INSERT INTO tickets SQLState 23503"

            val result =
                service.createTicket(
                    clubId = clubId,
                    userId = Long.MAX_VALUE,
                    bookingId = null,
                    listEntryId = null,
                    topic = TicketTopic.OTHER,
                    text = "Question",
                    attachments = null,
                )

            assertPersistenceFailure(result, rawDetail, "tickets_user_id_fkey", "INSERT INTO", "23503")
            assertNoTicketOrMessageRows()
        }

    @Test
    fun `initial message insert failure returns generic persistence failure and rolls ticket back`() =
        runBlocking {
            val userId = insertUser(username = "guest", displayName = "Guest")
            val clubId = insertClub(name = "Aurora")
            val sentinel = "force-message-insert-failure"
            val constraintName = "ticket_messages_test_reject_sentinel"
            transaction(database) {
                exec(
                    "ALTER TABLE ticket_messages ADD CONSTRAINT $constraintName " +
                        "CHECK (text <> '$sentinel')",
                )
            }

            val result =
                service.createTicket(
                    clubId = clubId,
                    userId = userId,
                    bookingId = null,
                    listEntryId = null,
                    topic = TicketTopic.COMPLAINT,
                    text = sentinel,
                    attachments = null,
                )

            assertPersistenceFailure(result, sentinel, constraintName, "INSERT INTO", "SQL")
            assertNoTicketOrMessageRows()
        }

    @Test
    fun `create ticket rethrows cancellation`() {
        val userId = insertUser(username = "guest", displayName = "Guest")
        val clubId = insertClub(name = "Aurora")
        val cancellation = CancellationException("cancel support persistence")
        val cancellingClock =
            object : Clock() {
                override fun getZone(): ZoneId = ZoneOffset.UTC

                override fun withZone(zone: ZoneId): Clock = this

                override fun instant(): Instant = throw cancellation
            }
        val cancellingService = SupportServiceImpl(SupportRepository(database, cancellingClock))

        val thrown =
            assertThrows(CancellationException::class.java) {
                runBlocking {
                    cancellingService.createTicket(
                        clubId = clubId,
                        userId = userId,
                        bookingId = null,
                        listEntryId = null,
                        topic = TicketTopic.OTHER,
                        text = "Question",
                        attachments = null,
                    )
                }
            }

        assertEquals(cancellation.message, thrown.message)
        assertNoTicketOrMessageRows()
    }

    @Test
    fun `happy path updates last message summary`() =
        runBlocking {
            val userId = insertUser(username = "guest", displayName = "Guest")
            val agentId = insertUser(username = "agent", displayName = "Agent")
            val clubId = insertClub(name = "Aurora")

            val created =
                service.createTicket(
                    clubId = clubId,
                    userId = userId,
                    bookingId = null,
                    listEntryId = null,
                    topic = TicketTopic.OTHER,
                    text = "Need help with entry",
                    attachments = null,
                ) as SupportServiceResult.Success

            val guestMessage =
                service.addGuestMessage(
                    ticketId = created.value.ticket.id,
                    userId = userId,
                    text = "Adding more context",
                    attachments = null,
                )
            assertTrue(guestMessage is SupportServiceResult.Success)

            seedLegacyStatus(created.value.ticket.id, TicketStatus.OPENED)
            val assigned = service.assign(ticketId = created.value.ticket.id, agentUserId = agentId)
            assertTrue(assigned is SupportServiceResult.Success)
            val assignedTicket = (assigned as SupportServiceResult.Success).value
            assertEquals(TicketStatus.IN_PROGRESS, assignedTicket.status)
            assertEquals(agentId, assignedTicket.lastAgentId)
            service.reply(
                ticketId = created.value.ticket.id,
                agentUserId = agentId,
                text = "We have resolved your issue",
                attachments = null,
            )

            val summaries = service.listMyTickets(userId)
            assertEquals(1, summaries.size)
            val summary = summaries.first()
            assertTrue(summary.lastMessagePreview?.contains("resolved") == true)
            assertEquals(TicketSenderType.AGENT, summary.lastSenderType)
            assertEquals(TicketStatus.ANSWERED, summary.status)
        }

    @Test
    fun `guest message reopens answered ticket`() =
        runBlocking {
            val userId = insertUser(username = "guest", displayName = "Guest")
            val clubId = insertClub(name = "Aurora")

            val created =
                service.createTicket(
                    clubId = clubId,
                    userId = userId,
                    bookingId = null,
                    listEntryId = null,
                    topic = TicketTopic.OTHER,
                    text = "Need help",
                    attachments = null,
                ) as SupportServiceResult.Success

            seedLegacyStatus(created.value.ticket.id, TicketStatus.ANSWERED)

            val message =
                service.addGuestMessage(
                    ticketId = created.value.ticket.id,
                    userId = userId,
                    text = "It came back",
                    attachments = null,
                )
            assertTrue(message is SupportServiceResult.Success)

            val ticket = service.getTicket(created.value.ticket.id)
            assertEquals(TicketStatus.OPENED, ticket?.status)
        }

    @Test
    fun `closed ticket blocks guest message`() =
        runBlocking {
            val userId = insertUser(username = "guest", displayName = "Guest")
            val clubId = insertClub(name = "Aurora")

            val created =
                service.createTicket(
                    clubId = clubId,
                    userId = userId,
                    bookingId = null,
                    listEntryId = null,
                    topic = TicketTopic.OTHER,
                    text = "Need help",
                    attachments = null,
                ) as SupportServiceResult.Success

            seedLegacyStatus(created.value.ticket.id, TicketStatus.CLOSED)

            val result =
                service.addGuestMessage(
                    ticketId = created.value.ticket.id,
                    userId = userId,
                    text = "Trying to reopen",
                    attachments = null,
                )

            assertTrue(result is SupportServiceResult.Failure)
            assertEquals(SupportServiceError.TicketClosed, (result as SupportServiceResult.Failure).error)
        }

    @Test
    fun `rating not allowed for open or in progress tickets`() =
        runBlocking {
            val userId = insertUser(username = "guest", displayName = "Guest")
            val agentId = insertUser(username = "agent", displayName = "Agent")
            val clubId = insertClub(name = "Aurora")

            val created =
                service.createTicket(
                    clubId = clubId,
                    userId = userId,
                    bookingId = null,
                    listEntryId = null,
                    topic = TicketTopic.OTHER,
                    text = "Need help",
                    attachments = null,
                ) as SupportServiceResult.Success

            seedLegacyStatus(created.value.ticket.id, TicketStatus.OPENED)
            val openedRating = service.setResolutionRating(created.value.ticket.id, userId, 1)
            assertTrue(openedRating is SupportServiceResult.Failure)
            assertEquals(SupportServiceError.RatingNotAllowed, (openedRating as SupportServiceResult.Failure).error)

            val assigned = service.assign(ticketId = created.value.ticket.id, agentUserId = agentId)
            assertTrue(assigned is SupportServiceResult.Success)

            val inProgressRating = service.setResolutionRating(created.value.ticket.id, userId, 1)
            assertTrue(inProgressRating is SupportServiceResult.Failure)
            assertEquals(SupportServiceError.RatingNotAllowed, (inProgressRating as SupportServiceResult.Failure).error)
        }

    @Test
    fun `rating is set once and guarded by ownership`() =
        runBlocking {
            val userId = insertUser(username = "guest", displayName = "Guest")
            val otherUserId = insertUser(username = "other", displayName = "Other")
            val agentId = insertUser(username = "agent", displayName = "Agent")
            val clubId = insertClub(name = "Aurora")

            val created =
                service.createTicket(
                    clubId = clubId,
                    userId = userId,
                    bookingId = null,
                    listEntryId = null,
                    topic = TicketTopic.OTHER,
                    text = "Need help",
                    attachments = null,
                ) as SupportServiceResult.Success

            seedLegacyStatus(created.value.ticket.id, TicketStatus.OPENED)
            service.reply(
                ticketId = created.value.ticket.id,
                agentUserId = agentId,
                text = "Resolved",
                attachments = null,
            )

            val firstRating = service.setResolutionRating(created.value.ticket.id, userId, 1)
            assertTrue(firstRating is SupportServiceResult.Success)

            val secondRating = service.setResolutionRating(created.value.ticket.id, userId, -1)
            assertTrue(secondRating is SupportServiceResult.Failure)
            assertEquals(SupportServiceError.RatingAlreadySet, (secondRating as SupportServiceResult.Failure).error)

            val forbidden = service.setResolutionRating(created.value.ticket.id, otherUserId, 1)
            assertTrue(forbidden is SupportServiceResult.Failure)
            assertEquals(SupportServiceError.TicketForbidden, (forbidden as SupportServiceResult.Failure).error)
        }

    @Test
    fun `rating update is atomic`() =
        runBlocking {
            val userId = insertUser(username = "guest", displayName = "Guest")
            val agentId = insertUser(username = "agent", displayName = "Agent")
            val clubId = insertClub(name = "Aurora")

            val created =
                service.createTicket(
                    clubId = clubId,
                    userId = userId,
                    bookingId = null,
                    listEntryId = null,
                    topic = TicketTopic.OTHER,
                    text = "Need help",
                    attachments = null,
                ) as SupportServiceResult.Success

            seedLegacyStatus(created.value.ticket.id, TicketStatus.OPENED)
            service.reply(
                ticketId = created.value.ticket.id,
                agentUserId = agentId,
                text = "Resolved",
                attachments = null,
            )

            val first = service.setResolutionRating(created.value.ticket.id, userId, 1)
            assertTrue(first is SupportServiceResult.Success)

            val second = service.setResolutionRating(created.value.ticket.id, userId, 1)
            assertTrue(second is SupportServiceResult.Failure)
            assertEquals(SupportServiceError.RatingAlreadySet, (second as SupportServiceResult.Failure).error)
        }

    private suspend fun createTicket(
        userId: Long,
        clubId: Long,
        text: String,
    ) = (
        service.createTicket(
            clubId = clubId,
            userId = userId,
            bookingId = null,
            listEntryId = null,
            topic = TicketTopic.OTHER,
            text = text,
            attachments = null,
        ) as SupportServiceResult.Success
    ).value

    private fun seedLegacyStatus(
        ticketId: Long,
        status: TicketStatus,
    ) {
        require(status != TicketStatus.NEW)
        transaction(database) {
            val updated =
                TicketsTable.update({ TicketsTable.id eq ticketId }) {
                    it[TicketsTable.status] = status.wire
                }
            assertEquals(1, updated)
        }
    }

    private fun messageCount(ticketId: Long): Long =
        transaction(database) {
            TicketMessagesTable
                .selectAll()
                .where { TicketMessagesTable.ticketId eq ticketId }
                .count()
        }

    private fun assertInvalidState(result: SupportServiceResult<*>) {
        assertTrue(result is SupportServiceResult.Failure)
        assertEquals(
            SupportServiceError.InvalidState,
            (result as SupportServiceResult.Failure).error,
        )
    }

    private fun assertTicketNotFound(result: SupportServiceResult<*>) {
        assertTrue(result is SupportServiceResult.Failure)
        assertEquals(
            SupportServiceError.TicketNotFound,
            (result as SupportServiceResult.Failure).error,
        )
    }

    private fun assertPersistenceFailure(
        result: SupportServiceResult<*>,
        vararg forbiddenDetails: String,
    ) {
        assertTrue(result is SupportServiceResult.Failure)
        assertEquals(
            SupportServiceError.PersistenceFailure,
            (result as SupportServiceResult.Failure).error,
        )
        val publicResult = result.toString()
        forbiddenDetails.forEach { detail ->
            assertFalse(publicResult.contains(detail, ignoreCase = true), publicResult)
        }
    }

    private fun assertNoTicketOrMessageRows() {
        transaction(database) {
            assertEquals(0L, TicketsTable.selectAll().count())
            assertEquals(0L, TicketMessagesTable.selectAll().count())
        }
    }

    private fun insertUser(
        username: String,
        displayName: String,
    ): Long =
        transaction(database) {
            TestUsersTable
                .insert {
                    it[TestUsersTable.telegramUserId] = null
                    it[TestUsersTable.username] = username
                    it[TestUsersTable.displayName] = displayName
                    it[TestUsersTable.phoneE164] = null
                } get TestUsersTable.id
        }

    private fun insertClub(
        name: String,
        timezone: String = "Europe/Moscow",
    ): Long =
        transaction(database) {
            TestClubsTable
                .insert {
                    it[TestClubsTable.name] = name
                    it[TestClubsTable.description] = null
                    it[TestClubsTable.timezone] = timezone
                    it[TestClubsTable.adminChannelId] = null
                    it[TestClubsTable.bookingsTopicId] = null
                    it[TestClubsTable.checkinTopicId] = null
                    it[TestClubsTable.qaTopicId] = null
                } get TestClubsTable.id
        }
}

private object TestClubsTable : Table("clubs") {
    val id = long("id").autoIncrement()
    val name = text("name")
    val description = text("description").nullable()
    val timezone = text("timezone")
    val adminChannelId = long("admin_channel_id").nullable()
    val bookingsTopicId = integer("bookings_topic_id").nullable()
    val checkinTopicId = integer("checkin_topic_id").nullable()
    val qaTopicId = integer("qa_topic_id").nullable()
    override val primaryKey = PrimaryKey(id)
}

private object TestUsersTable : Table("users") {
    val id = long("id").autoIncrement()
    val telegramUserId = long("telegram_user_id").nullable()
    val username = text("username").nullable()
    val displayName = text("display_name").nullable()
    val phoneE164 = text("phone_e164").nullable()
    override val primaryKey = PrimaryKey(id)
}
