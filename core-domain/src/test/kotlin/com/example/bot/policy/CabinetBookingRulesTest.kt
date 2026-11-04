package com.example.bot.policy

import com.example.bot.time.CalendarNight
import com.example.bot.time.NightSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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
        val preview = CabinetBookingRules(arrivalMinutes = 300).preview(night)
        assertEquals("2026-10-25T02:00+01:00", preview.cutoffAt.toOffsetDateTime().toString())
        assertEquals("2026-10-25T02:00+01:00", preview.arrivalBy?.toOffsetDateTime().toString())
        assertFalse(preview.cutoffAfterArrival)
    }

    @Test
    fun `short night exposes ordering warnings without clamping either instant`() {
        val night = night("2026-11-01T22:00Z", "2026-11-01T23:00Z")
        val preview = CabinetBookingRules(arrivalMinutes = 60).preview(night)
        assertEquals(night.closesAt, preview.arrivalBy)
        assertTrue(preview.arrivalAtOrAfterClose)
        assertTrue(preview.cutoffAtOrBeforeOpen)
        assertNull(CabinetBookingRules().preview(night).arrivalBy)
    }

    @Test
    fun `invalid duration cannot create configuration`() {
        assertThrows(IllegalArgumentException::class.java) { CabinetBookingRules(holdMinutes = 0) }
        assertThrows(IllegalArgumentException::class.java) { CabinetBookingRules(arrivalMinutes = 1441) }
    }

    private fun night(
        start: String,
        end: String,
    ): CalendarNight {
        val open = ZonedDateTime.parse(start)
        return CalendarNight(open.toLocalDate(), open, ZonedDateTime.parse(end), NightSource.EXCEPTION)
    }
}
