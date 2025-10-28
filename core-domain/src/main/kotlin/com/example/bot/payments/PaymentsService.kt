package com.example.bot.payments

import com.example.bot.booking.PaymentMode
import com.example.bot.booking.PaymentPolicy
import com.example.bot.booking.legacy.ConfirmInput
import com.example.bot.booking.legacy.InvoiceInfo
import java.math.BigDecimal
import java.util.UUID

/**
 * Simple factory for generating invoice information used in tests.
 */
private val MINORS_IN_MAJOR = BigDecimal(100)
private const val START_PARAM_LENGTH = 8

object PaymentsService {
    /**
     * Creates invoice based on provided [input] and [policy].
     */
    suspend fun createInvoice(
        input: ConfirmInput,
        policy: PaymentPolicy,
        idemKey: String,
    ): InvoiceInfo {
        val total = input.minDeposit.multiply(BigDecimal(input.guestsCount))
        val id = UUID.randomUUID().toString()
        val currency = if (policy.mode == PaymentMode.STARS_DIGITAL) "XTR" else policy.currency
        val totalMinor = total.multiply(MINORS_IN_MAJOR).toInt()
        return InvoiceInfo(
            invoiceId = id,
            payload = idemKey,
            totalMinor = totalMinor,
            currency = currency,
            startParameter = id.take(START_PARAM_LENGTH),
            createLink = if (policy.splitPay) null else null,
        )
    }
}
