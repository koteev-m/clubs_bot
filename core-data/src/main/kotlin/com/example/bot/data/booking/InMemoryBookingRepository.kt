package com.example.bot.data.booking

import com.example.bot.booking.BookingReadRepository
import com.example.bot.booking.BookingRecord
import com.example.bot.booking.BookingWriteRepository
import com.example.bot.booking.EventDto
import com.example.bot.booking.HoldRecord
import com.example.bot.booking.TableDto
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Simple in-memory repositories used for tests. This is a lightweight
 * substitute for real database implementations.
 */
class InMemoryBookingRepository :
    BookingReadRepository,
    BookingWriteRepository {
    private val events = ConcurrentHashMap<Pair<Long, Instant>, EventDto>()
    private val tables = ConcurrentHashMap<Long, TableDto>()
    private val holds = ConcurrentHashMap<UUID, HoldRecord>()
    private val holdsByKey = ConcurrentHashMap<String, HoldRecord>()
    private val bookings = ConcurrentHashMap<UUID, BookingRecord>()
    private val bookingsByKey = ConcurrentHashMap<String, BookingRecord>()

    fun seed(
        event: EventDto,
        table: TableDto,
    ) {
        events[event.clubId to event.startUtc] = event
        tables[table.id] = table
    }

    override suspend fun findEvent(
        clubId: Long,
        startUtc: Instant,
    ): EventDto? = events[clubId to startUtc]

    override suspend fun findTable(tableId: Long): TableDto? = tables[tableId]

    override suspend fun findActiveHold(holdId: UUID): HoldRecord? = holds[holdId]

    override suspend fun findBookingById(id: UUID): BookingRecord? = bookings[id]

    override suspend fun findBookingByQr(qrSecret: String): BookingRecord? {
        return bookings.values.find {
            it.qrSecret == qrSecret
        }
    }

    override suspend fun insertHold(
        tableId: Long,
        eventId: Long,
        guests: Int,
        expiresAt: Instant,
        idempotencyKey: String,
    ): HoldRecord {
        holdsByKey[idempotencyKey]?.let { return it }
        check(
            holds.values.none {
                it.tableId == tableId && it.eventId == eventId && it.expiresAt.isAfter(Instant.now())
            },
        ) {
            "active hold exists"
        }
        val record = HoldRecord(UUID.randomUUID(), tableId, eventId, guests, expiresAt)
        holds[record.id] = record
        holdsByKey[idempotencyKey] = record
        return record
    }

    override suspend fun deleteHold(id: UUID) {
        val record = holds.remove(id)
        if (record != null) {
            holdsByKey.entries.removeIf { it.value.id == id }
        }
    }

    override suspend fun insertBooking(
        tableId: Long,
        eventId: Long,
        tableNumber: Int,
        guests: Int,
        totalDeposit: BigDecimal,
        status: String,
        arrivalBy: Instant?,
        qrSecret: String,
        idempotencyKey: String,
    ): BookingRecord {
        return synchronized(bookings) {
            bookingsByKey[idempotencyKey]?.let { return@synchronized it }
            check(
                bookings.values.none {
                    it.tableId == tableId &&
                        it.eventId == eventId &&
                        it.status in setOf("BOOKED", "SEATED")
                },
            ) {
                "active booking exists"
            }
            val record =
                BookingRecord(
                    UUID.randomUUID(),
                    tableId,
                    tableNumber,
                    eventId,
                    guests,
                    totalDeposit,
                    status,
                    arrivalBy,
                    qrSecret,
                )
            bookings[record.id] = record
            bookingsByKey[idempotencyKey] = record
            return@synchronized record
        }
    }

    override suspend fun updateStatus(
        id: UUID,
        status: String,
    ) {
        bookings.computeIfPresent(id) { _, rec -> rec.copy(status = status) }
    }
}
