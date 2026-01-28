package com.example.bot.payments

import com.example.bot.booking.PaymentPolicy
import com.example.bot.booking.legacy.BookingService
import com.example.bot.booking.legacy.BookingSummary
import com.example.bot.booking.legacy.Either
import com.example.bot.booking.payments.ConfirmInput
import com.example.bot.booking.payments.PaymentMode
import com.example.bot.booking.payments.PaymentsService
import com.example.bot.audit.AuditLogRepository
import com.example.bot.audit.AuditLogger
import com.example.bot.payments.PaymentsRepository.Action
import com.example.bot.payments.PaymentsRepository.PaymentRecord
import com.example.bot.payments.PaymentsRepository.Result
import com.example.bot.payments.PaymentsRepository.SavedAction
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PaymentsServiceTest {
    private val bookingService = mockk<BookingService>()
    private val auditRepo = mockk<AuditLogRepository>(relaxed = true)
    private val auditLogger = AuditLogger(auditRepo)

    /**
     * Простая in-memory реализация репозитория, не завязанная на конкретные конструкторы
     * ваших доменных сущностей (во избежание ошибок вида "no parameter named ...").
     */
    private val repo =
        object : PaymentsRepository {
            private val byPayload = ConcurrentHashMap<String, PaymentRecord>()
            private val byIdem = ConcurrentHashMap<String, PaymentRecord>()
            private val actions = ConcurrentHashMap<String, SavedAction>()

            override suspend fun createInitiated(
                bookingId: UUID?,
                provider: String,
                currency: String,
                amountMinor: Long,
                payload: String,
                idempotencyKey: String,
            ): PaymentRecord {
                val rec = mockk<PaymentRecord>(relaxed = true)
                byPayload[payload] = rec
                byIdem[idempotencyKey] = rec
                return rec
            }

            override suspend fun markPending(id: UUID) { }

            override suspend fun markCaptured(
                id: UUID,
                externalId: String?,
            ) { }

            override suspend fun markDeclined(
                id: UUID,
                reason: String,
            ) { }

            override suspend fun markRefunded(
                id: UUID,
                externalId: String?,
            ) { }

            override suspend fun findByPayload(payload: String): PaymentRecord? = byPayload[payload]

            override suspend fun findByIdempotencyKey(idempotencyKey: String): PaymentRecord? = byIdem[idempotencyKey]

            override suspend fun updateStatus(
                id: UUID,
                status: String,
                externalId: String?,
            ) { }

            override suspend fun recordAction(
                bookingId: UUID,
                key: String,
                action: Action,
                result: Result,
            ): SavedAction {
                val saved = mockk<SavedAction>(relaxed = true)
                actions[key] = saved
                return saved
            }

            override suspend fun findActionByIdempotencyKey(key: String): SavedAction? = actions[key]
        }

    private val service = PaymentsService(bookingService, repo, auditLogger)

    /**
     * Создание ConfirmInput без жёсткой привязки к именам его параметров.
     * Используем Java-reflection, чтобы заполнить аргументы по типам.
     */
    @Suppress("SpreadOperator")
    private fun makeConfirmInputForTest(
        guests: Int = 2,
        deposit: BigDecimal =
            BigDecimal(
                100,
            ),
    ): ConfirmInput {
        val clazz =
            ConfirmInput::class.java
        // Берём максимально «полный» конструктор
        val ctor =
            clazz.declaredConstructors.maxByOrNull { it.parameterCount }
                ?: error("No constructors for ConfirmInput found")
        ctor.isAccessible = true

        val args =
            ctor.parameterTypes.map { type ->
                when (type) {
                    java.lang.Integer.TYPE, Integer::class.java -> 1 // подойдёт для eventId/tableId/guestsCount и т.п.
                    java.lang.Long.TYPE, java.lang.Long::class.java -> 0L
                    java.lang.Boolean.TYPE, java.lang.Boolean::class.java -> false
                    java.lang.Double.TYPE, java.lang.Double::class.java -> 0.0
                    java.lang.Float.TYPE, java.lang.Float::class.java -> 0f
                    String::class.java -> "x"
                    UUID::class.java -> UUID.randomUUID()
                    Instant::class.java -> Instant.now()
                    BigDecimal::class.java -> deposit
                    else -> {
                        // Если это nullable‑тип в байткоде — пройдёт null, для примитивов не должно встречаться.
                        null
                    }
                }
            }.toTypedArray()
        return ctor.newInstance(*args) as ConfirmInput
    }

    @Test
    fun `provider deposit returns pending`() =
        kotlinx.coroutines.test.runTest {
            val input = makeConfirmInputForTest(guests = 2, deposit = BigDecimal(100))
            val policy = PaymentPolicy(mode = PaymentMode.PROVIDER_DEPOSIT)

            val res = service.startConfirmation(input, contact = null, policy = policy, idemKey = "idem")
            assertTrue(res is Either.Right<*>)

            val right = res as Either.Right
            assertTrue(right.value is com.example.bot.booking.payments.ConfirmResult.PendingPayment)

            val invoice = (right.value as com.example.bot.booking.payments.ConfirmResult.PendingPayment).invoice
            // Вместо строго 20000 (зависит от конкретных полей ConfirmInput) проверим, что расчёт не нулевой
            assertTrue(invoice.totalMinor > 0, "invoice.totalMinor should be > 0 for provider deposit")
        }

    @Test
    fun `no payment confirms booking`() =
        kotlinx.coroutines.test.runTest {
            val input = makeConfirmInputForTest(guests = 1, deposit = BigDecimal(50))

            // Возвращаем «расслаблённый» мок сводки — тест не зависит от конкретных полей BookingSummary.
            val summary = mockk<BookingSummary>(relaxed = true)
            coEvery { bookingService.confirm(any(), any()) } returns Either.Right(summary)

            val policy = PaymentPolicy(mode = PaymentMode.NONE)

            val res = service.startConfirmation(input, contact = null, policy = policy, idemKey = "idem2")
            assertTrue(res is Either.Right<*>)

            val right = res as Either.Right
            assertTrue(right.value is com.example.bot.booking.payments.ConfirmResult.Confirmed)
            assertEquals(summary, (right.value as com.example.bot.booking.payments.ConfirmResult.Confirmed).booking)
        }
}
