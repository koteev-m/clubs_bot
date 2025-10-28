package com.example.bot.booking.legacy

import com.example.bot.booking.PaymentMode
import com.example.bot.booking.PaymentPolicy
import com.example.bot.payments.PaymentsService

/**
 * Starts booking confirmation flow with optional payment requirement.
 */
suspend fun BookingService.startConfirmation(
    input: ConfirmInput,
    contact: ContactInfo?,
    policy: PaymentPolicy,
    idemKey: String,
): Either<BookingError, ConfirmResult> =
    when (policy.mode) {
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
            when (val res = confirm(req, idemKey)) {
                is Either.Left -> Either.Left(res.value)
                is Either.Right -> Either.Right(ConfirmResult.Confirmed(res.value))
            }
        }
        PaymentMode.PROVIDER_DEPOSIT, PaymentMode.STARS_DIGITAL -> {
            val invoice = PaymentsService.createInvoice(input, policy, idemKey)
            Either.Right(ConfirmResult.PendingPayment(invoice))
        }
    }
