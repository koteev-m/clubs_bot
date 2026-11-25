package com.example.bot.policy

import com.example.bot.time.CalendarNight
import com.example.bot.time.NightSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime

class CabinetBookingRulesTest {
    @Test
    fun `autumn overlap uses elapsed minutes and retains the actual offset`() {
        val night =
            night(
                "2026-10-24T22:00+02:00[Europe/Berlin]",
                "2026-10-25T04:00+01:00[Europe/Berlin]",
            )
        val preview = CabinetBookingRules().preview(night)
        assertEquals("2026-10-25T02:00+01:00", preview.cutoffAt.toOffsetDateTime().toString())
        assertFalse(preview.cutoffAtOrBeforeOpen)
    }

    @Test
    fun `short night warns when the cutoff is before opening`() {
        val night = night("2026-11-01T22:00Z", "2026-11-01T23:00Z")
        val preview = CabinetBookingRules().preview(night)
        assertTrue(preview.cutoffAtOrBeforeOpen)
        assertEquals(night.closesAt.minusHours(2), preview.cutoffAt)
    }

    @Test
    fun `invalid duration cannot create configuration`() {
        assertThrows(IllegalArgumentException::class.java) { CabinetBookingRules(holdMinutes = 0) }
        assertThrows(IllegalArgumentException::class.java) { CabinetBookingRules(retentionMinutes = 1441) }
    }

    private fun night(
        start: String,
        end: String,
    ): CalendarNight {
        val open = ZonedDateTime.parse(start)
        return CalendarNight(open.toLocalDate(), open, ZonedDateTime.parse(end), NightSource.EXCEPTION)
    }
}
