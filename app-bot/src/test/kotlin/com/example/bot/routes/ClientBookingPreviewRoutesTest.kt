package com.example.bot.routes

import com.example.bot.booking.a3.BookingState
import com.example.bot.booking.a3.ConfirmResult
import com.example.bot.booking.a3.HoldResult
import com.example.bot.data.clubs.EventsDbRepository
import com.example.bot.data.layout.LayoutDbRepository
import com.example.bot.data.privacy.JdbcLegacyClubAccess
import io.ktor.client.request.get
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

open class ClientBookingPreviewRoutesTest : ClientOnboardingRoutesTestBase() {
    @Test
    fun `owner sees stored hall and the verified DST night while even identical table IDs stay blocked`() =
        database { source ->
            withApp(source, CLOCK) {
                val club = createClub(RECIPIENT)
                val clubId = club.string("id")
                val base = "$C/clubs/$clubId"
                val path = "$base/booking-preview"
                val profile =
                    ok(
                        "$base/profile",
                        RECIPIENT,
                        JsonObject(version(club) + obj("timezone" to "Europe/Berlin")),
                        HttpMethod.Patch,
                    )
                assertEquals("true", profile.string("canPreviewBooking"))
                val night = createNight(base)
                val rulesPath = "$base/booking-rules"
                val rules = ok(rulesPath, RECIPIENT)
                val changedRules =
                    Json
                        .parseToJsonElement(
                            """{"cutoffMinutes":120,"arrivalMinutes":90,"holdMinutes":10,"retentionMinutes":30}""",
                        ).jsonObject
                ok(rulesPath, RECIPIENT, JsonObject(changedRules + version(rules)), HttpMethod.Patch)
                val hall = ok("$base/halls", RECIPIENT, obj("name" to "Saved hall")).firstHall()
                val hallId = hall.string("id")
                source.seedDistinctTables(hallId, clubId)
                source.execute(
                    "INSERT INTO events(id,club_id,start_at,end_at) VALUES " +
                        "(8801,$clubId,'2026-10-25T00:00:00Z','2026-10-25T07:00:00Z')",
                )
                val initial = ok(path, RECIPIENT)
                assertEquals("TABLE_MAPPING_REQUIRED", initial.string("bookingBlocker"))
                assertEquals("UNLINKED", initial.firstNight().string("eventStatus"))
                assertEquals("2026-10-24", initial.firstNight().string("businessDate"))
                assertEquals("2026-10-25T02:00+02:00", initial.firstNight().string("opensAt"))
                assertEquals("2026-10-25T08:00+01:00", initial.firstNight().string("closesAt"))
                assertEquals("2026-10-25T02:30+01:00", initial.firstNight().string("arrivalBy"))
                val table =
                    initial
                        .firstHall()
                        .getValue("tables")
                        .jsonArray
                        .single()
                        .jsonObject
                assertEquals("8801", table.string("id"))
                assertEquals("4", table.string("capacity"))
                assertEquals("5000", source.scalar("SELECT min_deposit FROM hall_tables WHERE id=8801"))
                assertFalse("minDeposit" in table || "bookingDeposit" in table)
                assertEquals("false", table.string("bookingLinked"))
                assertFalse("availability" in table)
                val calendar = ok("$base/calendar", RECIPIENT)
                ok(
                    "$C/calendar/nights/${night.string("nightId")}/legacy-event",
                    RECIPIENT,
                    JsonObject(version(calendar) + obj("eventId" to "8801")),
                )
                assertEquals("VERIFIED", ok(path, RECIPIENT).firstNight().string("eventStatus"))
                source.execute("UPDATE events SET end_at='2026-10-25T07:30:00Z' WHERE id=8801")
                assertEquals("INVALID_LINK", ok(path, RECIPIENT).firstNight().string("eventStatus"))
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM booking_holds"))
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM bookings"))
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM payments"))
            }
        }

    @Test
    fun `preview needs a private club current grant and creator identity`() =
        database { source ->
            withApp(source, CLOCK) {
                val club = createClub(RECIPIENT)
                val other = createClub(FOREIGN)
                val clubId = club.string("id")
                val clientId = club.string("clientId")
                val path = "$C/clubs/$clubId/booking-preview"
                client.get(path).assertClientError(HttpStatusCode.Unauthorized, "unauthorized")
                requestAs(path, OWNER).assertClientError(HttpStatusCode.NotFound, "not_found")
                requestAs(path, FOREIGN).assertClientError(HttpStatusCode.NotFound, "not_found")
                val foreignId = source.scalar("SELECT id FROM users WHERE telegram_user_id=$FOREIGN")
                source.execute(
                    "INSERT INTO client_memberships(client_id,user_id,is_active) VALUES ($clientId,$foreignId,TRUE)",
                )
                source.execute(
                    "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active) " +
                        "VALUES ($clientId,$foreignId,$clubId,'client.club.read',TRUE)",
                )
                val shared = ok("$C/clubs/$clubId/profile", FOREIGN)
                assertEquals("false", shared.string("canPreviewBooking"))
                requestAs(path, FOREIGN).assertClientError(HttpStatusCode.NotFound, "not_found")
                assertTrue(ok(path, RECIPIENT).getValue("halls").jsonArray.isEmpty())
                source.execute("UPDATE clubs SET is_active=TRUE WHERE id=$clubId")
                requestAs(path, RECIPIENT).assertClientError(HttpStatusCode.NotFound, "not_found")
                val otherId = other.string("id")
                source.execute("UPDATE client_club_grants SET is_active=FALSE WHERE club_id=$otherId")
                requestAs("$C/clubs/$otherId/booking-preview", FOREIGN)
                    .assertClientError(HttpStatusCode.NotFound, "not_found")
            }
        }

    @Test
    fun `availability reads the six states from explicit linked identities and legacy night keys`() =
        database { source ->
            withApp(source, CLOCK) {
                val f = availabilityFixture(source)
                val unlinked = ok(f.path, RECIPIENT)
                assertEquals("EVENT_UNLINKED", unlinked.tables().getValue("9001").string("reason"))
                linkAvailability(f)
                source.hold(2, f.eventId, "2026-10-23T12:01:00Z")
                source.hold(3, f.eventId, "2026-10-23T12:00:00Z")
                source.booking(4, f, "BOOKED")
                source.booking(5, f, "SEATED")
                source.booking(6, f, "CANCELLED")
                source.booking(12, f, "BOOKED")
                source.session(7, f, true, START)
                source.session(8, f, false, START)
                source.session(12, f, true, START)
                source.session(13, f, true, "2026-10-24T00:00:00Z")
                val watched =
                    listOf(
                        "events",
                        "bookings",
                        "booking_holds",
                        "table_sessions",
                        "table_deposits",
                        "shift_reports",
                        "audit_log",
                        "client_commands",
                        "hall_tables",
                        "tables",
                    )
                val before = watched.map { source.scalar("SELECT COUNT(*) FROM $it") }
                val snapshot = ok(f.path, RECIPIENT)
                assertEquals("2026-10-24", snapshot.string("businessDate"))
                assertEquals("2026-10-23T12:00:00Z", snapshot.string("sampledAt"))
                assertEquals(f.hallId, snapshot.string("hallId"))
                assertEquals(f.nightId, snapshot.string("nightId"))
                val actual = snapshot.tables()
                val statuses =
                    listOf(
                        "FREE",
                        "HOLD",
                        "FREE",
                        "BOOKED",
                        "BOOKED",
                        "FREE",
                        "OCCUPIED",
                        "FREE",
                        "UNKNOWN",
                        "UNAVAILABLE",
                        "UNAVAILABLE",
                        "UNKNOWN",
                        "FREE",
                    )
                assertEquals(statuses, (1..13).map { actual.getValue("${9000 + it}").string("status") })
                assertEquals("TABLE_UNLINKED", actual.getValue("9009").string("reason"))
                assertEquals("BOOKING_TABLE_INACTIVE", actual.getValue("9010").string("reason"))
                assertEquals("OCCUPANCY_CONFLICT", actual.getValue("9012").string("reason"))
                assertTrue(actual.values.all { it.keys.all(setOf("id", "status", "reason")::contains) })
                assertEquals(before, watched.map { source.scalar("SELECT COUNT(*) FROM $it") })

                source.execute("UPDATE events SET end_at='2026-10-25T07:30:00Z' WHERE id=${f.eventId}")
                assertEquals("EVENT_MISMATCH", ok(f.path, RECIPIENT).tables().getValue("9001").string("reason"))
                source.execute("UPDATE club_operational_nights SET status='CANCELLED' WHERE id=${f.nightId}")
                assertEquals("NIGHT_CANCELLED", ok(f.path, RECIPIENT).tables().getValue("9001").string("reason"))
                source.execute("UPDATE club_operational_nights SET status='SCHEDULED' WHERE id=${f.nightId}")
                source.execute("UPDATE events SET end_at='$END' WHERE id=${f.eventId}")
                source.execute("ALTER TABLE table_sessions RENAME TO unavailable_sessions")
                requestAs(f.path, RECIPIENT).assertClientError(HttpStatusCode.InternalServerError, "internal_error")
            }
        }

    @Test
    fun `availability denies foreign scopes and revoked authority and ignores uncommitted occupancy`() =
        database { source ->
            withApp(source, CLOCK) {
                val f = availabilityFixture(source)
                linkAvailability(f)
                val other = createClub(FOREIGN).string("id")
                val foreignHall = ok("$C/clubs/$other/halls", FOREIGN, obj("name" to "Other")).firstHall().string("id")
                val foreignNight = createNight("$C/clubs/$other", FOREIGN).string("nightId")
                client.get(f.path).assertClientError(HttpStatusCode.Unauthorized, "unauthorized")
                requestAs(f.path, FOREIGN).assertClientError(HttpStatusCode.NotFound, "not_found")
                requestAs(f.path.replace("/halls/${f.hallId}", "/halls/$foreignHall"), RECIPIENT)
                    .assertClientError(HttpStatusCode.NotFound, "not_found")
                requestAs(f.path.replace("/nights/${f.nightId}", "/nights/$foreignNight"), RECIPIENT)
                    .assertClientError(HttpStatusCode.NotFound, "not_found")
                source.connection.use { writer ->
                    writer.autoCommit = false
                    writer.createStatement().use {
                        it.execute(holdSql(1, f.eventId, "2026-10-23T12:01:00Z"))
                    }
                    val beforeCommit = withTimeout(5_000) { ok(f.path, RECIPIENT) }
                    assertEquals("FREE", beforeCommit.tables().getValue("9001").string("status"))
                    writer.commit()
                }
                assertEquals("HOLD", ok(f.path, RECIPIENT).tables().getValue("9001").string("status"))
                source.execute("UPDATE client_club_grants SET is_active=FALSE WHERE club_id=${f.clubId}")
                requestAs(f.path, RECIPIENT).assertClientError(HttpStatusCode.NotFound, "not_found")
            }
        }

    @Test
    fun `A3 and direct promoter assignment reject a fenced private club inside BookingState`() =
        database { source ->
            withApp(source, CLOCK) {
                val fixture = availabilityFixture(source)
                linkAvailability(fixture)
                source.execute("INSERT INTO booking_cutover_fences VALUES (${fixture.clubId},${fixture.nightId},9101)")
                val db = Database.connect(source)
                BookingState(
                    LayoutDbRepository(db),
                    EventsDbRepository(db),
                    isLegacyClub = JdbcLegacyClubAccess(source)::isLegacyClub,
                ).use { state ->
                    val clubId = fixture.clubId.toLong()
                    val eventId = fixture.eventId.toLong()
                    assertIs<HoldResult.Error>(state.hold(RECIPIENT, clubId, 9001, eventId, 2, "a3-test", "same"))
                    assertIs<HoldResult.Error>(
                        state.hold(
                            RECIPIENT,
                            clubId,
                            9001,
                            eventId,
                            1,
                            "promoter-test",
                            "same",
                            promoterId = RECIPIENT,
                        ),
                    )
                    assertIs<ConfirmResult.Error>(state.confirm(RECIPIENT, clubId, 1, "confirm-test", "same"))
                    assertTrue(state.findUserBookings(RECIPIENT).isEmpty())
                }
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM bookings"))
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM booking_holds"))
            }
        }

    private suspend fun ApplicationTestBuilder.availabilityFixture(source: DataSource): AvailabilityFixture {
        val clubId = createClub(RECIPIENT).string("id")
        val base = "$C/clubs/$clubId"
        val nightId = createNight(base).string("nightId")
        val hallId = ok("$base/halls", RECIPIENT, obj("name" to "Main")).firstHall().string("id")
        source.execute("INSERT INTO events(id,club_id,start_at,end_at) VALUES (8801,$clubId,'$START','$END')")
        for (number in 1..13) {
            val table = 9100 + number
            source.execute(
                "INSERT INTO tables(id,club_id,table_number,capacity,min_deposit,active) " +
                    "VALUES ($table,$clubId,$number,4,0,${number != 10})",
            )
            val link = if (number == 9) "NULL,NULL" else "$table,$clubId"
            source.execute(
                "INSERT INTO hall_tables(id,hall_id,table_number,label,capacity,zone_id,is_active," +
                    "booking_table_id,booking_club_id) VALUES " +
                    "(${9000 + number},$hallId,$number,'Test table',4,'main',${number != 11},$link)",
            )
        }
        return AvailabilityFixture(clubId, hallId, nightId, 8801)
    }

    private suspend fun ApplicationTestBuilder.linkAvailability(f: AvailabilityFixture) {
        val calendar = ok("$C/clubs/${f.clubId}/calendar", RECIPIENT)
        ok(
            "$C/calendar/nights/${f.nightId}/legacy-event",
            RECIPIENT,
            JsonObject(version(calendar) + obj("eventId" to f.eventId.toString())),
        )
    }

    private fun DataSource.hold(
        number: Int,
        eventId: Int,
        expires: String,
    ) = execute(holdSql(number, eventId, expires))

    private fun holdSql(
        number: Int,
        eventId: Int,
        expires: String,
    ): String =
        "INSERT INTO booking_holds(id,event_id,table_id,guests_count,min_deposit,slot_start,slot_end," +
            "expires_at,idempotency_key) VALUES " +
            "('${UUID.randomUUID()}',$eventId,${9100 + number},2,0,'$START','$END','$expires','hold-$number')"

    private fun DataSource.booking(
        number: Int,
        f: AvailabilityFixture,
        status: String,
    ) = execute(
        "INSERT INTO bookings(id,event_id,club_id,table_id,table_number,guests_count,min_deposit,total_deposit," +
            "slot_start,slot_end,status,qr_secret,idempotency_key) VALUES " +
            "('${UUID.randomUUID()}',${f.eventId},${f.clubId},${9100 + number},$number,2,0,0," +
            "'$START','$END','$status','synthetic-$number','book-$number')",
    )

    private fun DataSource.session(
        number: Int,
        f: AvailabilityFixture,
        open: Boolean,
        start: String,
    ) {
        val actor = scalar("SELECT id FROM users WHERE telegram_user_id=$RECIPIENT")
        val state = if (open) "'OPEN',1,NULL,NULL" else "'CLOSED',NULL,'$START',$actor"
        execute(
            "INSERT INTO table_sessions(club_id,table_id,night_start_utc,opened_at,opened_by," +
                "status,open_marker,closed_at,closed_by) " +
                "VALUES (${f.clubId},${9100 + number},'$start','$START',$actor,$state)",
        )
    }

    private fun JsonObject.tables() = getValue("tables").jsonArray.map { it.jsonObject }.associateBy { it.string("id") }

    private data class AvailabilityFixture(
        val clubId: String,
        val hallId: String,
        val nightId: String,
        val eventId: Int,
    ) {
        val path get() = "$C/clubs/$clubId/booking-preview/nights/$nightId/halls/$hallId/availability"
    }

    private suspend fun ApplicationTestBuilder.createClub(user: Long): JsonObject {
        val invite = ok("$P/invitations", OWNER, obj("clientName" to "Tenant $user", "recipientLabel" to "Owner"))
        ok(
            "$C/invitations/${invite.string("id")}/claim",
            user,
            obj("token" to invite.string("token"), "expectedVersion" to invite.string("version")),
        )
        return ok(
            "$C/clients/${invite.string("clientId")}/clubs",
            user,
            obj("venueName" to "Test club", "countryCode" to "RU", "cityId" to "524901", "address" to "Test street"),
        )
    }

    private suspend fun ApplicationTestBuilder.createNight(
        base: String,
        owner: Long = RECIPIENT,
    ): JsonObject {
        val profile = ok("$base/profile", owner)
        if (profile.string("timezone") != "Europe/Berlin") {
            ok(
                "$base/profile",
                owner,
                JsonObject(version(profile) + obj("timezone" to "Europe/Berlin")),
                HttpMethod.Patch,
            )
        }
        val calendar = ok("$base/calendar", owner)
        val delta =
            Json
                .parseToJsonElement(
                    """
                    {"weekly":[],"holidays":[],"exceptions":[
                      {"date":"2026-10-24","mode":"open","open":"02:00","close":"08:00","startNextDay":true,"nextDay":false}
                    ]}
                    """.trimIndent(),
                ).jsonObject
        val saved =
            ok(
                "$base/calendar",
                owner,
                JsonObject(delta + version(calendar) + obj("timezone" to "Europe/Berlin")),
                HttpMethod.Patch,
            )
        return saved.firstNight()
    }

    private fun DataSource.seedDistinctTables(
        hallId: String,
        clubId: String,
    ) {
        execute(
            "INSERT INTO hall_tables(id,hall_id,table_number,label,capacity,min_deposit,zone_id) " +
                "VALUES (8801,$hallId,7,'Saved table',4,5000,'main')",
        )
        execute(
            "INSERT INTO tables(id,club_id,table_number,capacity,min_deposit) VALUES (8801,$clubId,7,12,9000)",
        )
    }

    private fun DataSource.execute(sql: String) {
        connection.use { connection ->
            connection.createStatement().use { it.execute(sql) }
        }
    }

    private fun JsonObject.firstNight() = getValue("nights").jsonArray.first().jsonObject

    private fun JsonObject.firstHall() = getValue("halls").jsonArray.first().jsonObject

    private companion object {
        const val START = "2026-10-25T00:00:00Z"
        const val END = "2026-10-25T07:00:00Z"
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC)
    }
}

@Tag("it")
class ClientBookingPreviewPostgresIT : ClientBookingPreviewRoutesTest() {
    override fun database(block: (DataSource) -> Unit) {
        PostgreSQLContainer<Nothing>("postgres:16-alpine").use { postgres ->
            postgres.start()
            val source =
                PGSimpleDataSource().apply {
                    setUrl(postgres.jdbcUrl)
                    user = postgres.username
                    password = postgres.password
                }
            Flyway
                .configure()
                .dataSource(source)
                .locations("classpath:db/migration/common", "classpath:db/migration/postgresql")
                .load()
                .migrate()
            block(source)
        }
    }
}
