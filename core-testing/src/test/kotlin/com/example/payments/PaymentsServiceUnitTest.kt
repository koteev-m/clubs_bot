package com.example.payments

import com.example.bot.booking.PaymentPolicy
import com.example.bot.booking.legacy.BookingService
import com.example.bot.booking.legacy.BookingSummary
import com.example.bot.booking.legacy.Either
import com.example.bot.booking.payments.ConfirmInput
import com.example.bot.booking.payments.ConfirmResult
import com.example.bot.booking.payments.PaymentMode
import com.example.bot.booking.payments.PaymentsService
import com.example.bot.payments.PaymentsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class PaymentsServiceUnitTest {
    private val bookingService = mockk<BookingService>()
    private val repo = mockk<PaymentsRepository>(relaxed = true)
    private val service = PaymentsService(bookingService, repo)

    @Test
    fun `should create pending payment for provider deposit`() =
        runTest {
            val input = ConfirmInput(1, Instant.parse("2024-03-01T00:00:00Z"), 1, 1, 2, BigDecimal(100))
            val policy = PaymentPolicy(mode = PaymentMode.PROVIDER_DEPOSIT, currency = "RUB")
            val res = service.startConfirmation(input, null, policy, "idem")
            assertTrue(res is Either.Right)
            val pending = (res as Either.Right).value as ConfirmResult.PendingPayment
            assertEquals(20000L, pending.invoice.totalMinor)
            assertTrue(pending.invoice.payload.isNotBlank())
            coVerify { repo.createInitiated(null, "PROVIDER", "RUB", 20000L, pending.invoice.payload, "idem") }
        }

    @Test
    fun `should confirm without payment when mode none`() =
        runTest {
            val summary =
                BookingSummary(UUID.randomUUID(), 1, 1, 1, 1, 1, BigDecimal(50), "BOOKED", Instant.now(), "qr")
            coEvery { bookingService.confirm(any(), any()) } returns Either.Right(summary)
            val input = ConfirmInput(1, Instant.now(), 1, 1, 1, BigDecimal(50))
            val policy = PaymentPolicy(mode = PaymentMode.NONE)
            val res = service.startConfirmation(input, null, policy, "idem2")
            assertTrue(res is Either.Right)
            val confirmed = (res as Either.Right).value as ConfirmResult.Confirmed
            assertEquals(summary, confirmed.booking)
        }

    @Test
    fun `stars mode uses XTR currency`() =
        runTest {
            val input = ConfirmInput(1, Instant.now(), 1, 1, 3, BigDecimal(1))
            val policy = PaymentPolicy(mode = PaymentMode.STARS_DIGITAL)
            val res = service.startConfirmation(input, null, policy, "idem3")
            assertTrue(res is Either.Right)
            val pending = (res as Either.Right).value as ConfirmResult.PendingPayment
            assertEquals("XTR", pending.invoice.currency)
            coVerify { repo.createInitiated(null, "STARS", "XTR", 3L, pending.invoice.payload, "idem3") }
        }
}
