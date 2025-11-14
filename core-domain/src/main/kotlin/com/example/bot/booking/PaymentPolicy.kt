package com.example.bot.booking

/**
 * Mode of payment for a booking confirmation.
 */
enum class PaymentMode {
    /** Booking requires no upfront payment. */
    NONE,

    /** Deposit should be charged via external provider. */
    PROVIDER_DEPOSIT,

    /** Digital add-ons are paid using Telegram Stars. */
    STARS_DIGITAL,
}

/**
 * Policy describing how payment should be handled.
 *
 * @property mode mode of payment
 * @property currency ISO 4217 currency code or "XTR" for stars
 * @property splitPay whether invoice supports split payment
 */
data class PaymentPolicy(
    val mode: PaymentMode,
    val currency: String = "RUB",
    val splitPay: Boolean = true,
)
