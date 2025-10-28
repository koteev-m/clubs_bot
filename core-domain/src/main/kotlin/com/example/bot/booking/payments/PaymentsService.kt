package com.example.bot.booking.payments

import com.example.bot.booking.PaymentPolicy
import com.example.bot.booking.legacy.BookingError
import com.example.bot.booking.legacy.BookingService
import com.example.bot.booking.legacy.ConfirmRequest
import com.example.bot.booking.legacy.Either
import com.example.bot.payments.PaymentsRepository
import java.math.BigDecimal
import java.util.UUID

/**
 * Service orchestrating booking confirmation with optional payments.
 */
private val MINORS_IN_MAJOR = BigDecimal(100)

class PaymentsService(private val bookingService: BookingService, private val paymentsRepo: PaymentsRepository) {
    /**
     * Starts confirmation flow respecting [policy].
     */
    suspend fun startConfirmation(
        input: ConfirmInput,
        contact: ContactInfo?,
        policy: PaymentPolicy,
        idemKey: String,
    ): Either<BookingError, ConfirmResult> {
        val total = input.minDeposit.multiply(BigDecimal(input.guestsCount))
        return when (policy.mode) {
            PaymentMode.NONE -> {
                val req =
                    ConfirmRequest(
                        holdId = null,
                        clubId = input.clubId,
                        eventStartUtc = input.eventStartUtc,
                        tableId = input.tableId,
                        guestsCount = input.guestsCount,
                        guestUserId = null,
                        guestName = contact?.tgUsername,
                        phoneE164 = contact?.phoneE164,
                    )
                when (val res = bookingService.confirm(req, idemKey)) {
                    is Either.Left -> Either.Left(res.value)
                    is Either.Right -> Either.Right(ConfirmResult.Confirmed(res.value))
                }
            }
            PaymentMode.PROVIDER_DEPOSIT -> {
                val totalMinor = total.multiply(MINORS_IN_MAJOR).longValueExact()
                Either.Right(createPendingPayment("PROVIDER", policy.currency, totalMinor, idemKey))
            }
            PaymentMode.STARS_DIGITAL -> {
                val totalMinor = total.longValueExact()
                Either.Right(createPendingPayment("STARS", "XTR", totalMinor, idemKey))
            }
        }
    }

    private suspend fun createPendingPayment(
        provider: String,
        currency: String,
        amountMinor: Long,
        idemKey: String,
    ): ConfirmResult {
        val payload = UUID.randomUUID().toString()
        paymentsRepo.createInitiated(
            bookingId = null,
            provider = provider,
            currency = currency,
            amountMinor = amountMinor,
            payload = payload,
            idempotencyKey = idemKey,
        )
        val invoice =
            InvoiceInfo(
                invoiceId = payload,
                payload = payload,
                totalMinor = amountMinor,
                currency = currency,
                invoiceLink = null,
            )
        return ConfirmResult.PendingPayment(invoice)
    }
}
