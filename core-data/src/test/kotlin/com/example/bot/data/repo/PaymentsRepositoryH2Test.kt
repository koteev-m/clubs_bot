package com.example.bot.data.repo

import com.example.bot.data.TestDatabase
import com.example.bot.payments.PaymentsRepository
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PaymentsRepositoryH2Test {
    private lateinit var testDatabase: TestDatabase
    private lateinit var repository: PaymentsRepositoryImpl

    @BeforeEach
    fun setUp() {
        testDatabase = TestDatabase()
        repository = PaymentsRepositoryImpl(testDatabase.database)
    }

    @AfterEach
    fun tearDown() {
        testDatabase.close()
    }

    @Test
    fun `capture maps duplicate charge ids to charge conflict on h2`() = runBlocking {
        val firstPayment =
            repository.createInitiated(
                bookingId = null,
                provider = "TG",
                currency = "RUB",
                amountMinor = 10_000,
                payload = "h2-payload-1",
                idempotencyKey = "h2-idem-1",
            )
        val secondPayment =
            repository.createInitiated(
                bookingId = null,
                provider = "TG",
                currency = "RUB",
                amountMinor = 20_000,
                payload = "h2-payload-2",
                idempotencyKey = "h2-idem-2",
            )

        repository.markCapturedByChargeIds(
            id = firstPayment.id,
            externalId = "provider-charge-1",
            telegramPaymentChargeId = "telegram-charge-1",
            providerPaymentChargeId = "provider-charge-1",
        ) shouldBe PaymentsRepository.CaptureResult.CAPTURED

        val conflictResult =
            repository.markCapturedByChargeIds(
                id = secondPayment.id,
                externalId = "provider-charge-1",
                telegramPaymentChargeId = "telegram-charge-2",
                providerPaymentChargeId = "provider-charge-1",
            )

        val secondActual = repository.findByPayload("h2-payload-2")

        conflictResult shouldBe PaymentsRepository.CaptureResult.CHARGE_CONFLICT
        secondActual?.status shouldBe "INITIATED"
        secondActual?.externalId shouldBe null
        secondActual?.telegramPaymentChargeId shouldBe null
        secondActual?.providerPaymentChargeId shouldBe null
    }
}
