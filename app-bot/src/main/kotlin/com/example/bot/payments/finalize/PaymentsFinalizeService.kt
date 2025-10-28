package com.example.bot.payments.finalize

import java.util.UUID

interface PaymentsFinalizeService {
    data class FinalizeResult(val paymentStatus: String)

    class ValidationException(message: String) : RuntimeException(message)

    class ConflictException(message: String) : RuntimeException(message)

    suspend fun finalize(
        clubId: Long,
        bookingId: UUID,
        paymentToken: String?,
        idemKey: String,
        actorUserId: Long,
    ): FinalizeResult
}
