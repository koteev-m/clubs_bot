package com.example.bot.payments

import java.time.Instant
import java.util.UUID

/**
 * Persistence interface for payment records.
 */
interface PaymentsRepository {
    /** Representation of a payment row. */
    data class PaymentRecord(
        val id: UUID,
        val bookingId: UUID?,
        val provider: String,
        val currency: String,
        val amountMinor: Long,
        val status: String,
        val payload: String,
        val externalId: String?,
        val telegramPaymentChargeId: String?,
        val providerPaymentChargeId: String?,
        val idempotencyKey: String,
        val createdAt: Instant,
        val updatedAt: Instant,
    )

    enum class CaptureResult {
        CAPTURED,
        ALREADY_CAPTURED,
        CHARGE_CONFLICT,
        PAYMENT_NOT_FOUND,
    }

    enum class Action { CANCEL, REFUND }

    enum class RefundRequestMode {
        EXPLICIT,
        ALL_REMAINING,
    }

    enum class RefundSourceKind {
        ATOMIC_ACTION,
        LEGACY_ACTION,
        PAYMENT_STATUS,
    }

    data class RefundFingerprint(
        val mode: RefundRequestMode,
        val requestAmountMinor: Long?,
    )

    data class Result(
        val status: Status,
        val reason: String?,
    ) {
        enum class Status {
            OK,
            ALREADY,
            CONFLICT,
            ERROR,
        }
    }

    data class SavedAction(
        val id: Long,
        val bookingId: UUID,
        val idempotencyKey: String,
        val action: Action,
        val result: Result,
        val createdAt: Instant,
        val refundFingerprint: RefundFingerprint? = null,
        val refundResultAmountMinor: Long? = null,
        val refundSourceKind: RefundSourceKind? = null,
    )

    sealed interface CancelExecution {
        data class Success(
            val clubId: Long,
            val bookingId: UUID,
            val slotStart: Instant,
            val idempotent: Boolean,
            val alreadyCancelled: Boolean,
        ) : CancelExecution

        data class Conflict(
            val reason: String,
            val idempotent: Boolean,
        ) : CancelExecution

        data class StoredError(
            val reason: String,
            val idempotent: Boolean,
        ) : CancelExecution

        data object NotFound : CancelExecution

        data object IdempotencyBindingMismatch : CancelExecution
    }

    sealed interface RefundExecution {
        data class Success(
            val amountMinor: Long,
            val remainingMinor: Long?,
            val idempotent: Boolean,
        ) : RefundExecution

        data class Conflict(
            val reason: String,
            val idempotent: Boolean,
        ) : RefundExecution

        data class Unprocessable(
            val reason: String,
            val idempotent: Boolean,
        ) : RefundExecution

        data object IdempotencyBindingMismatch : RefundExecution

        data object IdempotencyPayloadMismatch : RefundExecution
    }

    @Suppress("LongParameterList")
    suspend fun createInitiated(
        bookingId: UUID?,
        provider: String,
        currency: String,
        amountMinor: Long,
        payload: String,
        idempotencyKey: String,
    ): PaymentRecord

    suspend fun markPending(id: UUID)

    suspend fun markCaptured(
        id: UUID,
        externalId: String?,
    )

    suspend fun markCapturedByChargeIds(
        id: UUID,
        externalId: String?,
        telegramPaymentChargeId: String?,
        providerPaymentChargeId: String?,
    ): CaptureResult =
        CaptureResult.CAPTURED.also {
            markCaptured(id, externalId)
        }

    suspend fun markDeclined(
        id: UUID,
        reason: String,
    )

    suspend fun markRefunded(
        id: UUID,
        externalId: String?,
    )

    suspend fun findByPayload(payload: String): PaymentRecord?

    suspend fun findByIdempotencyKey(idempotencyKey: String): PaymentRecord?

    suspend fun recordAction(
        bookingId: UUID,
        key: String,
        action: Action,
        result: Result,
    ): SavedAction

    suspend fun findActionByIdempotencyKey(key: String): SavedAction?

    suspend fun executeCancelIdempotently(
        clubId: Long,
        bookingId: UUID,
        idempotencyKey: String,
        reason: String?,
    ): CancelExecution

    suspend fun executeRefundIdempotently(
        clubId: Long,
        bookingId: UUID,
        idempotencyKey: String,
        fingerprint: RefundFingerprint,
    ): RefundExecution

    suspend fun updateStatus(
        id: UUID,
        status: String,
        externalId: String?,
    )
}

/**
 * Read-model required to safely validate Telegram pre-checkout requests.
 */
interface PaymentsPreCheckoutRepository {
    data class BookingSnapshot(
        val status: String,
        val guestUserId: Long?,
        val arrivalBy: Instant?,
    )

    suspend fun findBookingSnapshot(bookingId: UUID): BookingSnapshot?
}
