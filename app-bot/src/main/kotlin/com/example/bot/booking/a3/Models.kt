package com.example.bot.booking.a3

import java.time.Instant

enum class BookingStatus { HOLD, BOOKED, CANCELED }

data class Booking(
    val id: Long,
    val userId: Long,
    val clubId: Long,
    val tableId: Long,
    val eventId: Long,
    var status: BookingStatus,
    var guestCount: Int,
    val arrivalWindow: Pair<Instant, Instant>,
    val latePlusOneAllowedUntil: Instant?,
    var plusOneUsed: Boolean,
    val capacityAtHold: Int?,
    val createdAt: Instant,
    var updatedAt: Instant,
    val holdExpiresAt: Instant?,
)

data class BookingRequestKey(
    val userId: Long,
    val route: String,
    val idempotencyKey: String,
)

data class StoredIdempotentResponse(
    val requestHash: String,
    val status: Int,
    val bodyJson: String,
    val snapshot: BookingResponseSnapshot,
    val createdAt: Instant,
)

@kotlinx.serialization.Serializable
data class BookingResponseSnapshot(
    val booking: BookingView,
    val latePlusOneAllowedUntil: String?,
    val arrivalWindow: List<String>,
    @kotlinx.serialization.Transient val userId: Long = 0,
)

@kotlinx.serialization.Serializable
data class BookingView(
    val id: Long,
    val clubId: Long,
    val tableId: Long,
    val eventId: Long,
    val status: String,
    val guestCount: Int,
    val arrivalWindow: List<String>,
    val latePlusOneAllowedUntil: String?,
    val plusOneUsed: Boolean,
    val capacityAtHold: Int? = null,
    val createdAt: String,
    val updatedAt: String,
)

@kotlinx.serialization.Serializable
data class PlusOneCanonicalPayload(
    val bookingId: Long,
    val op: String = "plus-one",
)

sealed interface HoldResult {
    data class Success(
        val booking: Booking,
        val snapshot: BookingResponseSnapshot,
        val bodyJson: String,
        val cached: Boolean,
    ) : HoldResult
    data class Error(val code: BookingError) : HoldResult
}

sealed interface ConfirmResult {
    data class Success(
        val booking: Booking,
        val snapshot: BookingResponseSnapshot,
        val bodyJson: String,
        val cached: Boolean,
    ) : ConfirmResult
    data class Error(val code: BookingError) : ConfirmResult
}

sealed interface PlusOneResult {
    data class Success(
        val booking: Booking,
        val snapshot: BookingResponseSnapshot,
        val bodyJson: String,
        val cached: Boolean,
    ) : PlusOneResult
    data class Error(val code: BookingError) : PlusOneResult
}

enum class BookingError {
    TABLE_NOT_AVAILABLE,
    VALIDATION_ERROR,
    IDEMPOTENCY_CONFLICT,
    NOT_FOUND,
    HOLD_EXPIRED,
    INVALID_STATE,
    LATE_PLUS_ONE_EXPIRED,
    PLUS_ONE_ALREADY_USED,
    FORBIDDEN,
    CAPACITY_EXCEEDED,
    CLUB_SCOPE_MISMATCH,
}
