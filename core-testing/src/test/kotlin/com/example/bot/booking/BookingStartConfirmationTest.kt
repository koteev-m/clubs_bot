package com.example.bot.booking

import com.example.bot.booking.legacy.startConfirmation
import com.example.bot.data.booking.InMemoryBookingRepository
import com.example.bot.data.outbox.InMemoryOutboxService
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant
import com.example.bot.booking.legacy.BookingService as LegacyBookingService
import com.example.bot.booking.legacy.ConfirmInput as LegacyConfirmInput
import com.example.bot.booking.legacy.ConfirmResult as LegacyConfirmResult
import com.example.bot.booking.legacy.Either as LegacyEither

class BookingStartConfirmationTest :
    StringSpec({
        "start confirmation returns invoice when payment required" {
            val repo = InMemoryBookingRepository()
            val outbox = InMemoryOutboxService()
            val service = LegacyBookingService(repo, repo, outbox)
            val event = EventDto(1, 1, Instant.parse("2025-01-01T20:00:00Z"), Instant.parse("2025-01-01T23:00:00Z"))
            val table = TableDto(1, 10, 4, BigDecimal("10"), true)
            repo.seed(event, table)
            val input = LegacyConfirmInput(1, event.startUtc, table.id, table.number, 2, table.minDeposit)
            val policy = PaymentPolicy(PaymentMode.PROVIDER_DEPOSIT)
            val result = service.startConfirmation(input, null, policy, "idem1")
            val pending =
                (result as LegacyEither.Right<LegacyConfirmResult>)
                    .value as LegacyConfirmResult.PendingPayment
            pending.invoice.currency shouldBe "RUB"
            pending.invoice.totalMinor shouldBe 2000
        }

        "start confirmation with none confirms immediately" {
            val repo = InMemoryBookingRepository()
            val outbox = InMemoryOutboxService()
            val service = LegacyBookingService(repo, repo, outbox)
            val event = EventDto(1, 1, Instant.parse("2025-01-01T20:00:00Z"), Instant.parse("2025-01-01T23:00:00Z"))
            val table = TableDto(1, 10, 4, BigDecimal("10"), true)
            repo.seed(event, table)
            val input = LegacyConfirmInput(1, event.startUtc, table.id, table.number, 2, table.minDeposit)
            val policy = PaymentPolicy(PaymentMode.NONE)
            val result = service.startConfirmation(input, null, policy, "idem2")
            val booking =
                (result as LegacyEither.Right<LegacyConfirmResult>)
                    .value as LegacyConfirmResult.Confirmed
            booking.booking.status shouldBe "BOOKED"
        }
    })
