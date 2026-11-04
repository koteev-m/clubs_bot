package com.example.bot.time

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

data class CalendarRules(
    val weekly: List<ClubHour>,
    val holidays: List<ClubHoliday>,
    val exceptions: List<ClubException>,
)

data class CalendarNight(
    val businessDate: LocalDate,
    val opensAt: ZonedDateTime,
    val closesAt: ZonedDateTime,
    val source: NightSource,
    val nightId: Long? = null,
)

data class CalendarPreview(
    val nights: List<CalendarNight>,
    val invalidDates: List<LocalDate>,
)

sealed interface CalendarDateResolution {
    data class Open(
        val night: CalendarNight,
    ) : CalendarDateResolution

    data object Closed : CalendarDateResolution

    data object Invalid : CalendarDateResolution
}

/** Resolves business dates for both the read-only preview and explicit materialization. */
class CalendarSchedule(
    rules: CalendarRules,
    private val zone: ZoneId,
) {
    private val weekly = rules.weekly.associateBy { it.dayOfWeek }
    private val holidays = rules.holidays.associateBy { it.date }
    private val exceptions = rules.exceptions.associateBy { it.date }

    val datedRules: Set<LocalDate> get() = holidays.keys + exceptions.keys

    fun resolve(date: LocalDate): CalendarDateResolution {
        val weeklyWindow =
            weekly[date.dayOfWeek]?.let {
                CalendarWindow(it.open, it.close, it.closeNextDay, it.startNextDay ?: false, NightSource.WEEKEND_RULE)
            }
        val holiday = holidays[date]
        val fallback =
            when {
                holiday == null -> weeklyWindow
                holiday.isOpen -> weeklyWindow.withHoliday(holiday)
                else -> null
            }
        val exception = exceptions[date]
        val window =
            when {
                exception == null -> fallback
                exception.isOpen -> (fallback ?: weeklyWindow).withException(exception)
                else -> null
            }
        return window?.resolve(date, zone) ?: CalendarDateResolution.Closed
    }

    fun preview(
        now: Instant,
        overrides: Map<LocalDate, CalendarDateResolution> = emptyMap(),
    ): CalendarPreview {
        val today = now.atZone(zone).toLocalDate()
        // Both boundaries may be offset, and a DST gap can shift the actual start forward.
        val first = today.minusDays(PREVIEW_LOOKBACK_DAYS)
        val extraDates = datedRules + overrides.keys
        val weeklyDays = if (weekly.isEmpty()) 0 else (PREVIEW_SIZE + extraDates.size + 2) * DayOfWeek.entries.size
        val candidates = (0..weeklyDays).asSequence().map { first.plusDays(it.toLong()) }
        val dates = (candidates + extraDates.asSequence().filter { it >= first }).toSortedSet()
        val invalid = mutableListOf<LocalDate>()
        val nights =
            dates
                .asSequence()
                .mapNotNull { date ->
                    when (val result = overrides[date] ?: resolve(date)) {
                        is CalendarDateResolution.Open -> result.night.takeIf { it.closesAt.toInstant().isAfter(now) }
                        CalendarDateResolution.Closed -> null
                        CalendarDateResolution.Invalid -> {
                            if (date >= today && invalid.size < PREVIEW_SIZE) invalid += date
                            null
                        }
                    }
                }.sortedWith(compareBy<CalendarNight> { it.opensAt.toInstant() }.thenBy { it.businessDate })
                .take(PREVIEW_SIZE)
                .toList()
        return CalendarPreview(nights, invalid)
    }
}

internal fun calendarPreview(
    rules: CalendarRules,
    zone: ZoneId,
    now: Instant,
): CalendarPreview = CalendarSchedule(rules, zone).preview(now)

private fun CalendarWindow?.withHoliday(rule: ClubHoliday): CalendarWindow? =
    overrideWindow(
        this,
        rule.overrideOpen,
        rule.overrideClose,
        rule.closeNextDay,
        rule.startNextDay,
        NightSource.HOLIDAY,
    )

private fun CalendarWindow?.withException(rule: ClubException): CalendarWindow? =
    overrideWindow(
        this,
        rule.overrideOpen,
        rule.overrideClose,
        rule.closeNextDay,
        rule.startNextDay,
        NightSource.EXCEPTION,
    )

private fun overrideWindow(
    fallback: CalendarWindow?,
    open: LocalTime?,
    close: LocalTime?,
    nextDay: Boolean?,
    startNextDay: Boolean?,
    source: NightSource,
): CalendarWindow? {
    val effectiveOpen = open ?: fallback?.open
    val effectiveClose = close ?: fallback?.close
    val effectiveNextDay = nextDay ?: if (open == null && close == null) fallback?.nextDay else null
    return if (effectiveOpen != null && effectiveClose != null) {
        CalendarWindow(
            effectiveOpen,
            effectiveClose,
            effectiveNextDay,
            startNextDay ?: fallback?.startNextDay ?: false,
            source,
        )
    } else {
        null
    }
}

private data class CalendarWindow(
    val open: LocalTime,
    val close: LocalTime,
    val nextDay: Boolean?,
    val startNextDay: Boolean,
    val source: NightSource,
) {
    fun resolve(
        date: LocalDate,
        zone: ZoneId,
    ): CalendarDateResolution {
        val startDate = if (startNextDay) date.plusDays(1) else date
        // Resolve each wall date independently. java.time moves gaps forward and picks the earlier overlap offset.
        val start = startDate.atTime(open).atZone(zone)
        val endsNextDay = nextDay ?: (close < open)
        val closeDate = if (endsNextDay) startDate.plusDays(1) else startDate
        val end = closeDate.atTime(close).atZone(zone)
        return if (end.isAfter(start)) {
            CalendarDateResolution.Open(CalendarNight(date, start, end, source))
        } else {
            CalendarDateResolution.Invalid
        }
    }
}

private const val PREVIEW_SIZE = 8
private const val PREVIEW_LOOKBACK_DAYS = 3L
