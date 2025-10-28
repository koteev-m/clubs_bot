package com.example.bot.booking.payments

import com.example.bot.booking.legacy.BookingSummary

/**
 * Mode of payment for a booking confirmation.
 *
 * This is a re-export of [com.example.bot.booking.PaymentMode] to avoid touching
 * the original booking package while providing a dedicated namespace for
 * payment-related APIs.
 */
@Suppress("EXPERIMENTAL_FEATURE_WARNING")
typealias PaymentMode = com.example.bot.booking.PaymentMode

/**
 * Input parameters for starting booking confirmation.
 * Delegates to [com.example.bot.booking.ConfirmInput].
 */
@Suppress("EXPERIMENTAL_FEATURE_WARNING")
typealias ConfirmInput = com.example.bot.booking.legacy.ConfirmInput

/**
 * Contact details supplied by the guest.
 * Delegates to [com.example.bot.booking.ContactInfo].
 */
@Suppress("EXPERIMENTAL_FEATURE_WARNING")
typealias ContactInfo = com.example.bot.booking.legacy.ContactInfo

/** Information about a generated invoice. */
data class InvoiceInfo(
    val invoiceId: String,
    val payload: String,
    val totalMinor: Long,
    val currency: String,
    val invoiceLink: String? = null,
)

/** Result of confirmation attempt. */
sealed interface ConfirmResult {
    /** Confirmation is pending payment; [invoice] should be sent to user. */
    data class PendingPayment(val invoice: InvoiceInfo) : ConfirmResult

    /** Booking has been confirmed immediately. */
    data class Confirmed(val booking: BookingSummary) : ConfirmResult
}
