package com.example.bot.booking

import com.example.bot.audit.AuditLogRepository
import com.example.bot.audit.AuditLogger
import com.example.bot.data.booking.BookingStatus
import com.example.bot.data.booking.core.BookingCoreError
import com.example.bot.data.booking.core.BookingCoreResult
import com.example.bot.data.booking.core.BookingHoldRepository
import com.example.bot.data.booking.core.BookingRecord
import com.example.bot.data.booking.core.BookingRepository
import com.example.bot.data.booking.core.OutboxRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class BookingServiceConfirmFallbackTest {
    @Test
    fun `confirm returns already booked when hold not found but booking appears on fallback lookup`() =
        runTest {
            val bookingRepository = mockk<BookingRepository>()
            val holdRepository = mockk<BookingHoldRepository>()
            val outboxRepository = mockk<OutboxRepository>()
            val auditRepository = mockk<AuditLogRepository>(relaxed = true)
            val auditLogger = AuditLogger(auditRepository)
            val service =
                BookingService(
                    bookingRepository = bookingRepository,
                    holdRepository = holdRepository,
                    outboxRepository = outboxRepository,
                    auditLogger = auditLogger,
                )

            val holdId = UUID.randomUUID()
            val idemKey = "confirm-idem-fallback"
            val existingBooking = bookingRecord(idempotencyKey = idemKey)

            coEvery { bookingRepository.findByIdempotencyKey(idemKey) } returnsMany
                listOf(null, existingBooking)
            coEvery {
                bookingRepository.confirmFromHold(
                    holdId = holdId,
                    idempotencyKey = idemKey,
                    guestUserId = null,
                    promoterUserId = null,
                )
            } returns BookingCoreResult.Failure(BookingCoreError.HoldNotFound)

            val result = service.confirm(holdId, idemKey)

            assertEquals(BookingCmdResult.AlreadyBooked(existingBooking.id), result)
            coVerify(exactly = 2) { bookingRepository.findByIdempotencyKey(idemKey) }
        }

    private fun bookingRecord(idempotencyKey: String): BookingRecord =
        BookingRecord(
            id = UUID.randomUUID(),
            clubId = 77,
            tableId = 14,
            tableNumber = 14,
            eventId = 120,
            guests = 2,
            minRate = BigDecimal("5000"),
            totalRate = BigDecimal("5000"),
            slotStart = Instant.parse("2025-04-02T18:00:00Z"),
            slotEnd = Instant.parse("2025-04-02T21:00:00Z"),
            status = BookingStatus.BOOKED,
            arrivalBy = null,
            qrSecret = "secret",
            idempotencyKey = idempotencyKey,
            createdAt = Instant.parse("2025-04-01T10:00:00Z"),
            updatedAt = Instant.parse("2025-04-01T10:00:00Z"),
        )
}
