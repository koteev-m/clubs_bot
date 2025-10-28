package com.example.bot.time

import com.example.bot.availability.AvailabilityRepository
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Source of the slot generation.
 */
enum class NightSource {
    WEEKEND_RULE,
    HOLIDAY,
    EXCEPTION,
    EVENT_MATERIALIZED,
}

/**
 * Represents a single night slot in UTC and local time.
 */
data class NightSlot(
    val clubId: Long,
    val eventStartUtc: Instant,
    val eventEndUtc: Instant,
    val isSpecial: Boolean,
    val source: NightSource,
    val openLocal: LocalDateTime,
    val closeLocal: LocalDateTime,
    val zone: ZoneId,
)

private data class DayHours(val open: LocalTime, val close: LocalTime)

private data class DayException(
    val isOpen: Boolean,
    val overrideOpen: LocalTime?,
    val overrideClose: LocalTime?,
)

private data class DayHoliday(
    val isOpen: Boolean,
    val overrideOpen: LocalTime?,
    val overrideClose: LocalTime?,
)

/**
 * Weekly operating hours for a club.
 */
data class ClubHour(val dayOfWeek: DayOfWeek, val open: LocalTime, val close: LocalTime)

/**
 * Special holiday rules for a club.
 */
data class ClubHoliday(
    val date: LocalDate,
    val isOpen: Boolean,
    val overrideOpen: LocalTime?,
    val overrideClose: LocalTime?,
)

/**
 * Exceptions override all other rules.
 */
data class ClubException(
    val date: LocalDate,
    val isOpen: Boolean,
    val overrideOpen: LocalTime?,
    val overrideClose: LocalTime?,
)

/**
 * Minimal club representation.
 */
data class Club(val id: Long, val timezone: String)

/**
 * Materialized event stored in the database.
 */
data class Event(
    val id: Long,
    val clubId: Long,
    val startUtc: Instant,
    val endUtc: Instant,
    val isSpecial: Boolean = true,
)

/**
 * Resolves operating rules into concrete night slots.
 *
 * Rules cascade with the following precedence: exception overrides holiday, which overrides base.
 * When an override omits a boundary, the value is inherited from the previous source in the chain.
 * Intervals where open equals close are treated as invalid unless explicitly defined as 24 hours and are skipped.
 * Overnight windows are normalized by shifting close to the next day.
 * This occurs when close is less than or equal to open before UTC conversion.
 */
class OperatingRulesResolver(
    private val repository: AvailabilityRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(OperatingRulesResolver::class.java)

    private val debugEnabled: Boolean =
        System.getenv("RULES_DEBUG")?.equals("true", ignoreCase = true) == true || logger.isDebugEnabled

    private fun logDebug(
        message: String,
        vararg args: Any?,
    ) {
        if (!debugEnabled) return
        if (logger.isDebugEnabled) {
            logger.debug(message, *args)
        } else {
            logger.info("[rules-debug] $message", *args)
        }
    }

    private fun logInfo(
        message: String,
        vararg args: Any?,
    ) {
        logger.info(message, *args)
    }

    private fun logWarn(
        message: String,
        vararg args: Any?,
    ) {
        logger.warn(message, *args)
    }

    /**
     * Resolve slots for given club and period.
     */
    @Suppress(
        "LongMethod",
        "CyclomaticComplexMethod",
        "ReturnCount",
        "LoopWithTooManyJumpStatements",
        "NestedBlockDepth",
    )
    suspend fun resolve(
        clubId: Long,
        fromUtc: Instant,
        toUtc: Instant,
    ): List<NightSlot> {
        val club = repository.findClub(clubId) ?: return emptyList()
        val zone = ZoneId.of(club.timezone)
        val fromDate = fromUtc.atZone(zone).toLocalDate()
        val toDate = toUtc.atZone(zone).toLocalDate()

        val hours = repository.listClubHours(clubId)
        val holidays = repository.listHolidays(clubId, fromDate, toDate).associateBy { it.date }
        val exceptions = repository.listExceptions(clubId, fromDate, toDate).associateBy { it.date }
        val events = repository.listEvents(clubId, fromUtc, toUtc)

        val result = mutableListOf<NightSlot>()

        var date = fromDate
        while (!date.isAfter(toDate)) {
            val exception = exceptions[date]
            val holiday = holidays[date]
            val baseHour = hours.find { it.dayOfWeek == date.dayOfWeek }

            logDebug(
                "rules.merge_input date={} base_open={} base_close={} exception_is_open={} exception_open={} " +
                    "exception_close={} holiday_is_open={} holiday_open={} holiday_close={}",
                date,
                baseHour?.open,
                baseHour?.close,
                exception?.isOpen,
                exception?.overrideOpen,
                exception?.overrideClose,
                holiday?.isOpen,
                holiday?.overrideOpen,
                holiday?.overrideClose,
            )

            val resolution =
                mergeDayHours(
                    base = baseHour?.let { DayHours(it.open, it.close) },
                    exception =
                        exception?.let {
                            DayException(
                                isOpen = it.isOpen,
                                overrideOpen = it.overrideOpen,
                                overrideClose = it.overrideClose,
                            )
                        },
                    holiday =
                        holiday?.let {
                            DayHoliday(
                                isOpen = it.isOpen,
                                overrideOpen = it.overrideOpen,
                                overrideClose = it.overrideClose,
                            )
                        },
                )

            when (resolution) {
                is DayResolution.Open -> {
                    val dayHours = resolution.hours
                    val dow = date.dayOfWeek.value
                    val overnight = dayHours.close <= dayHours.open
                    logDebug(
                        "rules.merge_output date={} open={} open_source={} close={} close_source={} overnight={}",
                        date,
                        dayHours.open,
                        dayHours.openSource.logKey,
                        dayHours.close,
                        dayHours.closeSource.logKey,
                        overnight,
                    )

                    if (dayHours.exceptionApplied) {
                        RulesMetrics.incExceptionApplied(dow, overnight)
                    }
                    if (dayHours.holidayApplied) {
                        if (dayHours.holidayInheritedOpen) {
                            RulesMetrics.incHolidayInheritedOpen(dow, overnight)
                        }
                        if (dayHours.holidayInheritedClose) {
                            RulesMetrics.incHolidayInheritedClose(dow, overnight)
                        }
                    }

                    if (dayHours.open == dayHours.close) {
                        logWarn(
                            "rules.invalid_window open==close date={} open={} close={}",
                            date,
                            dayHours.open,
                            dayHours.close,
                        )
                    } else {
                        RulesMetrics.incDayOpen(dow, dayHours.exceptionApplied, dayHours.holidayApplied, overnight)
                        logInfo(
                            "rules.day_open date={} open={} close={} overnight={}",
                            date,
                            dayHours.open,
                            dayHours.close,
                            overnight,
                        )

                        val source =
                            when {
                                holiday?.isOpen == true -> NightSource.HOLIDAY
                                exception?.isOpen == true -> NightSource.EXCEPTION
                                baseHour != null -> NightSource.WEEKEND_RULE
                                else -> NightSource.WEEKEND_RULE
                            }
                        val (startUtc, endUtc) = toUtcWindow(date, dayHours.toDayHours(), zone)
                        if (endUtc > startUtc) {
                            result +=
                                NightSlot(
                                    clubId = clubId,
                                    eventStartUtc = startUtc,
                                    eventEndUtc = endUtc,
                                    isSpecial = source != NightSource.WEEKEND_RULE,
                                    source = source,
                                    openLocal = startUtc.atZone(zone).toLocalDateTime(),
                                    closeLocal = endUtc.atZone(zone).toLocalDateTime(),
                                    zone = zone,
                                )
                        }
                    }
                }
                is DayResolution.Closed -> {
                    logInfo("rules.day_closed date={} reason={}", date, resolution.reason.logKey)
                }
            }

            date = date.plusDays(1)
        }

        events.forEach { event ->
            val startLocal = event.startUtc.atZone(zone).toLocalDateTime()
            val endLocal = event.endUtc.atZone(zone).toLocalDateTime()
            result +=
                NightSlot(
                    clubId = clubId,
                    eventStartUtc = event.startUtc,
                    eventEndUtc = event.endUtc,
                    isSpecial = event.isSpecial,
                    source = NightSource.EVENT_MATERIALIZED,
                    openLocal = startLocal,
                    closeLocal = endLocal,
                    zone = zone,
                )
        }

        val now = Instant.now(clock)
        val filtered = result.filter { it.eventEndUtc > now }

        val sorted = filtered.sortedBy { it.eventStartUtc }
        if (sorted.isEmpty()) return sorted

        val merged = mutableListOf<NightSlot>()
        for (slot in sorted) {
            val last = merged.lastOrNull()
            if (shouldMerge(last, slot)) {
                merged[merged.lastIndex] = last!!.copy(eventEndUtc = slot.eventEndUtc, closeLocal = slot.closeLocal)
            } else {
                merged += slot
            }
        }
        return merged
    }

    private fun shouldMerge(
        last: NightSlot?,
        slot: NightSlot,
    ): Boolean =
        last != null &&
            last.source == slot.source &&
            last.isSpecial == slot.isSpecial &&
            last.eventEndUtc == slot.eventStartUtc
}

private enum class BoundarySource(val logKey: String) {
    BASE("base"),
    EXCEPTION("exception"),
    HOLIDAY("holiday"),
    INHERITED("inherited"),
}

private enum class ClosedReason(val logKey: String) {
    EXCEPTION_CLOSED("exception_closed"),
    HOLIDAY_CLOSED("holiday_closed"),
    NO_BASE_INCOMPLETE_HOLIDAY("no_base_incomplete_holiday"),
}

private data class HoursWithSource(
    val open: LocalTime,
    val close: LocalTime,
    val openSource: BoundarySource,
    val closeSource: BoundarySource,
)

private data class ResolvedDayHours(
    val open: LocalTime,
    val close: LocalTime,
    val openSource: BoundarySource,
    val closeSource: BoundarySource,
    val exceptionApplied: Boolean,
    val holidayApplied: Boolean,
    val holidayInheritedOpen: Boolean,
    val holidayInheritedClose: Boolean,
)

private fun ResolvedDayHours.toDayHours(): DayHours = DayHours(open, close)

private sealed class DayResolution {
    data class Open(val hours: ResolvedDayHours) : DayResolution()

    data class Closed(val reason: ClosedReason) : DayResolution()
}

@Suppress("ReturnCount")
private fun mergeDayHours(
    base: DayHours?,
    exception: DayException?,
    holiday: DayHoliday?,
): DayResolution {
    val baseHours = base?.let { HoursWithSource(it.open, it.close, BoundarySource.BASE, BoundarySource.BASE) }

    var exceptionApplied = false
    var current = baseHours

    if (exception != null) {
        if (!exception.isOpen) {
            return DayResolution.Closed(ClosedReason.EXCEPTION_CLOSED)
        }
        val baseForException = current ?: baseHours
        if (baseForException == null) {
            return DayResolution.Closed(ClosedReason.NO_BASE_INCOMPLETE_HOLIDAY)
        }
        val openOverride = exception.overrideOpen
        val closeOverride = exception.overrideClose
        val open = openOverride ?: baseForException.open
        val close = closeOverride ?: baseForException.close
        current =
            HoursWithSource(
                open = open,
                close = close,
                openSource = if (openOverride != null) BoundarySource.EXCEPTION else BoundarySource.INHERITED,
                closeSource = if (closeOverride != null) BoundarySource.EXCEPTION else BoundarySource.INHERITED,
            )
        exceptionApplied = openOverride != null || closeOverride != null
    }

    if (holiday != null) {
        if (!holiday.isOpen) {
            return DayResolution.Closed(ClosedReason.HOLIDAY_CLOSED)
        }

        val openOverride = holiday.overrideOpen
        val closeOverride = holiday.overrideClose
        val fallback = current ?: baseHours

        if (openOverride == null && closeOverride == null && fallback == null) {
            return DayResolution.Closed(ClosedReason.NO_BASE_INCOMPLETE_HOLIDAY)
        }

        val open = openOverride ?: fallback?.open
        val close = closeOverride ?: fallback?.close
        if (open == null || close == null) {
            return DayResolution.Closed(ClosedReason.NO_BASE_INCOMPLETE_HOLIDAY)
        }

        val openSource = if (openOverride != null) BoundarySource.HOLIDAY else BoundarySource.INHERITED
        val closeSource = if (closeOverride != null) BoundarySource.HOLIDAY else BoundarySource.INHERITED

        return DayResolution.Open(
            ResolvedDayHours(
                open = open,
                close = close,
                openSource = openSource,
                closeSource = closeSource,
                exceptionApplied = exceptionApplied,
                holidayApplied = true,
                holidayInheritedOpen = openOverride == null && fallback != null,
                holidayInheritedClose = closeOverride == null && fallback != null,
            ),
        )
    }

    val finalHours = current ?: baseHours
    return if (finalHours != null) {
        DayResolution.Open(
            ResolvedDayHours(
                open = finalHours.open,
                close = finalHours.close,
                openSource = finalHours.openSource,
                closeSource = finalHours.closeSource,
                exceptionApplied = exceptionApplied,
                holidayApplied = false,
                holidayInheritedOpen = false,
                holidayInheritedClose = false,
            ),
        )
    } else {
        DayResolution.Closed(ClosedReason.NO_BASE_INCOMPLETE_HOLIDAY)
    }
}

private fun toUtcWindow(
    localDate: LocalDate,
    hours: DayHours,
    zone: ZoneId,
): Pair<Instant, Instant> {
    val openZdt = localDate.atTime(hours.open).atZone(zone)
    val closeZdt0 = localDate.atTime(hours.close).atZone(zone)
    val closeZdt = if (!closeZdt0.isAfter(openZdt)) closeZdt0.plusDays(1) else closeZdt0
    return openZdt.toInstant() to closeZdt.toInstant()
}
