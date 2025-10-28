package com.example.bot.telegram.tokens

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuestsSelectCodecTest {
    @Test
    fun `encode decode round trip`() {
        val start = Instant.parse("2024-04-01T18:00:00Z")
        val end = start.plusSeconds(6 * 3600)
        val token =
            GuestsSelectCodec.encode(
                clubId = 1234L,
                startUtc = start,
                endUtc = end,
                tableId = 55L,
                guests = 4,
            )
        assertTrue(token.length <= 64)
        val decoded = GuestsSelectCodec.decode(token)
        assertNotNull(decoded)
        assertEquals(1234L, decoded.clubId)
        assertEquals(start, decoded.startUtc)
        assertEquals(end, decoded.endUtc)
        assertEquals(55L, decoded.tableId)
        assertEquals(4, decoded.guests)
    }

    @Test
    fun `encode normalizes zero end`() {
        val start = Instant.parse("2024-04-01T18:00:00Z")
        val token =
            GuestsSelectCodec.encode(
                clubId = 1L,
                startUtc = start,
                endUtc = start,
                tableId = 2L,
                guests = 2,
            )
        val decoded = GuestsSelectCodec.decode(token)
        assertNotNull(decoded)
        assertTrue(decoded.endUtc.isAfter(start))
    }

    @Test
    fun `decode rejects malformed tokens`() {
        assertNull(GuestsSelectCodec.decode(""))
        assertNull(GuestsSelectCodec.decode("g:"))
        assertNull(GuestsSelectCodec.decode("g:a.b.c.d"))
        assertNull(GuestsSelectCodec.decode("g:a.b.c.d.e.f"))
    }

    @Test
    fun `decode rejects invalid numbers`() {
        assertNull(GuestsSelectCodec.decode("g:-1.a.a.a.a"))
        assertNull(GuestsSelectCodec.decode("g:a.-1.a.a.a"))
        assertNull(GuestsSelectCodec.decode("g:a.a.-1.a.a"))
        assertNull(GuestsSelectCodec.decode("g:a.a.a.-1.a"))
        assertNull(GuestsSelectCodec.decode("g:a.a.a.a.-1"))
    }
}
