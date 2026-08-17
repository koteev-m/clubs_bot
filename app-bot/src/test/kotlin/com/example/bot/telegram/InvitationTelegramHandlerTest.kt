package com.example.bot.telegram

import com.example.bot.club.InvitationResponse
import com.example.bot.club.InvitationService
import com.example.bot.club.InvitationServiceError
import com.example.bot.club.InvitationServiceResult
import com.pengrad.telegrambot.model.Chat
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.response.BaseResponse
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class InvitationTelegramHandlerTest {
    @Test
    fun `production handler resolves invitation start token`() =
        runBlocking {
            val service = mockk<InvitationService>()
            val requests = mutableListOf<BaseRequest<*, *>>()
            coEvery { service.resolveInvitation("AbCdEf") } returns
                InvitationServiceResult.Failure(InvitationServiceError.INVITATION_INVALID)
            val handler =
                InvitationTelegramHandler(
                    send = { request ->
                        requests += request
                        mockk<BaseResponse>()
                    },
                    invitationService = service,
                    meterRegistry = SimpleMeterRegistry(),
                )

            handler.handle(invitationStartUpdate("/start inv_AbCdEf"))

            coVerify(exactly = 1) { service.resolveInvitation("AbCdEf") }
            assertEquals(1, requests.filterIsInstance<SendMessage>().size)
        }

    @Test
    fun `parses start token with inv prefix`() {
        val token = InvitationTelegramHandler.parseStartToken("/start inv_AbCdEf")

        assertEquals("AbCdEf", token)
    }

    @Test
    fun `parses start token without trailing payload`() {
        val token = InvitationTelegramHandler.parseStartToken("/start inv_AbCdEf extra")

        assertEquals("AbCdEf", token)
    }

    @Test
    fun `parses start token with bot name`() {
        val token = InvitationTelegramHandler.parseStartToken("/start@MyBot inv_AbCdEf")

        assertEquals("AbCdEf", token)
    }

    @Test
    fun `ignores unrelated start payloads`() {
        assertNull(InvitationTelegramHandler.parseStartToken("/start promo_123"))
        assertNull(InvitationTelegramHandler.parseStartToken("/start"))
    }

    @Test
    fun `parses invitation callback routing`() {
        val confirm = InvitationTelegramHandler.parseCallbackData("inv_confirm:token123")
        val decline = InvitationTelegramHandler.parseCallbackData("inv_decline:token456")

        assertEquals(InvitationResponse.CONFIRM, confirm?.response)
        assertEquals("token123", confirm?.token)
        assertEquals(InvitationResponse.DECLINE, decline?.response)
        assertEquals("token456", decline?.token)
        assertNull(InvitationTelegramHandler.parseCallbackData("unknown:data"))
    }
}

private fun invitationStartUpdate(text: String): Update {
    val update = mockk<Update>()
    val message = mockk<Message>()
    val chat = mockk<Chat>()
    every { update.message() } returns message
    every { update.callbackQuery() } returns null
    every { message.text() } returns text
    every { message.chat() } returns chat
    every { chat.id() } returns 42L
    return update
}
