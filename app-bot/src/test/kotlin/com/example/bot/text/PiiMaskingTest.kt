package com.example.bot.text

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PiiMaskingTest {
    @Test
    fun `masks outgoing sensitive fragments`() {
        val source = "phone=+7 (999) 111-22-33 initData=abc qrSecret=q1 idempotency-key=idem-1 token=t0"

        val masked = maskSensitiveOutgoingText(source)

        assertFalse(masked.contains("+7 (999) 111-22-33"))
        assertFalse(masked.contains("abc"))
        assertFalse(masked.contains("q1"))
        assertFalse(masked.contains("idem-1"))
        assertFalse(masked.contains("token=t0"))
        assertTrue(masked.contains("***"))
    }
}
