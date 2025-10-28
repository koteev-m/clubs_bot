package com.example.bot.booking

import com.example.bot.data.booking.InMemoryBookingRepository
import com.example.bot.data.outbox.InMemoryOutboxService
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.math.BigDecimal
import java.time.Instant
import com.example.bot.booking.legacy.BookingError as LegacyBookingError
import com.example.bot.booking.legacy.BookingService as LegacyBookingService
import com.example.bot.booking.legacy.ConfirmRequest as LegacyConfirmRequest
import com.example.bot.booking.legacy.Either as LegacyEither

class BookingRaceTest :
    StringSpec({
        "two parallel confirms, only one succeeds" {
            val repo = InMemoryBookingRepository()
            val outbox = InMemoryOutboxService()
            val service = LegacyBookingService(repo, repo, outbox)
            val event = EventDto(1, 1, Instant.parse("2025-01-01T20:00:00Z"), Instant.parse("2025-01-01T23:00:00Z"))
            val table = TableDto(1, 1, 4, BigDecimal("10"), true)
            repo.seed(event, table)

            val req = LegacyConfirmRequest(null, 1, event.startUtc, 1, 2, null, null, null)
            coroutineScope {
                val a = async { service.confirm(req, "a") }
                val b = async { service.confirm(req, "b") }
                val r1 = a.await()
                val r2 = b.await()
                val successes = listOf(r1, r2).count { it is LegacyEither.Right }
                successes shouldBe 1
                val conflicts =
                    listOf(
                        r1,
                        r2,
                    ).count { (it as? LegacyEither.Left)?.value is LegacyBookingError.Conflict }
                conflicts shouldBe 1
            }
        }
    })
