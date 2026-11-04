package com.example.bot.time

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class CalendarRulePreviewTest {
    @Test
    fun `explicit next day preserves start date and a night still in progress`() {
        val date = LocalDate.parse("2026-10-30")
        val rules =
            CalendarRules(
                listOf(ClubHour(DayOfWeek.FRIDAY, time("00:00"), time("12:00"), true)),
                emptyList(),
                emptyList(),
            )
        val first = preview(rules, "2026-10-31T09:00:00Z").nights.first()
        assertEquals(date, first.businessDate)
        assertEquals("2026-10-30T00:00Z[Europe/London]", first.opensAt.toString())
        assertEquals("2026-10-31T12:00Z[Europe/London]", first.closesAt.toString())
        assertEquals(date.plusWeeks(1), preview(rules, "2026-10-31T13:00:00Z").nights.first().businessDate)
    }

    @Test
    fun `spring gap adjusts the closing wall time on the following day and reports a collapsed interval`() {
        val weekly =
            listOf(
                ClubHour(DayOfWeek.SATURDAY, time("22:00"), time("01:30"), true),
                ClubHour(DayOfWeek.SUNDAY, time("01:30"), time("02:30"), false),
            )
        val result = preview(CalendarRules(weekly, emptyList(), emptyList()), "2026-03-28T18:00:00Z")
        val first = result.nights.first()
        assertEquals("2026-03-28", first.businessDate.toString())
        assertEquals("2026-03-29T02:30+01:00[Europe/London]", first.closesAt.toString())
        assertEquals(listOf(LocalDate.parse("2026-03-29")), result.invalidDates)
        assertFalse(result.nights.any { it.businessDate.toString() == "2026-03-29" })
    }

    @Test
    fun `exception wins over holiday and overlap chooses the earlier offset`() {
        val date = LocalDate.parse("2026-10-25")
        val rules =
            CalendarRules(
                emptyList(),
                listOf(ClubHoliday(date, false, null, null)),
                listOf(ClubException(date, true, time("01:30"), time("02:30"), false)),
            )
        val night = preview(rules, "2026-10-24T22:00:00Z").nights.single()
        assertEquals(NightSource.EXCEPTION, night.source)
        assertEquals("2026-10-25T00:30:00Z", night.opensAt.toInstant().toString())
        assertEquals("2026-10-25T02:30:00Z", night.closesAt.toInstant().toString())
    }

    @Test
    fun `cancels a holiday and includes an additional night even when no weekly rule exists`() {
        val closed = LocalDate.parse("2026-11-05")
        val extra = LocalDate.parse("2027-04-10")
        val rules =
            CalendarRules(
                emptyList(),
                listOf(ClubHoliday(closed, true, time("22:00"), time("06:00"))),
                listOf(
                    ClubException(closed, false, null, null),
                    ClubException(extra, true, time("21:00"), time("03:00")),
                ),
            )
        val night = preview(rules, "2026-11-03T00:00:00Z").nights.single()
        assertEquals(extra, night.businessDate)
        assertEquals(extra.plusDays(1), night.closesAt.toLocalDate())
    }

    private fun preview(
        rules: CalendarRules,
        now: String,
    ): CalendarPreview = OperatingRulesResolver.previewCalendar(rules, ZoneId.of("Europe/London"), Instant.parse(now))

    private fun time(value: String): LocalTime = LocalTime.parse(value)
}
