package com.example.bot.notifications

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TemplatesSafetyTest {
    @Test
    fun `markdown special characters are escaped`() {
        val special = "_*[]()~\\>#+-=|{}.!`"
        val escaped = escapeMdV2(special)
        val expected = "\\_\\*\\[\\]\\(\\)\\~\\\\\\>\\#\\+\\-\\=\\|\\{\\}\\.\\!\\`"
        assertEquals(expected, escaped)
        assertEquals("\u2705 Booking created: $expected", tmplBookingCreatedEN(special))
        assertEquals("Напоминание: $expected", tmplReminderRU(special))
    }

    @Test
    fun `html special characters are escaped`() {
        val special = "<tag>\"&"
        val escaped = escapeHtml(special)
        val expected = "&lt;tag&gt;&quot;&amp;"
        assertEquals(expected, escaped)
        assertEquals("<b>$expected</b>", tmplAfishaEN(special))
    }
}
