package com.example.bot.booking

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Read-only operations for bookings and holds.
 */
interface BookingReadRepository {
    suspend fun findEvent(
        clubId: Long,
        startUtc: Instant,
    ): EventDto?

    suspend fun findTable(tableId: Long): TableDto?

    suspend fun findActiveHold(holdId: UUID): HoldRecord?

    suspend fun findBookingById(id: UUID): BookingRecord?

    suspend fun findBookingByQr(qrSecret: String): BookingRecord?
}

/**
 * Write operations for bookings and holds.
 */
interface BookingWriteRepository {
    suspend fun insertHold(
        tableId: Long,
        eventId: Long,
        guests: Int,
        expiresAt: Instant,
        idempotencyKey: String,
    ): HoldRecord

    suspend fun deleteHold(id: UUID)

    @Suppress("LongParameterList")
    suspend fun insertBooking(
        tableId: Long,
        eventId: Long,
        tableNumber: Int,
        guests: Int,
        totalDeposit: BigDecimal,
        status: String,
        arrivalBy: Instant?,
        qrSecret: String,
        idempotencyKey: String,
    ): BookingRecord

    suspend fun updateStatus(
        id: UUID,
        status: String,
    )
}

/** Simple event projection for repository. */
data class EventDto(val id: Long, val clubId: Long, val startUtc: Instant, val endUtc: Instant)

/** Simple table projection for repository. */
data class TableDto(val id: Long, val number: Int, val capacity: Int, val minDeposit: BigDecimal, val active: Boolean)

/** Representation of a hold stored in repository. */
data class HoldRecord(val id: UUID, val tableId: Long, val eventId: Long, val guests: Int, val expiresAt: Instant)

/** Representation of a booking stored in repository. */
data class BookingRecord(
    val id: UUID,
    val tableId: Long,
    val tableNumber: Int,
    val eventId: Long,
    val guests: Int,
    val totalDeposit: BigDecimal,
    val status: String,
    val arrivalBy: Instant?,
    val qrSecret: String,
)
