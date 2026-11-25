package com.example.bot.policy

import com.example.bot.time.NightSlot
import java.time.Duration
import java.time.Instant

/**
 * Online booking cutoff.
 */
class CutoffPolicy(
    private val onlineCutoffMinutes: Long = 60,
) {
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

    /** The legacy availability DTO exposes the closing instant as its arrival boundary. */
    fun arrivalBy(slot: NightSlot): Instant = slot.eventEndUtc
}
