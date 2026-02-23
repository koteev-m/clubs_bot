package com.example.bot.telegram

import com.example.bot.payments.PaymentsPreCheckoutRepository
import com.example.bot.payments.PaymentsRepository
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.model.PreCheckoutQuery
import com.pengrad.telegrambot.model.SuccessfulPayment
import com.pengrad.telegrambot.model.User
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class PreCheckoutValidatorTest {
    private val now = Instant.parse("2026-01-15T20:00:00Z")
    private val bookingId = UUID.fromString("5de66a95-4864-4a95-99f8-7f8f8984423f")

    @Test
    fun `cancelled booking reject`() = runBlocking {
        val validator =
            PreCheckoutValidator(
                paymentsRepository = FakePaymentsRepository(payment(status = "INITIATED")),
                preCheckoutRepository =
                    FakePreCheckoutRepository(
                        PaymentsPreCheckoutRepository.BookingSnapshot(
                            status = "CANCELLED",
                            guestUserId = 1001L,
                            arrivalBy = now.plusSeconds(1800),
                        ),
                    ),
                clock = fixedClock(),
            )

        val result = validator.validate(query())

        assertEquals(PreCheckoutValidation.Reject("booking inactive"), result)
    }

    @Test
    fun `amount mismatch reject`() = runBlocking {
        val validator =
            PreCheckoutValidator(
                paymentsRepository = FakePaymentsRepository(payment(amountMinor = 9_999L)),
                preCheckoutRepository = FakePreCheckoutRepository(activeBooking()),
                clock = fixedClock(),
            )

        val result = validator.validate(query(totalAmountMinor = 10_000L))

        assertEquals(PreCheckoutValidation.Reject("amount mismatch"), result)
    }

    @Test
    fun `expired hold reject`() = runBlocking {
        val validator =
            PreCheckoutValidator(
                paymentsRepository = FakePaymentsRepository(payment(createdAt = now.minusSeconds(4000))),
                preCheckoutRepository =
                    FakePreCheckoutRepository(
                        activeBooking(arrivalBy = now.minusSeconds(1)),
                    ),
                clock = fixedClock(),
            )

        val result = validator.validate(query())

        assertEquals(PreCheckoutValidation.Reject("hold expired"), result)
    }

    @Test
    fun `happy path ok`() = runBlocking {
        val validator =
            PreCheckoutValidator(
                paymentsRepository = FakePaymentsRepository(payment(status = "PENDING")),
                preCheckoutRepository = FakePreCheckoutRepository(activeBooking()),
                clock = fixedClock(),
            )

        val result = validator.validate(query())

        assertEquals(PreCheckoutValidation.Ok, result)
    }

    @Test
    fun `pre-checkout validator exception fails closed`() = runBlocking {
        val bot = mockk<TelegramBot>()
        val config = com.example.bot.payments.PaymentConfig(providerToken = "token")
        val paymentsRepository = FakePaymentsRepository(record = null)
        val validator = mockk<PreCheckoutValidator>()
        val query = mockk<PreCheckoutQuery>()
        val requestSlot = slot<com.pengrad.telegrambot.request.AnswerPreCheckoutQuery>()

        every { query.id() } returns "pcq-1"
        coEvery { validator.validate(query) } throws IllegalStateException("boom")
        every { bot.execute(capture(requestSlot)) } returns mockk(relaxed = true)

        val handlers =
            PaymentsHandlers(
                bot = bot,
                config = config,
                paymentsRepo = paymentsRepository,
                preCheckoutValidator = validator,
            )

        handlers.handlePreCheckout(query)

        coVerify(exactly = 1) { validator.validate(query) }
        coVerify(exactly = 1) { bot.execute(any<com.pengrad.telegrambot.request.AnswerPreCheckoutQuery>()) }

        val parameters = requestSlot.captured.getParameters()
        assertEquals(false, parameters["ok"])
        assertEquals("Платеж недоступен, обновите бронь", parameters["error_message"])
    }

    @Test
    fun `successful payment captures with provider external id`() = runBlocking {
        val bot = mockk<TelegramBot>(relaxed = true)
        val config = com.example.bot.payments.PaymentConfig(providerToken = "token")
        val paymentsRepository = mockk<PaymentsRepository>()
        val validator = mockk<PreCheckoutValidator>()
        val message = mockk<Message>()
        val successfulPayment = mockk<SuccessfulPayment>()
        val record = payment(status = "PENDING")

        every { message.successfulPayment() } returns successfulPayment
        every { successfulPayment.invoicePayload } returns "payload-1"
        every { successfulPayment.providerPaymentChargeId } returns "provider-charge-1"
        every { successfulPayment.telegramPaymentChargeId } returns "telegram-charge-1"
        coEvery { paymentsRepository.findByPayload("payload-1") } returns record
        coEvery { paymentsRepository.markCaptured(any(), any()) } returns Unit

        val handlers =
            PaymentsHandlers(
                bot = bot,
                config = config,
                paymentsRepo = paymentsRepository,
                preCheckoutValidator = validator,
            )

        handlers.handleSuccessfulPayment(message)

        coVerify(exactly = 1) { paymentsRepository.markCaptured(record.id, "provider-charge-1") }
    }

    private fun payment(
        amountMinor: Long = 10_000L,
        status: String = "INITIATED",
        createdAt: Instant = now.minusSeconds(300),
    ) =
        PaymentsRepository.PaymentRecord(
            id = UUID.fromString("9f7f36fd-122f-4a54-a132-d0556d6ab4be"),
            bookingId = bookingId,
            provider = "PROVIDER",
            currency = "RUB",
            amountMinor = amountMinor,
            status = status,
            payload = "payload-1",
            externalId = null,
            idempotencyKey = "idem-1",
            createdAt = createdAt,
            updatedAt = createdAt,
        )

    private fun activeBooking(arrivalBy: Instant = now.plusSeconds(1200)) =
        PaymentsPreCheckoutRepository.BookingSnapshot(
            status = "BOOKED",
            guestUserId = 1001L,
            arrivalBy = arrivalBy,
        )

    private fun query(
        totalAmountMinor: Long = 10_000L,
        currency: String = "RUB",
        payload: String = "payload-1",
        userId: Long = 1001L,
    ): PreCheckoutQuery {
        val user = mockk<User>()
        every { user.id() } returns userId

        val query = mockk<PreCheckoutQuery>()
        every { query.invoicePayload() } returns payload
        every { query.totalAmount() } returns totalAmountMinor.toInt()
        every { query.currency() } returns currency
        every { query.from() } returns user
        return query
    }

    private fun fixedClock() = Clock.fixed(now, ZoneOffset.UTC)
}

private class FakePaymentsRepository(
    private val record: PaymentsRepository.PaymentRecord?,
) : PaymentsRepository {
    override suspend fun createInitiated(
        bookingId: UUID?,
        provider: String,
        currency: String,
        amountMinor: Long,
        payload: String,
        idempotencyKey: String,
    ): PaymentsRepository.PaymentRecord = throw UnsupportedOperationException("not used")

    override suspend fun markPending(id: UUID) = Unit

    override suspend fun markCaptured(
        id: UUID,
        externalId: String?,
    ) = Unit

    override suspend fun markDeclined(
        id: UUID,
        reason: String,
    ) = Unit

    override suspend fun markRefunded(
        id: UUID,
        externalId: String?,
    ) = Unit

    override suspend fun findByPayload(payload: String): PaymentsRepository.PaymentRecord? =
        record?.takeIf { it.payload == payload }

    override suspend fun findByIdempotencyKey(idempotencyKey: String): PaymentsRepository.PaymentRecord? = null

    override suspend fun recordAction(
        bookingId: UUID,
        key: String,
        action: PaymentsRepository.Action,
        result: PaymentsRepository.Result,
    ): PaymentsRepository.SavedAction = throw UnsupportedOperationException("not used")

    override suspend fun findActionByIdempotencyKey(key: String): PaymentsRepository.SavedAction? = null

    override suspend fun updateStatus(
        id: UUID,
        status: String,
        externalId: String?,
    ) = Unit
}

private class FakePreCheckoutRepository(
    private val booking: PaymentsPreCheckoutRepository.BookingSnapshot?,
) : PaymentsPreCheckoutRepository {
    override suspend fun findBookingSnapshot(bookingId: UUID): PaymentsPreCheckoutRepository.BookingSnapshot? = booking
}
