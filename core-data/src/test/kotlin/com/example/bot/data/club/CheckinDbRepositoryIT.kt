package com.example.bot.data.club

import com.example.bot.club.CheckinMethod
import com.example.bot.club.CheckinResultStatus
import com.example.bot.club.CheckinSubjectType
import com.example.bot.data.booking.BookingStatus
import com.example.bot.data.booking.BookingsTable
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

class CheckinDbRepositoryIT : PostgresClubIntegrationTest() {
    private val clock = Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC)
    private val checkinRepo by lazy { CheckinDbRepository(database, clock) }

    @Test
    fun `insert with booking update does not override status outside allowed set`() =
        runBlocking {
            val clubId = insertClub("Club")
            val eventId = insertEvent(
                clubId = clubId,
                title = "Event",
                startAt = Instant.parse("2025-01-02T20:00:00Z"),
                endAt = Instant.parse("2025-01-03T02:00:00Z"),
            )
            val tableNumber = 7
            val tableId = insertTable(
                clubId = clubId,
                tableNumber = tableNumber,
                capacity = 4,
                minDeposit = BigDecimal("100.00"),
            )
            val bookingId = UUID.randomUUID()
            val slotStart = Instant.parse("2025-01-02T20:00:00Z")
            val slotEnd = Instant.parse("2025-01-03T02:00:00Z")
            val timestamp = OffsetDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC)

            transaction(database) {
                BookingsTable.insert {
                    it[id] = bookingId
                    it[BookingsTable.eventId] = eventId
                    it[BookingsTable.clubId] = clubId
                    it[BookingsTable.tableId] = tableId
                    it[BookingsTable.tableNumber] = tableNumber
                    it[BookingsTable.guestUserId] = null
                    it[BookingsTable.guestName] = null
                    it[BookingsTable.phoneE164] = null
                    it[BookingsTable.promoterUserId] = null
                    it[BookingsTable.guestsCount] = 2
                    it[BookingsTable.minDeposit] = BigDecimal("100.00")
                    it[BookingsTable.totalDeposit] = BigDecimal("100.00")
                    it[BookingsTable.slotStart] = slotStart.atOffset(ZoneOffset.UTC)
                    it[BookingsTable.slotEnd] = slotEnd.atOffset(ZoneOffset.UTC)
                    it[BookingsTable.arrivalBy] = null
                    it[BookingsTable.status] = BookingStatus.CANCELLED.name
                    it[BookingsTable.qrSecret] = UUID.randomUUID().toString().replace("-", "")
                    it[BookingsTable.idempotencyKey] = UUID.randomUUID().toString()
                    it[BookingsTable.createdAt] = timestamp
                    it[BookingsTable.updatedAt] = timestamp
                }
            }

            val checkin =
                NewCheckin(
                    clubId = clubId,
                    eventId = eventId,
                    subjectType = CheckinSubjectType.BOOKING,
                    subjectId = bookingId.toString(),
                    checkedBy = null,
                    method = CheckinMethod.QR,
                    resultStatus = CheckinResultStatus.ARRIVED,
                    denyReason = null,
                    occurredAt = Instant.now(clock),
                )

            val inserted =
                checkinRepo.insertWithBookingUpdate(
                    checkin = checkin,
                    bookingId = bookingId,
                    bookingStatus = BookingStatus.SEATED,
                    allowedFromStatuses = setOf(BookingStatus.BOOKED),
                )

            val stored = checkinRepo.findBySubject(CheckinSubjectType.BOOKING, bookingId.toString())
            assertNotNull(stored)
            assertEquals(inserted.id, stored?.id)

            val bookingStatus =
                transaction(database) {
                    BookingsTable
                        .selectAll()
                        .where { BookingsTable.id eq bookingId }
                        .single()[BookingsTable.status]
                }
            assertEquals(BookingStatus.CANCELLED.name, bookingStatus)
        }
}
