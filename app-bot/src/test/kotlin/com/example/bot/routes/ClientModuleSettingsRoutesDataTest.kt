package com.example.bot.routes

import com.example.bot.webapp.TEST_BOT_TOKEN
import com.example.bot.webapp.WebAppInitDataTestHelper
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.assertEquals

/** Identical transport, authorization, persistence and race checks on H2 and PostgreSQL. */
open class ClientModuleSettingsRoutesDataTest : ClientOnboardingRoutesTestBase() {
    @Test
    fun `read is inert and explicit save persists canonical early rule without activating anything`() =
        database { source ->
            lateinit var path: String
            lateinit var saved: JsonObject
            withApp(source) {
                path = path(createClub(RECIPIENT))
                val initial = ok(path, RECIPIENT)
                assertEquals(JsonPrimitive(false), initial["settingsPresent"])
                assertEquals(JsonNull, initial["earlyWindowMinutes"])
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM club_gamification_settings"))
                assertEquals("0", source.auditCount())
                saved = ok(path, RECIPIENT, payload(180), HttpMethod.Patch, KEY)
                assertEquals("2", saved.string("version"))
                assertEquals(JsonPrimitive(180), saved["earlyWindowMinutes"])
                assertEquals(JsonPrimitive(true), saved["settingsPresent"])
                assertEquals(
                    setOf(JsonPrimitive(false)),
                    saved
                        .getValue("flags")
                        .jsonObject.values
                        .toSet(),
                )
                assertEquals("180", source.scalar("SELECT early_window_minutes FROM club_gamification_settings"))
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM user_badges"))
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM reward_coupons"))
            }
            withApp(source) {
                assertEquals(saved, ok(path, RECIPIENT))
                assertEquals(saved, ok(path, RECIPIENT, payload(180), HttpMethod.Patch, KEY))
                assertEquals("1", source.auditCount())
                assertEquals("1", source.commandCount())
                requestAs(path, RECIPIENT, payload(181), HttpMethod.Patch, KEY)
                    .assertClientError(HttpStatusCode.Conflict, "idempotency_conflict")
                requestAs(path, RECIPIENT, payload(181), HttpMethod.Patch)
                    .assertClientError(HttpStatusCode.Conflict, "version_conflict")
                val cleared = ok(path, RECIPIENT, payload(null, "2"), HttpMethod.Patch)
                assertEquals(JsonNull, cleared["earlyWindowMinutes"])
                assertEquals("3", cleared.string("version"))
            }
        }

    @Test
    fun `existing settings flags and definitions survive early rule edits and no reward is issued`() =
        database { source ->
            withApp(source) {
                val club = createClub(RECIPIENT)
                source.execute(
                    "INSERT INTO club_gamification_settings(club_id,stamps_enabled,early_enabled,badges_enabled," +
                        "prizes_enabled,contests_enabled,tables_loyalty_enabled,early_window_minutes) VALUES " +
                        "(${club.string("id")},TRUE,TRUE,TRUE,TRUE,TRUE,TRUE,90)",
                )
                val path = path(club)
                val before = ok(path, RECIPIENT)
                assertEquals(
                    setOf(JsonPrimitive(true)),
                    before
                        .getValue("flags")
                        .jsonObject.values
                        .toSet(),
                )
                val after = ok(path, RECIPIENT, payload(120), HttpMethod.Patch)
                assertEquals(before["flags"], after["flags"])
                assertEquals(JsonPrimitive(120), after["earlyWindowMinutes"])
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM reward_coupons"))
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM user_badges"))
            }
        }

    @Test
    fun `invalid flags scope redirection and malformed values cannot change stored settings`() =
        database { source ->
            withApp(source) {
                val path = path(createClub(RECIPIENT))
                val bodies =
                    listOf(
                        payload(-1),
                        obj("expectedVersion" to "1", "earlyWindowMinutes" to "180"),
                        obj("expectedVersion" to "1"),
                        JsonObject(payload(60) + ("stampsEnabled" to JsonPrimitive(true))),
                        JsonObject(payload(60) + ("clubId" to JsonPrimitive("99"))),
                        JsonObject(payload(60) + ("clientId" to JsonPrimitive("99"))),
                        JsonObject(payload(60) + ("earlyWindowMinutes" to JsonPrimitive(1.5))),
                    )
                bodies.forEach {
                    requestAs(path, RECIPIENT, it, HttpMethod.Patch)
                        .assertClientError(HttpStatusCode.BadRequest, "validation_error")
                }
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM club_gamification_settings"))
                assertEquals("0", source.auditCount())
                assertEquals("0", source.commandCount())
            }
        }

    @Test
    fun `fresh identity and both exact grants are required independently of legacy or foreign authority`() =
        database { source ->
            withApp(source) {
                val club = createClub(RECIPIENT)
                val path = path(club)
                createClub(FOREIGN)
                source.execute(
                    "INSERT INTO user_roles(user_id,role_code,scope_type) " +
                        "SELECT id,'OWNER','GLOBAL' FROM users WHERE telegram_user_id=$OWNER",
                )
                client.get(path).assertClientError(HttpStatusCode.Unauthorized, "unauthorized")
                for (user in listOf(OWNER, FOREIGN)) {
                    requestAs(path, user).assertClientError(HttpStatusCode.NotFound, "not_found")
                    requestAs(path, user, payload(120), HttpMethod.Patch)
                        .assertClientError(HttpStatusCode.NotFound, "not_found")
                }
                val stale =
                    WebAppInitDataTestHelper.createInitData(
                        TEST_BOT_TOKEN,
                        mapOf(
                            "user" to WebAppInitDataTestHelper.encodeUser(RECIPIENT),
                            "auth_date" to
                                Instant
                                    .now()
                                    .minusSeconds(86_400)
                                    .epochSecond
                                    .toString(),
                        ),
                    )
                client
                    .patch(path) {
                        header("X-Telegram-Init-Data", stale)
                        header("Content-Type", "application/json")
                        header("Idempotency-Key", KEY)
                        setBody(payload(120).toString())
                    }.assertClientError(HttpStatusCode.Unauthorized, "unauthorized")
                source.toggleGrant(club, WRITE, false)
                assertEquals(JsonPrimitive(false), ok(path, RECIPIENT)["canWrite"])
                requestAs(path, RECIPIENT, payload(120), HttpMethod.Patch)
                    .assertClientError(HttpStatusCode.NotFound, "not_found")
                source.toggleGrant(club, WRITE, true)
                source.toggleGrant(club, READ, false)
                requestAs(path, RECIPIENT).assertClientError(HttpStatusCode.NotFound, "not_found")
                requestAs(path, RECIPIENT, payload(120), HttpMethod.Patch)
                    .assertClientError(HttpStatusCode.NotFound, "not_found")
                assertEquals("0", source.auditCount())
            }
        }

    @Test
    fun `revoked membership client or mapping prevents saved command replay and reads`() =
        database { source ->
            withApp(source) {
                val club = createClub(RECIPIENT)
                val path = path(club)
                ok(path, RECIPIENT, payload(180), HttpMethod.Patch, KEY)
                val clientId = club.string("clientId")
                val disables =
                    listOf(
                        "UPDATE client_memberships SET is_active=FALSE WHERE client_id=$clientId" to
                            "UPDATE client_memberships SET is_active=TRUE WHERE client_id=$clientId",
                        "UPDATE clients SET is_active=FALSE WHERE id=$clientId" to
                            "UPDATE clients SET is_active=TRUE WHERE id=$clientId",
                        "UPDATE client_club_grants SET is_active=FALSE WHERE club_id=${club.string("id")}" to
                            "UPDATE client_club_grants SET is_active=TRUE WHERE club_id=${club.string("id")}",
                    )
                disables.forEach { (revoke, restore) ->
                    source.execute(revoke)
                    requestAs(path, RECIPIENT).assertClientError(HttpStatusCode.NotFound, "not_found")
                    requestAs(path, RECIPIENT, payload(180), HttpMethod.Patch, KEY)
                        .assertClientError(HttpStatusCode.NotFound, "not_found")
                    source.execute(restore)
                }
                assertEquals("1", source.auditCount())
            }
        }

    @Test
    fun `audit failure rolls back canonical setting revision and command allowing same key retry`() =
        database { source ->
            withApp(source) {
                val path = path(createClub(RECIPIENT))
                source.execute(
                    "ALTER TABLE audit_log ADD CONSTRAINT module_audit_failure " +
                        "CHECK (action <> 'client.club.modules.update')",
                )
                requestAs(path, RECIPIENT, payload(120), HttpMethod.Patch, KEY)
                    .assertClientError(HttpStatusCode.InternalServerError, "internal_error")
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM club_gamification_settings"))
                assertEquals("0", source.commandCount())
                source.execute("ALTER TABLE audit_log DROP CONSTRAINT module_audit_failure")
                assertEquals("2", ok(path, RECIPIENT, payload(120), HttpMethod.Patch, KEY).string("version"))
            }
        }

    @Test
    fun `competing edits serialize and duplicate commands change the canonical rule once`() =
        database { source ->
            withApp(source) {
                val club = createClub(RECIPIENT)
                source.addEditor(club)
                val path = path(club)
                val statuses =
                    coroutineScope {
                        val first = async { requestAs(path, RECIPIENT, payload(90), HttpMethod.Patch).status }
                        val second = async { requestAs(path, FOREIGN, payload(120), HttpMethod.Patch).status }
                        setOf(first.await(), second.await())
                    }
                assertEquals(setOf(HttpStatusCode.OK, HttpStatusCode.Conflict), statuses)
                val repeated =
                    coroutineScope {
                        List(2) { async { ok(path, RECIPIENT, payload(180, "2"), HttpMethod.Patch, KEY) } }
                            .map { it.await() }
                    }
                assertEquals(repeated[0], repeated[1])
                assertEquals("3", repeated[0].string("version"))
                assertEquals("2", source.auditCount())
                assertEquals("2", source.commandCount())
            }
        }

    @Test
    fun `settings writes stay closed after club is published`() =
        database { source ->
            withApp(source) {
                val club = createClub(RECIPIENT)
                source.execute("UPDATE clubs SET is_active=TRUE WHERE id=${club.string("id")}")
                val path = path(club)
                assertEquals(JsonPrimitive(false), ok(path, RECIPIENT)["canWrite"])
                requestAs(path, RECIPIENT, payload(120), HttpMethod.Patch)
                    .assertClientError(HttpStatusCode.Conflict, "invalid_transition")
                assertEquals("0", source.auditCount())
            }
        }

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

    private fun path(club: JsonObject) = "$C/clubs/${club.string("id")}/modules-settings"

    private fun DataSource.execute(sql: String) {
        connection.use { it.createStatement().use { statement -> statement.execute(sql) } }
    }

    private fun DataSource.toggleGrant(
        club: JsonObject,
        permission: String,
        enabled: Boolean,
    ) = execute(
        "UPDATE client_club_grants SET is_active=$enabled WHERE club_id=${club.string("id")} " +
            "AND permission_code='$permission'",
    )

    private fun DataSource.addEditor(club: JsonObject) {
        execute("INSERT INTO users(telegram_user_id) VALUES ($FOREIGN)")
        val userId = scalar("SELECT id FROM users WHERE telegram_user_id=$FOREIGN")
        val clientId = club.string("clientId")
        val clubId = club.string("id")
        execute("INSERT INTO client_memberships(client_id,user_id,is_active) VALUES ($clientId,$userId,TRUE)")
        listOf("client.club.read", READ, WRITE).forEach { permission ->
            execute(
                "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active) " +
                    "VALUES ($clientId,$userId,$clubId,'$permission',TRUE)",
            )
        }
    }

    private fun DataSource.auditCount() =
        scalar("SELECT COUNT(*) FROM audit_log WHERE action='client.club.modules.update'")

    private fun DataSource.commandCount() =
        scalar("SELECT COUNT(*) FROM client_commands WHERE result_type='club_module_settings'")

    private fun payload(
        minutes: Int?,
        version: String = "1",
    ) = JsonObject(
        mapOf(
            "expectedVersion" to JsonPrimitive(version),
            "earlyWindowMinutes" to (minutes?.let(::JsonPrimitive) ?: JsonNull),
        ),
    )

    private companion object {
        const val KEY = "module-settings-command"
        const val READ = "client.club.modules.read"
        const val WRITE = "client.club.modules.write"
    }
}
