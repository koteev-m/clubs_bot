package com.example.bot.data.client.onboarding

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.time.Duration
import java.util.UUID

internal abstract class OnboardingFoundationContract {
    protected lateinit var fixture: OnboardingFixture

    protected abstract fun newFixture(target: String? = null): OnboardingFixture

    @BeforeEach
    fun setup() {
        fixture = newFixture()
    }

    @AfterEach
    fun close() = fixture.close()

    @Test
    fun `V061 upgrade preserves prior records and introduces no client authority`() {
        newFixture("61").use { previous ->
            previous.execute("INSERT INTO clients(id,display_name,is_active) VALUES (6201,'Synthetic prior',TRUE)")
            previous.execute("INSERT INTO users(id,telegram_user_id) VALUES (6201,880062100)")
            previous.execute("INSERT INTO client_memberships(client_id,user_id,is_active) VALUES (6201,6201,TRUE)")
            val before =
                previous.rows(
                    "SELECT client_id,user_id,is_active,created_at,updated_at FROM client_memberships",
                )
            val legacy = previous.rows("SELECT * FROM user_roles ORDER BY id")
            previous.migrate()
            assertEquals(
                before,
                previous.rows("SELECT client_id,user_id,is_active,created_at,updated_at FROM client_memberships"),
            )
            assertEquals(legacy, previous.rows("SELECT * FROM user_roles ORDER BY id"))
            assertEquals("1", previous.rows("SELECT version FROM client_memberships").single()["version"])
            listOf("client_membership_grants", "client_invitations", "client_club_applications", "client_commands")
                .forEach { assertEquals(0, previous.count(it)) }
        }
    }

    @Test
    fun `audit and persisted commands contain hashes and correlation without raw secrets`() =
        runBlocking<Unit> {
            val key = UUID.randomUUID().toString()
            val issued = fixture.issue(key)
            val secret = checkNotNull(issued.string("token"))
            fixture.claim(issued)
            val records =
                fixture.rows("SELECT * FROM audit_log").toString() + fixture.rows("SELECT * FROM client_commands")
            assertFalse(records.contains(key))
            assertFalse(records.contains(secret))
            assertTrue(records.contains("commandCorrelation"))
            assertFalse(records.contains("verificationCode"))
        }

    @Test
    fun `database forbids malformed invitation and cross client membership authority`() =
        runBlocking<Unit> {
            val issued = fixture.issue()
            try {
                fixture.execute("UPDATE client_invitations SET open_marker=NULL WHERE id=${issued.id("id")}")
                error("Inconsistent invitation must fail")
            } catch (_: SQLException) {
                assertEquals("issued", fixture.rows("SELECT status FROM client_invitations").single()["status"])
            }
            val accepted = fixture.mutate(fixture.owner, OnboardingMutation.INVITATION_CONFIRM, fixture.claim(issued))
            val userId = fixture.rows("SELECT user_id FROM client_memberships").single().long("user_id")
            val other = fixture.issue()
            try {
                fixture.execute(
                    "INSERT INTO client_membership_grants(client_id,user_id,permission_code,is_active) " +
                        "VALUES (${other.id("clientId")},$userId,'$MANAGE',TRUE)",
                )
                error("Cross-client grant must fail")
            } catch (_: SQLException) {
                assertEquals(1, fixture.count("client_membership_grants"))
                assertEquals(
                    accepted.id("clientId"),
                    fixture.rows("SELECT client_id FROM client_membership_grants").single().long("client_id"),
                )
            }
        }

    @Test
    fun `issue preview claim confirmation and approval persist without creating a club`() =
        runBlocking<Unit> {
            val issued = fixture.issue()
            val secret = checkNotNull(issued.string("token"))
            assertEquals(43, secret.length)
            assertEquals(64, fixture.rows("SELECT token_hash FROM client_invitations").single()["token_hash"]?.length)
            assertFalse(fixture.rows("SELECT * FROM client_invitations").toString().contains(secret))
            val beforeUsers = fixture.count("users")
            val preview =
                fixture.repository.read(
                    fixture.alice,
                    OnboardingRead.INVITATION_RESOLVE,
                    body =
                        buildJsonObject {
                            put("token", secret)
                        },
                )
            assertEquals("issued", preview.string("status"))
            assertEquals(beforeUsers, fixture.count("users"))
            assertEquals(0, fixture.count("client_memberships"))
            val claimed = fixture.claim(issued)
            assertEquals("claimed", claimed.string("status"))
            assertTrue(Regex("[0-9]{6}").matches(checkNotNull(claimed.string("verificationCode"))))
            assertEquals(0, fixture.count("client_memberships"))
            val accepted = fixture.mutate(fixture.owner, OnboardingMutation.INVITATION_CONFIRM, claimed)
            assertEquals("accepted", accepted.string("status"))
            assertEquals(JsonNull, accepted["verificationCode"])
            val clientId = accepted.id("clientId")
            val draft = fixture.draft(clientId)
            val submitted = fixture.mutate(fixture.alice, OnboardingMutation.APPLICATION_SUBMIT, draft)
            val reviewing = fixture.mutate(fixture.owner, OnboardingMutation.APPLICATION_REVIEW, submitted)
            val approved =
                fixture.mutate(
                    fixture.owner,
                    OnboardingMutation.APPLICATION_APPROVE,
                    reviewing,
                    OnboardingFixture.approval(),
                )
            assertEquals("approved", approved.string("status"))
            val freshRepository =
                JdbcClientOnboardingRepository(fixture.source, fixture.owner.telegramUserId, fixture.clock)
            val clientView =
                freshRepository.read(
                    fixture.alice,
                    OnboardingRead.CLIENT_APPLICATION_GET,
                    approved.id("id"),
                )
            assertEquals("approved", clientView.string("status"))
            assertEquals(4, clientView.getValue("history").jsonArray.size)
            assertFalse(clientView.containsKey("internalNote"))
            assertFalse(clientView.containsKey("commercialReference"))
            assertFalse(clientView.toString().contains("PRIVATE-SYNTHETIC-NOTE"))
            assertEquals(fixture.baseline.getValue("clubs"), fixture.rows("SELECT * FROM clubs ORDER BY id"))
            assertEquals(0, fixture.count("client_clubs"))
            assertEquals(0, fixture.count("client_club_grants"))
            assertEquals(fixture.baseline.getValue("user_roles"), fixture.rows("SELECT * FROM user_roles ORDER BY id"))
            val application = fixture.rows("SELECT * FROM client_club_applications").single()
            assertEquals(application["submitted_hash"], application["approved_snapshot_hash"])
            assertEquals(application["submitted_version"], application["approved_snapshot_version"])
            assertEquals("2", application["submitted_version"])
        }

    @Test
    fun `preview and invalid or forwarded claim never provision or grant access`() =
        runBlocking<Unit> {
            val issued = fixture.issue()
            expect("not_found") {
                fixture.repository.mutate(
                    fixture.bob,
                    OnboardingMutation.INVITATION_CLAIM,
                    issued.id("id"),
                    buildJsonObject {
                        put("token", "x".repeat(43))
                        put("expectedVersion", "1")
                    },
                    "invalid-claim",
                )
            }
            assertEquals(fixture.baseline.getValue("users").size + 1L, fixture.count("users"))
            val claimed = fixture.claim(issued)
            expect("not_found") { fixture.claim(issued, fixture.bob) }
            expect("not_found") {
                fixture.repository.read(
                    fixture.bob,
                    OnboardingRead.INVITATION_RESOLVE,
                    body =
                        buildJsonObject {
                            put("token", issued.getValue("token"))
                        },
                )
            }
            assertEquals(fixture.baseline.getValue("users").size + 2L, fixture.count("users"))
            assertEquals(0, fixture.count("client_memberships"))
            expect("forbidden") { fixture.mutate(fixture.alice, OnboardingMutation.INVITATION_CONFIRM, claimed) }
            assertEquals(0, fixture.count("client_memberships"))
        }

    @Test
    fun `expiry is effective without GET mutation and prevents platform confirmation`() =
        runBlocking<Unit> {
            val issued = fixture.issue()
            val claimed = fixture.claim(issued)
            fixture.clock.current = fixture.clock.current.plus(Duration.ofDays(7))
            val before = fixture.rows("SELECT * FROM client_invitations")
            val status = fixture.repository.read(fixture.alice, OnboardingRead.INVITATION_STATUS, issued.id("id"))
            assertEquals("expired", status.string("status"))
            assertEquals(before, fixture.rows("SELECT * FROM client_invitations"))
            expect(
                "invalid_transition",
            ) { fixture.mutate(fixture.owner, OnboardingMutation.INVITATION_CONFIRM, claimed) }
            expect("not_found") { fixture.claim(issued) }
            assertEquals(0, fixture.count("client_memberships"))
        }

    @Test
    fun `issue retry never recovers secret and explicit reissue invalidates prior link`() =
        runBlocking<Unit> {
            val issued = fixture.issue("issue-retry-062")
            val repeated = fixture.issue("issue-retry-062")
            assertEquals(issued["id"], repeated["id"])
            assertNull(repeated["token"])
            assertEquals(1, fixture.count("clients"))
            assertEquals(1, fixture.count("audit_log"))
            val replacement = fixture.mutate(fixture.owner, OnboardingMutation.INVITATION_REISSUE, issued)
            assertTrue(replacement.id("id") != issued.id("id"))
            expect("not_found") { fixture.claim(issued) }
            val newClaim = fixture.claim(replacement)
            assertEquals("claimed", newClaim.string("status"))
            assertEquals(issued.id("clientId"), replacement.id("clientId"))
        }

    @Test
    fun `concurrent lost issue and reissue retries persist one client and expose each secret once`() =
        runBlocking<Unit> {
            val issued =
                coroutineScope {
                    List(2) { async { fixture.issue("concurrent-lost-issue") } }.map { it.await() }
                }
            assertEquals(1, issued.map { it.id("id") }.toSet().size)
            assertEquals(1, issued.count { it["token"] != null })
            assertEquals(1, fixture.count("clients"))
            assertEquals(1, fixture.count("client_invitations"))
            val replacements =
                coroutineScope {
                    List(2) {
                        async {
                            fixture.mutate(
                                fixture.owner,
                                OnboardingMutation.INVITATION_REISSUE,
                                issued.first(),
                                key = "concurrent-lost-reissue",
                            )
                        }
                    }.map { it.await() }
                }
            assertEquals(1, replacements.map { it.id("id") }.toSet().size)
            assertEquals(1, replacements.count { it["token"] != null })
            assertEquals(issued.first()["clientId"], replacements.first()["clientId"])
            assertEquals(1, fixture.count("clients"))
            assertEquals(2, fixture.count("client_invitations"))
            assertEquals(2, fixture.count("client_commands"))
            assertEquals(3, fixture.count("audit_log"))
        }

    @Test
    fun `same claim and confirmation retry generate one authority and audit event`() =
        runBlocking<Unit> {
            val issued = fixture.issue()
            val claimed = fixture.claim(issued, key = "claim-retry-062")
            val repeatedClaim = fixture.claim(issued, key = "claim-retry-062")
            assertEquals(claimed, repeatedClaim)
            val accepted =
                fixture.mutate(
                    fixture.owner,
                    OnboardingMutation.INVITATION_CONFIRM,
                    claimed,
                    key = "confirm-retry-062",
                )
            assertEquals(
                accepted,
                fixture.mutate(
                    fixture.owner,
                    OnboardingMutation.INVITATION_CONFIRM,
                    claimed,
                    key = "confirm-retry-062",
                ),
            )
            assertEquals(1, fixture.count("client_memberships"))
            assertEquals(1, fixture.count("client_membership_grants"))
            assertEquals(3, fixture.count("audit_log"))
            expect("idempotency_conflict") { fixture.issue("confirm-retry-062") }
        }

    @Test
    fun `member revoke blocks reads replay and old accepted invitation while preserving data`() =
        runBlocking<Unit> {
            val accepted = fixture.accept()
            val clientId = accepted.id("clientId")
            val draft = fixture.draft(clientId)
            val client = fixture.repository.read(fixture.owner, OnboardingRead.PLATFORM_CLIENT_GET, clientId)
            val member =
                client
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
                    put("expectedVersion", member.getValue("version"))
                },
                "member-revoke-062",
            )
            expect(
                "not_found",
            ) { fixture.repository.read(fixture.alice, OnboardingRead.CLIENT_APPLICATION_GET, draft.id("id")) }
            expect(
                "not_found",
            ) { fixture.repository.read(fixture.alice, OnboardingRead.INVITATION_STATUS, accepted.id("id")) }
            expect("not_found") { fixture.repository.read(fixture.alice, OnboardingRead.CLIENT_ONBOARDING, clientId) }
            assertEquals(1, fixture.count("client_club_applications"))
            assertEquals(1, fixture.count("clients"))
            val restore =
                buildJsonObject {
                    put("userId", member.getValue("userId"))
                    put("expectedVersion", "2")
                    put("permissions", JsonArray(emptyList()))
                    put("clubGrants", JsonArray(emptyList()))
                }
            fixture.repository.mutate(
                fixture.owner,
                OnboardingMutation.MEMBER_RESTORE,
                clientId,
                restore,
                "restore-empty-062",
            )
            val context = fixture.repository.read(fixture.alice, OnboardingRead.CLIENT_ONBOARDING, clientId)
            assertTrue(context.getValue("permissions").jsonArray.isEmpty())
            expect("not_found") { fixture.draft(clientId) }
        }

    @Test
    fun `restore cannot invent a club grant or transfer to another account`() =
        runBlocking<Unit> {
            val accepted = fixture.accept()
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
                "revoke-scope-062",
            )
            expect("validation_error") {
                fixture.repository.mutate(
                    fixture.owner,
                    OnboardingMutation.MEMBER_RESTORE,
                    clientId,
                    buildJsonObject {
                        put("userId", member.getValue("userId"))
                        put("expectedVersion", "2")
                        put("permissions", JsonArray(listOf(JsonPrimitive(MANAGE))))
                        put(
                            "clubGrants",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("clubId", "999")
                                        put("permission", READ_CLUB)
                                    },
                                ),
                            ),
                        )
                    },
                    "restore-forged-062",
                )
            }
            assertFalse(fixture.rows("SELECT * FROM client_memberships").single().active())
            assertFalse(fixture.rows("SELECT * FROM client_membership_grants").single().active())
        }

    protected suspend fun expect(
        code: String,
        block: suspend () -> Unit,
    ) {
        try {
            block()
            error("Expected $code")
        } catch (failure: OnboardingFailure) {
            assertEquals(code, failure.code)
        }
    }

    protected suspend fun race(
        first: suspend () -> Unit,
        second: suspend () -> Unit,
    ): List<String> =
        coroutineScope {
            val start = CompletableDeferred<Unit>()
            val tasks =
                listOf(first, second).map { action ->
                    async {
                        start.await()
                        try {
                            action()
                            "ok"
                        } catch (failure: OnboardingFailure) {
                            failure.code
                        }
                    }
                }
            start.complete(Unit)
            tasks.map { it.await() }
        }
}
