package com.example.bot.support

import com.example.bot.clubs.Club
import com.example.bot.clubs.InMemoryClubsRepository
import com.example.bot.data.support.SupportRepository
import com.example.bot.data.support.SupportServiceImpl
import com.example.bot.data.support.findReplyDelivery
import com.example.bot.telegram.SupportCallbacks
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.response.BaseResponse
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Statement
import java.sql.Types
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.fail

class SupportReplyDeliveryServiceTest {
    private lateinit var fixture: DeliveryServiceH2Fixture

    @BeforeEach
    fun setUp() {
        fixture = DeliveryServiceH2Fixture()
    }

    @AfterEach
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `confirmed response delivers persisted text to authoritative recipient exactly once`() =
        runBlocking {
            val pending = fixture.createPendingDelivery(replyText = "request text")
            fixture.updatePersistedReplyText(pending.replyMessageId, "persisted authoritative reply")
            val gateway = RecordingSupportReplyTelegramGateway(response = telegramResponse(isOk = true))
            val service = fixture.deliveryService(pending, gateway)

            assertSame(SupportReplyDeliveryOutcome.Delivered, service.deliver(pending.deliveryId))
            val record = fixture.repository.findReplyDelivery(pending.deliveryId)
            assertEquals(SupportReplyDeliveryStatus.DELIVERED, record?.status)
            assertNull(record?.failureCode)

            val request = assertIs<SendMessage>(gateway.requests.single())
            assertEquals(pending.recipientTelegramUserId, request.parameters["chat_id"])
            assertEquals(
                "Ответ от клуба «${pending.clubName}»\n\npersisted authoritative reply",
                request.parameters["text"],
            )
            val markup = assertIs<InlineKeyboardMarkup>(request.parameters["reply_markup"])
            val callbacks = markup.inlineKeyboard().flatMap { it.asList() }.map { it.callbackData }
            assertEquals(
                listOf(
                    SupportCallbacks.buildRate(pending.ticketId, up = true),
                    SupportCallbacks.buildRate(pending.ticketId, up = false),
                ),
                callbacks,
            )

            assertSame(SupportReplyDeliveryOutcome.PersistenceFailure, service.deliver(pending.deliveryId))
            assertEquals(1, gateway.requests.size)
        }

    @Test
    fun `concurrent delivery callers produce one telegram request`() =
        runBlocking {
            val pending = fixture.createPendingDelivery()
            val sendStarted = CompletableDeferred<Unit>()
            val releaseSend = CompletableDeferred<Unit>()
            val gateway = RecordingSupportReplyTelegramGateway(response = telegramResponse(isOk = true))
            gateway.sendBehavior = {
                sendStarted.complete(Unit)
                releaseSend.await()
                telegramResponse(isOk = true)
            }
            val service = fixture.deliveryService(pending, gateway)

            coroutineScope {
                val sender = async { service.deliver(pending.deliveryId) }
                sendStarted.await()
                val duplicate = async { service.deliver(pending.deliveryId) }

                assertSame(SupportReplyDeliveryOutcome.PersistenceFailure, duplicate.await())
                assertEquals(1, gateway.requests.size)
                releaseSend.complete(Unit)
                assertSame(SupportReplyDeliveryOutcome.Delivered, sender.await())
            }
            assertEquals(1, gateway.requests.size)
            assertEquals(
                SupportReplyDeliveryStatus.DELIVERED,
                fixture.repository.findReplyDelivery(pending.deliveryId)?.status,
            )
        }

    @Test
    fun `telegram rejection is a bounded failed result`() =
        runBlocking {
            val pending = fixture.createPendingDelivery()
            val gateway = RecordingSupportReplyTelegramGateway(response = telegramResponse(isOk = false))

            val outcome = fixture.deliveryService(pending, gateway).deliver(pending.deliveryId)

            assertSame(SupportReplyDeliveryOutcome.Failed, outcome)
            val record = fixture.repository.findReplyDelivery(pending.deliveryId)
            assertEquals(SupportReplyDeliveryStatus.FAILED, record?.status)
            assertEquals(SupportReplyDeliveryFailureCode.TELEGRAM_REJECTED, record?.failureCode)
            assertEquals(1, gateway.requests.size)
        }

    @Test
    fun `missing recipient telegram id fails without a telegram attempt`() =
        runBlocking {
            val pending = fixture.createPendingDelivery(recipientTelegramUserId = null)
            val gateway = RecordingSupportReplyTelegramGateway(response = telegramResponse(isOk = true))

            val outcome = fixture.deliveryService(pending, gateway).deliver(pending.deliveryId)

            assertSame(SupportReplyDeliveryOutcome.Failed, outcome)
            val record = fixture.repository.findReplyDelivery(pending.deliveryId)
            assertEquals(SupportReplyDeliveryStatus.FAILED, record?.status)
            assertEquals(SupportReplyDeliveryFailureCode.RECIPIENT_UNAVAILABLE, record?.failureCode)
            assertEquals(0, gateway.requests.size)
        }

    @Test
    fun `invalid recipient telegram id fails without a telegram attempt`() =
        runBlocking {
            val pending = fixture.createPendingDelivery(recipientTelegramUserId = 0L)
            val gateway = RecordingSupportReplyTelegramGateway(response = telegramResponse(isOk = true))

            val outcome = fixture.deliveryService(pending, gateway).deliver(pending.deliveryId)

            assertSame(SupportReplyDeliveryOutcome.Failed, outcome)
            val record = fixture.repository.findReplyDelivery(pending.deliveryId)
            assertEquals(SupportReplyDeliveryStatus.FAILED, record?.status)
            assertEquals(SupportReplyDeliveryFailureCode.RECIPIENT_UNAVAILABLE, record?.failureCode)
            assertEquals(0, gateway.requests.size)
        }

    @Test
    fun `unconfigured telegram client fails without a telegram attempt`() =
        runBlocking {
            val pending = fixture.createPendingDelivery()
            val gateway =
                RecordingSupportReplyTelegramGateway(
                    isConfigured = false,
                    response = telegramResponse(isOk = true),
                )

            val outcome = fixture.deliveryService(pending, gateway).deliver(pending.deliveryId)

            assertSame(SupportReplyDeliveryOutcome.Failed, outcome)
            val record = fixture.repository.findReplyDelivery(pending.deliveryId)
            assertEquals(SupportReplyDeliveryStatus.FAILED, record?.status)
            assertEquals(SupportReplyDeliveryFailureCode.CLIENT_UNAVAILABLE, record?.failureCode)
            assertEquals(0, gateway.requests.size)
        }

    @Test
    fun `timeout is unconfirmed after one telegram attempt`() =
        runBlocking {
            val pending = fixture.createPendingDelivery()
            val gateway = RecordingSupportReplyTelegramGateway(response = telegramResponse(isOk = true))
            gateway.sendBehavior = { awaitCancellation() }

            val outcome =
                fixture
                    .deliveryService(pending, gateway, sendTimeout = Duration.ofMillis(20))
                    .deliver(pending.deliveryId)

            assertSame(SupportReplyDeliveryOutcome.Unconfirmed, outcome)
            val record = fixture.repository.findReplyDelivery(pending.deliveryId)
            assertEquals(SupportReplyDeliveryStatus.UNCONFIRMED, record?.status)
            assertEquals(SupportReplyDeliveryFailureCode.TIMEOUT, record?.failureCode)
            assertEquals(1, gateway.requests.size)
        }

    @Test
    fun `transport exception is unconfirmed after one telegram attempt`() =
        runBlocking {
            val pending = fixture.createPendingDelivery()
            val gateway = RecordingSupportReplyTelegramGateway(response = telegramResponse(isOk = true))
            gateway.sendBehavior = { throw IllegalStateException("private transport detail") }

            val outcome = fixture.deliveryService(pending, gateway).deliver(pending.deliveryId)

            assertSame(SupportReplyDeliveryOutcome.Unconfirmed, outcome)
            val record = fixture.repository.findReplyDelivery(pending.deliveryId)
            assertEquals(SupportReplyDeliveryStatus.UNCONFIRMED, record?.status)
            assertEquals(SupportReplyDeliveryFailureCode.TRANSPORT_ERROR, record?.failureCode)
            assertEquals(1, gateway.requests.size)
        }

    @Test
    fun `cancellation is finalized best effort and the same exception is rethrown`() =
        runBlocking {
            val pending = fixture.createPendingDelivery()
            val cancellation = IdentityStableCancellation.create()
            val gateway = RecordingSupportReplyTelegramGateway(response = telegramResponse(isOk = true))
            gateway.sendBehavior = { throw cancellation }
            val service = fixture.deliveryService(pending, gateway)

            val thrown =
                try {
                    service.deliver(pending.deliveryId)
                    fail("delivery cancellation must be rethrown")
                } catch (actual: CancellationException) {
                    actual
                }

            assertSame(cancellation, thrown)
            val record = fixture.repository.findReplyDelivery(pending.deliveryId)
            assertEquals(SupportReplyDeliveryStatus.UNCONFIRMED, record?.status)
            assertEquals(SupportReplyDeliveryFailureCode.CANCELED, record?.failureCode)
            assertEquals(1, gateway.requests.size)
        }
}

private class RecordingSupportReplyTelegramGateway(
    override val isConfigured: Boolean = true,
    response: BaseResponse,
) : SupportReplyTelegramGateway {
    val requests = mutableListOf<BaseRequest<*, *>>()
    var sendBehavior: suspend (BaseRequest<*, *>) -> BaseResponse = { response }

    override suspend fun send(request: BaseRequest<*, *>): BaseResponse {
        requests += request
        return sendBehavior(request)
    }
}

private class DeliveryServiceH2Fixture : AutoCloseable {
    private val dataSource: HikariDataSource
    private val database: Database
    val repository: SupportRepository

    init {
        dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl =
                        "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;" +
                        "DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false"
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
            .also {
                it.clean()
                it.migrate()
            }
        database = Database.connect(dataSource)
        repository =
            SupportRepository(
                db = database,
                clock = Clock.fixed(TEST_NOW, ZoneOffset.UTC),
                auditFingerprintFactory = { action, ticketId -> "test:$action:$ticketId" },
            )
    }

    suspend fun createPendingDelivery(
        replyText: String = "persisted reply",
        recipientTelegramUserId: Long? = 9_200_001L,
    ): PendingDelivery {
        val clubName = "Authoritative Club"
        val clubId = insertClub(clubName)
        val recipientUserId = insertUser("recipient", recipientTelegramUserId)
        val actingStaffUserId = insertUser("manager", 9_300_001L)
        val assignmentId = insertManagerAssignment(actingStaffUserId, clubId)
        grantSupportReply(assignmentId)
        val supportService = SupportServiceImpl(repository)
        val ticket =
            assertIs<SupportServiceResult.Success<TicketWithMessage>>(
                supportService.createTicket(
                    clubId = clubId,
                    userId = recipientUserId,
                    bookingId = null,
                    listEntryId = null,
                    topic = TicketTopic.OTHER,
                    text = "guest question",
                    attachments = null,
                ),
            ).value.ticket
        val reply =
            assertIs<SupportServiceResult.Success<SupportReplyResult>>(
                supportService.reply(
                    ticketId = ticket.id,
                    agentUserId = actingStaffUserId,
                    text = replyText,
                    attachments = null,
                ),
            ).value
        assertEquals(SupportReplyDeliveryStatus.PENDING, reply.deliveryStatus)
        return PendingDelivery(
            deliveryId = reply.deliveryId,
            replyMessageId = reply.replyMessage.id,
            ticketId = ticket.id,
            clubId = clubId,
            clubName = clubName,
            recipientTelegramUserId = recipientTelegramUserId,
        )
    }

    fun deliveryService(
        pending: PendingDelivery,
        gateway: SupportReplyTelegramGateway,
        sendTimeout: Duration = Duration.ofSeconds(1),
    ): SupportReplyDeliveryServiceImpl =
        SupportReplyDeliveryServiceImpl(
            repository = repository,
            clubsRepository =
                InMemoryClubsRepository(
                    listOf(
                        Club(
                            id = pending.clubId,
                            city = "Moscow",
                            name = pending.clubName,
                            genres = emptyList(),
                            tags = emptyList(),
                            logoUrl = null,
                        ),
                    ),
                ),
            telegramGateway = gateway,
            sendTimeout = sendTimeout,
            cancellationCleanupTimeout = Duration.ofSeconds(1),
        )

    fun updatePersistedReplyText(
        replyMessageId: Long,
        text: String,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("UPDATE ticket_messages SET text = ? WHERE id = ?").use { statement ->
                statement.setString(1, text)
                statement.setLong(2, replyMessageId)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    override fun close() {
        dataSource.close()
    }

    private fun insertClub(name: String): Long =
        insertAndReturnId(
            """
            INSERT INTO clubs (
                name, description, timezone, admin_channel_id, bookings_topic_id, checkin_topic_id, qa_topic_id
            ) VALUES (?, NULL, 'Europe/Moscow', NULL, NULL, NULL, NULL)
            """.trimIndent(),
        ) { statement ->
            statement.setString(1, name)
        }

    private fun insertUser(
        username: String,
        telegramUserId: Long?,
    ): Long =
        insertAndReturnId(
            """
            INSERT INTO users (telegram_user_id, username, display_name, phone_e164)
            VALUES (?, ?, ?, NULL)
            """.trimIndent(),
        ) { statement ->
            if (telegramUserId == null) {
                statement.setNull(1, Types.BIGINT)
            } else {
                statement.setLong(1, telegramUserId)
            }
            statement.setString(2, "$username-${UUID.randomUUID()}")
            statement.setString(3, username)
        }

    private fun insertManagerAssignment(
        userId: Long,
        clubId: Long,
    ): Long =
        insertAndReturnId(
            """
            INSERT INTO user_roles (user_id, role_code, scope_type, scope_club_id)
            VALUES (?, 'MANAGER', 'CLUB', ?)
            """.trimIndent(),
        ) { statement ->
            statement.setLong(1, userId)
            statement.setLong(2, clubId)
        }

    private fun grantSupportReply(assignmentId: Long) {
        dataSource.connection.use { connection ->
            connection
                .prepareStatement(
                    "INSERT INTO user_role_permissions (user_role_id, permission_code) VALUES (?, 'support.reply')",
                ).use { statement ->
                    statement.setLong(1, assignmentId)
                    assertEquals(1, statement.executeUpdate())
                }
        }
    }

    private fun insertAndReturnId(
        sql: String,
        bind: (java.sql.PreparedStatement) -> Unit,
    ): Long =
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { statement ->
                bind(statement)
                assertEquals(1, statement.executeUpdate())
                statement
                    .generatedKeys
                    .use { keys ->
                        check(keys.next()) { "generated key is required" }
                        keys.getLong(1)
                    }
            }
        }

    private companion object {
        val TEST_NOW: Instant = Instant.parse("2026-08-23T10:00:00Z")
    }
}

private data class PendingDelivery(
    val deliveryId: Long,
    val replyMessageId: Long,
    val ticketId: Long,
    val clubId: Long,
    val clubName: String,
    val recipientTelegramUserId: Long?,
)

private fun telegramResponse(isOk: Boolean): BaseResponse =
    mockk {
        every { this@mockk.isOk } returns isOk
    }

private class IdentityStableCancellation private constructor() : CancellationException("delivery canceled") {
    companion object {
        fun create(): IdentityStableCancellation = IdentityStableCancellation()
    }
}
