package com.example.bot.routes

import com.example.bot.data.booking.core.BookingCoreResult
import com.example.bot.data.booking.core.BookingHold
import com.example.bot.data.booking.core.BookingHoldRepository
import com.example.bot.data.clubs.EventsDbRepository
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

open class ClientNightEventLinksRoutesDataTest : ClientOnboardingRoutesTestBase() {
    @Test
    fun `upgrade preserves legacy rows and legacy readers and writers on an empty link table`() =
        databaseAt("69") { source ->
            source.cabinet()
            source.execute(
                "INSERT INTO club_operational_nights(id,client_id,club_id,business_date,timezone,start_at,end_at," +
                    "source,status,created_at,updated_at) VALUES (8801,8801,8801,'$DATE','Europe/Moscow'," +
                    "${ts(START)},${ts(END)},'EXCEPTION','SCHEDULED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
            )
            source.execute("INSERT INTO clubs(id,name,timezone,is_active) VALUES (8802,'Legacy','Europe/Moscow',TRUE)")
            source.event(clubId = 8802)
            source.execute("INSERT INTO tables(id,club_id,table_number,capacity,min_deposit) VALUES (8801,8802,1,4,0)")
            source.execute(
                "INSERT INTO bookings(event_id,club_id,table_id,table_number,guests_count,min_deposit,total_deposit," +
                    "slot_start,slot_end,status,qr_secret,idempotency_key) VALUES " +
                    "(8801,8802,8801,1,2,0,0,${ts(START)},${ts(END)},'CANCELLED','synthetic','legacy-booking')",
            )
            source.execute(
                "INSERT INTO shift_reports(club_id,night_start_utc,status,created_at,updated_at) " +
                    "VALUES (8802,${ts(START)},'DRAFT',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
            )
            val database = Database.connect(source)
            val holdRepository = BookingHoldRepository(database, CLOCK)
            val legacyEvent = EventsDbRepository(database).findById(8802, 8801)
            assertEquals(Instant.parse(START), legacyEvent?.startUtc)
            val firstHold = runBlocking { holdRepository.legacyHold("before-v070") }
            val vendor =
                source.connection.use {
                    if (it.metaData.databaseProductName ==
                        "PostgreSQL"
                    ) {
                        "postgresql"
                    } else {
                        "h2"
                    }
                }

            assertEquals(1, migrateBookingRulesFixture(source, vendor, "70"))
            assertEquals(1, migrateBookingRulesFixture(source, vendor, "71"))
            assertEquals(1, migrateBookingRulesFixture(source, vendor, "72"))
            assertEquals(1, migrateBookingRulesFixture(source, vendor))
            assertEquals("0", source.linkCount())
            assertEquals(
                "1",
                source.scalar(
                    "SELECT COUNT(*) FROM club_operational_nights WHERE id=8801 AND business_date='$DATE' " +
                        "AND start_at=${ts(START)} AND end_at=${ts(END)}",
                ),
            )
            assertEquals(legacyEvent, EventsDbRepository(database).findById(8802, 8801))
            assertEquals(
                "1",
                source.scalar(
                    "SELECT COUNT(*) FROM bookings WHERE event_id=8801 " +
                        "AND slot_start=${ts(START)} AND slot_end=${ts(END)}",
                ),
            )
            assertEquals(
                "1",
                source.scalar("SELECT COUNT(*) FROM shift_reports WHERE club_id=8802 AND night_start_utc=${ts(START)}"),
            )
            runBlocking {
                assertEquals(firstHold, holdRepository.findHoldByIdempotencyKey("before-v070"))
                holdRepository.cleanupExpired(Instant.parse(END))
                assertEquals(8801L, holdRepository.legacyHold("after-v070").eventId)
            }
            assertEquals("0", source.linkCount())
        }

    @Test
    fun `midnight link survives restart and replay checks original command and current grants`() =
        database { source ->
            source.cabinet()
            lateinit var path: String
            lateinit var body: JsonObject
            lateinit var receipt: JsonObject
            withApp(source, CLOCK) {
                path = materialize()
                source.event()
                val preview = ok(path, RECIPIENT)
                assertEquals(DATE, preview.string("businessDate"))
                assertEquals("EXACT_MATCH", preview.string("classification"))
                assertEquals("8801", preview.string("matchingEventId"))
                assertEquals("0", source.linkCount())
                body = linkBody(preview)
                receipt = ok(path, RECIPIENT, body, key = KEY)
                assertEquals(DATE, receipt.string("businessDate"))
                assertEquals("8801", receipt.string("eventId"))
                assertEquals(START, receipt.string("legacyStartsAt"))
                assertEquals(END, receipt.string("legacyEndsAt"))
                assertEquals("3", ok(path, RECIPIENT).string("version"))
            }
            withApp(source, CLOCK) {
                assertEquals(receipt, ok(path, RECIPIENT, body, key = KEY))
                requestAs(path, RECIPIENT, JsonObject(body + obj("eventId" to "8802")), key = KEY)
                    .assertClientError(HttpStatusCode.Conflict, "idempotency_conflict")
                requestAs(path, RECIPIENT, body).assertClientError(HttpStatusCode.Conflict, "version_conflict")
                source.execute(
                    "UPDATE client_club_grants SET is_active=FALSE WHERE permission_code='client.club.calendar.write'",
                )
                assertEquals("8801", ok(path, RECIPIENT).string("linkedEventId"))
                requestAs(path, RECIPIENT, body, key = KEY).assertClientError(HttpStatusCode.NotFound, "not_found")
                source.execute("UPDATE client_memberships SET is_active=FALSE WHERE user_id=8801")
                requestAs(path, RECIPIENT).assertClientError(HttpStatusCode.NotFound, "not_found")
                assertEquals("1", source.linkCount())
                assertEquals("1", source.linkAudits())
            }
        }

    @Test
    fun `classifier reports absence boundary mismatch and multiple candidates without writes`() =
        database { source ->
            source.cabinet()
            withApp(source, CLOCK) {
                val path = materialize()
                assertEquals("NO_EVENT", ok(path, RECIPIENT).string("classification"))
                source.event(end = "2026-11-04T04:59:59Z")
                val mismatch = ok(path, RECIPIENT)
                assertEquals("MISMATCH", mismatch.string("classification"))
                requestAs(path, RECIPIENT, linkBody(mismatch)).assertConflict("mismatch")
                source.execute("UPDATE events SET end_at=${ts(END)} WHERE id=8801")
                source.event(8802, start = "2026-11-03T16:00:00Z", end = "2026-11-03T19:00:00Z")
                val ambiguous = ok(path, RECIPIENT)
                assertEquals("AMBIGUOUS", ambiguous.string("classification"))
                requestAs(path, RECIPIENT, linkBody(ambiguous)).assertConflict("ambiguous")
                assertEquals("0", source.linkCount())
                assertEquals("0", source.linkAudits())
                assertEquals("2", source.scalar("SELECT COUNT(*) FROM events WHERE id IN (8801,8802)"))
            }
        }

    @Test
    fun `DST overlap matches the stored instant and rejects the other occurrence of the same wall time`() =
        database { source ->
            source.cabinet(zone = "Europe/Berlin")
            val beforeOverlap = Clock.fixed(Instant.parse("2026-10-23T12:00:00Z"), ZoneOffset.UTC)
            withApp(source, beforeOverlap) {
                val path = materialize("2026-10-24", open = "02:00")
                source.event(start = "2026-10-25T01:00:00Z", end = "2026-10-25T07:00:00Z")
                assertEquals("MISMATCH", ok(path, RECIPIENT).string("classification"))
                source.execute("UPDATE events SET start_at=${ts("2026-10-25T00:00:00Z")} WHERE id=8801")
                val exact = ok(path, RECIPIENT)
                assertEquals("EXACT_MATCH", exact.string("classification"))
                val receipt = ok(path, RECIPIENT, linkBody(exact))
                assertEquals("2026-10-24", receipt.string("businessDate"))
                assertEquals("2026-10-25T00:00:00Z", receipt.string("legacyStartsAt"))
            }
        }

    @Test
    fun `authority and schema reject cross club and both duplicate directions and linked deletes`() =
        database { source ->
            source.cabinet()
            source.cabinet(8802, FOREIGN)
            withApp(source, CLOCK) {
                val path = materialize()
                val other = materialize(clubId = 8802, user = FOREIGN)
                val preview = ok(path, RECIPIENT)
                val nightId = preview.string("nightId")
                val otherNightId = ok(other, FOREIGN).string("nightId")
                source.event()
                source.event(8802, clubId = 8802)
                source.execute("INSERT INTO users(id,telegram_user_id) VALUES (8803,$OWNER)")
                source.execute("INSERT INTO user_roles(user_id,role_code,scope_type) VALUES (8803,'OWNER','GLOBAL')")

                requestAs(path, OWNER).assertClientError(HttpStatusCode.NotFound, "not_found")
                requestAs(path, FOREIGN, linkBody(preview)).assertClientError(HttpStatusCode.NotFound, "not_found")
                requestAs(
                    path,
                    RECIPIENT,
                    linkBody(preview, "8802"),
                ).assertClientError(HttpStatusCode.NotFound, "not_found")
                assertFailsWith<SQLException> { source.rawLink(nightId, 8802, 8801) }
                assertFailsWith<SQLException> { source.rawLink(otherNightId, 8801, 8801) }

                ok(path, RECIPIENT, linkBody(preview))
                source.event(8803, start = "2026-11-03T16:00:00Z", end = "2026-11-03T19:00:00Z")
                requestAs(path, RECIPIENT, linkBody(ok(path, RECIPIENT), "8803")).assertConflict("already_linked")
                assertFailsWith<SQLException> { source.rawLink(nightId, 8803, 8801) }
                val second = materialize("2026-11-06")
                val secondPreview = ok(second, RECIPIENT)
                requestAs(second, RECIPIENT, linkBody(secondPreview)).assertConflict("already_linked")
                assertFailsWith<SQLException> { source.rawLink(secondPreview.string("nightId"), 8801, 8801) }
                assertFailsWith<SQLException> { source.execute("DELETE FROM events WHERE id=8801") }
                assertFailsWith<SQLException> {
                    source.execute(
                        "DELETE FROM club_operational_nights WHERE id=$nightId",
                    )
                }
                assertEquals("1", source.linkCount())
            }
        }

    @Test
    fun `linked night stays frozen even if the legacy event interval later drifts`() =
        database { source ->
            source.cabinet()
            withApp(source, CLOCK) {
                val path = materialize()
                source.event()
                val receipt = ok(path, RECIPIENT, linkBody(ok(path, RECIPIENT)))
                source.execute(
                    "UPDATE events SET start_at=${ts("2026-12-03T21:00:00Z")}," +
                        "end_at=${ts("2026-12-04T05:00:00Z")} WHERE id=8801",
                )
                val calendar = ok(CALENDAR, RECIPIENT)
                val closed = """{"date":"$DATE","mode":"closed"}"""
                for (edit in listOf(closed, openRule(DATE, "02:00"))) {
                    requestAs(CALENDAR, RECIPIENT, calendarBody(calendar, edit), HttpMethod.Patch)
                        .assertClientError(HttpStatusCode.Conflict, "calendar_night_dependencies")
                }
                assertEquals(calendar, ok(CALENDAR, RECIPIENT))
                assertEquals(
                    "1",
                    source.scalar(
                        "SELECT COUNT(*) FROM canonical_night_event_links WHERE legacy_start_at=${ts(START)}",
                    ),
                )
                assertEquals(receipt.string("nightId"), ok(path, RECIPIENT).string("nightId"))
            }
        }

    @Test
    fun `cancelled nights and clubs outside the private cabinet cannot be linked`() =
        database { source ->
            source.cabinet()
            withApp(source, CLOCK) {
                val path = materialize()
                val closed = """{"date":"$DATE","mode":"closed"}"""
                ok(CALENDAR, RECIPIENT, calendarBody(ok(CALENDAR, RECIPIENT), closed), HttpMethod.Patch)
                source.event()
                val cancelled = ok(path, RECIPIENT)
                assertEquals("CANCELLED", cancelled.string("nightStatus"))
                val body = linkBody(cancelled)
                requestAs(path, RECIPIENT, body).assertConflict("night_not_scheduled")
                source.execute("UPDATE clubs SET is_active=TRUE WHERE id=8801")
                requestAs(path, RECIPIENT, body)
                    .assertClientError(HttpStatusCode.Conflict, "calendar_night_cutover_required")
                assertEquals("0", source.linkCount())
            }
        }

    @Test
    fun `competing link and calendar serialize and identical concurrent links audit once`() =
        database { source ->
            source.cabinet()
            source.addEditor()
            withApp(source, CLOCK) {
                val path = materialize()
                source.event()
                val command = linkBody(ok(path, RECIPIENT))
                val calendar = ok(CALENDAR, RECIPIENT)
                val responses =
                    coroutineScope {
                        val link = async { requestAs(path, RECIPIENT, command, key = KEY) }
                        val edit =
                            async {
                                requestAs(
                                    CALENDAR,
                                    FOREIGN,
                                    calendarBody(calendar, openRule("2026-11-06")),
                                    HttpMethod.Patch,
                                )
                            }
                        listOf(link.await(), edit.await())
                    }
                assertEquals(setOf(HttpStatusCode.OK, HttpStatusCode.Conflict), responses.map { it.status }.toSet())
                responses
                    .single { it.status == HttpStatusCode.Conflict }
                    .assertClientError(HttpStatusCode.Conflict, "version_conflict")
                val current =
                    if (responses.first().status ==
                        HttpStatusCode.OK
                    ) {
                        command
                    } else {
                        linkBody(ok(path, RECIPIENT))
                    }
                val replies =
                    coroutineScope {
                        List(2) { async { ok(path, RECIPIENT, current, key = KEY) } }.map { it.await() }
                    }
                assertEquals(replies.first(), replies.last())
                assertEquals("1", source.linkCount())
                assertEquals("1", source.linkAudits())
            }
        }

    @Test
    fun `audit failure rolls back link version and command so the same key can retry`() =
        database { source ->
            source.cabinet()
            withApp(source, CLOCK) {
                val path = materialize()
                source.event()
                val preview = ok(path, RECIPIENT)
                val body = linkBody(preview)
                source.execute("ALTER TABLE audit_log ADD CONSTRAINT test_link_audit CHECK (action <> '$AUDIT')")
                requestAs(path, RECIPIENT, body, key = KEY)
                    .assertClientError(HttpStatusCode.InternalServerError, "internal_error")
                assertEquals(preview, ok(path, RECIPIENT))
                assertEquals("0", source.linkCount())
                assertEquals(
                    "0",
                    source.scalar("SELECT COUNT(*) FROM client_commands WHERE result_type='club_night_event_link'"),
                )
                source.execute("ALTER TABLE audit_log DROP CONSTRAINT test_link_audit")
                assertEquals("8801", ok(path, RECIPIENT, body, key = KEY).string("eventId"))
                assertEquals("1", source.linkAudits())
            }
        }

    private suspend fun ApplicationTestBuilder.materialize(
        date: String = DATE,
        open: String = "00:00",
        clubId: Int = 8801,
        user: Long = RECIPIENT,
    ): String {
        val calendar = "$C/clubs/$clubId/calendar"
        val saved = ok(calendar, user, calendarBody(ok(calendar, user), openRule(date, open)), HttpMethod.Patch)
        val night =
            saved
                .getValue(
                    "nights",
                ).jsonArray
                .map { it.jsonObject }
                .single { it.string("businessDate") == date }
        return "$C/calendar/nights/${night.string("nightId")}/legacy-event"
    }

    private fun openRule(
        date: String,
        open: String = "00:00",
    ): String = """{"date":"$date","mode":"open","open":"$open","close":"08:00","startNextDay":true,"nextDay":false}"""

    private fun calendarBody(
        calendar: JsonObject,
        edit: String,
    ): JsonObject =
        JsonObject(
            version(calendar) + obj("timezone" to calendar.string("timezone")) +
                mapOf(
                    "weekly" to JsonArray(emptyList()),
                    "holidays" to JsonArray(emptyList()),
                    "exceptions" to Json.parseToJsonElement("[$edit]"),
                ),
        )

    private fun linkBody(
        view: JsonObject,
        eventId: String = "8801",
    ): JsonObject = JsonObject(version(view) + obj("eventId" to eventId))

    private suspend fun HttpResponse.assertConflict(reason: String) {
        assertEquals(HttpStatusCode.Conflict, status)
        val error = Json.parseToJsonElement(bodyAsText()).jsonObject
        assertEquals("calendar_night_event_conflict", error.string("code"))
        assertEquals(reason, error.getValue("details").jsonObject.string("reason"))
    }

    private fun DataSource.cabinet(
        id: Int = 8801,
        telegramId: Long = RECIPIENT,
        zone: String = "Europe/Moscow",
    ) {
        execute("INSERT INTO users(id,telegram_user_id) VALUES ($id,$telegramId)")
        execute("INSERT INTO clients(id,display_name,is_active) VALUES ($id,'Client',TRUE)")
        execute("INSERT INTO clubs(id,name,timezone,is_active) VALUES ($id,'Club','$zone',FALSE)")
        execute("INSERT INTO client_clubs(client_id,club_id) VALUES ($id,$id)")
        addGrants(id, id)
    }

    private fun DataSource.addEditor() {
        execute("INSERT INTO users(id,telegram_user_id) VALUES (8802,$FOREIGN)")
        addGrants(8801, 8802)
    }

    private fun DataSource.addGrants(
        clubId: Int,
        userId: Int,
    ) {
        execute("INSERT INTO client_memberships(client_id,user_id,is_active) VALUES ($clubId,$userId,TRUE)")
        execute(
            "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active) VALUES " +
                "($clubId,$userId,$clubId,'client.club.read',TRUE)," +
                "($clubId,$userId,$clubId,'client.club.calendar.write',TRUE)",
        )
    }

    private fun DataSource.event(
        id: Int = 8801,
        clubId: Int = 8801,
        start: String = START,
        end: String = END,
    ) = execute("INSERT INTO events(id,club_id,start_at,end_at) VALUES ($id,$clubId,${ts(start)},${ts(end)})")

    private fun DataSource.rawLink(
        nightId: String,
        eventId: Int,
        clubId: Int,
    ) = execute(
        "INSERT INTO canonical_night_event_links(canonical_night_id,event_id,club_id,legacy_start_at,legacy_end_at) " +
            "VALUES ($nightId,$eventId,$clubId,${ts(START)},${ts(END)})",
    )

    private suspend fun BookingHoldRepository.legacyHold(key: String) =
        assertIs<BookingCoreResult.Success<BookingHold>>(
            createHold(8801, Instant.parse(START), Instant.parse(END), 2, Duration.ofMinutes(10), key),
        ).value

    private fun DataSource.linkCount() = scalar("SELECT COUNT(*) FROM canonical_night_event_links")

    private fun DataSource.linkAudits() = scalar("SELECT COUNT(*) FROM audit_log WHERE action='$AUDIT'")

    private fun DataSource.execute(sql: String) {
        connection.use { connection -> connection.createStatement().use { it.execute(sql) } }
    }

    private fun ts(value: String): String = "TIMESTAMP WITH TIME ZONE '$value'"

    private companion object {
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-11-03T12:00:00Z"), ZoneOffset.UTC)
        const val CALENDAR = "$C/clubs/8801/calendar"
        const val DATE = "2026-11-03"
        const val START = "2026-11-03T21:00:00Z"
        const val END = "2026-11-04T05:00:00Z"
        const val KEY = "canonical-link-command"
        const val AUDIT = "client.club.calendar.night.link"
    }
}
