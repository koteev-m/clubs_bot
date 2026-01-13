package com.example.bot.telegram

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SupportCallbacksTest {
    @Test
    fun `supports callback fits byte boundary`() {
        assertTrue(SupportCallbacks.fits("a".repeat(64)))
        assertFalse(SupportCallbacks.fits("a".repeat(65)))
    }

    @Test
    fun `supports callback fits multibyte boundary`() {
        val str64 = "€".repeat(21) + "a"
        val str65 = "€".repeat(21) + "aa"

        assertEquals(64, str64.toByteArray(Charsets.UTF_8).size)
        assertEquals(65, str65.toByteArray(Charsets.UTF_8).size)
        assertTrue(SupportCallbacks.fits(str64))
        assertFalse(SupportCallbacks.fits(str65))
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
}
