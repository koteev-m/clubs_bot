package com.example.bot.booking.a3

import com.example.bot.clubs.EventsRepository
import com.example.bot.layout.LayoutRepository
import com.example.bot.layout.TableStatus
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory

class BookingState(
    private val layoutRepository: LayoutRepository,
    private val eventsRepository: EventsRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val holdTtl: Duration = Duration.ofMinutes(10),
    private val latePlusOneOffset: Duration = Duration.ofMinutes(30),
    private val arrivalWindowBefore: Duration = Duration.ofMinutes(15),
    private val arrivalWindowAfter: Duration = Duration.ofMinutes(45),
    private val idempotencyTtl: Duration = Duration.ofMinutes(15),
    private val bookingRetention: Duration = Duration.ofHours(48),
    private val watermarkRetention: Duration = Duration.ofDays(7),
    private val maxIdempotencyEntries: Int = 10_000,
    meterRegistry: MeterRegistry? = null,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(BookingState::class.java)
    private val sequence = AtomicLong(1)
    private val bookings = ConcurrentHashMap<Long, Booking>()
    private val tableLocks = ConcurrentHashMap<Pair<Long, Long>, TableState>()
    private val idempotency = ConcurrentHashMap<BookingRequestKey, StoredIdempotentResponse>()
    private val lastUpdated = ConcurrentHashMap<Pair<Long, Long>, Instant>()
    private val eventWatermark = ConcurrentHashMap<Long, java.util.concurrent.atomic.AtomicReference<Instant>>()
    private val cleanupScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        cleanupScope.launch {
            while (isActive) {
                delay(idempotencyTtl.toMillis())
                cleanupExpired(Instant.now(clock))
            }
        }
        meterRegistry?.gauge("booking.state.bookings.size", bookings) { it.size.toDouble() }
        meterRegistry?.gauge("booking.state.table_locks.size", tableLocks) { it.size.toDouble() }
        meterRegistry?.gauge("booking.state.idempotency.size", idempotency) { it.size.toDouble() }
        meterRegistry?.gauge("booking.state.event_watermarks.size", eventWatermark) { it.size.toDouble() }
    }

    override fun close() {
        cleanupScope.cancel()
    }

    suspend fun hold(
        userId: Long,
        clubId: Long,
        tableId: Long,
        eventId: Long,
        guestCount: Int,
        idempotencyKey: String,
        requestHash: String,
    ): HoldResult {
        val layout = layoutRepository.getLayout(clubId, eventId) ?: return HoldResult.Error(BookingError.NOT_FOUND)
        val table = layout.tables.find { it.id == tableId } ?: return HoldResult.Error(BookingError.NOT_FOUND)
        if (guestCount < 1) return HoldResult.Error(BookingError.VALIDATION_ERROR)
        if (guestCount > table.capacity) return HoldResult.Error(BookingError.CAPACITY_EXCEEDED)

        val now = Instant.now(clock)
        cleanupExpired(now)

        val idemKey = BookingRequestKey(userId, "POST:/api/clubs/$clubId/bookings/hold", idempotencyKey)
        idempotency[idemKey]?.let { stored ->
            if (stored.isExpired(now, idempotencyTtl)) {
                idempotency.remove(idemKey)
            } else if (stored.requestHash == requestHash) {
                return HoldResult.Success(stored.snapshot.toBooking(), stored.snapshot, stored.bodyJson, cached = true)
            } else {
                return HoldResult.Error(BookingError.IDEMPOTENCY_CONFLICT)
            }
        }

        val event = eventsRepository.findById(clubId, eventId) ?: return HoldResult.Error(BookingError.NOT_FOUND)

        val start = event.startUtc
        val arrivalStart = start.minus(arrivalWindowBefore)
        val arrivalEnd = start.plus(arrivalWindowAfter)
        val latePlusOneUntil = start.plus(latePlusOneOffset)

        val bookingId = sequence.getAndIncrement()
        val expiresAt = now.plus(holdTtl)
        val key = eventId to tableId
        val tookHold: Boolean =
            tableLocks.compute(key) { _, prev ->
                val cur = prev?.evictIfExpired(now) ?: TableState.free()
                when (cur.status) {
                    TableOccupancy.FREE -> TableState(TableOccupancy.HOLD, bookingId, expiresAt)
                    else -> cur
                }
            }?.bookingId == bookingId

        if (!tookHold) return HoldResult.Error(BookingError.TABLE_NOT_AVAILABLE)

        val booking = Booking(
            id = bookingId,
            userId = userId,
            clubId = clubId,
            tableId = tableId,
            eventId = eventId,
            status = BookingStatus.HOLD,
            guestCount = guestCount,
            arrivalWindow = arrivalStart to arrivalEnd,
            latePlusOneAllowedUntil = latePlusOneUntil,
            plusOneUsed = false,
            capacityAtHold = table.capacity,
            createdAt = now,
            updatedAt = now,
            holdExpiresAt = expiresAt,
        )

        bookings[bookingId] = booking
        lastUpdated[key] = now
        bumpWatermark(eventId, now)
        val body = booking.toSnapshot()
        val bodyJson = CanonJson.encodeToString(body)
        idempotency[idemKey] = StoredIdempotentResponse(requestHash, 200, bodyJson, body, now)

        return HoldResult.Success(booking, body, bodyJson, cached = false)
    }

    suspend fun confirm(
        userId: Long,
        clubId: Long,
        bookingId: Long,
        idempotencyKey: String,
        requestHash: String,
    ): ConfirmResult {
        val now = Instant.now(clock)
        cleanupExpired(now)

        val routeKey = "POST:/api/clubs/$clubId/bookings/confirm/$bookingId"
        val idemKey = BookingRequestKey(userId, routeKey, idempotencyKey)
        idempotency[idemKey]?.let { stored ->
            if (stored.isExpired(now, idempotencyTtl)) {
                idempotency.remove(idemKey)
            } else if (stored.requestHash == requestHash) {
                return ConfirmResult.Success(stored.snapshot.toBooking(), stored.snapshot, stored.bodyJson, cached = true)
            } else {
                return ConfirmResult.Error(BookingError.IDEMPOTENCY_CONFLICT)
            }
        }

        val booking = bookings[bookingId] ?: return ConfirmResult.Error(BookingError.NOT_FOUND)
        if (booking.userId != userId) return ConfirmResult.Error(BookingError.FORBIDDEN)
        if (booking.clubId != clubId) return ConfirmResult.Error(BookingError.CLUB_SCOPE_MISMATCH)
        if (booking.status != BookingStatus.HOLD) return ConfirmResult.Error(BookingError.INVALID_STATE)
        val key = booking.eventId to booking.tableId
        val state = tableLocks[key]?.evictIfExpired(now)
        if (state == null || state.status != TableOccupancy.HOLD || state.bookingId != bookingId) {
            tableLocks[key] = TableState.free()
            return ConfirmResult.Error(BookingError.HOLD_EXPIRED)
        }
        if (booking.holdExpiresAt != null && booking.holdExpiresAt.isBefore(now)) {
            expireBooking(booking, now)
            tableLocks[key] = TableState.free()
            return ConfirmResult.Error(BookingError.HOLD_EXPIRED)
        }

        booking.status = BookingStatus.BOOKED
        booking.updatedAt = now
        tableLocks[key] = TableState(TableOccupancy.BOOKED, booking.id, null)
        lastUpdated[key] = now
        bumpWatermark(booking.eventId, now)
        val body = booking.toSnapshot()
        val bodyJson = CanonJson.encodeToString(body)
        idempotency[idemKey] = StoredIdempotentResponse(requestHash, 200, bodyJson, body, now)
        return ConfirmResult.Success(booking, body, bodyJson, cached = false)
    }

    suspend fun plusOne(
        userId: Long,
        bookingId: Long,
        idempotencyKey: String,
        requestHash: String,
    ): PlusOneResult {
        val now = Instant.now(clock)
        cleanupExpired(now)

        val routeKey = "POST:/api/bookings/$bookingId/plus-one"
        val idemKey = BookingRequestKey(userId, routeKey, idempotencyKey)
        idempotency[idemKey]?.let { stored ->
            if (stored.isExpired(now, idempotencyTtl)) {
                idempotency.remove(idemKey)
            } else if (stored.requestHash == requestHash) {
                return PlusOneResult.Success(stored.snapshot.toBooking(), stored.snapshot, stored.bodyJson, cached = true)
            } else {
                return PlusOneResult.Error(BookingError.IDEMPOTENCY_CONFLICT)
            }
        }

        val existing = bookings[bookingId] ?: return PlusOneResult.Error(BookingError.NOT_FOUND)
        if (existing.userId != userId) return PlusOneResult.Error(BookingError.FORBIDDEN)

        val layout = layoutRepository.getLayout(existing.clubId, existing.eventId)
        val fallbackCapacity =
            layout?.tables?.firstOrNull { it.id == existing.tableId }?.capacity
                ?: existing.capacityAtHold
        val capacity = existing.capacityAtHold ?: fallbackCapacity
        if (capacity == null) return PlusOneResult.Error(BookingError.NOT_FOUND)

        var applied = false
        val booking = bookings.compute(bookingId) { _, current ->
            val cur = current ?: return@compute null
            if (cur.userId != userId) return@compute cur
            val deadline = cur.latePlusOneAllowedUntil
            if (cur.status != BookingStatus.BOOKED || cur.plusOneUsed || (deadline != null && now.isAfter(deadline))) {
                return@compute cur
            }
            if (cur.guestCount + 1 > capacity) return@compute cur

            cur.plusOneUsed = true
            cur.guestCount += 1
            cur.updatedAt = now
            applied = true
            cur
        } ?: return PlusOneResult.Error(BookingError.NOT_FOUND)

        if (booking.userId != userId) return PlusOneResult.Error(BookingError.FORBIDDEN)
        if (!applied) {
            return when {
                booking.status != BookingStatus.BOOKED -> PlusOneResult.Error(BookingError.INVALID_STATE)
                booking.plusOneUsed -> PlusOneResult.Error(BookingError.PLUS_ONE_ALREADY_USED)
                booking.latePlusOneAllowedUntil?.let(now::isAfter) == true -> PlusOneResult.Error(BookingError.LATE_PLUS_ONE_EXPIRED)
                booking.guestCount + 1 > capacity -> PlusOneResult.Error(BookingError.CAPACITY_EXCEEDED)
                else -> PlusOneResult.Error(BookingError.INVALID_STATE)
            }
        }

        val body = booking.toSnapshot()
        val bodyJson = CanonJson.encodeToString(body)
        idempotency[idemKey] = StoredIdempotentResponse(requestHash, 200, bodyJson, body, now)
        return PlusOneResult.Success(booking, body, bodyJson, cached = false)
    }

    fun findBookingById(bookingId: Long): Booking? {
        val now = Instant.now(clock)
        cleanupExpired(now)
        return bookings[bookingId]?.copy()
    }

    fun findUserBookings(userId: Long): List<Booking> {
        val now = Instant.now(clock)
        cleanupExpired(now)
        return bookings.values.filter { it.userId == userId }.map { it.copy() }
    }

    fun now(): Instant = Instant.now(clock)

    fun snapshotOf(booking: Booking): BookingResponseSnapshot = booking.toSnapshot()

    fun tableStatus(eventId: Long, tableId: Long): TableStatus {
        val now = Instant.now(clock)
        val key = eventId to tableId
        var expired = false
        val state =
            tableLocks.compute(key) { _, s ->
                val next = s?.evictIfExpired(now)
                if (s != null && next == null) {
                    expired = true
                    TableState.free()
                } else {
                    next ?: TableState.free()
                }
            }

        if (expired) {
            lastUpdated[key] = now
            bumpWatermark(eventId, now)
        }

        return when (state?.status) {
            TableOccupancy.HOLD -> TableStatus.HOLD
            TableOccupancy.BOOKED -> TableStatus.BOOKED
            else -> TableStatus.FREE
        }
    }

    fun lastUpdatedAt(eventId: Long, tableId: Long): Instant? =
        lastUpdated[eventId to tableId]

    fun lastUpdatedAt(eventId: Long): Instant? =
        eventWatermark[eventId]?.get()

    fun bookingClubId(bookingId: Long): Long? = bookings[bookingId]?.clubId

    private fun cleanupExpired(now: Instant) {
        var removedLocks = 0
        val iterator = tableLocks.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val evicted = entry.value.evictIfExpired(now)
            if (evicted == null || evicted.status == TableOccupancy.FREE) {
                iterator.remove()
                bumpWatermark(entry.key.first, now)
                removedLocks += 1
            }
        }

        val idempotencyBefore = idempotency.size
        val expiredKeys = mutableListOf<BookingRequestKey>()
        idempotency.forEach { (key, stored) ->
            if (stored.isExpired(now, idempotencyTtl)) {
                expiredKeys += key
            }
        }
        expiredKeys.forEach { idempotency.remove(it) }
        pruneIdempotency(now)
        val removedIdempotency = (idempotencyBefore - idempotency.size).coerceAtLeast(0)

        val bookingCutoff = now.minus(bookingRetention)
        var removedBookings = 0
        val bookingIterator = bookings.entries.iterator()
        while (bookingIterator.hasNext()) {
            val entry = bookingIterator.next()
            val booking = entry.value
            val baseTime = maxOf(booking.updatedAt, booking.arrivalWindow.second)
            val expired = booking.status != BookingStatus.HOLD && baseTime.isBefore(bookingCutoff)
            if (expired) {
                bookingIterator.remove()
                removedBookings += 1
                val key = booking.eventId to booking.tableId
                val state = tableLocks[key]
                if (state != null && (state.bookingId == booking.id || state.status != TableOccupancy.HOLD)) {
                    tableLocks.remove(key)
                    bumpWatermark(booking.eventId, now)
                }
            }
        }

        val watermarkCutoff = now.minus(watermarkRetention)
        var removedWatermarks = 0
        val watermarkIterator = eventWatermark.entries.iterator()
        while (watermarkIterator.hasNext()) {
            val entry = watermarkIterator.next()
            if (entry.value.get().isBefore(watermarkCutoff)) {
                watermarkIterator.remove()
                removedWatermarks += 1
            }
        }

        if (removedLocks > 0 || removedIdempotency > 0 || removedBookings > 0 || removedWatermarks > 0) {
            logger.debug(
                "booking.cleanup removed_locks={} removed_idempotency={} removed_bookings={} removed_watermarks={}",
                removedLocks,
                removedIdempotency,
                removedBookings,
                removedWatermarks,
            )
        }
    }

    private fun expireBooking(
        booking: Booking,
        now: Instant,
    ) {
        booking.status = BookingStatus.CANCELED
        booking.updatedAt = now
    }

    private fun bumpWatermark(eventId: Long, now: Instant) {
        eventWatermark
            .computeIfAbsent(eventId) { java.util.concurrent.atomic.AtomicReference(Instant.EPOCH) }
            .updateAndGet { prev -> if (now.isAfter(prev)) now else prev }
    }

    private fun pruneIdempotency(@Suppress("UNUSED_PARAMETER") now: Instant) {
        if (idempotency.size <= maxIdempotencyEntries) return
        val overflow = idempotency.size - maxIdempotencyEntries
        idempotency.entries
            .sortedBy { it.value.createdAt }
            .take(overflow)
            .forEach { idempotency.remove(it.key) }
    }
}

private data class TableState(
    val status: TableOccupancy,
    val bookingId: Long?,
    val expiresAt: Instant?,
) {
    fun evictIfExpired(now: Instant): TableState? {
        return if (expiresAt != null && expiresAt.isBefore(now)) {
            null
        } else {
            this
        }
    }

    companion object {
        fun free(): TableState = TableState(TableOccupancy.FREE, null, null)
    }
}

enum class TableOccupancy { FREE, HOLD, BOOKED }

private fun StoredIdempotentResponse.isExpired(
    now: Instant,
    ttl: Duration,
): Boolean = createdAt.plus(ttl).isBefore(now)

inline fun <reified T> hashRequestCanonical(payload: T): String {
    val json = CanonJson.encodeToString(payload)
    val digest = MessageDigest.getInstance("SHA-256").digest(json.toByteArray())
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

@PublishedApi
internal val CanonJson = Json { encodeDefaults = true; explicitNulls = false }

private fun Booking.toSnapshot(): BookingResponseSnapshot {
    val arrival = listOf(arrivalWindow.first.toString(), arrivalWindow.second.toString())
    val view =
        BookingView(
            id = id,
            clubId = clubId,
            tableId = tableId,
            eventId = eventId,
            status = status.name,
            guestCount = guestCount,
            arrivalWindow = arrival,
            latePlusOneAllowedUntil = latePlusOneAllowedUntil?.toString(),
            plusOneUsed = plusOneUsed,
            capacityAtHold = capacityAtHold,
            createdAt = createdAt.toString(),
            updatedAt = updatedAt.toString(),
        )
    return BookingResponseSnapshot(view, latePlusOneAllowedUntil?.toString(), arrival, userId)
}

private fun BookingResponseSnapshot.toBooking(): Booking {
    val start = Instant.parse(arrivalWindow.first())
    val end = Instant.parse(arrivalWindow.last())
    return Booking(
        id = booking.id,
        userId = userId,
        clubId = booking.clubId,
        tableId = booking.tableId,
        eventId = booking.eventId,
        status = BookingStatus.valueOf(booking.status),
        guestCount = booking.guestCount,
        arrivalWindow = start to end,
        latePlusOneAllowedUntil = booking.latePlusOneAllowedUntil?.let(Instant::parse),
        plusOneUsed = booking.plusOneUsed,
        capacityAtHold = booking.capacityAtHold,
        createdAt = Instant.parse(booking.createdAt),
        updatedAt = Instant.parse(booking.updatedAt),
        holdExpiresAt = null,
    )
}
