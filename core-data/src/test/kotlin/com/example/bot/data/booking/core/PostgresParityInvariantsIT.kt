package com.example.bot.data.booking.core

import com.example.bot.data.booking.BookingHoldsTable
import com.example.bot.data.booking.BookingStatus
import com.example.bot.data.booking.BookingsTable
import com.example.bot.data.booking.EventsTable
import com.example.bot.data.booking.TablesTable
import com.example.bot.data.db.Clubs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class PostgresParityInvariantsIT : PostgresIntegrationTest() {
    private val now: Instant = Instant.parse("2025-03-01T19:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `partial unique index forbids second active booking for same slot`() {
        val seed = seedData(tableNumber = 21)

        transaction(database) {
            insertBooking(seed = seed, bookingId = UUID(0L, 1001L), status = BookingStatus.BOOKED)

            assertThrows<ExposedSQLException> {
                insertBooking(seed = seed, bookingId = UUID(0L, 1002L), status = BookingStatus.SEATED)
            }
        }
    }

    @Test
    fun `checkins uniqueness forbids duplicate check in for one subject`() {
        transaction(database) {
            exec(
                """
                INSERT INTO checkins (subject_type, subject_id, method, result_status, deny_reason)
                VALUES ('BOOKING', 'booking-subject-1', 'QR', 'ARRIVED', NULL)
                """.trimIndent(),
            )

            assertThrows<ExposedSQLException> {
                exec(
                    """
                    INSERT INTO checkins (subject_type, subject_id, method, result_status, deny_reason)
                    VALUES ('BOOKING', 'booking-subject-1', 'QR', 'DENIED', 'duplicate-scan')
                    """.trimIndent(),
                )
            }
        }
    }

    @Test
    fun `hold creation is serialized by slot lock on postgres`() =
        runBlocking {
            val seed = seedData(tableNumber = 22)
            val holdRepository = BookingHoldRepository(database, clock)

            val outcomes =
                (1..2)
                    .map { idx ->
                        async {
                            holdRepository.createHold(
                                tableId = seed.tableId,
                                slotStart = seed.slotStart,
                                slotEnd = seed.slotEnd,
                                guestsCount = 2,
                                ttl = Duration.ofMinutes(20),
                                idempotencyKey = "hold-lock-$idx",
                            )
                        }
                    }.awaitAll()

            val successCount = outcomes.count { it is BookingCoreResult.Success }
            val activeHoldExistsCount =
                outcomes.count {
                    it is BookingCoreResult.Failure && it.error == BookingCoreError.ActiveHoldExists
                }

            assertEquals(1, successCount)
            assertEquals(1, activeHoldExistsCount)

            val holdCount =
                transaction(database) {
                    BookingHoldsTable
                        .selectAll()
                        .andWhere { BookingHoldsTable.tableId eq seed.tableId }
                        .andWhere {
                            BookingHoldsTable.slotStart eq OffsetDateTime.ofInstant(seed.slotStart, ZoneOffset.UTC)
                        }.andWhere {
                            BookingHoldsTable.slotEnd eq OffsetDateTime.ofInstant(seed.slotEnd, ZoneOffset.UTC)
                        }.count()
                }
            assertEquals(1, holdCount)
            assertTrue(outcomes.any { it is BookingCoreResult.Success })
        }

    private fun seedData(tableNumber: Int): SeedContext {
        val slotStart = Instant.parse("2025-03-02T20:00:00Z")
        val slotEnd = Instant.parse("2025-03-03T02:00:00Z")

        return transaction(database) {
            val clubIdEntity =
                Clubs.insert {
                    it[name] = "Parity Club"
                    it[description] = "Parity"
                    it[timezone] = "UTC"
                } get Clubs.id
            val clubId = clubIdEntity.value.toLong()
            val tableId =
                TablesTable.insert {
                    it[TablesTable.clubId] = clubId
                    it[zoneId] = null
                    it[TablesTable.tableNumber] = tableNumber
                    it[capacity] = 4
                    it[minDeposit] = BigDecimal("100.00")
                    it[active] = true
                } get TablesTable.id

            val eventId =
                EventsTable.insert {
                    it[EventsTable.clubId] = clubId
                    it[startAt] = OffsetDateTime.ofInstant(slotStart, ZoneOffset.UTC)
                    it[endAt] = OffsetDateTime.ofInstant(slotEnd, ZoneOffset.UTC)
                    it[title] = "Parity Night"
                    it[isSpecial] = false
                    it[posterUrl] = null
                } get EventsTable.id

            SeedContext(
                clubId = clubId,
                tableId = tableId,
                eventId = eventId,
                slotStart = slotStart,
                slotEnd = slotEnd,
            )
        }
    }

    private fun insertBooking(
        seed: SeedContext,
        bookingId: UUID,
        status: BookingStatus,
    ) {
        val timestamp = OffsetDateTime.ofInstant(now, ZoneOffset.UTC)
        BookingsTable.insert {
            it[id] = bookingId
            it[eventId] = seed.eventId
            it[clubId] = seed.clubId
            it[tableId] = seed.tableId
            it[tableNumber] = 1
            it[guestUserId] = null
            it[guestName] = null
            it[phoneE164] = null
            it[promoterUserId] = null
            it[guestsCount] = 2
            it[minDeposit] = BigDecimal("100.00")
            it[totalDeposit] = BigDecimal("100.00")
            it[slotStart] = seed.slotStart.atOffset(ZoneOffset.UTC)
            it[slotEnd] = seed.slotEnd.atOffset(ZoneOffset.UTC)
            it[arrivalBy] = null
            it[BookingsTable.status] = status.name
            it[qrSecret] = UUID.randomUUID().toString().replace("-", "")
            it[idempotencyKey] = UUID.randomUUID().toString()
            it[createdAt] = timestamp
            it[updatedAt] = timestamp
        }
    }

    private data class SeedContext(
        val clubId: Long,
        val tableId: Long,
        val eventId: Long,
        val slotStart: Instant,
        val slotEnd: Instant,
    )
}
