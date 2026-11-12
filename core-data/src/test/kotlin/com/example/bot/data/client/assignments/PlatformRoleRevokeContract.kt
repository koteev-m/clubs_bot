package com.example.bot.data.client.assignments

import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.CALENDAR_WRITE
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.CLIENT
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.CLUB
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.FLOOR_READ
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.FLOOR_SOURCE
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.FOREIGN_CLUB
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.LEGACY
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.MANAGER
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.OTHER_CLIENT
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.OTHER_CLUB
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.TARGET
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.THIRD_CLUB
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.evidence
import com.example.bot.data.client.onboarding.OnboardingActor
import com.example.bot.data.client.onboarding.digest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/** The same real repository/adapter contract executes on migrated H2 and PostgreSQL. */
internal abstract class PlatformRoleRevokeContract {
    protected abstract fun database(): AssignmentTestDatabase

    @Test
    fun `platform revoke terminates multi-club roles and both delegation directions`() =
        runBlocking {
            fixture { fx ->
                fx.mixedIssuer()
                val otherClient = fx.clientSnapshot(OTHER_CLIENT)
                val recipientFence = requireNotNull(fx.base.fence())
                val sourceCount =
                    fx.database.scalar(
                        "SELECT COUNT(*) FROM client_role_assignments WHERE user_id=$MANAGER",
                    )
                fx.change(restore = false, user = MANAGER)
                assertEquals("inactive", fx.memberState(MANAGER))
                assertEquals(
                    sourceCount,
                    fx.database.scalar("SELECT COUNT(*) FROM client_role_assignments WHERE user_id=$MANAGER"),
                )
                assertEquals(
                    "0",
                    fx.database.scalar(
                        "SELECT COUNT(*) FROM client_role_assignments WHERE client_id=$CLIENT " +
                            "AND user_id=$MANAGER AND status<>'REVOKED'",
                    ),
                )
                assertEquals(
                    "0",
                    fx.database.scalar(
                        "SELECT COUNT(*) FROM client_permission_delegations WHERE client_id=$CLIENT " +
                            "AND (user_id=$MANAGER OR issuer_user_id=$MANAGER) AND status<>'REVOKED'",
                    ),
                )
                assertEquals(
                    listOf(listOf("DISABLED"), listOf("DISABLED")),
                    fx.database.rows(
                        "SELECT status FROM client_club_access_state WHERE client_id=$CLIENT " +
                            "AND user_id=$MANAGER ORDER BY club_id",
                    ),
                )
                assertEquals(recipientFence.version + 1, requireNotNull(fx.base.fence()).version)
                val recipient = fx.foundation().effective(evidence(TARGET), CLIENT, CLUB)
                assertTrue(recipient.permits(FLOOR_READ))
                assertFalse(recipient.permits(CALENDAR_WRITE))
                assertEquals(otherClient, fx.clientSnapshot(OTHER_CLIENT))
                assertTrue(fx.foundation().effective(evidence(MANAGER), OTHER_CLIENT, FOREIGN_CLUB).permits(FLOOR_READ))
            }
        }

    @Test
    fun `membership restore never reactivates retired assignments delegations or disabled admissions`() =
        runBlocking {
            fixture { fx ->
                fx.recipient(withDelegation = true)
                fx.change(restore = false)
                val terminal = fx.sourceSnapshot()
                fx.change(restore = true)
                assertEquals("active", fx.memberState())
                assertEquals(terminal, fx.sourceSnapshot())
                assertTrue(
                    fx
                        .foundation()
                        .effective(evidence(TARGET), CLIENT, CLUB)
                        .contributions
                        .isEmpty(),
                )
                assertEquals(
                    "REVOKED",
                    fx.database.scalar("SELECT status FROM client_role_assignments WHERE id='$FLOOR_SOURCE'"),
                )
                assertEquals(
                    "DISABLED",
                    fx.database.scalar("SELECT status FROM client_club_access_state WHERE user_id=$TARGET"),
                )
            }
        }

    @Test
    fun `new revoke advances even empty and already disabled admissions only in the selected client`() =
        runBlocking {
            fixture { fx ->
                fx.recipient()
                fx.foreignSubject()
                fx.database.execute(
                    "INSERT INTO client_club_access_state(client_id,club_id,user_id,status,mode," +
                        "admission_epoch,version) VALUES ($CLIENT,$OTHER_CLUB,$TARGET,'PENDING','SHADOW',4,6)",
                    "INSERT INTO client_club_access_state(client_id,club_id,user_id,status,mode," +
                        "admission_epoch,version," +
                        "revoked_at,revoked_by,revoke_reason) VALUES " +
                        "($CLIENT,$THIRD_CLUB,$TARGET,'DISABLED','SHADOW',7,9," +
                        "CURRENT_TIMESTAMP,$MANAGER,'Prior disable')",
                )
                val before =
                    fx.database.rows(
                        "SELECT club_id,admission_epoch,version FROM client_club_access_state " +
                            "WHERE client_id=$CLIENT AND user_id=$TARGET ORDER BY club_id",
                    )
                val foreign = fx.clientSnapshot(OTHER_CLIENT)
                fx.change(restore = false)
                val after =
                    fx.database.rows(
                        "SELECT club_id,admission_epoch,version FROM client_club_access_state " +
                            "WHERE client_id=$CLIENT AND user_id=$TARGET ORDER BY club_id",
                    )
                assertEquals(
                    before.map { listOf(it[0], (it[1]!!.toLong() + 1).toString(), (it[2]!!.toLong() + 1).toString()) },
                    after,
                )
                assertEquals(foreign, fx.clientSnapshot(OTHER_CLIENT))
                val revoked = fx.sourceSnapshot()
                fx.change(restore = true)
                assertEquals(revoked, fx.sourceSnapshot())
            }
        }

    @Test
    fun `restore quarantines dangling active sources instead of reviving them after a damaged membership revoke`() =
        runBlocking {
            fixture { fx ->
                fx.recipient(withDelegation = true)
                val fence = requireNotNull(fx.base.fence())
                fx.database.execute(
                    "UPDATE client_memberships SET is_active=FALSE,version=version+1 " +
                        "WHERE client_id=$CLIENT AND user_id=$TARGET",
                )
                fx.change(restore = true)
                assertEquals("active", fx.memberState())
                assertEquals(fence.epoch + 1, requireNotNull(fx.base.fence()).epoch)
                assertTrue(
                    fx
                        .foundation()
                        .effective(evidence(TARGET), CLIENT, CLUB)
                        .contributions
                        .isEmpty(),
                )
                assertEquals(
                    "0",
                    fx.database.scalar(
                        "SELECT COUNT(*) FROM client_permission_delegations " +
                            "WHERE user_id=$TARGET AND status='ACTIVE'",
                    ),
                )
            }
        }

    @Test
    fun `controlled raw restoration is atomic and uncontrolled old scopes remain restorable`() =
        runBlocking {
            fixture { fx ->
                fx.recipient()
                fx.database.execute(
                    "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active) VALUES " +
                        "($CLIENT,$TARGET,$CLUB,'client.club.read',TRUE)," +
                        "($CLIENT,$TARGET,$THIRD_CLUB,'client.club.read',TRUE)",
                )
                fx.change(restore = false)
                val before = fx.snapshot()
                val denied = fx.body(true, grants = listOf(CLUB to "client.club.read"))
                assertEquals("invalid_transition", platformFailure { fx.change(true, request = denied) })
                assertEquals(before, fx.snapshot())
                fx.change(true, request = fx.body(true, grants = listOf(THIRD_CLUB to "client.club.read")))
                assertEquals("active", fx.memberState())
                assertEquals(
                    listOf(listOf(CLUB.toString(), "inactive"), listOf(THIRD_CLUB.toString(), "active")),
                    fx.database.rows(
                        "SELECT club_id,CASE WHEN is_active THEN 'active' ELSE 'inactive' END " +
                            "FROM client_club_grants WHERE client_id=$CLIENT AND user_id=$TARGET ORDER BY club_id",
                    ),
                )
                assertTrue(
                    fx
                        .foundation()
                        .effective(evidence(TARGET), CLIENT, CLUB)
                        .contributions
                        .isEmpty(),
                )
            }
        }

    @Test
    fun `legacy restore preserves its existing whitelist and cannot invent a grant or membership permission`() =
        runBlocking {
            fixture { fx ->
                fx.database.execute(
                    "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active) " +
                        "VALUES ($CLIENT,$LEGACY,$THIRD_CLUB,'client.club.read',TRUE)",
                    "INSERT INTO client_membership_grants(client_id,user_id,permission_code,is_active) " +
                        "VALUES ($CLIENT,$LEGACY,'client.application.manage',TRUE)",
                )
                fx.change(false, user = LEGACY)
                val before = fx.snapshot()
                val unknown = fx.body(true, LEGACY, grants = listOf(THIRD_CLUB to "client.club.profile.write"))
                assertEquals("validation_error", platformFailure { fx.change(true, LEGACY, unknown) })
                assertEquals(before, fx.snapshot())
                val allowed =
                    fx.body(
                        true,
                        LEGACY,
                        grants = listOf(THIRD_CLUB to "client.club.read"),
                        permissions = listOf("client.application.manage"),
                    )
                fx.change(true, LEGACY, allowed)
                assertEquals("active", fx.memberState(LEGACY))
                assertEquals(
                    "1",
                    fx.database.scalar(
                        "SELECT COUNT(*) FROM client_membership_grants WHERE user_id=$LEGACY AND is_active=TRUE",
                    ),
                )
                assertEquals(
                    "0",
                    fx.database.scalar("SELECT COUNT(*) FROM client_role_assignments WHERE user_id=$LEGACY"),
                )
            }
        }

    @Test
    fun `old platform and staff receipts return current state without rerunning revoke or restoration`() =
        runBlocking {
            fixture { fx ->
                fx.recipient()
                val revokeBody = fx.body(false)
                val revokeKey = fx.base.commandKey()
                fx.change(false, request = revokeBody, key = revokeKey)
                val restoreBody = fx.body(true)
                val restoreKey = fx.base.commandKey()
                fx.change(true, request = restoreBody, key = restoreKey)
                val restored = fx.snapshot()
                val oldRevoke = fx.change(false, request = revokeBody, key = revokeKey)
                assertEquals("true", oldRevoke["active"].toString())
                val replay = fx.foundation().mutate(evidence(MANAGER), fx.originalRoleCommand, fx.originalRoleKey)
                assertTrue(replay.replayed)
                assertTrue(replay.current.contributions.isEmpty())
                assertEquals(restored, fx.snapshot())
                fx.change(false)
                val terminal = fx.snapshot()
                val oldRestore = fx.change(true, request = restoreBody, key = restoreKey)
                assertEquals("false", oldRestore["active"].toString())
                assertEquals(terminal, fx.snapshot())
            }
        }

    @Test
    fun `fresh signed target admission and a new acknowledged source are required after membership restore`() =
        runBlocking {
            fixture { fx ->
                fx.recipient()
                fx.change(false)
                fx.change(true)
                val terminal = fx.sourceSnapshot()
                assertEquals("forbidden", platformFailure { fx.base.grant(fx.base.issue()) })
                assertEquals(terminal, fx.sourceSnapshot())
                fx.base.admit()
                val newId = UUID.randomUUID()
                fx.base.grant(
                    fx.base.issue(sourceId = newId).copy(regrantAcknowledgement = fx.base.floorPolicy.permissions),
                )
                assertTrue(fx.base.view().permits(FLOOR_READ))
                assertEquals(
                    "REVOKED",
                    fx.database.scalar("SELECT status FROM client_role_assignments WHERE id='$FLOOR_SOURCE'"),
                )
                assertEquals(
                    "ACTIVE",
                    fx.database.scalar("SELECT status FROM client_role_assignments WHERE id='$newId'"),
                )
            }
        }

    @Test
    fun `late audit and receipt failures roll back both new foundation and old platform effects`() =
        runBlocking {
            for (receipt in listOf(false, true)) {
                fixture { fx ->
                    fx.recipient(withDelegation = true)
                    val before = fx.snapshot()
                    val observed = AtomicBoolean()
                    val source =
                        latePlatformFailure(
                            fx.database.dataSource,
                            if (receipt) "INSERT INTO client_commands" else "INSERT INTO audit_log",
                        ) { connection ->
                            val lastAction = scalar(connection, "SELECT action FROM audit_log ORDER BY id DESC LIMIT 1")
                            if (!receipt && lastAction != "client.member.revoke") {
                                false
                            } else {
                                assertEquals(
                                    "0",
                                    scalar(
                                        connection,
                                        "SELECT COUNT(*) FROM client_memberships WHERE client_id=$CLIENT " +
                                            "AND user_id=$TARGET AND is_active=TRUE",
                                    ),
                                )
                                assertEquals(
                                    "REVOKED",
                                    scalar(
                                        connection,
                                        "SELECT status FROM client_role_assignments WHERE id='$FLOOR_SOURCE'",
                                    ),
                                )
                                observed.set(true)
                                true
                            }
                        }
                    assertThrows(SQLException::class.java) { runBlocking { fx.change(false, source = source) } }
                    assertTrue(observed.get())
                    assertEquals(before, fx.snapshot())
                }
            }
        }

    @Test
    fun `serialization retry rereads the membership changed after its first source update rolled back`() =
        runBlocking {
            fixture { fx ->
                fx.legacyStaff()
                fx.recipient(withDelegation = true)
                val request = fx.body(false)
                val key = fx.base.commandKey()
                val replacementKey = fx.base.commandKey()
                val before = fx.snapshot()
                val injected = AtomicBoolean()
                val rolledBack = AtomicBoolean()
                val closed = AtomicBoolean()
                val connections = AtomicInteger()
                var committed: Map<String, List<List<String?>>>? = null
                val failing =
                    latePlatformFailure(
                        fx.database.dataSource,
                        "UPDATE client_role_assignments SET status='REVOKED'",
                        sqlState = "40001",
                    ) { connection ->
                        assertEquals(
                            "REVOKED",
                            scalar(connection, "SELECT status FROM client_role_assignments WHERE id='$FLOOR_SOURCE'"),
                        )
                        injected.compareAndSet(false, true)
                    }
                val source =
                    object : DataSource by fx.database.dataSource {
                        override fun getConnection(): Connection =
                            when (connections.incrementAndGet()) {
                                1 -> observeFailedConnection(failing.connection, rolledBack, closed)
                                2 -> {
                                    assertTrue(injected.get())
                                    assertTrue(rolledBack.get())
                                    assertTrue(closed.get())
                                    assertEquals(before, fx.snapshot())
                                    runBlocking { fx.change(false, request = request, key = replacementKey) }
                                    committed = fx.snapshot()
                                    fx.database.dataSource.connection
                                }
                                else -> error("Unexpected additional platform transaction retry")
                            }
                    }
                assertEquals(
                    "version_conflict",
                    platformFailure { fx.change(false, request = request, key = key, source = source) },
                )
                assertEquals(2, connections.get())
                assertEquals(requireNotNull(committed), fx.snapshot())
                assertEquals("inactive", fx.memberState())
                assertEquals(
                    "0",
                    fx.database.scalar("SELECT COUNT(*) FROM client_commands WHERE key_hash='${digest(key)}'"),
                )
                assertEquals(
                    "1",
                    fx.database.scalar("SELECT COUNT(*) FROM audit_log WHERE action='client.member.revoke'"),
                )
            }
        }

    @Test
    fun `only configured platform identity may use the adapter and stale membership versions have no effects`() =
        runBlocking {
            fixture { fx ->
                fx.recipient()
                val request = fx.body(false)
                val before = fx.snapshot()
                assertEquals(
                    "forbidden",
                    platformFailure { fx.change(false, request = request, caller = fx.actor(MANAGER)) },
                )
                val wrongConnection = OnboardingActor(fx.actor().telegramUserId, "foreign")
                assertEquals(
                    "forbidden",
                    platformFailure { fx.change(false, request = request, caller = wrongConnection) },
                )
                assertEquals(before, fx.snapshot())
                fx.change(false, request = request)
                val revoked = fx.snapshot()
                assertEquals("version_conflict", platformFailure { fx.change(false, request = request) })
                assertEquals(revoked, fx.snapshot())
                assertEquals(
                    "1",
                    fx.database.scalar("SELECT COUNT(*) FROM audit_log WHERE action='client.member.revoke'"),
                )
            }
        }

    private suspend fun fixture(block: suspend (PlatformRoleRevokeFixture) -> Unit) {
        PlatformRoleRevokeFixture(database()).use { block(it) }
    }

    private fun observeFailedConnection(
        connection: Connection,
        rolledBack: AtomicBoolean,
        closed: AtomicBoolean,
    ): Connection =
        Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, args ->
            try {
                val result = method.invoke(connection, *args.orEmpty())
                if (method.name == "rollback") rolledBack.set(true)
                if (method.name == "close") closed.set(true)
                result
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        } as Connection

    private fun scalar(
        connection: Connection,
        query: String,
    ): String? =
        connection.createStatement().use { statement ->
            statement.executeQuery(query).use { result ->
                check(result.next())
                result.getString(1)
            }
        }
}
