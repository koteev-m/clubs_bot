package com.example.bot.policy

import com.example.bot.time.CalendarNight
import java.time.ZonedDateTime

/** Owner configuration and calendar preview, independent of the runtime booking policy. */
data class CabinetBookingRules(
    val cutoffMinutes: Int = DEFAULT_CUTOFF,
    val arrivalMinutes: Int? = null,
    val holdMinutes: Int? = null,
    val retentionMinutes: Int = DEFAULT_RETENTION,
) {
    init {
        require(cutoffMinutes in OFFSET_RANGE)
        require(arrivalMinutes == null || arrivalMinutes in OFFSET_RANGE)
        require(holdMinutes == null || holdMinutes in HOLD_RANGE)
        require(retentionMinutes in OFFSET_RANGE)
    }

    fun preview(night: CalendarNight): BookingRulesNightPreview {
        // ZonedDateTime time-based units advance elapsed minutes through DST transitions.
        val cutoffAt = night.closesAt.minusMinutes(cutoffMinutes.toLong())
        val arrivalBy = arrivalMinutes?.let { night.opensAt.plusMinutes(it.toLong()) }
        return BookingRulesNightPreview(
            night = night,
            cutoffAt = cutoffAt,
            arrivalBy = arrivalBy,
            cutoffAtOrBeforeOpen = !cutoffAt.isAfter(night.opensAt),
            arrivalAtOrAfterClose = arrivalBy?.let { !it.isBefore(night.closesAt) } ?: false,
            cutoffAfterArrival = arrivalBy?.let { cutoffAt.isAfter(it) } ?: false,
        )
    }

    companion object {
        val OFFSET_RANGE = 0..1440
        val HOLD_RANGE = 1..1440
        private const val DEFAULT_CUTOFF = 120
        private const val DEFAULT_RETENTION = 30
    }
}

data class BookingRulesNightPreview(
    val night: CalendarNight,
    val cutoffAt: ZonedDateTime,
    val arrivalBy: ZonedDateTime?,
    val cutoffAtOrBeforeOpen: Boolean,
    val arrivalAtOrAfterClose: Boolean,
    val cutoffAfterArrival: Boolean,
)
