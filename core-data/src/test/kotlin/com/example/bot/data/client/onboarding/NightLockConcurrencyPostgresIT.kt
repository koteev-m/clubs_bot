package com.example.bot.data.client.onboarding

import com.example.bot.data.booking.core.BookingCoreError
import com.example.bot.data.booking.core.BookingCoreResult
import com.example.bot.data.booking.core.BookingHold
import com.example.bot.data.booking.core.BookingHoldRepository
import com.example.bot.data.booking.core.BookingRecord
import com.example.bot.data.booking.core.BookingRepository
import com.example.bot.data.booking.core.CanonicalBookingScope
import com.example.bot.data.booking.core.PostgresIntegrationTest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** Calendar, linking and the original booking concurrency probes. */
@Timeout(30)
internal class NightLockConcurrencyPostgresIT : NightLockPostgresFixture() {
    @Test
    fun `hold first does not prevent the calendar reaching reconciliation`() = overlapHold(calendarFirst = false)

    @Test
    fun `calendar first does not prevent a hold from committing`() = overlapHold(calendarFirst = true)

    private fun overlapHold(calendarFirst: Boolean) =
        scenario(Gate.RULE, Gate.HOLD) { control ->
            control.insertEvent()
            val blocker = control.pid()
            val first = if (calendarFirst) CALENDAR else BOOKING
            val second = if (calendarFirst) BOOKING else CALENDAR
            val operations =
                mapOf<String, suspend () -> Unit>(
                    CALENDAR to {
                        rejects("calendar_night_dependencies") { edit(original, closed(), "calendar-close") }
                    },
                    BOOKING to { assertSlot(hold().success()) },
                )
            val firstOperation = async { operations.getValue(first)() }
            val firstPid = control.awaitBlocked(first, blocker)
            val secondOperation = async { operations.getValue(second)() }
            val secondPid = control.awaitBlocked(second, blocker)
            assertNotEquals(firstPid, secondPid)
            control.assertSlotLock(if (calendarFirst) secondPid else firstPid)
            // Complete the follower while the first transaction still owns its locks.
            control.release(if (calendarFirst) Gate.HOLD else Gate.RULE)
            withTimeout(WAIT_MS) { secondOperation.await() }
            assertTrue(!firstOperation.isCompleted)
            control.release(if (calendarFirst) Gate.RULE else Gate.HOLD)
            firstOperation.await()
            control.assertOriginal()
            assertEquals("1", control.scalar("SELECT count(*) FROM booking_holds"))
            val replay = hold().success()
            assertSlot(replay)
            assertEquals(control.scalar("SELECT id FROM booking_holds"), replay.id.toString())
        }

    @Test
    fun `confirm first leaves committed hold visible to the competing calendar`() =
        scenario(Gate.BOOKING, Gate.RULE) { control ->
            control.insertEvent()
            val hold = hold().success()
            val blocker = control.pid()
            val confirm = async { bookings.confirmFromHold(hold.id, CONFIRM_KEY) }
            val confirmPid = control.awaitBlocked(BOOKING, blocker)
            control.assertSlotLock(confirmPid)
            val edit =
                async {
                    rejects("calendar_night_dependencies") { edit(original, open(DATE, "02:00"), "calendar-retime") }
                }
            val calendarPid = control.awaitBlocked(CALENDAR, blocker)
            assertNotEquals(confirmPid, calendarPid)
            assertEquals("1", control.scalar("SELECT count(*) FROM booking_holds"))
            control.release(Gate.RULE)
            withTimeout(WAIT_MS) { edit.await() }
            assertTrue(!confirm.isCompleted)
            control.release(Gate.BOOKING)
            assertConfirmation(control, confirm.await().success())
        }

    @Test
    fun `calendar first makes confirm wait on booking insert then leaves the slot intact`() =
        scenario(Gate.RULE) { control ->
            control.insertEvent()
            val hold = hold().success()
            val edit = async { rejects("calendar_night_dependencies") { edit(original, closed(), "calendar-close") } }
            val calendarPid = control.awaitBlocked(CALENDAR, control.pid())
            val confirm = async { bookings.confirmFromHold(hold.id, CONFIRM_KEY) }
            val confirmPid = control.awaitBlocked(BOOKING, calendarPid, queryPrefix = "INSERT INTO bookings")
            assertNotEquals(calendarPid, confirmPid)
            control.assertSlotLock(confirmPid)
            control.release(Gate.RULE)
            edit.await()
            assertConfirmation(control, confirm.await().success())
            assertEquals(
                BookingCoreResult.Failure(BookingCoreError.HoldNotFound),
                bookings.confirmFromHold(hold.id, CONFIRM_KEY),
            )
            assertEquals("1", control.scalar("SELECT count(*) FROM bookings"))
        }

    @Test
    fun `link first serializes a calendar edit and replay after loss is durable`() =
        scenario(Gate.LINK) { control ->
            control.insertEvent()
            val request = linkBody(original)
            val first = async { link(linker, EDITOR, request, LINK_KEY) }
            val linkPid = control.awaitBlocked(LINKER, control.pid())
            val edit = async { rejects("version_conflict") { edit(original, closed(), "stale-calendar") } }
            val calendarPid = control.awaitBlocked(CALENDAR, linkPid, queryPrefix = "SELECT * FROM clients")
            assertNotEquals(calendarPid, linkPid)
            control.release(Gate.LINK)
            val ignoredResponse = first.await()
            edit.await()
            val replay = link(cabinet("replay"), EDITOR, request, LINK_KEY)
            assertEquals(ignoredResponse, replay)
            rejects("idempotency_conflict") { link(linker, EDITOR, linkBody(original, EVENT + 1), LINK_KEY) }
            val fresh = calendar.read(OWNER, OnboardingRead.CLUB_CALENDAR_GET, CLUB)
            rejects("calendar_night_dependencies") { edit(fresh, closed(), "close-linked") }
            rejects("calendar_night_dependencies") { edit(fresh, open(DATE, "02:00"), "move-linked") }
            control.assertOriginal()
            control.assertSingleLink()
        }

    @Test
    fun `calendar first invalidates a pending link version`() =
        scenario(Gate.RULE) { control ->
            control.insertEvent()
            val edit = async { edit(original, open("2026-11-06"), "edit-another-night") }
            val calendarPid = control.awaitBlocked(CALENDAR, control.pid())
            val link = async { rejects("version_conflict") { link(linker, EDITOR, linkBody(original), LINK_KEY) } }
            val linkPid = control.awaitBlocked(LINKER, calendarPid, queryPrefix = "SELECT * FROM clients")
            assertNotEquals(calendarPid, linkPid)
            control.release(Gate.RULE)
            val fresh = edit.await()
            link.await()
            assertEquals("0", control.scalar("SELECT count(*) FROM canonical_night_event_links"))
            link(linker, EDITOR, linkBody(fresh), LINK_KEY)
            control.assertOriginal()
            control.assertSingleLink()
        }

    @Test
    fun `two explicit links cannot commit competing commands`() =
        scenario(Gate.LINK) { control ->
            control.insertEvent()
            val first = async { link(linker, EDITOR, linkBody(original), LINK_KEY) }
            val linkPid = control.awaitBlocked(LINKER, control.pid())
            val other =
                async {
                    rejects("version_conflict") { link(calendar, OWNER, linkBody(original), "second-link") }
                }
            val otherPid = control.awaitBlocked(CALENDAR, linkPid, queryPrefix = "SELECT * FROM clients")
            assertNotEquals(otherPid, linkPid)
            control.release(Gate.LINK)
            first.await()
            other.await()
            val fresh = calendar.read(OWNER, OnboardingRead.CLUB_CALENDAR_GET, CLUB)
            rejects("calendar_night_event_conflict") { link(calendar, OWNER, linkBody(fresh), "second-link") }
            control.assertSingleLink()
        }

    @Test
    fun `contract probe rejects hold after calendar closes the night`() = rejectsStaleHold(closed(), "CANCELLED", START)

    @Test
    fun `contract probe rejects hold after calendar retimes the night`() =
        rejectsStaleHold(open(DATE, "02:00"), "SCHEDULED", START.plusSeconds(7_200))

    private fun rejectsStaleHold(
        rule: String,
        status: String,
        start: Instant,
        writerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) = scenario(Gate.VERSION) { control ->
        val edit = async { edit(original, rule, "edit-before-legacy-event") }
        val calendarPid = control.awaitBlocked(CALENDAR, control.pid())
        // Deliberately supply a late legacy event in the test. Its FK waits for the club lock.
        // The deployed guest route cannot access this synthetic mapped club.
        val writer =
            async {
                withContext(writerDispatcher) { bookingSource.connection.use { it.insertEvent() } }
                protectedHold(scope(original))
            }
        val writerPid = control.awaitBlocked(BOOKING, calendarPid, queryPrefix = "INSERT INTO events")
        assertNotEquals(calendarPid, writerPid)
        control.release(Gate.VERSION)
        edit.await()
        val result = writer.await()
        assertEquals(status, control.scalar("SELECT status FROM club_operational_nights WHERE id=$nightId"))
        assertEquals(start, control.instant("SELECT start_at FROM club_operational_nights WHERE id=$nightId"))
        assertTrue(result is BookingCoreResult.Failure, "DB HOLD accepted the obsolete slot after the calendar commit")
        assertEquals("0", control.scalar("SELECT count(*) FROM booking_holds"))
    }

    @Test
    fun `contract probe rejects a new hold when retrying a cleaned up key`() =
        scenario { control ->
            control.insertEvent()
            val scope = linkedScope()
            val lostResponse = protectedHold(scope).success()
            assertEquals(lostResponse.id, protectedHold(scope).success().id)
            val afterExpiry = lostResponse.expiresAt.plusSeconds(1)
            val restarted = BookingHoldRepository(bookingDb, Clock.fixed(afterExpiry, ZoneOffset.UTC))
            assertEquals(1, restarted.cleanupExpired(afterExpiry))
            val replay = protectedHold(scope, repository = restarted)
            val holdCount = control.scalar("SELECT count(*) FROM booking_holds")
            assertEquals("0", holdCount, "Expired command was executed again")
            assertEquals(BookingCoreResult.Failure(BookingCoreError.HoldExpired), replay)
        }

    @ParameterizedTest(name = "calendar first={0}, confirm={1}")
    @CsvSource("false,false", "true,false", "false,true", "true,true")
    fun `protected booking serializes with calendar and rechecks version`(
        calendarFirst: Boolean,
        confirm: Boolean,
    ) = scenario(
        if (calendarFirst) {
            Gate.RULE
        } else if (confirm) {
            Gate.BOOKING
        } else {
            Gate.HOLD
        },
    ) { control ->
        control.insertEvent()
        val scope = linkedScope()
        val hold = if (confirm) protectedHold(scope).success() else null
        val current = calendar.read(OWNER, OnboardingRead.CLUB_CALENDAR_GET, CLUB)
        val operate: suspend () -> BookingCoreResult<*> = {
            if (hold == null) protectedHold(scope) else bookings.confirmCanonicalHold(scope, hold.id, CONFIRM_KEY)
        }
        if (calendarFirst) {
            val edit = async { edit(current, open("2026-11-06"), "advance-calendar") }
            val calendarPid = control.awaitBlocked(CALENDAR, control.pid())
            val follower = async { operate() }
            val bookingPid = control.awaitBlocked(BOOKING, calendarPid, queryPrefix = "SELECT * FROM clients")
            assertNotEquals(calendarPid, bookingPid)
            control.release(Gate.RULE)
            val updated = edit.await()
            assertEquals(BookingCoreResult.Failure(BookingCoreError.EventNotFound), follower.await())
            if (hold == null) {
                assertEquals("0", control.scalar("SELECT count(*) FROM booking_holds"))
            } else {
                assertEquals(hold.id.toString(), control.scalar("SELECT id FROM booking_holds"))
                assertConfirmation(
                    control,
                    bookings.confirmCanonicalHold(scope(updated), hold.id, CONFIRM_KEY).success(),
                )
            }
        } else {
            val first = async { operate() }
            val bookingPid = control.awaitBlocked(BOOKING, control.pid())
            control.assertSlotLock(bookingPid)
            val edit = async { rejects("calendar_night_dependencies") { edit(current, closed(), "close-linked") } }
            val calendarPid = control.awaitBlocked(CALENDAR, bookingPid, queryPrefix = "SELECT * FROM clients")
            assertNotEquals(bookingPid, calendarPid)
            control.release(if (confirm) Gate.BOOKING else Gate.HOLD)
            when (val result = first.await().success()) {
                is BookingRecord -> assertConfirmation(control, result)
                is BookingHold -> assertSlot(result)
                else -> error("Unexpected booking result")
            }
            edit.await()
        }
        control.assertOriginal()
    }

    @Test
    fun `two protected holds acquire club before slot and create one obligation`() =
        scenario(Gate.HOLD) { control ->
            control.insertEvent()
            val scope = linkedScope()
            val lock = source("club-lock").connection
            lock.use {
                lock.autoCommit = false
                lock.exec("SELECT id FROM clubs WHERE id=$CLUB FOR UPDATE")
                val first = async { protectedHold(scope) }
                try {
                    val bookingPid =
                        control.awaitBlocked(
                            BOOKING,
                            lock.pid(),
                            queryPrefix = "SELECT is_active,timezone",
                        )
                    assertEquals(
                        "0",
                        control.scalar(
                            "SELECT count(*) FROM pg_locks WHERE pid=$bookingPid " +
                                "AND locktype='advisory' AND objsubid=2",
                        ),
                    )
                } finally {
                    lock.rollback()
                }
                val bookingPid = control.awaitBlocked(BOOKING, control.pid())
                val otherRepository = BookingHoldRepository(Database.connect(source("booking-other")), CLOCK)
                val second =
                    async {
                        protectedHold(
                            scope.copy(actor = EDITOR),
                            repository = otherRepository,
                            key = "other-protected-hold",
                        )
                    }
                assertNotEquals(bookingPid, control.awaitBlocked("booking-other", bookingPid))
                control.release(Gate.HOLD)
                assertSlot(first.await().success())
                assertEquals(BookingCoreResult.Failure(BookingCoreError.ActiveHoldExists), second.await())
            }
            assertEquals("1", control.scalar("SELECT count(*) FROM booking_holds"))
            assertEquals("1", control.scalar("SELECT count(*) FROM canonical_booking_commands"))
        }

    @Test
    fun `protected confirm replays committed booking and rejects changed command after restart`() =
        scenario(Gate.BOOKING) { control ->
            control.insertEvent()
            val scope = linkedScope()
            val hold = protectedHold(scope).success()
            val restarted = BookingRepository(Database.connect(source("booking-restart")), CLOCK)
            val first = async { bookings.confirmCanonicalHold(scope, hold.id, CONFIRM_KEY) }
            val firstPid = control.awaitBlocked(BOOKING, control.pid())
            val replay = async { restarted.confirmCanonicalHold(scope, hold.id, CONFIRM_KEY) }
            assertNotEquals(firstPid, control.awaitBlocked("booking-restart", firstPid))
            control.release(Gate.BOOKING)
            val booking = first.await().success()
            assertEquals(booking.id, replay.await().success().id)
            assertEquals(booking.id, restarted.confirmCanonicalHold(scope, hold.id, CONFIRM_KEY).success().id)
            assertEquals(
                BookingCoreResult.Failure(BookingCoreError.IdempotencyConflict),
                restarted.confirmCanonicalHold(scope, java.util.UUID.randomUUID(), CONFIRM_KEY),
            )
            assertConfirmation(control, booking)
            assertEquals("2", control.scalar("SELECT count(*) FROM canonical_booking_commands"))
        }

    @Test
    fun `receipt failures roll back hold and confirm before allowing same key retries`() =
        scenario { control ->
            control.insertEvent()
            val scope = linkedScope()
            control.exec("ALTER TABLE canonical_booking_commands ADD CONSTRAINT fail_hold CHECK (operation <> 'HOLD')")
            try {
                assertWriteFailure { protectedHold(scope) }
                assertEquals("0", control.scalar("SELECT count(*) FROM booking_holds"))
                assertEquals("0", control.scalar("SELECT count(*) FROM canonical_booking_commands"))
            } finally {
                control.exec("ALTER TABLE canonical_booking_commands DROP CONSTRAINT fail_hold")
            }
            val hold = protectedHold(scope).success()
            control.exec(
                "ALTER TABLE canonical_booking_commands ADD CONSTRAINT fail_confirm CHECK (operation <> 'CONFIRM')",
            )
            try {
                assertWriteFailure { bookings.confirmCanonicalHold(scope, hold.id, CONFIRM_KEY) }
                assertEquals("0", control.scalar("SELECT count(*) FROM bookings"))
                assertEquals(hold.id.toString(), control.scalar("SELECT id FROM booking_holds"))
                assertEquals("1", control.scalar("SELECT count(*) FROM canonical_booking_commands"))
            } finally {
                control.exec("ALTER TABLE canonical_booking_commands DROP CONSTRAINT fail_confirm")
            }
            assertConfirmation(control, bookings.confirmCanonicalHold(scope, hold.id, CONFIRM_KEY).success())
        }
}

/** Synthetic linked club and PostgreSQL wait observation shared by the protected writer tests. */
internal open class NightLockPostgresFixture protected constructor() : PostgresIntegrationTest() {
    protected val observer by lazy { source("observer") }
    protected val bookingSource by lazy { source(BOOKING) }
    protected val bookingDb by lazy { Database.connect(bookingSource) }
    protected val holds by lazy { BookingHoldRepository(bookingDb, CLOCK) }
    protected val bookings by lazy { BookingRepository(bookingDb, CLOCK) }
    protected val calendar by lazy { cabinet(CALENDAR) }
    protected val linker by lazy { cabinet(LINKER) }
    protected lateinit var original: JsonObject
    protected var nightId = 0L

    @BeforeEach
    fun prepareNight() =
        runBlocking {
            observer.connection.use { control ->
                control.exec("TRUNCATE TABLE clients, users RESTART IDENTITY CASCADE")
                control.exec("INSERT INTO users(id,telegram_user_id) VALUES (1,101),(2,102)")
                control.exec("INSERT INTO clients(id,display_name,is_active) VALUES (1,'Test client',TRUE)")
                control.exec("INSERT INTO clubs(id,name,timezone,is_active) VALUES (1,'Test club','UTC',FALSE)")
                control.exec("INSERT INTO client_clubs(client_id,club_id) VALUES (1,1)")
                control.exec("INSERT INTO client_memberships(client_id,user_id,is_active) VALUES (1,1,TRUE),(1,2,TRUE)")
                control.exec(
                    "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active) " +
                        "SELECT 1,u,1,p,TRUE FROM (VALUES (1),(2)) AS actors(u) " +
                        "CROSS JOIN (VALUES ('client.club.read'),('client.club.calendar.write')) AS permissions(p)",
                )
                control.exec("INSERT INTO tables(id,club_id,table_number,capacity,min_deposit) VALUES (1,1,1,4,0)")
                control.exec(
                    "INSERT INTO halls(id,club_id,name,geometry_json,geometry_fingerprint) " +
                        "VALUES (1,1,'Hall','{}','h')",
                )
                control.exec("INSERT INTO hall_zones(hall_id,zone_id,name) VALUES (1,'main','Main')")
                control.exec(
                    "INSERT INTO hall_tables(id,hall_id,table_number,label,capacity,zone_id," +
                        "booking_table_id,booking_club_id) " +
                        "VALUES ($HALL_TABLE,1,1,'Table',4,'main',$TABLE,$CLUB)",
                )
            }
            val initial = calendar.read(OWNER, OnboardingRead.CLUB_CALENDAR_GET, CLUB)
            original = edit(initial, open(DATE), "seed-calendar")
            nightId =
                original
                    .getValue("nights")
                    .jsonArray
                    .single()
                    .jsonObject
                    .id("nightId")
        }

    protected suspend fun assertWriteFailure(block: suspend () -> Unit) {
        try {
            block()
            throw AssertionError("Expected receipt constraint failure")
        } catch (error: SQLException) {
            assertEquals("23514", error.sqlState)
        }
    }

    protected fun scenario(
        vararg gates: Gate,
        block: suspend CoroutineScope.(Connection) -> Unit,
    ) = runBlocking {
        observer.connection.use { control ->
            gates.forEach { control.install(it) }
            try {
                coroutineScope {
                    try {
                        block(control)
                    } finally {
                        control.exec("SELECT pg_advisory_unlock_all()")
                    }
                }
            } finally {
                gates.forEach { control.exec("DROP TRIGGER night_test_pause ON ${it.table}") }
            }
        }
    }

    protected fun Connection.install(gate: Gate) {
        exec(
            """
            CREATE OR REPLACE FUNCTION night_test_pause() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
                PERFORM pg_advisory_xact_lock(TG_ARGV[0]::bigint);
                RETURN NEW;
            END;
            $$
            """.trimIndent(),
        )
        exec(
            "CREATE TRIGGER night_test_pause BEFORE ${gate.action} ON ${gate.table} " +
                "FOR EACH ROW EXECUTE FUNCTION night_test_pause('${gate.key}')",
        )
        exec("SELECT pg_advisory_lock(${gate.key}::bigint)")
    }

    protected fun Connection.release(gate: Gate) =
        assertEquals("1", scalar("SELECT CASE WHEN pg_advisory_unlock(${gate.key}::bigint) THEN 1 ELSE 0 END"))

    protected suspend fun Connection.awaitBlocked(
        actor: String,
        blocker: Int,
        queryPrefix: String? = null,
    ): Int =
        withTimeout(WAIT_MS) {
            var pid: Int? = null
            while (pid == null) {
                prepareStatement(
                    "SELECT pid, query FROM pg_stat_activity WHERE application_name=? AND ?=ANY(pg_blocking_pids(pid))",
                ).use { statement ->
                    statement.setString(1, "night-lock-$actor")
                    statement.setInt(2, blocker)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) {
                            if (queryPrefix != null) {
                                val query = rows.getString("query")
                                assertTrue(
                                    query.startsWith(queryPrefix, ignoreCase = true),
                                    "Unexpected blocked statement for $actor, expected $queryPrefix",
                                )
                            }
                            pid = rows.getInt("pid")
                        }
                    }
                }
                if (pid == null) delay(10)
            }
            checkNotNull(pid)
        }

    protected fun Connection.assertSlotLock(pid: Int) {
        val slotLocks =
            scalar(
                "SELECT count(*) FROM pg_locks WHERE pid=$pid AND locktype='advisory' AND granted AND objsubid=2",
            )
        assertEquals("1", slotLocks)
    }

    protected fun Connection.assertOriginal() {
        assertEquals("SCHEDULED", scalar("SELECT status FROM club_operational_nights WHERE id=$nightId"))
        assertEquals(START, instant("SELECT start_at FROM club_operational_nights WHERE id=$nightId"))
        assertEquals(END, instant("SELECT end_at FROM club_operational_nights WHERE id=$nightId"))
        assertEquals(START, instant("SELECT start_at FROM events WHERE id=$EVENT"))
        assertEquals(END, instant("SELECT end_at FROM events WHERE id=$EVENT"))
    }

    protected fun Connection.assertSingleLink() {
        val linkedRows =
            scalar(
                "SELECT count(*) FROM canonical_night_event_links " +
                    "WHERE canonical_night_id=$nightId AND event_id=$EVENT",
            )
        assertEquals("1", linkedRows)
        assertEquals("1", scalar("SELECT count(*) FROM audit_log WHERE action='client.club.calendar.night.link'"))
        assertEquals("1", scalar("SELECT count(*) FROM client_commands WHERE result_type='club_night_event_link'"))
    }

    protected fun assertConfirmation(
        control: Connection,
        record: BookingRecord,
    ) {
        assertEquals(EVENT, record.eventId)
        assertEquals(START, record.slotStart)
        assertEquals(END, record.slotEnd)
        assertEquals("0", control.scalar("SELECT count(*) FROM booking_holds"))
        assertEquals(EVENT.toString(), control.scalar("SELECT event_id FROM bookings WHERE id='${record.id}'"))
        assertEquals(START, control.instant("SELECT slot_start FROM bookings WHERE id='${record.id}'"))
        assertEquals(END, control.instant("SELECT slot_end FROM bookings WHERE id='${record.id}'"))
        control.assertOriginal()
    }

    protected fun assertSlot(hold: BookingHold) {
        assertEquals(EVENT, hold.eventId)
        assertEquals(START, hold.slotStart)
        assertEquals(END, hold.slotEnd)
    }

    protected suspend fun hold(repository: BookingHoldRepository = holds) =
        repository.createHold(TABLE, START, END, 2, Duration.ofMinutes(5), HOLD_KEY)

    protected fun scope(calendar: JsonObject) =
        CanonicalBookingScope(OWNER, CLUB, nightId, EVENT, calendar.id("version"))

    protected suspend fun linkedScope(): CanonicalBookingScope {
        link(linker, EDITOR, linkBody(original), LINK_KEY)
        return scope(calendar.read(OWNER, OnboardingRead.CLUB_CALENDAR_GET, CLUB))
    }

    protected suspend fun protectedHold(
        scope: CanonicalBookingScope,
        repository: BookingHoldRepository = holds,
        key: String = HOLD_KEY,
        guests: Int = 2,
        end: Instant = END,
        tableId: Long = TABLE,
    ) = repository.createCanonicalHold(scope, tableId, START, end, guests, Duration.ofMinutes(5), key)

    protected fun Connection.insertEvent() =
        exec("INSERT INTO events(id,club_id,start_at,end_at) VALUES ($EVENT,$CLUB,'$START','$END')")

    protected suspend fun edit(
        base: JsonObject,
        rule: String,
        key: String,
    ): JsonObject =
        calendar.mutate(
            OWNER,
            OnboardingMutation.CLUB_CALENDAR_UPDATE,
            CLUB,
            Json
                .parseToJsonElement(
                    """
                    {"expectedVersion":"${base.string(
                        "version",
                    )}","timezone":"UTC","weekly":[],"holidays":[],"exceptions":[$rule]}
                    """.trimIndent(),
                ).jsonObject,
            key,
        )

    protected fun linkBody(
        base: JsonObject,
        eventId: Long = EVENT,
    ) = JsonObject(mapOf("expectedVersion" to base.getValue("version"), "eventId" to JsonPrimitive(eventId.toString())))

    protected suspend fun link(
        repository: JdbcClientOnboardingRepository,
        actor: OnboardingActor,
        body: JsonObject,
        key: String,
    ) = repository.mutate(actor, OnboardingMutation.CLUB_NIGHT_EVENT_LINK, nightId, body, key)

    protected suspend fun rejects(
        code: String,
        block: suspend () -> Unit,
    ) {
        val failure =
            try {
                block()
                throw AssertionError("Expected rejection $code")
            } catch (error: OnboardingFailure) {
                error
            }
        assertEquals(code, failure.code)
    }

    protected fun cabinet(name: String) = JdbcClientOnboardingRepository(source(name), null, CLOCK)

    protected fun source(name: String) =
        PGSimpleDataSource().apply {
            setUrl(postgres.jdbcUrl)
            user = postgres.username
            password = postgres.password
            applicationName = "night-lock-$name"
            options = "-c lock_timeout=12s -c statement_timeout=15s"
            connectTimeout = 5
            socketTimeout = 20
        }

    protected fun Connection.exec(query: String) {
        createStatement().use { it.execute(query) }
    }

    protected fun Connection.pid() = scalar("SELECT pg_backend_pid()").toInt()

    protected fun Connection.scalar(query: String): String =
        checkNotNull(checkNotNull(OnboardingSql(this).row(query)).values.single())

    protected fun Connection.instant(query: String): Instant =
        checkNotNull(OnboardingSql(this).row(query)).let { it.instant(it.keys.single()) }

    protected fun open(
        date: String,
        start: String = "00:00",
    ) = """{"date":"$date","mode":"open","open":"$start","close":"08:00","startNextDay":true,"nextDay":false}"""

    protected fun closed() = """{"date":"$DATE","mode":"closed"}"""

    protected fun <T> BookingCoreResult<T>.success(): T =
        when (this) {
            is BookingCoreResult.Success -> value
            is BookingCoreResult.Failure -> throw AssertionError("Expected booking success, got $error")
        }

    protected enum class Gate(
        val table: String,
        val action: String,
        val key: Long,
    ) {
        RULE("club_exceptions", "INSERT OR UPDATE", 6_057_001),
        HOLD("booking_holds", "INSERT", 6_057_002),
        BOOKING("bookings", "INSERT", 6_057_003),
        LINK("canonical_night_event_links", "INSERT", 6_057_004),
        VERSION("clubs", "UPDATE OF calendar_version", 6_057_005),
        SESSION("table_sessions", "INSERT", 6_057_006),
        FENCE("booking_cutover_fences", "INSERT", 6_057_007),
    }

    protected companion object {
        const val CLUB = 1L
        const val TABLE = 1L
        const val HALL_TABLE = 17L
        const val SEAT = "seat"
        const val EVENT = 1L
        const val DATE = "2026-11-03"
        const val WAIT_MS = 4_000L
        const val CALENDAR = "calendar"
        const val BOOKING = "booking"
        const val LINKER = "linker"
        const val HOLD_KEY = "hold-concurrency-test"
        const val CONFIRM_KEY = "confirm-concurrency-test"
        const val LINK_KEY = "link-concurrency-test"
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-11-03T12:00:00Z"), ZoneOffset.UTC)
        val START: Instant = Instant.parse("2026-11-04T00:00:00Z")
        val END: Instant = Instant.parse("2026-11-04T08:00:00Z")
        val NIGHT_CLOCK: Clock = Clock.fixed(START.plusSeconds(60), ZoneOffset.UTC)
        val OWNER = OnboardingActor(101, "platform")
        val EDITOR = OnboardingActor(102, "platform")
    }
}
