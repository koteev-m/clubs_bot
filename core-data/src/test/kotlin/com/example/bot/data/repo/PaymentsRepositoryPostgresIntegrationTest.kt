package com.example.bot.data.repo

import com.example.bot.data.booking.core.PostgresIntegrationTest
import com.example.bot.payments.PaymentsRepository
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PaymentsRepositoryPostgresIntegrationTest : PostgresIntegrationTest() {
    private lateinit var repository: PaymentsRepositoryImpl

    @BeforeEach
    fun setupRepository() {
        repository = PaymentsRepositoryImpl(database)
        transaction(database) {
            exec("TRUNCATE TABLE payment_actions, payments RESTART IDENTITY CASCADE")
        }
    }

    @Test
    fun `duplicate successful payment update is idempotent`() = runBlocking {
        val payment =
            repository.createInitiated(
                bookingId = null,
                provider = "TG",
                currency = "RUB",
                amountMinor = 10_000,
                payload = "payload-1",
                idempotencyKey = "idem-1",
            )

        val firstResult =
            repository.markCapturedByChargeIds(
                id = payment.id,
                externalId = "provider-charge-1",
                telegramPaymentChargeId = "telegram-charge-1",
                providerPaymentChargeId = "provider-charge-1",
            )
        val secondResult =
            repository.markCapturedByChargeIds(
                id = payment.id,
                externalId = "new-provider-charge",
                telegramPaymentChargeId = "telegram-charge-1",
                providerPaymentChargeId = "provider-charge-1",
            )

        val actual = repository.findByPayload("payload-1")

        firstResult shouldBe PaymentsRepository.CaptureResult.CAPTURED
        secondResult shouldBe PaymentsRepository.CaptureResult.ALREADY_CAPTURED
        actual?.status shouldBe "CAPTURED"
        actual?.externalId shouldBe "provider-charge-1"
        actual?.telegramPaymentChargeId shouldBe "telegram-charge-1"
        actual?.providerPaymentChargeId shouldBe "provider-charge-1"
    }

    @Test
    fun `capture rejects charge id that already belongs to other payment`() = runBlocking {
        val firstPayment =
            repository.createInitiated(
                bookingId = null,
                provider = "TG",
                currency = "RUB",
                amountMinor = 10_000,
                payload = "payload-1",
                idempotencyKey = "idem-1",
            )
        val secondPayment =
            repository.createInitiated(
                bookingId = null,
                provider = "TG",
                currency = "RUB",
                amountMinor = 20_000,
                payload = "payload-2",
                idempotencyKey = "idem-2",
            )

        repository.markCapturedByChargeIds(
            id = firstPayment.id,
            externalId = "provider-charge-1",
            telegramPaymentChargeId = "telegram-charge-1",
            providerPaymentChargeId = "provider-charge-1",
        )

        val conflictResult =
            repository.markCapturedByChargeIds(
                id = secondPayment.id,
                externalId = "provider-charge-1",
                telegramPaymentChargeId = "telegram-charge-2",
                providerPaymentChargeId = "provider-charge-1",
            )

        val secondActual = repository.findByPayload("payload-2")

        conflictResult shouldBe PaymentsRepository.CaptureResult.CHARGE_CONFLICT
        secondActual?.status shouldBe "INITIATED"
        secondActual?.externalId shouldBe null
        secondActual?.telegramPaymentChargeId shouldBe null
        secondActual?.providerPaymentChargeId shouldBe null
    }
}
