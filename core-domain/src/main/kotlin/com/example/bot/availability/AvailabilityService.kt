package com.example.bot.availability

import com.example.bot.policy.CutoffPolicy
import com.example.bot.time.Club
import com.example.bot.time.ClubException
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
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
    meterRegistry: MeterRegistry? = null,
) {
    private val nightsCache = TimedCache<Long, List<NightDto>>(nightsTtl, clock)
    private val tablesCache = TimedCache<Pair<Long, Instant>, List<TableAvailabilityDto>>(tablesTtl, clock)
    private val metrics = AvailabilityMetrics(meterRegistry)

    /**
     * Returns upcoming nights open for online booking.
     */
    open suspend fun listOpenNights(
        clubId: Long,
        limit: Int = 8,
    ): List<NightDto> {
        val operation = AvailabilityOperation.NIGHTS
        nightsCache.get(clubId)?.let {
            metrics.recordHit(operation, clubId)
            return it.take(limit)
        }
        metrics.recordMiss(operation, clubId)
        return metrics.recordLoad(operation, clubId) {
            val now = Instant.now(clock)
            val slots = rulesResolver.resolve(clubId, now, now.plus(OPEN_LOOKAHEAD))
            val nights =
                slots
                    .filter { cutoffPolicy.isOnlineBookingOpen(it, now) }
                    .map { slot ->
                        val dto = slot.toDto(cutoffPolicy.arrivalBy(slot))
                        val eventId = repository.findEvent(clubId, slot.eventStartUtc)?.id
                        dto.copy(eventId = eventId)
                    }
                    .take(limit)
            nightsCache.put(clubId, nights)
            nights
        }
    }

    /**
     * Lists free tables for event start.
     */
    open suspend fun listFreeTables(
        clubId: Long,
        eventStartUtc: Instant,
    ): List<TableAvailabilityDto> {
        val operation = AvailabilityOperation.TABLES
        val key = clubId to eventStartUtc
        tablesCache.get(key)?.let {
            metrics.recordHit(operation, clubId)
            return it
        }
        metrics.recordMiss(operation, clubId)

        return metrics.recordLoad(operation, clubId) {
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
                        ?: return@recordLoad emptyList()

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
            result
        }
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
private class TimedCache<K, V>(
    private val ttl: Duration,
    private val clock: Clock,
) {
    private data class Entry<V>(
        val value: V,
        val expiresAt: Instant,
    )

    private val store = ConcurrentHashMap<K, Entry<V>>()

    fun get(key: K): V? {
        val now = Instant.now(clock)
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
        store[key] = Entry(value, Instant.now(clock).plus(ttl))
    }

    fun remove(key: K) {
        store.remove(key)
    }
}


fun interface AvailabilityCacheInvalidator {
    fun invalidateTables(
        clubId: Long,
        startUtc: Instant,
    )

    companion object {
        val Noop = AvailabilityCacheInvalidator { _, _ -> }
    }
}

private enum class AvailabilityOperation(
    val tag: String,
) {
    NIGHTS("nights"),
    TABLES("tables"),
}

private class AvailabilityMetrics(
    private val registry: MeterRegistry?,
) {
    fun recordHit(
        operation: AvailabilityOperation,
        clubId: Long,
    ) {
        counter("availability.cache.hit", operation, clubId)?.increment()
    }

    fun recordMiss(
        operation: AvailabilityOperation,
        clubId: Long,
    ) {
        counter("availability.cache.miss", operation, clubId)?.increment()
    }

    suspend fun <T> recordLoad(
        operation: AvailabilityOperation,
        clubId: Long,
        block: suspend () -> T,
    ): T {
        val sample = registry?.let { Timer.start(it) }
        try {
            return block()
        } finally {
            val latencyTimer = timer(operation, clubId)
            if (sample != null && latencyTimer != null) {
                sample.stop(latencyTimer)
            }
        }
    }

    private fun counter(
        name: String,
        operation: AvailabilityOperation,
        clubId: Long,
    ): io.micrometer.core.instrument.Counter? =
        registry?.counter(name, "operation", operation.tag, "club_id", clubId.toString())

    private fun timer(
        operation: AvailabilityOperation,
        clubId: Long,
    ): Timer? =
        registry?.find("availability.load.latency")
            ?.tags("operation", operation.tag, "club_id", clubId.toString())
            ?.timer()
            ?: registry?.let {
                Timer.builder("availability.load.latency")
                    .description("Availability cache miss load latency")
                    .tags("operation", operation.tag, "club_id", clubId.toString())
                    .register(it)
            }
}
