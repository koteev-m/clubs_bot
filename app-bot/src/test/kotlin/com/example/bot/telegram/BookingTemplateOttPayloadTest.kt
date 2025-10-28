package com.example.bot.telegram

import com.example.bot.telegram.ott.CallbackTokenService
import com.example.bot.telegram.ott.TemplateOttPayload.Booking
import com.example.bot.telegram.ott.TemplateOttPayload.Selection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class BookingTemplateOttPayloadTest {
    private val tokenService = CallbackTokenService()

    @Test
    fun `booking payload round trip`() {
        val payload =
            Booking(
                templateId = 12L,
                clubId = 34L,
                tableId = 56L,
                slotStart = Instant.parse("2025-05-01T18:00:00Z"),
                slotEnd = Instant.parse("2025-05-01T21:00:00Z"),
                guests = 4,
            )
        val token = tokenService.issueToken(payload)
        assertTrue(token.length <= 64)

        val decoded = tokenService.consume(token) as? Booking
        assertEquals(payload, decoded)

        val replay = tokenService.consume(token)
        assertNull(replay)
    }

    @Test
    fun `selection payload fits callback limit`() {
        val payload = Selection(templateId = Long.MAX_VALUE)
        val token = tokenService.issueToken(payload)
        assertTrue(token.length <= 64)

        val decoded = tokenService.consume(token)
        assertEquals(payload, decoded)
    }
}
