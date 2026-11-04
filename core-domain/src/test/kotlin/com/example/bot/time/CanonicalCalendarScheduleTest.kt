package com.example.bot.time

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class CanonicalCalendarScheduleTest {
    @Test
    fun `delayed business date resolves a spring gap on the actual opening day`() {
        val saturday = LocalDate.parse("2026-03-28")
        val schedule =
            schedule(ClubHour(DayOfWeek.SATURDAY, time("02:30"), time("08:00"), false, true))
        val night = (schedule.resolve(saturday) as CalendarDateResolution.Open).night
        assertEquals(saturday, night.businessDate)
        assertEquals("2026-03-29T03:30+02:00", night.opensAt.toOffsetDateTime().toString())
        assertEquals("2026-03-29T08:00+02:00", night.closesAt.toOffsetDateTime().toString())
    }

    @Test
    fun `delayed opening resolves autumn overlap and can also close the day after opening`() {
        val saturday = LocalDate.parse("2026-10-24")
        val schedule =
            schedule(ClubHour(DayOfWeek.SATURDAY, time("02:30"), time("01:00"), true, true))
        val night = (schedule.resolve(saturday) as CalendarDateResolution.Open).night
        assertEquals(saturday, night.businessDate)
        assertEquals("2026-10-25T02:30+02:00", night.opensAt.toOffsetDateTime().toString())
        assertEquals("2026-10-26T01:00+01:00", night.closesAt.toOffsetDateTime().toString())
    }

    @Test
    fun `preview orders nonoverlapping delayed nights by actual opening`() {
        val rules =
            CalendarRules(
                listOf(
                    ClubHour(DayOfWeek.TUESDAY, time("22:00"), time("23:00"), false, true),
                    ClubHour(DayOfWeek.WEDNESDAY, time("10:00"), time("18:00"), false, false),
                ),
                emptyList(),
                emptyList(),
            )
        val preview = CalendarSchedule(rules, ZoneId.of("Europe/Berlin")).preview(Instant.parse("2026-11-03T08:00:00Z"))
        assertEquals(listOf("2026-11-04", "2026-11-03"), preview.nights.take(2).map { it.businessDate.toString() })
    }

    private fun schedule(weekly: ClubHour): CalendarSchedule =
        CalendarSchedule(CalendarRules(listOf(weekly), emptyList(), emptyList()), ZoneId.of("Europe/Berlin"))

    private fun time(value: String): LocalTime = LocalTime.parse(value)
}
