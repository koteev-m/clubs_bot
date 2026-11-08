package com.example.bot.data.client.onboarding

import com.example.bot.data.client.ClientAudience
import com.example.bot.data.client.JdbcClientContextRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
import java.util.UUID

/** This same contract is inherited by H2 and PostgreSQL 16, including racing commands. */
internal abstract class ClubStaffContract : ClubFinanceContract() {
    @Test
    fun `staff acceptance issues only fixed club grants persists after reconnect and leaves legacy roles unchanged`() =
        runBlocking<Unit> {
            val club = create(approved()).id("id")
            val before = fixture.rows("SELECT * FROM user_roles ORDER BY id")
            val issued = invite(club, "MANAGER")
            assertTrue(issued.string("token") != null)
            val pending = claim(issued)
            assertEquals(0, fixture.rows("SELECT * FROM client_memberships WHERE user_id=${user(fixture.bob)}").size)
            val accepted = confirm(club, pending)
            assertEquals(1, accepted.items("members").count { it.string("role") == "MANAGER" })
            val context =
                JdbcClientContextRepository(
                    fixture.source,
                ).context(fixture.bob.telegramUserId, ClientAudience.Platform)
            assertEquals(listOf(club), context.flatMap { it.clubs }.map { it.id })
            val reopened = JdbcClientOnboardingRepository(fixture.source, fixture.owner.telegramUserId, fixture.clock)
            assertEquals(
                club.toString(),
                reopened.read(fixture.bob, OnboardingRead.CLUB_PROFILE_GET, club).string("id"),
            )
            assertEquals(staffPackage("MANAGER").sorted(), grants(club, fixture.bob))
            assertEquals(
                staffPackage(
                    "MANAGER",
                ).sorted(),
                reopened
                    .read(
                        fixture.bob,
                        OnboardingRead.CLUB_PROFILE_GET,
                        club,
                    ).getValue("permissions")
                    .jsonArray
                    .map {
                        it.toString().trim('"')
                    }.sorted(),
            )
            assertEquals(
                0,
                fixture.rows("SELECT * FROM client_membership_grants WHERE user_id=${user(fixture.bob)}").size,
            )
            assertEquals(before, fixture.rows("SELECT * FROM user_roles ORDER BY id"))
            expect("not_found") { fixture.repository.read(fixture.bob, OnboardingRead.CLUB_TEAM_GET, club) }
            expect("not_found") { fixture.repository.read(fixture.owner, OnboardingRead.CLUB_TEAM_GET, club) }
            expect("not_found") { fixture.repository.read(fixture.bob, OnboardingRead.CLUB_FINANCE_GET, club) }
        }

    @Test
    fun `staff role change disable and fresh invitation never restore a previous larger package`() =
        runBlocking<Unit> {
            val club = create(approved()).id("id")
            confirm(club, claim(invite(club, "CLUB_ADMIN")))
            assertEquals(staffPackage("CLUB_ADMIN").sorted(), grants(club, fixture.bob))
            change(club, "change_role", "MANAGER")
            assertEquals(staffPackage("MANAGER").sorted(), grants(club, fixture.bob))
            val oldInvite = team(club).items("invitations").first()
            val preRevocationInvite = invite(club, "CLUB_ADMIN")
            change(club, "disable")
            assertTrue(grants(club, fixture.bob).isEmpty())
            expect("not_found") { claim(preRevocationInvite) }
            assertTrue(
                fixture.rows("SELECT * FROM client_memberships WHERE user_id=${user(fixture.bob)}").single().active(),
            )
            expect("not_found") { fixture.repository.read(fixture.bob, OnboardingRead.CLUB_PROFILE_GET, club) }
            expect(
                "not_found",
            ) { fixture.repository.read(fixture.bob, OnboardingRead.INVITATION_STATUS, oldInvite.id("id")) }
            expect("invalid_transition") { change(club, "change_role", "CLUB_ADMIN") }
            confirm(club, claim(invite(club, "ENTRY_MANAGER")))
            assertEquals(listOf(READ_CLUB), grants(club, fixture.bob))
            expect(
                "not_found",
            ) { fixture.repository.read(fixture.bob, OnboardingRead.INVITATION_STATUS, oldInvite.id("id")) }
            assertEquals(0, fixture.rows("SELECT * FROM user_roles WHERE user_id=${user(fixture.bob)}").size)
        }

    @Test
    fun `staff access cannot cross clubs clients invitation ids or grant team management to itself`() =
        runBlocking<Unit> {
            val a = create(approved()).id("id")
            val aClient = team(a).id("clientId")
            fixture.execute("UPDATE clients SET allowed_clubs=3 WHERE id=$aClient")
            val a2 = create(fixture.approved(aClient, venue = "Second synthetic venue")).id("id")
            val foreign =
                create(
                    fixture.approved(fixture.accept(fixture.bob).id("clientId"), fixture.bob),
                    fixture.bob,
                ).id("id")
            val inviteA = invite(a, "ENTRY_MANAGER")
            val claimA = claim(inviteA)
            val wrong = ownerBody(a2, "confirm", claimA)
            expect("not_found") { updateTeam(a2, wrong) }
            confirm(a, claimA)
            expect("not_found") { fixture.repository.read(fixture.bob, OnboardingRead.CLUB_PROFILE_GET, a2) }
            expect("not_found") { fixture.repository.read(fixture.alice, OnboardingRead.CLUB_PROFILE_GET, foreign) }
            expect("not_found") {
                fixture.repository.mutate(
                    fixture.bob,
                    OnboardingMutation.CLUB_TEAM_UPDATE,
                    a,
                    buildJsonObject {
                        put("action", "invite")
                        put("expectedVersion", team(a).getValue("version"))
                        put("role", "CLUB_ADMIN")
                        put("recipientLabel", "Spoof")
                    },
                    UUID.randomUUID().toString(),
                )
            }
            val before = grants(a, fixture.alice)
            val self =
                buildJsonObject {
                    put("action", "disable")
                    put("expectedVersion", team(a).getValue("version"))
                    put("userId", user(fixture.alice).toString())
                    put("expectedMemberVersion", "1")
                    put("confirmed", true)
                    put("reason", "Self")
                }
            expect("forbidden") { updateTeam(a, self) }
            assertEquals(before, grants(a, fixture.alice))
        }

    @Test
    fun `invite issue and confirmation recheck the entire issuers package with no partial grants`() =
        runBlocking<Unit> {
            val club = create(approved()).id("id")
            val issued = invite(club, "CLUB_ADMIN")
            val pending = claim(issued)
            fixture.execute(
                "UPDATE client_club_grants SET is_active=FALSE WHERE club_id=$club AND permission_code='$WRITE_FINANCE'",
            )
            expect("forbidden") { invite(club, "CLUB_ADMIN") }
            expect("forbidden") { confirm(club, pending) }
            assertTrue(grants(club, fixture.bob).isEmpty())
            assertEquals(
                "claimed",
                fixture.rows("SELECT status FROM client_invitations WHERE id=${pending.id("id")}").single()["status"],
            )
            assertEquals(0, fixture.rows("SELECT * FROM client_club_staff").size)
        }

    @Test
    fun `staff secret is returned once and command replay never reissues revoked access`() =
        runBlocking<Unit> {
            val club = create(approved()).id("id")
            val body = inviteBody(club, "ENTRY_MANAGER")
            val issued = updateTeam(club, body, "staff-issued-once")
            val replay = updateTeam(club, body, "staff-issued-once")
            assertFalse(replay.containsKey("token"))
            assertEquals(issued["createdInvitationId"], replay["createdInvitationId"])
            val pending = claim(issued)
            val confirmBody = ownerBody(club, "confirm", pending)
            updateTeam(club, confirmBody, "staff-confirm-once")
            change(club, "disable")
            val current = updateTeam(club, confirmBody, "staff-confirm-once")
            assertFalse(
                current
                    .items("members")
                    .single { it.string("role") != null }
                    .getValue("active")
                    .toString()
                    .toBoolean(),
            )
            assertTrue(grants(club, fixture.bob).isEmpty())
            assertEquals(1, fixture.rows("SELECT * FROM audit_log WHERE action='client.staff.invitation.confirm'").size)
            assertFalse(
                fixture.rows("SELECT metadata_json FROM audit_log").toString().contains(issued.string("token")!!),
            )
        }

    @Test
    fun `expiration revocation and wrong account cannot claim or confirm staff invitations`() =
        runBlocking<Unit> {
            val club = create(approved()).id("id")
            val issued = invite(club, "ENTRY_MANAGER")
            val pending = claim(issued)
            expect("not_found") { claim(issued, OnboardingActor(880062099, "platform")) }
            fixture.clock.current = fixture.clock.current.plus(Duration.ofDays(7))
            expect("invalid_transition") { confirm(club, pending) }
            assertEquals(
                "expired",
                fixture.repository
                    .read(
                        fixture.bob,
                        OnboardingRead.INVITATION_STATUS,
                        pending.id("id"),
                    ).string("status"),
            )
            val second = invite(club, "MANAGER")
            val invitation = team(club).items("invitations").single { it.id("id") == second.id("createdInvitationId") }
            updateTeam(club, ownerBody(club, "revoke_invitation", invitation))
            expect("not_found") { claim(second) }
            assertTrue(grants(club, fixture.bob).isEmpty())
        }

    @Test
    fun `concurrent claims bind one identity and concurrent confirmations issue once`() =
        runBlocking<Unit> {
            val club = create(approved()).id("id")
            val issued = invite(club, "MANAGER")
            val actors = listOf(fixture.bob, OnboardingActor(880062098, "platform"))
            val results =
                coroutineScope {
                    actors
                        .map { actor ->
                            async { runCatching { claim(issued, actor) } }
                        }.awaitAll()
                }
            assertEquals(1, results.count { it.isSuccess })
            val pending = results.single { it.isSuccess }.getOrThrow()
            val body = ownerBody(club, "confirm", pending)
            val confirms = coroutineScope { (1..2).map { async { runCatching { updateTeam(club, body) } } }.awaitAll() }
            assertEquals(1, confirms.count { it.isSuccess })
            assertEquals(1, fixture.count("client_club_staff"))
            assertEquals(1, fixture.rows("SELECT * FROM audit_log WHERE action='client.staff.invitation.confirm'").size)
        }

    @Test
    fun `audit and durable command failures roll back invitation membership grants and assignment`() =
        runBlocking<Unit> {
            val club = create(approved()).id("id")
            val pending = claim(invite(club, "CLUB_ADMIN"))
            val body = ownerBody(club, "confirm", pending)
            val before =
                listOf(
                    "client_invitations",
                    "client_memberships",
                    "client_club_grants",
                    "client_club_staff",
                    "client_commands",
                    "audit_log",
                ).associateWith { fixture.rows("SELECT * FROM $it ORDER BY 1,2") }
            for (repository in listOf(
                fixture.failingAuditRepository(),
                fixture.failingAfterRepository("INSERT INTO client_commands"),
            )) {
                try {
                    repository.mutate(
                        fixture.alice,
                        OnboardingMutation.CLUB_TEAM_UPDATE,
                        club,
                        body,
                        UUID.randomUUID().toString(),
                    )
                    error("Expected atomic failure")
                } catch (_: SQLException) {
                    // synthetic failure must roll back
                }
                assertEquals(before, before.keys.associateWith { fixture.rows("SELECT * FROM $it ORDER BY 1,2") })
            }
        }

    @Test
    fun `platform revoked membership cannot be revived by staff invitation or platform grant restoration`() =
        runBlocking<Unit> {
            val club = create(approved()).id("id")
            confirm(club, claim(invite(club, "MANAGER")))
            val target = user(fixture.bob)
            fixture.repository.mutate(
                fixture.owner,
                OnboardingMutation.MEMBER_REVOKE,
                team(club).id("clientId"),
                buildJsonObject {
                    put("userId", target.toString())
                    put("expectedVersion", "1")
                },
                UUID.randomUUID().toString(),
            )
            val pending = claim(invite(club, "ENTRY_MANAGER"))
            expect("invalid_transition") { confirm(club, pending) }
            val restore =
                buildJsonObject {
                    put("userId", target.toString())
                    put("expectedVersion", "2")
                    put("permissions", JsonArray(emptyList()))
                    put(
                        "clubGrants",
                        JsonArray(
                            listOf(
                                buildJsonObject {
                                    put("clubId", club.toString())
                                    put("permission", READ_CLUB)
                                },
                            ),
                        ),
                    )
                }
            expect("invalid_transition") {
                fixture.repository.mutate(
                    fixture.owner,
                    OnboardingMutation.MEMBER_RESTORE,
                    team(club).id("clientId"),
                    restore,
                    UUID.randomUUID().toString(),
                )
            }
            assertTrue(grants(club, fixture.bob).isEmpty())
        }

    @Test
    fun `V075 upgrades only active immutable creators and preserves grant tombstones`() {
        newFixture("74").use { old ->
            for (id in 75001L..75005L) {
                old.execute("INSERT INTO users(id,telegram_user_id) VALUES ($id,${880000000L + id})")
                old.execute(
                    "INSERT INTO clients(id,display_name,is_active) VALUES ($id,'Synthetic upgrade',$id<>75004)",
                )
                old.execute("INSERT INTO client_memberships(client_id,user_id,is_active) VALUES ($id,$id,$id<>75002)")
                old.execute(
                    "INSERT INTO clubs(id,name,timezone,is_active) VALUES ($id,'Synthetic upgrade','Europe/Moscow',$id=75005)",
                )
                old.execute("INSERT INTO client_clubs(client_id,club_id) VALUES ($id,$id)")
                old.execute(
                    "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active) VALUES ($id,$id,$id,'$READ_CLUB',$id<>75003)",
                )
                val fingerprint = digest("staff-upgrade-$id")
                val legacy = digest("staff-legacy-$id")
                old.execute(
                    "INSERT INTO client_direct_club_profiles(club_id,client_id,owner_user_id," +
                        "venue_fingerprint,country_code,city,address,legacy_fingerprint) " +
                        "VALUES ($id,$id,$id,'$fingerprint','RU','Synthetic','Synthetic','$legacy')",
                )
            }
            val before = old.rows("SELECT * FROM client_direct_club_profiles ORDER BY club_id")
            old.migrate()
            assertEquals(before, old.rows("SELECT * FROM client_direct_club_profiles ORDER BY club_id"))
            assertEquals(
                listOf(
                    "75001",
                ),
                old
                    .rows(
                        "SELECT user_id FROM client_club_grants WHERE permission_code='$MANAGE_TEAM' AND is_active=TRUE",
                    ).map {
                        it["user_id"]
                    },
            )
            old.execute("UPDATE client_club_grants SET is_active=FALSE WHERE permission_code='$MANAGE_TEAM'")
            val migration =
                checkNotNull(
                    javaClass.classLoader.getResource("db/migration/${old.vendor}/V075__client_club_staff.sql"),
                ).readText()
            migration
                .substring(
                    migration.indexOf("-- Bounded upgrade"),
                ).split(';')
                .filter { it.isNotBlank() }
                .forEach(old::execute)
            assertTrue(
                old
                    .rows(
                        "SELECT * FROM client_club_grants WHERE permission_code='$MANAGE_TEAM' AND is_active=TRUE",
                    ).isEmpty(),
            )
            assertEquals(1, old.rows("SELECT * FROM audit_log WHERE action='client.club.team.manage.bootstrap'").size)
        }
    }

    @Test
    fun `staff schema preserves client invitation uniqueness and rejects cross club assignments`() =
        runBlocking<Unit> {
            val club = create(approved()).id("id")
            val first = invite(club, "ENTRY_MANAGER")
            invite(club, "MANAGER")
            assertEquals(2, team(club).items("invitations").size)
            val pending = claim(first)
            confirm(club, pending)
            val target = user(fixture.bob)
            val clientId = team(club).id("clientId")
            for (statement in listOf(
                "UPDATE client_club_staff SET club_id=999999 WHERE user_id=$target",
                "UPDATE client_club_staff SET role_code='OWNER' WHERE user_id=$target",
                "UPDATE client_invitations SET acceptance_policy='recipient_acceptance' WHERE id=${pending.id("id")}",
                "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active) VALUES ($clientId,$target,$club,'support.view',TRUE)",
            )) {
                try {
                    fixture.execute(statement)
                    error("Expected constraint rejection")
                } catch (
                    _: SQLException,
                ) {
                    // DB boundary
                }
            }
        }

    private suspend fun team(club: Long) = fixture.repository.read(fixture.alice, OnboardingRead.CLUB_TEAM_GET, club)

    private fun user(actor: OnboardingActor) =
        fixture.rows("SELECT id FROM users WHERE telegram_user_id=${actor.telegramUserId}").single().long("id")

    private fun grants(
        club: Long,
        actor: OnboardingActor,
    ) = fixture
        .rows(
            "SELECT permission_code FROM client_club_grants WHERE club_id=$club AND user_id=${user(
                actor,
            )} AND is_active=TRUE ORDER BY permission_code",
        ).map {
            it["permission_code"]
        }

    private fun JsonObject.items(key: String) = getValue(key).jsonArray.map { it.jsonObject }

    private suspend fun inviteBody(
        club: Long,
        role: String,
    ) = buildJsonObject {
        put("action", "invite")
        put("expectedVersion", team(club).getValue("version"))
        put("role", role)
        put("recipientLabel", "Synthetic colleague")
    }

    private suspend fun invite(
        club: Long,
        role: String,
    ) = updateTeam(club, inviteBody(club, role))

    private suspend fun updateTeam(
        club: Long,
        body: JsonObject,
        key: String = UUID.randomUUID().toString(),
    ) = fixture.repository.mutate(fixture.alice, OnboardingMutation.CLUB_TEAM_UPDATE, club, body, key)

    private suspend fun claim(
        issued: JsonObject,
        actor: OnboardingActor = fixture.bob,
    ): JsonObject =
        fixture.repository.mutate(
            actor,
            OnboardingMutation.INVITATION_CLAIM,
            issued.id("createdInvitationId"),
            buildJsonObject {
                put("token", issued.getValue("token"))
                put("expectedVersion", "1")
            },
            UUID.randomUUID().toString(),
        )

    private suspend fun ownerBody(
        club: Long,
        action: String,
        pending: JsonObject,
    ) = buildJsonObject {
        put("action", action)
        put("expectedVersion", team(club).getValue("version"))
        put("invitationId", pending.getValue("id"))
        put("expectedInvitationVersion", pending.getValue("version"))
        put(
            "verificationCode",
            pending["verificationCode"] ?: kotlinx.serialization.json.JsonNull,
        )
        put("confirmed", true)
    }

    private suspend fun confirm(
        club: Long,
        pending: JsonObject,
    ) = updateTeam(club, ownerBody(club, "confirm", pending))

    private suspend fun change(
        club: Long,
        action: String,
        role: String? = null,
    ): JsonObject {
        val view = team(club)
        val member = view.items("members").single { it.string("role") != null }
        return updateTeam(
            club,
            buildJsonObject {
                put("action", action)
                put("expectedVersion", view.getValue("version"))
                put("userId", member.getValue("userId"))
                put("expectedMemberVersion", member.getValue("version"))
                put("role", role)
                put("confirmed", true)
                put("reason", "Synthetic staff change")
            },
        )
    }
}
