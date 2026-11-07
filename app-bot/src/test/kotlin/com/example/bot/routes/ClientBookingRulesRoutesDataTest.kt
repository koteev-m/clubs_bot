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

open class ClientBookingRulesRoutesDataTest : ClientOnboardingRoutesTestBase() {
    @Test
    fun `defaults and saved rules preview the same calendar across DST and replay after restart`() =
        database { source ->
            lateinit var path: String
            lateinit var saved: JsonObject
            withApp(source, CLOCK) {
                val club = createClub(RECIPIENT)
                path = "$C/clubs/${club.string("id")}/booking-rules"
                createSpringNight(club)
                val initial = ok(path, RECIPIENT)
                assertEquals(defaults, initial["rules"])
                assertEquals("2026-03-28", initial.firstNight().string("businessDate"))
                assertEquals("2026-03-29T04:00+02:00", initial.firstNight().string("cutoffAt"))
                assertEquals(JsonNull, initial.firstNight()["arrivalBy"])
                saved = ok(path, RECIPIENT, payload(), HttpMethod.Patch, KEY)
                assertEquals("2", saved.string("version"))
                assertEquals("2026-03-29T04:30+02:00", saved.firstNight().string("cutoffAt"))
                assertEquals("2026-03-29T04:00+02:00", saved.firstNight().string("arrivalBy"))
                assertEquals(JsonPrimitive(true), saved.firstNight()["cutoffAfterArrival"])
            }
            withApp(source, CLOCK) {
                assertEquals(saved, ok(path, RECIPIENT))
                assertEquals(saved, ok(path, RECIPIENT, payload(), HttpMethod.Patch, KEY))
                requestAs(path, RECIPIENT, payload(hold = 13), HttpMethod.Patch, KEY)
                    .assertClientError(HttpStatusCode.Conflict, "idempotency_conflict")
                requestAs(path, RECIPIENT, payload(), HttpMethod.Patch)
                    .assertClientError(HttpStatusCode.Conflict, "version_conflict")
                assertEquals("1", source.ruleCommands())
                assertEquals("1", source.ruleAudits())
                val cleared = ok(path, RECIPIENT, payload(version = "2", hold = null), HttpMethod.Patch)
                assertEquals(JsonNull, cleared["rules"]!!.jsonObject["holdMinutes"])
                assertEquals("3", cleared.string("version"))
            }
        }

    @Test
    fun `invalid input and audit failure leave settings and commands uncommitted`() =
        database { source ->
            withApp(source, CLOCK) {
                val path = "$C/clubs/${createClub(RECIPIENT).string("id")}/booking-rules"
                val malformed = JsonObject(payload() - "arrivalMinutes")
                for (body in listOf(payload(hold = 0), payload(hold = 1441), malformed)) {
                    requestAs(path, RECIPIENT, body, HttpMethod.Patch)
                        .assertClientError(HttpStatusCode.BadRequest, "validation_error")
                }
                source.execute(
                    "ALTER TABLE audit_log ADD CONSTRAINT booking_rules_failure " +
                        "CHECK (action <> 'client.club.booking_rules.update')",
                )
                requestAs(path, RECIPIENT, payload(), HttpMethod.Patch, KEY)
                    .assertClientError(HttpStatusCode.InternalServerError, "internal_error")
                assertEquals(defaults, ok(path, RECIPIENT)["rules"])
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM client_club_booking_rules"))
                assertEquals("0", source.ruleCommands())
                source.execute("ALTER TABLE audit_log DROP CONSTRAINT booking_rules_failure")
                assertEquals("2", ok(path, RECIPIENT, payload(), HttpMethod.Patch, KEY).string("version"))
            }
        }

    @Test
    fun `exact grants exclude foreign and legacy principals and revoke blocks replay and restoration of write`() =
        database { source ->
            withApp(source, CLOCK) {
                val club = createClub(RECIPIENT)
                val path = "$C/clubs/${club.string("id")}/booking-rules"
                createClub(FOREIGN)
                source.execute(
                    "INSERT INTO user_roles(user_id,role_code,scope_type) " +
                        "SELECT id,'OWNER','GLOBAL' FROM users WHERE telegram_user_id=$OWNER",
                )
                client.get(path).assertClientError(HttpStatusCode.Unauthorized, "unauthorized")
                requestAs(path, OWNER).assertClientError(HttpStatusCode.NotFound, "not_found")
                requestAs(path, FOREIGN).assertClientError(HttpStatusCode.NotFound, "not_found")
                requestAs(path, FOREIGN, payload(), HttpMethod.Patch)
                    .assertClientError(HttpStatusCode.NotFound, "not_found")
                ok(path, RECIPIENT, payload(), HttpMethod.Patch, KEY)
                revokeAndRestoreRead(club, path)
                assertEquals(JsonPrimitive(false), ok(path, RECIPIENT)["canWrite"])
                requestAs(path, RECIPIENT, payload(), HttpMethod.Patch, KEY)
                    .assertClientError(HttpStatusCode.NotFound, "not_found")
                assertEquals("1", source.ruleAudits())
            }
        }

    @Test
    fun `competing versions serialize across actors and matching keys replay once`() =
        database { source ->
            withApp(source, CLOCK) {
                val club = createClub(RECIPIENT)
                val clubId = club.string("id")
                source.addEditor(clubId, club.string("clientId"))
                val path = "$C/clubs/$clubId/booking-rules"
                val statuses =
                    coroutineScope {
                        val first = async { requestAs(path, RECIPIENT, payload(hold = 11), HttpMethod.Patch).status }
                        val second = async { requestAs(path, FOREIGN, payload(hold = 12), HttpMethod.Patch).status }
                        setOf(first.await(), second.await())
                    }
                assertEquals(setOf(HttpStatusCode.OK, HttpStatusCode.Conflict), statuses)
                val responses =
                    coroutineScope {
                        List(2) {
                            async { ok(path, RECIPIENT, payload(version = "2"), HttpMethod.Patch, KEY) }
                        }.map { it.await() }
                    }
                assertEquals(responses[0], responses[1])
                assertEquals("3", responses[0].string("version"))
                assertEquals("2", source.ruleAudits())
            }
        }

    @Test
    fun `upgrade preserves calendar and grants settings only to the eligible creator`() =
        databaseAt("67") { source ->
            source.seedLegacy()
            val dialect =
                source.connection.use { if (it.metaData.databaseProductName == "PostgreSQL") "postgresql" else "h2" }
            assertEquals(1, migrateBookingRulesFixture(source, dialect, "68"))
            assertEquals(1, migrateBookingRulesFixture(source, dialect, "69"))
            assertEquals(1, migrateBookingRulesFixture(source, dialect, "70"))
            assertEquals(1, migrateBookingRulesFixture(source, dialect, "71"))
            assertEquals(1, migrateBookingRulesFixture(source, dialect, "72"))
            assertEquals(1, migrateBookingRulesFixture(source, dialect, "73"))
            assertEquals(1, migrateBookingRulesFixture(source, dialect))
            withApp(source, CLOCK) {
                val path = "$C/clubs/8801/booking-rules"
                val view = ok(path, RECIPIENT)
                assertEquals(JsonPrimitive(true), view["canWrite"])
                assertEquals("2026-03-29T04:00+02:00", view.firstNight().string("cutoffAt"))
                assertEquals(JsonPrimitive(false), ok(path, FOREIGN)["canWrite"])
                assertEquals(JsonPrimitive(false), ok("$C/clubs/8802/booking-rules", RECIPIENT)["canWrite"])
                assertEquals("4", ok("$C/clubs/8801/calendar", RECIPIENT).string("version"))
                assertEquals(
                    "1",
                    source.scalar("SELECT COUNT(*) FROM audit_log WHERE action='client.club.booking_rules.bootstrap'"),
                )
            }
        }

    private suspend fun ApplicationTestBuilder.revokeAndRestoreRead(
        club: JsonObject,
        rulesPath: String,
    ) {
        val clubId = club.string("id")
        val clientId = club.string("clientId")
        val member =
            ok("$P/clients/$clientId", OWNER)
                .getValue("members")
                .jsonArray
                .single()
                .jsonObject
        val path = "$P/clients/$clientId/members/${member.string("userId")}"
        val revoked = ok("$path/revoke", OWNER, version(member))
        requestAs(rulesPath, RECIPIENT).assertClientError(HttpStatusCode.NotFound, "not_found")
        requestAs("$path/restore", OWNER, restorePayload(revoked, clubId, PERMISSION))
            .assertClientError(HttpStatusCode.BadRequest, "validation_error")
        ok("$path/restore", OWNER, restorePayload(revoked, clubId, "client.club.read"))
    }

    private fun restorePayload(
        revoked: JsonObject,
        clubId: String,
        permission: String,
    ): JsonObject =
        JsonObject(
            version(revoked) +
                mapOf(
                    "permissions" to JsonArray(emptyList()),
                    "clubGrants" to JsonArray(listOf(obj("clubId" to clubId, "permission" to permission))),
                ),
        )

    private suspend fun ApplicationTestBuilder.createClub(user: Long): JsonObject {
        val invite = ok("$P/invitations", OWNER, obj("clientName" to "Club $user", "recipientLabel" to "Owner"))
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

    private suspend fun ApplicationTestBuilder.createSpringNight(club: JsonObject) {
        val path = "$C/clubs/${club.string("id")}"
        ok(
            "$path/profile",
            RECIPIENT,
            JsonObject(version(club) + obj("timezone" to "Europe/Berlin")),
            HttpMethod.Patch,
        )
        val calendar = ok("$path/calendar", RECIPIENT)
        val rules =
            Json
                .parseToJsonElement(
                    """{"weekly":[],"holidays":[],"exceptions":[
                {"date":"2026-03-28","mode":"open","open":"22:00","close":"06:00","nextDay":true}]}""",
                ).jsonObject
        ok(
            "$path/calendar",
            RECIPIENT,
            JsonObject(rules + version(calendar) + obj("timezone" to "Europe/Berlin")),
            HttpMethod.Patch,
        )
    }

    private fun DataSource.seedLegacy() {
        execute("INSERT INTO users(id,telegram_user_id) VALUES (8801,$RECIPIENT),(8802,$FOREIGN)")
        execute("INSERT INTO clients(id,display_name,is_active) VALUES (8801,'Legacy client',TRUE)")
        execute("INSERT INTO client_memberships(client_id,user_id,is_active) VALUES (8801,8801,TRUE),(8801,8802,TRUE)")
        execute(
            "INSERT INTO clubs(id,name,timezone,is_active,calendar_version) VALUES " +
                "(8801,'Legacy club','Europe/Berlin',FALSE,4),(8802,'Read only','Europe/Berlin',FALSE,1)",
        )
        execute("INSERT INTO client_clubs(client_id,club_id) VALUES (8801,8801),(8801,8802)")
        execute(
            "INSERT INTO client_direct_club_profiles(club_id,client_id,owner_user_id,venue_fingerprint," +
                "legacy_fingerprint,country_code,city,address) VALUES " +
                "(8801,8801,8801,REPEAT('a',64),REPEAT('a',64),'DE','Berlin','Address')," +
                "(8802,8801,8801,REPEAT('b',64),REPEAT('b',64),'DE','Berlin','Address')",
        )
        execute(
            "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active) " +
                "SELECT m.client_id,u.id,m.club_id,p.code," +
                "NOT (m.club_id=8802 AND p.code='client.club.calendar.write') " +
                "FROM client_clubs m CROSS JOIN users u CROSS JOIN permissions p WHERE m.client_id=8801 AND " +
                "u.id IN (8801,8802) AND p.code IN ('client.club.read','client.club.profile.write'," +
                "'client.club.layout.write','client.club.calendar.write')",
        )
        execute(
            "INSERT INTO club_hours(club_id,dow,open_local,close_local,close_next_day) " +
                "VALUES (8801,6,'22:00','06:00',TRUE)",
        )
    }

    private fun DataSource.addEditor(
        clubId: String,
        clientId: String,
    ) {
        execute("INSERT INTO users(telegram_user_id) VALUES ($FOREIGN)")
        val userId = scalar("SELECT id FROM users WHERE telegram_user_id=$FOREIGN")
        execute("INSERT INTO client_memberships(client_id,user_id,is_active) VALUES ($clientId,$userId,TRUE)")
        execute(
            "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active) VALUES " +
                "($clientId,$userId,$clubId,'client.club.read',TRUE),($clientId,$userId,$clubId,'$PERMISSION',TRUE)",
        )
    }

    private fun DataSource.execute(sql: String) {
        connection.use { it.createStatement().use { statement -> statement.execute(sql) } }
    }

    private fun DataSource.ruleCommands(): String =
        scalar("SELECT COUNT(*) FROM client_commands WHERE result_type='club_booking_rules'")

    private fun DataSource.ruleAudits(): String =
        scalar("SELECT COUNT(*) FROM audit_log WHERE action='client.club.booking_rules.update'")

    private fun payload(
        version: String = "1",
        hold: Int? = 12,
    ): JsonObject =
        JsonObject(
            mapOf(
                "expectedVersion" to JsonPrimitive(version),
                "cutoffMinutes" to JsonPrimitive(90),
                "arrivalMinutes" to JsonPrimitive(300),
                "holdMinutes" to (hold?.let(::JsonPrimitive) ?: JsonNull),
                "retentionMinutes" to JsonPrimitive(45),
            ),
        )

    private fun JsonObject.firstNight() = getValue("nights").jsonArray.first().jsonObject

    private companion object {
        const val KEY = "booking-rules-command"
        const val PERMISSION = "client.club.booking_rules.write"
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-03-28T12:00:00Z"), ZoneOffset.UTC)
        val defaults =
            Json.parseToJsonElement(
                """{"cutoffMinutes":120,"arrivalMinutes":null,"holdMinutes":null,"retentionMinutes":30}""",
            )
    }
}

internal fun migrateBookingRulesFixture(
    source: DataSource,
    dialect: String,
    target: String? = null,
): Int {
    val flyway =
        Flyway
            .configure()
            .dataSource(source)
            .locations("classpath:db/migration/common", "classpath:db/migration/$dialect")
            .apply { target?.let { target(it) } }
            .load()
    val count = flyway.migrate().migrationsExecuted
    flyway.validate()
    return count
}
