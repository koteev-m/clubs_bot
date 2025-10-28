package com.example.bot.booking

import com.example.bot.data.booking.InMemoryBookingRepository
import com.example.bot.data.outbox.InMemoryOutboxService
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant
import com.example.bot.booking.legacy.BookingService as LegacyBookingService
import com.example.bot.booking.legacy.ConfirmRequest as LegacyConfirmRequest

class OutboxIntegrationTest :
    StringSpec({
        "confirm enqueues outbox record" {
            val repo = InMemoryBookingRepository()
            val outbox = InMemoryOutboxService()
            val service = LegacyBookingService(repo, repo, outbox)
            val event = EventDto(1, 1, Instant.now(), Instant.now().plusSeconds(3600))
            val table = TableDto(1, 1, 2, BigDecimal("10"), true)
            repo.seed(event, table)
            val req = LegacyConfirmRequest(null, 1, event.startUtc, 1, 1, null, null, null)
            service.confirm(req, "k")
            outbox.items.size shouldBe 1
        }
    })
