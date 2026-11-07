package com.example.bot.routes

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

open class ClientOperationalNightsRoutesDataTest : ClientOnboardingRoutesTestBase() {
    @Test
    fun `opening after midnight keeps the business date and id through retime cancellation reopening and restart`() =
        database { source ->
            lateinit var path: String
            lateinit var id: String
            lateinit var reopen: JsonObject
            withApp(source, CLOCK) {
                val clubId = createClub()
                path = "$C/clubs/$clubId/calendar"
                val initial = ok(path, RECIPIENT)
                assertEquals("0", source.nightCount())
                val saved = patch(path, change(initial, weekly = """[{"day":2,$MIDNIGHT}]"""))
                val first = saved.night()
                id = first.string("nightId")
                assertEquals("2026-11-03", first.string("businessDate"))
                assertEquals("2026-11-04T00:00+03:00", first.string("opensAt"))
                assertEquals("2026-11-04T08:00+03:00", first.string("closesAt"))
                assertEquals("Europe/Moscow", first.string("timezone"))
                val shifted = patch(path, change(saved, exceptions = """[{"date":"$DATE",$AFTER_MIDNIGHT}]"""))
                assertEquals(id, shifted.night().string("nightId"))
                assertEquals("2026-11-04T01:00+03:00", shifted.night().string("opensAt"))
                val cancelled = patch(path, change(shifted, exceptions = CLOSED))
                assertTrue(cancelled.nights("nights").none { it.string("businessDate") == DATE })
                val tombstone = cancelled.night("cancelledNights")
                assertEquals(id, tombstone.string("nightId"))
                assertEquals("2026-11-04T01:00+03:00", tombstone.string("opensAt"))
                reopen = change(cancelled, exceptions = """[{"date":"$DATE","mode":"remove"}]""")
                assertEquals(id, patch(path, reopen, REOPEN_KEY).night().string("nightId"))
            }
            withApp(source, CLOCK) {
                val current = ok(path, RECIPIENT)
                assertEquals(id, current.night().string("nightId"))
                assertEquals(current, patch(path, reopen, REOPEN_KEY))
                assertEquals("1", source.scalar("SELECT COUNT(*) FROM club_operational_nights WHERE id=$id"))
                val history =
                    source.scalar(
                        "SELECT COUNT(*) FROM audit_log WHERE action='client.club.calendar.night.reconcile' " +
                            "AND metadata_json LIKE '%$DATE%'",
                    )
                assertEquals("4", history)
            }
        }

    @Test
    fun `legacy event with live booking and hold blocks cancellation and retime without changing any records`() =
        database { source ->
            withApp(source, CLOCK) {
                val clubId = createClub()
                val path = "$C/clubs/$clubId/calendar"
                val saved = patch(path, change(ok(path, RECIPIENT), weekly = """[{"day":2,$MIDNIGHT}]"""))
                source.seedLegacyBooking(clubId)
                assertDependency(path, change(saved, exceptions = CLOSED))
                assertDependency(path, change(saved, exceptions = """[{"date":"$DATE",$AFTER_MIDNIGHT}]"""))
                assertEquals(saved, ok(path, RECIPIENT))
                assertEquals("1", source.scalar("SELECT COUNT(*) FROM events WHERE id=8801"))
                assertEquals(
                    "1",
                    source.scalar("SELECT COUNT(*) FROM bookings WHERE event_id=8801 AND status='SEATED'"),
                )
                assertEquals("1", source.scalar("SELECT COUNT(*) FROM booking_holds WHERE event_id=8801"))
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM club_exceptions WHERE club_id=$clubId"))
            }
        }

    @Test
    fun `legacy event earlier on the business date blocks an after midnight change`() =
        database { source ->
            withApp(source, CLOCK) {
                val clubId = createClub()
                val path = "$C/clubs/$clubId/calendar"
                val saved = patch(path, change(ok(path, RECIPIENT), exceptions = """[{"date":"$DATE",$MIDNIGHT}]"""))
                source.execute(
                    "INSERT INTO events(id,club_id,start_at,end_at) VALUES (8801,$clubId," +
                        "TIMESTAMP WITH TIME ZONE '2026-11-03 16:00:00+00'," +
                        "TIMESTAMP WITH TIME ZONE '2026-11-03 19:00:00+00')",
                )
                assertDependency(path, change(saved, exceptions = CLOSED))
                assertEquals(saved, ok(path, RECIPIENT))
            }
        }

    @Test
    fun `a timestamp based finance record blocks changes even without an event foreign key`() =
        database { source ->
            withApp(source, CLOCK) {
                val clubId = createClub()
                val path = "$C/clubs/$clubId/calendar"
                val saved = patch(path, change(ok(path, RECIPIENT), exceptions = """[{"date":"$DATE",$MIDNIGHT}]"""))
                source.execute(
                    "INSERT INTO shift_reports(club_id,night_start_utc,status,created_at,updated_at) " +
                        "VALUES ($clubId,TIMESTAMP WITH TIME ZONE '2026-11-03 18:00:00+00'," +
                        "'DRAFT',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                )
                assertDependency(path, change(saved, exceptions = CLOSED))
                assertEquals(saved, ok(path, RECIPIENT))
                assertEquals("1", source.scalar("SELECT COUNT(*) FROM shift_reports WHERE club_id=$clubId"))
            }
        }

    @Test
    fun `parallel same key replays and another business date cannot claim an overlapping window`() =
        database { source ->
            withApp(source, CLOCK) {
                val path = "$C/clubs/${createClub()}/calendar"
                val body = change(ok(path, RECIPIENT), exceptions = """[{"date":"$DATE",$MIDNIGHT}]""")
                val responses =
                    coroutineScope {
                        List(2) { async { patch(path, body, "canonical-concurrent") } }.map { it.await() }
                    }
                val saved = responses.first()
                assertEquals(saved, responses.last())
                assertEquals("1", source.nightCount())
                val overlap =
                    change(
                        saved,
                        exceptions = """
                            [{"date":"2026-11-04","mode":"open","open":"00:00","close":"04:00","nextDay":false}]
                        """,
                    )
                requestAs(path, RECIPIENT, overlap, HttpMethod.Patch)
                    .assertClientError(HttpStatusCode.Conflict, "calendar_night_overlap")
                assertEquals(saved, ok(path, RECIPIENT))
                assertEquals("1", source.nightCount())
            }
        }

    @Test
    fun `failed canonical audit rolls back calendar rules nights and idempotency record`() =
        database { source ->
            withApp(source, CLOCK) {
                val path = "$C/clubs/${createClub()}/calendar"
                val initial = ok(path, RECIPIENT)
                val body = change(initial, exceptions = """[{"date":"$DATE",$MIDNIGHT}]""")
                source.execute(
                    "ALTER TABLE audit_log ADD CONSTRAINT canonical_audit_failure " +
                        "CHECK (action <> 'client.club.calendar.night.reconcile')",
                )
                requestAs(path, RECIPIENT, body, HttpMethod.Patch, "canonical-rollback")
                    .assertClientError(HttpStatusCode.InternalServerError, "internal_error")
                assertEquals(initial, ok(path, RECIPIENT))
                assertEquals("0", source.nightCount())
                source.execute("ALTER TABLE audit_log DROP CONSTRAINT canonical_audit_failure")
                assertEquals("2", patch(path, body, "canonical-rollback").string("version"))
                assertEquals("1", source.nightCount())
            }
        }

    @Test
    fun `upgrade keeps v68 rules and get never materializes before an explicit reconcile`() =
        databaseAt("68") { source ->
            source.seedLegacyCalendar()
            val vendor =
                source.connection.use { if (it.metaData.databaseProductName == "PostgreSQL") "postgresql" else "h2" }
            assertEquals(1, migrateBookingRulesFixture(source, vendor, "69"))
            assertEquals(1, migrateBookingRulesFixture(source, vendor, "70"))
            assertEquals(1, migrateBookingRulesFixture(source, vendor, "71"))
            assertEquals(1, migrateBookingRulesFixture(source, vendor, "72"))
            assertEquals(1, migrateBookingRulesFixture(source, vendor, "73"))
            assertEquals(1, migrateBookingRulesFixture(source, vendor))
            withApp(source, CLOCK) {
                val path = "$C/clubs/8801/calendar"
                val initial = ok(path, RECIPIENT)
                assertEquals("2026-11-03T22:00+03:00", initial.night().string("opensAt"))
                assertEquals("2026-11-04T06:00+03:00", initial.night().string("closesAt"))
                assertEquals("0", source.nightCount())
                val sync = JsonObject(change(initial) + ("reconcile" to JsonPrimitive(true)))
                val saved = patch(path, sync)
                assertEquals("2", saved.string("version"))
                assertEquals("8", source.nightCount())
                val repeated = patch(path, JsonObject(change(saved) + ("reconcile" to JsonPrimitive(true))))
                assertEquals(saved, repeated)
                assertEquals("8", source.nightCount())
            }
        }

    @Test
    fun `a started canonical night keeps its saved interval`() =
        database { source ->
            lateinit var path: String
            withApp(source, CLOCK) {
                path = "$C/clubs/${createClub()}/calendar"
                patch(path, change(ok(path, RECIPIENT), exceptions = """[{"date":"$DATE",$MIDNIGHT}]"""))
            }
            val afterOpen = Clock.fixed(Instant.parse("2026-11-03T22:00:00Z"), ZoneOffset.UTC)
            withApp(source, afterOpen) {
                val current = ok(path, RECIPIENT)
                requestAs(path, RECIPIENT, change(current, exceptions = CLOSED), HttpMethod.Patch)
                    .assertClientError(HttpStatusCode.Conflict, "calendar_night_started")
                assertEquals(current, ok(path, RECIPIENT))
            }
        }

    private suspend fun ApplicationTestBuilder.assertDependency(
        path: String,
        body: JsonObject,
    ) {
        val response = requestAs(path, RECIPIENT, body, HttpMethod.Patch)
        assertEquals(HttpStatusCode.Conflict, response.status)
        val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("calendar_night_dependencies", error.string("code"))
        assertEquals(DATE, error.getValue("details").jsonObject.string("businessDate"))
    }

    private suspend fun ApplicationTestBuilder.createClub(): String {
        val invite = ok("$P/invitations", OWNER, obj("clientName" to "Club", "recipientLabel" to "Owner"))
        ok(
            "$C/invitations/${invite.string("id")}/claim",
            RECIPIENT,
            obj("token" to invite.string("token"), "expectedVersion" to invite.string("version")),
        )
        return ok(
            "$C/clients/${invite.string("clientId")}/clubs",
            RECIPIENT,
            obj("venueName" to "Test club", "countryCode" to "RU", "cityId" to "524901", "address" to "Test street"),
        ).string("id")
    }

    private suspend fun ApplicationTestBuilder.patch(
        path: String,
        body: JsonObject,
        key: String =
            java.util.UUID
                .randomUUID()
                .toString(),
    ): JsonObject = ok(path, RECIPIENT, body, HttpMethod.Patch, key)

    private fun change(
        current: JsonObject,
        weekly: String = "[]",
        exceptions: String = "[]",
    ): JsonObject =
        JsonObject(
            version(current) + obj("timezone" to current.string("timezone")) +
                mapOf(
                    "weekly" to Json.parseToJsonElement(weekly),
                    "holidays" to JsonArray(emptyList()),
                    "exceptions" to Json.parseToJsonElement(exceptions),
                ),
        )

    private fun DataSource.seedLegacyCalendar() {
        execute("INSERT INTO users(id,telegram_user_id) VALUES (8801,$RECIPIENT)")
        execute("INSERT INTO clients(id,display_name,is_active) VALUES (8801,'Legacy client',TRUE)")
        execute("INSERT INTO client_memberships(client_id,user_id,is_active) VALUES (8801,8801,TRUE)")
        execute("INSERT INTO clubs(id,name,timezone,is_active) VALUES (8801,'Legacy club','Europe/Moscow',FALSE)")
        execute("INSERT INTO client_clubs(client_id,club_id) VALUES (8801,8801)")
        execute(
            "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active) VALUES " +
                "(8801,8801,8801,'client.club.read',TRUE),(8801,8801,8801,'client.club.calendar.write',TRUE)",
        )
        execute(
            "INSERT INTO club_hours(club_id,dow,open_local,close_local,close_next_day) " +
                "VALUES (8801,2,'22:00','06:00',TRUE)",
        )
    }

    private fun DataSource.seedLegacyBooking(clubId: String) {
        execute("INSERT INTO events(id,club_id,start_at,end_at) VALUES (8801,$clubId,$START,$END)")
        execute(
            "INSERT INTO tables(id,club_id,table_number,capacity,min_deposit) VALUES (8801,$clubId,1,2,0)",
        )
        execute(
            "INSERT INTO bookings(event_id,club_id,table_id,table_number,guests_count,min_deposit,total_deposit," +
                "slot_start,slot_end,status,qr_secret,idempotency_key) " +
                "VALUES (8801,$clubId,8801,1,2,0,0,$START,$END,'SEATED','synthetic','canonical-legacy-booking')",
        )
        execute(
            "INSERT INTO booking_holds(event_id,table_id,guests_count,min_deposit,expires_at,idempotency_key," +
                "slot_start,slot_end) VALUES (8801,8801,2,0,$END,'canonical-legacy-hold',$START,$END)",
        )
    }

    private fun DataSource.execute(statement: String) {
        connection.use { connection -> connection.createStatement().use { it.execute(statement) } }
    }

    private fun DataSource.nightCount() = scalar("SELECT COUNT(*) FROM club_operational_nights")

    private fun JsonObject.nights(field: String) = getValue(field).jsonArray.map { it.jsonObject }

    private fun JsonObject.night(field: String = "nights") = nights(field).single { it.string("businessDate") == DATE }

    private companion object {
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-11-03T12:00:00Z"), ZoneOffset.UTC)
        const val DATE = "2026-11-03"
        const val MIDNIGHT = """"mode":"open","open":"00:00","close":"08:00","startNextDay":true,"nextDay":false"""
        const val AFTER_MIDNIGHT =
            """"mode":"open","open":"01:00","close":"08:00","startNextDay":true,"nextDay":false"""
        const val CLOSED = """[{"date":"$DATE","mode":"closed"}]"""
        const val REOPEN_KEY = "canonical-reopen"
        const val START = "TIMESTAMP WITH TIME ZONE '2026-11-03 21:00:00+00'"
        const val END = "TIMESTAMP WITH TIME ZONE '2026-11-04 05:00:00+00'"
    }
}
