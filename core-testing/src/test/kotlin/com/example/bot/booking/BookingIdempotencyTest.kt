package com.example.bot.booking

import com.example.bot.data.booking.InMemoryBookingRepository
import com.example.bot.data.outbox.InMemoryOutboxService
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant
import com.example.bot.booking.legacy.BookingService as LegacyBookingService
import com.example.bot.booking.legacy.ConfirmRequest as LegacyConfirmRequest
import com.example.bot.booking.legacy.Either as LegacyEither

class BookingIdempotencyTest :
    StringSpec({
        "repeated confirm with same key returns same booking" {
            val repo = InMemoryBookingRepository()
            val outbox = InMemoryOutboxService()
            val service = LegacyBookingService(repo, repo, outbox)
            val event = EventDto(1, 1, Instant.parse("2025-01-01T20:00:00Z"), Instant.parse("2025-01-01T23:00:00Z"))
            val table = TableDto(1, 1, 4, BigDecimal("10"), true)
            repo.seed(event, table)
            val req = LegacyConfirmRequest(null, 1, event.startUtc, 1, 2, null, null, null)
            val first = service.confirm(req, "key")
            val second = service.confirm(req, "key")
            (first as LegacyEither.Right).value.id shouldBe (second as LegacyEither.Right).value.id
        }
    })
