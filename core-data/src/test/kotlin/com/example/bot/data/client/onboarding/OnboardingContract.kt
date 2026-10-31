package com.example.bot.data.client.onboarding

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.time.Duration

internal abstract class OnboardingContract : ClubLayoutContract() {
    @Test
    fun `cross client isolation and membership alone never allow applications`() =
        runBlocking<Unit> {
            val a = fixture.accept(fixture.alice)
            val b = fixture.accept(fixture.bob)
            val draft = fixture.draft(a.id("clientId"))
            expect(
                "not_found",
            ) { fixture.repository.read(fixture.bob, OnboardingRead.CLIENT_APPLICATION_GET, draft.id("id")) }
            expect(
                "not_found",
            ) { fixture.repository.read(fixture.bob, OnboardingRead.CLIENT_APPLICATION_SEARCH, a.id("clientId")) }
            expect("not_found") { fixture.draft(a.id("clientId"), fixture.bob) }
            fixture.execute("UPDATE client_membership_grants SET is_active=FALSE WHERE client_id=${b.id("clientId")}")
            expect("not_found") { fixture.draft(b.id("clientId"), fixture.bob) }
            val context = fixture.repository.read(fixture.bob, OnboardingRead.CLIENT_ONBOARDING, b.id("clientId"))
            assertTrue(context.getValue("permissions").jsonArray.isEmpty())
        }

    @Test
    fun `submitted immutable draft and terminal lifecycle require new linked application`() =
        runBlocking<Unit> {
            val accepted = fixture.accept()
            val draft = fixture.draft(accepted.id("clientId"))
            expect(
                "not_found",
            ) { fixture.repository.read(fixture.owner, OnboardingRead.PLATFORM_APPLICATION_GET, draft.id("id")) }
            val submitted = fixture.mutate(fixture.alice, OnboardingMutation.APPLICATION_SUBMIT, draft)
            expect("invalid_transition") {
                fixture.mutate(
                    fixture.alice,
                    OnboardingMutation.APPLICATION_UPDATE,
                    submitted,
                    OnboardingFixture.fields("Changed"),
                )
            }
            val reviewing = fixture.mutate(fixture.owner, OnboardingMutation.APPLICATION_REVIEW, submitted)
            expect(
                "validation_error",
            ) { fixture.mutate(fixture.owner, OnboardingMutation.APPLICATION_REJECT, reviewing) }
            val rejected =
                fixture.mutate(
                    fixture.owner,
                    OnboardingMutation.APPLICATION_REJECT,
                    reviewing,
                    buildJsonObject {
                        put("reason", "Synthetic rejection")
                    },
                )
            expect(
                "invalid_transition",
            ) { fixture.mutate(fixture.owner, OnboardingMutation.APPLICATION_REVIEW, rejected) }
            expect(
                "invalid_transition",
            ) { fixture.mutate(fixture.alice, OnboardingMutation.APPLICATION_CANCEL, rejected) }
            val replacement =
                fixture.repository.mutate(
                    fixture.alice,
                    OnboardingMutation.APPLICATION_CREATE,
                    accepted.id("clientId"),
                    JsonObject(OnboardingFixture.fields() + ("previousApplicationId" to rejected.getValue("id"))),
                    "linked-new-062",
                )
            assertEquals(rejected["id"], replacement["previousApplicationId"])
            assertEquals(2, fixture.count("client_club_applications"))
        }

    @Test
    fun `approval requires reference date explicit platform confirmation and exact owner`() =
        runBlocking<Unit> {
            val accepted = fixture.accept()
            val draft = fixture.draft(accepted.id("clientId"))
            val submitted = fixture.mutate(fixture.alice, OnboardingMutation.APPLICATION_SUBMIT, draft)
            val reviewing = fixture.mutate(fixture.owner, OnboardingMutation.APPLICATION_REVIEW, submitted)
            expect("forbidden") {
                fixture.mutate(
                    fixture.alice,
                    OnboardingMutation.APPLICATION_APPROVE,
                    reviewing,
                    OnboardingFixture.approval(),
                )
            }
            expect(
                "validation_error",
            ) { fixture.mutate(fixture.owner, OnboardingMutation.APPLICATION_APPROVE, reviewing) }
            expect("validation_error") {
                fixture.mutate(
                    fixture.owner,
                    OnboardingMutation.APPLICATION_APPROVE,
                    reviewing,
                    JsonObject(OnboardingFixture.approval() + ("platformConfirmed" to JsonPrimitive(false))),
                )
            }
            val approved =
                fixture.mutate(
                    fixture.owner,
                    OnboardingMutation.APPLICATION_APPROVE,
                    reviewing,
                    OnboardingFixture.approval(),
                )
            fixture.clock.current = fixture.clock.current.plus(Duration.ofDays(365))
            assertEquals(
                "approved",
                fixture.repository
                    .read(
                        fixture.alice,
                        OnboardingRead.CLIENT_APPLICATION_GET,
                        approved.id("id"),
                    ).string("status"),
            )
            expect(
                "invalid_transition",
            ) { fixture.mutate(fixture.alice, OnboardingMutation.APPLICATION_CANCEL, approved) }
            val cancelled =
                fixture
                    .mutate(
                        fixture.owner,
                        OnboardingMutation.PLATFORM_APPLICATION_CANCEL,
                        approved,
                        buildJsonObject {
                            put("reason", "Approval withdrawn")
                        },
                    )
            assertEquals("cancelled", cancelled.string("status"))
        }

    @Test
    fun `rejecting another venue preserves existing exact club access and restore selects only listed clubs`() =
        runBlocking<Unit> {
            val accepted = fixture.accept()
            val clientId = accepted.id("clientId")
            val userId = fixture.rows("SELECT user_id FROM client_memberships").single().long("user_id")
            fixture.execute(
                "INSERT INTO clubs(id,name,timezone) VALUES (62001,'Synthetic A1','UTC'),(62002,'Synthetic A2','UTC')",
            )
            fixture.execute("INSERT INTO client_clubs(client_id,club_id) VALUES ($clientId,62001),($clientId,62002)")
            fixture.execute(
                "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active) VALUES " +
                    "($clientId,$userId,62001,'$READ_CLUB',TRUE),($clientId,$userId,62002,'$READ_CLUB',TRUE)",
            )
            val draft = fixture.draft(clientId)
            val submitted = fixture.mutate(fixture.alice, OnboardingMutation.APPLICATION_SUBMIT, draft)
            val reviewing = fixture.mutate(fixture.owner, OnboardingMutation.APPLICATION_REVIEW, submitted)
            fixture.mutate(
                fixture.owner,
                OnboardingMutation.APPLICATION_REJECT,
                reviewing,
                buildJsonObject {
                    put("reason", "Only the new venue is rejected")
                },
            )
            assertTrue(fixture.rows("SELECT * FROM client_club_grants").all { it.active() })
            fixture.repository.mutate(
                fixture.owner,
                OnboardingMutation.MEMBER_REVOKE,
                clientId,
                buildJsonObject {
                    put("userId", userId.toString())
                    put("expectedVersion", "1")
                },
                "specific-clubs-revoke",
            )
            fixture.repository.mutate(
                fixture.owner,
                OnboardingMutation.MEMBER_RESTORE,
                clientId,
                buildJsonObject {
                    put("userId", userId.toString())
                    put("expectedVersion", "2")
                    put("permissions", JsonArray(listOf(JsonPrimitive(MANAGE))))
                    put(
                        "clubGrants",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("clubId", "62001")
                                    put("permission", READ_CLUB)
                                },
                            ),
                        ),
                    )
                },
                "specific-clubs-restore",
            )
            val context = fixture.repository.read(fixture.alice, OnboardingRead.CLIENT_ONBOARDING, clientId)
            assertEquals(listOf("62001"), context.getValue("clubs").jsonArray.map { it.jsonObject.string("id") })
            assertEquals(2, fixture.count("client_clubs"))
        }

    @Test
    fun `normalized duplicate reservation blocks second submission until cancellation`() =
        runBlocking<Unit> {
            val accepted = fixture.accept()
            val first = fixture.draft(accepted.id("clientId"), venue = "  Synthetic   Venue ")
            val second = fixture.draft(accepted.id("clientId"), venue = "synthetic venue")
            val submitted = fixture.mutate(fixture.alice, OnboardingMutation.APPLICATION_SUBMIT, first)
            expect(
                "duplicate_application",
            ) { fixture.mutate(fixture.alice, OnboardingMutation.APPLICATION_SUBMIT, second) }
            fixture.mutate(fixture.alice, OnboardingMutation.APPLICATION_CANCEL, submitted)
            assertEquals(
                "submitted",
                fixture.mutate(fixture.alice, OnboardingMutation.APPLICATION_SUBMIT, second).string("status"),
            )
        }

    @Test
    fun `audit failure rolls back minimal user client invitation and command`() =
        runBlocking<Unit> {
            try {
                fixture.failingAuditRepository().mutate(
                    fixture.owner,
                    OnboardingMutation.INVITATION_ISSUE,
                    body =
                        buildJsonObject {
                            put("clientName", "Synthetic failure")
                            put("recipientLabel", "No side effects")
                        },
                    idempotencyKey = "fault-audit-062",
                )
                error("Expected synthetic audit failure")
            } catch (_: SQLException) {
                assertEquals(fixture.baseline.getValue("users"), fixture.rows("SELECT * FROM users ORDER BY id"))
                assertEquals(0, fixture.count("clients"))
                assertEquals(0, fixture.count("client_invitations"))
                assertEquals(0, fixture.count("client_commands"))
            }
        }

    @Test
    fun `audit failure during confirmation rolls back all authority grants`() =
        runBlocking<Unit> {
            val issued = fixture.issue()
            val claimed = fixture.claim(issued)
            try {
                fixture.failingAuditRepository().mutate(
                    fixture.owner,
                    OnboardingMutation.INVITATION_CONFIRM,
                    claimed.id("id"),
                    buildJsonObject { put("expectedVersion", claimed.getValue("version")) },
                    "confirm-fault-062",
                )
                error("Expected audit failure")
            } catch (_: SQLException) {
                assertEquals(0, fixture.count("client_memberships"))
                assertEquals(0, fixture.count("client_membership_grants"))
                assertFalse(fixture.rows("SELECT * FROM clients").single().active())
                assertEquals("claimed", fixture.rows("SELECT * FROM client_invitations").single()["status"])
                assertEquals(2, fixture.count("client_commands"))
            }
        }

    @Test
    fun `audit failure during approval rolls back decision commercial agreement and command`() =
        runBlocking<Unit> {
            val accepted = fixture.accept()
            val draft = fixture.draft(accepted.id("clientId"))
            val submitted = fixture.mutate(fixture.alice, OnboardingMutation.APPLICATION_SUBMIT, draft)
            val reviewing = fixture.mutate(fixture.owner, OnboardingMutation.APPLICATION_REVIEW, submitted)
            val before = fixture.rows("SELECT * FROM client_club_applications")
            val commands = fixture.count("client_commands")
            try {
                fixture.failingAuditRepository().mutate(
                    fixture.owner,
                    OnboardingMutation.APPLICATION_APPROVE,
                    reviewing.id("id"),
                    JsonObject(OnboardingFixture.approval() + ("expectedVersion" to reviewing.getValue("version"))),
                    "approval-fault-062",
                )
                error("Expected audit failure")
            } catch (_: SQLException) {
                assertEquals(before, fixture.rows("SELECT * FROM client_club_applications"))
                assertEquals(commands, fixture.count("client_commands"))
            }
        }

    @Test
    fun `client revocation races submission without leaving an unauthorized later mutation`() =
        runBlocking<Unit> {
            val accepted = fixture.accept()
            val draft = fixture.draft(accepted.id("clientId"))
            val member =
                fixture.repository
                    .read(fixture.owner, OnboardingRead.PLATFORM_CLIENT_GET, accepted.id("clientId"))
                    .getValue("members")
                    .jsonArray
                    .single()
                    .jsonObject
            val outcomes =
                race(
                    {
                        fixture.repository.mutate(
                            fixture.owner,
                            OnboardingMutation.MEMBER_REVOKE,
                            accepted.id("clientId"),
                            buildJsonObject {
                                put("userId", member.getValue("userId"))
                                put("expectedVersion", "1")
                            },
                            "revoke-race-062",
                        )
                    },
                    { fixture.mutate(fixture.alice, OnboardingMutation.APPLICATION_SUBMIT, draft) },
                )
            assertTrue(outcomes == listOf("ok", "ok") || outcomes == listOf("ok", "not_found"))
            assertFalse(fixture.rows("SELECT * FROM client_memberships").single().active())
            expect(
                "not_found",
            ) { fixture.repository.read(fixture.alice, OnboardingRead.CLIENT_APPLICATION_GET, draft.id("id")) }
        }

    @Test
    fun `same-client managers see submitted applications but never another authors draft or mutations`() =
        runBlocking<Unit> {
            val accepted = fixture.accept()
            val clientId = accepted.id("clientId")
            fixture.execute("INSERT INTO users(telegram_user_id,username) VALUES (880062003,'synthetic_manager')")
            val userId = fixture.rows("SELECT id FROM users WHERE telegram_user_id=880062003").single().long("id")
            fixture.execute(
                "INSERT INTO client_memberships(client_id,user_id,is_active) VALUES ($clientId,$userId,TRUE)",
            )
            fixture.execute(
                "INSERT INTO client_membership_grants(client_id,user_id,permission_code," +
                    "is_active) VALUES ($clientId,$userId,'$MANAGE',TRUE)",
            )
            val draft = fixture.draft(clientId)
            expect(
                "not_found",
            ) { fixture.repository.read(fixture.bob, OnboardingRead.CLIENT_APPLICATION_GET, draft.id("id")) }
            assertTrue(
                fixture.repository
                    .read(
                        fixture.bob,
                        OnboardingRead.CLIENT_APPLICATION_SEARCH,
                        clientId,
                    ).getValue("items")
                    .jsonArray
                    .isEmpty(),
            )
            val submitted = fixture.mutate(fixture.alice, OnboardingMutation.APPLICATION_SUBMIT, draft)
            assertEquals(
                "submitted",
                fixture.repository
                    .read(
                        fixture.bob,
                        OnboardingRead.CLIENT_APPLICATION_GET,
                        submitted.id("id"),
                    ).string("status"),
            )
            expect("not_found") { fixture.mutate(fixture.bob, OnboardingMutation.APPLICATION_CANCEL, submitted) }
        }

    @Test
    fun `concurrent claim has one candidate and no automatic authority`() =
        runBlocking<Unit> {
            val issued = fixture.issue()
            val outcomes = race({ fixture.claim(issued, fixture.alice) }, { fixture.claim(issued, fixture.bob) })
            assertEquals(listOf("not_found", "ok"), outcomes.sorted())
            assertEquals(fixture.baseline.getValue("users").size + 2L, fixture.count("users"))
            assertEquals(0, fixture.count("client_memberships"))
            assertEquals(2, fixture.count("audit_log"))
        }

    @Test
    fun `confirmation and concurrent repeated claim avoid FK actor deadlock and retain one membership`() =
        runBlocking<Unit> {
            val issued = fixture.issue()
            val claimed = fixture.claim(issued)
            val outcomes =
                race(
                    { fixture.mutate(fixture.owner, OnboardingMutation.INVITATION_CONFIRM, claimed) },
                    { fixture.claim(issued) },
                )
            assertEquals(listOf("ok", "ok"), outcomes)
            assertEquals(1, fixture.count("client_memberships"))
            assertEquals(1, fixture.count("client_membership_grants"))
            assertEquals("accepted", fixture.rows("SELECT status FROM client_invitations").single()["status"])
        }

    @Test
    fun `concurrent confirm revoke uses version and creates no partial access`() =
        runBlocking<Unit> {
            val claimed = fixture.claim(fixture.issue())
            val outcomes =
                race(
                    { fixture.mutate(fixture.owner, OnboardingMutation.INVITATION_CONFIRM, claimed) },
                    { fixture.mutate(fixture.owner, OnboardingMutation.INVITATION_REVOKE, claimed) },
                )
            assertEquals(listOf("ok", "version_conflict"), outcomes.sorted())
            val accepted = fixture.rows("SELECT * FROM client_invitations").single()["status"] == "accepted"
            assertEquals(if (accepted) 1 else 0, fixture.count("client_memberships").toInt())
            assertEquals(if (accepted) 1 else 0, fixture.count("client_membership_grants").toInt())
        }

    @Test
    fun `concurrent submit cancel and approve reject have one winning transition`() =
        runBlocking<Unit> {
            val accepted = fixture.accept()
            val draft = fixture.draft(accepted.id("clientId"))
            assertEquals(
                listOf("ok", "version_conflict"),
                race(
                    { fixture.mutate(fixture.alice, OnboardingMutation.APPLICATION_SUBMIT, draft) },
                    { fixture.mutate(fixture.alice, OnboardingMutation.APPLICATION_CANCEL, draft) },
                ).sorted(),
            )
            val next = fixture.draft(accepted.id("clientId"), venue = "Another venue")
            val submitted = fixture.mutate(fixture.alice, OnboardingMutation.APPLICATION_SUBMIT, next)
            val reviewing = fixture.mutate(fixture.owner, OnboardingMutation.APPLICATION_REVIEW, submitted)
            assertEquals(
                listOf("ok", "version_conflict"),
                race(
                    {
                        fixture.mutate(
                            fixture.owner,
                            OnboardingMutation.APPLICATION_APPROVE,
                            reviewing,
                            OnboardingFixture.approval(),
                        )
                    },
                    {
                        fixture.mutate(
                            fixture.owner,
                            OnboardingMutation.APPLICATION_REJECT,
                            reviewing,
                            buildJsonObject {
                                put("reason", "Synthetic no")
                            },
                        )
                    },
                ).sorted(),
            )
        }

    @Test
    fun `lost mutation response replay is stable and rejects changed payload or revoked authority`() =
        runBlocking<Unit> {
            val accepted = fixture.accept()
            val draft = fixture.draft(accepted.id("clientId"))
            val submitted =
                fixture.mutate(
                    fixture.alice,
                    OnboardingMutation.APPLICATION_SUBMIT,
                    draft,
                    key = "lost-submit-062",
                )
            val count = fixture.count("audit_log")
            assertEquals(
                submitted,
                fixture.mutate(fixture.alice, OnboardingMutation.APPLICATION_SUBMIT, draft, key = "lost-submit-062"),
            )
            assertEquals(count, fixture.count("audit_log"))
            expect("idempotency_conflict") {
                fixture.mutate(fixture.alice, OnboardingMutation.APPLICATION_CANCEL, draft, key = "lost-submit-062")
            }
            fixture.execute("UPDATE client_membership_grants SET is_active=FALSE")
            expect("not_found") {
                fixture.mutate(fixture.alice, OnboardingMutation.APPLICATION_SUBMIT, draft, key = "lost-submit-062")
            }
        }
}

internal class ClientOnboardingRepositoryTest : OnboardingContract() {
    override fun newFixture(target: String?): OnboardingFixture = OnboardingFixture.h2(target)
}
