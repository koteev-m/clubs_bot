package com.example.bot.data.client.assignments

import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.CALENDAR_WRITE
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.CLIENT
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.CLUB
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.ENTRY_SOURCE
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.FLOOR_READ
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.MANAGER
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.OWNER
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.REASON
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.TARGET
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.evidence
import com.example.bot.data.client.onboarding.OnboardingMutation
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import javax.sql.DataSource

/** Both orders use actual platform/foundation repositories and observed PostgreSQL lock barriers. */
@Tag("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class PlatformRoleRevokeConcurrencyPostgresIT {
    private val workers = Executors.newFixedThreadPool(2).asCoroutineDispatcher()

    @AfterAll
    fun closeWorkers() = workers.close()

    @Test
    fun `platform revoke and role issue serialize in both commit orders`() =
        runBlocking {
            bothOrders { fx, platformFirst ->
                fx.recipient()
                val body = fx.body(false)
                val command = fx.base.issue(StaffRole.ENTRANCE_MANAGER, ENTRY_SOURCE)
                val result =
                    race(
                        fx,
                        platformFirst,
                        { source -> fx.change(false, request = body, source = source) },
                        { source -> fx.foundation(source).mutate(evidence(MANAGER), command, fx.base.commandKey()) },
                    )
                assertEquals(null, result.first)
                assertEquals(if (platformFirst) "version_conflict" else null, result.second)
                assertEquals("inactive", fx.memberState())
                assertEquals(
                    "0",
                    fx.database.scalar(
                        "SELECT COUNT(*) FROM client_role_assignments WHERE user_id=$TARGET AND status='ACTIVE'",
                    ),
                )
            }
        }

    @Test
    fun `platform revoke and delegation issue serialize for both recipient and issuer in both orders`() =
        runBlocking {
            for (subject in listOf(TARGET, MANAGER)) {
                bothOrders { fx, platformFirst ->
                    fx.recipient()
                    val body = fx.body(false, subject)
                    val command = fx.base.delegation()
                    val result =
                        race(
                            fx,
                            platformFirst,
                            { source -> fx.change(false, subject, body, source = source) },
                            { source ->
                                fx.foundation(source).mutate(evidence(MANAGER), command, fx.base.commandKey())
                            },
                        )
                    assertEquals(null, result.first)
                    assertEquals(
                        if (!platformFirst) {
                            null
                        } else if (subject == MANAGER) {
                            "forbidden"
                        } else {
                            "version_conflict"
                        },
                        result.second,
                    )
                    assertEquals("inactive", fx.memberState(subject))
                    assertEquals(
                        "0",
                        fx.database.scalar("SELECT COUNT(*) FROM client_permission_delegations WHERE status='ACTIVE'"),
                    )
                    if (subject == MANAGER) {
                        val recipient = fx.foundation().effective(evidence(TARGET), CLIENT, CLUB)
                        assertTrue(recipient.permits(FLOOR_READ))
                        assertFalse(recipient.permits(CALENDAR_WRITE))
                    }
                }
            }
        }

    @Test
    fun `platform restore racing an old staff receipt cannot reexecute the old grant in either order`() =
        runBlocking {
            bothOrders { fx, restoreFirst ->
                fx.recipient()
                fx.change(false)
                val body = fx.body(true)
                val terminal = fx.sourceSnapshot()
                val result =
                    race(
                        fx,
                        restoreFirst,
                        { source -> fx.change(true, request = body, source = source) },
                        { source ->
                            val replay =
                                fx.foundation(source).mutate(
                                    evidence(MANAGER),
                                    fx.originalRoleCommand,
                                    fx.originalRoleKey,
                                )
                            assertTrue(replay.replayed)
                            assertTrue(replay.current.contributions.isEmpty())
                        },
                    )
                assertEquals(null to null, result)
                assertEquals("active", fx.memberState())
                assertEquals(terminal, fx.sourceSnapshot())
            }
        }

    @Test
    fun `two platform revokes have one effect with stale distinct keys in either queue order`() =
        runBlocking {
            bothOrders { fx, firstKeyFirst ->
                fx.recipient()
                val body = fx.body(false)
                val initial = requireNotNull(fx.base.fence())
                val keys = fx.base.commandKey() to fx.base.commandKey()
                val result =
                    race(
                        fx,
                        firstKeyFirst,
                        { source -> fx.change(false, request = body, key = keys.first, source = source) },
                        { source -> fx.change(false, request = body, key = keys.second, source = source) },
                    )
                assertEquals(
                    if (firstKeyFirst) null to "version_conflict" else "version_conflict" to null,
                    result,
                )
                assertEquals(initial.epoch + 1, requireNotNull(fx.base.fence()).epoch)
                assertEquals(2L, fx.memberVersion())
                assertEquals(
                    "1",
                    fx.database.scalar("SELECT COUNT(*) FROM audit_log WHERE action='client.member.revoke'"),
                )
            }
        }

    @Test
    fun `platform revoke and full club disable serialize without partial effects in both orders`() =
        runBlocking {
            bothOrders { fx, platformFirst ->
                fx.recipient()
                val initial = requireNotNull(fx.base.fence())
                val body = fx.body(false)
                val disable = AssignmentCommand.Disable(fx.base.targetScope, initial, REASON, true)
                val result =
                    race(
                        fx,
                        platformFirst,
                        { source -> fx.change(false, request = body, source = source) },
                        { source -> fx.foundation(source).mutate(evidence(MANAGER), disable, fx.base.commandKey()) },
                    )
                assertEquals(null, result.first)
                assertEquals(if (platformFirst) "forbidden" else null, result.second)
                assertEquals(initial.epoch + if (platformFirst) 1 else 2, requireNotNull(fx.base.fence()).epoch)
                assertEquals("inactive", fx.memberState())
                assertEquals(
                    "DISABLED",
                    fx.database.scalar("SELECT status FROM client_club_access_state WHERE user_id=$TARGET"),
                )
            }
        }

    @Test
    fun `membership restore and fresh explicit readmission obey their actual commit order`() =
        runBlocking {
            bothOrders { fx, restoreFirst ->
                fx.recipient()
                fx.change(false)
                val body = fx.body(true)
                val expected = requireNotNull(fx.base.fence())
                val admit =
                    AssignmentCommand.Admit(
                        fx.base.targetScope,
                        expected,
                        StaffRole.FLOOR_MANAGER,
                        fx.base.confirmation(),
                        REASON,
                        true,
                    )
                val result =
                    race(
                        fx,
                        restoreFirst,
                        { source -> fx.change(true, request = body, source = source) },
                        { source -> fx.foundation(source).mutate(evidence(MANAGER), admit, fx.base.commandKey()) },
                    )
                assertEquals(null, result.first)
                assertEquals(if (restoreFirst) null else "forbidden", result.second)
                assertEquals("active", fx.memberState())
                assertEquals(
                    if (restoreFirst) "ACTIVE" else "DISABLED",
                    fx.database.scalar("SELECT status FROM client_club_access_state WHERE user_id=$TARGET"),
                )
                assertEquals(
                    "0",
                    fx.database.scalar(
                        "SELECT COUNT(*) FROM client_role_assignments WHERE user_id=$TARGET AND status='ACTIVE'",
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
    fun `platform revoke and an authorized old staff mutation never bypass controlled scope in either order`() =
        runBlocking {
            bothOrders { fx, platformFirst ->
                fx.legacyStaff()
                fx.recipient()
                val body = fx.body(false)
                val change = fx.legacyChangeBody()
                val result =
                    race(
                        fx,
                        platformFirst,
                        { source -> fx.change(false, request = body, source = source) },
                        { source ->
                            fx.platform(source).mutate(
                                fx.actor(OWNER),
                                OnboardingMutation.CLUB_TEAM_UPDATE,
                                CLUB,
                                change,
                                fx.base.commandKey(),
                            )
                        },
                    )
                assertEquals(null, result.first)
                assertEquals(if (platformFirst) "version_conflict" else "invalid_transition", result.second)
                assertEquals("inactive", fx.memberState())
                assertEquals(
                    "0",
                    fx.database.scalar(
                        "SELECT COUNT(*) FROM client_club_staff WHERE client_id=$CLIENT " +
                            "AND user_id=$TARGET AND is_active=TRUE",
                    ),
                )
                assertEquals(
                    "0",
                    fx.database.scalar(
                        "SELECT COUNT(*) FROM client_club_grants WHERE client_id=$CLIENT " +
                            "AND user_id=$TARGET AND is_active=TRUE",
                    ),
                )
            }
        }

    @Test
    fun `cancelled platform revoke and restore roll back after a real client lock wait`() =
        runBlocking {
            for (restoring in listOf(false, true)) {
                PlatformRoleRevokeFixture(AssignmentTestDatabase.postgres()).use { fx ->
                    fx.legacyStaff()
                    fx.recipient(withDelegation = true)
                    if (restoring) fx.change(false)
                    val before = fx.snapshot()
                    val teamVersion = fx.database.scalar("SELECT team_version FROM clubs WHERE id=$CLUB")
                    val request = fx.body(restoring)
                    val key = fx.base.commandKey()
                    val name = "pr-cancel-${UUID.randomUUID()}"
                    coroutineScope {
                        holdClient(fx).use { lock ->
                            val mutation =
                                async(workers) {
                                    fx.change(
                                        restoring,
                                        request = request,
                                        key = key,
                                        source = namedSource(fx.database.dataSource, name),
                                    )
                                }
                            awaitBlocked(fx, name)
                            mutation.cancel()
                            lock.commit()
                            mutation.join()
                            assertTrue(mutation.isCancelled)
                        }
                    }
                    assertEquals(before, fx.snapshot())
                    assertEquals(teamVersion, fx.database.scalar("SELECT team_version FROM clubs WHERE id=$CLUB"))
                }
            }
        }

    private suspend fun bothOrders(block: suspend (PlatformRoleRevokeFixture, Boolean) -> Unit) {
        for (first in listOf(true, false)) {
            PlatformRoleRevokeFixture(AssignmentTestDatabase.postgres()).use { block(it, first) }
        }
    }

    /** The pair is returned in semantic operation order, independently of the selected queue order. */
    private suspend fun race(
        fx: PlatformRoleRevokeFixture,
        firstOperationFirst: Boolean,
        first: suspend (DataSource) -> Unit,
        second: suspend (DataSource) -> Unit,
    ): Pair<String?, String?> =
        coroutineScope {
            val id = UUID.randomUUID()
            val names = "pr-a-$id" to "pr-b-$id"
            holdClient(fx).use { lock ->
                val a = if (firstOperationFirst) first else second
                val b = if (firstOperationFirst) second else first
                val firstResult =
                    async(workers) { platformFailure { a(namedSource(fx.database.dataSource, names.first)) } }
                awaitBlocked(fx, names.first)
                val secondResult =
                    async(workers) { platformFailure { b(namedSource(fx.database.dataSource, names.second)) } }
                awaitBlocked(fx, names.second)
                lock.commit()
                val result = firstResult.await() to secondResult.await()
                if (firstOperationFirst) result else result.second to result.first
            }
        }

    private fun holdClient(fx: PlatformRoleRevokeFixture): Connection =
        fx.database.dataSource.connection.also { connection ->
            connection.autoCommit = false
            connection.createStatement().use {
                it
                    .executeQuery(
                        "SELECT id FROM clients WHERE id=$CLIENT FOR UPDATE",
                    ).close()
            }
        }

    private fun awaitBlocked(
        fx: PlatformRoleRevokeFixture,
        name: String,
    ) {
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        while (System.nanoTime() < deadline) {
            if (fx.database
                    .scalar(
                        "SELECT COUNT(*) FROM pg_stat_activity WHERE application_name='$name' AND wait_event_type='Lock'",
                    )!!
                    .toInt() > 0
            ) {
                return
            }
            Thread.sleep(10)
        }
        error("Synthetic platform race did not reach the real PostgreSQL lock barrier")
    }

    private fun namedSource(
        source: DataSource,
        name: String,
    ): DataSource =
        object : DataSource by source {
            override fun getConnection(): Connection =
                source.connection.also { connection ->
                    connection.prepareStatement("SELECT set_config('application_name', ?, false)").use {
                        it.setString(1, name)
                        it.execute()
                    }
                }
        }
}
