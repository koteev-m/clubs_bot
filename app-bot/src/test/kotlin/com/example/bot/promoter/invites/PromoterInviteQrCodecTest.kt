package com.example.bot.promoter.invites

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class PromoterInviteQrCodecTest {
    private val issuedAt = Instant.parse("2024-06-12T15:00:00Z")

    @Test
    fun `encode and decode roundtrip`() {
        val token = PromoterInviteQrCodec.encode(inviteId = 10, eventId = 20, issuedAt = issuedAt, secret = "secret")

        val decoded = PromoterInviteQrCodec.tryDecode(token, "secret")

        assertEquals(10L, decoded!!.inviteId)
        assertEquals(20L, decoded.eventId)
        assertEquals(issuedAt, decoded.issuedAt)
    }

    @Test
    fun `decode fails with wrong secret`() {
        val token = PromoterInviteQrCodec.encode(inviteId = 1, eventId = 2, issuedAt = issuedAt, secret = "secret")

        val decoded = PromoterInviteQrCodec.tryDecode(token, "other")

        assertNull(decoded)
    }

    @Test
    fun `decode fails for malformed token`() {
        val decoded = PromoterInviteQrCodec.tryDecode("INV:bad:token", "secret")

        assertNull(decoded)
    }
}
