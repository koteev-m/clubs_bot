package com.example.bot.data.booking.core

import com.example.bot.data.booking.BookingStatus
import com.example.bot.data.booking.EventsTable
import com.example.bot.data.booking.TablesTable
import com.example.bot.data.db.Clubs
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import testing.RequiresDocker
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

@RequiresDocker
@Tag("it")
class BookingRepositoryIT : PostgresIntegrationTest() {
    private val clock = Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val bookingRepo by lazy { BookingRepository(database, clock) }

    @Test
    fun `create booking and fetch by idempotency`() =
        runBlocking {
            val context = seedData()
            val result =
                bookingRepo.createBooked(
                    clubId = context.clubId,
                    tableId = context.tableId,
                    slotStart = context.slotStart,
                    slotEnd = context.slotEnd,
                    guests = 2,
                    minRate = BigDecimal("150.00"),
                    idempotencyKey = "idem-1",
                )
            val created = (result as BookingCoreResult.Success).value
            assertEquals("idem-1", created.idempotencyKey)
            assertEquals(BookingStatus.BOOKED, created.status)
            assertEquals(context.tableId, created.tableId)

            val found =
                bookingRepo.findByIdempotencyKey("idem-1")
                    ?: fail("Booking was not found by idempotency key")
            assertEquals(created.id, found.id)
        }

    @Test
    fun `active booking uniqueness enforced`() =
        runBlocking {
            val context = seedData()
            val first =
                bookingRepo.createBooked(
                    clubId = context.clubId,
                    tableId = context.tableId,
                    slotStart = context.slotStart,
                    slotEnd = context.slotEnd,
                    guests = 2,
                    minRate = BigDecimal("100.00"),
                    idempotencyKey = "dup-1",
                )
            assertTrue(first is BookingCoreResult.Success)
            val duplicate =
                bookingRepo.createBooked(
                    clubId = context.clubId,
                    tableId = context.tableId,
                    slotStart = context.slotStart,
                    slotEnd = context.slotEnd,
                    guests = 2,
                    minRate = BigDecimal("100.00"),
                    idempotencyKey = "dup-2",
                )
            assertEquals(BookingCoreError.DuplicateActiveBooking, (duplicate as BookingCoreResult.Failure).error)
        }

    @Test
    fun `set status updates record`() =
        runBlocking {
            val context = seedData()
            val created =
                bookingRepo.createBooked(
                    clubId = context.clubId,
                    tableId = context.tableId,
                    slotStart = context.slotStart,
                    slotEnd = context.slotEnd,
                    guests = 4,
                    minRate = BigDecimal("200.00"),
                    idempotencyKey = "status-1",
                ) as BookingCoreResult.Success
            val updated = bookingRepo.setStatus(created.value.id, BookingStatus.CANCELLED) as BookingCoreResult.Success
            assertEquals(BookingStatus.CANCELLED, updated.value.status)
            val exists = bookingRepo.existsActiveFor(context.tableId, context.slotStart, context.slotEnd)
            assertFalse(exists)
        }

    private fun seedData(): SeedContext {
        val slotStart = Instant.parse("2025-01-02T20:00:00Z")
        val slotEnd = Instant.parse("2025-01-03T02:00:00Z")
        return transaction(database) {
            val clubIdEntity =
                Clubs.insert {
                    it[name] = "Club"
                    it[description] = "Test club"
                    it[timezone] = "UTC"
                } get Clubs.id
            val clubId = clubIdEntity.value.toLong()
            val tableId =
                TablesTable.insert {
                    it[TablesTable.clubId] = clubId
                    it[zoneId] = null
                    it[tableNumber] = 1
                    it[capacity] = 4
                    it[minDeposit] = BigDecimal("100.00")
                    it[active] = true
                } get TablesTable.id
            EventsTable.insert {
                it[EventsTable.clubId] = clubId
                it[startAt] = OffsetDateTime.ofInstant(slotStart, ZoneOffset.UTC)
                it[endAt] = OffsetDateTime.ofInstant(slotEnd, ZoneOffset.UTC)
                it[title] = "Party"
                it[isSpecial] = false
                it[posterUrl] = null
            }
            SeedContext(
                clubId = clubId,
                tableId = tableId,
                slotStart = slotStart,
                slotEnd = slotEnd,
            )
        }
    }

    private data class SeedContext(
        val clubId: Long,
        val tableId: Long,
        val slotStart: Instant,
        val slotEnd: Instant,
    )
}
