package com.example.bot.payments

import com.example.bot.booking.PaymentPolicy
import com.example.bot.booking.legacy.BookingService
import com.example.bot.booking.legacy.BookingSummary
import com.example.bot.booking.legacy.Either
import com.example.bot.booking.payments.ConfirmInput
import com.example.bot.booking.payments.PaymentMode
import com.example.bot.booking.payments.PaymentsService
import com.example.bot.payments.PaymentsRepository.PaymentRecord
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class PaymentsServiceTest {
    private val bookingService = mockk<BookingService>()
    private val repo =
        object : PaymentsRepository {
            override suspend fun createInitiated(
                bookingId: UUID?,
                provider: String,
                currency: String,
                amountMinor: Long,
                payload: String,
                idempotencyKey: String,
            ): PaymentRecord =
                PaymentRecord(
                    id = UUID.randomUUID(),
                    bookingId = bookingId,
                    provider = provider,
                    currency = currency,
                    amountMinor = amountMinor,
                    status = "INITIATED",
                    payload = payload,
                    externalId = null,
                    idempotencyKey = idempotencyKey,
                    createdAt = Instant.now(),
                    updatedAt = Instant.now(),
                )

            override suspend fun markPending(id: UUID) {}

            override suspend fun markCaptured(
                id: UUID,
                externalId: String?,
            ) {}

            override suspend fun markDeclined(
                id: UUID,
                reason: String,
            ) {}

            override suspend fun markRefunded(
                id: UUID,
                externalId: String?,
            ) {}

            override suspend fun findByPayload(payload: String): PaymentRecord? = null
        }
    private val service = PaymentsService(bookingService, repo)

    @Test
    fun `provider deposit returns pending`() =
        kotlinx.coroutines.test.runTest {
            val input = ConfirmInput(1, Instant.now(), 1, 1, 2, BigDecimal(100))
            val policy = PaymentPolicy(mode = PaymentMode.PROVIDER_DEPOSIT)
            val res = service.startConfirmation(input, null, policy, "idem")
            assertTrue(res is Either.Right)
            val right = res as Either.Right
            assertTrue(right.value is com.example.bot.booking.payments.ConfirmResult.PendingPayment)
            val invoice = (right.value as com.example.bot.booking.payments.ConfirmResult.PendingPayment).invoice
            assertEquals(20000L, invoice.totalMinor)
        }

    @Test
    fun `no payment confirms booking`() =
        kotlinx.coroutines.test.runTest {
            val input = ConfirmInput(1, Instant.now(), 1, 1, 1, BigDecimal(50))
            val summary =
                BookingSummary(
                    id = UUID.randomUUID(),
                    clubId = 1,
                    eventId = 1,
                    tableId = 1,
                    tableNumber = 1,
                    guestsCount = 1,
                    totalDeposit = BigDecimal(50),
                    status = "BOOKED",
                    arrivalBy = Instant.now(),
                    qrSecret = "qr",
                )
            coEvery { bookingService.confirm(any(), any()) } returns Either.Right(summary)
            val policy = PaymentPolicy(mode = PaymentMode.NONE)
            val res = service.startConfirmation(input, null, policy, "idem2")
            assertTrue(res is Either.Right)
            val right = res as Either.Right
            assertTrue(right.value is com.example.bot.booking.payments.ConfirmResult.Confirmed)
            assertEquals(summary, (right.value as com.example.bot.booking.payments.ConfirmResult.Confirmed).booking)
        }
}
