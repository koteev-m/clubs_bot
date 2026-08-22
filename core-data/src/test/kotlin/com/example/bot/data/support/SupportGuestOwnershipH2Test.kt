package com.example.bot.data.support

import com.example.bot.support.SupportServiceError
import com.example.bot.support.SupportServiceResult
import com.example.bot.support.TicketSenderType
import com.example.bot.support.TicketStatus
import com.example.bot.support.TicketTopic
import com.example.bot.support.TicketWithMessage
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

class SupportGuestOwnershipH2Test {
    private lateinit var dataSource: HikariDataSource
    private lateinit var database: Database
    private lateinit var repository: SupportRepository
    private lateinit var service: SupportServiceImpl

    private val fixedInstant = Instant.parse("2024-06-01T00:00:00Z")
    private val fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC)

    @BeforeEach
    fun setUp() {
        val dbName = "support-guest-ownership-${UUID.randomUUID()}"
        dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false"
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
    fun `guest list and complete thread reads are owner scoped ordered and durable`() =
        runBlocking {
            val ownerUserId = insertUser(username = "owner", displayName = "Owner")
            val otherUserId = insertUser(username = "other", displayName = "Other")
            val clubId = insertClub(name = "Aurora")
            val foreignText = "foreign-message-sentinel"
            val foreignAttachments = """[{"name":"foreign-attachment-sentinel.txt"}]"""

            val olderOwned = createTicket(ownerUserId, clubId, "owned older ticket")
            seedTicketUpdatedAt(olderOwned.ticket.id, fixedInstant.minusSeconds(60))

            val ownedInitialAttachments = """[{"name":"owned-initial.txt"}]"""
            val ownedThreadResult =
                service.createTicket(
                    clubId = clubId,
                    userId = ownerUserId,
                    bookingId = null,
                    listEntryId = null,
                    topic = TicketTopic.COMPLAINT,
                    text = "owned initial message",
                    attachments = ownedInitialAttachments,
                )
            assertTrue(ownedThreadResult is SupportServiceResult.Success)
            val ownedThreadCreated = (ownedThreadResult as SupportServiceResult.Success).value

            val newestOwned = createTicket(ownerUserId, clubId, "owned newest ticket")
            val foreignTicketResult =
                service.createTicket(
                    clubId = clubId,
                    userId = otherUserId,
                    bookingId = null,
                    listEntryId = null,
                    topic = TicketTopic.INVITE,
                    text = foreignText,
                    attachments = foreignAttachments,
                )
            assertTrue(foreignTicketResult is SupportServiceResult.Success)
            val foreignTicket = (foreignTicketResult as SupportServiceResult.Success).value.ticket

            val laterGuestAttachments = """[{"name":"owned-guest.txt"}]"""
            val laterGuestResult =
                service.addGuestMessage(
                    ticketId = ownedThreadCreated.ticket.id,
                    userId = ownerUserId,
                    text = "owned later guest message",
                    attachments = laterGuestAttachments,
                )
            assertTrue(laterGuestResult is SupportServiceResult.Success)
            val laterGuestMessage = (laterGuestResult as SupportServiceResult.Success).value

            val agentAttachments = """[{"name":"owned-agent.txt"}]"""
            val agentMessageId =
                insertMessage(
                    ticketId = ownedThreadCreated.ticket.id,
                    senderType = TicketSenderType.AGENT,
                    text = "owned agent reply",
                    attachments = agentAttachments,
                    createdAt = fixedInstant,
                )
            seedLegacyStatus(ownedThreadCreated.ticket.id, TicketStatus.ANSWERED)

            val summaries = service.listMyTickets(ownerUserId)
            assertEquals(
                listOf(newestOwned.ticket.id, ownedThreadCreated.ticket.id, olderOwned.ticket.id),
                summaries.map { it.id },
            )
            assertFalse(summaries.any { it.id == foreignTicket.id })
            assertFalse(summaries.toString().contains(foreignText))
            assertFalse(summaries.toString().contains(foreignAttachments))

            val ownedRead = service.getMyTicket(ownedThreadCreated.ticket.id, ownerUserId)
            assertTrue(ownedRead is SupportServiceResult.Success)
            val ownedThread = (ownedRead as SupportServiceResult.Success).value
            assertEquals(ownedThreadCreated.ticket.id, ownedThread.ticket.id)
            assertEquals(clubId, ownedThread.ticket.clubId)
            assertEquals(TicketTopic.COMPLAINT, ownedThread.ticket.topic)
            assertEquals(TicketStatus.ANSWERED, ownedThread.ticket.status)
            assertEquals(fixedInstant, ownedThread.ticket.createdAt)
            assertEquals(fixedInstant, ownedThread.ticket.updatedAt)
            assertEquals(
                listOf(
                    ownedThreadCreated.initialMessage.id,
                    laterGuestMessage.id,
                    agentMessageId,
                ),
                ownedThread.messages.map { it.id },
            )
            assertEquals(
                listOf(fixedInstant, fixedInstant, fixedInstant),
                ownedThread.messages.map { it.createdAt },
            )
            assertEquals(
                listOf(TicketSenderType.GUEST, TicketSenderType.GUEST, TicketSenderType.AGENT),
                ownedThread.messages.map { it.senderType },
            )
            assertEquals(
                listOf("owned initial message", "owned later guest message", "owned agent reply"),
                ownedThread.messages.map { it.text },
            )
            assertEquals(
                listOf(ownedInitialAttachments, laterGuestAttachments, agentAttachments),
                ownedThread.messages.map { it.attachments },
            )
            assertFalse(ownedThread.toString().contains(foreignText))
            assertFalse(ownedThread.toString().contains(foreignAttachments))

            val foreignRead = service.getMyTicket(ownedThreadCreated.ticket.id, otherUserId)
            val missingRead = service.getMyTicket(Long.MAX_VALUE, otherUserId)
            assertTicketNotFound(foreignRead)
            assertTicketNotFound(missingRead)
            assertEquals(missingRead, foreignRead)

            val restartedService = SupportServiceImpl(SupportRepository(database, fixedClock))
            val restartedRead = restartedService.getMyTicket(ownedThreadCreated.ticket.id, ownerUserId)
            assertTrue(restartedRead is SupportServiceResult.Success)
            assertEquals(ownedThread, (restartedRead as SupportServiceResult.Success).value)
        }

    @Test
    fun `staff list and detail stay club scoped deterministic minimal and durable`() =
        runBlocking {
            val guestUserId = insertUser(username = "guest", displayName = "Guest")
            val clubA = insertClub(name = "Club A")
            val clubB = insertClub(name = "Club B")
            val older = createTicket(guestUserId, clubA, "club-a-older")
            val opened = createTicket(guestUserId, clubA, "club-a-initial")
            val foreign = createTicket(guestUserId, clubB, "FOREIGN_MESSAGE_SENTINEL")
            val tiedUpdatedAt = fixedInstant.plusSeconds(120)
            seedTicketUpdatedAt(older.ticket.id, tiedUpdatedAt)
            seedTicketUpdatedAt(opened.ticket.id, tiedUpdatedAt)
            seedTicketUpdatedAt(foreign.ticket.id, tiedUpdatedAt.plusSeconds(60))
            seedLegacyStatus(older.ticket.id, TicketStatus.OPENED)

            val latestByTimeId =
                insertMessage(
                    ticketId = opened.ticket.id,
                    senderType = TicketSenderType.AGENT,
                    text = "latest-by-created-at",
                    attachments = "latest-attachment",
                    createdAt = fixedInstant.plusSeconds(60),
                )
            val higherIdButOlder =
                insertMessage(
                    ticketId = opened.ticket.id,
                    senderType = TicketSenderType.GUEST,
                    text = "higher-id-but-older",
                    attachments = null,
                    createdAt = fixedInstant.plusSeconds(30),
                )
            assertTrue(latestByTimeId < higherIdButOlder)

            val listResult = service.listStaffTicketsForClub(clubA, status = null)
            assertTrue(listResult is SupportServiceResult.Success)
            val summaries = (listResult as SupportServiceResult.Success).value
            assertEquals(listOf(opened.ticket.id, older.ticket.id), summaries.map { it.id })
            assertEquals("latest-by-created-at", summaries.first().lastMessagePreview)
            assertEquals(TicketSenderType.AGENT, summaries.first().lastSenderType)
            assertFalse(summaries.toString().contains("FOREIGN_MESSAGE_SENTINEL"))

            val filteredResult = service.listStaffTicketsForClub(clubA, TicketStatus.OPENED)
            assertTrue(filteredResult is SupportServiceResult.Success)
            assertEquals(
                listOf(older.ticket.id),
                (filteredResult as SupportServiceResult.Success).value.map { it.id },
            )

            val detailResult = service.getStaffTicket(opened.ticket.id, setOf(clubA))
            assertTrue(detailResult is SupportServiceResult.Success)
            val thread = (detailResult as SupportServiceResult.Success).value
            assertEquals(opened.ticket.id, thread.ticket.id)
            assertEquals(clubA, thread.ticket.clubId)
            assertEquals(
                listOf(opened.initialMessage.id, higherIdButOlder, latestByTimeId),
                thread.messages.map { it.id },
            )
            assertEquals(
                listOf("club-a-initial", "higher-id-but-older", "latest-by-created-at"),
                thread.messages.map { it.text },
            )
            assertFalse(thread.toString().contains("FOREIGN_MESSAGE_SENTINEL"))

            val foreignRead = service.getStaffTicket(foreign.ticket.id, setOf(clubA))
            val missingRead = service.getStaffTicket(Long.MAX_VALUE, setOf(clubA))
            assertTicketNotFound(foreignRead)
            assertTicketNotFound(missingRead)
            assertEquals(missingRead, foreignRead)

            val foreignMutation = service.getStaffMutationTicket(foreign.ticket.id, setOf(clubA))
            val missingMutation = service.getStaffMutationTicket(Long.MAX_VALUE, setOf(clubA))
            assertTicketNotFound(foreignMutation)
            assertTicketNotFound(missingMutation)
            assertEquals(missingMutation, foreignMutation)

            val restartedService = SupportServiceImpl(SupportRepository(database, fixedClock))
            assertEquals(detailResult, restartedService.getStaffTicket(opened.ticket.id, setOf(clubA)))
        }

    @Test
    fun `staff ticket reads rethrow cancellation`() {
        val cancellingRepository = mockk<SupportRepository>()
        val cancellation = CancellationException("cancel staff thread read")
        coEvery { cancellingRepository.findStaffTicketThread(101L, setOf(202L)) } throws cancellation
        val cancellingService = SupportServiceImpl(cancellingRepository)

        val thrown =
            assertThrows(CancellationException::class.java) {
                runBlocking { cancellingService.getStaffTicket(ticketId = 101L, permittedClubIds = setOf(202L)) }
            }

        assertEquals(cancellation.message, thrown.message)
    }

    @Test
    fun `staff ticket read failures return detail-free persistence failure`() =
        runBlocking {
            val failingRepository = mockk<SupportRepository>()
            val rawDetail = "foreign-message-sentinel ticket_messages SQLState 23514"
            coEvery { failingRepository.listTicketsByClub(303L, null) } throws
                SQLException(rawDetail, "23514")
            coEvery { failingRepository.findStaffTicketThread(404L, setOf(303L)) } throws
                SQLException(rawDetail, "23514")
            val failingService = SupportServiceImpl(failingRepository)

            val listResult = failingService.listStaffTicketsForClub(clubId = 303L, status = null)
            val detailResult = failingService.getStaffTicket(ticketId = 404L, permittedClubIds = setOf(303L))

            assertPersistenceFailure(listResult, rawDetail, "ticket_messages", "23514")
            assertPersistenceFailure(detailResult, rawDetail, "ticket_messages", "23514")
        }

    @Test
    fun `owned ticket without persisted messages is not returned as a partial thread`() =
        runBlocking {
            val userId = insertUser(username = "guest", displayName = "Guest")
            val clubId = insertClub(name = "Aurora")
            val created = createTicket(userId, clubId, "message to remove")

            transaction(database) {
                val deleted =
                    TicketMessagesTable.deleteWhere {
                        TicketMessagesTable.ticketId eq created.ticket.id
                    }
                assertEquals(1, deleted)
            }

            assertNotNull(repository.findTicket(created.ticket.id))
            assertNull(repository.findTicketThreadByUser(created.ticket.id, userId))
            assertTicketNotFound(service.getMyTicket(created.ticket.id, userId))
        }

    @Test
    fun `guest ticket read rethrows cancellation`() {
        val cancellingRepository = mockk<SupportRepository>()
        val cancellation = CancellationException("cancel owned thread read")
        coEvery { cancellingRepository.findTicketThreadByUser(101L, 202L) } throws cancellation
        val cancellingService = SupportServiceImpl(cancellingRepository)

        val thrown =
            assertThrows(CancellationException::class.java) {
                runBlocking { cancellingService.getMyTicket(ticketId = 101L, userId = 202L) }
            }

        assertEquals(cancellation.message, thrown.message)
    }

    @Test
    fun `guest ticket read failure returns detail-free persistence failure`() =
        runBlocking {
            val failingRepository = mockk<SupportRepository>()
            val rawDetail = "foreign-message-sentinel ticket_messages SQLState 23514"
            coEvery { failingRepository.findTicketThreadByUser(303L, 404L) } throws
                SQLException(rawDetail, "23514")
            val failingService = SupportServiceImpl(failingRepository)

            val result = failingService.getMyTicket(ticketId = 303L, userId = 404L)

            assertPersistenceFailure(result, rawDetail, "ticket_messages", "23514")
        }

    @Test
    fun `guest ticket read does not swallow JVM errors`() {
        val failingRepository = mockk<SupportRepository>()
        val fatal = AssertionError("fatal owned thread read")
        coEvery { failingRepository.findTicketThreadByUser(505L, 606L) } throws fatal
        val failingService = SupportServiceImpl(failingRepository)

        val thrown =
            assertThrows(AssertionError::class.java) {
                runBlocking { failingService.getMyTicket(ticketId = 505L, userId = 606L) }
            }

        assertEquals(fatal.message, thrown.message)
    }

    private suspend fun createTicket(
        userId: Long,
        clubId: Long,
        text: String,
    ): TicketWithMessage =
        (
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

    private fun seedTicketUpdatedAt(
        ticketId: Long,
        updatedAt: Instant,
    ) {
        transaction(database) {
            val updated =
                TicketsTable.update({ TicketsTable.id eq ticketId }) {
                    it[TicketsTable.updatedAt] = updatedAt.atOffset(ZoneOffset.UTC)
                }
            assertEquals(1, updated)
        }
    }

    private fun insertMessage(
        ticketId: Long,
        senderType: TicketSenderType,
        text: String,
        attachments: String?,
        createdAt: Instant,
    ): Long =
        transaction(database) {
            TicketMessagesTable.insert {
                it[TicketMessagesTable.ticketId] = ticketId
                it[TicketMessagesTable.senderType] = senderType.wire
                it[TicketMessagesTable.text] = text
                it[TicketMessagesTable.attachments] = attachments
                it[TicketMessagesTable.createdAt] = createdAt.atOffset(ZoneOffset.UTC)
            }[TicketMessagesTable.id]
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

    private fun insertUser(
        username: String,
        displayName: String,
    ): Long =
        transaction(database) {
            SupportGuestOwnershipH2UsersTable
                .insert {
                    it[SupportGuestOwnershipH2UsersTable.telegramUserId] = null
                    it[SupportGuestOwnershipH2UsersTable.username] = username
                    it[SupportGuestOwnershipH2UsersTable.displayName] = displayName
                    it[SupportGuestOwnershipH2UsersTable.phoneE164] = null
                } get SupportGuestOwnershipH2UsersTable.id
        }

    private fun insertClub(
        name: String,
        timezone: String = "Europe/Moscow",
    ): Long =
        transaction(database) {
            SupportGuestOwnershipH2ClubsTable
                .insert {
                    it[SupportGuestOwnershipH2ClubsTable.name] = name
                    it[SupportGuestOwnershipH2ClubsTable.description] = null
                    it[SupportGuestOwnershipH2ClubsTable.timezone] = timezone
                    it[SupportGuestOwnershipH2ClubsTable.adminChannelId] = null
                    it[SupportGuestOwnershipH2ClubsTable.bookingsTopicId] = null
                    it[SupportGuestOwnershipH2ClubsTable.checkinTopicId] = null
                    it[SupportGuestOwnershipH2ClubsTable.qaTopicId] = null
                } get SupportGuestOwnershipH2ClubsTable.id
        }
}

private object SupportGuestOwnershipH2ClubsTable : Table("clubs") {
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

private object SupportGuestOwnershipH2UsersTable : Table("users") {
    val id = long("id").autoIncrement()
    val telegramUserId = long("telegram_user_id").nullable()
    val username = text("username").nullable()
    val displayName = text("display_name").nullable()
    val phoneE164 = text("phone_e164").nullable()
    override val primaryKey = PrimaryKey(id)
}
