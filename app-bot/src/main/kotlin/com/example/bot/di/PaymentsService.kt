package com.example.bot.di

import java.util.UUID

interface PaymentsService {
    data class FinalizeResult(val paymentStatus: String)
    data class CancelResult(
        val bookingId: UUID,
        val idempotent: Boolean,
        val alreadyCancelled: Boolean,
    )

    data class RefundResult(
        val refundAmountMinor: Long,
        val idempotent: Boolean,
    )

    class ValidationException(message: String) : RuntimeException(message)
    class ConflictException(message: String) : RuntimeException(message)
    class UnprocessableException(message: String) : RuntimeException(message)

    suspend fun finalize(
        clubId: Long,
        bookingId: UUID,
        paymentToken: String?,
        idemKey: String,
        actorUserId: Long,
    ): FinalizeResult

    suspend fun cancel(
        clubId: Long,
        bookingId: UUID,
        reason: String?,
        idemKey: String,
        actorUserId: Long,
    ): CancelResult

    suspend fun refund(
        clubId: Long,
        bookingId: UUID,
        amountMinor: Long?,
        idemKey: String,
        actorUserId: Long,
    ): RefundResult
}
