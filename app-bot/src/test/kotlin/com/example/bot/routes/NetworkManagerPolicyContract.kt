package com.example.bot.routes

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.HexFormat
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse

abstract class NetworkManagerPolicyContract : LegacyStaffAdoptionContract() {
    @Test
    fun `V083 amends only previously accepted live network sources and preserves invitation proofs`() =
        databaseAt("81") { source ->
            withApp(source) {
                val (club, other) = business(source)
                val path = "$C/clubs/$club/managers"
                val profile = "$C/clubs/$club/profile"
                val active = historicalNetworkInvitation(source, club)
                val roster = confirm(path, RECIPIENT, claim(active, FOREIGN))
                val assignment =
                    roster
                        .getValue("assignments")
                        .jsonArray
                        .single()
                        .jsonObject
                val pending = historicalNetworkInvitation(source, club)
                val pendingClaim = claim(pending, SECOND_MANAGER)
                val sourceId = assignment.string("id")
                val pendingId = pendingClaim.string("id")
                val permission = JsonPrimitive("client.club.profile.write")
                assertFalse(permission in ok(profile, FOREIGN).getValue("permissions").jsonArray)

                upgradeClubFixture(source)
                assertEquals(
                    OLD_POLICY_HASH,
                    source.scalar("SELECT policy_hash FROM client_role_assignments WHERE id='$sourceId'"),
                )
                assertEquals(pendingClaim, ok("$C/manager-invitations/$pendingId", SECOND_MANAGER))
                val live = ok(profile, FOREIGN)
                ok(profile, FOREIGN, profileChange(live), HttpMethod.Patch)
                assertEquals(HttpStatusCode.NotFound, requestAs("$C/clubs/$other/profile", FOREIGN).status)
                val audits = source.scalar("SELECT COUNT(*) FROM audit_log WHERE action='client.manager.policy.v2'")
                assertEquals("1", audits)
                assertEquals("1", source.scalar("SELECT COUNT(*) FROM client_role_assignments"))
                assertEquals(
                    "2",
                    source.scalar(
                        "SELECT COUNT(*) FROM client_manager_invitations WHERE policy_hash='$OLD_POLICY_HASH' " +
                            "AND policy_version=1 AND acceptance_policy='owner_confirmation' " +
                            "AND parent_invitation_id IS NULL",
                    ),
                )

                confirm(path, RECIPIENT, pendingClaim)
                val late = ok(profile, SECOND_MANAGER)
                assertEquals("NETWORK_MANAGER", late.string("businessRole"))
                assertFalse(permission in late.getValue("permissions").jsonArray)
                ok(path, SECOND_MANAGER)
                ok(path, RECIPIENT, revokeSource(ok(path, RECIPIENT), assignment, "revoke_assignment"))
                assertEquals(HttpStatusCode.NotFound, requestAs(profile, FOREIGN).status)
            }
        }

    private fun historicalNetworkInvitation(
        source: DataSource,
        club: String,
    ): JsonObject {
        val client = source.scalar("SELECT client_id FROM client_business_clubs WHERE club_id=$club")
        val owner = source.scalar("SELECT user_id FROM client_business_owners WHERE client_id=$client")
        val version = source.scalar("SELECT member_version FROM client_business_owners WHERE client_id=$client")
        val token = "historical-" + UUID.randomUUID().toString().replace("-", "")
        val tokenHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.toByteArray()))
        val created = OffsetDateTime.now()
        source.connection.use { connection ->
            connection
                .prepareStatement(
                    "INSERT INTO client_manager_invitations(client_id,club_id,role_code,policy_version,policy_hash," +
                        "inviter_user_id,issuer_ref,recipient_label,token_hash,created_at,expires_at) " +
                        "VALUES (?,?,'NETWORK_MANAGER',1,?,?,?,'V081 manager',?,?,?)",
                ).use {
                    it.setLong(1, client.toLong())
                    it.setLong(2, club.toLong())
                    it.setString(3, OLD_POLICY_HASH)
                    it.setLong(4, owner.toLong())
                    it.setString(5, "business:$client:$club:$owner:$version")
                    it.setString(6, tokenHash)
                    it.setObject(7, created)
                    it.setObject(8, created.plusDays(7))
                    assertEquals(1, it.executeUpdate())
                }
        }
        return obj("token" to token)
    }

    private companion object {
        // Frozen V081 fingerprint. The fixture must not derive it from today's catalog.
        const val OLD_POLICY_HASH = "8696dee290220fc8c929ad4d9ca4645628628aa550adfb6ca1e53a9f06b307d1"
    }
}
