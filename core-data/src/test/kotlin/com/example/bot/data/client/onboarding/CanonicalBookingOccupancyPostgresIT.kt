package com.example.bot.data.client.onboarding

import com.example.bot.data.booking.AllocationInput
import com.example.bot.data.booking.CanonicalTableOperations
import com.example.bot.data.booking.PhysicalTableKey
import com.example.bot.data.booking.TableDepositAmount
import com.example.bot.data.booking.TableOperationResult
import com.example.bot.data.booking.TableOperationsScope
import com.example.bot.data.booking.core.BookingCoreError
import com.example.bot.data.booking.core.BookingCoreResult
import com.example.bot.data.booking.core.BookingHoldRepository
import com.example.bot.data.booking.core.BookingRepository
import com.example.bot.data.booking.core.CanonicalBookingScope
import kotlinx.coroutines.async
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.sql.Connection
import java.time.Duration
import java.time.Instant

@Timeout(30)
internal class CanonicalBookingOccupancyPostgresIT : NightLockPostgresFixture() {
    @ParameterizedTest
    @CsvSource("true,false", "true,true", "false,false", "false,true")
    fun `protected seating and reservation serialize under the same club lock`(
        seatFirst: Boolean,
        confirm: Boolean,
    ) = scenario(
        if (seatFirst) {
            Gate.SESSION
        } else if (confirm) {
            Gate.BOOKING
        } else {
            Gate.HOLD
        },
    ) { control ->
        control.insertEvent()
        val scope = linkedScope()
        control.draftShift()
        val holdRepo = BookingHoldRepository(bookingDb, NIGHT_CLOCK)
        val bookingRepo = BookingRepository(bookingDb, NIGHT_CLOCK)
        // Only an expired HOLD permits protected seating to win against confirm.
        val prior = if (confirm) protectedHold(scope, if (seatFirst) holds else holdRepo).success() else null
        val reservation: suspend () -> BookingCoreResult<*> = {
            if (prior == null) {
                protectedHold(scope, holdRepo)
            } else {
                bookingRepo.confirmCanonicalHold(scope, prior.id, CONFIRM_KEY)
            }
        }
        if (seatFirst) {
            val seat = async { seat(scope) }
            val leaderPid = control.awaitBlocked(SEAT, control.pid(), "INSERT INTO table_sessions")
            val book = async { reservation() }
            val followerPid = control.awaitBlocked(BOOKING, leaderPid, "SELECT is_active,timezone")
            assertNotEquals(leaderPid, followerPid)
            control.assertOccupancyCounts(0, 0, 0, if (confirm) 1 else 0, if (confirm) 1 else 0)
            control.release(Gate.SESSION)
            assertTrue(seat.await() is TableOperationResult.Success)
            assertEquals(BookingCoreResult.Failure(BookingCoreError.TableOccupied), book.await())
            control.assertOccupancyCounts(1, 1, 0, if (confirm) 1 else 0, if (confirm) 1 else 0)
            assertEquals(TABLE.toString(), control.scalar("SELECT table_id FROM table_sessions"))
            assertEquals(START, control.instant("SELECT night_start_utc FROM table_sessions"))
            if (prior != null) assertEquals(prior.id.toString(), control.scalar("SELECT id FROM booking_holds"))
        } else {
            val book = async { reservation() }
            val leaderPid = control.awaitBlocked(BOOKING, control.pid())
            control.assertSlotLock(leaderPid)
            val seat = async { seat(scope) }
            val followerPid = control.awaitBlocked(SEAT, leaderPid, "SELECT timezone FROM clubs")
            assertNotEquals(leaderPid, followerPid)
            control.assertOccupancyCounts(0, 0, 0, if (confirm) 1 else 0, if (confirm) 1 else 0)
            control.release(if (confirm) Gate.BOOKING else Gate.HOLD)
            book.await().success()
            assertEquals(TableOperationResult.Rejected.OCCUPIED, seat.await())
            control.assertOccupancyCounts(0, 0, if (confirm) 1 else 0, if (confirm) 0 else 1, if (confirm) 2 else 1)
        }
    }

    @Test
    fun `seating takes the lock first but an existing live hold lets confirm win`() =
        scenario { control ->
            control.insertEvent()
            val scope = linkedScope()
            control.draftShift()
            val hold = protectedHold(scope, BookingHoldRepository(bookingDb, NIGHT_CLOCK)).success()
            val bookingRepo = BookingRepository(bookingDb, NIGHT_CLOCK)
            source("shift-gate").connection.use { shift ->
                shift.autoCommit = false
                shift.exec("SELECT id FROM shift_reports WHERE club_id=$CLUB FOR UPDATE")
                val first = async { seat(scope) }
                val confirm =
                    try {
                        val seatPid = control.awaitBlocked(SEAT, shift.pid(), "SELECT status FROM shift_reports")
                        val next = async { bookingRepo.confirmCanonicalHold(scope, hold.id, CONFIRM_KEY) }
                        assertNotEquals(seatPid, control.awaitBlocked(BOOKING, seatPid, "SELECT is_active,timezone"))
                        control.assertOccupancyCounts(0, 0, 0, 1, 1)
                        next
                    } finally {
                        shift.rollback()
                    }
                assertEquals(TableOperationResult.Rejected.OCCUPIED, first.await())
                assertConfirmation(control, confirm.await().success())
                control.assertOccupancyCounts(0, 0, 1, 0, 2)
            }
        }

    @Test
    fun `seat audit failure rolls back session and deposit before protected hold proceeds`() =
        scenario(Gate.SESSION) { control ->
            control.insertEvent()
            val scope = linkedScope()
            control.draftShift()
            control.exec("ALTER TABLE audit_log ADD CONSTRAINT reject_test_seat CHECK (entity_type <> 'TABLE_SESSION')")
            try {
                val seat = async { assertWriteFailure { seat(scope) } }
                val seatPid = control.awaitBlocked(SEAT, control.pid(), "INSERT INTO table_sessions")
                val book = async { protectedHold(scope, BookingHoldRepository(bookingDb, NIGHT_CLOCK)) }
                assertNotEquals(seatPid, control.awaitBlocked(BOOKING, seatPid, "SELECT is_active,timezone"))
                control.release(Gate.SESSION)
                seat.await()
                assertSlot(book.await().success())
                control.assertOccupancyCounts(0, 0, 0, 1, 1)
            } finally {
                control.exec("ALTER TABLE audit_log DROP CONSTRAINT reject_test_seat")
            }
        }

    @Test
    fun `legacy hold writer can bypass the protected occupancy contract`() =
        scenario(Gate.SESSION) { control ->
            control.insertEvent()
            val scope = linkedScope()
            control.draftShift()
            val seat = async { seat(scope) }
            val seatPid = control.awaitBlocked(SEAT, control.pid(), "INSERT INTO table_sessions")
            val legacy =
                async { hold(BookingHoldRepository(Database.connect(source("legacy-occupancy")), NIGHT_CLOCK)) }
            assertNotEquals(seatPid, control.awaitBlocked("legacy-occupancy", seatPid, "INSERT INTO booking_holds"))
            control.release(Gate.SESSION)
            assertTrue(seat.await() is TableOperationResult.Success)
            assertSlot(legacy.await().success())
            control.assertOccupancyCounts(1, 1, 0, 1, 0)
        }

    @Test
    fun `open legacy session blocks fresh commands and confirmed receipts survive subsequent seating`() =
        scenario { control ->
            control.insertEvent()
            val scope = linkedScope()
            val hold = protectedHold(scope).success()
            control.insertSession(TABLE, START)
            val occupied = BookingCoreResult.Failure(BookingCoreError.TableOccupied)
            assertEquals(occupied, protectedHold(scope, key = "another-hold"))
            assertEquals(occupied, bookings.confirmCanonicalHold(scope, hold.id, CONFIRM_KEY))
            assertEquals(hold, protectedHold(scope).success())
            control.assertOccupancyCounts(1, 0, 0, 1, 1, audit = 0)
            assertEquals(hold.id.toString(), control.scalar("SELECT id FROM booking_holds"))
            control.exec(
                "UPDATE table_sessions SET status='CLOSED',open_marker=NULL,closed_at='$START',closed_by=1",
            )
            val booked = bookings.confirmCanonicalHold(scope, hold.id, CONFIRM_KEY).success()
            control.insertSession(TABLE, START)
            val restarted = BookingRepository(Database.connect(source("replayed-occupancy")), CLOCK)
            assertEquals(booked.id, restarted.confirmCanonicalHold(scope, hold.id, CONFIRM_KEY).success().id)
            assertEquals(
                BookingCoreResult.Failure(BookingCoreError.IdempotencyConflict),
                restarted.confirmCanonicalHold(scope, java.util.UUID.randomUUID(), CONFIRM_KEY),
            )
            control.exec(
                "UPDATE client_club_grants SET is_active=FALSE " +
                    "WHERE club_id=$CLUB AND user_id=1 AND permission_code='client.club.calendar.write'",
            )
            assertEquals(
                BookingCoreResult.Failure(BookingCoreError.HoldNotFound),
                restarted.confirmCanonicalHold(scope, hold.id, CONFIRM_KEY),
            )
            control.assertOccupancyCounts(2, 0, 1, 0, 2, audit = 0)
        }

    @Test
    fun `same-number or unrelated sessions do not replace the stored physical identity`() =
        scenario { control ->
            control.insertEvent()
            val scope = linkedScope()
            control.exec("INSERT INTO clubs(id,name,timezone,is_active) VALUES (2,'Other','UTC',TRUE)")
            control.exec(
                "INSERT INTO tables(id,club_id,table_number,capacity,min_deposit) " +
                    "VALUES ($HALL_TABLE,2,1,4,0),(3,$CLUB,2,4,0)",
            )
            control.insertSession(3, START)
            control.insertSession(TABLE, START.minus(Duration.ofDays(1)))
            assertEquals(
                BookingCoreResult.Failure(BookingCoreError.TableNotFound),
                protectedHold(scope, tableId = HALL_TABLE),
            )
            assertEquals(BookingCoreResult.Failure(BookingCoreError.TableNotFound), protectedHold(scope, tableId = 3))
            val hold = protectedHold(scope).success()
            assertEquals(TABLE, hold.tableId)
            assertSlot(hold)
            control.assertOccupancyCounts(2, 0, 0, 1, 1, audit = 0)
        }

    @Test
    fun `protected slot waiter does not block legacy confirm foreign key checks`() =
        scenario(Gate.BOOKING) { control ->
            control.insertEvent()
            val scope = linkedScope()
            val hold = hold().success()
            val legacy =
                async {
                    BookingRepository(
                        Database.connect(source("legacy-confirm")),
                        CLOCK,
                    ).confirmFromHold(hold.id, CONFIRM_KEY)
                }
            val legacyPid = control.awaitBlocked("legacy-confirm", control.pid(), "INSERT INTO bookings")
            val waiter = async { protectedHold(scope) }
            assertNotEquals(legacyPid, control.awaitBlocked(BOOKING, legacyPid, "SELECT pg_advisory_xact_lock"))
            control.release(Gate.BOOKING)
            assertConfirmation(control, legacy.await().success())
            assertEquals(BookingCoreResult.Failure(BookingCoreError.DuplicateActiveBooking), waiter.await())
            control.assertOccupancyCounts(0, 0, 1, 0, 0)
        }

    private fun Connection.insertSession(
        tableId: Long,
        start: Instant,
    ) = exec(
        "INSERT INTO table_sessions(club_id,table_id,night_start_utc,status,open_marker,opened_at,opened_by) " +
            "VALUES ($CLUB,$tableId,'$start','OPEN',1,'$start',1)",
    )

    private suspend fun seat(scope: CanonicalBookingScope) =
        CanonicalTableOperations(Database.connect(source(SEAT)), NIGHT_CLOCK).seat(
            TableOperationsScope(CLUB, PhysicalTableKey(CLUB, 1, HALL_TABLE), scope.nightId, EVENT, START),
            actorId = 1,
            expectedLastSessionId = null,
            initialDeposit = TableDepositAmount(100, listOf(AllocationInput("BAR", 100))),
        )

    private fun Connection.draftShift() =
        exec(
            "INSERT INTO shift_reports(club_id,night_start_utc,status,created_at,updated_at) " +
                "VALUES ($CLUB,'$START','DRAFT','$START','$START')",
        )

    private fun Connection.assertOccupancyCounts(
        sessions: Int,
        deposits: Int,
        bookings: Int,
        holds: Int,
        receipts: Int,
        audit: Int = sessions + deposits,
    ) {
        val expected =
            mapOf(
                "table_sessions" to sessions,
                "table_deposits" to deposits,
                "table_deposit_operations" to deposits,
                "bookings" to bookings,
                "booking_holds" to holds,
                "canonical_booking_commands" to receipts,
                "booking_outbox" to 0,
            )
        for ((table, count) in expected) assertEquals(count.toString(), scalar("SELECT count(*) FROM $table"), table)
        assertEquals(
            audit.toString(),
            scalar("SELECT count(*) FROM audit_log WHERE entity_type IN ('TABLE_SESSION','TABLE_DEPOSIT')"),
        )
    }
}
