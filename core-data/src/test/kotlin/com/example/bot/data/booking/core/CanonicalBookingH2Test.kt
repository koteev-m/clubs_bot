package com.example.bot.data.booking.core

import com.example.bot.data.client.onboarding.OnboardingActor
import com.example.bot.data.client.onboarding.OnboardingSql
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

internal class CanonicalBookingH2Test {
    @Test
    fun `upgrade keeps legacy booking and protected receipts replay on a new repository`() =
        fixture("70") { source ->
            val db = Database.connect(source)
            val legacy = BookingHoldRepository(db, CLOCK)
            val oldHold = legacy.createHold(LEGACY, START, END, 2, TTL, "legacy-before-v071").success()
            assertEquals(1, flyway(source, "71").migrate().migrationsExecuted)
            assertEquals(1, flyway(source, "72").migrate().migrationsExecuted)
            assertEquals(1, flyway(source, "73").migrate().migrationsExecuted)
            assertEquals(1, flyway(source, "74").migrate().migrationsExecuted)
            assertEquals(1, flyway(source, "75").migrate().migrationsExecuted)
            assertEquals(1, flyway(source).migrate().migrationsExecuted)
            flyway(source).validate()
            source.seedPhysicalTable()
            assertEquals("0", source.count("canonical_booking_commands"))
            val bookingRepo = BookingRepository(db, CLOCK)
            assertEquals(LEGACY, bookingRepo.confirmFromHold(oldHold.id, "legacy-confirm").success().eventId)
            assertEquals("0", source.count("canonical_booking_commands"))

            val hold = legacy.protectedHold().success()
            val restartedDb = Database.connect(source)
            val restarted = BookingHoldRepository(restartedDb, CLOCK)
            assertEquals(hold.id, restarted.protectedHold().success().id)
            assertEquals(
                BookingCoreResult.Failure(BookingCoreError.IdempotencyConflict),
                restarted.protectedHold(guests = 3),
            )
            val booked = bookingRepo.confirmCanonicalHold(SCOPE, hold.id, CONFIRM).success()
            val replay = BookingRepository(restartedDb, CLOCK).confirmCanonicalHold(SCOPE, hold.id, CONFIRM).success()
            assertEquals(booked.id, replay.id)
            assertEquals(EVENT, replay.eventId)
            assertEquals(START, replay.slotStart)
            assertEquals(END, replay.slotEnd)
            assertEquals("2", source.count("canonical_booking_commands"))
            assertEquals("0", source.count("booking_holds"))
            assertFalse(hold.idempotencyKey == HOLD)
            assertFalse(booked.idempotencyKey == CONFIRM)
        }

    @Test
    fun `protected hold and confirm reject mismatched window ambiguity and cancelled state`() =
        fixture { source ->
            val db = Database.connect(source)
            val holds = BookingHoldRepository(db, CLOCK)
            val bookings = BookingRepository(db, CLOCK)
            assertEquals(
                BookingCoreResult.Failure(BookingCoreError.EventNotFound),
                holds.protectedHold(end = END.minusSeconds(1)),
            )
            val hold = holds.protectedHold().success()
            source.write("UPDATE events SET end_at=TIMESTAMP WITH TIME ZONE '2026-11-04 07:59:00+00' WHERE id=$EVENT")
            assertEquals(
                BookingCoreResult.Failure(BookingCoreError.EventNotFound),
                bookings.confirmCanonicalHold(SCOPE, hold.id, CONFIRM),
            )
            source.write("UPDATE events SET end_at=TIMESTAMP WITH TIME ZONE '2026-11-04 08:00:00+00' WHERE id=$EVENT")
            source.write(
                "INSERT INTO events(id,club_id,start_at,end_at) VALUES (8803,$CLUB," +
                    "TIMESTAMP WITH TIME ZONE '2026-11-03 20:00:00+00'," +
                    "TIMESTAMP WITH TIME ZONE '2026-11-03 22:00:00+00')",
            )
            assertEquals(
                BookingCoreResult.Failure(BookingCoreError.EventNotFound),
                bookings.confirmCanonicalHold(SCOPE, hold.id, CONFIRM),
            )
            source.write("DELETE FROM events WHERE id=8803")
            source.write("UPDATE club_operational_nights SET status='CANCELLED' WHERE id=$NIGHT")
            assertEquals(
                BookingCoreResult.Failure(BookingCoreError.EventNotFound),
                bookings.confirmCanonicalHold(SCOPE, hold.id, CONFIRM),
            )
            assertEquals("1", source.count("booking_holds"))
            assertEquals("0", source.count("bookings"))
            assertEquals("1", source.count("canonical_booking_commands"))
        }

    @Test
    fun `authorization and club boundary apply to new commands and durable replay`() =
        fixture { source ->
            val db = Database.connect(source)
            val holds = BookingHoldRepository(db, CLOCK)
            val bookings = BookingRepository(db, CLOCK)
            val missing = BookingCoreResult.Failure(BookingCoreError.TableNotFound)
            assertEquals(missing, holds.protectedHold(tableId = LEGACY))
            assertEquals(missing, holds.protectedHold(scope = SCOPE.copy(clubId = LEGACY), tableId = LEGACY))
            val otherActor = OnboardingActor(8802, "platform")
            assertEquals(missing, holds.protectedHold(scope = SCOPE.copy(actor = otherActor)))
            val hold = holds.protectedHold().success()
            val booked = bookings.confirmCanonicalHold(SCOPE, hold.id, CONFIRM).success()
            source.write(
                "UPDATE client_club_grants SET is_active=FALSE WHERE permission_code='client.club.calendar.write'",
            )
            assertEquals(missing, holds.protectedHold())
            assertEquals(
                BookingCoreResult.Failure(BookingCoreError.HoldNotFound),
                bookings.confirmCanonicalHold(SCOPE, hold.id, CONFIRM),
            )
            assertEquals("1", source.count("bookings"))
            assertEquals("2", source.count("canonical_booking_commands"))
            assertEquals(EVENT, booked.eventId)
        }

    @Test
    fun `open session blocks new hold and confirm but never rewrites receipts`() =
        fixture { source ->
            val db = Database.connect(source)
            val holds = BookingHoldRepository(db, CLOCK)
            val bookings = BookingRepository(db, CLOCK)
            val hold = holds.protectedHold().success()
            source.session(CLUB, START)
            val occupied = BookingCoreResult.Failure(BookingCoreError.TableOccupied)
            assertEquals(occupied, holds.protectedHold(key = "new-protected-hold"))
            assertEquals(occupied, bookings.confirmCanonicalHold(SCOPE, hold.id, CONFIRM))
            assertEquals(hold, holds.protectedHold().success())
            assertEquals(
                BookingCoreResult.Failure(BookingCoreError.IdempotencyConflict),
                holds.protectedHold(guests = 3),
            )
            assertEquals("1", source.count("booking_holds"))
            assertEquals("1", source.count("canonical_booking_commands"))
            assertEquals("0", source.count("bookings"))
            assertEquals("0", source.count("table_deposits"))
            assertEquals("0", source.count("audit_log"))

            source.write(
                "UPDATE table_sessions SET status='CLOSED',open_marker=NULL,closed_at='$START',closed_by=8801",
            )
            assertEquals(
                BookingCoreResult.Failure(BookingCoreError.ActiveHoldExists),
                holds.protectedHold(key = "new-protected-hold"),
            )
            val booking = bookings.confirmCanonicalHold(SCOPE, hold.id, CONFIRM).success()
            source.session(CLUB, START)
            val restarted = BookingRepository(Database.connect(source), CLOCK)
            assertEquals(booking, restarted.confirmCanonicalHold(SCOPE, hold.id, CONFIRM).success())
            assertEquals(
                BookingCoreResult.Failure(BookingCoreError.IdempotencyConflict),
                restarted.confirmCanonicalHold(SCOPE, UUID.randomUUID(), CONFIRM),
            )
            assertEquals("1", source.count("bookings"))
            assertEquals("0", source.count("booking_holds"))
            assertEquals("2", source.count("canonical_booking_commands"))
            assertEquals("0", source.count("table_deposits"))
        }

    @Test
    fun `session keys use linked booking identity and saved legacy night start`() =
        fixture { source ->
            val db = Database.connect(source)
            val holds = BookingHoldRepository(db, CLOCK)
            source.write(
                "INSERT INTO tables(id,club_id,table_number,capacity,min_deposit) VALUES ($HALL_TABLE,$CLUB,2,4,0)",
            )
            source.session(HALL_TABLE, START)
            source.session(CLUB, START.minus(Duration.ofDays(1)))
            assertEquals(
                BookingCoreResult.Failure(BookingCoreError.TableNotFound),
                holds.protectedHold(tableId = HALL_TABLE),
            )
            source.write("UPDATE tables SET active=FALSE WHERE id=$CLUB")
            assertEquals(BookingCoreResult.Failure(BookingCoreError.TableNotFound), holds.protectedHold())
            source.write("UPDATE tables SET active=TRUE WHERE id=$CLUB")
            val hold = holds.protectedHold().success()
            assertEquals(CLUB, hold.tableId)
            assertEquals(START, hold.slotStart)
            assertEquals("1", source.count("booking_holds"))
        }

    @Test
    fun `empty fence is opt in and enabled pair rejects direct legacy writer`() =
        fixture { source ->
            val db = Database.connect(source)
            val holds = BookingHoldRepository(db, CLOCK)
            assertEquals("0", source.count("booking_cutover_fences"))
            assertEquals(true, enableBookingFence(db, CLOCK, SCOPE, CLUB, START, END))
            assertEquals(
                BookingCoreResult.Failure(BookingCoreError.LegacyWriterFenced),
                holds.createHold(CLUB, START, END, 2, TTL, "legacy-after-fence"),
            )
            val protected = holds.protectedHold().success()
            assertEquals(
                BookingCoreResult.Failure(BookingCoreError.LegacyWriterFenced),
                BookingRepository(db, CLOCK).confirmFromHold(protected.id, "legacy-confirm-fenced"),
            )
            assertEquals("1", source.count("booking_holds"))
            assertEquals("0", source.count("bookings"))
            assertEquals(protected.id, holds.protectedHold().success().id)
        }

    private fun DataSource.session(
        tableId: Long,
        start: Instant,
    ) = write(
        "INSERT INTO table_sessions(club_id,table_id,night_start_utc,status,open_marker,opened_at,opened_by) " +
            "VALUES ($CLUB,$tableId,'$start','OPEN',1,'$start',8801)",
    )

    private fun DataSource.seedPhysicalTable() {
        write(
            "INSERT INTO halls(id,club_id,name,geometry_json,geometry_fingerprint) " +
                "VALUES ($CLUB,$CLUB,'Hall','{}','h')",
        )
        write("INSERT INTO hall_zones(hall_id,zone_id,name) VALUES ($CLUB,'main','Main')")
        write(
            "INSERT INTO hall_tables(id,hall_id,table_number,label,capacity,zone_id," +
                "booking_table_id,booking_club_id) " +
                "VALUES ($HALL_TABLE,$CLUB,1,'Table',4,'main',$CLUB,$CLUB)",
        )
    }

    private fun fixture(
        target: String? = null,
        block: suspend (DataSource) -> Unit,
    ) = runBlocking {
        val source =
            JdbcDataSource().apply {
                setURL(
                    "jdbc:h2:mem:canonical-booking-${UUID.randomUUID()};" +
                        "MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
                )
                user = "sa"
            }
        flyway(source, target).migrate()
        try {
            source.seed()
            if (target == null) source.seedPhysicalTable()
            block(source)
        } finally {
            source.write("SHUTDOWN")
        }
    }

    private fun DataSource.seed() {
        write("INSERT INTO users(id,telegram_user_id) VALUES (8801,8801),(8802,8802)")
        write("INSERT INTO clients(id,display_name,is_active) VALUES ($CLUB,'Test client',TRUE)")
        write(
            "INSERT INTO clubs(id,name,timezone,is_active) VALUES " +
                "($CLUB,'Private','UTC',FALSE),($LEGACY,'Legacy','UTC',TRUE)",
        )
        write("INSERT INTO client_clubs(client_id,club_id) VALUES ($CLUB,$CLUB)")
        write("INSERT INTO client_memberships(client_id,user_id,is_active) VALUES ($CLUB,8801,TRUE)")
        write(
            "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active) VALUES " +
                "($CLUB,8801,$CLUB,'client.club.read',TRUE),($CLUB,8801,$CLUB,'client.club.calendar.write',TRUE)",
        )
        write(
            "INSERT INTO tables(id,club_id,table_number,capacity,min_deposit) VALUES " +
                "($CLUB,$CLUB,1,4,0),($LEGACY,$LEGACY,1,4,0)",
        )
        val start = "TIMESTAMP WITH TIME ZONE '2026-11-04 00:00:00+00'"
        val end = "TIMESTAMP WITH TIME ZONE '2026-11-04 08:00:00+00'"
        write(
            "INSERT INTO events(id,club_id,start_at,end_at) VALUES " +
                "($EVENT,$CLUB,$start,$end),($LEGACY,$LEGACY,$start,$end)",
        )
        write(
            "INSERT INTO club_operational_nights(id,client_id,club_id,business_date,timezone,start_at,end_at," +
                "source,status,created_at,updated_at) VALUES ($NIGHT,$CLUB,$CLUB,'2026-11-03','UTC',$start,$end," +
                "'EXCEPTION','SCHEDULED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
        )
        write(
            "INSERT INTO canonical_night_event_links " +
                "(canonical_night_id,event_id,club_id,legacy_start_at,legacy_end_at) " +
                "VALUES ($NIGHT,$EVENT,$CLUB,$start,$end)",
        )
    }

    private fun flyway(
        source: DataSource,
        target: String? = null,
    ): Flyway =
        Flyway
            .configure()
            .dataSource(source)
            .locations("classpath:db/migration/common", "classpath:db/migration/h2")
            .apply { target?.let { target(it) } }
            .load()

    private fun DataSource.count(table: String): String =
        connection.use { checkNotNull(OnboardingSql(it).row("SELECT count(*) AS total FROM $table")?.get("total")) }

    private fun DataSource.write(sql: String) {
        connection.use { connection -> connection.createStatement().use { it.execute(sql) } }
    }

    private suspend fun BookingHoldRepository.protectedHold(
        scope: CanonicalBookingScope = SCOPE,
        tableId: Long = CLUB,
        guests: Int = 2,
        end: Instant = END,
        key: String = HOLD,
    ) = createCanonicalHold(scope, tableId, START, end, guests, TTL, key)

    private fun <T> BookingCoreResult<T>.success(): T =
        when (this) {
            is BookingCoreResult.Success -> value
            is BookingCoreResult.Failure -> throw AssertionError("Expected booking success, got $error")
        }

    private companion object {
        const val CLUB = 8801L
        const val LEGACY = 8802L
        const val NIGHT = 8801L
        const val EVENT = 8801L
        const val HALL_TABLE = 9901L
        const val HOLD = "canonical-hold-test"
        const val CONFIRM = "canonical-confirm-test"
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-11-03T12:00:00Z"), ZoneOffset.UTC)
        val START: Instant = Instant.parse("2026-11-04T00:00:00Z")
        val END: Instant = Instant.parse("2026-11-04T08:00:00Z")
        val TTL: Duration = Duration.ofMinutes(5)
        val SCOPE = CanonicalBookingScope(OnboardingActor(8801, "platform"), CLUB, NIGHT, EVENT, 1)
    }
}
