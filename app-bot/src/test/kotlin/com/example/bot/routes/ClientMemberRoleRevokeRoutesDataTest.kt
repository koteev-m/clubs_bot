package com.example.bot.routes

import com.example.bot.routes.ClientMemberRoleRevokeRouteFixture.Companion.CLIENT
import com.example.bot.routes.ClientMemberRoleRevokeRouteFixture.Companion.CLUB
import com.example.bot.routes.ClientMemberRoleRevokeRouteFixture.Companion.DEPENDENT
import com.example.bot.routes.ClientMemberRoleRevokeRouteFixture.Companion.DEPENDENT_FLOOR
import com.example.bot.routes.ClientMemberRoleRevokeRouteFixture.Companion.INCOMING
import com.example.bot.routes.ClientMemberRoleRevokeRouteFixture.Companion.INDEPENDENT
import com.example.bot.routes.ClientMemberRoleRevokeRouteFixture.Companion.ISSUER_MANAGER
import com.example.bot.routes.ClientMemberRoleRevokeRouteFixture.Companion.OUTGOING
import com.example.bot.routes.ClientMemberRoleRevokeRouteFixture.Companion.SECOND_CLUB
import com.example.bot.routes.ClientMemberRoleRevokeRouteFixture.Companion.TARGET
import com.example.bot.webapp.TEST_BOT_TOKEN
import com.example.bot.webapp.WebAppInitDataTestHelper
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Existing mounted member endpoints; the new assignment evaluator remains unmounted. */
class ClientMemberRoleRevokeRoutesDataTest : ClientOnboardingRoutesTestBase() {
    @Test
    fun `platform revoke requires current canonical owner signature before touching role controlled data`() =
        database { source ->
            val fixture = ClientMemberRoleRevokeRouteFixture(source, OWNER, RECIPIENT)
            withApp(source) {
                val before = fixture.snapshot()
                for (credential in listOf(
                    null,
                    "forged",
                    credential(date = Instant.now().epochSecond - 90_000),
                    credential(token = "222222:OTHER_SYNTHETIC_BOT"),
                )) {
                    client
                        .post(REVOKE_PATH) {
                            if (credential != null) header("X-Telegram-Init-Data", credential)
                            header("X-Telegram-Id", OWNER.toString())
                            header("X-Debug-Roles", "OWNER,GLOBAL_ADMIN")
                            header("Content-Type", "application/json")
                            header("Idempotency-Key", "synthetic-denied-revoke")
                            setBody(obj("expectedVersion" to "1").toString())
                        }.assertClientError(HttpStatusCode.Unauthorized, "unauthorized")
                    assertEquals(before, fixture.snapshot())
                }
                requestAs(REVOKE_PATH, FOREIGN, obj("expectedVersion" to "1"))
                    .assertClientError(HttpStatusCode.Forbidden, "forbidden")
                assertEquals(before, fixture.snapshot())
            }
        }

    @Test
    fun `signed platform revoke covers both clubs and delegation tail while restore leaves sources terminal`() =
        database { source ->
            val fixture = ClientMemberRoleRevokeRouteFixture(source, OWNER, RECIPIENT)
            val unrelated = fixture.unrelatedClientSnapshot()
            val independent =
                fixture.rows(
                    "SELECT * FROM client_permission_delegations WHERE id='$INDEPENDENT'",
                )
            val issuer = fixture.rows("SELECT * FROM client_role_assignments WHERE id='$ISSUER_MANAGER'")
            val dependent = fixture.rows("SELECT * FROM client_role_assignments WHERE id='$DEPENDENT_FLOOR'")
            lateinit var revoked: JsonObject
            withApp(source) {
                val body = obj("expectedVersion" to "1")
                revoked = ok(REVOKE_PATH, OWNER, body, key = REVOKE_KEY)
                assertEquals(JsonPrimitive(false), revoked["active"])
                assertEquals("2", revoked.string("version"))
                assertEquals(
                    listOf(
                        listOf(CLUB.toString(), "DISABLED", "6", "9"),
                        listOf(SECOND_CLUB.toString(), "DISABLED", "8", "10"),
                    ),
                    fixture.rows(
                        "SELECT club_id,status,admission_epoch,version FROM client_club_access_state " +
                            "WHERE client_id=$CLIENT AND user_id=$TARGET ORDER BY club_id",
                    ),
                )
                assertEquals(
                    "3",
                    source.scalar(
                        "SELECT COUNT(*) FROM client_role_assignments WHERE client_id=$CLIENT " +
                            "AND user_id=$TARGET AND status='REVOKED' AND version=2",
                    ),
                )
                assertEquals(
                    listOf(listOf("REVOKED", "2"), listOf("REVOKED", "2")),
                    fixture.rows(
                        "SELECT status,version FROM client_permission_delegations " +
                            "WHERE id IN ('$INCOMING','$OUTGOING') ORDER BY id",
                    ),
                )
                assertEquals(
                    listOf(listOf("ACTIVE", "1", "4")),
                    fixture.rows(
                        "SELECT status,admission_epoch,version FROM client_club_access_state " +
                            "WHERE client_id=$CLIENT AND club_id=$CLUB AND user_id=$DEPENDENT",
                    ),
                )
                assertEquals(
                    "0",
                    source.scalar("SELECT COUNT(*) FROM client_club_grants WHERE client_id=$CLIENT AND is_active=TRUE"),
                )
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM client_membership_grants WHERE is_active=TRUE"))
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM client_club_staff WHERE is_active=TRUE"))
                assertEquals(unrelated, fixture.unrelatedClientSnapshot())
                assertEquals(
                    independent,
                    fixture.rows("SELECT * FROM client_permission_delegations WHERE id='$INDEPENDENT'"),
                )
                assertEquals(issuer, fixture.rows("SELECT * FROM client_role_assignments WHERE id='$ISSUER_MANAGER'"))
                assertEquals(
                    dependent,
                    fixture.rows("SELECT * FROM client_role_assignments WHERE id='$DEPENDENT_FLOOR'"),
                )
                requestAs("$C/clients/$CLIENT/onboarding", RECIPIENT)
                    .assertClientError(HttpStatusCode.NotFound, "not_found")
                val afterRevoke = fixture.snapshot()
                assertEquals(revoked, ok(REVOKE_PATH, OWNER, body, key = REVOKE_KEY))
                assertEquals(afterRevoke, fixture.snapshot())
                requestAs(REVOKE_PATH, OWNER, body)
                    .assertClientError(HttpStatusCode.Conflict, "version_conflict")
                assertEquals(afterRevoke, fixture.snapshot())
            }
            // A fresh Ktor/repository instance must use persisted current state and the same receipt.
            withApp(source) {
                val beforeReplay = fixture.snapshot()
                assertEquals(revoked, ok(REVOKE_PATH, OWNER, obj("expectedVersion" to "1"), key = REVOKE_KEY))
                assertEquals(beforeReplay, fixture.snapshot())
                val sources = fixture.foundationSnapshot()
                val restored = ok(RESTORE_PATH, OWNER, emptyRestore(revoked.string("version")))
                assertEquals(JsonPrimitive(true), restored["active"])
                assertEquals("3", restored.string("version"))
                assertEquals(sources, fixture.foundationSnapshot())
                assertEquals(unrelated, fixture.unrelatedClientSnapshot())
                assertEquals(JsonArray(emptyList()), ok("$C/clients/$CLIENT/onboarding", RECIPIENT)["permissions"])
                requestAs("$C/clubs/$SECOND_CLUB/profile", RECIPIENT)
                    .assertClientError(HttpStatusCode.NotFound, "not_found")
                assertEquals(
                    "0",
                    source.scalar(
                        "SELECT COUNT(*) FROM client_club_grants WHERE client_id=$CLIENT " +
                            "AND user_id=$TARGET AND is_active=TRUE",
                    ),
                )
            }
        }

    @Test
    fun `restore cannot revive a legacy grant in a controlled club without any legacy staff row`() =
        database { source ->
            val fixture = ClientMemberRoleRevokeRouteFixture(source, OWNER, RECIPIENT)
            withApp(source) {
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM client_club_staff WHERE club_id=$SECOND_CLUB"))
                val revoked = ok(REVOKE_PATH, OWNER, obj("expectedVersion" to "1"))
                val body =
                    JsonObject(
                        emptyRestore(revoked.string("version")) +
                            (
                                "clubGrants" to
                                    JsonArray(
                                        listOf(
                                            obj(
                                                "clubId" to SECOND_CLUB.toString(),
                                                "permission" to "client.club.read",
                                            ),
                                        ),
                                    )
                            ),
                    )
                val before = fixture.snapshot()
                requestAs(RESTORE_PATH, OWNER, body).assertClientError(HttpStatusCode.Conflict, "invalid_transition")
                assertEquals(before, fixture.snapshot())
                assertEquals(JsonPrimitive(true), ok(RESTORE_PATH, OWNER, emptyRestore("2"))["active"])
                assertEquals(
                    "0",
                    source.scalar(
                        "SELECT COUNT(*) FROM client_role_assignments WHERE client_id=$CLIENT " +
                            "AND user_id=$TARGET AND status='ACTIVE'",
                    ),
                )
            }
        }

    @Test
    fun `legacy only member lifecycle keeps explicit old scope restore before and after V077`() {
        for (target in listOf("76", null)) {
            databaseAt(target) { source ->
                ClientMemberRoleRevokeRouteFixture(source, OWNER, RECIPIENT, foundation = false)
                withApp(source) {
                    val revoked = ok(REVOKE_PATH, OWNER, obj("expectedVersion" to "1"))
                    assertEquals(JsonPrimitive(false), revoked["active"])
                    val restored =
                        ok(
                            RESTORE_PATH,
                            OWNER,
                            JsonObject(
                                obj("expectedVersion" to revoked.string("version")) +
                                    mapOf(
                                        "permissions" to JsonArray(listOf(JsonPrimitive("client.application.manage"))),
                                        "clubGrants" to
                                            JsonArray(
                                                listOf(
                                                    obj(
                                                        "clubId" to SECOND_CLUB.toString(),
                                                        "permission" to "client.club.read",
                                                    ),
                                                ),
                                            ),
                                    ),
                            ),
                        )
                    assertEquals(JsonPrimitive(true), restored["active"])
                    assertEquals("3", restored.string("version"))
                    assertEquals(
                        "1",
                        source.scalar("SELECT COUNT(*) FROM client_membership_grants WHERE is_active=TRUE"),
                    )
                    assertEquals(
                        "1",
                        source.scalar(
                            "SELECT COUNT(*) FROM client_club_grants WHERE client_id=$CLIENT " +
                                "AND club_id=$SECOND_CLUB AND user_id=$TARGET AND is_active=TRUE",
                        ),
                    )
                    assertFalse(
                        source.scalar("SELECT is_active FROM client_club_staff WHERE club_id=$CLUB").toBoolean(),
                    )
                    assertTrue(
                        source
                            .scalar(
                                "SELECT is_active FROM client_memberships WHERE client_id=$CLIENT AND user_id=$TARGET",
                            ).toBoolean(),
                    )
                }
            }
        }
    }

    private fun emptyRestore(version: String): JsonObject =
        JsonObject(
            obj("expectedVersion" to version) +
                mapOf("permissions" to JsonArray(emptyList()), "clubGrants" to JsonArray(emptyList())),
        )

    private fun credential(
        date: Long = Instant.now().epochSecond,
        token: String = TEST_BOT_TOKEN,
    ): String =
        WebAppInitDataTestHelper.createInitData(
            token,
            mapOf(
                "user" to WebAppInitDataTestHelper.encodeUser(OWNER),
                "auth_date" to date.toString(),
                "start_param" to "ci_" + "s".repeat(43),
            ),
        )

    private companion object {
        const val REVOKE_KEY = "synthetic-platform-role-revoke"
        const val REVOKE_PATH = "/api/platform/onboarding/clients/$CLIENT/members/$TARGET/revoke"
        const val RESTORE_PATH = "/api/platform/onboarding/clients/$CLIENT/members/$TARGET/restore"
    }
}
