package com.example.bot.routes

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real signed HTTP identity -> Ktor -> transactional JDBC -> persisted task on application reopen. */
open class ClientPreparationRoutesDataTest : ClientOnboardingRoutesTestBase() {
    @Test
    fun `preparation http preserves private ownership invitations saved configuration and review after reopen`() =
        database { source ->
            lateinit var club: JsonObject
            withApp(source) {
                club = club()
                val path = "$C/clubs/${club.string("id")}/preparation"
                assertEquals(HttpStatusCode.Unauthorized, client.get(path).status)
                assertEquals(HttpStatusCode.NotFound, requestAs(path, FOREIGN).status)
                val initial = ok(path, RECIPIENT)
                assertEquals("unassigned", initial.string("status"))
                assertEquals("false", initial["published"].toString())
                val issued =
                    ok(
                        path,
                        RECIPIENT,
                        buildJsonObject {
                            put("action", "invite")
                            put("expectedVersion", initial.getValue("version"))
                            put(
                                "expectedTeamVersion",
                                ok("$C/clubs/${club.string("id")}/team", RECIPIENT).getValue("version"),
                            )
                            put("recipientLabel", "Synthetic responsible")
                        },
                        HttpMethod.Patch,
                    )
                assertTrue(issued.string("invitationLink").startsWith("https://t.me/synthetic_bot?startapp=ci_"))
                val pending =
                    ok(
                        "$C/invitations/${issued.string("createdInvitationId")}/claim",
                        FOREIGN,
                        obj("token" to issued.string("token"), "expectedVersion" to "1"),
                    )
                assertEquals(HttpStatusCode.NotFound, requestAs(path, FOREIGN).status)
                ok(
                    "$C/clubs/${club.string("id")}/team",
                    RECIPIENT,
                    buildJsonObject {
                        put("action", "confirm")
                        put("expectedVersion", ok("$C/clubs/${club.string("id")}/team", RECIPIENT).getValue("version"))
                        put("invitationId", pending.getValue("id"))
                        put("expectedInvitationVersion", pending.getValue("version"))
                        put("verificationCode", pending.getValue("verificationCode"))
                        put("confirmed", true)
                    },
                )
                assertEquals("in_progress", ok(path, FOREIGN).string("status"))
                assertEquals(HttpStatusCode.NotFound, preparationRequest(club, "accept", FOREIGN).status)
                val profilePath = "$C/clubs/${club.string("id")}/profile"
                val profile = ok(profilePath, FOREIGN)
                ok(
                    profilePath,
                    FOREIGN,
                    JsonObject(
                        version(profile) +
                            obj(
                                "name" to "Prepared club",
                                "description" to "Saved result",
                                "timezone" to "Europe/Moscow",
                            ),
                    ),
                    HttpMethod.Patch,
                )
                val submitted = preparationOk(club, "submit", FOREIGN)
                assertEquals("submitted", submitted.string("status"))
            }
            withApp(source) {
                val path = "$C/clubs/${club.string("id")}/preparation"
                assertEquals("submitted", ok(path, RECIPIENT).string("status"))
                assertEquals("Prepared club", ok("$C/clubs/${club.string("id")}/profile", RECIPIENT).string("name"))
                assertEquals(HttpStatusCode.BadRequest, preparationRequest(club, "return", RECIPIENT).status)
                preparationOk(club, "return", RECIPIENT, obj("reason" to "Уточните часы"))
                assertEquals("Уточните часы", ok(path, FOREIGN).string("reason"))
                preparationOk(club, "submit", FOREIGN)
                preparationOk(club, "accept", RECIPIENT)
                assertEquals("accepted", ok(path, FOREIGN).string("status"))
                assertEquals("false", ok(path, RECIPIENT)["published"].toString())
                val team = ok("$C/clubs/${club.string("id")}/team", RECIPIENT)
                val staff =
                    team.getValue("members").jsonArray.map { it.jsonObject }.single {
                        it["role"].toString() ==
                            "\"CLUB_ADMIN\""
                    }
                ok(
                    "$C/clubs/${club.string("id")}/team",
                    RECIPIENT,
                    buildJsonObject {
                        put("action", "disable")
                        put("expectedVersion", team.getValue("version"))
                        put("userId", staff.getValue("userId"))
                        put("expectedMemberVersion", staff.getValue("version"))
                        put("reason", "Synthetic revoke")
                        put("confirmed", true)
                    },
                )
                assertEquals(HttpStatusCode.NotFound, requestAs(path, FOREIGN).status)
                assertFalse(
                    ok(path, RECIPIENT)["assignee"]!!
                        .jsonObject
                        .getValue("active")
                        .toString()
                        .toBoolean(),
                )
            }
        }

    @Test
    fun `preparation http rejects ids spoofing readonly mutation initdata query and stale versions`() =
        database { source ->
            withApp(source) {
                val club = club()
                val path = "$C/clubs/${club.string("id")}/preparation"
                assertEquals(
                    HttpStatusCode.Unauthorized,
                    client.get("$path?initData=synthetic") { header("X-Telegram-Init-Data", signed(RECIPIENT)) }.status,
                )
                assertEquals(
                    HttpStatusCode.BadRequest,
                    preparationRequest(club, "self", RECIPIENT, obj("clubId" to "999999")).status,
                )
                val stale = obj("action" to "later", "expectedVersion" to ok(path, RECIPIENT).string("version"))
                preparationOk(club, "self", RECIPIENT)
                assertEquals(HttpStatusCode.Conflict, requestAs(path, RECIPIENT, stale, HttpMethod.Patch).status)
                val other = club(998104)
                assertEquals(
                    HttpStatusCode.NotFound,
                    requestAs("$C/clubs/${other.string("id")}/preparation", RECIPIENT).status,
                )
                assertEquals(HttpStatusCode.NotFound, requestAs("$C/clubs/999999/preparation", RECIPIENT).status)
                source.connection.use { connection ->
                    connection.createStatement().use {
                        it.execute(
                            "UPDATE client_club_grants SET is_active=FALSE WHERE club_id=${club.string(
                                "id",
                            )} AND permission_code='client.club.team.manage'",
                        )
                    }
                }
                assertFalse(ok(path, RECIPIENT).getValue("canManage").toString().toBoolean())
                assertEquals(HttpStatusCode.NotFound, preparationRequest(club, "later", RECIPIENT).status)
                assertEquals(HttpStatusCode.NotFound, preparationRequest(club, "submit", RECIPIENT).status)
            }
        }

    private suspend fun ApplicationTestBuilder.preparationRequest(
        club: JsonObject,
        action: String,
        user: Long,
        extra: JsonObject = JsonObject(emptyMap()),
    ) = requestAs(
        "$C/clubs/${club.string("id")}/preparation",
        user,
        JsonObject(
            version(ok("$C/clubs/${club.string("id")}/preparation", user)) + obj("action" to action) + extra,
        ),
        HttpMethod.Patch,
    )

    private suspend fun ApplicationTestBuilder.preparationOk(
        club: JsonObject,
        action: String,
        user: Long,
        extra: JsonObject = JsonObject(emptyMap()),
    ) = ok(
        "$C/clubs/${club.string("id")}/preparation",
        user,
        JsonObject(
            version(ok("$C/clubs/${club.string("id")}/preparation", user)) + obj("action" to action) + extra,
        ),
        HttpMethod.Patch,
    )

    private suspend fun ApplicationTestBuilder.club(user: Long = RECIPIENT): JsonObject {
        val issued =
            ok(
                "$P/invitations",
                OWNER,
                obj(
                    "clientName" to "Synthetic preparation client",
                    "recipientLabel" to "Owner",
                ),
            )
        ok(
            "$C/invitations/${issued.string("id")}/claim",
            user,
            obj("token" to issued.string("token"), "expectedVersion" to "1"),
        )
        return ok(
            "$C/clients/${issued.string("clientId")}/clubs",
            user,
            obj(
                "venueName" to "Synthetic preparation venue",
                "countryCode" to "RU",
                "cityId" to "524901",
                "address" to "Synthetic address",
            ),
        )
    }
}
