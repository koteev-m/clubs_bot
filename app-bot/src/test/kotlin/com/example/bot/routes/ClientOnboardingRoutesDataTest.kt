package com.example.bot.routes

import com.example.bot.data.client.JdbcClientContextRepository
import com.example.bot.data.client.onboarding.JdbcClientOnboardingRepository
import com.example.bot.plugins.installJsonErrorPages
import com.example.bot.webapp.TEST_BOT_TOKEN
import com.example.bot.webapp.WebAppInitDataTestHelper
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.flywaydb.core.Flyway
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientOnboardingRoutesDataTest {
    @Test
    fun `direct Mini App recipient proceeds from approved application to one persisted private club profile`() =
        database { source ->
            val initialRoles = source.scalar("SELECT COUNT(*) FROM user_roles")
            val initialClubs = source.scalar("SELECT COUNT(*) FROM clubs")
            lateinit var applicationId: String
            lateinit var clubId: String
            withApp(source) {
                val invitation =
                    ok(
                        P + "/invitations",
                        OWNER,
                        obj(
                            "clientName" to "Synthetic workspace",
                            "recipientLabel" to "Synthetic recipient",
                        ),
                    )
                val invitationId = invitation.string("id")
                val token = invitation.string("token")
                val clientId = invitation.string("clientId")
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM users WHERE telegram_user_id=$RECIPIENT"))
                assertEquals(JsonArray(emptyList()), ok(C + "/context", RECIPIENT)["clients"])
                ok(C + "/invitations/resolve", RECIPIENT, obj("token" to token))
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM users WHERE telegram_user_id=$RECIPIENT"))
                val claimed =
                    ok(
                        C + "/invitations/$invitationId/claim",
                        RECIPIENT,
                        obj(
                            "token" to token,
                            "expectedVersion" to invitation.string("version"),
                        ),
                    )
                assertEquals("claimed", claimed.string("status"))
                assertTrue(claimed.string("verificationCode").matches(Regex("[0-9]{6}")))
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM client_memberships"))
                requestAs(
                    C + "/invitations/$invitationId/claim",
                    FOREIGN,
                    obj(
                        "token" to token,
                        "expectedVersion" to claimed.string("version"),
                    ),
                ).assertClientError(HttpStatusCode.NotFound, "not_found")
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM users WHERE telegram_user_id=$FOREIGN"))
                val accepted =
                    ok(P + "/invitations/$invitationId/confirm", OWNER, version(claimed), key = "confirm-lost-response")
                val auditCount = source.scalar("SELECT COUNT(*) FROM audit_log")
                val repeated =
                    ok(P + "/invitations/$invitationId/confirm", OWNER, version(claimed), key = "confirm-lost-response")
                assertEquals(accepted, repeated)
                assertEquals(auditCount, source.scalar("SELECT COUNT(*) FROM audit_log"))
                assertEquals("accepted", accepted.string("status"))
                val context = ok(C + "/context", RECIPIENT)["clients"]!!.jsonArray
                assertEquals(1, context.size)
                assertEquals(JsonArray(emptyList()), context.single().jsonObject["clubs"])
                assertEquals(initialRoles, source.scalar("SELECT COUNT(*) FROM user_roles"))
                var draft = ok(C + "/clients/$clientId/applications", RECIPIENT, venue())
                applicationId = draft.string("id")
                requestAs(C + "/applications/$applicationId/club", RECIPIENT, version(draft))
                    .assertClientError(HttpStatusCode.Conflict, "invalid_transition")
                requestAs(
                    P + "/applications/$applicationId",
                    OWNER,
                ).assertClientError(HttpStatusCode.NotFound, "not_found")
                draft =
                    ok(
                        C + "/applications/$applicationId",
                        RECIPIENT,
                        JsonObject(
                            version(draft) + obj("comment" to "Saved synthetic draft"),
                        ),
                        HttpMethod.Patch,
                    )
                val submitted = ok(C + "/applications/$applicationId/submit", RECIPIENT, version(draft))
                requestAs(
                    C + "/applications/$applicationId",
                    RECIPIENT,
                    JsonObject(
                        version(submitted) + obj("venueName" to "Changed"),
                    ),
                    HttpMethod.Patch,
                ).assertClientError(HttpStatusCode.Conflict, "invalid_transition")
                requestAs(P + "/applications/$applicationId/approve", RECIPIENT, version(submitted))
                    .assertClientError(HttpStatusCode.Forbidden, "forbidden")
                val reviewed = ok(P + "/applications/$applicationId/review", OWNER, version(submitted))
                val approval =
                    JsonObject(
                        version(reviewed) +
                            obj(
                                "commercialReference" to "SYNTHETIC-A-001",
                                "commercialAgreedAt" to "2026-01-01",
                                "internalNote" to "PLATFORM_ONLY_NOTE",
                            ) +
                            ("platformConfirmed" to JsonPrimitive(true)),
                    )
                val approved =
                    ok(P + "/applications/$applicationId/approve", OWNER, approval, key = "approve-lost-response")
                assertEquals("approved", approved.string("status"))
                val publicView = ok(C + "/applications/$applicationId", RECIPIENT)
                assertEquals("approved", publicView.string("status"))
                assertFalse(publicView.toString().contains("PLATFORM_ONLY_NOTE"))
                assertFalse(publicView.containsKey("commercialReference"))
                assertEquals(
                    listOf("draft", "submitted", "reviewing", "approved"),
                    publicView["history"]!!.jsonArray.map {
                        it.jsonObject.string("status")
                    },
                )
                requestAs(
                    C + "/applications/$applicationId",
                    FOREIGN,
                ).assertClientError(HttpStatusCode.NotFound, "not_found")
                assertEquals(initialClubs, source.scalar("SELECT COUNT(*) FROM clubs"))
                requestAs(C + "/applications/$applicationId/club", FOREIGN, version(approved))
                    .assertClientError(HttpStatusCode.NotFound, "not_found")
                requestAs(
                    C + "/applications/$applicationId/club",
                    RECIPIENT,
                    JsonObject(version(approved) + obj("clientId" to "1", "ownerId" to "1")),
                ).assertClientError(HttpStatusCode.BadRequest, "validation_error")
                val profile =
                    ok(
                        C + "/applications/$applicationId/club",
                        RECIPIENT,
                        version(approved),
                        key = "create-club-lost-response",
                    )
                clubId = profile.string("id")
                val repeat =
                    ok(
                        C + "/applications/$applicationId/club",
                        RECIPIENT,
                        version(approved),
                        key = "create-club-lost-response",
                    )
                assertEquals(profile, repeat)
                assertEquals(profile, ok(C + "/applications/$applicationId/club", RECIPIENT, version(approved)))
                assertEquals("FALSE", source.scalar("SELECT is_active FROM clubs WHERE id=$clubId"))
                assertEquals("1", source.scalar("SELECT COUNT(*) FROM client_club_onboarding"))
                assertEquals("Synthetic Street 1", profile.string("address"))
                requestAs(C + "/clubs/$clubId/profile", FOREIGN).assertClientError(HttpStatusCode.NotFound, "not_found")
                requestAs(
                    C + "/clubs/9223372036854775807/profile",
                    FOREIGN,
                ).assertClientError(HttpStatusCode.NotFound, "not_found")
                requestAs(
                    C + "/clubs/$clubId/profile",
                    RECIPIENT,
                    JsonObject(version(profile) + obj("address" to "Forged address")),
                    HttpMethod.Patch,
                ).assertClientError(HttpStatusCode.BadRequest, "validation_error")
                val update =
                    JsonObject(
                        version(profile) +
                            obj(
                                "name" to "Saved club",
                                "description" to "Saved description",
                                "timezone" to "Europe/Paris",
                            ),
                    )
                val saved =
                    ok(C + "/clubs/$clubId/profile", RECIPIENT, update, HttpMethod.Patch, "save-club-lost-response")
                assertEquals("2", saved.string("version"))
                assertEquals(
                    saved,
                    ok(C + "/clubs/$clubId/profile", RECIPIENT, update, HttpMethod.Patch, "save-club-lost-response"),
                )
                requestAs(C + "/clubs/$clubId/profile", RECIPIENT, update, HttpMethod.Patch)
                    .assertClientError(HttpStatusCode.Conflict, "version_conflict")
                val consumed = ok(C + "/applications/$applicationId", RECIPIENT)
                assertEquals(profile["id"], consumed["createdClubId"])
                assertEquals("Synthetic Venue", consumed.string("venueName"))
                requestAs(
                    P + "/applications/$applicationId/cancel",
                    OWNER,
                    JsonObject(version(consumed) + obj("reason" to "Cannot cancel consumed approval")),
                ).assertClientError(HttpStatusCode.Conflict, "invalid_transition")
            }
            // A new Ktor application/repository instance uses the same persisted result.
            withApp(source) {
                val restored = ok(C + "/applications/$applicationId", RECIPIENT)
                assertEquals("approved", restored.string("status"))
                assertEquals("Saved synthetic draft", restored.string("comment"))
                val profile = ok(C + "/clubs/$clubId/profile", RECIPIENT)
                assertEquals("Saved club", profile.string("name"))
                assertEquals("Saved description", profile.string("description"))
                assertEquals("Europe/Paris", profile.string("timezone"))
                assertEquals("Synthetic Street 1", profile.string("address"))
                val clubs = ok(C + "/clients/${restored.string("clientId")}/onboarding", RECIPIENT)["clubs"]!!.jsonArray
                assertEquals(listOf(clubId), clubs.map { it.jsonObject.string("id") })
            }
        }

    @Test
    fun `revoked membership and exact grant prevent replay and restoration never grants all scopes`() =
        database { source ->
            withApp(source) {
                val invitation =
                    ok(
                        P + "/invitations",
                        OWNER,
                        obj(
                            "clientName" to "Synthetic access",
                            "recipientLabel" to "Recipient",
                        ),
                    )
                val id = invitation.string("id")
                val clientId = invitation.string("clientId")
                val claim =
                    ok(
                        C + "/invitations/$id/claim",
                        RECIPIENT,
                        obj(
                            "token" to invitation.string("token"),
                            "expectedVersion" to invitation.string("version"),
                        ),
                    )
                ok(P + "/invitations/$id/confirm", OWNER, version(claim))
                val member = ok(P + "/clients/$clientId", OWNER)["members"]!!.jsonArray.single().jsonObject
                val memberId = member.string("userId")
                val draft = ok(C + "/clients/$clientId/applications", RECIPIENT, venue(), key = "create-before-revoke")
                val revoked = ok(P + "/clients/$clientId/members/$memberId/revoke", OWNER, version(member))
                requestAs(C + "/clients/$clientId/applications", RECIPIENT, venue(), key = "create-before-revoke")
                    .assertClientError(HttpStatusCode.NotFound, "not_found")
                requestAs(C + "/invitations/$id", RECIPIENT).assertClientError(HttpStatusCode.NotFound, "not_found")
                val restored =
                    ok(
                        P + "/clients/$clientId/members/$memberId/restore",
                        OWNER,
                        JsonObject(
                            version(revoked) +
                                mapOf("permissions" to JsonArray(emptyList()), "clubGrants" to JsonArray(emptyList())),
                        ),
                    )
                assertEquals(JsonPrimitive(true), restored["active"])
                assertEquals(1, ok(C + "/context", RECIPIENT)["clients"]!!.jsonArray.size)
                assertEquals(JsonArray(emptyList()), ok(C + "/clients/$clientId/onboarding", RECIPIENT)["permissions"])
                requestAs(
                    C + "/applications/${draft.string("id")}",
                    RECIPIENT,
                ).assertClientError(HttpStatusCode.NotFound, "not_found")
                assertEquals("1", source.scalar("SELECT COUNT(*) FROM client_club_applications"))
            }
        }

    @Test
    fun `issued link opens recipient confirmation and lost response replay safely reissues for the same client`() =
        database { source ->
            withApp(source) {
                val body = obj("clientName" to "MIX Group", "recipientLabel" to "Иван Петров")
                val before = Instant.now()
                val issued = ok(P + "/invitations", OWNER, body, key = "lost-link-response")
                val link = URI(issued.string("invitationLink"))
                assertEquals("https", link.scheme)
                assertEquals("t.me", link.host)
                assertEquals("/synthetic_bot", link.path)
                assertTrue(link.rawQuery.matches(Regex("startapp=ci_[A-Za-z0-9_-]{43}")))
                val token = link.rawQuery.removePrefix("startapp=ci_")
                assertEquals(issued.string("token"), token)
                val expiresAt = Instant.parse(issued.string("expiresAt"))
                assertTrue(!expiresAt.isBefore(before.plus(Duration.ofDays(7))))
                assertTrue(!expiresAt.isAfter(Instant.now().plus(Duration.ofDays(7))))
                val replay = ok(P + "/invitations", OWNER, body, key = "lost-link-response")
                assertEquals(issued["id"], replay["id"])
                assertFalse(replay.containsKey("token"))
                assertFalse(replay.containsKey("invitationLink"))
                assertEquals("1", source.scalar("SELECT COUNT(*) FROM clients"))
                assertEquals("1", source.scalar("SELECT COUNT(*) FROM client_invitations"))
                assertEquals("1", source.scalar("SELECT COUNT(*) FROM client_commands"))
                val id = issued.string("id")
                val existing = ok(P + "/invitations/$id", OWNER)
                assertFalse(existing.containsKey("token"))
                val replacement =
                    ok(P + "/invitations/$id/reissue", OWNER, version(existing), key = "lost-reissue-response")
                val reissueReplay =
                    ok(P + "/invitations/$id/reissue", OWNER, version(existing), key = "lost-reissue-response")
                assertEquals(replacement["id"], reissueReplay["id"])
                assertEquals(issued["clientId"], replacement["clientId"])
                assertFalse(reissueReplay.containsKey("token"))
                assertFalse(reissueReplay.containsKey("invitationLink"))
                assertEquals("1", source.scalar("SELECT COUNT(*) FROM clients"))
                assertEquals("2", source.scalar("SELECT COUNT(*) FROM client_invitations"))
                requestAs(
                    C + "/invitations/$id/claim",
                    RECIPIENT,
                    obj("token" to token, "expectedVersion" to issued.string("version")),
                ).assertClientError(HttpStatusCode.NotFound, "not_found")
                val newToken = URI(replacement.string("invitationLink")).rawQuery.removePrefix("startapp=ci_")
                val preview = ok(C + "/invitations/resolve", RECIPIENT, obj("token" to newToken))
                assertEquals(replacement["id"], preview["id"])
                val claim =
                    ok(
                        C + "/invitations/${preview.string("id")}/claim",
                        RECIPIENT,
                        obj("token" to newToken, "expectedVersion" to preview.string("version")),
                    )
                assertEquals("claimed", claim.string("status"))
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM client_memberships"))
                val accepted = ok(P + "/invitations/${claim.string("id")}/confirm", OWNER, version(claim))
                assertEquals("accepted", accepted.string("status"))
                assertEquals("1", source.scalar("SELECT COUNT(*) FROM client_memberships"))
            }
        }

    @Test
    fun `invalid forwarding and reissue cannot create users or replay a secret`() =
        database { source ->
            withApp(source) {
                val issueBody = obj("clientName" to "Synthetic reissue", "recipientLabel" to "Recipient")
                val invitation = ok(P + "/invitations", OWNER, issueBody, key = "issue-response-lost")
                val replay = ok(P + "/invitations", OWNER, issueBody, key = "issue-response-lost")
                assertFalse(replay.containsKey("token"))
                assertFalse(replay.containsKey("invitationLink"))
                val id = invitation.string("id")
                val reissued = ok(P + "/invitations/$id/reissue", OWNER, version(invitation))
                requestAs(
                    C + "/invitations/$id/claim",
                    RECIPIENT,
                    obj(
                        "token" to invitation.string("token"),
                        "expectedVersion" to invitation.string("version"),
                    ),
                ).assertClientError(HttpStatusCode.NotFound, "not_found")
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM users WHERE telegram_user_id=$RECIPIENT"))
                assertEquals("issued", reissued.string("status"))
                assertFalse(
                    source
                        .scalar(
                            "SELECT token_hash FROM client_invitations WHERE id=$id",
                        ).contains(invitation.string("token")),
                )
            }
        }

    private fun database(block: (JdbcDataSource) -> Unit) {
        val source =
            JdbcDataSource().apply {
                setURL(
                    "jdbc:h2:mem:onboarding_api_${UUID.randomUUID()};" +
                        "MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
                )
                user = "sa"
                password = ""
            }
        Flyway
            .configure()
            .dataSource(
                source,
            ).locations("classpath:db/migration/common", "classpath:db/migration/h2")
            .load()
            .migrate()
        try {
            block(source)
        } finally {
            source.connection.use { it.createStatement().use { statement -> statement.execute("SHUTDOWN") } }
        }
    }

    private fun withApp(
        source: JdbcDataSource,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            installJsonErrorPages()
            clientOnboardingRoutes(
                JdbcClientContextRepository(source),
                JdbcClientOnboardingRepository(source, OWNER),
                "synthetic_bot",
                OWNER,
            ) {
                TEST_BOT_TOKEN
            }
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.requestAs(
        path: String,
        user: Long,
        body: JsonObject? = null,
        method: HttpMethod = if (body == null) HttpMethod.Get else HttpMethod.Post,
        key: String = UUID.randomUUID().toString(),
    ): HttpResponse =
        client.request(path) {
            this.method = method
            header("X-Telegram-Init-Data", signed(user))
            if (body != null) {
                header("Content-Type", "application/json")
                header("Idempotency-Key", key)
                setBody(body.toString())
            }
        }

    private suspend fun ApplicationTestBuilder.ok(
        path: String,
        user: Long,
        body: JsonObject? = null,
        method: HttpMethod = if (body == null) HttpMethod.Get else HttpMethod.Post,
        key: String = UUID.randomUUID().toString(),
    ): JsonObject {
        val response = requestAs(path, user, body, method, key)
        val text = response.bodyAsText()
        assertEquals(HttpStatusCode.OK, response.status, text)
        assertEquals("no-store", response.headers["Cache-Control"])
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun signed(user: Long): String =
        WebAppInitDataTestHelper.createInitData(
            TEST_BOT_TOKEN,
            mapOf(
                "user" to WebAppInitDataTestHelper.encodeUser(user),
                "auth_date" to Instant.now().epochSecond.toString(),
                "start_param" to "ci_" + "s".repeat(43),
            ),
        )

    private fun obj(vararg pairs: Pair<String, String>) =
        JsonObject(
            pairs.associate {
                it.first to
                    JsonPrimitive(it.second)
            },
        )

    private fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content

    private fun version(value: JsonObject) = obj("expectedVersion" to value.string("version"))

    private fun venue() =
        obj(
            "venueName" to "Synthetic Venue",
            "countryCode" to "RU",
            "city" to "Synthetic City",
            "address" to "Synthetic Street 1",
            "timezone" to "Europe/Moscow",
        )

    private fun JdbcDataSource.scalar(sql: String): String =
        connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    check(rows.next())
                    rows.getString(1)
                }
            }
        }

    private companion object {
        const val P = "/api/platform/onboarding"
        const val C = "/api/client"
        const val OWNER = 998101L
        const val RECIPIENT = 998102L
        const val FOREIGN = 998103L
    }
}
