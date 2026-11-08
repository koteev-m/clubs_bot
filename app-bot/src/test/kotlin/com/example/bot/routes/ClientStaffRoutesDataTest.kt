package com.example.bot.routes

import io.ktor.client.request.get
import io.ktor.client.request.header
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

class ClientStaffRoutesDataTest : ClientOnboardingRoutesTestBase() {
    @Test
    fun `team http path persists restricts staff scopes prevents spoofing and disables old sessions`() =
        database { source ->
            lateinit var club: JsonObject
            lateinit var issued: JsonObject
            withApp(source) {
                club = club()
                val path = "$C/clubs/${club.string("id")}/team"
                assertEquals(HttpStatusCode.Unauthorized, client.get(path).status)
                assertEquals(HttpStatusCode.NotFound, requestAs(path, OWNER).status)
                val team = ok(path, RECIPIENT)
                val invite =
                    obj(
                        "action" to "invite",
                        "expectedVersion" to team.string("version"),
                        "role" to "ENTRY_MANAGER",
                        "recipientLabel" to "Synthetic colleague",
                    )
                assertEquals(
                    HttpStatusCode.BadRequest,
                    requestAs(
                        path,
                        RECIPIENT,
                        JsonObject(
                            invite + obj("clientId" to club.string("clientId")),
                        ),
                    ).status,
                )
                assertEquals(
                    HttpStatusCode.BadRequest,
                    requestAs(
                        path,
                        RECIPIENT,
                        JsonObject(
                            invite + obj("role" to "OWNER"),
                        ),
                    ).status,
                )
                issued = ok(path, RECIPIENT, invite, key = "staff-http-issue")
                assertTrue(issued.string("invitationLink").startsWith("https://t.me/synthetic_bot?startapp=ci_"))
                assertFalse(ok(path, RECIPIENT, invite, key = "staff-http-issue").containsKey("token"))
                val resolved = ok("$C/invitations/resolve", FOREIGN, obj("token" to issued.string("token")))
                assertEquals("staff", resolved.string("acceptanceMode"))
                assertEquals(
                    HttpStatusCode.NotFound,
                    requestAs("$C/clubs/${club.string("id")}/profile", FOREIGN).status,
                )
                val pending =
                    ok(
                        "$C/invitations/${issued.string("createdInvitationId")}/claim",
                        FOREIGN,
                        obj(
                            "token" to issued.string("token"),
                            "expectedVersion" to "1",
                        ),
                    )
                assertEquals("claimed", pending.string("status"))
                assertEquals(HttpStatusCode.NotFound, requestAs(path, FOREIGN).status)
                val confirm =
                    buildJsonObject {
                        put("action", "confirm")
                        put("expectedVersion", ok(path, RECIPIENT).getValue("version"))
                        put("invitationId", pending.getValue("id"))
                        put("expectedInvitationVersion", pending.getValue("version"))
                        put("verificationCode", pending.getValue("verificationCode"))
                        put("confirmed", true)
                    }
                ok(path, RECIPIENT, confirm)
            }
            withApp(source) {
                val clubPath = "$C/clubs/${club.string("id")}"
                val profile = ok("$clubPath/profile", FOREIGN)
                assertEquals(
                    listOf("client.club.read"),
                    profile["permissions"]!!.jsonArray.map { it.toString().trim('"') },
                )
                assertEquals(HttpStatusCode.NotFound, requestAs("$clubPath/finance/template", FOREIGN).status)
                assertEquals(HttpStatusCode.NotFound, requestAs("$clubPath/team", FOREIGN).status)
                val other = club(998104L)
                assertEquals(
                    HttpStatusCode.NotFound,
                    requestAs("$C/clubs/${other.string("id")}/profile", FOREIGN).status,
                )
                val team = ok("$clubPath/team", RECIPIENT)
                val staff =
                    team["members"]!!.jsonArray.map { it.jsonObject }.single {
                        it["role"].toString() ==
                            "\"ENTRY_MANAGER\""
                    }
                val disable =
                    buildJsonObject {
                        put("action", "disable")
                        put("expectedVersion", team.getValue("version"))
                        put("userId", staff.getValue("userId"))
                        put("expectedMemberVersion", staff.getValue("version"))
                        put("reason", "Synthetic revoke")
                        put("confirmed", true)
                    }
                ok("$clubPath/team", RECIPIENT, disable, key = "staff-http-disable")
                ok("$clubPath/team", RECIPIENT, disable, key = "staff-http-disable")
                assertEquals(HttpStatusCode.NotFound, requestAs("$clubPath/profile", FOREIGN).status)
                assertEquals(
                    HttpStatusCode.NotFound,
                    requestAs("$C/invitations/${issued.string("createdInvitationId")}", FOREIGN).status,
                )
                assertEquals(
                    "0",
                    source.scalar(
                        "SELECT COUNT(*) FROM user_roles r JOIN users u ON u.id=r.user_id WHERE u.telegram_user_id=$FOREIGN",
                    ),
                )
            }
        }

    @Test
    fun `team http denies a forged team grant without immutable ownership and rejects initdata query`() =
        database { source ->
            withApp(source) {
                val club = club()
                val id = club.string("id")
                val path = "$C/clubs/$id/team"
                assertEquals(
                    HttpStatusCode.Unauthorized,
                    client
                        .get("$path?initData=synthetic") {
                            header("X-Telegram-Init-Data", signed(RECIPIENT))
                        }.status,
                )
                source.connection.use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute(
                            "INSERT INTO users(telegram_user_id,display_name) VALUES ($FOREIGN,'Synthetic member')",
                        )
                        val user = source.scalar("SELECT id FROM users WHERE telegram_user_id=$FOREIGN")
                        val clientId = club.string("clientId")
                        statement.execute(
                            "INSERT INTO client_memberships(client_id,user_id,is_active) VALUES ($clientId,$user,TRUE)",
                        )
                        for (permission in listOf(
                            "client.club.read",
                            "client.club.team.manage",
                        )) {
                            statement.execute(
                                "INSERT INTO client_club_grants(client_id,club_id,user_id,permission_code,is_active) VALUES ($clientId,$id,$user,'$permission',TRUE)",
                            )
                        }
                    }
                }
                assertEquals(HttpStatusCode.NotFound, requestAs(path, FOREIGN).status)
                assertFalse(
                    ok("$C/clubs/$id/profile", FOREIGN)["permissions"]!!.jsonArray.any {
                        it.toString().contains("team.manage")
                    },
                )
            }
        }

    private suspend fun ApplicationTestBuilder.club(user: Long = RECIPIENT): JsonObject {
        val issued =
            ok(
                "$P/invitations",
                OWNER,
                obj(
                    "clientName" to "Synthetic staff client",
                    "recipientLabel" to "Owner",
                ),
            )
        ok(
            "$C/invitations/${issued.string("id")}/claim",
            user,
            obj(
                "token" to issued.string("token"),
                "expectedVersion" to "1",
            ),
        )
        return ok(
            "$C/clients/${issued.string("clientId")}/clubs",
            user,
            obj(
                "venueName" to "Synthetic staff venue",
                "countryCode" to "RU",
                "cityId" to "524901",
                "address" to "Synthetic address",
            ),
        )
    }
}
