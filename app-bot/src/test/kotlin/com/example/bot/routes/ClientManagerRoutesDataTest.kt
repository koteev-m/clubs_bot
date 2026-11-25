package com.example.bot.routes

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

open class ClientManagerRoutesDataTest : LegacyStaffAdoptionContract() {
    override fun legacyDatabase(block: (DataSource) -> Unit) = databaseAt("79", block)

    @Test
    fun `explicit business owner and scoped managers configure and revoke without assuming legacy authority`() =
        database { source ->
            withApp(source) {
                val (club, other) = business(source)
                val path = "$C/clubs/$club/managers"
                val profile = "$C/clubs/$club/profile"
                val otherProfile = "$C/clubs/$other/profile"
                assertEquals(HttpStatusCode.Unauthorized, client.get(path).status)
                val ownerProfile = ok(profile, RECIPIENT)
                assertFalse(
                    ownerProfile["permissions"]!!.jsonArray.contains(JsonPrimitive("client.club.profile.write")),
                )
                assertEquals(
                    HttpStatusCode.NotFound,
                    requestAs(profile, RECIPIENT, profileChange(ownerProfile), HttpMethod.Patch).status,
                )
                val network = invite(path, RECIPIENT, "NETWORK_MANAGER")
                val claimed = claim(network, FOREIGN)
                assertEquals(HttpStatusCode.NotFound, requestAs(path, FOREIGN).status)
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM client_role_assignments"))
                val listed = ok(path, RECIPIENT)["invitations"]!!.jsonArray.single().jsonObject
                assertEquals(JsonNull, listed["verificationCode"])
                val networkResult = confirm(path, RECIPIENT, claimed)
                assertEquals(1, networkResult["assignments"]!!.jsonArray.size)
                val manager = invite(path, FOREIGN, "CLUB_MANAGER")
                val clubClaim = claim(manager, SECOND_MANAGER)
                assertEquals(HttpStatusCode.NotFound, requestAs(otherProfile, FOREIGN).status)
                assertEquals(HttpStatusCode.NotFound, requestAs(otherProfile, SECOND_MANAGER).status)
                assertEquals(HttpStatusCode.NotFound, requestAs("$C/clubs/$other/managers", FOREIGN).status)
                val peerInvite =
                    obj(
                        "action" to "invite",
                        "expectedVersion" to ok(path, FOREIGN).string("version"),
                        "role" to "NETWORK_MANAGER",
                        "recipientLabel" to "Peer",
                    )
                assertEquals(HttpStatusCode.Forbidden, requestAs(path, FOREIGN, peerInvite).status)
                val takeover = confirmBody(path, RECIPIENT, clubClaim)
                assertEquals(HttpStatusCode.NotFound, requestAs(path, RECIPIENT, takeover).status)
                val confirmed = confirm(path, FOREIGN, clubClaim)
                assertEquals(1, confirmed["assignments"]!!.jsonArray.size)
                val clubManager = confirmed["assignments"]!!.jsonArray.single().jsonObject
                checkWorkspaces(club, other)
                val update = profileChange(ok(profile, SECOND_MANAGER))
                val updateKey = UUID.randomUUID().toString()
                val saved = ok(profile, SECOND_MANAGER, update, HttpMethod.Patch, updateKey)
                assertEquals("Manager saved", saved.string("name"))
                assertEquals(HttpStatusCode.NotFound, requestAs(path, SECOND_MANAGER).status)
                val pending = invite(path, FOREIGN, "CLUB_MANAGER")
                val unconfirmed = claim(pending, THIRD_MANAGER)
                val staleConfirm = confirmBody(path, FOREIGN, unconfirmed)
                revoke(path, RECIPIENT, networkResult["assignments"]!!.jsonArray.single().jsonObject)
                assertEquals(HttpStatusCode.NotFound, requestAs(path, FOREIGN).status)
                assertEquals("Manager saved", ok(profile, SECOND_MANAGER).string("name"))
                // A source created while the network manager had authority survives its issuer's revocation.
                // That former issuer cannot consume an earlier pending invitation.
                assertEquals(HttpStatusCode.NotFound, requestAs(path, FOREIGN, staleConfirm).status)
                val unclaimed = invite(path, RECIPIENT, "CLUB_MANAGER")
                revoke(path, RECIPIENT, clubManager)
                assertEquals(HttpStatusCode.NotFound, requestAs(profile, SECOND_MANAGER).status)
                assertEquals(
                    HttpStatusCode.NotFound,
                    requestAs(profile, SECOND_MANAGER, update, HttpMethod.Patch, updateKey).status,
                )
                assertTrue(workspace(SECOND_MANAGER)["clubs"]!!.jsonArray.isEmpty())
                assertEquals(
                    HttpStatusCode.NotFound,
                    requestAs(
                        "$C/manager-invitations/${unclaimed.string("createdInvitationId")}/claim",
                        SECOND_MANAGER,
                        obj("token" to unclaimed.string("token"), "expectedVersion" to "1"),
                    ).status,
                )
                assertTrue(pending.string("createdInvitationId") != unclaimed.string("createdInvitationId"))
                assertEquals("2", source.scalar("SELECT COUNT(*) FROM client_role_assignments WHERE status='REVOKED'"))
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM client_club_staff"))
                assertEquals(
                    "0",
                    source.scalar(
                        "SELECT COUNT(*) FROM client_club_grants WHERE user_id IN (SELECT id " +
                            "FROM users WHERE telegram_user_id IN ($FOREIGN,$SECOND_MANAGER))",
                    ),
                )
            }
        }

    @Test
    fun `unreviewed flat grants cannot add or restore rights for a managed subject`() =
        database { source ->
            withApp(source) {
                val (club, _) = business(source)
                val path = "$C/clubs/$club/managers"
                val accepted = confirm(path, RECIPIENT, claim(invite(path, RECIPIENT, "NETWORK_MANAGER"), FOREIGN))
                // Model a historical or out-of-band flat row. TARGET resolves only its accepted sources.
                source.connection.use { connection ->
                    connection
                        .prepareStatement(
                            "INSERT INTO client_club_grants(client_id,club_id,user_id,permission_code," +
                                "is_active,created_at,updated_at) " +
                                "SELECT m.client_id,m.club_id,u.id,?,TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP " +
                                "FROM client_clubs m CROSS JOIN users u WHERE m.club_id=? AND u.telegram_user_id=?",
                        ).use { insert ->
                            for (permission in listOf("client.club.read", "client.club.profile.write")) {
                                insert.setString(1, permission)
                                insert.setLong(2, club.toLong())
                                insert.setLong(3, FOREIGN)
                                assertEquals(1, insert.executeUpdate())
                            }
                        }
                }
                val profile = "$C/clubs/$club/profile"
                val permitted = ok(profile, FOREIGN)
                assertFalse(JsonPrimitive("client.club.profile.write") in permitted.getValue("permissions").jsonArray)
                assertEquals(
                    HttpStatusCode.NotFound,
                    requestAs(profile, FOREIGN, profileChange(permitted), HttpMethod.Patch).status,
                )
                revoke(
                    path,
                    RECIPIENT,
                    accepted
                        .getValue("assignments")
                        .jsonArray
                        .single()
                        .jsonObject,
                )
                assertEquals(HttpStatusCode.NotFound, requestAs(profile, FOREIGN).status)
                assertTrue(workspace(FOREIGN).getValue("clubs").jsonArray.isEmpty())
            }
        }

    @Test
    fun `direct appointment requires a fresh claimant code and a revoked source cannot be restored by replay`() =
        database { source ->
            withApp(source) {
                val (club, _) = business(source)
                val path = "$C/clubs/$club/managers"
                val issued = invite(path, RECIPIENT, "CLUB_MANAGER")
                val claim = claim(issued, SECOND_MANAGER)
                val id = claim.string("id")
                val recipient = "$C/manager-invitations/$id"
                val stale = confirmBody(path, RECIPIENT, claim)
                source.connection.use { connection ->
                    connection
                        .prepareStatement(
                            "UPDATE client_manager_invitations SET claim_valid_until=created_at WHERE id=?",
                        ).use {
                            it.setLong(1, id.toLong())
                            it.executeUpdate()
                        }
                }
                assertEquals(HttpStatusCode.Conflict, requestAs(path, RECIPIENT, stale).status)
                assertEquals(JsonNull, ok(recipient, SECOND_MANAGER)["verificationCode"])
                assertEquals(
                    HttpStatusCode.NotFound,
                    requestAs("$recipient/claim", FOREIGN, obj("expectedVersion" to claim.string("version"))).status,
                )
                val refreshed =
                    ok("$recipient/claim", SECOND_MANAGER, obj("expectedVersion" to claim.string("version")))
                assertTrue(refreshed.string("version").toLong() > claim.string("version").toLong())
                val body = confirmBody(path, RECIPIENT, refreshed)
                val incorrect = JsonObject(body + ("verificationCode" to JsonPrimitive("bad")))
                assertEquals(HttpStatusCode.Conflict, requestAs(path, RECIPIENT, incorrect).status)
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM client_club_access_state"))
                val key = UUID.randomUUID().toString()
                val roster = ok(path, RECIPIENT, body, key = key)
                val assigned =
                    roster
                        .getValue("assignments")
                        .jsonArray
                        .single()
                        .jsonObject
                assertEquals("CLUB_MANAGER", assigned.string("role"))
                assertEquals("accepted", ok(recipient, SECOND_MANAGER).string("status"))
                val profile = "$C/clubs/$club/profile"
                ok(profile, SECOND_MANAGER, profileChange(ok(profile, SECOND_MANAGER)), HttpMethod.Patch)
                revoke(path, RECIPIENT, assigned)
                assertEquals(HttpStatusCode.NotFound, requestAs(profile, SECOND_MANAGER).status)
                assertEquals(HttpStatusCode.NotFound, requestAs(recipient, SECOND_MANAGER).status)
                assertTrue(ok(path, RECIPIENT, body, key = key).getValue("assignments").jsonArray.isEmpty())
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM client_role_assignments WHERE status='ACTIVE'"))
            }
        }

    @Test
    fun `platform revoke fences older invitations across all clubs after restore`() =
        database { source ->
            withApp(source) {
                val (club, other) = business(source)
                val first = "$C/clubs/$club/managers"
                val second = "$C/clubs/$other/managers"
                val direct = invite(first, RECIPIENT, "CLUB_MANAGER")
                confirm(first, RECIPIENT, claim(direct, SECOND_MANAGER))
                val stillUnclaimed = invite(second, RECIPIENT, "NETWORK_MANAGER")
                val clientId = direct.string("clientId")
                val member =
                    ok("$P/clients/$clientId", OWNER)
                        .getValue("members")
                        .jsonArray
                        .map { it.jsonObject }
                        .single { it.string("telegramUserId") == SECOND_MANAGER.toString() }
                val memberPath = "$P/clients/$clientId/members/${member.string("userId")}"
                val revoked = ok("$memberPath/revoke", OWNER, version(member))
                val restored =
                    JsonObject(
                        version(revoked) +
                            mapOf(
                                "permissions" to JsonArray(emptyList()),
                                "clubGrants" to JsonArray(emptyList()),
                            ),
                    )
                val active = ok("$memberPath/restore", OWNER, restored)
                val again = ok("$memberPath/revoke", OWNER, version(active))
                ok("$memberPath/restore", OWNER, JsonObject(restored + version(again)))
                val fences = "FROM client_manager_invitation_fences WHERE client_id=$clientId AND club_id IS NULL"
                assertEquals("1", source.scalar("SELECT COUNT(*) $fences"))
                assertEquals(
                    stillUnclaimed.string("createdInvitationId"),
                    source.scalar("SELECT through_invitation_id $fences"),
                )
                assertEquals(HttpStatusCode.NotFound, requestAs("$C/clubs/$club/profile", SECOND_MANAGER).status)
                assertEquals(
                    HttpStatusCode.NotFound,
                    requestAs(
                        "$C/manager-invitations/resolve",
                        SECOND_MANAGER,
                        obj("token" to stillUnclaimed.string("token")),
                    ).status,
                )
                assertEquals("1", source.scalar("SELECT COUNT(*) FROM client_role_assignments WHERE status='REVOKED'"))
                // A newly issued and explicitly re-confirmed source is independent of the disabled one.
                val newInvite = invite(first, RECIPIENT, "CLUB_MANAGER")
                confirm(first, RECIPIENT, claim(newInvite, SECOND_MANAGER))
                ok("$C/clubs/$club/profile", SECOND_MANAGER)
                assertEquals("1", source.scalar("SELECT COUNT(*) FROM client_role_assignments WHERE status='REVOKED'"))
                assertEquals("1", source.scalar("SELECT COUNT(*) FROM client_role_assignments WHERE status='ACTIVE'"))
            }
        }

    @Test
    fun `network retains its own scope after owner membership revoke and can appoint a guest`() =
        database { source ->
            withApp(source) {
                val (club, other) = business(source)
                val path = "$C/clubs/$club/managers"
                val network = invite(path, RECIPIENT, "NETWORK_MANAGER")
                confirm(path, RECIPIENT, claim(network, FOREIGN))
                val unclaimedOwnerLink = invite(path, RECIPIENT, "CLUB_MANAGER")
                val clientId = network.string("clientId")
                val owner =
                    ok("$P/clients/$clientId", OWNER)
                        .getValue("members")
                        .jsonArray
                        .map { it.jsonObject }
                        .single { it.string("telegramUserId") == RECIPIENT.toString() }
                ok("$P/clients/$clientId/members/${owner.string("userId")}/revoke", OWNER, version(owner))
                assertEquals(HttpStatusCode.NotFound, requestAs(path, RECIPIENT).status)
                assertEquals(HttpStatusCode.NotFound, requestAs("$C/clubs/$other/managers", FOREIGN).status)
                assertEquals(
                    HttpStatusCode.NotFound,
                    requestAs(
                        "$C/manager-invitations/resolve",
                        SECOND_MANAGER,
                        obj(
                            "token" to unclaimedOwnerLink.string("token"),
                        ),
                    ).status,
                )
                val claim = claim(invite(path, FOREIGN, "CLUB_MANAGER"), SECOND_MANAGER)
                // A guest's independent SELF role survives staff appointment and revocation.
                source.connection.use { connection ->
                    connection
                        .prepareStatement(
                            "INSERT INTO user_roles(user_id,role_code,scope_type) " +
                                "SELECT id,'GUEST','GLOBAL' FROM users WHERE telegram_user_id=?",
                        ).use {
                            it.setLong(1, SECOND_MANAGER)
                            assertEquals(1, it.executeUpdate())
                        }
                }
                val roster = confirm(path, FOREIGN, claim)
                val profile = "$C/clubs/$club/profile"
                ok(profile, SECOND_MANAGER, profileChange(ok(profile, SECOND_MANAGER)), HttpMethod.Patch)
                revoke(
                    path,
                    FOREIGN,
                    roster
                        .getValue("assignments")
                        .jsonArray
                        .single()
                        .jsonObject,
                )
                assertEquals(HttpStatusCode.NotFound, requestAs(profile, SECOND_MANAGER).status)
                assertEquals(
                    "1",
                    source.scalar(
                        "SELECT COUNT(*) FROM user_roles r JOIN users u ON u.id=r.user_id " +
                            "WHERE u.telegram_user_id=$SECOND_MANAGER AND " +
                            "r.role_code='GUEST' AND r.scope_type='GLOBAL'",
                    ),
                )
            }
        }

    @Test
    fun `review dual manager sources advertise only actions the current actor may perform`() =
        database { source ->
            withApp(source) {
                val (club, _) = business(source)
                val path = "$C/clubs/$club/managers"
                confirm(path, RECIPIENT, claim(invite(path, RECIPIENT, "NETWORK_MANAGER"), FOREIGN))
                confirm(path, RECIPIENT, claim(invite(path, RECIPIENT, "CLUB_MANAGER"), FOREIGN))
                val network = ok(path, FOREIGN)
                val own =
                    network
                        .getValue("assignments")
                        .jsonArray
                        .single()
                        .jsonObject
                val attempted =
                    revokeSource(network, own, "revoke_assignment")
                assertEquals(HttpStatusCode.Forbidden, requestAs(path, FOREIGN, attempted).status)
                assertEquals(JsonPrimitive(false), own["canRevoke"])
                val owner = ok(path, RECIPIENT)
                assertTrue(
                    owner.getValue("assignments").jsonArray.all {
                        it.jsonObject["canRevoke"] ==
                            JsonPrimitive(true)
                    },
                )
                assertEquals("2", source.scalar("SELECT COUNT(*) FROM client_role_assignments WHERE status='ACTIVE'"))
                ok(
                    "$C/clubs/$club/profile",
                    FOREIGN,
                    profileChange(ok("$C/clubs/$club/profile", FOREIGN)),
                    HttpMethod.Patch,
                )
            }
        }

    @Test
    fun `concurrent confirmation and cancellation of a claimed appointment commit one outcome`() =
        database { source ->
            withApp(source) {
                val (club, _) = business(source)
                val path = "$C/clubs/$club/managers"
                val invitation = invite(path, RECIPIENT, "CLUB_MANAGER")
                val claimed = claim(invitation, FOREIGN)
                val approval = confirmBody(path, RECIPIENT, claimed)
                val cancel =
                    JsonObject(
                        (approval - "verificationCode") + ("action" to JsonPrimitive("revoke_invitation")),
                    )
                val outcomes =
                    coroutineScope {
                        listOf(approval, cancel)
                            .map { decision ->
                                async { requestAs(path, RECIPIENT, decision).status }
                            }.awaitAll()
                    }
                assertEquals(setOf(HttpStatusCode.OK, HttpStatusCode.Conflict), outcomes.toSet())
                val assigned = outcomes.first() == HttpStatusCode.OK
                val roster = ok(path, RECIPIENT).getValue("assignments").jsonArray
                assertEquals(if (assigned) 1 else 0, roster.size)
                assertEquals(
                    if (assigned) "accepted" else "revoked",
                    source.scalar("SELECT status FROM client_manager_invitations WHERE id=${claimed.string("id")}"),
                )
                assertEquals(
                    if (assigned) HttpStatusCode.OK else HttpStatusCode.NotFound,
                    requestAs("$C/clubs/$club/profile", FOREIGN).status,
                )
            }
        }

    private suspend fun ApplicationTestBuilder.workspace(user: Long): JsonObject =
        ok("$C/context", user)["clients"]!!.jsonArray.single().jsonObject

    private suspend fun ApplicationTestBuilder.checkWorkspaces(
        club: String,
        other: String,
    ) {
        fun JsonObject.clubIds(): Set<String> = getValue("clubs").jsonArray.map { it.jsonObject.string("id") }.toSet()

        val ownerContext = workspace(RECIPIENT)
        assertEquals(setOf(club, other), ownerContext.clubIds())
        val clientId = ownerContext.string("id")
        val path = "$C/clubs/$club"
        val settings = "$path/booking-rules"
        val onboarding = "$C/clients/$clientId/onboarding"

        suspend fun profile(user: Long) = ok("$path/profile", user)
        val ownerProfile = profile(RECIPIENT)
        assertEquals("BUSINESS_OWNER", ownerProfile.string("businessRole"))
        val ownerPermissions = ownerProfile.getValue("permissions").jsonArray
        assertTrue(JsonPrimitive("client.club.reports.read") in ownerPermissions)
        assertTrue(JsonPrimitive("client.staff.assign.club_manager") in ownerPermissions)
        assertEquals("true", ok(onboarding, RECIPIENT).string("businessOwner"))
        assertEquals("false", ok(settings, RECIPIENT).string("canWrite"))
        assertEquals("NETWORK_MANAGER", profile(FOREIGN).string("businessRole"))
        assertEquals("false", ok(settings, FOREIGN).string("canWrite"))
        assertEquals("CLUB_MANAGER", profile(SECOND_MANAGER).string("businessRole"))
        assertEquals("true", ok(settings, SECOND_MANAGER).string("canWrite"))
        for (manager in listOf(FOREIGN, SECOND_MANAGER)) {
            assertEquals(setOf(club), workspace(manager).clubIds())
            val view = ok(onboarding, manager)
            assertEquals(setOf(club), view.clubIds())
            assertEquals("false", view.string("businessOwner"))
            assertEquals("false", view.string("canCreate"))
            assertEquals("false", view.string("showClubLimit"))
            assertFalse(view.containsKey("allowedClubs"))
            assertEquals(HttpStatusCode.NotFound, requestAs("$C/clubs/$other", manager).status)
            val stale = signed(manager, Instant.now().minusSeconds(301))
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.get(settings) { header("X-Telegram-Init-Data", stale) }.status,
            )
        }
    }

    private suspend fun ApplicationTestBuilder.revoke(
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
