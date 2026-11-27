package com.example.bot.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

abstract class ClubStaffPolicyContract : NetworkManagerPolicyContract() {
    @Test
    fun `V084 amends verified live club manager assignments and preserves historical invitation policy`() =
        databaseAt("83") { source ->
            withApp(source) {
                val (club, _) = business(source)
                val roster = "$C/clubs/$club/managers"
                val profile = "$C/clubs/$club/profile"
                val accepted = invite(roster, RECIPIENT, "CLUB_MANAGER")
                claim(accepted, FOREIGN)
                val pending = invite(roster, RECIPIENT, "CLUB_MANAGER")
                val operations = JsonPrimitive("client.club.operations.team.manage")
                val originalPolicy = "79402dc78454fcaac9118535ed01bcf93644c985c71448b16865782c1e08695f"
                assertFalse(operations in ok(profile, FOREIGN).getValue("permissions").jsonArray)
                assertEquals(
                    "1",
                    source.scalar("SELECT COUNT(*) FROM client_role_assignments WHERE policy_hash='$originalPolicy'"),
                )

                upgradeClubFixture(source)

                assertTrue(operations in ok(profile, FOREIGN).getValue("permissions").jsonArray)
                assertEquals(
                    "1",
                    source.scalar("SELECT COUNT(*) FROM audit_log WHERE action='client.club_manager.policy.v2'"),
                )
                claim(pending, SECOND_MANAGER)
                assertFalse(operations in ok(profile, SECOND_MANAGER).getValue("permissions").jsonArray)
                assertEquals(
                    "2",
                    source.scalar("SELECT COUNT(*) FROM client_role_assignments WHERE policy_hash='$originalPolicy'"),
                )
                assertEquals(
                    "1",
                    source.scalar(
                        "SELECT COUNT(*) FROM client_role_assignments WHERE club_manager_operations_policy_hash IS NOT NULL",
                    ),
                )
            }
        }

    @Test
    fun `subordinate invitations and configuration delegations follow exact club manager source`() =
        database { source ->
            withApp(source) {
                val (club, other) = business(source)
                val base = "$C/clubs/$club"
                val roster = "$base/managers"
                val ownerAssignment = invite(roster, RECIPIENT, "CLUB_MANAGER")
                claim(ownerAssignment, FOREIGN)
                val floorInvite = invite(roster, FOREIGN, "FLOOR_MANAGER")
                val accepted = claim(floorInvite, SECOND_MANAGER)
                assertEquals("FLOOR_MANAGER", accepted.string("role"))
                val floor =
                    ok(roster, FOREIGN)
                        .getValue("assignments")
                        .jsonArray
                        .single()
                        .jsonObject
                val pending = invite(roster, FOREIGN, "ENTRANCE_MANAGER")

                suspend fun permissions() = ok("$base/profile", SECOND_MANAGER).getValue("permissions").jsonArray
                val floorInitial = permissions()
                val calendar = JsonPrimitive("client.club.calendar.write")
                val formation = JsonPrimitive("client.club.night.create")
                val profile = JsonPrimitive("client.club.profile.write")
                assertTrue(JsonPrimitive("client.club.operations.deposit.record") in floorInitial)
                assertFalse(calendar in floorInitial)
                assertFalse(formation in floorInitial)
                assertFalse(profile in floorInitial)
                assertEquals(HttpStatusCode.NotFound, requestAs(roster, SECOND_MANAGER).status)
                assertEquals(HttpStatusCode.NotFound, requestAs("$C/clubs/$other/profile", SECOND_MANAGER).status)

                suspend fun delegate(permission: String): JsonObject =
                    ok(
                        roster,
                        FOREIGN,
                        JsonObject(
                            obj(
                                "action" to "delegate",
                                "expectedVersion" to ok(roster, FOREIGN).string("version"),
                                "assignmentId" to floor.string("id"),
                                "expectedAssignmentVersion" to floor.string("version"),
                                "permission" to permission,
                            ) + ("confirmed" to JsonPrimitive(true)),
                        ),
                    )

                delegate(calendar.content)
                assertTrue(calendar in permissions())
                assertFalse(formation in permissions())
                delegate(formation.content)
                assertTrue(formation in permissions())
                assertFalse(profile in permissions())

                val ownerRoster = ok(roster, RECIPIENT)
                revoke(
                    roster,
                    RECIPIENT,
                    ownerRoster
                        .getValue("assignments")
                        .jsonArray
                        .single()
                        .jsonObject,
                )
                assertEquals(HttpStatusCode.NotFound, requestAs(roster, FOREIGN).status)
                assertEquals(floorInitial.toSet(), permissions().toSet())
                assertEquals(
                    HttpStatusCode.NotFound,
                    requestAs(
                        "$C/manager-invitations/resolve",
                        THIRD_MANAGER,
                        obj("token" to pending.string("token")),
                    ).status,
                )

                claim(invite(roster, RECIPIENT, "CLUB_MANAGER"), THIRD_MANAGER)
                val successor = ok(roster, THIRD_MANAGER)
                val previousInvite =
                    successor
                        .getValue("invitations")
                        .jsonArray
                        .single()
                        .jsonObject
                ok(
                    roster,
                    THIRD_MANAGER,
                    JsonObject(
                        obj(
                            "action" to "revoke_invitation",
                            "expectedVersion" to successor.string("version"),
                            "invitationId" to previousInvite.string("id"),
                            "expectedInvitationVersion" to previousInvite.string("version"),
                        ) + ("confirmed" to JsonPrimitive(true)),
                    ),
                )
                revoke(roster, THIRD_MANAGER, floor)
                assertEquals(HttpStatusCode.NotFound, requestAs("$base/profile", SECOND_MANAGER).status)
            }
        }

    protected suspend fun ApplicationTestBuilder.revoke(
        path: String,
        issuer: Long,
        target: JsonObject,
    ) {
        val body =
            JsonObject(
                obj(
                    "action" to "revoke_assignment",
                    "expectedVersion" to ok(path, issuer).string("version"),
                    "assignmentId" to target.string("id"),
                    "expectedAssignmentVersion" to target.string("version"),
                    "reason" to "End of duties",
                ) +
                    ("confirmed" to JsonPrimitive(true)),
            )
        val key = UUID.randomUUID().toString()
        val revoked = ok(path, issuer, body, key = key)
        assertEquals(revoked, ok(path, issuer, body, key = key))
        assertEquals(HttpStatusCode.Conflict, requestAs(path, issuer, body).status)
    }
}
