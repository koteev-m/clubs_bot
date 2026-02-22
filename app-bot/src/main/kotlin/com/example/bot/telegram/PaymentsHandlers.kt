package com.example.bot.telegram

import com.example.bot.booking.payments.InvoiceInfo
import com.example.bot.payments.PaymentConfig
import com.example.bot.payments.PaymentsPreCheckoutRepository
import com.example.bot.payments.PaymentsRepository
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.model.PreCheckoutQuery
import com.pengrad.telegrambot.model.request.LabeledPrice
import com.pengrad.telegrambot.request.AnswerPreCheckoutQuery
import com.pengrad.telegrambot.request.SendInvoice
import com.pengrad.telegrambot.response.SendResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Telegram adapter for handling payment related callbacks.
 *
 * Минимальная обёртка для Bot Payments: отправка инвойса, pre-checkout ok, фиксация успешного платежа.
 * Поддерживает текущую версию pengrad (9.2.0). Для более новой сигнатуры стоит обновить зависимость.
 */
class PaymentsHandlers(
    private val bot: TelegramBot,
    private val config: PaymentConfig,
    private val paymentsRepo: PaymentsRepository,
    private val preCheckoutValidator: PreCheckoutValidator,
) {
    /** Отправка инвойса через Bot Payments API. */
    fun sendInvoice(
        chatId: Long,
        invoice: InvoiceInfo,
    ): SendResponse {
        // Формируем цену (minor units → Int).
        val price = LabeledPrice("deposit", invoice.totalMinor.toInt())

        // Конструктор в pengrad 9.2.0 помечен deprecated — подавляем только для этого вызова.
        @Suppress("DEPRECATION")
        val req =
            SendInvoice(
                chatId,
                config.invoiceTitlePrefix,
                "",
                invoice.payload,
                invoice.currency,
                price,
            ).providerToken(config.providerToken)

        return bot.execute(req)
    }

    /** Ответ на pre-checkout: подтверждаем только после повторной серверной валидации. */
    suspend fun handlePreCheckout(query: PreCheckoutQuery) {
        val response =
            try {
                val validation = preCheckoutValidator.validate(query)
                if (validation is PreCheckoutValidation.Ok) {
                    AnswerPreCheckoutQuery(query.id())
                } else {
                    AnswerPreCheckoutQuery(query.id(), SAFE_PRECHECKOUT_ERROR)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                logger.warn("precheckout validation failed: {}", e.javaClass.simpleName)
                AnswerPreCheckoutQuery(query.id(), SAFE_PRECHECKOUT_ERROR)
            }

        withContext(Dispatchers.IO) {
            bot.execute(response)
        }
    }

    /** Обработка успешного платежа: маппим payload → запись и помечаем CAPTURED. */
    suspend fun handleSuccessfulPayment(message: Message) {
        val payload = message.successfulPayment()?.invoicePayload ?: return
        val record = paymentsRepo.findByPayload(payload) ?: return
        // В демо — генерим внешний id; в проде сюда кладём ID от платежного провайдера
        paymentsRepo.markCaptured(record.id, UUID.randomUUID().toString())
    }

    companion object {
        private val logger = LoggerFactory.getLogger(PaymentsHandlers::class.java)
        private const val SAFE_PRECHECKOUT_ERROR = "Платеж недоступен, обновите бронь"
    }
}

sealed interface PreCheckoutValidation {
    data object Ok : PreCheckoutValidation

    data class Reject(
        val reason: String,
    ) : PreCheckoutValidation
}

class PreCheckoutValidator(
    private val paymentsRepository: PaymentsRepository,
    private val preCheckoutRepository: PaymentsPreCheckoutRepository,
    private val holdTtl: Duration = Duration.ofMinutes(DEFAULT_HOLD_TTL_MINUTES),
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun validate(query: PreCheckoutQuery): PreCheckoutValidation {
        val payload = query.invoicePayload().orEmpty()
        if (payload.isBlank()) {
            return PreCheckoutValidation.Reject("payload blank")
        }

        val payment = paymentsRepository.findByPayload(payload) ?: return PreCheckoutValidation.Reject("payment not found")
        if (payment.payload != payload) {
            return PreCheckoutValidation.Reject("payload mismatch")
        }

        if (payment.status !in ALLOWED_PAYMENT_STATUSES) {
            return PreCheckoutValidation.Reject("invalid payment status")
        }

        if (payment.amountMinor != query.totalAmount().toLong()) {
            return PreCheckoutValidation.Reject("amount mismatch")
        }

        if (!payment.currency.equals(query.currency(), ignoreCase = true)) {
            return PreCheckoutValidation.Reject("currency mismatch")
        }

        val bookingId = payment.bookingId ?: return PreCheckoutValidation.Reject("booking not bound")
        val booking = preCheckoutRepository.findBookingSnapshot(bookingId) ?: return PreCheckoutValidation.Reject("booking not found")

        if (booking.status != BOOKING_STATUS_BOOKED) {
            return PreCheckoutValidation.Reject("booking inactive")
        }

        val actorUserId = query.from()?.id()?.toLong() ?: return PreCheckoutValidation.Reject("actor missing")
        if (booking.guestUserId == null || booking.guestUserId != actorUserId) {
            return PreCheckoutValidation.Reject("booking ownership mismatch")
        }

        val now = clock.instant()
        val expiresAt = booking.arrivalBy ?: payment.createdAt.plus(holdTtl)
        if (now.isAfter(expiresAt)) {
            return PreCheckoutValidation.Reject("hold expired")
        }

        return PreCheckoutValidation.Ok
    }

    private companion object {
        private val ALLOWED_PAYMENT_STATUSES = setOf("INITIATED", "PENDING")
        private const val BOOKING_STATUS_BOOKED = "BOOKED"
        private const val DEFAULT_HOLD_TTL_MINUTES = 30L
    }
}
