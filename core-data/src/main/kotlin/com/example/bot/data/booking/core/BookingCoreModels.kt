package com.example.bot.data.booking.core

import com.example.bot.data.booking.BookingStatus
import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

sealed interface BookingCoreError {
    data object DuplicateActiveBooking : BookingCoreError

    data object HoldExpired : BookingCoreError

    data object IdempotencyConflict : BookingCoreError

    data object OptimisticRetryExceeded : BookingCoreError

    data object HoldNotFound : BookingCoreError

    data object BookingNotFound : BookingCoreError

    data object ActiveHoldExists : BookingCoreError

    data object OutboxRecordNotFound : BookingCoreError

    data object UnexpectedFailure : BookingCoreError

    data object EventNotFound : BookingCoreError

    data object TableNotFound : BookingCoreError
}

sealed interface BookingCoreResult<out T> {
    data class Success<T>(val value: T) : BookingCoreResult<T>

    data class Failure(val error: BookingCoreError) : BookingCoreResult<Nothing>
}

data class BookingRecord(
    val id: UUID,
    val clubId: Long,
    val tableId: Long,
    val tableNumber: Int,
    val eventId: Long,
    val guests: Int,
    val minRate: BigDecimal,
    val totalRate: BigDecimal,
    val slotStart: Instant,
    val slotEnd: Instant,
    val status: BookingStatus,
    val qrSecret: String,
    val idempotencyKey: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class BookingHold(
    val id: UUID,
    val clubId: Long,
    val tableId: Long,
    val eventId: Long,
    val slotStart: Instant,
    val slotEnd: Instant,
    val expiresAt: Instant,
    val guests: Int,
    val minDeposit: BigDecimal,
    val idempotencyKey: String,
)

data class OutboxMessage(
    val id: Long,
    val topic: String,
    val payload: JsonObject,
    val status: OutboxMessageStatus,
    val attempts: Int,
    val nextAttemptAt: Instant,
    val lastError: String?,
)

enum class OutboxMessageStatus {
    NEW,
    SENT,
    FAILED,
}
