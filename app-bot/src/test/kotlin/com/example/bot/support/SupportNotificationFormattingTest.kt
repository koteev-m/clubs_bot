package com.example.bot.support

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SupportNotificationFormattingTest {
    @Test
    fun `support reply message sanitizes club name`() {
        val message = buildSupportReplyMessage("  Foo\nBar   ", "Спасибо")

        assertTrue(message.startsWith("Ответ от клуба «Foo Bar»"))
        assertFalse(message.contains("#"))
        assertFalse(message.contains("null"))
    }

    @Test
    fun `support reply message without club name omits quotes`() {
        listOf<String?>(null, "   ").forEach { rawName ->
            val message = buildSupportReplyMessage(rawName, "Спасибо")

            assertTrue(message.startsWith("Ответ от клуба"))
            assertFalse(message.startsWith("Ответ от клуба «"))
            assertFalse(message.contains("«"))
            assertFalse(message.contains("#"))
            assertFalse(message.contains("null"))
        }
    }
}
