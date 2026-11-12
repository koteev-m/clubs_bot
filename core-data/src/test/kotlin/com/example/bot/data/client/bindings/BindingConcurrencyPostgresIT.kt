package com.example.bot.data.client.bindings

import com.example.bot.data.client.assignments.AssignmentTestDatabase
import com.example.bot.data.client.onboarding.OnboardingFailure
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Actual PostgreSQL row-lock waits and independent connections; no sleeps are used as ordering proof. */
@Tag("it")
internal class BindingConcurrencyPostgresIT {
    @Test fun `shared platform root reads coexist and revoke waits for both`() =
        runBlocking {
            fixture { f ->
                val first = f.issue()
                val legacy = f.legacy()
                val holders = List(2) { f.holdRoot(first.rootId) }
                try {
                    val name = "binding-" + UUID.randomUUID()
                    f.source.applicationName = name
                    val pending =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            f.issue(BindingCommand.State(first.rootId, first.fence, BindingState.REVOKED))
                        }
                    f.awaitBlocked(name)
                    assertFalse(pending.isCompleted)
                    holders[0].commit()
                    f.awaitBlocked(name)
                    holders[1].commit()
                    val revoked = pending.await()
                    assertEquals(BindingState.REVOKED, revoked.status)
                    assertEquals(3L, revoked.fence.generation)
                    assertEquals(legacy, f.legacy())
                } finally {
                    holders.forEach { it.close() }
                }
            }
        }

    @Test fun `authority revoked while waiting for root lock prevents every mutation`() =
        runBlocking {
            fixture { f ->
                val first = f.issue()
                val before = f.snapshot()
                f.holdRoot(first.rootId).use { holder ->
                    val name = "binding-" + UUID.randomUUID()
                    f.source.applicationName = name
                    val pending =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            attempt(f, BindingCommand.State(first.rootId, first.fence, BindingState.REVOKED))
                        }
                    f.awaitBlocked(name)
                    f.identityAllowed = false
                    holder.commit()
                    assertTrue(pending.await() is OnboardingFailure)
                }
                assertEquals(before, f.snapshot())
            }
        }

    @Test fun `both ordered concurrent disable and revoke reject the losing stale CAS`() =
        runBlocking {
            for (winner in listOf(BindingState.DISABLED, BindingState.REVOKED)) {
                fixture { f ->
                    val first = f.issue()
                    val reached = CountDownLatch(1)
                    val release = CountDownLatch(1)
                    val pause = AtomicBoolean(true)
                    f.source.beforeSql = { sql, _ ->
                        if (sql.startsWith("UPDATE target_bot_connections") && pause.compareAndSet(true, false)) {
                            reached.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                        }
                    }
                    try {
                        val early =
                            async(
                                start = CoroutineStart.UNDISPATCHED,
                            ) { attempt(f, BindingCommand.State(first.rootId, first.fence, winner)) }
                        await(reached)
                        val name = "binding-" + UUID.randomUUID()
                        f.source.applicationName = name
                        val loser = if (winner == BindingState.DISABLED) BindingState.REVOKED else BindingState.DISABLED
                        val late =
                            async(
                                start = CoroutineStart.UNDISPATCHED,
                            ) { attempt(f, BindingCommand.State(first.rootId, first.fence, loser)) }
                        f.awaitBlocked(name)
                        release.countDown()
                        assertTrue(early.await() is BindingView)
                        assertTrue(late.await() is OnboardingFailure)
                        val final = checkNotNull(f.repository.read(BindingTestFixture.EVIDENCE, first.rootId))
                        assertEquals(winner, final.status)
                        assertEquals(3L, final.fence.generation)
                    } finally {
                        release.countDown()
                    }
                }
            }
        }

    @Test fun `two raw mapping writers serialize through the existing client lock without lost epochs`() =
        runBlocking {
            fixture { f ->
                f.database.execute("INSERT INTO clubs(id,name,timezone) VALUES (1004,'Another','UTC')")
                val before = f.epoch()
                f.holdClient().use { holder ->
                    val names = List(2) { "mapping-" + UUID.randomUUID() }
                    val sources = names.map { BindingTestSource(f.database.dataSource).apply { applicationName = it } }
                    val inserts =
                        listOf(1003L, 1004L).mapIndexed { index, club ->
                            async(start = CoroutineStart.UNDISPATCHED) { insertMapping(sources[index], club) }
                        }
                    names.forEach { f.awaitBlocked(it) }
                    assertEquals(before, f.epoch())
                    holder.commit()
                    inserts.forEach { it.await() }
                }
                assertEquals(before + 2, f.epoch())
                assertEquals(
                    f.epoch().toString(),
                    f.database.scalar("SELECT epoch FROM target_client_mapping_epochs WHERE client_id=101"),
                )
            }
        }

    @Test fun `concurrent client reinsert waits on durable tombstone and cannot reuse old epoch`() =
        runBlocking {
            fixture { f ->
                val before = f.epoch()
                f.database.dataSource.connection.use { deleting ->
                    deleting.autoCommit = false
                    deleting.createStatement().use {
                        it.execute("DELETE FROM client_clubs WHERE client_id=101")
                        it.execute("DELETE FROM clients WHERE id=101")
                    }
                    val name = "recreate-" + UUID.randomUUID()
                    val source = BindingTestSource(f.database.dataSource).apply { applicationName = name }
                    val recreating = async(start = CoroutineStart.UNDISPATCHED) { recreate(source) }
                    f.awaitBlocked(name)
                    deleting.commit()
                    recreating.await()
                }
                assertTrue(f.epoch() > before)
                assertEquals(
                    "1",
                    f.database.scalar("SELECT COUNT(*) FROM client_clubs WHERE client_id=101 AND club_id=1001"),
                )
            }
        }

    @Test fun `cancelled lock wait rolls back before audit or metadata writes`() =
        runBlocking {
            fixture { f ->
                val first = f.issue()
                val before = f.snapshot()
                f.holdRoot(first.rootId).use { holder ->
                    val name = "binding-" + UUID.randomUUID()
                    f.source.applicationName = name
                    val pending =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            f.issue(BindingCommand.State(first.rootId, first.fence, BindingState.REVOKED))
                        }
                    f.awaitBlocked(name)
                    pending.cancel()
                    holder.commit()
                    pending.join()
                    assertTrue(pending.isCancelled)
                }
                assertEquals(before, f.snapshot())
            }
        }

    @Test fun `service root lock makes competing different child DML fail closed`() =
        runBlocking {
            fixture { f ->
                val initial = f.issue()
                val selected = f.issue(f.attach(initial))
                val material = UUID.randomUUID()
                val audit = f.database.scalar("SELECT MIN(id) FROM audit_log")
                f.database.execute(
                    "INSERT INTO target_credential_materials(id,root_id,created_audit_id) VALUES ('$material','${selected.rootId}',$audit)",
                )
                val before = checkNotNull(f.repository.read(BindingTestFixture.EVIDENCE, selected.rootId))
                val reached = CountDownLatch(1)
                val release = CountDownLatch(1)
                f.source.beforeSql = { sql, _ ->
                    if (sql.startsWith("UPDATE target_bot_connections")) {
                        reached.countDown()
                        check(release.await(10, TimeUnit.SECONDS))
                    }
                }
                val command = BindingCommand.State(before.rootId, before.fence, BindingState.DISABLED)
                val pending = async(start = CoroutineStart.UNDISPATCHED) { f.issue(command, "service-child-race") }
                try {
                    await(reached)
                    val failure =
                        assertThrows<SQLException> {
                            f.database.execute(
                                "UPDATE target_credential_materials SET status='RETIRED',retired_at=CURRENT_TIMESTAMP,retired_audit_id=$audit WHERE id='$material'",
                            )
                        }
                    assertEquals("55P03", failure.sqlState)
                } finally {
                    release.countDown()
                }
                val after = pending.await()
                assertEquals(before.fence.generation + 2, after.fence.generation)
                assertEquals(
                    "AVAILABLE",
                    f.database.scalar("SELECT status FROM target_credential_materials WHERE id='$material'"),
                )
                assertEquals(
                    BindingResolutionStatus.COMPLETED_CURRENT,
                    f.repository.resolve(BindingTestFixture.EVIDENCE, command, "service-child-race").status,
                )
            }
        }

    @Test fun `raw child winner makes the waiting service fail stale CAS`() =
        runBlocking {
            fixture { f ->
                val create = f.create()
                val first = f.issue(create, "raw-winner")
                f.database.dataSource.connection.use { raw ->
                    raw.autoCommit = false
                    raw.createStatement().use {
                        it.execute(
                            "UPDATE target_bot_bindings SET status='DISABLED' WHERE id='${first.fence.bindingId}'",
                        )
                    }
                    val name = "child-race-" + UUID.randomUUID()
                    f.source.applicationName = name
                    val pending =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            attempt(f, BindingCommand.State(first.rootId, first.fence, BindingState.REVOKED))
                        }
                    f.awaitBlocked(name)
                    raw.commit()
                    val result = pending.await()
                    assertTrue(result is OnboardingFailure)
                    assertEquals("version_conflict", (result as OnboardingFailure).code)
                }
                val after = checkNotNull(f.repository.read(BindingTestFixture.EVIDENCE, first.rootId))
                assertEquals(first.fence.generation + 1, after.fence.generation)
                assertEquals(BindingState.DISABLED, after.status)
                assertEquals("1", f.database.scalar("SELECT COUNT(*) FROM target_binding_commands"))
                assertEquals(
                    BindingResolutionStatus.COMPLETED_STALE,
                    f.repository.resolve(BindingTestFixture.EVIDENCE, create, "raw-winner").status,
                )
                val revoked = f.issue(BindingCommand.State(after.rootId, after.fence, BindingState.REVOKED))
                assertEquals(after.fence.generation + 2, revoked.fence.generation)
            }
        }

    private suspend fun fixture(block: suspend CoroutineScope.(BindingTestFixture) -> Unit) {
        withTimeout(30000) {
            BindingTestFixture(AssignmentTestDatabase.postgres()).use { f -> coroutineScope { block(f) } }
        }
    }

    private suspend fun attempt(
        f: BindingTestFixture,
        command: BindingCommand,
    ): Any =
        try {
            f.issue(command)
        } catch (failure: OnboardingFailure) {
            failure
        }

    private suspend fun await(
        latch: CountDownLatch,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) = withContext(dispatcher) { assertTrue(latch.await(10, TimeUnit.SECONDS)) }

    private suspend fun insertMapping(
        source: BindingTestSource,
        club: Long,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) = withContext(dispatcher) {
        source.connection.use { connection ->
            connection.prepareStatement("INSERT INTO client_clubs(client_id,club_id) VALUES (101,?)").use {
                it.setLong(1, club)
                it.executeUpdate()
            }
        }
    }

    private suspend fun recreate(
        source: BindingTestSource,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) = withContext(dispatcher) {
        source.connection.use { connection ->
            connection.autoCommit = false
            connection.createStatement().use {
                it.execute("INSERT INTO clients(id,display_name,is_active) VALUES (101,'Recreated',TRUE)")
                it.execute("INSERT INTO client_clubs(client_id,club_id) VALUES (101,1001)")
            }
            connection.commit()
        }
    }
}

private fun BindingTestFixture.holdRoot(root: UUID): Connection =
    database.dataSource.connection.also {
        it.autoCommit = false
        it.prepareStatement("SELECT id FROM target_bot_connections WHERE id=? FOR SHARE").use { statement ->
            statement.setObject(1, root)
            statement.executeQuery().close()
        }
    }

private fun BindingTestFixture.holdClient(): Connection =
    database.dataSource.connection.also {
        it.autoCommit = false
        it.createStatement().use { statement ->
            statement.executeQuery("SELECT id FROM clients WHERE id=101 FOR UPDATE").close()
        }
    }

private fun BindingTestFixture.awaitBlocked(name: String) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (System.nanoTime() < deadline) {
        val waiting = hasLockWait(name)
        if (waiting) return
        Thread.yield()
    }
    error("Expected synthetic PostgreSQL lock wait")
}

private fun BindingTestFixture.hasLockWait(name: String): Boolean =
    database.dataSource.connection.use { connection ->
        connection
            .prepareStatement(
                "SELECT COUNT(*) FROM pg_stat_activity WHERE application_name=? AND wait_event_type='Lock'",
            ).use {
                it.setString(1, name)
                it.executeQuery().use { rows ->
                    rows.next()
                    rows.getInt(1) > 0
                }
            }
    }
