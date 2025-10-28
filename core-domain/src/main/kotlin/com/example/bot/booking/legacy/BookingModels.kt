package com.example.bot.booking.legacy

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Request to place a hold on a table for a particular event.
 */
data class HoldRequest(val clubId: Long, val eventStartUtc: Instant, val tableId: Long, val guestsCount: Int)

/**
 * Response for a successful hold operation.
 */
data class HoldResponse(
    val holdId: UUID,
    val expiresAt: Instant,
    val tableId: Long,
    val eventId: Long,
    val totalDeposit: BigDecimal,
)

/**
 * Request to confirm a booking.
 *
 * The caller may provide an existing hold identifier or let the service
 * attempt confirmation directly for the specified table and event.
 */
data class ConfirmRequest(
    val holdId: UUID? = null,
    val clubId: Long,
    val eventStartUtc: Instant,
    val tableId: Long,
    val guestsCount: Int,
    val guestUserId: Long?,
    val guestName: String?,
    val phoneE164: String?,
)

/**
 * Summary of a booking returned by service operations.
 */
data class BookingSummary(
    val id: UUID,
    val clubId: Long,
    val eventId: Long,
    val tableId: Long,
    val tableNumber: Int,
    val guestsCount: Int,
    val totalDeposit: BigDecimal,
    val status: String,
    val arrivalBy: Instant?,
    val qrSecret: String,
)

/**
 * Error returned by booking operations.
 */
sealed interface BookingError {
    data class Conflict(val message: String) : BookingError

    data class Validation(val message: String) : BookingError

    data class NotFound(val message: String) : BookingError

    data class Forbidden(val message: String) : BookingError

    data class Gone(val message: String) : BookingError

    data class Internal(val message: String, val cause: Throwable? = null) : BookingError
}
