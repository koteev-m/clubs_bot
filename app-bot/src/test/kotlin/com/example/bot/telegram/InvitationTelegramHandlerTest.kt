package com.example.bot.telegram

import com.example.bot.club.InvitationResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class InvitationTelegramHandlerTest {
    @Test
    fun `parses start token with inv prefix`() {
        val token = InvitationTelegramHandler.parseStartToken("/start inv_AbCdEf")

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
