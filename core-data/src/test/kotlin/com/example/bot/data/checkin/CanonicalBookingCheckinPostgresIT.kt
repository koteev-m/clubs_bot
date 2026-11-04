package com.example.bot.data.checkin

import com.example.bot.data.booking.core.PostgresIntegrationTest
import com.example.bot.data.client.onboarding.JdbcClientOnboardingRepository
import com.example.bot.data.client.onboarding.OnboardingFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import java.sql.SQLException

@Timeout(30)
internal class CanonicalBookingCheckinPostgresIT : PostgresIntegrationTest() {
    private val observer by lazy { source("observer") }

    @BeforeEach
    fun clearClientActors() {
        observer.write("TRUNCATE TABLE clients, users RESTART IDENTITY CASCADE")
    }

    @Test
    fun `checkin and its visit block the calendar until the whole admission commits`() =
        scenario("club_visits", "INSERT") { control, fixture ->
            val checkin = async { fixture.admit(repository = fixture.repository(source("checkin"))) }
            val checkinPid = control.awaitBlocked("checkin", control.pid(), "INSERT INTO club_visits")
            fixture.assertUntouched()
            val calendar = async { fixture.calendarConflict() }
            val calendarPid = control.awaitBlocked("calendar", checkinPid, "SELECT * FROM clients")
            assertNotEquals(checkinPid, calendarPid)
            control.release()
            fixture.assertAdmission(checkin.await().admitted())
            assertEquals("calendar_night_started", calendar.await())
            assertEquals("1", fixture.count("club_exceptions", "club_id=$CLUB AND is_open=TRUE"))
        }

    @Test
    fun `calendar first blocks checkin before checking the linked event and booking`() =
        scenario("club_exceptions", "INSERT OR UPDATE") { control, fixture ->
            val calendar = async { fixture.calendarConflict() }
            val calendarPid = control.awaitBlocked("calendar", control.pid(), "UPDATE club_exceptions")
            val checkin = async { fixture.admit(repository = fixture.repository(source("checkin"))) }
            val checkinPid = control.awaitBlocked("checkin", calendarPid, "SELECT * FROM clients")
            assertNotEquals(calendarPid, checkinPid)
            fixture.assertUntouched()
            control.release()
            assertEquals("calendar_night_started", calendar.await())
            fixture.assertAdmission(checkin.await().admitted())
            assertEquals("1", fixture.count("club_exceptions", "club_id=$CLUB AND is_open=TRUE"))
        }

    @Test
    fun `club row blocks admission before any event or booking child lock`() =
        runBlocking {
            val fixture = fixture()
            observer.connection.use { control ->
                control.autoCommit = false
                control.executeSql("SELECT id FROM clubs WHERE id=$CLUB FOR UPDATE")
                coroutineScope {
                    val checkin = async { fixture.admit(repository = fixture.repository(source("checkin"))) }
                    try {
                        val checkinPid =
                            observer.connection.use {
                                it.awaitBlocked("checkin", control.pid(), "SELECT name,")
                            }
                        assertNotEquals(control.pid(), checkinPid)
                        control.executeSql("SELECT id FROM events WHERE id=$EVENT FOR UPDATE NOWAIT")
                        control.executeSql("SELECT id FROM bookings WHERE id='$BOOKING' FOR UPDATE NOWAIT")
                    } finally {
                        control.rollback()
                    }
                    fixture.assertAdmission(checkin.await().admitted())
                }
            }
        }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `two admissions for one guest yield one checkin and one visit`(sameBooking: Boolean) =
        scenario("club_visits", "INSERT") { control, fixture ->
            val first = async { fixture.admit(repository = fixture.repository(source("checkin"))) }
            val firstPid = control.awaitBlocked("checkin", control.pid(), "INSERT INTO club_visits")
            val second =
                async {
                    fixture.admit(
                        bookingId = if (sameBooking) BOOKING else SECOND_BOOKING,
                        actor = MANAGER,
                        repository = fixture.repository(source("second")),
                    )
                }
            val secondPid = control.awaitBlocked("second", firstPid, "SELECT * FROM clients")
            assertNotEquals(firstPid, secondPid)
            control.release()
            val admission = first.await().admitted()
            if (sameBooking) {
                assertEquals(admission.copy(replayed = true), second.await())
            } else {
                assertEquals(CanonicalCheckinResult.Rejected.ALREADY_ADMITTED, second.await())
            }
            val replay = fixture.admit(repository = fixture.repository(source("restarted"))).admitted()
            assertEquals(admission.copy(replayed = true), replay)
            fixture.assertAdmission(admission)
        }

    @ParameterizedTest
    @ValueSource(strings = ["unlinked", "cancelled", "drift", "ambiguous"])
    fun `unavailable or ambiguous linked night fails closed`(failure: String) =
        runBlocking {
            val fixture = fixture(linked = failure != "unlinked")
            when (failure) {
                "cancelled" ->
                    observer.write(
                        "UPDATE club_operational_nights SET status='CANCELLED' WHERE id=${fixture.nightId}",
                    )
                "drift" -> observer.write("UPDATE events SET end_at=end_at + interval '1 hour' WHERE id=$EVENT")
                "ambiguous" ->
                    observer.write(
                        "INSERT INTO events(id,club_id,start_at,end_at) " +
                            "SELECT id+1,club_id,start_at + interval '1 hour',end_at FROM events WHERE id=$EVENT",
                    )
            }
            assertEquals(CanonicalCheckinResult.Rejected.NIGHT_UNAVAILABLE, fixture.admit())
            fixture.assertUntouched()
        }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `midnight and DST keep the original event and slot keys`(dst: Boolean) =
        runBlocking {
            val fixture = fixture(if (dst) DST else MIDNIGHT)
            fixture.assertAdmission(fixture.admit().admitted())
            observer.connection.use { control ->
                assertEquals(
                    "1",
                    control.scalar(
                        "SELECT count(*) FROM bookings b JOIN events e ON e.id=b.event_id " +
                            "JOIN club_operational_nights n ON n.id=${fixture.nightId} " +
                            "WHERE b.id='$BOOKING' AND e.id=$EVENT " +
                            "AND b.slot_start=e.start_at AND b.slot_end=e.end_at " +
                            "AND n.business_date='${fixture.window.businessDate}' " +
                            "AND e.start_at='${fixture.window.start}' AND e.end_at='${fixture.window.end}'",
                    ),
                )
            }
        }

    @Test
    fun `visit and audit failures roll back the whole admission and allow safe retry`() =
        runBlocking {
            val fixture = fixture()
            assertAtomicFailure(fixture, "club_visits", "id < 0")
            assertAtomicFailure(fixture, "audit_log", "action <> 'CHECKIN'")
            fixture.assertAdmission(fixture.admit().admitted())
        }

    @Test
    fun `wrong club stale grant and unscoped host cannot record a visit`() =
        runBlocking {
            val fixture = fixture()
            assertEquals(CanonicalCheckinResult.Rejected.BOOKING_UNAVAILABLE, fixture.admit(clubId = CLUB + 1))
            observer.write("UPDATE user_roles SET scope_type='GLOBAL',scope_club_id=NULL WHERE user_id=${HOST.userId}")
            assertEquals(CanonicalCheckinResult.Rejected.FORBIDDEN, fixture.admit())
            observer.write(
                "UPDATE client_club_grants SET is_active=FALSE WHERE club_id=$CLUB AND user_id=${MANAGER.userId}",
            )
            assertEquals(CanonicalCheckinResult.Rejected.FORBIDDEN, fixture.admit(actor = MANAGER))

            val foreignClub = CLUB + 1
            observer.write("INSERT INTO clubs(id,name,timezone,is_active) VALUES ($foreignClub,'Other','UTC',FALSE)")
            observer.write("INSERT INTO clients(id,display_name,is_active) VALUES ($foreignClub,'Other',TRUE)")
            observer.write("INSERT INTO client_clubs(client_id,club_id) VALUES ($foreignClub,$foreignClub)")
            observer.write(
                "INSERT INTO client_memberships(client_id,user_id,is_active) " +
                    "VALUES ($foreignClub,${MANAGER.userId},TRUE)",
            )
            observer.write(
                "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active) " +
                    "VALUES ($foreignClub,${MANAGER.userId},$foreignClub,'client.club.read',TRUE)",
            )
            observer.write("UPDATE user_roles SET scope_club_id=$foreignClub WHERE user_id=${MANAGER.userId}")
            assertEquals(CanonicalCheckinResult.Rejected.FORBIDDEN, fixture.admit(actor = MANAGER))
            assertEquals(
                CanonicalCheckinResult.Rejected.BOOKING_UNAVAILABLE,
                fixture.admit(actor = MANAGER, clubId = foreignClub),
            )
            fixture.assertUntouched()
        }

    @Test
    fun `replay requires current host authority`() =
        runBlocking {
            val fixture = fixture()
            val admission = fixture.admit().admitted()
            observer.write("DELETE FROM user_roles WHERE user_id=${HOST.userId} AND scope_club_id=$CLUB")
            assertEquals(CanonicalCheckinResult.Rejected.FORBIDDEN, fixture.admit())
            fixture.assertAdmission(admission)
        }

    private suspend fun assertAtomicFailure(
        fixture: CanonicalCheckinFixture,
        table: String,
        condition: String,
    ) {
        observer.write("ALTER TABLE $table ADD CONSTRAINT fail_checkin_write CHECK ($condition)")
        try {
            try {
                fixture.admit()
                throw AssertionError("Expected PostgreSQL constraint failure on $table")
            } catch (error: SQLException) {
                assertEquals("23514", error.sqlState)
            }
            fixture.assertUntouched()
        } finally {
            observer.write("ALTER TABLE $table DROP CONSTRAINT fail_checkin_write")
        }
    }

    private suspend fun CanonicalCheckinFixture.calendarConflict(): String =
        try {
            closeNight(JdbcClientOnboardingRepository(source("calendar"), null, clock))
            throw AssertionError("Calendar changed a linked night")
        } catch (error: OnboardingFailure) {
            error.code
        }

    private suspend fun fixture(
        window: CheckinWindow = MIDNIGHT,
        linked: Boolean = true,
    ) = CanonicalCheckinFixture(observer, window).also { it.seed(linked) }

    private fun scenario(
        table: String,
        action: String,
        block: suspend CoroutineScope.(Connection, CanonicalCheckinFixture) -> Unit,
    ) = runBlocking {
        val fixture = fixture()
        observer.connection.use { control ->
            control.executeSql(
                """
                CREATE OR REPLACE FUNCTION checkin_test_pause() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    PERFORM pg_advisory_xact_lock($GATE);
                    RETURN NEW;
                END;
                $$
                """.trimIndent(),
            )
            control.executeSql(
                "CREATE TRIGGER checkin_test_pause BEFORE $action ON $table " +
                    "FOR EACH ROW EXECUTE FUNCTION checkin_test_pause()",
            )
            control.executeSql("SELECT pg_advisory_lock($GATE)")
            try {
                coroutineScope {
                    try {
                        block(control, fixture)
                    } finally {
                        control.executeSql("SELECT pg_advisory_unlock_all()")
                    }
                }
            } finally {
                control.executeSql("DROP TRIGGER IF EXISTS checkin_test_pause ON $table")
            }
        }
    }

    private fun Connection.release() =
        assertEquals("1", scalar("SELECT CASE WHEN pg_advisory_unlock($GATE) THEN 1 ELSE 0 END"))

    private fun Connection.pid() = scalar("SELECT pg_backend_pid()").toInt()

    private suspend fun Connection.awaitBlocked(
        actor: String,
        blocker: Int,
        prefix: String,
    ): Int =
        withTimeout(5_000) {
            var result: Int? = null
            while (result == null) {
                prepareStatement(
                    "SELECT pid,query FROM pg_stat_activity WHERE application_name=? AND ?=ANY(pg_blocking_pids(pid))",
                ).use { statement ->
                    statement.setString(1, "night-checkin-$actor")
                    statement.setInt(2, blocker)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) {
                            val query = rows.getString("query")
                            assertTrue(query.startsWith(prefix, ignoreCase = true), "Expected $prefix, got $query")
                            result = rows.getInt("pid")
                        }
                    }
                }
                if (result == null) delay(10)
            }
            checkNotNull(result)
        }

    private fun source(name: String) =
        PGSimpleDataSource().apply {
            setUrl(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
            applicationName = "night-checkin-$name"
            options = "-c lock_timeout=12s -c statement_timeout=15s"
            connectTimeout = 5
            socketTimeout = 18
        }

    private companion object {
        const val GATE = 28_046_571
    }
}
