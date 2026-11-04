package com.example.bot.data.client.onboarding

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException

internal enum class CalendarRuleTable(
    val table: String,
    val key: String,
    val open: String,
    val close: String,
) {
    WEEKLY("club_hours", "dow", "open_local", "close_local"),
    HOLIDAYS("club_holidays", "date", "override_open_local", "override_close_local"),
    EXCEPTIONS("club_exceptions", "date", "override_open_local", "override_close_local"),
}

internal enum class CalendarChangeMode { OPEN, CLOSED, REMOVE }

internal data class CalendarChange(
    val table: CalendarRuleTable,
    val key: Any,
    val mode: CalendarChangeMode,
    val window: CalendarTimes?,
)

internal data class CalendarTimes(
    val open: LocalTime,
    val close: LocalTime,
    val nextDay: Boolean,
    val startNextDay: Boolean,
)

internal fun JsonObject.calendarChanges(): List<CalendarChange> {
    only(listOf("expectedVersion", "timezone", "weekly", "holidays", "exceptions", "reconcile"))
    val changes =
        CalendarRuleTable.entries.flatMap { table ->
            val rows = this[table.name.lowercase()] as? JsonArray ?: fail("validation_error")
            if (rows.size >
                if (table == CalendarRuleTable.WEEKLY) DayOfWeek.entries.size else MAX_CHANGES
            ) {
                fail("validation_error")
            }
            rows.map { (it as? JsonObject ?: fail("validation_error")).calendarChange(table) }
        }
    val reconcile = if ("reconcile" in this) calendarBoolean("reconcile") else false
    if (changes.isEmpty() && !reconcile) fail("validation_error")
    if (changes.size > MAX_CHANGES || changes.distinctBy { it.table to it.key }.size != changes.size) {
        fail("validation_error")
    }
    return changes
}

private fun JsonObject.calendarChange(table: CalendarRuleTable): CalendarChange {
    val weekly = table == CalendarRuleTable.WEEKLY
    val keyName = if (weekly) "day" else "date"
    val key = if (weekly) calendarDay() else calendarDate()
    val mode =
        when (string("mode")) {
            "open" -> CalendarChangeMode.OPEN
            "closed" -> if (weekly) fail("validation_error") else CalendarChangeMode.CLOSED
            "remove" -> CalendarChangeMode.REMOVE
            else -> fail("validation_error")
        }
    if (mode != CalendarChangeMode.OPEN) {
        only(listOf(keyName, "mode"))
        return CalendarChange(table, key, mode, null)
    }
    only(listOf(keyName, "mode", "open", "close", "nextDay", "startNextDay"))
    val open = calendarTime("open")
    val close = calendarTime("close")
    val nextDay = calendarBoolean("nextDay")
    val startNextDay = if ("startNextDay" in this) calendarBoolean("startNextDay") else false
    if (!nextDay && close <= open) fail("validation_error")
    return CalendarChange(table, key, mode, CalendarTimes(open, close, nextDay, startNextDay))
}

private fun JsonObject.calendarBoolean(key: String): Boolean =
    (this[key] as? JsonPrimitive)
        ?.takeUnless { it.isString }
        ?.booleanOrNull ?: fail("validation_error")

private fun JsonObject.calendarDay(): Int =
    (this["day"] as? JsonPrimitive)
        ?.takeUnless { it.isString }
        ?.intOrNull
        ?.takeIf { it in 1..DayOfWeek.entries.size } ?: fail("validation_error")

private fun JsonObject.calendarDate(): LocalDate {
    val value = string("date")?.takeIf(DATE::matches) ?: fail("validation_error")
    return try {
        LocalDate.parse(value).takeIf { it.year in 1..LAST_CALENDAR_YEAR } ?: fail("validation_error")
    } catch (_: DateTimeParseException) {
        fail("validation_error")
    }
}

private fun JsonObject.calendarTime(key: String): LocalTime {
    val value = string(key)?.takeIf(TIME::matches) ?: fail("validation_error")
    return try {
        LocalTime.parse(value)
    } catch (_: DateTimeParseException) {
        fail("validation_error")
    }
}

private const val LAST_CALENDAR_YEAR = 9998
private const val MAX_CHANGES = 366
private val DATE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
private val TIME = Regex("[0-9]{2}:[0-9]{2}(:[0-9]{2}(\\.[0-9]{1,6})?)?")
