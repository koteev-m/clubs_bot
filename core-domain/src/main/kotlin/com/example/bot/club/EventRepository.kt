package com.example.bot.club

import java.time.Instant
import kotlin.ranges.ClosedRange

/**
 * Repository exposing club events for read models.
 */
interface EventRepository {
    /**
     * Lists events for the given [clubId] that start within [dateRange].
     */
    suspend fun listByClub(
        clubId: Long,
        dateRange: ClosedRange<Instant>,
    ): List<Event>

    /**
     * Loads an event by its identifier.
     */
    suspend fun get(id: Long): Event?
}

/** Simple event projection used by the data layer. */
data class Event(
    val id: Long,
    val clubId: Long,
    val title: String?,
    val startAt: Instant,
    val endAt: Instant,
    val isSpecial: Boolean,
    val posterUrl: String?,
)
