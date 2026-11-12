package com.example.bot.data.client.assignments

import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.CLIENT
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.CLUB
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.ENTRY_SOURCE
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.FLOOR_READ
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.FLOOR_SOURCE
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.LEGACY
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.MANAGER
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.OWNER
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.REASON
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.TARGET
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.evidence
import com.example.bot.data.client.assignments.AssignmentLifecycleFixture.Companion.telegram
import com.example.bot.data.client.onboarding.OnboardingFailure
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

/** Real PostgreSQL barriers observe pg_stat_activity lock waits, not a guessed sleep ordering. */
@Tag("it")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class AssignmentConcurrencyPostgresIT {
    private val ioDispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()

    @AfterAll
    fun closeTestWorkers() {
        ioDispatcher.close()
    }

    @Test
    fun `queued intent detaches mutable selectors and binds replay to the original permission scope`() =
        runBlocking {
            fixture { fx ->
                val shifts = mutableListOf(10L)
                val originalSelector = PermissionSelector(SelectorKind.SHIFT_SET, shifts)
                val command = fx.issue(StaffRole.FINANCE_MANAGER).copy(selector = originalSelector)
                val name = names().first
                val key = fx.commandKey()
                coroutineScope {
                    fx.holdClient().use { lock ->
                        val mutation =
                            async(ioDispatcher) {
                                fx
                                    .repository(namedAssignmentSource(fx.database.dataSource, name))
                                    .mutate(evidence(MANAGER), command, key)
                            }
                        fx.awaitBlocked(name)
                        shifts += 20L
                        lock.commit()
                        val result = mutation.await()
                        assertTrue(result.current.permits(AssignmentLifecycleFixture.FINANCE_READ, 10L))
                        assertFalse(result.current.permits(AssignmentLifecycleFixture.FINANCE_READ, 20L))
                    }
                }
                assertEquals(
                    "[10]",
                    fx.database.scalar("SELECT selector_json FROM client_role_assignments WHERE id='$FLOOR_SOURCE'"),
                )
                val before = fx.snapshot()
                assertEquals("idempotency_conflict", fx.tryMutation(command, MANAGER, name, key))
                assertEquals(before, fx.snapshot())
            }
        }

    @Test
    fun `different issuers cannot append two roles using one admission revision`() =
        runBlocking {
            fixture { fx ->
                val first = fx.issue()
                val second = fx.issue(StaffRole.ENTRANCE_MANAGER, ENTRY_SOURCE)
                val names = names()
                val firstKey = fx.commandKey()
                val secondKey = fx.commandKey()
                coroutineScope {
                    fx.holdClient().use { lock ->
                        val a = async(ioDispatcher) { fx.tryMutation(first, MANAGER, names.first, firstKey) }
                        fx.awaitBlocked(names.first)
                        val b = async(ioDispatcher) { fx.tryMutation(second, LEGACY, names.second, secondKey) }
                        fx.awaitBlocked(names.second)
                        lock.commit()
                        assertEquals(listOf(null, "version_conflict"), listOf(a.await(), b.await()))
                    }
                }
                assertEquals(
                    "1",
                    fx.database.scalar("SELECT COUNT(*) FROM client_role_assignments WHERE user_id=$TARGET"),
                )
                fx.grant(fx.issue(StaffRole.ENTRANCE_MANAGER, ENTRY_SOURCE), LEGACY)
                assertEquals(2, fx.view().sources.size)
            }
        }

    @Test
    fun `a queued role revoke commits before a stale concurrent role change`() =
        runBlocking {
            fixture { fx ->
                fx.grant()
                val revoke = fx.revokeIntent()
                val issue = fx.issue(StaffRole.ENTRANCE_MANAGER, ENTRY_SOURCE)
                val names = names()
                val keys = fx.commandKey() to fx.commandKey()
                coroutineScope {
                    fx.holdClient().use { lock ->
                        val a = async(ioDispatcher) { fx.tryMutation(revoke, MANAGER, names.first, keys.first) }
                        fx.awaitBlocked(names.first)
                        val b = async(ioDispatcher) { fx.tryMutation(issue, LEGACY, names.second, keys.second) }
                        fx.awaitBlocked(names.second)
                        lock.commit()
                        assertEquals(null, a.await())
                        assertEquals("version_conflict", b.await())
                    }
                }
                assertFalse(fx.view().permits(FLOOR_READ))
                assertEquals(
                    "REVOKED",
                    fx.database.scalar("SELECT status FROM client_role_assignments WHERE id='$FLOOR_SOURCE'"),
                )
                assertEquals(
                    "0",
                    fx.database.scalar("SELECT COUNT(*) FROM client_role_assignments WHERE id='$ENTRY_SOURCE'"),
                )
            }
        }

    @Test
    fun `a role addition committed first preserves both sources and rejects stale revoke`() =
        runBlocking {
            fixture { fx ->
                fx.grant()
                val issue = fx.issue(StaffRole.ENTRANCE_MANAGER, ENTRY_SOURCE)
                val revoke = fx.revokeIntent()
                val names = names()
                val keys = fx.commandKey() to fx.commandKey()
                coroutineScope {
                    fx.holdClient().use { lock ->
                        val a = async(ioDispatcher) { fx.tryMutation(issue, LEGACY, names.first, keys.first) }
                        fx.awaitBlocked(names.first)
                        val b = async(ioDispatcher) { fx.tryMutation(revoke, MANAGER, names.second, keys.second) }
                        fx.awaitBlocked(names.second)
                        lock.commit()
                        assertEquals(null, a.await())
                        assertEquals("version_conflict", b.await())
                    }
                }
                assertEquals(
                    setOf("ACTIVE"),
                    fx
                        .view()
                        .sources.values
                        .toSet(),
                )
                assertEquals(2, fx.view().sources.size)
            }
        }

    @Test
    fun `full disable wins the client lock and a concurrent grant cannot enter the old epoch`() =
        runBlocking {
            fixture { fx ->
                fx.grant()
                val before = requireNotNull(fx.fence())
                val disable = AssignmentCommand.Disable(fx.targetScope, before, REASON, true)
                val issue = fx.issue(StaffRole.ENTRANCE_MANAGER, ENTRY_SOURCE)
                val names = names()
                val keys = fx.commandKey() to fx.commandKey()
                coroutineScope {
                    fx.holdClient().use { lock ->
                        val a = async(ioDispatcher) { fx.tryMutation(disable, MANAGER, names.first, keys.first) }
                        fx.awaitBlocked(names.first)
                        val b = async(ioDispatcher) { fx.tryMutation(issue, LEGACY, names.second, keys.second) }
                        fx.awaitBlocked(names.second)
                        lock.commit()
                        assertEquals(null, a.await())
                        assertEquals("version_conflict", b.await())
                    }
                }
                assertEquals(before.epoch + 1, fx.fence()?.epoch)
                assertTrue(fx.view().contributions.isEmpty())
                assertEquals(
                    setOf("REVOKED"),
                    fx
                        .view()
                        .sources.values
                        .toSet(),
                )
            }
        }

    @Test
    fun `two concurrent disables produce exactly one epoch transition`() =
        runBlocking {
            fixture { fx ->
                fx.grant()
                val before = requireNotNull(fx.fence())
                val command = AssignmentCommand.Disable(fx.targetScope, before, REASON, true)
                val names = names()
                val keys = fx.commandKey() to fx.commandKey()
                coroutineScope {
                    fx.holdClient().use { lock ->
                        val a = async(ioDispatcher) { fx.tryMutation(command, MANAGER, names.first, keys.first) }
                        fx.awaitBlocked(names.first)
                        val b = async(ioDispatcher) { fx.tryMutation(command, OWNER, names.second, keys.second) }
                        fx.awaitBlocked(names.second)
                        lock.commit()
                        assertEquals(null, a.await())
                        assertEquals("version_conflict", b.await())
                    }
                }
                assertEquals(before.epoch + 1, fx.fence()?.epoch)
            }
        }

    @Test
    fun `authorization waiting behind a committed revoke sees no stale permission`() =
        runBlocking {
            fixture { fx ->
                fx.grant()
                val names = names()
                val command = fx.revokeIntent()
                val key = fx.commandKey()
                coroutineScope {
                    fx.holdClient().use { lock ->
                        val revoke = async(ioDispatcher) { fx.tryMutation(command, MANAGER, names.first, key) }
                        fx.awaitBlocked(names.first)
                        val read =
                            async(ioDispatcher) {
                                fx
                                    .repository(
                                        namedAssignmentSource(fx.database.dataSource, names.second),
                                    ).effective(evidence(TARGET), CLIENT, CLUB)
                            }
                        fx.awaitBlocked(names.second)
                        lock.commit()
                        assertEquals(null, revoke.await())
                        assertFalse(read.await().permits(FLOOR_READ))
                    }
                }
            }
        }

    @Test
    fun `expiry is checked after the lock wait even with a still fresh signed identity`() =
        runBlocking {
            fixture { fx ->
                val before = fx.snapshot()
                val start = fx.clock.instant()
                fx.database.execute(
                    "UPDATE client_role_assignments SET expires_at='${start.plusSeconds(1)}' WHERE user_id=$MANAGER",
                )
                val command = fx.issue()
                val name = names().first
                val fixedIdentity = AssignmentIdentityVerifier { proof, _ -> fx.identity.verify(proof, start) }
                val repository =
                    JdbcRoleAssignmentFoundation(
                        namedAssignmentSource(fx.database.dataSource, name),
                        fx.catalog,
                        fixedIdentity,
                        fx.ownership,
                        fx.admissions,
                        telegram(AssignmentLifecycleFixture.PLATFORM),
                        fx.clock,
                    )
                coroutineScope {
                    fx.holdClient().use { lock ->
                        val mutation =
                            async(ioDispatcher) {
                                try {
                                    repository.mutate(evidence(MANAGER), command, "expiry-wait-key")
                                    "unexpected_allow"
                                } catch (failure: OnboardingFailure) {
                                    failure.code
                                }
                            }
                        fx.awaitBlocked(name)
                        fx.clock.current = start.plusSeconds(2)
                        lock.commit()
                        assertEquals("forbidden", mutation.await())
                    }
                }
                assertEquals(
                    "0",
                    fx.database.scalar("SELECT COUNT(*) FROM client_role_assignments WHERE user_id=$TARGET"),
                )
                assertEquals(before["client_commands"], fx.snapshot()["client_commands"])
            }
        }

    @Test
    fun `cancellation during a real client lock wait rolls back before permission issuance`() =
        runBlocking {
            fixture { fx ->
                val before = fx.snapshot()
                val command = fx.issue()
                val key = fx.commandKey()
                val name = names().first
                coroutineScope {
                    fx.holdClient().use { lock ->
                        val mutation =
                            async(ioDispatcher) {
                                fx
                                    .repository(namedAssignmentSource(fx.database.dataSource, name))
                                    .mutate(evidence(MANAGER), command, key)
                            }
                        fx.awaitBlocked(name)
                        mutation.cancel()
                        lock.commit()
                        mutation.join()
                        assertTrue(mutation.isCancelled)
                    }
                }
                assertEquals(before, fx.snapshot())
            }
        }

    @Test
    fun `simultaneous replay produces one source one audit and one durable receipt`() =
        runBlocking {
            fixture { fx ->
                val command = fx.issue()
                val name = names()
                val key = fx.commandKey()
                val audits = fx.database.scalar("SELECT COUNT(*) FROM audit_log")!!.toLong()
                coroutineScope {
                    fx.holdClient().use { lock ->
                        val a =
                            async(ioDispatcher) {
                                fx
                                    .repository(
                                        namedAssignmentSource(fx.database.dataSource, name.first),
                                    ).mutate(evidence(MANAGER), command, key)
                            }
                        fx.awaitBlocked(name.first)
                        val b =
                            async(ioDispatcher) {
                                fx
                                    .repository(
                                        namedAssignmentSource(fx.database.dataSource, name.second),
                                    ).mutate(evidence(MANAGER), command, key)
                            }
                        fx.awaitBlocked(name.second)
                        lock.commit()
                        assertEquals(listOf(false, true), listOf(a.await().replayed, b.await().replayed))
                    }
                }
                assertEquals(
                    "1",
                    fx.database.scalar("SELECT COUNT(*) FROM client_role_assignments WHERE user_id=$TARGET"),
                )
                assertEquals(audits + 1, fx.database.scalar("SELECT COUNT(*) FROM audit_log")!!.toLong())
            }
        }

    private suspend fun fixture(block: suspend (AssignmentLifecycleFixture) -> Unit) {
        AssignmentLifecycleFixture(AssignmentTestDatabase.postgres()).use { fx ->
            fx.prepare()
            val second = StaffScope(CLIENT, CLUB, LEGACY)
            fx.admit(second, StaffRole.CLUB_MANAGER, OWNER)
            fx.grant(fx.issue(StaffRole.CLUB_MANAGER, UUID.randomUUID(), second), OWNER)
            fx.admit()
            block(fx)
        }
    }
}

private fun AssignmentLifecycleFixture.revokeIntent(): AssignmentCommand.RevokeRole =
    AssignmentCommand.RevokeRole(
        targetScope,
        requireNotNull(fence()),
        FLOOR_SOURCE,
        sourceVersion(FLOOR_SOURCE),
        REASON,
        true,
    )

private suspend fun AssignmentLifecycleFixture.tryMutation(
    command: AssignmentCommand,
    actor: Long,
    name: String,
    key: String,
): String? =
    try {
        repository(namedAssignmentSource(database.dataSource, name)).mutate(evidence(actor), command, key)
        null
    } catch (failure: OnboardingFailure) {
        failure.code
    }

private fun AssignmentLifecycleFixture.holdClient(): Connection =
    database.dataSource.connection.also { connection ->
        connection.autoCommit = false
        connection.createStatement().use {
            it
                .executeQuery(
                    "SELECT id FROM clients WHERE id=$CLIENT FOR UPDATE",
                ).close()
        }
    }

private fun AssignmentLifecycleFixture.awaitBlocked(name: String) {
    val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
    while (System.nanoTime() < deadline) {
        val count =
            database
                .scalar(
                    "SELECT COUNT(*) FROM pg_stat_activity WHERE application_name='$name' AND wait_event_type='Lock'",
                )!!
                .toInt()
        if (count > 0) return
        Thread.sleep(10)
    }
    error("Synthetic command did not reach the PostgreSQL lock barrier")
}

private fun names(): Pair<String, String> = UUID.randomUUID().toString().let { "af-a-$it" to "af-b-$it" }

private fun namedAssignmentSource(
    source: DataSource,
    name: String,
): DataSource =
    object : DataSource by source {
        override fun getConnection(): Connection =
            source.connection.also { connection ->
                connection.prepareStatement("SELECT set_config('application_name', ?, false)").use { statement ->
                    statement.setString(1, name)
                    statement.execute()
                }
            }
    }
