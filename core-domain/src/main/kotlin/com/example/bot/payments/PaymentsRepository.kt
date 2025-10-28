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
        val idempotencyKey: String,
        val createdAt: Instant,
        val updatedAt: Instant,
    )

    enum class Action { CANCEL, REFUND }

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
    )

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

    suspend fun updateStatus(
        id: UUID,
        status: String,
        externalId: String?,
    )
}
