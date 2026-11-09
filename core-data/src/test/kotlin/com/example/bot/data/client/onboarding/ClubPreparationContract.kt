package com.example.bot.data.client.onboarding

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.flywaydb.core.api.FlywayException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.util.UUID

/** Identical persisted task, isolation, rollback and concurrency checks run on H2 and PostgreSQL 16. */
internal abstract class ClubPreparationContract : ClubStaffContract() {
    @Test
    fun `preparation self later and owner review survive repository reopen without publishing club`() =
        runBlocking<Unit> {
            val club = create(approved()).id("id")
            assertEquals("unassigned", preparation(club).string("status"))
            command(club, "later")
            val started = command(club, "self")
            assertEquals("in_progress", started.string("status"))
            val submitted = command(club, "submit")
            assertEquals("submitted", submitted.string("status"))
            val reopened = JdbcClientOnboardingRepository(fixture.source, fixture.owner.telegramUserId, fixture.clock)
            assertEquals(submitted, reopened.read(fixture.alice, OnboardingRead.CLUB_PREPARATION_GET, club))
            val accepted = command(club, "accept")
            assertEquals("accepted", accepted.string("status"))
            fixture.execute("UPDATE clubs SET calendar_version=calendar_version+1 WHERE id=$club")
            assertTrue(preparation(club).getValue("canSubmit").toString().toBoolean())
            command(club, "submit")
            command(club, "accept")
            assertEquals("false", accepted.getValue("published").toString())
            assertFalse(fixture.rows("SELECT is_active FROM clubs WHERE id=$club").single().active())
        }

    @Test
    fun `preparation invitation and two step staff confirmation atomically assign only the intended club`() =
        runBlocking<Unit> {
            val club = create(approved()).id("id")
            val issued = invite(club)
            assertEquals("invitation_pending", issued.string("status"))
            assertEquals(0, fixture.count("client_club_staff"))
            val claimed = claim(issued)
            expect("not_found") { preparation(club, fixture.bob) }
            assertEquals("invitation_pending", preparation(club).string("status"))
            confirm(club, claimed)
            val working = preparation(club, fixture.bob)
            assertEquals("in_progress", working.string("status"))
            assertTrue(working.getValue("canSubmit").toString().toBoolean())
            assertFalse(working.getValue("canManage").toString().toBoolean())
            command(club, "submit", fixture.bob)
            command(club, "return", extra = buildJsonObject { put("reason", "Проверьте часы открытия") })
            assertEquals("Проверьте часы открытия", preparation(club, fixture.bob).string("reason"))
            command(club, "submit", fixture.bob)
            command(club, "accept")
            assertEquals("accepted", preparation(club, fixture.bob).string("status"))
            assertEquals(0, fixture.rows("SELECT * FROM user_roles WHERE user_id=${user(fixture.bob)}").size)
            assertTrue(
                fixture
                    .rows("SELECT * FROM client_club_grants WHERE user_id=${user(fixture.bob)}")
                    .all { it["club_id"] == club.toString() && it["permission_code"] in staffPackage("CLUB_ADMIN") },
            )
        }

    @Test
    fun `preparation invite rejects incomplete issuer package and replays without secrets or duplicate effects`() =
        runBlocking<Unit> {
            val club = create(approved()).id("id")
            val before = snapshot().withoutControlledGrantTouch(club)
            fixture.execute(
                "UPDATE client_club_grants SET is_active=FALSE WHERE club_id=$club AND permission_code='$WRITE_FINANCE'",
            )
            val denied = snapshot()
            expect("forbidden") { invite(club) }
            assertEquals(denied, snapshot())
            fixture.execute(
                "UPDATE client_club_grants SET is_active=TRUE WHERE club_id=$club AND permission_code='$WRITE_FINANCE'",
            )
            // PostgreSQL's existing touch trigger timestamps our explicit fixture reactivation.
            // The denied production operation above is still checked against an exact snapshot.
            assertEquals(before, snapshot().withoutControlledGrantTouch(club))
            val body = inviteBody(club)
            val issued = mutate(club, body, key = "preparation-invite-once")
            val replay = mutate(club, body, key = "preparation-invite-once")
            assertEquals("invitation_pending", replay.string("status"))
            assertFalse(replay.containsKey("token"))
            assertEquals(1, fixture.rows("SELECT * FROM client_invitations WHERE purpose='staff'").size)
            assertFalse(
                fixture.rows("SELECT metadata_json FROM audit_log").toString().contains(issued.string("token")!!),
            )
        }

    @Test
    fun `preparation revocation blocks new submit replay and old assignment after fresh staff readmission`() =
        runBlocking<Unit> {
            val club = create(approved()).id("id")
            confirm(club, claim(invite(club)))
            val submit = body(club, "submit", fixture.bob)
            mutate(club, submit, fixture.bob, "preparation-submit-once")
            val team = fixture.repository.read(fixture.alice, OnboardingRead.CLUB_TEAM_GET, club)
            val staff =
                team.getValue("members").jsonArray.map { it.jsonObject }.single {
                    it.string("role") ==
                        "CLUB_ADMIN"
                }
            fixture.repository.mutate(
                fixture.alice,
                OnboardingMutation.CLUB_TEAM_UPDATE,
                club,
                buildJsonObject {
                    put("action", "disable")
                    put("expectedVersion", team.getValue("version"))
                    put("expectedMemberVersion", staff.getValue("version"))
                    put("userId", staff.getValue("userId"))
                    put("confirmed", true)
                    put("reason", "Synthetic revoke")
                },
                UUID.randomUUID().toString(),
            )
            expect("not_found") { mutate(club, submit, fixture.bob, "preparation-submit-once") }
            expect("not_found") { preparation(club, fixture.bob) }
            val fresh = ordinaryInvite(club)
            confirm(club, claim(fresh))
            assertFalse(preparation(club, fixture.bob).getValue("canSubmit").toString().toBoolean())
            command(club, "return", extra = buildJsonObject { put("reason", "New admission needs assignment") })
            expect("not_found") { command(club, "submit", fixture.bob) }
            command(club, "assign", extra = buildJsonObject { put("userId", user(fixture.bob).toString()) })
            command(club, "submit", fixture.bob)
            val grants =
                fixture.rows(
                    "SELECT * FROM client_club_grants WHERE user_id=${user(fixture.bob)} ORDER BY permission_code",
                )
            command(club, "unassign")
            assertEquals(
                grants,
                fixture.rows(
                    "SELECT * FROM client_club_grants WHERE user_id=${user(fixture.bob)} ORDER BY permission_code",
                ),
            )
            assertFalse(preparation(club, fixture.bob).getValue("canSubmit").toString().toBoolean())
        }

    @Test
    fun `preparation current single grant revocation blocks assigned submit and accept without hiding allowed reads`() =
        runBlocking<Unit> {
            val club = create(approved()).id("id")
            confirm(club, claim(invite(club)))
            command(club, "submit", fixture.bob)
            val target = user(fixture.bob)
            fixture.execute(
                "UPDATE client_club_grants SET is_active=FALSE WHERE club_id=$club AND user_id=$target AND permission_code='$WRITE_CALENDAR'",
            )
            assertFalse(preparation(club, fixture.bob).getValue("canSubmit").toString().toBoolean())
            assertFalse(
                preparation(club)
                    .getValue("assignee")
                    .jsonObject
                    .getValue("active")
                    .toString()
                    .toBoolean(),
            )
            expect("invalid_transition") { command(club, "accept") }
            command(club, "return", extra = buildJsonObject { put("reason", "Access changed") })
            expect("not_found") { command(club, "submit", fixture.bob) }
            assertTrue(fixture.repository.read(fixture.bob, OnboardingRead.CLUB_PROFILE_GET, club).containsKey("name"))
        }

    @Test
    fun `preparation cannot cross client club or actor boundaries and readonly grants cannot operate task`() =
        runBlocking<Unit> {
            val club = create(approved()).id("id")
            val foreign =
                create(
                    fixture.approved(fixture.accept(fixture.bob).id("clientId"), fixture.bob),
                    fixture.bob,
                ).id("id")
            expect("not_found") { preparation(foreign) }
            expect("not_found") { command(foreign, "self") }
            expect("not_found") {
                command(club, "assign", extra = buildJsonObject { put("userId", user(fixture.bob).toString()) })
            }
            val issued = ordinaryInvite(club, "ENTRY_MANAGER")
            // Bob's separate client membership does not imply authority in this club.
            confirm(club, claim(issued))
            val view = preparation(club, fixture.bob)
            assertFalse(view.getValue("canManage").toString().toBoolean())
            assertFalse(view.getValue("canSubmit").toString().toBoolean())
            expect("not_found") { command(club, "self", fixture.bob) }
            expect("not_found") { command(club, "submit", fixture.bob) }
            expect("validation_error") {
                command(club, "self", extra = buildJsonObject { put("clientId", view.getValue("clientId")) })
            }
        }

    @Test
    fun `preparation submitted snapshot rejects stale acceptance and concurrent owner decisions commit only once`() =
        runBlocking<Unit> {
            val club = create(approved()).id("id")
            command(club, "self")
            command(club, "submit")
            fixture.execute("UPDATE clubs SET calendar_version=calendar_version+1 WHERE id=$club")
            assertTrue(preparation(club).getValue("configurationChanged").toString().toBoolean())
            expect("version_conflict") { command(club, "accept") }
            command(club, "submit")
            val accept = body(club, "accept")
            val returned =
                JsonObject(
                    accept +
                        buildJsonObject {
                            put("action", "return")
                            put("reason", "Concurrent review")
                        },
                )
            val results =
                coroutineScope {
                    listOf(
                        accept,
                        returned,
                    ).map { async { runCatching { mutate(club, it) } } }.awaitAll()
                }
            assertEquals(1, results.count { it.isSuccess })
            val failure = results.single { it.isFailure }.exceptionOrNull() as OnboardingFailure
            assertEquals("version_conflict", failure.code)
            assertEquals(
                1,
                fixture
                    .rows(
                        "SELECT * FROM audit_log WHERE action IN ('client.preparation.accept','client.preparation.return')",
                    ).size,
            )
        }

    @Test
    fun `preparation command and audit failure roll back task invitation and confirmation together`() =
        runBlocking<Unit> {
            val club = create(approved()).id("id")
            val invite = inviteBody(club)
            for (repository in listOf(
                fixture.failingAuditRepository(),
                fixture.failingAfterRepository("INSERT INTO client_commands"),
            )) {
                val before = snapshot()
                try {
                    repository.mutate(
                        fixture.alice,
                        OnboardingMutation.CLUB_PREPARATION_UPDATE,
                        club,
                        invite,
                        UUID.randomUUID().toString(),
                    )
                    error("Expected synthetic failure")
                } catch (_: SQLException) {
                    // The task, issued invitation, command, and audit must share the rollback.
                }
                assertEquals(before, snapshot())
            }
            val issued = invite(club)
            val pending = claim(issued)
            val confirmation = confirmationBody(club, pending)
            val before = snapshot()
            try {
                fixture.failingAfterRepository("UPDATE client_club_preparation SET").mutate(
                    fixture.alice,
                    OnboardingMutation.CLUB_TEAM_UPDATE,
                    club,
                    confirmation,
                    UUID.randomUUID().toString(),
                )
                error("Expected task-hook failure")
            } catch (_: SQLException) {
                // No staff access can survive a failed linked task transition.
            }
            assertEquals(before, snapshot())
        }

    @Test
    fun `preparation migration failure never reports success or mutates historical authority`() =
        runBlocking<Unit> {
            newFixture("75").use { old ->
                val client = old.accept().id("clientId")
                old.mutate(old.alice, OnboardingMutation.APPLICATION_CREATE_CLUB, old.approved(client))
                val tables =
                    listOf(
                        "client_club_grants",
                        "client_memberships",
                        "client_club_staff",
                        "client_invitations",
                        "clubs",
                        "audit_log",
                    )
                val before = tables.associateWith { old.rows("SELECT * FROM $it ORDER BY 1,2") }
                old.execute("CREATE TABLE client_club_preparation (synthetic_conflict BIGINT)")
                assertThrows(FlywayException::class.java) { old.migrate() }
                assertEquals(before, tables.associateWith { old.rows("SELECT * FROM $it ORDER BY 1,2") })
                assertFalse(
                    old.rows("SELECT version FROM flyway_schema_history WHERE success=TRUE").any {
                        it["version"]?.toIntOrNull() ==
                            76
                    },
                )
            }
        }

    @Test
    fun `preparation migration upgrades without changing grants and constrains task state and tenant ids`() =
        runBlocking<Unit> {
            newFixture("75").use { old ->
                val client = old.accept().id("clientId")
                val club =
                    old
                        .mutate(
                            old.alice,
                            OnboardingMutation.APPLICATION_CREATE_CLUB,
                            old.approved(client),
                        ).id("id")
                val grants = old.rows("SELECT * FROM client_club_grants ORDER BY permission_code")
                old.migrate()
                assertEquals(grants, old.rows("SELECT * FROM client_club_grants ORDER BY permission_code"))
                old.execute("INSERT INTO client_club_preparation(client_id,club_id) VALUES ($client,$club)")
                for (statement in listOf(
                    "UPDATE client_club_preparation SET client_id=999999 WHERE club_id=$club",
                    "UPDATE client_club_preparation SET status='accepted' WHERE club_id=$club",
                    "UPDATE client_club_preparation SET status='invitation_pending' WHERE club_id=$club",
                    "UPDATE client_club_preparation SET version=0 WHERE club_id=$club",
                )) {
                    try {
                        old.execute(statement)
                        error("Expected task DB constraint rejection")
                    } catch (_: SQLException) {
                        // Both vendors must reject malformed states without touching grants.
                    }
                }
            }
        }

    private suspend fun preparation(
        club: Long,
        actor: OnboardingActor = fixture.alice,
    ) = fixture.repository.read(actor, OnboardingRead.CLUB_PREPARATION_GET, club)

    private suspend fun body(
        club: Long,
        action: String,
        actor: OnboardingActor = fixture.alice,
    ) = buildJsonObject {
        put("action", action)
        put("expectedVersion", preparation(club, actor).getValue("version"))
    }

    private suspend fun command(
        club: Long,
        action: String,
        actor: OnboardingActor = fixture.alice,
        extra: JsonObject = JsonObject(emptyMap()),
    ) = mutate(club, JsonObject(body(club, action, actor) + extra), actor)

    private suspend fun mutate(
        club: Long,
        body: JsonObject,
        actor: OnboardingActor = fixture.alice,
        key: String = UUID.randomUUID().toString(),
    ) = fixture.repository.mutate(actor, OnboardingMutation.CLUB_PREPARATION_UPDATE, club, body, key)

    private suspend fun inviteBody(club: Long) =
        JsonObject(
            body(club, "invite") +
                buildJsonObject {
                    put(
                        "expectedTeamVersion",
                        fixture.repository.read(fixture.alice, OnboardingRead.CLUB_TEAM_GET, club).getValue("version"),
                    )
                    put("recipientLabel", "Synthetic responsible")
                },
        )

    private suspend fun invite(club: Long) = mutate(club, inviteBody(club))

    private suspend fun ordinaryInvite(
        club: Long,
        role: String = "CLUB_ADMIN",
    ) = fixture.repository.mutate(
        fixture.alice,
        OnboardingMutation.CLUB_TEAM_UPDATE,
        club,
        buildJsonObject {
            put("action", "invite")
            put(
                "expectedVersion",
                fixture.repository.read(fixture.alice, OnboardingRead.CLUB_TEAM_GET, club).getValue("version"),
            )
            put("recipientLabel", "Synthetic staff")
            put("role", role)
        },
        UUID.randomUUID().toString(),
    )

    private suspend fun claim(issued: JsonObject) =
        fixture.repository.mutate(
            fixture.bob,
            OnboardingMutation.INVITATION_CLAIM,
            issued.id("createdInvitationId"),
            buildJsonObject {
                put("token", issued.getValue("token"))
                put("expectedVersion", "1")
            },
            UUID.randomUUID().toString(),
        )

    private suspend fun confirmationBody(
        club: Long,
        pending: JsonObject,
    ) = buildJsonObject {
        put("action", "confirm")
        put(
            "expectedVersion",
            fixture.repository.read(fixture.alice, OnboardingRead.CLUB_TEAM_GET, club).getValue("version"),
        )
        put("invitationId", pending.getValue("id"))
        put("expectedInvitationVersion", pending.getValue("version"))
        put("verificationCode", pending.getValue("verificationCode"))
        put("confirmed", true)
    }

    private suspend fun confirm(
        club: Long,
        pending: JsonObject,
    ) = fixture.repository.mutate(
        fixture.alice,
        OnboardingMutation.CLUB_TEAM_UPDATE,
        club,
        confirmationBody(club, pending),
        UUID.randomUUID().toString(),
    )

    private fun user(actor: OnboardingActor) =
        fixture.rows("SELECT id FROM users WHERE telegram_user_id=${actor.telegramUserId}").single().long("id")

    private fun Map<String, List<DbRow>>.withoutControlledGrantTouch(club: Long): Map<String, List<DbRow>> =
        mapValues { (table, rows) ->
            if (table == "client_club_grants") {
                rows.sortedBy { row -> row["user_id"] + ":" + row["permission_code"] }.map { row ->
                    if (row["club_id"] == club.toString() && row["permission_code"] == WRITE_FINANCE) {
                        row - "updated_at"
                    } else {
                        row
                    }
                }
            } else {
                rows
            }
        }

    private fun snapshot() =
        listOf(
            "client_club_preparation",
            "client_invitations",
            "client_club_staff",
            "client_club_grants",
            "client_memberships",
            "clubs",
            "client_commands",
            "audit_log",
        ).associateWith { fixture.rows("SELECT * FROM $it ORDER BY 1,2") }
}
