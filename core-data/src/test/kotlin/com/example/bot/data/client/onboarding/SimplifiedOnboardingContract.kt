package com.example.bot.data.client.onboarding

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.time.Duration
import java.util.UUID

/** The same new contract runs on H2 and real PostgreSQL, alongside retained legacy lifecycle tests. */
internal abstract class SimplifiedOnboardingContract : ClubProfileContract() {
    @Test
    fun `new invitation accepts in one mutation without comparison code or platform confirmation`() =
        runBlocking<Unit> {
            val invitation = fixture.issueDirect()
            val before = fixture.count("users")
            val preview =
                fixture.repository.read(
                    fixture.alice,
                    OnboardingRead.INVITATION_RESOLVE,
                    body = buildJsonObject { put("token", invitation.getValue("token")) },
                )
            assertEquals("direct", preview.string("acceptanceMode"))
            assertEquals(before, fixture.count("users"))
            assertEquals(0, fixture.count("client_memberships"))
            val accepted = fixture.claim(invitation, key = "direct-accept-once")
            assertEquals("accepted", accepted.string("status"))
            assertEquals(JsonNull, accepted["verificationCode"])
            assertEquals(accepted, fixture.claim(invitation, key = "direct-accept-once"))
            assertEquals(accepted, fixture.claim(invitation, key = "direct-accept-again"))
            assertEquals(1, fixture.count("client_memberships"))
            assertEquals(1, fixture.count("client_membership_grants"))
            assertEquals(1, fixture.rows("SELECT * FROM audit_log WHERE action='client.invitation.accept'").size)
            assertEquals(fixture.baseline.getValue("user_roles"), fixture.rows("SELECT * FROM user_roles ORDER BY id"))
            val context = context(accepted.id("clientId"))
            assertEquals("1", context.getValue("allowedClubs").jsonPrimitive.content)
            expect(
                "invalid_transition",
            ) { fixture.mutate(fixture.owner, OnboardingMutation.INVITATION_CONFIRM, accepted) }
        }

    @Test
    fun `two recipients racing a direct invitation grant only one account`() =
        runBlocking<Unit> {
            val invitation = fixture.issueDirect()
            assertEquals(
                listOf("not_found", "ok"),
                race({ fixture.claim(invitation) }, { fixture.claim(invitation, fixture.bob) }).sorted(),
            )
            assertEquals(1, fixture.count("client_memberships"))
            assertEquals(1, fixture.count("client_membership_grants"))
            assertEquals(1, fixture.rows("SELECT * FROM audit_log WHERE action='client.invitation.accept'").size)
        }

    @Test
    fun `direct expiry revoke reissue and membership revoke remain fail closed`() =
        runBlocking<Unit> {
            val expired = fixture.issueDirect()
            fixture.clock.current = fixture.clock.current.plus(Duration.ofDays(7))
            expect("not_found") { fixture.claim(expired) }
            val fresh = fixture.issueDirect()
            val revoked = fixture.mutate(fixture.owner, OnboardingMutation.INVITATION_REVOKE, fresh)
            expect("not_found") { fixture.claim(fresh) }
            val replacement = fixture.mutate(fixture.owner, OnboardingMutation.INVITATION_REISSUE, revoked)
            assertEquals("direct", replacement.string("acceptanceMode"))
            val accepted = fixture.claim(replacement)
            val clientId = accepted.id("clientId")
            val member =
                fixture.repository
                    .read(fixture.owner, OnboardingRead.PLATFORM_CLIENT_GET, clientId)
                    .getValue("members")
                    .jsonArray
                    .single()
                    .jsonObject
            fixture.repository.mutate(
                fixture.owner,
                OnboardingMutation.MEMBER_REVOKE,
                clientId,
                buildJsonObject {
                    put("userId", member.getValue("userId"))
                    put("expectedVersion", "1")
                },
                "revoke-direct-member",
            )
            expect("not_found") { fixture.claim(replacement) }
            expect("not_found") { context(clientId) }
            assertEquals(1, fixture.count("client_memberships"))
        }

    @Test
    fun `direct acceptance refuses a prepopulated cabinet and never restores existing membership`() =
        runBlocking<Unit> {
            val invitation = fixture.issueDirect()
            val clientId = invitation.id("clientId")
            fixture.execute("INSERT INTO clubs(id,name,timezone) VALUES (64001,'Existing','UTC')")
            fixture.execute("INSERT INTO client_clubs(client_id,club_id) VALUES ($clientId,64001)")
            expect("invalid_transition") { fixture.claim(invitation) }
            assertEquals(0, fixture.count("client_memberships"))
            assertEquals("issued", fixture.rows("SELECT status FROM client_invitations").single()["status"])
        }

    @Test
    fun `limit one allows first private club and owner increase enables second with durable audit`() =
        runBlocking<Unit> {
            val clientId = fixture.claim(fixture.issueDirect()).id("clientId")
            val first = directCreate(clientId)
            assertFalse(fixture.rows("SELECT * FROM clubs WHERE id=${first.id("id")}").single().active())
            assertEquals("0", context(clientId).getValue("availableClubs").jsonPrimitive.content)
            expect("club_limit_reached") { directCreate(clientId, "Second") }
            expect("forbidden") { limit(clientId, 2, fixture.alice) }
            expect("forbidden") { limit(clientId, 2, fixture.bob) }
            expect("club_limit_below_usage") { limit(clientId, 0) }
            limit(clientId, 2)
            val second = directCreate(clientId, "Second")
            assertTrue(first["id"] != second["id"])
            assertEquals(2, fixture.count("client_clubs"))
            assertEquals(0, fixture.count("client_club_applications"))
            val audit = fixture.rows("SELECT metadata_json FROM audit_log WHERE action='client.limit.update'").single()
            assertTrue(audit.getValue("metadata_json")!!.contains("\"allowedClubs\":2"))
            assertEquals("0", context(clientId).getValue("availableClubs").jsonPrimitive.content)
        }

    @Test
    fun `limit shared by all members serializes competing distinct creates`() =
        runBlocking<Unit> {
            val clientId = fixture.claim(fixture.issueDirect()).id("clientId")
            addBob(clientId)
            val outcomes =
                race(
                    { directCreate(clientId, "First") },
                    { directCreate(clientId, "Second", fixture.bob) },
                )
            assertEquals(listOf("club_limit_reached", "ok"), outcomes.sorted())
            assertEquals(1, fixture.count("client_direct_club_profiles"))
            assertEquals(1, fixture.count("client_clubs"))
            assertEquals("0", context(clientId, fixture.bob).getValue("availableClubs").jsonPrimitive.content)
        }

    @Test
    fun `lost direct create response and distinct key exact duplicate both return one durable club`() =
        runBlocking<Unit> {
            val clientId = fixture.claim(fixture.issueDirect()).id("clientId")
            val first = directCreate(clientId, key = "lost-direct-response")
            assertEquals(first, directCreate(clientId, key = "lost-direct-response"))
            assertEquals(first, directCreate(clientId, key = "new-key-exact-duplicate"))
            assertEquals(
                listOf("ok", "ok"),
                race(
                    { directCreate(clientId, key = "concurrent-direct-replay") },
                    { directCreate(clientId, key = "concurrent-direct-replay") },
                ),
            )
            assertEquals(1, fixture.count("client_direct_club_profiles"))
            assertEquals(1, fixture.rows("SELECT * FROM audit_log WHERE action='client.club.create'").size)
            expect("idempotency_conflict") { directCreate(clientId, "Changed", key = "lost-direct-response") }
            val fresh = JdbcClientOnboardingRepository(fixture.source, fixture.owner.telegramUserId, fixture.clock)
            assertEquals(first, fresh.read(fixture.alice, OnboardingRead.CLUB_PROFILE_GET, first.id("id")))
        }

    @Test
    fun `other client and revoked manager cannot create read or replay private clubs`() =
        runBlocking<Unit> {
            val clientId = fixture.claim(fixture.issueDirect()).id("clientId")
            fixture.claim(fixture.issueDirect(), fixture.bob)
            val first = directCreate(clientId, key = "private-club-command")
            expect("not_found") { directCreate(clientId, actor = fixture.bob) }
            expect(
                "not_found",
            ) { fixture.repository.read(fixture.bob, OnboardingRead.CLUB_PROFILE_GET, first.id("id")) }
            fixture.execute("UPDATE client_memberships SET is_active=FALSE WHERE client_id=$clientId")
            expect("not_found") { directCreate(clientId, key = "private-club-command") }
            expect(
                "not_found",
            ) { fixture.repository.read(fixture.alice, OnboardingRead.CLUB_PROFILE_GET, first.id("id")) }
            assertEquals(1, fixture.count("client_direct_club_profiles"))
        }

    @Test
    fun `legacy creation cannot bypass unset or exhausted limits and old saved application can be reused`() =
        runBlocking<Unit> {
            val clientId = fixture.accept().id("clientId")
            val application = fixture.approved(clientId)
            val oldRows = fixture.rows("SELECT * FROM client_club_applications")
            fixture.execute("UPDATE clients SET allowed_clubs=NULL WHERE id=$clientId")
            assertEquals(JsonNull, context(clientId)["allowedClubs"])
            expect("club_limit_unset") { create(application) }
            expect("club_limit_unset") { directCreate(clientId) }
            limit(clientId, 1)
            val saved =
                fixture.repository.mutate(
                    fixture.alice,
                    OnboardingMutation.CLUB_CREATE,
                    clientId,
                    JsonObject(directFields() + ("applicationId" to application.getValue("id"))),
                    "reuse-existing-application",
                )
            assertEquals(oldRows, fixture.rows("SELECT * FROM client_club_applications"))
            assertEquals(saved, create(application))
            val second = fixture.approved(clientId, venue = "Second")
            expect("club_limit_reached") { create(second) }
            assertEquals(1, fixture.count("client_clubs"))
        }

    @Test
    fun `direct create audit failure rolls back club mapping profile grants and command`() =
        runBlocking<Unit> {
            val clientId = fixture.claim(fixture.issueDirect()).id("clientId")
            val before =
                listOf("clubs", "client_clubs", "client_direct_club_profiles", "client_club_grants", "client_commands")
                    .associateWith { fixture.rows("SELECT * FROM $it ORDER BY 1") }
            try {
                fixture.failingAuditRepository().mutate(
                    fixture.alice,
                    OnboardingMutation.CLUB_CREATE,
                    clientId,
                    directFields(),
                    "failed-direct-audit",
                )
                error("Audit failure must abort creation")
            } catch (_: SQLException) {
                assertEquals(before, before.keys.associateWith { fixture.rows("SELECT * FROM $it ORDER BY 1") })
            }
            directCreate(clientId)
        }

    @Test
    fun `unlinked legacy city matches refuse ambiguity instead of returning a different city club`() =
        runBlocking<Unit> {
            val firstClient = fixture.claim(fixture.issueDirect()).id("clientId")
            limit(firstClient, 2)
            val approval = sameNamedLegacyApproval(firstClient)
            val legacy = create(approval)
            val selected =
                JsonObject(
                    directFields() +
                        buildJsonObject {
                            put("city", "Сокол")
                            put("cityId", "491281")
                            put("region", "Вологодская область")
                        },
                )
            expect("duplicate_application") {
                fixture.repository.mutate(
                    fixture.alice,
                    OnboardingMutation.CLUB_CREATE,
                    firstClient,
                    selected,
                    "legacy-ambiguous-city",
                )
            }
            val linked =
                fixture.repository.mutate(
                    fixture.alice,
                    OnboardingMutation.CLUB_CREATE,
                    firstClient,
                    JsonObject(selected + ("applicationId" to approval.getValue("id"))),
                    "explicit-legacy-source",
                )
            assertEquals(legacy["id"], linked["id"])
            val secondClient = fixture.claim(fixture.issueDirect()).id("clientId")
            limit(secondClient, 2)
            val direct =
                fixture.repository.mutate(
                    fixture.alice,
                    OnboardingMutation.CLUB_CREATE,
                    secondClient,
                    selected,
                    "direct-same-name-city",
                )
            fixture.mutate(
                fixture.alice,
                OnboardingMutation.CLUB_PROFILE_UPDATE,
                direct,
                buildJsonObject { put("timezone", "Europe/Paris") },
            )
            val laterApproval = sameNamedLegacyApproval(secondClient)
            expect("duplicate_application") { create(laterApproval) }
            assertEquals(2, fixture.count("client_clubs"))
        }

    @Test
    fun `same city name in different regions remains distinct while stable city repeat deduplicates`() =
        runBlocking<Unit> {
            val clientId = fixture.claim(fixture.issueDirect()).id("clientId")
            limit(clientId, 2)
            val kursk =
                JsonObject(
                    directFields() +
                        buildJsonObject {
                            put("city", "Железногорск")
                            put("cityId", "463343")
                            put("region", "Курская область")
                            put("timezone", "Europe/Moscow")
                        },
                )
            val krasnoyarsk =
                JsonObject(
                    kursk +
                        buildJsonObject {
                            put("cityId", "1538635")
                            put("region", "Красноярский край")
                            put("timezone", "Asia/Krasnoyarsk")
                        },
                )
            val first =
                fixture.repository.mutate(
                    fixture.alice,
                    OnboardingMutation.CLUB_CREATE,
                    clientId,
                    kursk,
                    "same-name-kursk-city",
                )
            val second =
                fixture.repository.mutate(
                    fixture.alice,
                    OnboardingMutation.CLUB_CREATE,
                    clientId,
                    krasnoyarsk,
                    "same-name-krasnoyarsk",
                )
            assertTrue(first["id"] != second["id"])
            assertEquals("463343", first.string("cityId"))
            assertEquals("Красноярский край", second.string("region"))
            assertEquals(
                first,
                fixture.repository.mutate(
                    fixture.alice,
                    OnboardingMutation.CLUB_CREATE,
                    clientId,
                    kursk,
                    "same-name-repeat-city",
                ),
            )
            assertEquals(2, fixture.count("client_clubs"))
        }

    @Test
    fun `direct ownership cannot be deleted and inactive clubs still consume allowance`() =
        runBlocking<Unit> {
            val clientId = fixture.claim(fixture.issueDirect()).id("clientId")
            val club = directCreate(clientId)
            val clubId = club.id("id")
            listOf(
                "DELETE FROM client_direct_club_profiles WHERE club_id=$clubId",
                "DELETE FROM client_clubs WHERE club_id=$clubId",
            ).forEach { statement ->
                try {
                    fixture.execute(statement)
                    error("Permanent ownership must remain")
                } catch (_: SQLException) {
                    assertEquals(1, fixture.count("client_clubs"))
                }
            }
            fixture.execute("UPDATE clubs SET is_active=FALSE WHERE id=$clubId")
            expect("club_limit_reached") { directCreate(clientId, "Second") }
        }

    @Test
    fun `V064 migration leaves existing limit unset and pending invitation legacy without activation`() {
        newFixture("63").use { previous ->
            previous.execute("INSERT INTO clients(id,display_name,is_active) VALUES (64001,'Legacy cabinet',FALSE)")
            previous.execute(
                "INSERT INTO client_invitations(client_id,recipient_label,token_hash,status,open_marker,expires_at) " +
                    "VALUES (64001,'Legacy recipient','${"a".repeat(
                        64,
                    )}','issued',1,TIMESTAMP WITH TIME ZONE '2030-01-01 00:00:00+00')",
            )
            val before = previous.rows("SELECT id,client_id,status,token_hash,expires_at FROM client_invitations")
            previous.migrate()
            assertEquals(
                before,
                previous.rows("SELECT id,client_id,status,token_hash,expires_at FROM client_invitations"),
            )
            assertEquals(null, previous.rows("SELECT allowed_clubs FROM clients").single()["allowed_clubs"])
            assertEquals(
                "owner_confirmation",
                previous.rows("SELECT acceptance_policy FROM client_invitations").single()["acceptance_policy"],
            )
            assertEquals(0, previous.count("client_memberships"))
        }
    }

    private suspend fun context(
        clientId: Long,
        actor: OnboardingActor = fixture.alice,
    ) = fixture.repository.read(actor, OnboardingRead.CLIENT_ONBOARDING, clientId)

    private suspend fun limit(
        clientId: Long,
        allowed: Int,
        actor: OnboardingActor = fixture.owner,
    ) = fixture.repository.mutate(
        actor,
        OnboardingMutation.CLIENT_LIMIT_UPDATE,
        clientId,
        buildJsonObject {
            put("allowedClubs", allowed)
            put("expectedVersion", "1")
        },
        UUID.randomUUID().toString(),
    )

    private suspend fun directCreate(
        clientId: Long,
        name: String = "Direct venue",
        actor: OnboardingActor = fixture.alice,
        key: String = UUID.randomUUID().toString(),
    ) = fixture.repository.mutate(actor, OnboardingMutation.CLUB_CREATE, clientId, directFields(name), key)

    private fun directFields(name: String = "Direct venue") =
        buildJsonObject {
            put("venueName", name)
            put("countryCode", "RU")
            put("city", "Москва")
            put("address", "Synthetic street 1")
            put("timezone", "Europe/Moscow")
        }

    private suspend fun sameNamedLegacyApproval(clientId: Long): JsonObject {
        val draft =
            fixture.repository.mutate(
                fixture.alice,
                OnboardingMutation.APPLICATION_CREATE,
                clientId,
                JsonObject(OnboardingFixture.fields("Direct venue") + buildJsonObject { put("city", "Сокол") }),
                UUID.randomUUID().toString(),
            )
        val submitted = fixture.mutate(fixture.alice, OnboardingMutation.APPLICATION_SUBMIT, draft)
        val reviewed = fixture.mutate(fixture.owner, OnboardingMutation.APPLICATION_REVIEW, submitted)
        return fixture.mutate(
            fixture.owner,
            OnboardingMutation.APPLICATION_APPROVE,
            reviewed,
            OnboardingFixture.approval(),
        )
    }

    private fun addBob(clientId: Long) {
        fixture.execute("INSERT INTO users(telegram_user_id) VALUES (${fixture.bob.telegramUserId})")
        val userId =
            fixture
                .rows(
                    "SELECT id FROM users WHERE telegram_user_id=${fixture.bob.telegramUserId}",
                ).single()
                .long("id")
        fixture.execute("INSERT INTO client_memberships(client_id,user_id,is_active) VALUES ($clientId,$userId,TRUE)")
        fixture.execute(
            "INSERT INTO client_membership_grants(client_id,user_id,permission_code,is_active) VALUES ($clientId,$userId,'$MANAGE',TRUE)",
        )
    }
}
