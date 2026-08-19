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
import com.pengrad.telegrambot.model.request.ForceReply
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

    @Test
    fun `ask from a known guest shows the production backed club list`() =
        runBlocking { verifyAskShowsProductionClubList() }

    @Test
    fun `ask with an active booking still requires explicit club selection`() =
        runBlocking { verifyActiveBookingRequiresClubSelection() }

    @Test
    fun `Long MAX club callback shows seven literal categories with bounded payloads`() =
        runBlocking { verifyClubCallbackShowsCategories() }

    @Test
    fun `category callback sends a bot authored force reply prompt without creating a ticket`() =
        runBlocking { verifyCategoryCallbackPrompt() }

    @Test
    fun `each explicit category creates a ticket with the selected club and topic`() =
        runBlocking { verifyEachCategoryCreatesSelectedTicket() }

    @Test
    fun `non canonical malformed and unknown callbacks are rejected without ticket creation`() =
        runBlocking { verifyInvalidCallbacksDoNotCreateTickets() }

    @Test
    fun `exact canonical prompt is accepted only from configured current bot id`() =
        runBlocking { verifyExactCurrentBotIdentityBoundary() }

    @Test
    fun `forwarded guest business inline and other provenance is rejected`() =
        runBlocking { verifyDisallowedPromptProvenance() }

    @Test
    fun `bot id lookup failure is bounded and cancellation is rethrown`() = verifyBotIdentityLookupFailures()

    @Test
    fun `contradictory missing duplicated malformed and overflow contexts are rejected`() =
        runBlocking { verifyInvalidReplyContexts() }

    @Test
    fun `all non canonical id forms are rejected in reply context`() = runBlocking { verifyNonCanonicalReplyClubIds() }

    @Test
    fun `canonical prompt supports newline and marker like persisted club name`() =
        runBlocking { verifyMarkerLikeClubName() }

    @Test
    fun `unrelated current bot reply and human authored lookalike are not accepted`() =
        runBlocking { verifyUnrelatedAndHumanReplyTargets() }

    @Test
    fun `blank ask reply is rejected without creating a ticket`() = runBlocking { verifyBlankAskReply() }
}

private fun handler(
    sender: FallbackRecordingTelegramSender,
    now: Instant,
    bookings: List<Booking>,
    qrSecret: String = "qr-secret",
    supportService: SupportService = RecordingSupportService(),
    clubsRepository: ClubsRepository = StaticClubsRepository(),
    userRepository: UserRepository = RegisteredFallbackUserRepository,
    userIdentityProvisioner: UserIdentityProvisioner = RegisteredFallbackIdentityProvisioner,
    currentBotUserIdProvider: suspend () -> Long = { TEST_BOT_USER_ID },
    botUsername: String? = "clubbot",
    miniAppUrl: String? = TEST_MINI_APP_URL,
): TelegramGuestFallbackHandler {
    val bookingState = mockk<BookingState>()
    every { bookingState.now() } returns now
    every { bookingState.findUserBookings(any()) } returns bookings
    return TelegramGuestFallbackHandler(
        send = sender::send,
        bookingState = bookingState,
        clubsRepository = clubsRepository,
        userRepository = userRepository,
        userIdentityProvisioner = userIdentityProvisioner,
        supportService = supportService,
        currentBotUserIdProvider = currentBotUserIdProvider,
        botUsername = botUsername,
        miniAppUrl = miniAppUrl,
        qrSecretProvider = { qrSecret },
    )
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
    val bookingId: UUID?,
    val listEntryId: Long?,
    val topic: TicketTopic,
    val text: String,
    val attachments: String?,
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
        createCalls +=
            CreateTicketCall(
                clubId = clubId,
                userId = userId,
                bookingId = bookingId,
                listEntryId = listEntryId,
                topic = topic,
                text = text,
                attachments = attachments,
            )
        return SupportServiceResult.Success(sampleTicketWithMessage(clubId, userId, topic))
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

private class StaticClubsRepository(
    private val clubs: List<Club> =
        listOf(Club(1L, "Moscow", "Club One", genres = emptyList(), tags = emptyList(), logoUrl = null)),
) : ClubsRepository {
    var listCalls: Int = 0
        private set
    var getByIdCalls: Int = 0
        private set

    override suspend fun getById(id: Long): Club? {
        getByIdCalls += 1
        return clubs.firstOrNull { it.id == id }
    }

    override suspend fun list(
        city: String?,
        query: String?,
        tag: String?,
        genre: String?,
        offset: Int,
        limit: Int,
    ): List<Club> {
        listCalls += 1
        return clubs
    }

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

    fun lastSendMessage(): SendMessage = requests.filterIsInstance<SendMessage>().last()

    fun lastInlineButtons(): List<com.pengrad.telegrambot.model.request.InlineKeyboardButton> {
        val markup = lastSendMessage().parameters["reply_markup"] as InlineKeyboardMarkup
        return markup.inlineKeyboard().flatMap { it.asList() }
    }
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
    replyFromBot: Boolean = true,
    replyFromUserId: Long? = TEST_BOT_USER_ID,
    replyProvenance: ReplyProvenance = ReplyProvenance(),
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
        val replyFrom = replyFromUserId?.let { mockk<TelegramUser>() }
        every { message.replyToMessage() } returns reply
        every { reply.text() } returns replyText
        every { reply.from() } returns replyFrom
        if (replyFrom != null) {
            every { replyFrom.isBot() } returns replyFromBot
            every { replyFrom.id() } returns replyFromUserId
        }
        every { reply.forwardOrigin() } returns if (replyProvenance.forwarded) mockk() else null
        every { reply.senderBusinessBot() } returns if (replyProvenance.senderBusinessBot) mockk() else null
        every { reply.businessConnectionId() } returns replyProvenance.businessConnectionId
        every { reply.viaBot() } returns if (replyProvenance.viaBot) mockk() else null
        every { reply.senderChat() } returns if (replyProvenance.senderChat) mockk() else null
        every { reply.isAutomaticForward() } returns replyProvenance.automaticForward
        every { reply.isFromOffline() } returns replyProvenance.fromOffline
        every { reply.directMessagesTopic() } returns if (replyProvenance.directMessagesTopic) mockk() else null
        every { reply.externalReply() } returns if (replyProvenance.externalReply) mockk() else null
    } else {
        every { message.replyToMessage() } returns null
    }
    return update
}

private data class ReplyProvenance(
    val forwarded: Boolean = false,
    val senderBusinessBot: Boolean = false,
    val businessConnectionId: String? = null,
    val viaBot: Boolean = false,
    val senderChat: Boolean = false,
    val automaticForward: Boolean = false,
    val fromOffline: Boolean = false,
    val directMessagesTopic: Boolean = false,
    val externalReply: Boolean = false,
)

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
    topic: TicketTopic,
): TicketWithMessage {
    val ticket =
        Ticket(
            id = 1L,
            clubId = clubId,
            userId = userId,
            bookingId = null,
            listEntryId = null,
            topic = topic,
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
private const val TEST_BOT_USER_ID = 7_770_001L
private const val ASK_REPLY_INSTRUCTION = "Ответьте на это сообщение текстом вопроса."
private const val ASK_CONTEXT_ERROR_TEXT = "Не удалось определить параметры вопроса. Начните заново через /ask."
private const val BOT_IDENTITY_ERROR_TEXT = "Не удалось подтвердить сообщение бота. Начните заново через /ask."

private data class ExpectedAskCategory(
    val label: String,
    val wire: String,
    val topic: TicketTopic,
)

private val EXPECTED_ASK_CATEGORIES =
    listOf(
        ExpectedAskCategory("Адрес / как добраться", "address", TicketTopic.ADDRESS),
        ExpectedAskCategory("Правила / дресс-код", "dresscode", TicketTopic.DRESSCODE),
        ExpectedAskCategory("Списки / вход", "invite", TicketTopic.INVITE),
        ExpectedAskCategory("Брони / депозит", "booking", TicketTopic.BOOKING),
        ExpectedAskCategory("Потерял вещь", "lost_found", TicketTopic.LOST_FOUND),
        ExpectedAskCategory("Жалоба / сервис", "complaint", TicketTopic.COMPLAINT),
        ExpectedAskCategory("Другое", "other", TicketTopic.OTHER),
    )

private val LONG_MAX_CATEGORY_CALLBACKS =
    listOf(
        "ask:club:9223372036854775807:topic:address",
        "ask:club:9223372036854775807:topic:dresscode",
        "ask:club:9223372036854775807:topic:invite",
        "ask:club:9223372036854775807:topic:booking",
        "ask:club:9223372036854775807:topic:lost_found",
        "ask:club:9223372036854775807:topic:complaint",
        "ask:club:9223372036854775807:topic:other",
    )

private suspend fun verifyAskShowsProductionClubList() {
    val sender = FallbackRecordingTelegramSender()
    val clubs =
        StaticClubsRepository(
            listOf(
                Club(1L, "Moscow", "Club One", genres = emptyList(), tags = emptyList(), logoUrl = null),
                Club(2L, "Moscow", "Club Two", genres = emptyList(), tags = emptyList(), logoUrl = null),
            ),
        )
    val handler =
        handler(
            sender = sender,
            now = TEST_NOW,
            bookings = emptyList(),
            clubsRepository = clubs,
        )

    handler.handle(messageUpdate(text = "/ask"))

    val buttons = sender.lastInlineButtons()
    assertEquals(1, clubs.listCalls)
    assertEquals(listOf("Club One", "Club Two"), buttons.map { it.text })
    assertEquals(listOf("ask:club:1", "ask:club:2"), buttons.map { it.callbackData })
}

private suspend fun verifyActiveBookingRequiresClubSelection() {
    val sender = FallbackRecordingTelegramSender()
    val handler =
        handler(
            sender = sender,
            now = TEST_NOW,
            bookings = listOf(bookedBooking(TEST_NOW)),
        )

    handler.handle(messageUpdate(text = "/ask"))

    assertEquals("Выберите клуб для вопроса:", sender.lastText())
    assertTrue(sender.lastSendMessage().parameters["reply_markup"] is InlineKeyboardMarkup)
}

private suspend fun verifyClubCallbackShowsCategories() {
    val sender = FallbackRecordingTelegramSender()
    val support = RecordingSupportService()
    val clubs =
        StaticClubsRepository(
            listOf(
                Club(
                    Long.MAX_VALUE,
                    "Moscow",
                    "Maximum Club",
                    genres = emptyList(),
                    tags = emptyList(),
                    logoUrl = null,
                ),
            ),
        )
    val handler =
        handler(
            sender = sender,
            now = TEST_NOW,
            bookings = emptyList(),
            supportService = support,
            clubsRepository = clubs,
        )

    handler.handle(askCallbackUpdate("ask:club:9223372036854775807"))

    val buttons = sender.lastInlineButtons()
    val callbacks = buttons.map { it.callbackData.orEmpty() }
    assertEquals(7, buttons.size)
    assertEquals(EXPECTED_ASK_CATEGORIES.map { it.label }, buttons.map { it.text })
    assertEquals(LONG_MAX_CATEGORY_CALLBACKS, callbacks)
    EXPECTED_ASK_CATEGORIES.forEach { category ->
        assertEquals(category.topic, TicketTopic.fromWire(category.wire), category.wire)
    }
    callbacks.forEach { callback ->
        assertTrue(callback.toByteArray(Charsets.UTF_8).size in 1..64, callback)
    }
    assertEquals(1, sender.requests.count { it is AnswerCallbackQuery })
    assertTrue(support.createCalls.isEmpty())
}

private suspend fun verifyCategoryCallbackPrompt() {
    val sender = FallbackRecordingTelegramSender()
    val support = RecordingSupportService()
    val handler = handler(sender = sender, now = TEST_NOW, bookings = emptyList(), supportService = support)

    handler.handle(askCallbackUpdate("ask:club:1:topic:invite"))

    val prompt = sender.lastSendMessage()
    assertTrue(prompt.parameters["reply_markup"] is ForceReply)
    assertEquals(
        "Клуб: Club One\n" +
            "Категория: Списки / вход\n" +
            "$ASK_REPLY_INSTRUCTION\n" +
            "askContext:v1:1:invite",
        sender.lastText(),
    )
    assertEquals(1, sender.requests.count { it is AnswerCallbackQuery })
    assertTrue(support.createCalls.isEmpty())
}

private suspend fun verifyEachCategoryCreatesSelectedTicket() {
    EXPECTED_ASK_CATEGORIES.forEach { category ->
        val sender = FallbackRecordingTelegramSender()
        val support = RecordingSupportService()
        val clubs =
            StaticClubsRepository(
                listOf(
                    Club(
                        2L,
                        "Moscow",
                        "Club Two",
                        genres = emptyList(),
                        tags = emptyList(),
                        logoUrl = null,
                    ),
                ),
            )
        val handler =
            handler(
                sender = sender,
                now = TEST_NOW,
                bookings = emptyList(),
                clubsRepository = clubs,
                supportService = support,
            )

        val callback = "ask:club:2:topic:${category.wire}"
        handler.handle(askCallbackUpdate(callback))
        val prompt = sender.lastText()
        handler.handle(
            messageUpdate(
                text = " \nВопрос: ${category.label}\t ",
                replyText = prompt,
                replyFromUserId = TEST_BOT_USER_ID,
            ),
        )

        assertEquals(1, support.createCalls.size, category.topic.name)
        assertEquals(
            CreateTicketCall(
                clubId = 2L,
                userId = 55L,
                bookingId = null,
                listEntryId = null,
                topic = category.topic,
                text = "Вопрос: ${category.label}",
                attachments = null,
            ),
            support.createCalls.single(),
            category.wire,
        )
        assertEquals(category.topic, TicketTopic.fromWire(category.wire), category.wire)
        assertEquals("Вопрос отправлен в клуб. Мы скоро ответим.", sender.lastText())
    }
}

private suspend fun verifyInvalidCallbacksDoNotCreateTickets() {
    val syntacticallyInvalidCallbacks =
        listOf(
            "ask:club:+1",
            "ask:club:01",
            "ask:club:0",
            "ask:club:-1",
            "ask:club:",
            "ask:club: ",
            "ask:club:1.0",
            "ask:club:9223372036854775808",
            "ask:club:999999999999999999999999999999999999999",
            "ask:club:1:",
            "ask:club:1:garbage",
            "ask:club:1:topic",
            "ask:club:+1:topic:other",
            "ask:club:01:topic:other",
            "ask:club:0:topic:other",
            "ask:club:-1:topic:other",
            "ask:club::topic:other",
            "ask:club: :topic:other",
            "ask:club:1.0:topic:other",
            "ask:club:9223372036854775808:topic:other",
            "ask:club:1:topic:",
            "ask:club:1:topic:unknown",
            "ask:club:1:topic:other:extra",
            "ask:club:1:topic:other:topic:other",
        )
    syntacticallyInvalidCallbacks.forEach { callbackData ->
        val sender = FallbackRecordingTelegramSender()
        val support = RecordingSupportService()
        val clubs = StaticClubsRepository()
        var currentBotUserIdCalls = 0
        val handler =
            handler(
                sender = sender,
                now = TEST_NOW,
                bookings = emptyList(),
                supportService = support,
                clubsRepository = clubs,
                currentBotUserIdProvider = {
                    currentBotUserIdCalls += 1
                    TEST_BOT_USER_ID
                },
                userIdentityProvisioner =
                    object : UserIdentityProvisioner {
                        override suspend fun ensureMinimalIdentity(telegramUserId: Long): User =
                            throw AssertionError("callbacks must not provision identity")
                    },
            )

        assertTrue(handler.handle(askCallbackUpdate(callbackData)), callbackData)
        assertEquals(1, sender.requests.count { it is AnswerCallbackQuery }, callbackData)
        assertTrue(sender.requests.none { it is SendMessage }, callbackData)
        assertTrue(support.createCalls.isEmpty(), callbackData)
        assertEquals(0, clubs.getByIdCalls, callbackData)
        assertEquals(0, currentBotUserIdCalls, callbackData)
    }

    listOf("ask:club:99", "ask:club:99:topic:other").forEach { callbackData ->
        val sender = FallbackRecordingTelegramSender()
        val support = RecordingSupportService()
        val handler = handler(sender = sender, now = TEST_NOW, bookings = emptyList(), supportService = support)

        assertTrue(handler.handle(askCallbackUpdate(callbackData)), callbackData)
        assertEquals(1, sender.requests.count { it is AnswerCallbackQuery }, callbackData)
        assertTrue(sender.requests.none { it is SendMessage }, callbackData)
        assertTrue(support.createCalls.isEmpty(), callbackData)
    }
}

private suspend fun verifyExactCurrentBotIdentityBoundary() {
    val prompt = emittedAskPrompt(clubId = 1L, topicWire = "other")
    val acceptedSender = FallbackRecordingTelegramSender()
    val acceptedSupport = RecordingSupportService()
    val acceptedHandler =
        handler(
            sender = acceptedSender,
            now = TEST_NOW,
            bookings = emptyList(),
            supportService = acceptedSupport,
        )

    assertTrue(
        acceptedHandler.handle(
            messageUpdate(
                text = "  Точный вопрос  ",
                replyText = prompt,
                replyFromUserId = TEST_BOT_USER_ID,
            ),
        ),
    )
    assertEquals(
        CreateTicketCall(1L, 55L, null, null, TicketTopic.OTHER, "Точный вопрос", null),
        acceptedSupport.createCalls.single(),
    )

    val otherBotSender = FallbackRecordingTelegramSender()
    val otherBotSupport = RecordingSupportService()
    val otherBotHandler =
        handler(
            sender = otherBotSender,
            now = TEST_NOW,
            bookings = emptyList(),
            supportService = otherBotSupport,
        )
    assertFalse(
        otherBotHandler.handle(
            messageUpdate(
                text = "Точный вопрос",
                replyText = prompt,
                replyFromUserId = TEST_BOT_USER_ID + 1L,
            ),
        ),
    )
    assertTrue(otherBotSender.requests.isEmpty())
    assertTrue(otherBotSupport.createCalls.isEmpty())
}

private suspend fun verifyDisallowedPromptProvenance() {
    val prompt = emittedAskPrompt(clubId = 1L, topicWire = "other")
    val cases =
        mapOf(
            "forwarded" to ReplyProvenance(forwarded = true),
            "guest business bot" to ReplyProvenance(senderBusinessBot = true),
            "business connection" to ReplyProvenance(businessConnectionId = "business-connection"),
            "inline via bot" to ReplyProvenance(viaBot = true),
            "sender chat" to ReplyProvenance(senderChat = true),
            "automatic forward" to ReplyProvenance(automaticForward = true),
            "offline business delivery" to ReplyProvenance(fromOffline = true),
            "direct messages topic" to ReplyProvenance(directMessagesTopic = true),
            "external reply" to ReplyProvenance(externalReply = true),
        )

    cases.forEach { (caseName, provenance) ->
        val sender = FallbackRecordingTelegramSender()
        val support = RecordingSupportService()
        val handler = handler(sender = sender, now = TEST_NOW, bookings = emptyList(), supportService = support)

        assertFalse(
            handler.handle(
                messageUpdate(
                    text = "Вопрос",
                    replyText = prompt,
                    replyFromUserId = TEST_BOT_USER_ID,
                    replyProvenance = provenance,
                ),
            ),
            caseName,
        )
        assertTrue(sender.requests.isEmpty(), caseName)
        assertTrue(support.createCalls.isEmpty(), caseName)
    }
}

private fun verifyBotIdentityLookupFailures() {
    val prompt = runBlocking { emittedAskPrompt(clubId = 1L, topicWire = "other") }
    val failureSender = FallbackRecordingTelegramSender()
    val failureSupport = RecordingSupportService()
    val failureHandler =
        handler(
            sender = failureSender,
            now = TEST_NOW,
            bookings = emptyList(),
            supportService = failureSupport,
            currentBotUserIdProvider = { throw IllegalStateException("raw getMe response and token detail") },
        )

    val handled =
        runBlocking {
            failureHandler.handle(
                messageUpdate(text = "Вопрос", replyText = prompt, replyFromUserId = TEST_BOT_USER_ID),
            )
        }

    assertTrue(handled)
    assertEquals(listOf(BOT_IDENTITY_ERROR_TEXT), failureSender.texts())
    assertFalse(failureSender.lastText().contains("raw getMe"))
    assertTrue(failureSupport.createCalls.isEmpty())

    val cancellation = CancellationException("cancel getMe")
    val cancellationSender = FallbackRecordingTelegramSender()
    val cancellationSupport = RecordingSupportService()
    val cancellationHandler =
        handler(
            sender = cancellationSender,
            now = TEST_NOW,
            bookings = emptyList(),
            supportService = cancellationSupport,
            currentBotUserIdProvider = { throw cancellation },
        )

    val thrown =
        assertThrows(CancellationException::class.java) {
            runBlocking {
                cancellationHandler.handle(
                    messageUpdate(text = "Вопрос", replyText = prompt, replyFromUserId = TEST_BOT_USER_ID),
                )
            }
        }
    assertSame(cancellation, thrown)
    assertTrue(cancellationSender.requests.isEmpty())
    assertTrue(cancellationSupport.createCalls.isEmpty())
}

private suspend fun verifyInvalidReplyContexts() {
    val canonical = emittedAskPrompt(clubId = 1L, topicWire = "other")
    val finalLine = canonical.substringAfterLast('\n')
    val prefix = canonical.substringBeforeLast('\n')
    val invalidPrompts =
        listOf(
            canonical.replaceFirst("Клуб: Club One", "Клуб: Contradicting Club"),
            canonical.replaceFirst("Категория: Другое", "Категория: Жалоба / сервис"),
            prefix,
            "$canonical\n$finalLine",
            "$prefix\naskContext:v2:1:other",
            "$prefix\naskContext:v1:1:unknown",
            "$prefix\naskContext:v1:1:other:extra",
            "$prefix\naskContext:v1:1:topic:other",
            "$prefix\naskContext:v1:9223372036854775808:other",
            "$prefix\naskContext:v1:99:other",
            canonical.replaceFirst(ASK_REPLY_INSTRUCTION, "Ответьте другим текстом."),
        )

    invalidPrompts.forEach { invalidPrompt ->
        val sender = FallbackRecordingTelegramSender()
        val support = RecordingSupportService()
        val handler = handler(sender = sender, now = TEST_NOW, bookings = emptyList(), supportService = support)

        assertTrue(
            handler.handle(
                messageUpdate(
                    text = "Вопрос",
                    replyText = invalidPrompt,
                    replyFromUserId = TEST_BOT_USER_ID,
                ),
            ),
            invalidPrompt,
        )
        assertTrue(sender.texts().single().isNotBlank(), invalidPrompt)
        assertTrue(support.createCalls.isEmpty(), invalidPrompt)
    }
}

private suspend fun verifyNonCanonicalReplyClubIds() {
    val canonical = emittedAskPrompt(clubId = 1L, topicWire = "other")
    val prefix = canonical.substringBeforeLast('\n')
    val invalidIds =
        listOf(
            "+1",
            "01",
            "0",
            "-1",
            "",
            " ",
            "1.0",
            "9223372036854775808",
            "999999999999999999999999999999999999999",
        )

    invalidIds.forEach { invalidId ->
        val sender = FallbackRecordingTelegramSender()
        val support = RecordingSupportService()
        val clubs = StaticClubsRepository()
        val handler =
            handler(
                sender = sender,
                now = TEST_NOW,
                bookings = emptyList(),
                supportService = support,
                clubsRepository = clubs,
            )
        val invalidPrompt = "$prefix\naskContext:v1:$invalidId:other"

        assertTrue(
            handler.handle(
                messageUpdate(text = "Вопрос", replyText = invalidPrompt, replyFromUserId = TEST_BOT_USER_ID),
            ),
            invalidId,
        )
        assertEquals(ASK_CONTEXT_ERROR_TEXT, sender.lastText(), invalidId)
        assertEquals(0, clubs.getByIdCalls, invalidId)
        assertTrue(support.createCalls.isEmpty(), invalidId)
    }
}

private suspend fun verifyMarkerLikeClubName() {
    val clubName =
        "Club 42!?\n" +
            "askContext:v1:999:other\n" +
            "$ASK_REPLY_INSTRUCTION\n" +
            "clubId:+1"
    val club = Club(7L, "Moscow", clubName, genres = emptyList(), tags = emptyList(), logoUrl = null)
    val clubs = StaticClubsRepository(listOf(club))
    val sender = FallbackRecordingTelegramSender()
    val support = RecordingSupportService()
    val handler =
        handler(
            sender = sender,
            now = TEST_NOW,
            bookings = emptyList(),
            supportService = support,
            clubsRepository = clubs,
        )

    handler.handle(askCallbackUpdate("ask:club:7:topic:other"))
    val prompt = sender.lastText()
    assertEquals(
        "Клуб: $clubName\n" +
            "Категория: Другое\n" +
            "$ASK_REPLY_INSTRUCTION\n" +
            "askContext:v1:7:other",
        prompt,
    )
    handler.handle(
        messageUpdate(
            text = "  Маркерный вопрос  ",
            replyText = prompt,
            replyFromUserId = TEST_BOT_USER_ID,
        ),
    )

    assertEquals(
        CreateTicketCall(7L, 55L, null, null, TicketTopic.OTHER, "Маркерный вопрос", null),
        support.createCalls.single(),
    )
}

private suspend fun verifyUnrelatedAndHumanReplyTargets() {
    val canonical = emittedAskPrompt(clubId = 1L, topicWire = "other")
    val unrelatedSender = FallbackRecordingTelegramSender()
    val unrelatedSupport = RecordingSupportService()
    var identityCalls = 0
    val unrelatedHandler =
        handler(
            sender = unrelatedSender,
            now = TEST_NOW,
            bookings = emptyList(),
            supportService = unrelatedSupport,
            currentBotUserIdProvider = {
                identityCalls += 1
                TEST_BOT_USER_ID
            },
        )
    assertFalse(
        unrelatedHandler.handle(
            messageUpdate(
                text = "Вопрос",
                replyText = "Системное уведомление от текущего бота",
                replyFromUserId = TEST_BOT_USER_ID,
            ),
        ),
    )
    assertEquals(0, identityCalls)
    assertTrue(unrelatedSender.requests.isEmpty())
    assertTrue(unrelatedSupport.createCalls.isEmpty())

    val humanSender = FallbackRecordingTelegramSender()
    val humanSupport = RecordingSupportService()
    val humanHandler =
        handler(
            sender = humanSender,
            now = TEST_NOW,
            bookings = emptyList(),
            supportService = humanSupport,
        )
    assertFalse(
        humanHandler.handle(
            messageUpdate(
                text = "Вопрос",
                replyText = canonical,
                replyFromBot = false,
                replyFromUserId = TEST_BOT_USER_ID,
            ),
        ),
    )
    assertTrue(humanSender.requests.isEmpty())
    assertTrue(humanSupport.createCalls.isEmpty())
}

private suspend fun emittedAskPrompt(
    clubId: Long,
    topicWire: String,
    clubsRepository: ClubsRepository = StaticClubsRepository(),
): String {
    val sender = FallbackRecordingTelegramSender()
    val handler =
        handler(
            sender = sender,
            now = TEST_NOW,
            bookings = emptyList(),
            clubsRepository = clubsRepository,
        )
    assertTrue(handler.handle(askCallbackUpdate("ask:club:$clubId:topic:$topicWire")))
    return sender.lastText()
}

private suspend fun verifyBlankAskReply() {
    val sender = FallbackRecordingTelegramSender()
    val support = RecordingSupportService()
    val handler = handler(sender = sender, now = TEST_NOW, bookings = emptyList(), supportService = support)

    handler.handle(askCallbackUpdate("ask:club:1:topic:complaint"))
    handler.handle(messageUpdate(text = "  ", replyText = sender.lastText()))

    assertEquals("Напишите текст вопроса одним сообщением.", sender.lastText())
    assertTrue(support.createCalls.isEmpty())
}
