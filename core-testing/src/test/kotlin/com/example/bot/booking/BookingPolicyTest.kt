package com.example.bot.booking

import com.example.bot.data.booking.InMemoryBookingRepository
import com.example.bot.data.outbox.InMemoryOutboxService
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant
import com.example.bot.booking.legacy.BookingError as LegacyBookingError
import com.example.bot.booking.legacy.BookingService as LegacyBookingService
import com.example.bot.booking.legacy.ConfirmRequest as LegacyConfirmRequest
import com.example.bot.booking.legacy.Either as LegacyEither

class BookingPolicyTest :
    StringSpec({
        "cannot exceed table capacity" {
            val repo = InMemoryBookingRepository()
            val outbox = InMemoryOutboxService()
            val service = LegacyBookingService(repo, repo, outbox)
            val event = EventDto(1, 1, Instant.now(), Instant.now().plusSeconds(3600))
            val table = TableDto(1, 1, 2, BigDecimal("10"), true)
            repo.seed(event, table)
            val req = LegacyConfirmRequest(null, 1, event.startUtc, 1, 5, null, null, null)
            val res = service.confirm(req, "k")
            (res as LegacyEither.Left).value shouldBe LegacyBookingError.Validation("capacity exceeded")
        }
    })
