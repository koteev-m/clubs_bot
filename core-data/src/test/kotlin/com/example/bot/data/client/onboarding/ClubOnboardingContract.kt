package com.example.bot.data.client.onboarding

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.SQLException

/** Runs unchanged against H2 and PostgreSQL 16, with distinct connections for racing commands. */
internal abstract class ClubOnboardingContract : ClubOnboardingTestBase() {
    @Test
    fun `private club uses immutable approved snapshot and grants only its author exact scopes`() =
        runBlocking<Unit> {
            val approved = approved()
            val before = fixture.rows("SELECT submitted_snapshot,submitted_hash FROM client_club_applications")
            val profile = create(approved)
            val club = fixture.rows("SELECT * FROM clubs WHERE id=${profile.id("id")}").single()
            assertFalse(club.active())
            assertEquals("Synthetic venue", profile.string("name"))
            assertEquals("Synthetic city", profile.string("city"))
            assertEquals("Synthetic street 1", profile.string("address"))
            assertEquals("Europe/Moscow", profile.string("timezone"))
            assertEquals("1", profile.string("version"))
            assertEquals(fixture.clock.instant().toString(), profile.string("savedAt"))
            val association = fixture.rows("SELECT * FROM client_club_onboarding").single()
            assertEquals(approved.id("id"), association.long("application_id"))
            assertEquals(approved.id("authorUserId"), association.long("owner_user_id"))
            assertEquals(approved.id("clientId"), association.long("client_id"))
            val grants = fixture.rows("SELECT * FROM client_club_grants ORDER BY permission_code")
            assertEquals(setOf(READ_CLUB, WRITE_PROFILE, WRITE_LAYOUT), grants.map { it["permission_code"] }.toSet())
            assertTrue(grants.all { it.active() && it.long("user_id") == approved.id("authorUserId") })
            assertEquals(fixture.baseline["user_roles"], fixture.rows("SELECT * FROM user_roles ORDER BY id"))
            val app = fixture.repository.read(fixture.alice, OnboardingRead.CLIENT_APPLICATION_GET, approved.id("id"))
            assertEquals(profile["id"], app["createdClubId"])
            assertEquals(JsonArray(listOf(JsonPrimitive("openClub"))), app["availableActions"])
            assertEquals(before, fixture.rows("SELECT submitted_snapshot,submitted_hash FROM client_club_applications"))
        }

    @Test
    fun `draft submitted reviewing rejected cancelled and missing approval cannot create clubs`() =
        runBlocking<Unit> {
            val client = fixture.accept()
            val draft = fixture.draft(client.id("clientId"))
            expect("invalid_transition") { create(draft) }
            val submitted = fixture.mutate(fixture.alice, OnboardingMutation.APPLICATION_SUBMIT, draft)
            expect("invalid_transition") { create(submitted) }
            val reviewed = fixture.mutate(fixture.owner, OnboardingMutation.APPLICATION_REVIEW, submitted)
            expect("invalid_transition") { create(reviewed) }
            val rejected = fixture.mutate(fixture.owner, OnboardingMutation.APPLICATION_REJECT, reviewed, reason())
            expect("invalid_transition") { create(rejected) }
            val second = fixture.draft(client.id("clientId"), venue = "Second synthetic venue")
            val cancelled = fixture.mutate(fixture.alice, OnboardingMutation.APPLICATION_CANCEL, second)
            expect("invalid_transition") { create(cancelled) }
            expect("not_found") {
                fixture.repository.mutate(
                    fixture.alice,
                    OnboardingMutation.APPLICATION_CREATE_CLUB,
                    Long.MAX_VALUE,
                    version(draft),
                    "missing-approval",
                )
            }
            assertEquals(
                fixture.baseline
                    .getValue("clubs")
                    .size
                    .toLong(),
                fixture.count("clubs"),
            )
            assertEquals(0, fixture.count("client_club_onboarding"))
        }

    @Test
    fun `other client other author inactive membership and revoked manage cannot consume approval`() =
        runBlocking<Unit> {
            val approved = approved()
            fixture.accept(fixture.bob)
            expect("not_found") { create(approved, actor = fixture.bob) }
            val bobId =
                fixture
                    .rows(
                        "SELECT id FROM users WHERE telegram_user_id=${fixture.bob.telegramUserId}",
                    ).single()
                    .long("id")
            val clientId = approved.id("clientId")
            fixture.execute(
                "INSERT INTO client_memberships(client_id,user_id,is_active) VALUES ($clientId,$bobId,TRUE)",
            )
            fixture.execute(
                "INSERT INTO client_membership_grants(client_id,user_id,permission_code,is_active) " +
                    "VALUES ($clientId,$bobId,'$MANAGE',TRUE)",
            )
            expect("not_found") { create(approved, actor = fixture.bob) }
            fixture.execute("UPDATE client_memberships SET is_active=FALSE WHERE client_id=$clientId")
            expect("not_found") { create(approved) }
            fixture.execute("UPDATE client_memberships SET is_active=TRUE WHERE client_id=$clientId")
            fixture.execute("UPDATE client_membership_grants SET is_active=FALSE WHERE client_id=$clientId")
            expect("not_found") { create(approved) }
            fixture.execute("UPDATE client_membership_grants SET is_active=TRUE WHERE client_id=$clientId")
            fixture.execute("UPDATE clients SET is_active=FALSE WHERE id=$clientId")
            expect("not_found") { create(approved) }
            assertEquals(0, fixture.count("client_club_onboarding"))
        }

    @Test
    fun `concurrent create same and different commands return one club and one audit event`() =
        runBlocking<Unit> {
            val approved = approved()
            val profiles = java.util.concurrent.ConcurrentLinkedQueue<JsonObject>()
            assertEquals(
                listOf("ok", "ok"),
                race(
                    { profiles += create(approved, key = "parallel-create-first") },
                    { profiles += create(approved, key = "parallel-create-second") },
                ),
            )
            assertEquals(1, profiles.map { it.id("id") }.distinct().size)
            assertEquals(
                listOf("ok", "ok"),
                race(
                    { profiles += create(approved, key = "parallel-create-same") },
                    { profiles += create(approved, key = "parallel-create-same") },
                ),
            )
            assertEquals(1, fixture.count("client_club_onboarding"))
            assertEquals(1, fixture.rows("SELECT * FROM audit_log WHERE action='client.club.create'").size)
            assertEquals(3, fixture.count("client_club_grants"))
        }

    @Test
    fun `lost create response replay checks current authority and changed payload never creates intent`() =
        runBlocking<Unit> {
            val approved = approved()
            val first = create(approved, key = "lost-create-response")
            assertEquals(first, create(approved, key = "lost-create-response"))
            assertEquals(first, create(approved, key = "explicit-repeat-key"))
            expect("idempotency_conflict") {
                fixture.repository.mutate(
                    fixture.alice,
                    OnboardingMutation.APPLICATION_CREATE_CLUB,
                    approved.id("id"),
                    buildJsonObject { put("expectedVersion", "999") },
                    "lost-create-response",
                )
            }
            expect("version_conflict") {
                fixture.repository.mutate(
                    fixture.alice,
                    OnboardingMutation.APPLICATION_CREATE_CLUB,
                    approved.id("id"),
                    buildJsonObject { put("expectedVersion", "999") },
                    "wrong-create-version",
                )
            }
            fixture.execute("UPDATE client_membership_grants SET is_active=FALSE")
            expect("not_found") { create(approved, key = "lost-create-response") }
            assertEquals(1, fixture.count("client_club_onboarding"))
        }

    @Test
    fun `cancel against create has one winner and consumed approval remains reserved`() =
        runBlocking<Unit> {
            val approved = approved()
            val outcomes =
                race(
                    { create(approved) },
                    {
                        fixture.mutate(
                            fixture.owner,
                            OnboardingMutation.PLATFORM_APPLICATION_CANCEL,
                            approved,
                            reason(),
                        )
                    },
                )
            assertEquals(listOf("ok", "version_conflict"), outcomes.sorted())
            val consumed = fixture.count("client_club_onboarding") == 1L
            val application =
                fixture.repository.read(
                    fixture.owner,
                    OnboardingRead.PLATFORM_APPLICATION_GET,
                    approved.id("id"),
                )
            if (consumed) {
                expect("invalid_transition") {
                    fixture.mutate(fixture.owner, OnboardingMutation.PLATFORM_APPLICATION_CANCEL, application, reason())
                }
                val duplicate = fixture.draft(approved.id("clientId"))
                expect(
                    "duplicate_application",
                ) { fixture.mutate(fixture.alice, OnboardingMutation.APPLICATION_SUBMIT, duplicate) }
                assertEquals("approved", application.string("status"))
            } else {
                assertEquals("cancelled", application.string("status"))
                expect("invalid_transition") { create(application) }
            }
        }

    @Test
    fun `membership revoke serializes with create without partial or restored grants`() =
        runBlocking<Unit> {
            val approved = approved()
            val outcomes = race({ create(approved) }, { revoke(approved) })
            assertTrue(outcomes == listOf("ok", "ok") || outcomes == listOf("not_found", "ok"))
            assertTrue(fixture.rows("SELECT * FROM client_club_grants").all { !it.active() })
            assertFalse(fixture.rows("SELECT * FROM client_memberships").single().active())
            expect("not_found") { create(approved) }
            assertEquals(fixture.count("client_club_onboarding"), fixture.count("client_clubs"))
        }

    @Test
    fun `failure after every create statement rolls back clubs association consumption grants audit and command`() =
        runBlocking<Unit> {
            val approved = approved()
            val before = state()
            listOf(
                "INSERT INTO clubs(" to 1,
                "INSERT INTO client_clubs(" to 1,
                "INSERT INTO client_club_onboarding(" to 1,
                "INSERT INTO client_club_grants(" to 1,
                "INSERT INTO client_club_grants(" to 2,
                "UPDATE client_club_applications SET version=" to 1,
                "INSERT INTO audit_log" to 1,
                "INSERT INTO client_commands" to 1,
            ).forEachIndexed { index, (prefix, occurrence) ->
                try {
                    fixture.failingAfterRepository(prefix, occurrence).mutate(
                        fixture.alice,
                        OnboardingMutation.APPLICATION_CREATE_CLUB,
                        approved.id("id"),
                        version(approved),
                        "create-fault-$index",
                    )
                    error("Expected synthetic failure after $prefix")
                } catch (_: SQLException) {
                    assertEquals(before, state(), "Rollback after $prefix occurrence $occurrence")
                }
            }
            try {
                fixture.failingAuditRepository().mutate(
                    fixture.alice,
                    OnboardingMutation.APPLICATION_CREATE_CLUB,
                    approved.id("id"),
                    version(approved),
                    "required-audit-fault",
                )
                error("Expected mandatory audit failure")
            } catch (_: SQLException) {
                assertEquals(before, state())
            }
            create(approved)
            assertEquals(1, fixture.count("client_club_onboarding"))
        }

    @Test
    fun `consumption cannot be removed or reassigned and composite ownership status keys reject forgery`() =
        runBlocking<Unit> {
            val approved = approved()
            val profile = create(approved)
            val other = fixture.accept(fixture.bob)
            val otherApplication = fixture.approved(other.id("clientId"), fixture.bob)
            val before = state()
            val appId = approved.id("id")
            val clubId = profile.id("id")
            listOf(
                "DELETE FROM client_club_onboarding WHERE application_id=$appId",
                "DELETE FROM client_clubs WHERE club_id=$clubId",
                "DELETE FROM clubs WHERE id=$clubId",
                "UPDATE client_clubs SET client_id=${other.id("clientId")} WHERE club_id=$clubId",
                "UPDATE client_club_onboarding SET client_id=${other.id("clientId")} WHERE application_id=$appId",
                "UPDATE client_club_onboarding SET owner_user_id=${otherApplication.id(
                    "authorUserId",
                )} WHERE application_id=$appId",
                "UPDATE client_club_applications SET status='cancelled',reserved_fingerprint=NULL WHERE id=$appId",
                "UPDATE client_club_applications SET approved_snapshot_hash='${"f".repeat(
                    64,
                )}',submitted_hash='${"f".repeat(64)}' WHERE id=$appId",
                "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active) VALUES " +
                    "(${other.id("clientId")},${otherApplication.id("authorUserId")},$clubId,'$WRITE_PROFILE',TRUE)",
            ).forEach { statement ->
                assertThrows(SQLException::class.java) { fixture.execute(statement) }
                assertEquals(before, state())
            }
            fixture.execute("INSERT INTO clubs(id,name,timezone) VALUES (63999,'Synthetic unmapped','UTC')")
            assertThrows(SQLException::class.java) {
                fixture.execute(
                    "INSERT INTO client_club_onboarding(application_id,client_id,owner_user_id,club_id," +
                        "approved_snapshot_hash,approved_snapshot_version,create_application_version) SELECT " +
                        "id,client_id,${approved.id(
                            "authorUserId",
                        )},63999,approved_snapshot_hash,approved_snapshot_version,version " +
                        "FROM client_club_applications WHERE id=${otherApplication.id("id")}",
                )
            }
        }

    @Test
    fun `association composite FK rejects valid members and mapped clubs of the wrong approval`() =
        runBlocking<Unit> {
            val approved = approved()
            val other = fixture.accept(fixture.bob)
            val otherUser =
                fixture
                    .rows(
                        "SELECT id FROM users WHERE telegram_user_id=${fixture.bob.telegramUserId}",
                    ).single()
                    .long("id")
            val clientId = approved.id("clientId")
            val authorId = approved.id("authorUserId")
            val otherClient = other.id("clientId")
            fixture.execute(
                "INSERT INTO client_memberships(client_id,user_id,is_active) " +
                    "VALUES ($clientId,$otherUser,TRUE),($otherClient,$authorId,TRUE)",
            )
            fixture.execute(
                "INSERT INTO clubs(id,name,timezone) VALUES (63991,'Synthetic 1','UTC'),(63992,'Synthetic 2','UTC')",
            )
            fixture.execute("INSERT INTO client_clubs(client_id,club_id) VALUES ($clientId,63991),($otherClient,63992)")
            val substitutions = listOf(Triple(clientId, otherUser, 63991), Triple(otherClient, authorId, 63992))
            substitutions.forEach { (client, owner, club) ->
                assertThrows(SQLException::class.java) {
                    fixture.execute(
                        "INSERT INTO client_club_onboarding(application_id,client_id,owner_user_id,club_id," +
                            "approved_snapshot_hash,approved_snapshot_version,create_application_version) SELECT " +
                            "id,$client,$owner,$club,approved_snapshot_hash,approved_snapshot_version,version " +
                            "FROM client_club_applications WHERE id=${approved.id("id")}",
                    )
                }
            }
            assertEquals(0, fixture.count("client_club_onboarding"))
        }

    @Test
    fun `V062 upgrade preserves existing legacy and client rows without automatic mappings`() {
        newFixture("62").use { old ->
            val clubs = old.rows("SELECT * FROM clubs ORDER BY id")
            val users = old.rows("SELECT * FROM users ORDER BY id")
            old.migrate()
            assertEquals(clubs, old.rows("SELECT * FROM clubs ORDER BY id"))
            assertEquals(users, old.rows("SELECT * FROM users ORDER BY id"))
            assertEquals(0, old.count("client_club_onboarding"))
            assertEquals(0, old.count("client_clubs"))
        }
    }
}
