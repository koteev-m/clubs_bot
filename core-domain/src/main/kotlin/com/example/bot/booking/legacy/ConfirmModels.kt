package com.example.bot.booking.legacy

import java.math.BigDecimal
import java.time.Instant

/**
 * Input parameters for starting booking confirmation.
 */
data class ConfirmInput(
    val clubId: Long,
    val eventStartUtc: Instant,
    val tableId: Long,
    val tableNumber: Int,
    val guestsCount: Int,
    val minDeposit: BigDecimal,
)

/**
 * Contact details supplied by the guest.
 */
data class ContactInfo(val tgUsername: String?, val phoneE164: String?)

/**
 * Information about a generated invoice.
 */
data class InvoiceInfo(
    val invoiceId: String,
    val payload: String,
    val totalMinor: Int,
    val currency: String,
    val startParameter: String,
    val createLink: String? = null,
)

/** Result of confirmation attempt. */
sealed interface ConfirmResult {
    /** Confirmation is pending payment; [invoice] should be sent to user. */
    data class PendingPayment(val invoice: InvoiceInfo) : ConfirmResult

    /** Booking has been confirmed immediately. */
    data class Confirmed(val booking: BookingSummary) : ConfirmResult
}
