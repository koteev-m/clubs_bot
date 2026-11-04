package com.example.bot.data.client.onboarding

import com.example.bot.data.booking.BookingStatus
import com.example.bot.data.booking.CanonicalTableOperations
import com.example.bot.data.booking.PhysicalTableKey
import com.example.bot.data.booking.TableOperationResult
import com.example.bot.data.booking.TableOperationsScope
import com.example.bot.data.booking.TableSessionRepository
import com.example.bot.data.booking.core.BookingCoreError
import com.example.bot.data.booking.core.BookingCoreResult
import com.example.bot.data.booking.core.BookingHoldRepository
import com.example.bot.data.booking.core.BookingRepository
import com.example.bot.data.booking.core.LegacyTableSessionFencedException
import com.example.bot.data.booking.core.enableBookingFence
import kotlinx.coroutines.async
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigDecimal
import java.sql.Connection
import java.time.Duration

@Timeout(30)
internal class BookingCutoverFencePostgresIT : NightLockPostgresFixture() {
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `activation and an in-flight legacy HOLD cannot both commit`(fenceFirst: Boolean) =
        scenario(if (fenceFirst) Gate.FENCE else Gate.HOLD) { control ->
            control.insertEvent()
            val scope = linkedScope()
            val activationDb = Database.connect(source("fence"))
            if (fenceFirst) {
                val activation = async { enableBookingFence(activationDb, CLOCK, scope, TABLE, START, END) }
                val firstPid = control.awaitBlocked("fence", control.pid(), "INSERT INTO booking_cutover_fences")
                val old = async { hold() }
                assertNotEquals(firstPid, control.awaitBlocked(BOOKING, firstPid, "SELECT club_id FROM client_clubs"))
                control.release(Gate.FENCE)
                assertTrue(activation.await())
                assertEquals(FENCED, old.await())
                control.assertFenceRows(fences = 1, holds = 0, bookings = 0, sessions = 0)
            } else {
                val old = async { hold() }
                val firstPid = control.awaitBlocked(BOOKING, control.pid(), "INSERT INTO booking_holds")
                val activation = async { enableBookingFence(activationDb, CLOCK, scope, TABLE, START, END) }
                assertNotEquals(firstPid, control.awaitBlocked("fence", firstPid, "SELECT club_id FROM client_clubs"))
                control.release(Gate.HOLD)
                val existing = old.await().success()
                assertFalse(activation.await())
                assertEquals(existing.id.toString(), control.scalar("SELECT id FROM booking_holds"))
                control.assertFenceRows(fences = 0, holds = 1, bookings = 0, sessions = 0)
            }
        }

    @Test
    fun `protected seating excludes direct legacy HOLD and createBooked`() =
        scenario { control ->
            control.insertEvent()
            val scope = linkedScope()
            control.exec(
                "INSERT INTO shift_reports(club_id,night_start_utc,status,created_at,updated_at) " +
                    "VALUES ($CLUB,'$START','DRAFT','$START','$START')",
            )
            assertTrue(enableBookingFence(bookingDb, NIGHT_CLOCK, scope, TABLE, START, END))
            val seated =
                CanonicalTableOperations(bookingDb, NIGHT_CLOCK).seat(
                    TableOperationsScope(CLUB, PhysicalTableKey(CLUB, CLUB, HALL_TABLE), scope.nightId, EVENT, START),
                    actorId = 1,
                    expectedLastSessionId = null,
                )
            assertTrue(seated is TableOperationResult.Success)
            assertEquals(FENCED, hold(BookingHoldRepository(bookingDb, NIGHT_CLOCK)))
            assertEquals(
                FENCED,
                bookings.createBooked(CLUB, TABLE, START, END, 2, BigDecimal.ZERO, "legacy-book-fenced"),
            )
            control.assertFenceRows(fences = 1, holds = 0, bookings = 0, sessions = 1)
            assertEquals(TABLE.toString(), control.scalar("SELECT table_id FROM table_sessions"))
            assertEquals(START, control.instant("SELECT night_start_utc FROM table_sessions"))
        }

    @Test
    fun `protected confirm and replay reject legacy confirm and both legacy seat identities`() =
        scenario(Gate.BOOKING) { control ->
            control.insertEvent()
            val scope = linkedScope()
            assertTrue(enableBookingFence(bookingDb, CLOCK, scope, TABLE, START, END))
            val hold = protectedHold(scope).success()
            assertEquals(FENCED, bookings.confirmFromHold(hold.id, "legacy-confirm-fenced"))
            assertEquals(FENCED, holds.prolongHold(hold.id, Duration.ofMinutes(2)))
            assertEquals(FENCED, holds.consumeHold(hold.id))
            assertEquals(hold.id.toString(), control.scalar("SELECT id FROM booking_holds"))
            val first = async { bookings.confirmCanonicalHold(scope, hold.id, CONFIRM_KEY) }
            val confirmPid = control.awaitBlocked(BOOKING, control.pid(), "INSERT INTO bookings")
            val legacySessions = TableSessionRepository(Database.connect(source("legacy-seat")))
            for (id in listOf(TABLE, HALL_TABLE)) {
                val error =
                    runCatching {
                        legacySessions.openSession(
                            CLUB,
                            START,
                            id,
                            1,
                            START,
                            null,
                        )
                    }.exceptionOrNull()
                assertTrue(error is LegacyTableSessionFencedException)
            }
            assertNotEquals(confirmPid, control.pid())
            control.release(Gate.BOOKING)
            val confirmed = first.await().success()
            val restarted = BookingRepository(Database.connect(source("replay")), CLOCK)
            assertEquals(confirmed.id, restarted.confirmCanonicalHold(scope, hold.id, CONFIRM_KEY).success().id)
            control.exec(
                "UPDATE client_club_grants SET is_active=FALSE " +
                    "WHERE club_id=$CLUB AND user_id=1 AND permission_code='client.club.calendar.write'",
            )
            assertEquals(
                BookingCoreResult.Failure(BookingCoreError.HoldNotFound),
                restarted.confirmCanonicalHold(scope, hold.id, CONFIRM_KEY),
            )
            control.assertFenceRows(fences = 1, holds = 0, bookings = 1, sessions = 0)
        }

    @Test
    fun `other table and other night retain legacy admission and their obligations`() =
        scenario { control ->
            control.insertEvent()
            val scope = linkedScope()
            assertTrue(enableBookingFence(bookingDb, CLOCK, scope, TABLE, START, END))
            control.exec("INSERT INTO tables(id,club_id,table_number,capacity,min_deposit) VALUES (2,$CLUB,2,4,0)")
            control.exec(
                "INSERT INTO hall_tables(id,hall_id,table_number,label,capacity,zone_id," +
                    "booking_table_id,booking_club_id) VALUES (18,$CLUB,2,'Other table',4,'main',2,$CLUB)",
            )
            val laterStart = START.plus(Duration.ofDays(2))
            val laterEnd = END.plus(Duration.ofDays(2))
            control.exec("INSERT INTO events(id,club_id,start_at,end_at) VALUES (2,$CLUB,'$laterStart','$laterEnd')")
            control.exec(
                "INSERT INTO club_operational_nights(id,client_id,club_id,business_date,timezone,start_at,end_at," +
                    "source,status,created_at,updated_at) VALUES " +
                    "(22,$CLUB,$CLUB,DATE '2026-11-05','UTC','$laterStart','$laterEnd'," +
                    "'EXCEPTION','SCHEDULED','$laterStart','$laterStart')",
            )
            control.exec(
                "INSERT INTO canonical_night_event_links " +
                    "(canonical_night_id,event_id,club_id,legacy_start_at,legacy_end_at) " +
                    "VALUES (22,2,$CLUB,'$laterStart','$laterEnd')",
            )
            val first = holds.createHold(2, START, END, 2, Duration.ofMinutes(5), "legacy-other-table").success()
            val second =
                holds
                    .createHold(
                        TABLE,
                        laterStart,
                        laterEnd,
                        2,
                        Duration.ofMinutes(5),
                        "legacy-other-night",
                    ).success()
            assertEquals(2L, first.tableId)
            assertEquals(2L, second.eventId)
            assertEquals(FENCED, hold())
            control.assertFenceRows(fences = 1, holds = 2, bookings = 0, sessions = 0)
        }

    @Test
    fun `activation rejects missing links revoked actor and active booking without changing history`() =
        scenario { control ->
            control.insertEvent()
            assertFalse(enableBookingFence(bookingDb, CLOCK, scope(original), TABLE, START, END))
            val scope = linkedScope()
            assertFalse(enableBookingFence(bookingDb, CLOCK, scope.copy(clubId = 2), TABLE, START, END))
            control.exec("INSERT INTO clients(id,display_name,is_active) VALUES (2,'Other client',TRUE)")
            control.exec("UPDATE client_memberships SET is_active=FALSE WHERE client_id=$CLUB AND user_id=2")
            control.exec("INSERT INTO client_memberships(client_id,user_id,is_active) VALUES (2,2,TRUE)")
            assertFalse(enableBookingFence(bookingDb, CLOCK, scope.copy(actor = EDITOR), TABLE, START, END))
            assertFalse(enableBookingFence(bookingDb, CLOCK, scope, HALL_TABLE, START, END))
            control.exec("UPDATE hall_tables SET booking_table_id=NULL,booking_club_id=NULL WHERE id=$HALL_TABLE")
            assertFalse(enableBookingFence(bookingDb, CLOCK, scope, TABLE, START, END))
            control.exec(
                "UPDATE hall_tables SET booking_table_id=$TABLE,booking_club_id=$CLUB WHERE id=$HALL_TABLE",
            )
            control.exec(
                "UPDATE client_club_grants SET is_active=FALSE " +
                    "WHERE club_id=$CLUB AND user_id=1 AND permission_code='client.club.calendar.write'",
            )
            assertFalse(enableBookingFence(bookingDb, CLOCK, scope, TABLE, START, END))
            control.exec(
                "UPDATE client_club_grants SET is_active=TRUE " +
                    "WHERE club_id=$CLUB AND user_id=1 AND permission_code='client.club.calendar.write'",
            )
            val hold = hold().success()
            val booking = bookings.confirmFromHold(hold.id, "existing-legacy-confirm").success()
            assertFalse(enableBookingFence(bookingDb, CLOCK, scope, TABLE, START, END))
            assertEquals(booking.id.toString(), control.scalar("SELECT id FROM bookings"))
            control.assertFenceRows(fences = 0, holds = 0, bookings = 1, sessions = 0)
            assertThrows(java.sql.SQLException::class.java) {
                control.exec(
                    "INSERT INTO booking_cutover_fences(club_id,canonical_night_id,booking_table_id) " +
                        "VALUES (2,${scope.nightId},$TABLE)",
                )
            }
        }

    @Test
    fun `legacy seating already in flight blocks activation before its session commits`() =
        scenario(Gate.SESSION) { control ->
            control.insertEvent()
            val scope = linkedScope()
            val legacySessions = TableSessionRepository(Database.connect(source("legacy-seat")))
            val old = async { legacySessions.openSession(CLUB, START, TABLE, 1, START, null) }
            val firstPid = control.awaitBlocked("legacy-seat", control.pid(), "INSERT INTO table_sessions")
            val fenceDb = Database.connect(source("fence"))
            val enable = async { enableBookingFence(fenceDb, CLOCK, scope, TABLE, START, END) }
            assertNotEquals(firstPid, control.awaitBlocked("fence", firstPid, "SELECT club_id FROM client_clubs"))
            control.release(Gate.SESSION)
            assertEquals(TABLE, old.await().tableId)
            assertFalse(enable.await())
            control.assertFenceRows(fences = 0, holds = 0, bookings = 0, sessions = 1)
        }

    @Test
    fun `cancelled historical booking cannot be reactivated by a direct legacy status write`() =
        scenario { control ->
            control.insertEvent()
            val scope = linkedScope()
            val hold = hold().success()
            val previous = bookings.confirmFromHold(hold.id, "cancelled-legacy").success()
            assertEquals(
                BookingStatus.CANCELLED,
                bookings.setStatus(previous.id, BookingStatus.CANCELLED).success().status,
            )
            assertTrue(enableBookingFence(bookingDb, CLOCK, scope, TABLE, START, END))
            assertEquals(FENCED, bookings.setStatus(previous.id, BookingStatus.BOOKED))
            assertEquals("CANCELLED", control.scalar("SELECT status FROM bookings WHERE id='${previous.id}'"))
            control.assertFenceRows(fences = 1, holds = 0, bookings = 1, sessions = 0)
        }

    private fun Connection.assertFenceRows(
        fences: Int,
        holds: Int,
        bookings: Int,
        sessions: Int,
    ) {
        val counts =
            mapOf(
                "booking_cutover_fences" to fences,
                "booking_holds" to holds,
                "bookings" to bookings,
                "table_sessions" to sessions,
            )
        for ((table, count) in counts) assertEquals(count.toString(), scalar("SELECT count(*) FROM $table"))
    }

    private companion object {
        val FENCED = BookingCoreResult.Failure(BookingCoreError.LegacyWriterFenced)
    }
}
