package com.example.bot.data.client.onboarding

import com.example.bot.data.client.assignments.AssignmentTestDatabase
import com.example.bot.data.promoter.PromoterAdminRepositoryImpl
import com.example.bot.promoter.admin.PromoterAccessUpdateResult
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.util.UUID
import java.util.concurrent.Executors
import javax.sql.DataSource

/** Actual legacy writers on migrated databases; the same contract runs on H2 and PostgreSQL 16. */
internal abstract class LegacyAuthorityWriteFenceContract {
    protected abstract fun database(target: String? = null): AssignmentTestDatabase

    @Test
    fun `old staff claim cannot mutate target admission even before membership exists`() =
        runBlocking<Unit> {
            database().use { db ->
                val fixture = fixture(db)
                val club = club(fixture)
                val issued = invite(fixture, club)
                val bob = user(fixture, fixture.bob)
                fence(fixture, club, bob, "TARGET")
                val before = snapshot(fixture)
                rejected { claim(fixture, issued) }
                assertEquals(before, snapshot(fixture))
                assertTrue(fixture.rows("SELECT * FROM client_memberships WHERE user_id=$bob").isEmpty())
            }
        }

    @Test
    fun `old confirmation cannot insert membership or grants into target admission`() =
        runBlocking<Unit> {
            database().use { db ->
                val fixture = fixture(db)
                val club = club(fixture)
                val claimed = claim(fixture, invite(fixture, club))
                val bob = user(fixture, fixture.bob)
                fence(fixture, club, bob, "TARGET")
                val before = snapshot(fixture)
                rejected { confirm(fixture, club, claimed) }
                assertEquals(before, snapshot(fixture))
            }
        }

    @Test
    fun `revoked role history in shadow mode fences old role change and disable`() =
        runBlocking<Unit> {
            database().use { db ->
                val fixture = fixture(db)
                val club = club(fixture)
                confirm(fixture, club, claim(fixture, invite(fixture, club)))
                val bob = user(fixture, fixture.bob)
                fence(fixture, club, bob, "SHADOW")
                roleHistory(fixture, club, bob, revoked = true)
                val before = snapshot(fixture)
                for (action in listOf("change_role", "disable")) {
                    rejected { change(fixture, club, bob, action) }
                    assertEquals(before, snapshot(fixture))
                }
            }
        }

    @Test
    fun `disabled empty shadow fences old writers while platform can revoke membership`() =
        runBlocking<Unit> {
            database().use { db ->
                val fixture = fixture(db)
                val club = club(fixture)
                confirm(fixture, club, claim(fixture, invite(fixture, club)))
                val bob = user(fixture, fixture.bob)
                fence(fixture, club, bob, "SHADOW")
                fixture.execute(
                    "UPDATE client_club_access_state SET status='DISABLED',revoked_at=CURRENT_TIMESTAMP," +
                        "revoked_by=${user(fixture, fixture.alice)},revoke_reason='Synthetic full disable' " +
                        "WHERE client_id=${club.id("clientId")} AND club_id=${club.id("id")} AND user_id=$bob",
                )
                val before = snapshot(fixture)
                for (action in listOf("change_role", "disable")) {
                    rejected { change(fixture, club, bob, action) }
                    assertEquals(before, snapshot(fixture))
                }
                val admission = fixture.rows("SELECT * FROM client_club_access_state WHERE user_id=$bob").single()
                membership(fixture, club, bob, restore = false)
                assertFalse(fixture.rows("SELECT * FROM client_memberships WHERE user_id=$bob").single().active())
                assertFalse(fixture.rows("SELECT * FROM client_club_staff WHERE user_id=$bob").single().active())
                assertTrue(permissions(fixture, club, bob).isEmpty())
                val revoked = fixture.rows("SELECT * FROM client_club_access_state WHERE user_id=$bob").single()
                assertEquals("DISABLED", revoked["status"])
                assertEquals(admission.long("admission_epoch") + 1, revoked.long("admission_epoch"))
                assertEquals(admission.long("version") + 1, revoked.long("version"))
                assertTrue(fixture.rows("SELECT * FROM client_role_assignments").isEmpty())
            }
        }

    @Test
    fun `empty foundation and empty shadow fence preserve accepted staff lifecycle`() =
        runBlocking<Unit> {
            database().use { db ->
                val fixture = fixture(db)
                val club = club(fixture)
                val bob = user(fixture, fixture.bob)
                fence(fixture, club, bob, "SHADOW")
                confirm(fixture, club, claim(fixture, invite(fixture, club)))
                fixture.execute(
                    "UPDATE client_club_access_state SET status='ACTIVE',membership_version=" +
                        "(SELECT version FROM client_memberships WHERE client_id=${club.id("clientId")} " +
                        "AND user_id=$bob) " +
                        "WHERE club_id=${club.id("id")} AND user_id=$bob",
                )
                change(fixture, club, bob, "change_role")
                assertEquals(staffPackage("MANAGER").sorted(), permissions(fixture, club, bob))
                change(fixture, club, bob, "disable")
                assertTrue(permissions(fixture, club, bob).isEmpty())
                assertFalse(
                    fixture.rows("SELECT is_active FROM client_club_staff WHERE user_id=$bob").single().active(),
                )
                assertTrue(fixture.rows("SELECT * FROM client_role_assignments").isEmpty())
            }
        }

    @Test
    fun `exact club guard preserves neighbouring staff while client revoke fences the whole member`() =
        runBlocking<Unit> {
            database().use { db ->
                val fixture = fixture(db)
                val first = club(fixture)
                fixture.execute("UPDATE clients SET allowed_clubs=2 WHERE id=${first.id("clientId")}")
                val second =
                    fixture.mutate(
                        fixture.alice,
                        OnboardingMutation.APPLICATION_CREATE_CLUB,
                        fixture.approved(first.id("clientId"), venue = "Second synthetic club"),
                    )
                confirm(fixture, first, claim(fixture, invite(fixture, first)))
                val bob = user(fixture, fixture.bob)
                fence(fixture, second, bob, "TARGET")
                change(fixture, first, bob, "change_role")
                assertEquals(staffPackage("MANAGER").sorted(), permissions(fixture, first, bob))
                val admission = fixture.rows("SELECT * FROM client_club_access_state WHERE user_id=$bob").single()
                membership(fixture, first, bob, restore = false)
                assertFalse(fixture.rows("SELECT * FROM client_memberships WHERE user_id=$bob").single().active())
                assertFalse(fixture.rows("SELECT * FROM client_club_staff WHERE user_id=$bob").single().active())
                assertTrue(permissions(fixture, first, bob).isEmpty())
                assertTrue(permissions(fixture, second, bob).isEmpty())
                val revoked = fixture.rows("SELECT * FROM client_club_access_state WHERE user_id=$bob").single()
                assertEquals("DISABLED", revoked["status"])
                assertEquals(admission.long("admission_epoch") + 1, revoked.long("admission_epoch"))
            }
        }

    @Test
    fun `platform membership restore keeps controlled sources terminal and old invitations blocked`() =
        runBlocking<Unit> {
            database().use { db ->
                val fixture = fixture(db)
                val club = club(fixture)
                confirm(fixture, club, claim(fixture, invite(fixture, club)))
                val bob = user(fixture, fixture.bob)
                val pending = claim(fixture, invite(fixture, club))
                val unclaimed = invite(fixture, club)
                fence(fixture, club, bob, "SHADOW")
                roleHistory(fixture, club, bob, revoked = true)
                val priorRoles = fixture.rows("SELECT * FROM client_role_assignments ORDER BY id")
                assertEquals(1, priorRoles.size)
                membership(fixture, club, bob, restore = false)
                val admissions =
                    fixture.rows("SELECT * FROM client_club_access_state ORDER BY client_id,club_id,user_id")
                val roles = fixture.rows("SELECT * FROM client_role_assignments ORDER BY id")
                assertTrue(admissions.all { it["status"] == "DISABLED" })
                assertTrue(roles.all { it["status"] == "REVOKED" })
                assertEquals(priorRoles, roles)
                membership(fixture, club, bob, restore = true)
                assertTrue(fixture.rows("SELECT * FROM client_memberships WHERE user_id=$bob").single().active())
                assertFalse(fixture.rows("SELECT * FROM client_club_staff WHERE user_id=$bob").single().active())
                assertEquals(
                    admissions,
                    fixture.rows("SELECT * FROM client_club_access_state ORDER BY client_id,club_id,user_id"),
                )
                assertEquals(roles, fixture.rows("SELECT * FROM client_role_assignments ORDER BY id"))
                assertTrue(permissions(fixture, club, bob).isEmpty())
                val before = snapshot(fixture)
                rejected { claim(fixture, unclaimed) }
                assertEquals(before, snapshot(fixture))
                rejected { confirm(fixture, club, pending) }
                assertEquals(before, snapshot(fixture))
            }
        }

    @Test
    fun `promoter repository does not bypass target fence with a direct enable or disable`() =
        runBlocking<Unit> {
            database().use { db ->
                val fixture = fixture(db)
                val club = club(fixture)
                val bob = user(fixture, fixture.bob)
                val promoter = PromoterAdminRepositoryImpl(Database.connect(db.dataSource), fixture.clock)
                assertTrue(promoter.setPromoterAccess(club.id("id"), bob, true) is PromoterAccessUpdateResult.Success)
                fence(fixture, club, bob, "TARGET")
                val before = snapshot(fixture)
                for (enabled in listOf(false, true)) {
                    assertEquals(
                        PromoterAccessUpdateResult.NotFound,
                        promoter.setPromoterAccess(club.id("id"), bob, enabled),
                    )
                    assertEquals(before, snapshot(fixture))
                }
            }
        }

    @Test
    fun `pre foundation schema keeps the historical writer contract even with unrelated foreign tables`() =
        runBlocking<Unit> {
            database("76").use { db ->
                val fixture = fixture(db, "76")
                val club = club(fixture)
                val foreignSchema = "foreign_role_guard_" + UUID.randomUUID().toString().replace("-", "")
                fixture.execute("CREATE SCHEMA $foreignSchema")
                try {
                    for (table in FOUNDATION_TABLES) {
                        fixture.execute("CREATE TABLE $foreignSchema.$table (marker BIGINT)")
                    }
                    confirm(fixture, club, claim(fixture, invite(fixture, club)))
                    val bob = user(fixture, fixture.bob)
                    assertEquals(staffPackage("CLUB_ADMIN").sorted(), permissions(fixture, club, bob))
                    fixture.execute("CREATE TABLE client_club_access_state (synthetic_partial_schema BIGINT)")
                    val before = snapshot(fixture, includeFoundation = false)
                    rejected { change(fixture, club, bob, "disable") }
                    assertEquals(before, snapshot(fixture, includeFoundation = false))
                } finally {
                    fixture.execute("DROP SCHEMA $foreignSchema CASCADE")
                }
            }
        }

    @Test
    fun `missing or partial foundation after successful migration refuses before old effects`() =
        runBlocking<Unit> {
            for (allMissing in listOf(false, true)) {
                database().use { db ->
                    val fixture = fixture(db)
                    val club = club(fixture)
                    val claimed = claim(fixture, invite(fixture, club))
                    fixture.execute("DROP TABLE client_permission_delegations")
                    if (allMissing) {
                        fixture.execute("DROP TABLE client_role_assignments")
                        fixture.execute("DROP TABLE client_club_access_state")
                    }
                    val before = snapshot(fixture, includeFoundation = false)
                    rejected { confirm(fixture, club, claimed) }
                    assertEquals(before, snapshot(fixture, includeFoundation = false))
                }
            }
        }

    @Test
    fun `new club bootstrap preserves exact compatibility grants without assignment inheritance`() =
        runBlocking<Unit> {
            database().use { db ->
                val fixture = fixture(db)
                val first = club(fixture)
                val alice = user(fixture, fixture.alice)
                fence(fixture, first, alice, "TARGET")
                fixture.execute("UPDATE clients SET allowed_clubs=2 WHERE id=${first.id("clientId")}")
                val second =
                    fixture.repository.mutate(
                        fixture.alice,
                        OnboardingMutation.CLUB_CREATE,
                        first.id("clientId"),
                        buildJsonObject {
                            put("venueName", "New compatibility club")
                            put("countryCode", "RU")
                            put("city", "Moscow")
                            put("cityId", "ru-moscow")
                            put("region", "Moscow")
                            put("address", "Synthetic separate address")
                            put("timezone", "Europe/Moscow")
                        },
                        UUID.randomUUID().toString(),
                    )
                assertEquals(10, permissions(fixture, second, alice).size)
                assertTrue(fixture.rows("SELECT * FROM client_role_assignments").isEmpty())
                assertTrue(
                    fixture.rows("SELECT * FROM client_club_access_state WHERE club_id=${second.id("id")}").isEmpty(),
                )
            }
        }

    protected fun fixture(
        db: AssignmentTestDatabase,
        target: String? = null,
    ) = OnboardingFixture(db.dataSource, db.dialect, target)

    protected suspend fun club(fixture: OnboardingFixture): JsonObject =
        fixture.mutate(
            fixture.alice,
            OnboardingMutation.APPLICATION_CREATE_CLUB,
            fixture.approved(fixture.accept().id("clientId")),
        )

    private suspend fun invite(
        fixture: OnboardingFixture,
        club: JsonObject,
    ): JsonObject =
        fixture.repository.mutate(
            fixture.alice,
            OnboardingMutation.CLUB_TEAM_UPDATE,
            club.id("id"),
            buildJsonObject {
                put("action", "invite")
                put("expectedVersion", team(fixture, club).getValue("version"))
                put("recipientLabel", "Synthetic guard recipient")
                put("role", "CLUB_ADMIN")
            },
            UUID.randomUUID().toString(),
        )

    private suspend fun claim(
        fixture: OnboardingFixture,
        issued: JsonObject,
    ): JsonObject =
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

    private suspend fun confirm(
        fixture: OnboardingFixture,
        club: JsonObject,
        invitation: JsonObject,
    ): JsonObject =
        fixture.repository.mutate(
            fixture.alice,
            OnboardingMutation.CLUB_TEAM_UPDATE,
            club.id("id"),
            buildJsonObject {
                put("action", "confirm")
                put("expectedVersion", team(fixture, club).getValue("version"))
                put("invitationId", invitation.getValue("id"))
                put("expectedInvitationVersion", invitation.getValue("version"))
                put("verificationCode", invitation.getValue("verificationCode"))
                put("confirmed", true)
            },
            UUID.randomUUID().toString(),
        )

    private suspend fun change(
        fixture: OnboardingFixture,
        club: JsonObject,
        targetUser: Long,
        action: String,
    ) {
        val staff =
            fixture
                .rows(
                    "SELECT version FROM client_club_staff WHERE user_id=$targetUser AND club_id=${club.id("id")}",
                ).single()
        fixture.repository.mutate(
            fixture.alice,
            OnboardingMutation.CLUB_TEAM_UPDATE,
            club.id("id"),
            buildJsonObject {
                put("action", action)
                put("expectedVersion", team(fixture, club).getValue("version"))
                put("userId", targetUser.toString())
                put("expectedMemberVersion", staff["version"])
                if (action == "change_role") put("role", "MANAGER")
                put("reason", "Synthetic exact-scope change")
                put("confirmed", true)
            },
            UUID.randomUUID().toString(),
        )
    }

    private suspend fun membership(
        fixture: OnboardingFixture,
        club: JsonObject,
        targetUser: Long,
        restore: Boolean,
    ) {
        val client = club.id("clientId")
        val version =
            fixture
                .rows(
                    "SELECT version FROM client_memberships WHERE client_id=$client AND user_id=$targetUser",
                ).single()["version"]
        fixture.repository.mutate(
            fixture.owner,
            if (restore) OnboardingMutation.MEMBER_RESTORE else OnboardingMutation.MEMBER_REVOKE,
            client,
            buildJsonObject {
                put("userId", targetUser.toString())
                put("expectedVersion", version)
                if (restore) {
                    put("permissions", JsonArray(emptyList()))
                    put("clubGrants", JsonArray(emptyList()))
                }
            },
            UUID.randomUUID().toString(),
        )
    }

    private suspend fun team(
        fixture: OnboardingFixture,
        club: JsonObject,
    ) = fixture.repository.read(fixture.alice, OnboardingRead.CLUB_TEAM_GET, club.id("id"))

    protected fun user(
        fixture: OnboardingFixture,
        actor: OnboardingActor,
    ): Long {
        val existing =
            fixture.rows("SELECT id FROM users WHERE telegram_user_id=${actor.telegramUserId}").singleOrNull()
        if (existing != null) return existing.long("id")
        fixture.execute("INSERT INTO users(telegram_user_id) VALUES (${actor.telegramUserId})")
        return fixture.rows("SELECT id FROM users WHERE telegram_user_id=${actor.telegramUserId}").single().long("id")
    }

    private fun fence(
        fixture: OnboardingFixture,
        club: JsonObject,
        targetUser: Long,
        mode: String,
    ) {
        fixture.execute(
            "INSERT INTO client_club_access_state(client_id,club_id,user_id,mode) " +
                "VALUES (${club.id("clientId")},${club.id("id")},$targetUser,'$mode')",
        )
    }

    private fun roleHistory(
        fixture: OnboardingFixture,
        club: JsonObject,
        targetUser: Long,
        revoked: Boolean,
    ) {
        val receipt =
            fixture
                .rows(
                    "SELECT * FROM client_commands WHERE result_type='club_create' AND result_id=${club.id("id")}",
                ).single()
        val audit =
            fixture
                .rows(
                    "SELECT id FROM audit_log WHERE action='client.club.create' AND entity_id='${club.id("id")}'",
                ).single()
                .long("id")
        val revocationFields = if (revoked) ",revoked_at,revoked_by,revoke_reason" else ""
        val revocationValues =
            if (revoked) ",CURRENT_TIMESTAMP,${receipt.long("actor_user_id")},'Synthetic revoked history'" else ""
        fixture.execute(
            "INSERT INTO client_role_assignments(id,client_id,club_id,user_id,role_code,policy_version,policy_hash," +
                "selector_kind,selector_json,admission_epoch,issuer_authority_ref,created_by,command_connection_key," +
                "command_key_hash,created_audit_id,last_audit_id,status$revocationFields) VALUES " +
                "('${UUID.randomUUID()}',${club.id("clientId")},${club.id("id")},$targetUser," +
                "'FLOOR_MANAGER',1,'${"0".repeat(64)}'," +
                "'CLUB','[]',1,'synthetic-guard',${receipt.long("actor_user_id")},'${receipt["connection_key"]}'," +
                "'${receipt["key_hash"]}',$audit,$audit,'${if (revoked) "REVOKED" else "ACTIVE"}'$revocationValues)",
        )
    }

    private fun permissions(
        fixture: OnboardingFixture,
        club: JsonObject,
        targetUser: Long,
    ) = fixture
        .rows(
            "SELECT permission_code FROM client_club_grants WHERE club_id=${club.id("id")} " +
                "AND user_id=$targetUser AND is_active=TRUE ORDER BY permission_code",
        ).map { checkNotNull(it["permission_code"]) }

    protected fun snapshot(
        fixture: OnboardingFixture,
        includeFoundation: Boolean = true,
    ): Map<String, List<DbRow>> =
        (LEGACY_TABLES + if (includeFoundation) FOUNDATION_TABLES else emptyList()).associateWith {
            fixture.rows("SELECT * FROM $it ORDER BY 1,2")
        }

    private suspend fun rejected(action: suspend () -> Unit) {
        val failure = assertThrows(OnboardingFailure::class.java) { runBlocking { action() } }
        assertEquals("invalid_transition", failure.code)
    }
}

internal class LegacyAuthorityWriteFenceTest : LegacyAuthorityWriteFenceContract() {
    override fun database(target: String?): AssignmentTestDatabase = AssignmentTestDatabase.h2(target)
}

@Tag("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class LegacyAuthorityWriteFencePostgresIT : LegacyAuthorityWriteFenceContract() {
    private val ioDispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()

    @AfterAll
    fun closeTestWorkers() {
        ioDispatcher.close()
    }

    override fun database(target: String?): AssignmentTestDatabase = AssignmentTestDatabase.postgres(target)

    @Test
    fun `promoter waiting for client lock observes committed target fence before any legacy mutation`() =
        runBlocking<Unit> {
            database().use { db ->
                val fixture = fixture(db)
                val club = club(fixture)
                val bob = user(fixture, fixture.bob)
                val before = snapshot(fixture, includeFoundation = false)
                val connectionName = "legacy_role_guard_" + UUID.randomUUID().toString().replace("-", "")
                val watchedSource =
                    object : DataSource by db.dataSource {
                        override fun getConnection(): Connection =
                            db.dataSource.connection.also { connection ->
                                connection.createStatement().use {
                                    it.execute("SET application_name='$connectionName'")
                                }
                            }
                    }
                val promoter = PromoterAdminRepositoryImpl(Database.connect(watchedSource), fixture.clock)
                db.dataSource.connection.use { blocker ->
                    blocker.autoCommit = false
                    val sql = OnboardingSql(blocker)
                    sql.row("SELECT id FROM clients WHERE id=? FOR UPDATE", club.id("clientId"))
                    coroutineScope {
                        val pending = async(ioDispatcher) { promoter.setPromoterAccess(club.id("id"), bob, true) }
                        try {
                            withTimeout(10_000) {
                                while (fixture
                                        .rows(
                                            "SELECT pid FROM pg_stat_activity " +
                                                "WHERE application_name='$connectionName' " +
                                                "AND wait_event_type='Lock'",
                                        ).isEmpty()
                                ) {
                                    delay(10)
                                }
                            }
                            sql.update(
                                "INSERT INTO client_club_access_state(client_id,club_id,user_id,mode) " +
                                    "VALUES (?,?,?,'TARGET')",
                                club.id("clientId"),
                                club.id("id"),
                                bob,
                            )
                            blocker.commit()
                            assertEquals(PromoterAccessUpdateResult.NotFound, pending.await())
                        } finally {
                            blocker.rollback()
                            pending.cancel()
                        }
                    }
                }
                assertEquals(before, snapshot(fixture, includeFoundation = false))
            }
        }
}

private val FOUNDATION_TABLES =
    listOf("client_club_access_state", "client_role_assignments", "client_permission_delegations")
private val LEGACY_TABLES =
    listOf(
        "users",
        "clients",
        "clubs",
        "client_memberships",
        "client_membership_grants",
        "client_club_grants",
        "client_club_staff",
        "client_invitations",
        "client_club_preparation",
        "client_commands",
        "audit_log",
        "user_roles",
        "user_role_permissions",
        "promoter_club_access",
    )
