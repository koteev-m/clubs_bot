package com.example.bot.dedup

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory deduplicator for Telegram update identifiers.
 *
 * The Bot API stores updates for at most 24 hours, therefore this cache keeps
 * identifiers for the same period. Entries older than [ttl] are discarded on
 * each access.
 */
private const val HOURS_IN_DAY = 24L

class UpdateDeduplicator(private val ttl: Duration = Duration.ofHours(HOURS_IN_DAY)) {
    private val seen: MutableMap<Long, Instant> = ConcurrentHashMap()

    /**
     * Returns `true` if the [updateId] has been seen recently.
     */
    fun isDuplicate(updateId: Long): Boolean {
        val now = Instant.now()
        cleanup(now)
        val previous = seen.putIfAbsent(updateId, now)
        return previous != null
    }

    private fun cleanup(now: Instant) {
        val expireBefore = now.minus(ttl)
        seen.entries.removeIf { it.value.isBefore(expireBefore) }
    }
}
