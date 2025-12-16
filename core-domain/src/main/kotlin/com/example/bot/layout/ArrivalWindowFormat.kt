package com.example.bot.layout

import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Formatter for arrival window times in 24-hour HH:mm pattern. */
val ARRIVAL_WINDOW_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** Formats an [ArrivalWindow] as "HH:mm-HH:mm". */
fun ArrivalWindow.toRangeString(): String =
    "${from.format(ARRIVAL_WINDOW_TIME_FORMATTER)}-${to.format(ARRIVAL_WINDOW_TIME_FORMATTER)}"

/**
 * Parses arrival window string formatted as "HH:mm-HH:mm" where start < end.
 * Returns null when the string does not match the expected pattern or is invalid.
 */
fun parseArrivalWindowOrNull(raw: String): ArrivalWindow? {
    val parts = raw.split("-")
    if (parts.size != 2) return null
    val (startRaw, endRaw) = parts
    val start = startRaw.toLocalTimeOrNull() ?: return null
    val end = endRaw.toLocalTimeOrNull() ?: return null
    if (!start.isBefore(end)) return null
    return ArrivalWindow(start, end)
}

private fun String.toLocalTimeOrNull(): LocalTime? =
    runCatching {
        if (!matches(Regex("^[0-9]{2}:[0-9]{2}$"))) return null
        LocalTime.parse(this, ARRIVAL_WINDOW_TIME_FORMATTER)
    }
        .getOrNull()
