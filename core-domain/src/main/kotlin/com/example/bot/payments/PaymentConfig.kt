package com.example.bot.payments

/**
 * Configuration for payment processing.
 *
 * @property providerToken secret token for external payment provider. Never log it.
 * @property currency default ISO-4217 currency code for invoices.
 * @property allowSplitPay whether split payments are allowed for provider invoices.
 * @property starsEnabled whether Telegram Stars payments are enabled.
 * @property invoiceTitlePrefix prefix used when generating invoice titles.
 */
data class PaymentConfig(
    val providerToken: String,
    val currency: String = "RUB",
    val allowSplitPay: Boolean = false,
    val starsEnabled: Boolean = false,
    val invoiceTitlePrefix: String = "Deposit for table",
)
