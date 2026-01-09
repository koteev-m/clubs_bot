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
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

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

            service.addGuestMessage(
                ticketId = created.value.ticket.id,
                userId = userId,
                text = "Adding more context",
                attachments = null,
            )

            service.assign(ticketId = created.value.ticket.id, agentUserId = agentId)
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
    fun `closed ticket blocks guest message`() =
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

            service.setStatus(created.value.ticket.id, agentId, TicketStatus.CLOSED)

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
