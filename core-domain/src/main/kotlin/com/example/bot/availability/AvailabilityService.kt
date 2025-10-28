package com.example.bot.availability

import com.example.bot.policy.CutoffPolicy
import com.example.bot.time.Club
import com.example.bot.time.ClubException
import com.example.bot.time.ClubHoliday
import com.example.bot.time.ClubHour
import com.example.bot.time.Event
import com.example.bot.time.NightSlot
import com.example.bot.time.OperatingRulesResolver
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository abstraction for reading availability related data.
 */
interface AvailabilityRepository {
    suspend fun findClub(clubId: Long): Club?

    suspend fun listClubHours(clubId: Long): List<ClubHour>

    suspend fun listHolidays(
        clubId: Long,
        from: LocalDate,
        to: LocalDate,
    ): List<ClubHoliday>

    suspend fun listExceptions(
        clubId: Long,
        from: LocalDate,
        to: LocalDate,
    ): List<ClubException>

    suspend fun listEvents(
        clubId: Long,
        from: Instant,
        to: Instant,
    ): List<Event>

    suspend fun findEvent(
        clubId: Long,
        startUtc: Instant,
    ): Event?

    suspend fun listTables(clubId: Long): List<Table>

    suspend fun listActiveHoldTableIds(
        eventId: Long,
        now: Instant,
    ): Set<Long>

    suspend fun listActiveBookingTableIds(eventId: Long): Set<Long>
}

/**
 * Service providing availability information.
 */
private val OPEN_LOOKAHEAD: Duration = Duration.ofDays(30)

open class AvailabilityService(
    private val repository: AvailabilityRepository,
    private val rulesResolver: OperatingRulesResolver,
    private val cutoffPolicy: CutoffPolicy,
    private val clock: Clock = Clock.systemUTC(),
    nightsTtl: Duration = Duration.ofSeconds(60),
    tablesTtl: Duration = Duration.ofSeconds(60),
) {
    private val nightsCache = TimedCache<Long, List<NightDto>>(nightsTtl)
    private val tablesCache = TimedCache<Pair<Long, Instant>, List<TableAvailabilityDto>>(tablesTtl)

    /**
     * Returns upcoming nights open for online booking.
     */
    open suspend fun listOpenNights(
        clubId: Long,
        limit: Int = 8,
    ): List<NightDto> {
        nightsCache.get(clubId)?.let { return it.take(limit) }
        val now = Instant.now(clock)
        val slots = rulesResolver.resolve(clubId, now, now.plus(OPEN_LOOKAHEAD))
        val nights =
            slots
                .filter { cutoffPolicy.isOnlineBookingOpen(it, now) }
                .map { slot -> slot.toDto(cutoffPolicy.arrivalBy(slot)) }
                .take(limit)
        nightsCache.put(clubId, nights)
        return nights
    }

    /**
     * Lists free tables for event start.
     */
    open suspend fun listFreeTables(
        clubId: Long,
        eventStartUtc: Instant,
    ): List<TableAvailabilityDto> {
        val key = clubId to eventStartUtc
        tablesCache.get(key)?.let { return it }

        val result =
            repository.findClub(clubId)?.let { club ->
                val zone = java.time.ZoneId.of(club.timezone)
                val event = repository.findEvent(clubId, eventStartUtc)
                event?.let {
                    NightSlot(
                        clubId = clubId,
                        eventStartUtc = it.startUtc,
                        eventEndUtc = it.endUtc,
                        isSpecial = it.isSpecial,
                        source = com.example.bot.time.NightSource.EVENT_MATERIALIZED,
                        openLocal = it.startUtc.atZone(zone).toLocalDateTime(),
                        closeLocal = it.endUtc.atZone(zone).toLocalDateTime(),
                        zone = zone,
                    )
                }
                    ?: rulesResolver
                        .resolve(
                            clubId,
                            eventStartUtc.minus(Duration.ofDays(1)),
                            eventStartUtc.plus(Duration.ofDays(1)),
                        ).find { eventStartUtc == it.eventStartUtc }
                    ?: return@let emptyList()

                val eventId = event?.id
                val tables = repository.listTables(clubId).filter { it.active }
                val holds = eventId?.let { repository.listActiveHoldTableIds(it, Instant.now(clock)) } ?: emptySet()
                val bookings = eventId?.let { repository.listActiveBookingTableIds(it) } ?: emptySet()

                tables
                    .filter { t -> t.id !in holds && t.id !in bookings }
                    .map { t ->
                        TableAvailabilityDto(
                            tableId = t.id,
                            tableNumber = t.number,
                            zone = t.zone,
                            capacity = t.capacity,
                            minDeposit = t.minDeposit,
                            status = TableStatus.FREE,
                        )
                    }
            } ?: emptyList()

        tablesCache.put(key, result)
        return result
    }

    /**
     * Counts free tables for quick badge display.
     */
    suspend fun countFreeTables(
        clubId: Long,
        eventStartUtc: Instant,
    ): Int = listFreeTables(clubId, eventStartUtc).size

    /** cache invalidation helpers */
    fun invalidateNights(clubId: Long) {
        nightsCache.remove(clubId)
    }

    fun invalidateTables(
        clubId: Long,
        startUtc: Instant,
    ) {
        tablesCache.remove(clubId to startUtc)
    }
}

/** Simple timed cache with TTL. */
private class TimedCache<K, V>(private val ttl: Duration) {
    private data class Entry<V>(val value: V, val expiresAt: Instant)

    private val store = ConcurrentHashMap<K, Entry<V>>()

    fun get(key: K): V? {
        val now = Instant.now()
        val entry = store[key]
        return if (entry != null && entry.expiresAt.isAfter(now)) {
            entry.value
        } else {
            if (entry != null) store.remove(key)
            null
        }
    }

    fun put(
        key: K,
        value: V,
    ) {
        store[key] = Entry(value, Instant.now().plus(ttl))
    }

    fun remove(key: K) {
        store.remove(key)
    }
}
