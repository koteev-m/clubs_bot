package com.example.bot.routes

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

open class ClientOnboardingRoutesDataTest : ClientOnboardingRoutesTestBase() {
    @Test
    fun `owner can archive only empty clients filter and restore without reviving revoked access`() =
        database { source ->
            withApp(source) {
                val issued =
                    ok(
                        P + "/invitations",
                        OWNER,
                        obj(
                            "clientName" to "Archive",
                            "recipientLabel" to "Recipient",
                        ),
                    )
                val clientId = issued.string("clientId")
                val before = ok(P + "/clients/$clientId", OWNER)
                requestAs(P + "/clients/$clientId/archive", FOREIGN, version(before))
                    .assertClientError(HttpStatusCode.Forbidden, "forbidden")
                requestAs(P + "/clients/$clientId/archive", OWNER, version(before))
                    .assertClientError(HttpStatusCode.Conflict, "client_not_empty")
                ok(
                    C + "/invitations/${issued.string("id")}/claim",
                    RECIPIENT,
                    obj("token" to issued.string("token"), "expectedVersion" to issued.string("version")),
                )
                val active = ok(P + "/clients/$clientId", OWNER)
                val member =
                    active
                        .getValue("members")
                        .jsonArray
                        .single()
                        .jsonObject
                ok(P + "/clients/$clientId/members/${member.string("userId")}/revoke", OWNER, version(member))
                val revoked = ok(P + "/clients/$clientId", OWNER)
                assertEquals(JsonPrimitive(true), revoked["active"])
                assertEquals(JsonPrimitive(0L), revoked["activeMembers"])
                val archived =
                    ok(P + "/clients/$clientId/archive", OWNER, version(revoked), key = "archive-http-lost-response")
                assertEquals(JsonPrimitive(true), archived["archived"])
                assertEquals(
                    archived,
                    ok(P + "/clients/$clientId/archive", OWNER, version(revoked), key = "archive-http-lost-response"),
                )
                assertTrue(ok(P + "/clients/search", OWNER, obj()).getValue("items").jsonArray.isEmpty())
                assertEquals(
                    1,
                    ok(P + "/clients/search", OWNER, obj("status" to "archived")).getValue("items").jsonArray.size,
                )
                requestAs(C + "/clients/$clientId/onboarding", RECIPIENT)
                    .assertClientError(HttpStatusCode.NotFound, "not_found")
                requestAs(C + "/invitations/resolve", RECIPIENT, obj("token" to issued.string("token")))
                    .assertClientError(HttpStatusCode.NotFound, "not_found")
                requestAs(P + "/clients/$clientId/restore", OWNER, version(revoked))
                    .assertClientError(HttpStatusCode.Conflict, "version_conflict")
                val restored = ok(P + "/clients/$clientId/restore", OWNER, version(archived))
                assertEquals(JsonPrimitive(false), restored["archived"])
                assertEquals(JsonPrimitive(0L), restored["activeMembers"])
                requestAs(C + "/clients/$clientId/onboarding", RECIPIENT)
                    .assertClientError(HttpStatusCode.NotFound, "not_found")
                assertEquals("1", source.scalar("SELECT COUNT(*) FROM client_memberships WHERE is_active=FALSE"))
                assertEquals("1", source.scalar("SELECT COUNT(*) FROM client_invitations"))
                val summary = ok(P + "/summary", OWNER)
                assertEquals(JsonPrimitive(1L), summary["clients"])
                assertEquals(JsonPrimitive(0L), summary["activeMembers"])
                assertTrue(ok(P + "/clubs/search", OWNER, obj()).getValue("items").jsonArray.isEmpty())
                requestAs(P + "/summary", FOREIGN).assertClientError(HttpStatusCode.Forbidden, "forbidden")
                requestAs(P + "/clubs/search", FOREIGN, obj()).assertClientError(HttpStatusCode.Forbidden, "forbidden")
            }
        }

    @Test
    fun `one action accepts invitation and direct creation enforces owner managed client limit`() =
        database { source ->
            lateinit var clientId: String
            lateinit var clubId: String
            withApp(source) {
                val invitation =
                    ok(
                        P + "/invitations",
                        OWNER,
                        obj(
                            "clientName" to "Direct",
                            "recipientLabel" to "Recipient",
                        ),
                    )
                clientId = invitation.string("clientId")
                val id = invitation.string("id")
                assertEquals("direct", invitation.string("acceptanceMode"))
                val acceptBody =
                    obj(
                        "token" to invitation.string("token"),
                        "expectedVersion" to invitation.string("version"),
                    )
                val accepted =
                    ok(C + "/invitations/$id/claim", RECIPIENT, acceptBody, key = "direct-accept-lost-response")
                assertEquals("accepted", accepted.string("status"))
                assertEquals(
                    accepted,
                    ok(C + "/invitations/$id/claim", RECIPIENT, acceptBody, key = "direct-accept-lost-response"),
                )
                requestAs(C + "/invitations/$id/claim", FOREIGN, acceptBody)
                    .assertClientError(HttpStatusCode.NotFound, "not_found")
                val before = ok(C + "/clients/$clientId/onboarding", RECIPIENT)
                assertEquals("1", before.string("allowedClubs"))
                assertEquals("0", before.string("createdClubs"))
                val body =
                    obj(
                        "venueName" to "Первое",
                        "countryCode" to "RU",
                        "cityId" to "524901",
                        "address" to "Полный адрес, дом 1",
                    )
                val club = ok(C + "/clients/$clientId/clubs", RECIPIENT, body, key = "direct-create-lost-response")
                clubId = club.string("id")
                assertEquals("Europe/Moscow", club.string("timezone"))
                assertEquals("Полный адрес, дом 1", club.string("address"))
                assertEquals(
                    club,
                    ok(C + "/clients/$clientId/clubs", RECIPIENT, body, key = "direct-create-lost-response"),
                )
                val second = JsonObject(body + obj("venueName" to "Второе", "address" to "Второй адрес"))
                requestAs(C + "/clients/$clientId/clubs", RECIPIENT, second)
                    .assertClientError(HttpStatusCode.Conflict, "club_limit_reached")
                requestAs(C + "/clients/$clientId/clubs", FOREIGN, second)
                    .assertClientError(HttpStatusCode.NotFound, "not_found")
                requestAs(C + "/clubs/$clubId/profile", FOREIGN)
                    .assertClientError(HttpStatusCode.NotFound, "not_found")
                val detail = ok(P + "/clients/$clientId", OWNER)
                val raise =
                    JsonObject(
                        obj("expectedVersion" to detail.string("limitVersion")) + ("allowedClubs" to JsonPrimitive(2)),
                    )
                requestAs(P + "/clients/$clientId/limit", RECIPIENT, raise)
                    .assertClientError(HttpStatusCode.Forbidden, "forbidden")
                ok(P + "/clients/$clientId/limit", OWNER, raise)
                val secondClub = ok(C + "/clients/$clientId/clubs", RECIPIENT, second)
                assertFalse(secondClub["id"] == club["id"])
                val current = ok(P + "/clients/$clientId", OWNER)
                assertEquals("2", current.string("createdClubs"))
                assertEquals("0", current.string("availableClubs"))
                requestAs(
                    P + "/clients/$clientId/limit",
                    OWNER,
                    JsonObject(
                        obj("expectedVersion" to current.string("limitVersion")) + ("allowedClubs" to JsonPrimitive(1)),
                    ),
                ).assertClientError(HttpStatusCode.Conflict, "club_limit_below_usage")
                assertEquals("0", source.scalar("SELECT COUNT(*) FROM client_club_applications"))
                assertEquals(
                    "0",
                    source.scalar(
                        "SELECT COUNT(*) FROM clubs c JOIN client_clubs m ON m.club_id=c.id WHERE m.client_id=$clientId AND c.is_active=TRUE",
                    ),
                )
            }
            withApp(source) {
                assertEquals("Первое", ok(C + "/clubs/$clubId/profile", RECIPIENT).string("name"))
                assertEquals(2, ok(C + "/clients/$clientId/onboarding", RECIPIENT)["clubs"]!!.jsonArray.size)
            }
        }

    @Test
    fun `location selection resolves timezone and refuses injected or mismatched locations`() =
        database { source ->
            withApp(source) {
                val countries = ok(C + "/locations", RECIPIENT)["countries"]!!.jsonArray
                assertTrue(countries.any { it.jsonObject["name"] == JsonPrimitive("Россия") })
                val matches =
                    ok(
                        C + "/locations/search",
                        RECIPIENT,
                        obj("countryCode" to "RU", "query" to "Москва"),
                    )["cities"]!!.jsonArray
                assertTrue(matches.any { it.jsonObject["id"] == JsonPrimitive("524901") })
                val ambiguous =
                    ok(
                        C + "/locations/search",
                        RECIPIENT,
                        obj(
                            "countryCode" to "US",
                            "query" to "Springfield",
                        ),
                    )["cities"]!!.jsonArray
                assertTrue(ambiguous.size > 1)
                assertTrue(ambiguous.map { it.jsonObject["region"] }.distinct().size > 1)
                val absent =
                    ok(
                        C + "/locations/search",
                        RECIPIENT,
                        obj(
                            "countryCode" to "RU",
                            "query" to "DefinitelyMissingCity",
                        ),
                    )
                assertEquals(JsonArray(emptyList()), absent["cities"])
                val base =
                    obj(
                        "venueName" to "No guess",
                        "countryCode" to "RU",
                        "cityId" to "524901",
                        "address" to "Address",
                    )
                requestAs(C + "/clients/1/clubs", RECIPIENT, JsonObject(base + obj("timezone" to "UTC")))
                    .assertClientError(HttpStatusCode.BadRequest, "validation_error")
                requestAs(C + "/clients/1/clubs", RECIPIENT, JsonObject(base + obj("countryCode" to "US")))
                    .assertClientError(HttpStatusCode.BadRequest, "location_required")
                requestAs(C + "/clients/1/clubs", RECIPIENT, JsonObject(base + obj("cityId" to "missing")))
                    .assertClientError(HttpStatusCode.BadRequest, "location_required")
            }
        }

    @Test
    fun `historical confirmation and approved application remain readable and consume the configured limit`() =
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
                // Explicit historical fixture: new invitations no longer use owner confirmation.
                source.connection.use { connection ->
                    connection.createStatement().use {
                        it.executeUpdate(
                            "UPDATE client_invitations SET acceptance_policy='owner_confirmation' WHERE id=$invitationId",
                        )
                    }
                }
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
                assertEquals("1", source.scalar("SELECT COUNT(*) FROM clubs WHERE id=$clubId AND is_active=FALSE"))
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
                assertEquals("accepted", claim.string("status"))
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
                assertEquals("accepted", claim.string("status"))
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
}
