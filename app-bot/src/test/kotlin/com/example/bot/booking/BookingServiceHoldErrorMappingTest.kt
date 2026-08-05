package com.example.bot.booking

import com.example.bot.audit.AuditLogEvent
import com.example.bot.audit.AuditLogRepository
import com.example.bot.audit.AuditLogger
import com.example.bot.audit.CustomAuditAction
import com.example.bot.audit.StandardAuditEntityType
import com.example.bot.availability.AvailabilityCacheInvalidator
import com.example.bot.data.booking.core.BookingCoreError
import com.example.bot.data.booking.core.BookingCoreResult
import com.example.bot.data.booking.core.BookingHoldRepository
import com.example.bot.data.booking.core.BookingRepository
import com.example.bot.data.booking.core.OutboxRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertFailsWith

class BookingServiceHoldErrorMappingTest {
    @Test
    fun `hold maps duplicate active booking to domain conflict`() =
        runTest {
            val fixture = fixture(BookingCoreError.DuplicateActiveBooking)
            val auditEventSlot = slot<AuditLogEvent>()

            val result = fixture.service.hold(REQUEST, IDEMPOTENCY_KEY)

            assertEquals(BookingCmdResult.DuplicateActiveBooking, result)
            coVerify(exactly = 1) { fixture.holdRepository.createHoldForRequest() }
            coVerify(exactly = 1) { fixture.auditRepository.append(capture(auditEventSlot)) }
            with(auditEventSlot.captured) {
                assertEquals(REQUEST.clubId, clubId)
                assertEquals(StandardAuditEntityType.BOOKING, entityType)
                assertEquals(CustomAuditAction("booking.hold"), action)
                assertEquals(JsonPrimitive("duplicate_active"), metadata?.jsonObject?.get("result"))
            }
            verify(exactly = 0) { fixture.cacheInvalidator.invalidateTables(any(), any()) }
        }

    @Test
    fun `hold keeps unexpected repository failures fail closed`() =
        runTest {
            val fixture = fixture(BookingCoreError.UnexpectedFailure)

            val failure =
                assertFailsWith<IllegalStateException> {
                    fixture.service.hold(REQUEST, IDEMPOTENCY_KEY)
                }

            assertEquals("unexpected hold error: UnexpectedFailure", failure.message)
            coVerify(exactly = 1) { fixture.holdRepository.createHoldForRequest() }
            coVerify(exactly = 1) { fixture.auditRepository.append(any()) }
            verify(exactly = 0) { fixture.cacheInvalidator.invalidateTables(any(), any()) }
        }

    private fun fixture(error: BookingCoreError): Fixture {
        val bookingRepository = mockk<BookingRepository>()
        val holdRepository = mockk<BookingHoldRepository>()
        val outboxRepository = mockk<OutboxRepository>()
        val auditRepository = mockk<AuditLogRepository>(relaxed = true)
        val cacheInvalidator = mockk<AvailabilityCacheInvalidator>(relaxed = true)

        coEvery { holdRepository.findHoldByIdempotencyKey(IDEMPOTENCY_KEY) } returns null
        coEvery {
            bookingRepository.existsActiveFor(
                REQUEST.tableId,
                REQUEST.slotStart,
                REQUEST.slotEnd,
            )
        } returns false
        coEvery { holdRepository.createHoldForRequest() } returns BookingCoreResult.Failure(error)

        return Fixture(
            service =
                BookingService(
                    bookingRepository = bookingRepository,
                    holdRepository = holdRepository,
                    outboxRepository = outboxRepository,
                    auditLogger = AuditLogger(auditRepository),
                    availabilityCacheInvalidator = cacheInvalidator,
                ),
            holdRepository = holdRepository,
            auditRepository = auditRepository,
            cacheInvalidator = cacheInvalidator,
        )
    }

    private suspend fun BookingHoldRepository.createHoldForRequest() =
        createHold(
            tableId = REQUEST.tableId,
            slotStart = REQUEST.slotStart,
            slotEnd = REQUEST.slotEnd,
            guestsCount = REQUEST.guestsCount,
            ttl = REQUEST.ttl,
            idempotencyKey = IDEMPOTENCY_KEY,
        )

    private data class Fixture(
        val service: BookingService,
        val holdRepository: BookingHoldRepository,
        val auditRepository: AuditLogRepository,
        val cacheInvalidator: AvailabilityCacheInvalidator,
    )

    private companion object {
        const val IDEMPOTENCY_KEY = "hold-duplicate-active"
        val REQUEST =
            HoldRequest(
                clubId = 77,
                tableId = 14,
                slotStart = Instant.parse("2025-04-02T18:00:00Z"),
                slotEnd = Instant.parse("2025-04-02T21:00:00Z"),
                guestsCount = 2,
                ttl = Duration.ofMinutes(15),
            )
    }
}
