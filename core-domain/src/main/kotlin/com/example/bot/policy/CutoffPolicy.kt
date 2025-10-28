package com.example.bot.policy

import com.example.bot.time.NightSlot
import java.time.Duration
import java.time.Instant

/**
 * Booking cutoff and arrival policies.
 */
class CutoffPolicy(private val onlineCutoffMinutes: Long = 60, private val defaultArrivalByMinutes: Long = 120) {
    /**
     * Returns true when online booking is still allowed for the slot.
     */
    fun isOnlineBookingOpen(
        slot: NightSlot,
        now: Instant,
    ): Boolean {
        val cutoff = now.plus(Duration.ofMinutes(onlineCutoffMinutes))
        return cutoff.isBefore(slot.eventStartUtc)
    }

    /**
     * Calculates latest arrival time for the slot.
     */
    fun arrivalBy(slot: NightSlot): Instant {
        val proposed = slot.eventStartUtc.plus(Duration.ofMinutes(defaultArrivalByMinutes))
        return if (proposed.isAfter(slot.eventEndUtc)) slot.eventEndUtc else proposed
    }
}
