package com.example.bot.routes

import io.ktor.client.request.get
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

open class ClientCalendarRoutesDataTest : ClientOnboardingRoutesTestBase() {
    @Test
    fun `dated hours inherit the Friday overnight start and accept an explicit complete override`() =
        database { source ->
            withApp(source, CLOCK) {
                val club = createClub(RECIPIENT)
                val clubId = club.string("id")
                val path = "$C/clubs/$clubId/calendar"
                val initial = ok(path, RECIPIENT)
                val weekly =
                    """[{"day":5,"mode":"open","open":"00:00","close":"12:00","nextDay":false,"startNextDay":true}]"""
                ok(path, RECIPIENT, calendarBody(initial, weekly = weekly), HttpMethod.Patch)
                source.execute(
                    "INSERT INTO club_holidays(club_id,date,is_open,override_open_local,override_close_local," +
                        "close_next_day,start_next_day) VALUES ($clubId,'2026-11-06',TRUE,NULL,NULL,NULL,FALSE)",
                )
                val inherited = ok(path, RECIPIENT)
                val holiday = inherited.rules("holidays").single().jsonObject
                assertEquals(JsonNull, holiday["open"])
                assertEquals(JsonNull, holiday["close"])
                assertEquals(JsonPrimitive(false), holiday["startNextDay"])
                assertEquals("2026-11-07T00:00+03:00", inherited.rules("nights").first().string("opensAt"))
                assertEquals("2026-11-07T12:00+03:00", inherited.rules("nights").first().string("closesAt"))
                val explicit =
                    """[{"date":"2026-11-06","mode":"open","open":"00:00","close":"12:00","nextDay":false}]"""
                val saved = ok(path, RECIPIENT, calendarBody(inherited, holidays = explicit), HttpMethod.Patch)
                assertEquals("2026-11-06T00:00+03:00", saved.rules("nights").first().string("opensAt"))
                assertEquals("2026-11-06T12:00+03:00", saved.rules("nights").first().string("closesAt"))
                assertEquals(saved, ok(path, RECIPIENT))
            }
        }

    @Test
    fun `owner persists weekly rules holidays and date changes and replays after restart`() =
        database { source ->
            lateinit var path: String
            lateinit var body: JsonObject
            val initialEvents = source.scalar("SELECT COUNT(*) FROM events")
            withApp(source, CLOCK) {
                val club = createClub(RECIPIENT)
                path = "$C/clubs/${club.string("id")}/calendar"
                val initial = ok(path, RECIPIENT)
                assertEquals(JsonPrimitive(true), initial["canWrite"])
                assertTrue(initial.rules("nights").isEmpty())
                body =
                    calendarBody(
                        initial,
                        weekly = """[{"day":5,"mode":"open","open":"00:00","close":"12:00","nextDay":true}]""",
                        holidays = """
                            [{"date":"2026-11-04","mode":"open","open":"22:00","close":"04:00","nextDay":true}]
                        """,
                        exceptions = """
                            [
                              {"date":"2026-11-05","mode":"open","open":"21:15","close":"23:30","nextDay":false},
                              {"date":"2026-11-06","mode":"closed"},
                              {"date":"2026-11-13","mode":"open","open":"23:00","close":"03:00","nextDay":true}
                            ]
                        """,
                    )
                val saved = ok(path, RECIPIENT, body, HttpMethod.Patch, SAVE_KEY)
                assertEquals("2", saved.string("version"))
                val nights = saved.rules("nights")
                assertEquals(
                    listOf("2026-11-04", "2026-11-05", "2026-11-13"),
                    nights.take(3).map { it.string("businessDate") },
                )
                assertEquals("2026-11-05T04:00+03:00", nights[0].string("closesAt"))
                assertEquals("2026-11-13T23:00+03:00", nights[2].string("opensAt"))
                assertEquals("2026-11-21T12:00+03:00", nights[3].string("closesAt"))
                requestAs(
                    path,
                    RECIPIENT,
                    body,
                    HttpMethod.Patch,
                ).assertClientError(HttpStatusCode.Conflict, "version_conflict")
            }
            withApp(source, CLOCK) {
                val saved = ok(path, RECIPIENT)
                assertEquals(saved, ok(path, RECIPIENT, body, HttpMethod.Patch, SAVE_KEY))
                val different = calendarBody(saved, exceptions = """[{"date":"2026-11-06","mode":"remove"}]""")
                requestAs(path, RECIPIENT, different, HttpMethod.Patch, SAVE_KEY)
                    .assertClientError(HttpStatusCode.Conflict, "idempotency_conflict")
                assertEquals(
                    "1",
                    source.scalar("SELECT COUNT(*) FROM client_commands WHERE result_type='club_calendar'"),
                )
                assertEquals(
                    "1",
                    source.scalar("SELECT COUNT(*) FROM audit_log WHERE action='client.club.calendar.update'"),
                )
                assertEquals(initialEvents, source.scalar("SELECT COUNT(*) FROM events"))
                val privateClub = "SELECT COUNT(*) FROM clubs WHERE id=${saved.string("clubId")} AND is_active=FALSE"
                assertEquals("1", source.scalar(privateClub))
                val reopened = ok(path, RECIPIENT, different, HttpMethod.Patch)
                assertEquals("2026-11-06", reopened.rules("nights")[2].string("businessDate"))
                assertEquals("2026-11-07T12:00+03:00", reopened.rules("nights")[2].string("closesAt"))
            }
        }

    @Test
    fun `read and write are exact grants and legacy platform owner cannot read a private club`() =
        database { source ->
            withApp(source, CLOCK) {
                val club = createClub(RECIPIENT)
                val foreign = createClub(FOREIGN)
                val path = "$C/clubs/${club.string("id")}/calendar"
                val initial = ok(path, RECIPIENT)
                val body = calendarBody(initial, exceptions = """[{"date":"2026-11-06","mode":"closed"}]""")
                ok(path, RECIPIENT, body, HttpMethod.Patch, SAVE_KEY)
                source.execute(
                    "INSERT INTO user_roles(user_id,role_code,scope_type) " +
                        "SELECT id,'OWNER','GLOBAL' FROM users WHERE telegram_user_id=$OWNER",
                )
                client.get(path).assertClientError(HttpStatusCode.Unauthorized, "unauthorized")
                requestAs(path, OWNER).assertClientError(HttpStatusCode.NotFound, "not_found")
                requestAs(path, FOREIGN, body, HttpMethod.Patch).assertClientError(HttpStatusCode.NotFound, "not_found")
                requestAs("$C/clubs/${foreign.string("id")}/calendar", RECIPIENT)
                    .assertClientError(HttpStatusCode.NotFound, "not_found")
                source.disableGrant(club, "client.club.calendar.write")
                assertEquals(JsonPrimitive(false), ok(path, RECIPIENT)["canWrite"])
                requestAs(path, RECIPIENT, body, HttpMethod.Patch, SAVE_KEY)
                    .assertClientError(HttpStatusCode.NotFound, "not_found")
                source.disableGrant(club, "client.club.read")
                requestAs(path, RECIPIENT).assertClientError(HttpStatusCode.NotFound, "not_found")
            }
        }

    @Test
    fun `validation and failed audit leave no partial rules or command`() =
        database { source ->
            withApp(source, CLOCK) {
                val club = createClub(RECIPIENT)
                val path = "$C/clubs/${club.string("id")}/calendar"
                val initial = ok(path, RECIPIENT)
                val badDates = """[{"date":"2026-02-30","mode":"closed"}]"""
                val backwards = """[{"day":5,"mode":"open","open":"22:00","close":"04:00","nextDay":false}]"""
                val duplicate = """[{"date":"2026-11-06","mode":"closed"},{"date":"2026-11-06","mode":"remove"}]"""
                for (body in listOf(
                    calendarBody(initial, holidays = badDates),
                    calendarBody(initial, weekly = backwards),
                    calendarBody(initial, exceptions = duplicate),
                )) {
                    requestAs(path, RECIPIENT, body, HttpMethod.Patch)
                        .assertClientError(HttpStatusCode.BadRequest, "validation_error")
                }
                val body = calendarBody(initial, exceptions = """[{"date":"2026-11-06","mode":"closed"}]""")
                source.execute(
                    "ALTER TABLE audit_log ADD CONSTRAINT synthetic_calendar_audit_failure " +
                        "CHECK (action <> 'client.club.calendar.update')",
                )
                requestAs(path, RECIPIENT, body, HttpMethod.Patch, SAVE_KEY)
                    .assertClientError(HttpStatusCode.InternalServerError, "internal_error")
                assertEquals(initial, ok(path, RECIPIENT))
                assertEquals(
                    "0",
                    source.scalar("SELECT COUNT(*) FROM client_commands WHERE result_type='club_calendar'"),
                )
                source.execute("ALTER TABLE audit_log DROP CONSTRAINT synthetic_calendar_audit_failure")
                assertEquals("2", ok(path, RECIPIENT, body, HttpMethod.Patch, SAVE_KEY).string("version"))
            }
        }

    @Test
    fun `one competing version wins and timezone change invalidates an old draft`() =
        database { source ->
            withApp(source, CLOCK) {
                val club = createClub(RECIPIENT)
                val id = club.string("id")
                source.addExactEditor(club.string("clientId"), id)
                val path = "$C/clubs/$id/calendar"
                val initial = ok(path, RECIPIENT)
                val results =
                    coroutineScope {
                        listOf(RECIPIENT to "2026-11-04", FOREIGN to "2026-11-05")
                            .map { (user, date) ->
                                async {
                                    requestAs(
                                        path,
                                        user,
                                        calendarBody(initial, exceptions = """[{"date":"$date","mode":"closed"}]"""),
                                        HttpMethod.Patch,
                                    ).status
                                }
                            }.map { it.await() }
                    }
                assertEquals(setOf(HttpStatusCode.OK, HttpStatusCode.Conflict), results.toSet())
                val current = ok(path, RECIPIENT)
                assertEquals(1, current.rules("exceptions").size)
                val oldTimezone = calendarBody(current, exceptions = """[{"date":"2026-11-06","mode":"closed"}]""")
                ok(
                    "$C/clubs/$id/profile",
                    RECIPIENT,
                    obj("expectedVersion" to club.string("version"), "timezone" to "Europe/London"),
                    HttpMethod.Patch,
                )
                requestAs(path, RECIPIENT, oldTimezone, HttpMethod.Patch)
                    .assertClientError(HttpStatusCode.Conflict, "version_conflict")
                val currentZone = ok(path, RECIPIENT)
                assertEquals("Europe/London", currentZone.string("timezone"))
                assertEquals(current.string("version"), currentZone.string("version"))
            }
        }

    @Test
    fun `concurrent same key executes once and both responses are authoritative`() =
        database { source ->
            withApp(source, CLOCK) {
                val path = "$C/clubs/${createClub(RECIPIENT).string("id")}/calendar"
                val body = calendarBody(ok(path, RECIPIENT), exceptions = """[{"date":"2026-11-06","mode":"closed"}]""")
                val responses =
                    coroutineScope {
                        List(2) { async { ok(path, RECIPIENT, body, HttpMethod.Patch, SAVE_KEY) } }.map { it.await() }
                    }
                assertEquals(responses[0], responses[1])
                assertEquals("2", responses[0].string("version"))
                assertEquals(
                    "1",
                    source.scalar("SELECT COUNT(*) FROM audit_log WHERE action='client.club.calendar.update'"),
                )
            }
        }

    @Test
    fun `upgrade preserves legacy rules and bootstraps only an authorized confirmed owner`() =
        databaseAt("66") { source ->
            source.seedLegacyCalendar()
            val dialect =
                source.connection.use { if (it.metaData.databaseProductName == "PostgreSQL") "postgresql" else "h2" }
            Flyway
                .configure()
                .dataSource(source)
                .locations("classpath:db/migration/common", "classpath:db/migration/$dialect")
                .target("67")
                .load()
                .also {
                    assertEquals(1, it.migrate().migrationsExecuted)
                    it.validate()
                }
            assertEquals(2, migrateBookingRulesFixture(source, dialect, "69"))
            assertEquals(1, migrateBookingRulesFixture(source, dialect, "70"))
            assertEquals(1, migrateBookingRulesFixture(source, dialect, "71"))
            assertEquals(1, migrateBookingRulesFixture(source, dialect, "72"))
            assertEquals(1, migrateBookingRulesFixture(source, dialect))
            withApp(source, CLOCK) {
                val path = "$C/clubs/8801/calendar"
                val owner = ok(path, RECIPIENT)
                assertEquals(JsonPrimitive(true), owner["canWrite"])
                assertEquals(JsonPrimitive(true), owner.rules("weekly").single()["nextDay"])
                assertEquals("2026-11-07T04:00+03:00", owner.rules("nights")[0].string("closesAt"))
                assertEquals(JsonPrimitive(false), ok(path, FOREIGN)["canWrite"])
                assertEquals(JsonPrimitive(false), ok("$C/clubs/8802/calendar", RECIPIENT)["canWrite"])
                assertEquals(
                    "1",
                    source.scalar("SELECT COUNT(*) FROM audit_log WHERE action='client.club.calendar.bootstrap'"),
                )
                val patch =
                    calendarBody(
                        owner,
                        holidays = """
                            [{"date":"2026-11-08","mode":"open","open":"21:00","close":"04:00","nextDay":true}]
                        """,
                    )
                ok(path, RECIPIENT, patch, HttpMethod.Patch)
                assertEquals("Legacy holiday", source.scalar("SELECT name FROM club_holidays WHERE club_id=8801"))
                assertEquals("8803", source.scalar("SELECT id FROM club_holidays WHERE club_id=8801"))
            }
        }

    private fun DataSource.seedLegacyCalendar() {
        execute("INSERT INTO users(id,telegram_user_id) VALUES (8801,$RECIPIENT),(8802,$FOREIGN)")
        execute("INSERT INTO clients(id,display_name,is_active) VALUES (8801,'Legacy client',TRUE)")
        execute("INSERT INTO client_memberships(client_id,user_id,is_active) VALUES (8801,8801,TRUE),(8801,8802,TRUE)")
        execute(
            "INSERT INTO clubs(id,name,timezone,is_active) VALUES " +
                "(8801,'Legacy club','Europe/Moscow',FALSE),(8802,'Read only','Europe/Moscow',FALSE)",
        )
        execute("INSERT INTO client_clubs(client_id,club_id) VALUES (8801,8801),(8801,8802)")
        execute(
            "INSERT INTO client_direct_club_profiles(club_id,client_id,owner_user_id,venue_fingerprint," +
                "legacy_fingerprint,country_code,city,address) VALUES " +
                "(8801,8801,8801,REPEAT('a',64),REPEAT('a',64),'RU','City','Address')," +
                "(8802,8801,8801,REPEAT('b',64),REPEAT('b',64),'RU','City','Address')",
        )
        execute(
            "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active) VALUES " +
                "(8801,8801,8801,'client.club.read',TRUE),(8801,8801,8801,'client.club.profile.write',TRUE)," +
                "(8801,8801,8801,'client.club.layout.write',TRUE),(8801,8802,8801,'client.club.read',TRUE)," +
                "(8801,8802,8801,'client.club.profile.write',TRUE),(8801,8802,8801,'client.club.layout.write',TRUE)," +
                "(8801,8801,8802,'client.club.read',TRUE),(8801,8801,8802,'client.club.profile.write',FALSE)," +
                "(8801,8801,8802,'client.club.layout.write',TRUE)",
        )
        execute("INSERT INTO club_hours(club_id,dow,open_local,close_local) VALUES (8801,5,'22:00','04:00')")
        execute(
            "INSERT INTO club_holidays(id,club_id,date,is_open,override_open_local,override_close_local,name) " +
                "VALUES (8803,8801,'2026-11-08',TRUE,'22:00','03:00','Legacy holiday')",
        )
    }

    private suspend fun ApplicationTestBuilder.createClub(user: Long): JsonObject {
        val invite = ok("$P/invitations", OWNER, obj("clientName" to "Client $user", "recipientLabel" to "Owner"))
        ok(
            "$C/invitations/${invite.string("id")}/claim",
            user,
            obj("token" to invite.string("token"), "expectedVersion" to invite.string("version")),
        )
        return ok(
            "$C/clients/${invite.string("clientId")}/clubs",
            user,
            obj("venueName" to "Club", "countryCode" to "RU", "cityId" to "524901", "address" to "Test Street 1"),
        )
    }

    private fun calendarBody(
        view: JsonObject,
        weekly: String = "[]",
        holidays: String = "[]",
        exceptions: String = "[]",
    ): JsonObject =
        JsonObject(
            obj("expectedVersion" to view.string("version"), "timezone" to view.string("timezone")) +
                mapOf("weekly" to array(weekly), "holidays" to array(holidays), "exceptions" to array(exceptions)),
        )

    private fun array(value: String): JsonArray = Json.parseToJsonElement(value).jsonArray

    private fun JsonObject.rules(key: String) = getValue(key).jsonArray.map { it.jsonObject }

    private fun DataSource.addExactEditor(
        clientId: String,
        clubId: String,
    ) {
        execute("INSERT INTO users(telegram_user_id) VALUES ($FOREIGN)")
        val userId = scalar("SELECT id FROM users WHERE telegram_user_id=$FOREIGN")
        execute("INSERT INTO client_memberships(client_id,user_id,is_active) VALUES ($clientId,$userId,TRUE)")
        execute(
            "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active) VALUES " +
                "($clientId,$userId,$clubId,'client.club.read',TRUE)," +
                "($clientId,$userId,$clubId,'client.club.calendar.write',TRUE)",
        )
    }

    private fun DataSource.disableGrant(
        club: JsonObject,
        permission: String,
    ) = execute(
        "UPDATE client_club_grants SET is_active=FALSE WHERE club_id=${club.string(
            "id",
        )} AND permission_code='$permission'",
    )

    private fun DataSource.execute(sql: String) {
        connection.use { it.createStatement().use { statement -> statement.execute(sql) } }
    }

    private companion object {
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-11-03T12:00:00Z"), ZoneOffset.UTC)
        const val SAVE_KEY = "calendar-lost-response"
    }
}
