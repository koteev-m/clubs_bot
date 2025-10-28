package com.example.bot.telegram.tokens

import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClubTokenCodecTest {
    @Test
    fun `encode decode round trip`() {
        val original = 42L
        val token = ClubTokenCodec.encode(original)
        assertEquals(original, ClubTokenCodec.decode(token))
        assertTrue(("club:$token").length < 64)
    }

    @Test
    fun `decode rejects invalid tokens`() {
        assertNull(ClubTokenCodec.decode(""))
        assertNull(ClubTokenCodec.decode("!@#"))
    }
}

class NightTokenCodecTest {
    @Test
    fun `encode decode round trip`() {
        val clubId = 512L
        val start = Instant.parse("2024-03-10T18:45:00Z")
        val token = NightTokenCodec.encode(clubId, start)
        val decoded = NightTokenCodec.decode(token)
        assertEquals(clubId, decoded?.first)
        assertEquals(start, decoded?.second)
        assertTrue(("night:$token").length < 64)
    }

    @Test
    fun `decode rejects malformed tokens`() {
        assertNull(NightTokenCodec.decode(""))
        assertNull(NightTokenCodec.decode("abc"))
        assertNull(NightTokenCodec.decode("a.b.c"))
        assertNull(NightTokenCodec.decode("a."))
        assertNull(NightTokenCodec.decode(".b"))
    }

    @Test
    fun `decode rejects invalid numbers`() {
        assertNull(NightTokenCodec.decode("zz.!"))
        assertNull(NightTokenCodec.decode("zz.-1"))
    }

    @Test
    fun `night callback data stays short for boundary values`() {
        val token = NightTokenCodec.encode(Long.MAX_VALUE, Instant.MAX)
        val callback = "night:$token"
        assertTrue(callback.length < 64)
    }
}
