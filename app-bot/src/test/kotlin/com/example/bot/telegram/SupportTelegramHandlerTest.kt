package com.example.bot.telegram

import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.support.SupportReplyResult
import com.example.bot.support.SupportService
import com.example.bot.support.SupportServiceError
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
import com.pengrad.telegrambot.request.AnswerCallbackQuery
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.request.EditMessageReplyMarkup
import com.pengrad.telegrambot.response.BaseResponse
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SupportTelegramHandlerTest {
    @Test
    fun `supports callback fits byte boundary`() {
        assertTrue(SupportCallbacks.fits("a".repeat(64)))
        assertFalse(SupportCallbacks.fits("a".repeat(65)))
    }

    @Test
    fun `supports callback fits multibyte boundary`() {
        val sixtyFourBytes = "€".repeat(21) + "a"
        val sixtyFiveBytes = "€".repeat(21) + "aa"

        assertTrue(SupportCallbacks.fits(sixtyFourBytes))
        assertFalse(SupportCallbacks.fits(sixtyFiveBytes))
    }

    @Test
    fun `parses support rating callback`() {
        val up = SupportCallbacks.parseRate("support_rate:42:up")
        val down = SupportCallbacks.parseRate("support_rate:7:down")

        assertEquals(42L, up?.ticketId)
        assertEquals(1, up?.rating)
        assertEquals(7L, down?.ticketId)
        assertEquals(-1, down?.rating)
    }

    @Test
    fun `rejects invalid support rating callback`() {
        assertNull(SupportCallbacks.parseRate("inv_confirm:123"))
        assertNull(SupportCallbacks.parseRate("support_rate:"))
        assertNull(SupportCallbacks.parseRate("support_rate:abc:up"))
        assertNull(SupportCallbacks.parseRate("support_rate:0:up"))
        assertNull(SupportCallbacks.parseRate("support_rate:1:sideways"))
        assertNull(SupportCallbacks.parseRate("support_rate:1:up:extra"))
    }

    @Test
    fun `second click returns already set text`() = runBlocking {
        val sender = RecordingTelegramSender()
        val supportService = RatingSupportService()
        val userRepository =
            object : UserRepository {
                override suspend fun getByTelegramId(id: Long): User? =
                    if (id == 101L) User(id = 55L, telegramId = 101L, username = "guest") else null

                override suspend fun getById(id: Long): User? =
                    if (id == 55L) User(id = 55L, telegramId = 101L, username = "guest") else null
            }
        val handler =
            SupportTelegramHandler(
                send = sender::send,
                supportService = supportService,
                userRepository = userRepository,
            )
        val update = mockCallbackUpdate("support_rate:10:up")

        handler.handle(update)
        handler.handle(update)

        val answers = sender.requests.filterIsInstance<AnswerCallbackQuery>()
        assertEquals(2, answers.size)
        assertEquals("Спасибо! Оценка сохранена.", answers[0].parameters["text"])
        assertEquals("Оценка уже сохранена.", answers[1].parameters["text"])
    }

    @Test
    fun `ack first then clears inline keyboard`() = runBlocking {
        val sender = RecordingTelegramSender()
        val supportService = RatingSupportService()
        val userRepository =
            object : UserRepository {
                override suspend fun getByTelegramId(id: Long): User? =
                    if (id == 101L) User(id = 55L, telegramId = 101L, username = "guest") else null

                override suspend fun getById(id: Long): User? =
                    if (id == 55L) User(id = 55L, telegramId = 101L, username = "guest") else null
            }
        val message = mockk<Message>()
        val chat = mockk<Chat>()
        every { message.chat() } returns chat
        every { chat.id() } returns 42L
        every { message.messageId() } returns 7
        val handler =
            SupportTelegramHandler(
                send = sender::send,
                supportService = supportService,
                userRepository = userRepository,
            )
        val update = mockCallbackUpdate("support_rate:10:up", message)

        handler.handle(update)

        assertEquals(2, sender.requests.size)
        assertTrue(sender.requests[0] is AnswerCallbackQuery)
        assertTrue(sender.requests[1] is EditMessageReplyMarkup)
    }

    @Test
    fun `best effort failures do not throw and only attempt one ack`() = runBlocking {
        val sender = FailingTelegramSender()
        val supportService = RatingSupportService()
        val userRepository =
            object : UserRepository {
                override suspend fun getByTelegramId(id: Long): User? =
                    if (id == 101L) User(id = 55L, telegramId = 101L, username = "guest") else null

                override suspend fun getById(id: Long): User? =
                    if (id == 55L) User(id = 55L, telegramId = 101L, username = "guest") else null
            }
        val message = mockk<Message>()
        val chat = mockk<Chat>()
        every { message.chat() } returns chat
        every { chat.id() } returns 42L
        every { message.messageId() } returns 7
        val handler =
            SupportTelegramHandler(
                send = sender::send,
                supportService = supportService,
                userRepository = userRepository,
            )
        val update = mockCallbackUpdate("support_rate:10:up", message)

        handler.handle(update)

        val answers = sender.requests.filterIsInstance<AnswerCallbackQuery>()
        assertEquals(1, answers.size)
        assertTrue(sender.requests.any { it is EditMessageReplyMarkup })
    }
}

class TelegramCallbackRouterTest {
    @Test
    fun `routes support callbacks to support handler`() = runBlocking {
        var supportCalls = 0
        var invitationCalls = 0
        val router =
            TelegramCallbackRouter(
                supportHandler = { supportCalls++ },
                invitationHandler = { invitationCalls++ },
            )
        val update = mockCallbackUpdate("support_rate:1:up")

        router.route(update)

        assertEquals(1, supportCalls)
        assertEquals(0, invitationCalls)
    }

    @Test
    fun `routes invitation callbacks to invitation handler`() = runBlocking {
        val router =
            TelegramCallbackRouter(
                supportHandler = { throw AssertionError("support should not be called") },
                invitationHandler = {},
            )
        val updateConfirm = mockCallbackUpdate("inv_confirm:abc")
        val updateDecline = mockCallbackUpdate("inv_decline:xyz")

        router.route(updateConfirm)
        router.route(updateDecline)
    }

    @Test
    fun `ignores other callback data`() = runBlocking {
        var supportCalls = 0
        var invitationCalls = 0
        val router =
            TelegramCallbackRouter(
                supportHandler = { supportCalls++ },
                invitationHandler = { invitationCalls++ },
            )
        val update = mockCallbackUpdate("other:callback")

        router.route(update)

        assertEquals(0, supportCalls)
        assertEquals(0, invitationCalls)
    }

    @Test
    fun `routes non callback updates to invitation handler`() = runBlocking {
        var invitationCalls = 0
        val router =
            TelegramCallbackRouter(
                supportHandler = { throw AssertionError("support should not be called") },
                invitationHandler = { invitationCalls++ },
            )
        val update = mockk<Update>()
        every { update.callbackQuery() } returns null

        router.route(update)

        assertEquals(1, invitationCalls)
    }
}

private class RecordingTelegramSender {
    val requests = mutableListOf<BaseRequest<*, *>>()

    suspend fun send(request: BaseRequest<*, *>): BaseResponse {
        requests += request
        return mockk()
    }
}

private class FailingTelegramSender {
    val requests = mutableListOf<BaseRequest<*, *>>()

    suspend fun send(request: BaseRequest<*, *>): BaseResponse {
        requests += request
        throw RuntimeException("send failed")
    }
}

private class RatingSupportService : SupportService {
    private var calls = 0

    override suspend fun setResolutionRating(
        ticketId: Long,
        userId: Long,
        rating: Int,
    ): SupportServiceResult<Ticket> {
        calls += 1
        return if (calls == 1) {
            SupportServiceResult.Success(sampleTicket(ticketId, userId))
        } else {
            SupportServiceResult.Failure(SupportServiceError.RatingAlreadySet)
        }
    }

    override suspend fun createTicket(
        clubId: Long,
        userId: Long,
        bookingId: UUID?,
        listEntryId: Long?,
        topic: TicketTopic,
        text: String,
        attachments: String?,
    ): SupportServiceResult<TicketWithMessage> = throw UnsupportedOperationException()

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

    override suspend fun getTicket(ticketId: Long): Ticket? = null
}

private fun sampleTicket(
    ticketId: Long,
    userId: Long,
): Ticket =
    Ticket(
        id = ticketId,
        clubId = 1L,
        userId = userId,
        bookingId = null,
        listEntryId = null,
        topic = TicketTopic.OTHER,
        status = TicketStatus.CLOSED,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        lastAgentId = null,
        resolutionRating = null,
    )

private fun mockCallbackUpdate(
    data: String,
    message: Message? = null,
): Update {
    val update = mockk<Update>()
    val callbackQuery = mockk<CallbackQuery>()
    val from = mockk<com.pengrad.telegrambot.model.User>()
    every { update.callbackQuery() } returns callbackQuery
    every { callbackQuery.data() } returns data
    every { callbackQuery.id() } returns "callback-id"
    every { callbackQuery.from() } returns from
    every { callbackQuery.message() } returns message
    every { from.id() } returns 101L
    return update
}
