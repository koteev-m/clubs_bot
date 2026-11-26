package com.example.bot.routes

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

abstract class LegacyStaffAdoptionContract : ClientManagerRoutesFixture() {
    protected abstract fun legacyDatabase(block: (DataSource) -> Unit)

    @Test
    fun `supported team invitations accept once and retain provable revocation after business adoption`() =
        legacyDatabase { source ->
            withApp(source) {
                val (club, other) = business(source, confirmed = false)
                upgradeClubFixture(source)
                val issued = legacyInvite(club, "MANAGER")
                val resolve = "$C/invitations/resolve"
                val token = issued.string("token")
                val preview = ok(resolve, FOREIGN, obj("token" to token))
                assertEquals("direct", preview.string("acceptanceFlow"))
                assertEquals("issued", preview.string("status"))
                assertEquals(HttpStatusCode.NotFound, requestAs("$C/clubs/$club/profile", FOREIGN).status)
                val accepted =
                    ok(
                        "$C/invitations/${preview.string("id")}/claim",
                        FOREIGN,
                        obj(
                            "expectedVersion" to "1",
                            "token" to token,
                        ),
                    )
                assertEquals("accepted", accepted.string("status"))
                assertEquals(JsonNull, accepted.getValue("verificationCode"))
                ok("$C/clubs/$club/profile", FOREIGN)
                assertEquals(HttpStatusCode.NotFound, requestAs("$C/clubs/$other/profile", FOREIGN).status)
                assertEquals(HttpStatusCode.NotFound, requestAs(resolve, SECOND_MANAGER, obj("token" to token)).status)
                val second = legacyInvite(club, "MANAGER")
                val owner = source.scalar("SELECT id FROM users WHERE telegram_user_id=$RECIPIENT")
                source.executeFixture(
                    "UPDATE client_club_grants SET is_active=FALSE WHERE user_id=$owner AND club_id=$club " +
                        "AND permission_code='client.club.team.manage'",
                )
                val secondId = second.string("createdInvitationId")
                val secondBody = obj("expectedVersion" to "1", "token" to second.string("token"))
                assertEquals(
                    HttpStatusCode.NotFound,
                    requestAs("$C/invitations/$secondId/claim", SECOND_MANAGER, secondBody).status,
                )
                adopt(source, club)
                val path = "$C/clubs/$club/managers"
                val roster = ok(path, RECIPIENT)
                val old =
                    roster
                        .getValue("legacyAssignments")
                        .jsonArray
                        .single()
                        .jsonObject
                assertEquals(JsonPrimitive(true), old["canRevoke"])
                ok(path, RECIPIENT, revokeSource(roster, old, "revoke_legacy_assignment"))
                assertEquals(HttpStatusCode.NotFound, requestAs("$C/clubs/$club/profile", FOREIGN).status)
            }
        }

    @Test
    fun `review adopted club revokes exactly one legacy staff source across simultaneous commands`() =
        legacyDatabase { source ->
            withApp(source) {
                val (a, b) = business(source, confirmed = false)
                val clientId = source.scalar("SELECT client_id FROM client_clubs WHERE club_id=$a")
                source.executeFixture("INSERT INTO users(telegram_user_id) VALUES ($FOREIGN)")
                val user = source.scalar("SELECT id FROM users WHERE telegram_user_id=$FOREIGN")
                source.executeFixture(
                    "INSERT INTO client_memberships(client_id,user_id,is_active) VALUES ($clientId,$user,TRUE)",
                )
                source.executeFixture(
                    "INSERT INTO client_club_grants(client_id,club_id,user_id,permission_code,is_active) " +
                        "VALUES ($clientId,$a,$user,'client.club.read',TRUE)," +
                        "($clientId,$a,$user,'client.club.modules.read',TRUE)",
                )
                val original = legacyStaff(a, FOREIGN)
                legacyStaff(b, FOREIGN)
                source.executeFixture(
                    "INSERT INTO client_membership_grants(client_id,user_id,permission_code,is_active) " +
                        "VALUES ($clientId,$user,'client.application.manage',TRUE)",
                )
                adopt(source, a)
                val path = "$C/clubs/$a/managers"
                assertTrue(
                    JsonPrimitive(
                        "update",
                    ) in ok("$C/clubs/$a/profile", FOREIGN).getValue("availableActions").jsonArray,
                )
                assertEquals(JsonPrimitive(true), ok("$C/clubs/$a/calendar", FOREIGN)["canWrite"])
                for (write in legacyStaffClubCommands) {
                    ok("$C/clubs/$a/${write.path}", FOREIGN, write.body, write.method, write.key)
                }
                claim(invite(path, RECIPIENT, "NETWORK_MANAGER"), SECOND_MANAGER)
                val network = ok(path, RECIPIENT)
                assertEquals(HttpStatusCode.NotFound, requestAs("$C/clubs/$a/team", RECIPIENT).status)
                val row =
                    network
                        .getValue("legacyAssignments")
                        .jsonArray
                        .single()
                        .jsonObject
                assertEquals(original.string("createdInvitationId"), row.string("id"))
                assertEquals("CLUB_ADMIN", row.string("role"))
                assertEquals(JsonPrimitive(true), row["canRevoke"])
                assertTrue(ok(path, SECOND_MANAGER).getValue("legacyAssignments").jsonArray.isEmpty())
                val command = revokeSource(network, row, "revoke_legacy_assignment")
                assertEquals(HttpStatusCode.Forbidden, requestAs(path, SECOND_MANAGER, command).status)
                val keys = listOf("revoke-old-first", "revoke-old-second")
                val attempts =
                    coroutineScope {
                        keys
                            .map { key ->
                                async { requestAs(path, RECIPIENT, command, key = key).status }
                            }.awaitAll()
                    }
                assertEquals(setOf(HttpStatusCode.OK, HttpStatusCode.Conflict), attempts.toSet())
                val winningKey = keys[attempts.indexOf(HttpStatusCode.OK)]
                assertTrue(
                    ok(path, RECIPIENT, command, key = winningKey).getValue("legacyAssignments").jsonArray.isEmpty(),
                )
                val retained = ok("$C/clubs/$a/profile", FOREIGN)
                for (write in legacyStaffClubCommands) {
                    val route = "$C/clubs/$a/${write.path}"
                    for (key in listOf(write.key, write.key + "-new")) {
                        requestAs(route, FOREIGN, write.body, write.method, key)
                            .assertClientError(HttpStatusCode.NotFound, "not_found")
                    }
                }
                ok("$C/clubs/$a/modules-settings", FOREIGN)
                requestAs(
                    "$C/clubs/$a/finance/template",
                    FOREIGN,
                ).assertClientError(HttpStatusCode.NotFound, "not_found")
                val calendar = legacyStaffClubCommands.single { it.path == "calendar" }
                requestAs("$C/clubs/$a/calendar/commands/resolve", FOREIGN, calendar.body, key = calendar.key)
                    .assertClientError(HttpStatusCode.NotFound, "not_found")
                assertTrue(
                    retained.getValue("permissions").jsonArray.contains(JsonPrimitive("client.club.modules.read")),
                )
                assertTrue(retained.getValue("availableActions").jsonArray.isEmpty())
                assertEquals(
                    HttpStatusCode.NotFound,
                    requestAs("$C/clubs/$a/profile", FOREIGN, profileChange(retained), HttpMethod.Patch).status,
                )
                val other = "$C/clubs/$b/profile"
                ok(other, FOREIGN, profileChange(ok(other, FOREIGN)), HttpMethod.Patch)
                assertEquals(
                    "1",
                    source.scalar(
                        "SELECT COUNT(*) FROM client_memberships WHERE " +
                            "client_id=$clientId AND user_id=$user AND is_active=TRUE",
                    ),
                )
                assertEquals(
                    "1",
                    source.scalar("SELECT COUNT(*) FROM client_club_staff WHERE user_id=$user AND is_active=TRUE"),
                )
                val restore = obj("expectedVersion" to "1", "token" to original.string("token"))
                assertEquals(
                    HttpStatusCode.NotFound,
                    requestAs(
                        "$C/invitations/${row.string("id")}/claim",
                        FOREIGN,
                        restore,
                        key = "claim-staff-$a-$FOREIGN",
                    ).status,
                )
            }
        }

    @Test
    fun `review pending staff links stop offering acceptance after adoption and keep their historical status`() =
        legacyDatabase { source ->
            withApp(source) {
                val (club, _) = business(source, confirmed = false)
                val issued = legacyInvite(club)
                val claimed = legacyInvite(club)
                val claimId = claimed.string("createdInvitationId")
                ok(
                    "$C/invitations/$claimId/claim",
                    FOREIGN,
                    obj(
                        "expectedVersion" to "1",
                        "token" to claimed.string("token"),
                    ),
                )
                val accepted = legacyStaff(club, SECOND_MANAGER).string("createdInvitationId")
                adopt(source, club)
                val target = "$C/invitations/${issued.string("createdInvitationId")}/claim"
                val claim = obj("expectedVersion" to "1", "token" to issued.string("token"))
                assertEquals(HttpStatusCode.NotFound, requestAs(target, THIRD_MANAGER, claim).status)
                val pending = ok("$C/invitations/resolve", THIRD_MANAGER, obj("token" to issued.string("token")))
                val pendingClaim = ok("$C/invitations/$claimId", FOREIGN)
                for (view in listOf(pending, pendingClaim)) {
                    assertEquals("revoked", view.string("status"))
                    assertTrue(view.getValue("availableActions").jsonArray.isEmpty())
                    assertEquals(JsonNull, view["verificationCode"])
                }
                assertEquals("accepted", ok("$C/invitations/$accepted", SECOND_MANAGER).string("status"))
                assertEquals("claimed", source.scalar("SELECT status FROM client_invitations WHERE id=$claimId"))
            }
        }

    @Test
    fun `review legacy role changes preserve later grants and require a complete receipt`() =
        legacyDatabase { source ->
            withApp(source) {
                val (a, b) = business(source, confirmed = false)
                legacyStaff(a, FOREIGN, receiptTag = "first-employment")
                legacyStaff(b, FOREIGN)
                val user = source.scalar("SELECT id FROM users WHERE telegram_user_id=$FOREIGN")
                val team = "$C/clubs/$a/team"
                ok(
                    team,
                    RECIPIENT,
                    JsonObject(
                        obj(
                            "action" to "disable",
                            "userId" to user,
                            "expectedVersion" to ok(team, RECIPIENT).string("version"),
                            "expectedMemberVersion" to "1",
                            "reason" to "End of first appointment",
                        ) + ("confirmed" to JsonPrimitive(true)),
                    ),
                )
                legacyStaff(a, FOREIGN, "ENTRY_MANAGER")
                ok(
                    team,
                    RECIPIENT,
                    JsonObject(
                        obj(
                            "action" to "change_role",
                            "role" to "MANAGER",
                            "userId" to user,
                            "expectedVersion" to ok(team, RECIPIENT).string("version"),
                            "expectedMemberVersion" to "3",
                            "reason" to "Changed responsibilities",
                        ) + ("confirmed" to JsonPrimitive(true)),
                    ),
                )
                source.executeFixture(
                    "UPDATE client_club_grants SET updated_at=TIMESTAMP '2099-01-01 00:00:00' " +
                        "WHERE club_id=$a AND user_id=$user AND permission_code " +
                        "IN ('client.club.read','client.club.profile.write')",
                )
                // A damaged historic receipt must not turn a role label into proof of which grants it supplied.
                source.executeFixture("DELETE FROM client_commands WHERE result_type='club_team' AND result_id=$b")
                adopt(source, a)
                val path = "$C/clubs/$a/managers"
                val roster = ok(path, RECIPIENT)
                val current =
                    roster
                        .getValue("legacyAssignments")
                        .jsonArray
                        .single()
                        .jsonObject
                assertEquals("MANAGER", current.string("role"))
                assertEquals(JsonPrimitive(true), current["canRevoke"])
                ok(path, RECIPIENT, revokeSource(roster, current, "revoke_legacy_assignment"))
                val profile = "$C/clubs/$a/profile"
                ok(profile, FOREIGN, profileChange(ok(profile, FOREIGN)), HttpMethod.Patch)
                assertEquals(JsonPrimitive(false), ok("$C/clubs/$a/calendar", FOREIGN)["canWrite"])
                val ambiguousPath = "$C/clubs/$b/managers"
                val ambiguousRoster = ok(ambiguousPath, RECIPIENT)
                val ambiguous =
                    ambiguousRoster
                        .getValue("legacyAssignments")
                        .jsonArray
                        .single()
                        .jsonObject
                assertEquals(JsonPrimitive(false), ambiguous["canRevoke"])
                val grants =
                    "SELECT COUNT(*) FROM client_club_grants WHERE " +
                        "club_id=$b AND user_id=$user AND is_active=TRUE"
                val before = source.scalar(grants)
                requestAs(
                    ambiguousPath,
                    RECIPIENT,
                    revokeSource(ambiguousRoster, ambiguous, "revoke_legacy_assignment"),
                ).assertClientError(HttpStatusCode.Conflict, "invalid_transition")
                assertEquals(before, source.scalar(grants))
                assertEquals(
                    "1",
                    source.scalar(
                        "SELECT COUNT(*) FROM client_club_staff WHERE club_id=$b AND user_id=$user AND is_active=TRUE",
                    ),
                )
            }
        }

    @Test
    fun `review controlled admissions protect their staff and flat grants from legacy revocation`() =
        legacyDatabase { source ->
            withApp(source) {
                val (club, _) = business(source, confirmed = false)
                legacyStaff(club, FOREIGN)
                adopt(source, club)
                val clientId = source.scalar("SELECT client_id FROM client_clubs WHERE club_id=$club")
                val user = source.scalar("SELECT id FROM users WHERE telegram_user_id=$FOREIGN")
                val tuple = "client_id=$clientId AND club_id=$club AND user_id=$user"
                val path = "$C/clubs/$club/managers"
                val grants = "SELECT COUNT(*) FROM client_club_grants WHERE $tuple AND is_active=TRUE"
                val before = source.scalar(grants)
                source.executeFixture(
                    "INSERT INTO client_club_access_state(client_id,club_id," +
                        "user_id,mode) VALUES ($clientId,$club,$user,'TARGET')",
                )

                suspend fun denied() {
                    val roster = ok(path, RECIPIENT)
                    val controlled =
                        roster
                            .getValue("legacyAssignments")
                            .jsonArray
                            .single()
                            .jsonObject
                    assertEquals(JsonPrimitive(false), controlled["canRevoke"])
                    requestAs(path, RECIPIENT, revokeSource(roster, controlled, "revoke_legacy_assignment"))
                        .assertClientError(HttpStatusCode.Conflict, "invalid_transition")
                    assertEquals(before, source.scalar(grants))
                    assertEquals(
                        "1",
                        source.scalar("SELECT version FROM client_club_staff WHERE $tuple AND is_active=TRUE"),
                    )
                    assertEquals(roster, ok(path, RECIPIENT))
                }
                denied()
                source.executeFixture(
                    "UPDATE client_club_access_state SET mode='SHADOW',status='DISABLED'," +
                        "revoked_at=CURRENT_TIMESTAMP,revoked_by=$user,revoke_reason='Test revoke' WHERE $tuple",
                )
                denied()
                source.executeFixture(
                    "UPDATE client_club_access_state SET status='ACTIVE',membership_version=1," +
                        "revoked_at=NULL,revoked_by=NULL,revoke_reason=NULL WHERE $tuple",
                )
                val audit =
                    source.scalar(
                        "SELECT id FROM audit_log WHERE action='client.club.create' AND entity_id='$club'",
                    )
                source.executeFixture(
                    "INSERT INTO client_role_assignments(id,client_id," +
                        "club_id,user_id,role_code,policy_version,policy_hash," +
                        "selector_kind,selector_json,admission_epoch," +
                        "issuer_authority_ref,created_by,command_connection_key," +
                        "command_key_hash,created_audit_id,last_audit_id,status) " +
                        "SELECT '${UUID.randomUUID()}',$clientId,$club,$user,'FLOOR_MANAGER',1,'${"0".repeat(64)}'," +
                        "'CLUB','[]',1,'Historical foundation',actor_user_id," +
                        "connection_key,key_hash,$audit,$audit,'ACTIVE' " +
                        "FROM client_commands WHERE result_type='club_create' AND result_id=$club",
                )
                denied()
                assertEquals(
                    "1",
                    source.scalar("SELECT COUNT(*) FROM client_role_assignments WHERE $tuple AND status='ACTIVE'"),
                )
            }
        }

    @Test
    fun `review incomplete role history cannot be validated by a matching final role name`() =
        legacyDatabase { source ->
            withApp(source) {
                val (club, _) = business(source, confirmed = false)
                legacyStaff(club, FOREIGN)
                val user = source.scalar("SELECT id FROM users WHERE telegram_user_id=$FOREIGN")
                val team = "$C/clubs/$club/team"
                for ((oldVersion, role) in listOf("1" to "MANAGER", "2" to "CLUB_ADMIN")) {
                    ok(
                        team,
                        RECIPIENT,
                        JsonObject(
                            obj(
                                "action" to "change_role",
                                "role" to role,
                                "userId" to user,
                                "expectedVersion" to ok(team, RECIPIENT).string("version"),
                                "expectedMemberVersion" to oldVersion,
                                "reason" to "Revised duties",
                            ) + ("confirmed" to JsonPrimitive(true)),
                        ),
                    )
                }
                source.executeFixture(
                    "DELETE FROM audit_log WHERE action='client.staff.role.change' AND entity_id='$user'",
                )
                adopt(source, club)
                val path = "$C/clubs/$club/managers"
                val roster = ok(path, RECIPIENT)
                val sourceRow =
                    roster
                        .getValue("legacyAssignments")
                        .jsonArray
                        .single()
                        .jsonObject
                assertEquals("3", sourceRow.string("version"))
                assertEquals(JsonPrimitive(false), sourceRow["canRevoke"])
                requestAs(path, RECIPIENT, revokeSource(roster, sourceRow, "revoke_legacy_assignment"))
                    .assertClientError(HttpStatusCode.Conflict, "invalid_transition")
                assertEquals(roster, ok(path, RECIPIENT))
                val profile = "$C/clubs/$club/profile"
                ok(profile, FOREIGN, profileChange(ok(profile, FOREIGN)), HttpMethod.Patch)
            }
        }

    private suspend fun ApplicationTestBuilder.legacyInvite(
        club: String,
        role: String = "CLUB_ADMIN",
    ): JsonObject {
        val path = "$C/clubs/$club/team"
        return ok(
            path,
            RECIPIENT,
            obj(
                "action" to "invite",
                "role" to role,
                "recipientLabel" to "Staff",
                "expectedVersion" to ok(path, RECIPIENT).string("version"),
            ),
        )
    }

    private suspend fun ApplicationTestBuilder.legacyStaff(
        club: String,
        guest: Long,
        role: String = "CLUB_ADMIN",
        receiptTag: String = "$club-$guest",
    ): JsonObject {
        val issued = legacyInvite(club, role)
        val path = "$C/clubs/$club/team"
        val id = issued.string("createdInvitationId")
        val accepted =
            ok(
                "$C/invitations/$id/claim",
                guest,
                obj(
                    "expectedVersion" to "1",
                    "token" to issued.string("token"),
                ),
                key = "claim-staff-$receiptTag",
            )
        ok(path, RECIPIENT, confirmBody(path, RECIPIENT, accepted), key = "confirm-staff-$receiptTag")
        return issued
    }

    private suspend fun ApplicationTestBuilder.adopt(
        source: DataSource,
        club: String,
    ) {
        upgradeClubFixture(source)
        val clientId = source.scalar("SELECT client_id FROM client_clubs WHERE club_id=$club")
        val user = source.scalar("SELECT id FROM users WHERE telegram_user_id=$RECIPIENT")
        ok("$P/clients/$clientId/members/$user/business-owner", OWNER, obj("expectedVersion" to "1"))
    }

    private fun DataSource.executeFixture(command: String) {
        connection.use { it.createStatement().use { statement -> statement.executeUpdate(command) } }
    }
}
