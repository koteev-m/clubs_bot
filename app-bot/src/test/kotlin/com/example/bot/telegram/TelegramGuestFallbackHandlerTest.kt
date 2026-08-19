package com.example.bot.telegram

import com.example.bot.booking.a3.Booking
import com.example.bot.booking.a3.BookingState
import com.example.bot.booking.a3.BookingStatus
import com.example.bot.clubs.Club
import com.example.bot.clubs.ClubsRepository
import com.example.bot.data.security.User
import com.example.bot.data.security.UserIdentityProvisioner
import com.example.bot.data.security.UserRepository
import com.example.bot.support.SupportReplyResult
import com.example.bot.support.SupportService
import com.example.bot.support.SupportServiceResult
import com.example.bot.support.Ticket
import com.example.bot.support.TicketMessage
import com.example.bot.support.TicketStatus
import com.example.bot.support.TicketSummary
import com.example.bot.support.TicketTopic
import com.example.bot.support.TicketWithMessage
import com.pengrad.telegrambot.model.CallbackQuery
import com.pengrad.telegrambot.model.Chat
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.model.User as TelegramUser
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.request.AnswerCallbackQuery
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.response.BaseResponse
import io.mockk.every
import io.mockk.mockk
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TelegramGuestFallbackHandlerTest {
    @Test
    fun `bare start provisions first identity and welcomes user with exact mini app url`() =
        runBlocking {
            val sender = FallbackRecordingTelegramSender()
            val identities = InMemoryIdentityStore()
            val handler =
                handler(
                    sender = sender,
                    now = TEST_NOW,
                    bookings = emptyList(),
                    userRepository = identities,
                    userIdentityProvisioner = identities,
                    botUsername = "NightConcierge_Bot",
                    miniAppUrl = TEST_MINI_APP_URL,
                )

            val handled = handler.handle(messageUpdate(text = " \n/start\t"))

            assertTrue(handled)
            assertEquals(1, sender.requests.size)
            val request = sender.requests.single() as SendMessage
            val markup = request.parameters["reply_markup"] as InlineKeyboardMarkup
            val buttons = markup.inlineKeyboard().flatMap { it.asList() }
            assertEquals("Добро пожаловать в Night Concierge!", request.parameters["text"])
            assertEquals(1, buttons.size)
            assertEquals("Открыть Night Concierge", buttons.single().text)
            assertEquals(TEST_MINI_APP_URL, buttons.single().webApp?.url())
            assertEquals(1, identities.ensureCalls)
            assertEquals(1, identities.size)
            assertEquals(101L, identities.getByTelegramId(101L)?.telegramId)
        }

    @Test
    fun `repeated bare start reuses the same logical identity`() =
        runBlocking {
            val sender = FallbackRecordingTelegramSender()
            val identities = InMemoryIdentityStore()
            val handler =
                handler(
                    sender = sender,
                    now = TEST_NOW,
                    bookings = emptyList(),
                    userRepository = identities,
                    userIdentityProvisioner = identities,
                )

            handler.handle(messageUpdate(text = "/start"))
            val first = identities.getByTelegramId(101L)
            handler.handle(messageUpdate(text = "/start"))
            val second = identities.getByTelegramId(101L)

            assertSame(first, second)
            assertEquals(1, identities.size)
            assertEquals(2, identities.ensureCalls)
            assertEquals(2, sender.texts().count { it == "Добро пожаловать в Night Concierge!" })
        }

    @Test
    fun `existing identity is left unchanged by bare start`() =
        runBlocking {
            val sender = FallbackRecordingTelegramSender()
            val existing = User(id = 77L, telegramId = 101L, username = "existing")
            val identities = InMemoryIdentityStore(existing)
            val handler =
                handler(
                    sender = sender,
                    now = TEST_NOW,
                    bookings = emptyList(),
                    userRepository = identities,
                    userIdentityProvisioner = identities,
                )

            handler.handle(messageUpdate(text = "/start"))

            assertSame(existing, identities.getByTelegramId(101L))
            assertEquals(1, identities.size)
        }

    @Test
    fun `persistence failure returns bounded response without success or sql details`() =
        runBlocking {
            val sender = FallbackRecordingTelegramSender()
            val rawDetail = "users_telegram_user_id_key INSERT INTO users constraint violation"
            val failingProvisioner =
                object : UserIdentityProvisioner {
                    override suspend fun ensureMinimalIdentity(telegramUserId: Long): User =
                        throw SQLException(rawDetail, "23505")
                }
            val handler =
                handler(
                    sender = sender,
                    now = TEST_NOW,
                    bookings = emptyList(),
                    userIdentityProvisioner = failingProvisioner,
                )

            handler.handle(messageUpdate(text = "/start"))

            assertEquals(listOf("Не удалось начать работу. Попробуйте позже."), sender.texts())
            assertFalse(sender.lastText().contains(rawDetail))
            assertFalse(sender.lastText().contains("SQL", ignoreCase = true))
            assertFalse(sender.lastText().contains("constraint", ignoreCase = true))
            assertFalse(sender.lastText().contains("Добро пожаловать"))
        }

    @Test
    fun `ask works after first start without pre-seeded identity`() =
        runBlocking {
            val sender = FallbackRecordingTelegramSender()
            val identities = InMemoryIdentityStore()
            val handler =
                handler(
                    sender = sender,
                    now = TEST_NOW,
                    bookings = emptyList(),
                    userRepository = identities,
                    userIdentityProvisioner = identities,
                )

            handler.handle(messageUpdate(text = "/start"))
            handler.handle(messageUpdate(text = "/ask"))

            assertEquals(1, identities.size)
            assertEquals("Выберите клуб для вопроса:", sender.lastText())
        }

    @Test
    fun `configured bot mention is accepted as bare start`() =
        runBlocking {
            val sender = FallbackRecordingTelegramSender()
            val identities = InMemoryIdentityStore()
            val handler =
                handler(
                    sender = sender,
                    now = TEST_NOW,
                    bookings = emptyList(),
                    userRepository = identities,
                    userIdentityProvisioner = identities,
                    botUsername = "NightConcierge_Bot",
                )

            val handled = handler.handle(messageUpdate(text = "/start@NightConcierge_Bot"))

            assertTrue(handled)
            assertEquals(1, sender.requests.filterIsInstance<SendMessage>().size)
            assertEquals(1, identities.ensureCalls)
            assertEquals(101L, identities.getByTelegramId(101L)?.telegramId)
        }

    @Test
    fun `bare start provisions identity when mini app url is absent`() =
        runBlocking {
            val sender = FallbackRecordingTelegramSender()
            val identities = InMemoryIdentityStore()
            val handler =
                handler(
                    sender = sender,
                    now = TEST_NOW,
                    bookings = emptyList(),
                    userRepository = identities,
                    userIdentityProvisioner = identities,
                    miniAppUrl = null,
                )

            val handled = handler.handle(messageUpdate(text = "/start"))

            assertTrue(handled)
            assertEquals(1, identities.ensureCalls)
            assertEquals(listOf("Добро пожаловать в Night Concierge!"), sender.texts())
            assertFalse(
                sender.requests
                    .single()
                    .parameters
                    .containsKey("reply_markup"),
            )
        }

    @Test
    fun `start payloads malformed prefixes and other bot mentions are not consumed`() =
        runBlocking {
            val sender = FallbackRecordingTelegramSender()
            val identities = InMemoryIdentityStore()
            val handler =
                handler(
                    sender = sender,
                    now = TEST_NOW,
                    bookings = emptyList(),
                    userRepository = identities,
                    userIdentityProvisioner = identities,
                    botUsername = "NightConcierge_Bot",
                )
            val ignoredTexts =
                listOf(
                    "/start inv_token",
                    "/start promo-token",
                    "/start other",
                    "/start anything",
                    "/start@NightConcierge_Bot inv_token",
                    "/startSomething",
                    "/start@OtherBot",
                    "hello",
                )

            ignoredTexts.forEach { text ->
                assertFalse(handler.handle(messageUpdate(text = text)), text)
            }

            assertTrue(sender.requests.isEmpty())
            assertEquals(0, identities.ensureCalls)
            assertEquals(0, identities.size)
        }

    @Test
    fun `bare start is ignored outside private chat`() =
        runBlocking {
            val sender = FallbackRecordingTelegramSender()
            val identities = InMemoryIdentityStore()
            val handler =
                handler(
                    sender = sender,
                    now = TEST_NOW,
                    bookings = emptyList(),
                    userRepository = identities,
                    userIdentityProvisioner = identities,
                )

            val handled = handler.handle(messageUpdate(text = "/start", chatType = "Group"))

            assertFalse(handled)
            assertTrue(sender.requests.isEmpty())
            assertEquals(0, identities.ensureCalls)
        }

    @Test
    fun `message from id is the only persisted telegram identity`() =
        runBlocking {
            val sender = FallbackRecordingTelegramSender()
            val identities = InMemoryIdentityStore()
            val handler =
                handler(
                    sender = sender,
                    now = TEST_NOW,
                    bookings = emptyList(),
                    userRepository = identities,
                    userIdentityProvisioner = identities,
                )

            handler.handle(messageUpdate(text = "/start", telegramUserId = 303L, chatId = 909L))

            assertEquals(303L, identities.getByTelegramId(303L)?.telegramId)
            assertEquals(null, identities.getByTelegramId(909L))
            assertEquals(1, identities.size)
        }

    @Test
    fun `missing and non-positive telegram ids fail with bounded response`() =
        runBlocking {
            listOf<Long?>(null, 0L, -1L).forEach { telegramUserId ->
                val sender = FallbackRecordingTelegramSender()
                val provisioner = ValidatingIdentityProvisioner()
                val handler =
                    handler(
                        sender = sender,
                        now = TEST_NOW,
                        bookings = emptyList(),
                        userIdentityProvisioner = provisioner,
                    )

                val handled = handler.handle(messageUpdate(text = "/start", telegramUserId = telegramUserId))

                assertTrue(handled)
                assertEquals(listOf("Не удалось начать работу. Попробуйте позже."), sender.texts())
                assertFalse(sender.lastText().contains("positive", ignoreCase = true))
            }
        }

    @Test
    fun `provisioning cancellation is rethrown without guest response`() {
        val sender = FallbackRecordingTelegramSender()
        val cancellation = CancellationException("cancel provisioning")
        val handler =
            handler(
                sender = sender,
                now = TEST_NOW,
                bookings = emptyList(),
                userIdentityProvisioner =
                    object : UserIdentityProvisioner {
                        override suspend fun ensureMinimalIdentity(telegramUserId: Long): User = throw cancellation
                    },
            )

        val thrown =
            assertThrows(CancellationException::class.java) {
                runBlocking { handler.handle(messageUpdate(text = "/start")) }
            }

        assertSame(cancellation, thrown)
        assertTrue(sender.requests.isEmpty())
    }

    @Test
    fun `ask defensively rejects unknown identity without provisioning`() =
        runBlocking {
            val sender = FallbackRecordingTelegramSender()
            val identities = InMemoryIdentityStore()
            val handler =
                handler(
                    sender = sender,
                    now = TEST_NOW,
                    bookings = emptyList(),
                    userRepository = identities,
                    userIdentityProvisioner = identities,
                )

            val handled = handler.handle(messageUpdate(text = "/ask"))

            assertTrue(handled)
            assertEquals(0, identities.ensureCalls)
            assertEquals(0, identities.size)
            assertTrue(sender.lastText().contains("ещё не зарегистрированы"))
        }

    @Test
    fun `router keeps invitation start reachable after fallback`() =
        runBlocking {
            val sender = FallbackRecordingTelegramSender()
            val fallback = handler(sender = sender, now = TEST_NOW, bookings = emptyList())
            var invitationCalls = 0
            val router =
                TelegramCallbackRouter(
                    supportHandler = { throw AssertionError("support should not be called") },
                    invitationHandler = { invitationCalls++ },
                    guestFallbackHandler = fallback::handle,
                    paymentsHandler = mockk(relaxed = true),
                )

            router.route(messageUpdate(text = "/start inv_token"))

            assertEquals(1, invitationCalls)
            assertTrue(sender.requests.isEmpty())
        }

    @Test
    fun `router does not pass bare start to invitation handler`() =
        runBlocking {
            val sender = FallbackRecordingTelegramSender()
            val fallback = handler(sender = sender, now = TEST_NOW, bookings = emptyList())
            var invitationCalls = 0
            val router =
                TelegramCallbackRouter(
                    supportHandler = { throw AssertionError("support should not be called") },
                    invitationHandler = { invitationCalls++ },
                    guestFallbackHandler = fallback::handle,
                    paymentsHandler = mockk(relaxed = true),
                )

            router.route(messageUpdate(text = "/start"))

            assertEquals(0, invitationCalls)
            assertEquals(1, sender.requests.filterIsInstance<SendMessage>().size)
        }

    @Test
    fun `qr command returns no booking message`() =
        runBlocking {
            val sender = FallbackRecordingTelegramSender()
            val handler =
                handler(
                    sender = sender,
                    now = Instant.parse("2026-01-01T20:00:00Z"),
                    bookings = emptyList(),
                )

            handler.handle(messageUpdate(text = "/qr"))

            assertEquals("Активной брони нет. Оформите бронь через miniapp и попробуйте снова.", sender.lastText())
        }

    @Test
    fun `qr command returns payload when booking exists`() =
        runBlocking {
            val sender = FallbackRecordingTelegramSender()
            val now = Instant.parse("2026-01-01T20:00:00Z")
            val booking = bookedBooking(now = now, updatedAt = Instant.parse("2026-01-01T19:00:00Z"))
            val handler = handler(sender = sender, now = now, bookings = listOf(booking), qrSecret = "top-secret")

            handler.handle(messageUpdate(text = "/my_pass@ClubBot"))

            val text = sender.lastText()
            assertTrue(text.contains("Ваш пропуск:"))
            assertTrue(text.contains("Покажите этот код на входе."))
            assertFalse(text.contains("top-secret"))
        }

    @Test
    fun `my command returns booking summary`() =
        runBlocking {
            val sender = FallbackRecordingTelegramSender()
            val now = Instant.parse("2026-01-01T20:00:00Z")
            val booking = bookedBooking(now = now)
            val handler = handler(sender = sender, now = now, bookings = listOf(booking))

            handler.handle(messageUpdate(text = "/my"))

            val text = sender.lastText()
            assertTrue(text.contains("Ближайшая бронь"))
            assertTrue(text.contains("Клуб: Club One"))
            assertTrue(text.contains("Статус: BOOKED"))
            assertTrue(text.contains("Инвайты: переходите по ссылке вида /start inv_<token>"))
        }

    @Test
    fun `my command returns empty state`() =
        runBlocking {
            val sender = FallbackRecordingTelegramSender()
            val handler =
                handler(
                    sender = sender,
                    now = Instant.parse("2026-01-01T20:00:00Z"),
                    bookings = emptyList(),
                )

            handler.handle(messageUpdate(text = "/next_booking"))

            assertEquals("Ближайших активных броней нет.", sender.lastText())
        }

    @Test
    fun `ask flow callback and reply creates support ticket`() =
        runBlocking {
            val sender = FallbackRecordingTelegramSender()
            val support = RecordingSupportService()
            val handler =
                handler(
                    sender = sender,
                    now = Instant.parse("2026-01-01T20:00:00Z"),
                    bookings = emptyList(),
                    supportService = support,
                )

            handler.handle(messageUpdate(text = "/ask"))
            handler.handle(askCallbackUpdate("ask:club:1"))
            handler.handle(
                messageUpdate(
                    text = "Можно ли приехать после полуночи?",
                    replyText = "Вы выбрали Club One. Ответьте на это сообщение вопросом. clubId:1",
                ),
            )

            assertEquals(1, support.createCalls.size)
            assertEquals(1L, support.createCalls.single().clubId)
            assertEquals("Можно ли приехать после полуночи?", support.createCalls.single().text)
            assertTrue(sender.texts().any { it.contains("Вопрос отправлен в клуб") })
        }

    @Test
    fun `ask callback without message still answers callback query`() =
        runBlocking {
            val sender = FallbackRecordingTelegramSender()
            val handler = handler(sender = sender, now = Instant.parse("2026-01-01T20:00:00Z"), bookings = emptyList())

            val handled = handler.handle(askCallbackUpdateWithoutMessage("ask:club:1"))

            assertTrue(handled)
            assertTrue(sender.requests.any { it is AnswerCallbackQuery })
            assertTrue(sender.requests.none { it is SendMessage })
        }

    @Test
    fun `ask reply without marker is not handled`() =
        runBlocking {
            val sender = FallbackRecordingTelegramSender()
            val handler =
                handler(
                    sender = sender,
                    now = Instant.parse("2026-01-01T20:00:00Z"),
                    bookings = emptyList(),
                )

            val handled = handler.handle(messageUpdate(text = "Вопрос", replyText = "Ответьте на это сообщение"))

            assertFalse(handled)
            assertTrue(sender.requests.isEmpty())
        }

    @Test
    fun `ask reply with malformed marker returns validation error`() =
        runBlocking {
            val sender = FallbackRecordingTelegramSender()
            val handler =
                handler(
                    sender = sender,
                    now = Instant.parse("2026-01-01T20:00:00Z"),
                    bookings = emptyList(),
                )

            val handled =
                handler.handle(
                    messageUpdate(text = "Вопрос", replyText = "Ответьте на это сообщение. clubId:abc"),
                )

            assertTrue(handled)
            assertEquals("Не удалось определить клуб. Начните заново через /ask.", sender.lastText())
        }

    @Test
    fun `reply to non-ask message containing clubId is not handled`() =
        runBlocking {
            val sender = FallbackRecordingTelegramSender()
            val handler = handler(sender = sender, now = Instant.parse("2026-01-01T20:00:00Z"), bookings = emptyList())

            val handled = handler.handle(messageUpdate(text = "Вопрос", replyText = "Просто текст clubId:1"))

            assertFalse(handled)
            assertTrue(sender.requests.isEmpty())
        }

    @Test
    fun `cancel command responds friendly`() =
        runBlocking {
            val sender = FallbackRecordingTelegramSender()
            val handler = handler(sender = sender, now = Instant.parse("2026-01-01T20:00:00Z"), bookings = emptyList())

            handler.handle(messageUpdate(text = "/cancel"))

            assertEquals("Ок, отменено. Когда будете готовы, используйте /ask.", sender.lastText())
        }

    @Test
    fun `private commands are ignored in group chat`() =
        runBlocking {
            val sender = FallbackRecordingTelegramSender()
            val handler = handler(sender = sender, now = Instant.parse("2026-01-01T20:00:00Z"), bookings = emptyList())

            handler.handle(messageUpdate(text = "/qr", chatType = "Group"))
            handler.handle(messageUpdate(text = "/my", chatType = "Group"))

            assertTrue(sender.requests.isEmpty())
        }

    private fun handler(
        sender: FallbackRecordingTelegramSender,
        now: Instant,
        bookings: List<Booking>,
        qrSecret: String = "qr-secret",
        supportService: SupportService = RecordingSupportService(),
        userRepository: UserRepository = RegisteredFallbackUserRepository,
        userIdentityProvisioner: UserIdentityProvisioner = RegisteredFallbackIdentityProvisioner,
        botUsername: String? = "clubbot",
        miniAppUrl: String? = TEST_MINI_APP_URL,
    ): TelegramGuestFallbackHandler {
        val bookingState = mockk<BookingState>()
        every { bookingState.now() } returns now
        every { bookingState.findUserBookings(any()) } returns bookings
        return TelegramGuestFallbackHandler(
            send = sender::send,
            bookingState = bookingState,
            clubsRepository = StaticClubsRepository(),
            userRepository = userRepository,
            userIdentityProvisioner = userIdentityProvisioner,
            supportService = supportService,
            botUsername = botUsername,
            miniAppUrl = miniAppUrl,
            qrSecretProvider = { qrSecret },
        )
    }
}

private object RegisteredFallbackUserRepository : UserRepository {
    override suspend fun getByTelegramId(id: Long): User? = if (id == 101L) User(55L, 101L, "guest") else null

    override suspend fun getById(id: Long): User? = null
}

private object RegisteredFallbackIdentityProvisioner : UserIdentityProvisioner {
    override suspend fun ensureMinimalIdentity(telegramUserId: Long): User =
        User(id = 55L, telegramId = telegramUserId, username = "guest")
}

private class ValidatingIdentityProvisioner : UserIdentityProvisioner {
    override suspend fun ensureMinimalIdentity(telegramUserId: Long): User {
        require(telegramUserId > 0) { "telegramUserId must be positive" }
        return User(id = 55L, telegramId = telegramUserId, username = null)
    }
}

private class InMemoryIdentityStore(
    existing: User? = null,
) : UserRepository,
    UserIdentityProvisioner {
    private val users = existing?.let { mutableMapOf(it.telegramId to it) } ?: mutableMapOf()
    var ensureCalls: Int = 0
        private set

    val size: Int
        get() = users.size

    override suspend fun ensureMinimalIdentity(telegramUserId: Long): User {
        ensureCalls += 1
        return users.getOrPut(telegramUserId) {
            User(id = users.size + 1L, telegramId = telegramUserId, username = null)
        }
    }

    override suspend fun getByTelegramId(id: Long): User? = users[id]

    override suspend fun getById(id: Long): User? = users.values.firstOrNull { it.id == id }
}

private data class CreateTicketCall(
    val clubId: Long,
    val userId: Long,
    val text: String,
)

private class RecordingSupportService : SupportService {
    val createCalls = mutableListOf<CreateTicketCall>()

    override suspend fun createTicket(
        clubId: Long,
        userId: Long,
        bookingId: UUID?,
        listEntryId: Long?,
        topic: TicketTopic,
        text: String,
        attachments: String?,
    ): SupportServiceResult<TicketWithMessage> {
        createCalls += CreateTicketCall(clubId = clubId, userId = userId, text = text)
        return SupportServiceResult.Success(sampleTicketWithMessage(clubId, userId))
    }

    override suspend fun listMyTickets(userId: Long): List<TicketSummary> = emptyList()

    override suspend fun addGuestMessage(
        ticketId: Long,
        userId: Long,
        text: String,
        attachments: String?,
    ): SupportServiceResult<TicketMessage> = throw UnsupportedOperationException()

    override suspend fun listTicketsForClub(
        clubId: Long,
        status: TicketStatus?,
    ): List<TicketSummary> = emptyList()

    override suspend fun assign(
        ticketId: Long,
        agentUserId: Long,
    ): SupportServiceResult<Ticket> = throw UnsupportedOperationException()

    override suspend fun setStatus(
        ticketId: Long,
        agentUserId: Long,
        status: TicketStatus,
    ): SupportServiceResult<Ticket> = throw UnsupportedOperationException()

    override suspend fun reply(
        ticketId: Long,
        agentUserId: Long,
        text: String,
        attachments: String?,
    ): SupportServiceResult<SupportReplyResult> = throw UnsupportedOperationException()

    override suspend fun setResolutionRating(
        ticketId: Long,
        userId: Long,
        rating: Int,
    ): SupportServiceResult<Ticket> = throw UnsupportedOperationException()

    override suspend fun getTicket(ticketId: Long): Ticket? = null
}

private class StaticClubsRepository : ClubsRepository {
    private val clubs = listOf(Club(1L, "Moscow", "Club One", genres = emptyList(), tags = emptyList(), logoUrl = null))

    override suspend fun getById(id: Long): Club? = clubs.firstOrNull { it.id == id }

    override suspend fun list(
        city: String?,
        query: String?,
        tag: String?,
        genre: String?,
        offset: Int,
        limit: Int,
    ): List<Club> = clubs

    override suspend fun lastUpdatedAt(): Instant? = null
}

private class FallbackRecordingTelegramSender {
    val requests = mutableListOf<BaseRequest<*, *>>()

    suspend fun send(request: BaseRequest<*, *>): BaseResponse {
        requests += request
        return mockk<BaseResponse>()
    }

    fun texts(): List<String> = requests.filterIsInstance<SendMessage>().map { it.parameters["text"].toString() }

    fun lastText(): String = texts().last()
}

private fun bookedBooking(
    now: Instant,
    updatedAt: Instant = now,
): Booking =
    Booking(
        id = 10L,
        userId = 55L,
        clubId = 1L,
        tableId = 15L,
        eventId = 100L,
        status = BookingStatus.BOOKED,
        guestCount = 2,
        arrivalWindow = now.plusSeconds(1_800) to now.plusSeconds(7_200),
        latePlusOneAllowedUntil = now.plusSeconds(5_400),
        plusOneUsed = false,
        capacityAtHold = 4,
        createdAt = now.minusSeconds(3_600),
        updatedAt = updatedAt,
        holdExpiresAt = null,
        promoterId = null,
    )

private fun messageUpdate(
    text: String,
    chatType: String = "Private",
    replyText: String? = null,
    telegramUserId: Long? = 101L,
    chatId: Long = 42L,
): Update {
    val update = mockk<Update>()
    val message = mockk<Message>()
    val chat = mockk<Chat>()
    val type = mockk<Chat.Type>()
    every { update.preCheckoutQuery() } returns null
    every { update.callbackQuery() } returns null
    every { update.message() } returns message
    every { message.successfulPayment() } returns null
    every { message.text() } returns text
    every { message.chat() } returns chat
    val from = telegramUserId?.let { mockk<TelegramUser>() }
    every { message.from() } returns from
    if (from != null) {
        every { from.id() } returns telegramUserId
    }
    every { chat.id() } returns chatId
    every { chat.type() } returns type
    every { type.name } returns chatType
    if (replyText != null) {
        val reply = mockk<Message>()
        every { message.replyToMessage() } returns reply
        every { reply.text() } returns replyText
    } else {
        every { message.replyToMessage() } returns null
    }
    return update
}

private fun askCallbackUpdate(data: String): Update {
    val update = mockk<Update>()
    val callback = mockk<CallbackQuery>()
    val message = mockk<Message>()
    val chat = mockk<Chat>()
    val type = mockk<Chat.Type>()

    every { update.callbackQuery() } returns callback
    every { update.message() } returns null
    every { callback.data() } returns data
    every { callback.id() } returns "cb-id"
    every { callback.message() } returns message
    every { message.chat() } returns chat
    every { chat.id() } returns 42L
    every { chat.type() } returns type
    every { type.name } returns "Private"

    return update
}

private fun askCallbackUpdateWithoutMessage(data: String): Update {
    val update = mockk<Update>()
    val callback = mockk<CallbackQuery>()

    every { update.callbackQuery() } returns callback
    every { update.message() } returns null
    every { callback.data() } returns data
    every { callback.id() } returns "cb-id"
    every { callback.message() } returns null

    return update
}

private fun sampleTicketWithMessage(
    clubId: Long,
    userId: Long,
): TicketWithMessage {
    val ticket =
        Ticket(
            id = 1L,
            clubId = clubId,
            userId = userId,
            bookingId = null,
            listEntryId = null,
            topic = TicketTopic.OTHER,
            status = TicketStatus.OPENED,
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            lastAgentId = null,
            resolutionRating = null,
        )
    val message =
        TicketMessage(
            id = 1L,
            ticketId = ticket.id,
            senderType = com.example.bot.support.TicketSenderType.GUEST,
            text = "ok",
            attachments = null,
            createdAt = Instant.EPOCH,
        )
    return TicketWithMessage(ticket, message)
}

private val TEST_NOW = Instant.parse("2026-01-01T20:00:00Z")
private const val TEST_MINI_APP_URL = "https://night.example/app"
