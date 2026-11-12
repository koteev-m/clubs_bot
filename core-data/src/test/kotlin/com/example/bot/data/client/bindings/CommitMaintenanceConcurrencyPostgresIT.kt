package com.example.bot.data.client.bindings

import com.example.bot.data.client.assignments.AssignmentTestDatabase
import com.example.bot.data.client.onboarding.OnboardingFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Tag("it")
internal class CommitMaintenanceConcurrencyPostgresIT {
    @Test fun `both concurrent disable revoke orders serialize and direct child NOWAIT fails closed`() =
        runBlocking<Unit> {
            for (action in listOf(InactiveMaintenanceAction.DISABLE, InactiveMaintenanceAction.REVOKE)) {
                BindingTestFixture(AssignmentTestDatabase.postgres()).use { f ->
                    val c = CommitMaintenanceFixture(f)
                    val intent = c.intent(f.issue(), action)
                    val locked = CountDownLatch(1)
                    val release = CountDownLatch(1)
                    val held = AtomicBoolean()
                    f.source.applicationName = "commit-race-" + UUID.randomUUID()
                    f.source.beforeSql = { sql, _ ->
                        if (sql.startsWith("UPDATE audit_log") && held.compareAndSet(false, true)) {
                            locked.countDown()
                            check(release.await(15, TimeUnit.SECONDS))
                        }
                    }
                    val first = async(start = CoroutineStart.UNDISPATCHED) { c.adapter.execute(intent, "first") }
                    awaitCommitLatch(locked)
                    val other =
                        if (action ==
                            InactiveMaintenanceAction.DISABLE
                        ) {
                            InactiveMaintenanceAction.REVOKE
                        } else {
                            InactiveMaintenanceAction.DISABLE
                        }
                    val second =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            assertThrows<OnboardingFailure> { c.adapter.execute(intent.copy(action = other), "second") }
                        }
                    try {
                        f.awaitCommitWait(checkNotNull(f.source.applicationName))
                        val error =
                            assertThrows<SQLException> {
                                f.database.execute(
                                    "INSERT INTO target_credential_materials(id,root_id,created_audit_id) " +
                                        "VALUES ('${UUID.randomUUID()}','${intent.expected.rootId}'," +
                                        "(SELECT MIN(id) FROM audit_log))",
                                )
                            }
                        assertEquals("55P03", error.sqlState)
                    } finally {
                        release.countDown()
                    }
                    assertEquals(MaintenanceMutationStatus.APPLIED, first.await().status)
                    assertEquals("version_conflict", second.await().code)
                    f.source.beforeSql = { _, _ -> }
                    assertEquals("2", f.database.scalar("SELECT COUNT(*) FROM target_binding_commands"))
                    assertEquals(c.acquires, c.closes)
                }
            }
        }

    @Test fun `raw child first and authority loss during root wait prevents adapter mutation`() =
        runBlocking<Unit> {
            BindingTestFixture(AssignmentTestDatabase.postgres()).use { f ->
                val c = CommitMaintenanceFixture(f)
                val intent = c.intent(f.issue())
                f.source.applicationName = "raw-first-" + UUID.randomUUID()
                f.database.dataSource.connection.use { holder ->
                    holder.autoCommit = false
                    holder.createStatement().use { s ->
                        s.execute(
                            "UPDATE target_bot_bindings SET status='DISABLED' WHERE id='${intent.expected.fence.bindingId}'",
                        )
                    }
                    val pending =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            assertThrows<OnboardingFailure> { c.adapter.execute(intent, "waited") }
                        }
                    f.awaitCommitWait(checkNotNull(f.source.applicationName))
                    c.allowed = false // Synthetic verification is deliberately withdrawn while the DB lock is held.
                    holder.commit()
                    assertEquals("forbidden", pending.await().code)
                }
                assertEquals("1", f.database.scalar("SELECT COUNT(*) FROM target_binding_commands"))
                assertEquals(c.acquires, c.closes)
            }
        }

    @Test fun `actual coroutine cancellation while waiting root rolls back and releases authority lease`() =
        runBlocking<Unit> {
            BindingTestFixture(AssignmentTestDatabase.postgres()).use { f ->
                val c = CommitMaintenanceFixture(f)
                val intent = c.intent(f.issue())
                val before = f.snapshot()
                f.source.applicationName = "cancel-commit-" + UUID.randomUUID()
                f.database.dataSource.connection.use { held ->
                    held.autoCommit = false
                    held.createStatement().use { stmt ->
                        stmt
                            .executeQuery(
                                "SELECT id FROM target_bot_connections WHERE id='${intent.expected.rootId}' FOR UPDATE",
                            ).close()
                    }
                    val pending =
                        async(start = CoroutineStart.UNDISPATCHED) { c.adapter.execute(intent, "cancel-wait") }
                    f.awaitCommitWait(checkNotNull(f.source.applicationName))
                    pending.cancel()
                    held.rollback()
                    assertThrows<CancellationException> { pending.await() }
                }
                assertEquals(before, f.snapshot())
                assertEquals(c.acquires, c.closes)
            }
        }

    @Test fun `transaction guard pins all writer relations and operators against hostile schema and TEMP`() =
        runBlocking<Unit> {
            BindingTestFixture(AssignmentTestDatabase.postgres()).use { f ->
                val c = CommitMaintenanceFixture(f)
                val intent = c.intent(f.issue())
                val evil = "commit_evil_" + UUID.randomUUID().toString().replace("-", "")
                f.database.dataSource.connection.use { held ->
                    val schema = checkNotNull(held.schema)
                    val tables =
                        listOf(
                            "users",
                            "audit_log",
                            "target_bot_connections",
                            "target_bot_bindings",
                            "target_credential_materials",
                            "target_credential_revisions",
                            "target_binding_clubs",
                            "target_binding_commands",
                            "clients",
                            "client_clubs",
                            "clubs",
                            "target_client_mapping_epochs",
                        )
                    held.createStatement().use { stmt ->
                        stmt.execute("CREATE SCHEMA $evil")
                        for (name in tables) {
                            stmt.execute("CREATE TEMP TABLE $name (LIKE \"$schema\".$name INCLUDING DEFAULTS)")
                            stmt.execute("CREATE TABLE $evil.$name (LIKE \"$schema\".$name INCLUDING DEFAULTS)")
                        }
                        stmt.execute(
                            "CREATE FUNCTION $evil.bad_uuid(UUID,UUID) RETURNS BOOLEAN LANGUAGE SQL AS 'SELECT FALSE'",
                        )
                        stmt.execute("CREATE OPERATOR $evil.= (LEFTARG=UUID,RIGHTARG=UUID,FUNCTION=$evil.bad_uuid)")
                        stmt.execute("SET search_path TO $evil,pg_temp,pg_catalog,\"$schema\"")
                    }
                    try {
                        val source = DurableBindingReadProbe(f.source, held)
                        val adapter = JdbcCommitTimeBindingMaintenance(source, c.schema, c.authorization, f.clock)
                        val outcome = adapter.execute(intent, "trusted-schema")
                        assertEquals(MaintenanceMutationStatus.APPLIED, outcome.status)
                        held.createStatement().use { stmt ->
                            for (name in tables) {
                                stmt.executeQuery("SELECT COUNT(*) FROM pg_temp.$name").use { rows ->
                                    assertTrue(rows.next())
                                    assertEquals(0, rows.getInt(1))
                                }
                            }
                        }
                        held.createStatement().use { stmt ->
                            for (name in tables) {
                                stmt.execute(
                                    "INSERT INTO pg_temp.$name SELECT * FROM \"$schema\".$name",
                                )
                            }
                        }
                        held.commit()
                        val missing =
                            JdbcCommitTimeBindingMaintenance(
                                source,
                                BindingStorageSchema.fromServerConfiguration("no_canonical_source"),
                                c.authorization,
                                f.clock,
                            )
                        assertThrows<SQLException> { missing.execute(intent, "trusted-schema") }
                    } finally {
                        if (!held.autoCommit) held.rollback()
                        held.autoCommit = true
                        held.createStatement().use { stmt ->
                            tables.forEach { stmt.execute("DROP TABLE pg_temp.$it") }
                            stmt.execute("SET search_path TO \"$schema\",public")
                            stmt.execute("DROP SCHEMA $evil CASCADE")
                        }
                    }
                }
            }
        }

    @Test fun `real PostgreSQL 40001 after child writes retries whole command with current authority`() =
        runBlocking<Unit> {
            for (withdraw in listOf(false, true)) {
                BindingTestFixture(AssignmentTestDatabase.postgres()).use { f ->
                    val c = CommitMaintenanceFixture(f)
                    val intent = c.intent(f.issue())
                    val before = f.snapshot()
                    val schema =
                        f.database.dataSource.connection
                            .use { checkNotNull(it.schema) }
                    f.database.execute(
                        "CREATE SEQUENCE \"$schema\".commit_retry_seq",
                        "CREATE FUNCTION \"$schema\".commit_retry_failure() RETURNS TRIGGER LANGUAGE plpgsql " +
                            "SET search_path=pg_catalog AS 'BEGIN " +
                            "IF nextval(''\"$schema\".commit_retry_seq''::regclass)=1 THEN " +
                            "RAISE EXCEPTION ''synthetic rollback'' USING ERRCODE=''40001''; END IF; RETURN NEW; END'",
                        "CREATE TRIGGER commit_retry_failure BEFORE UPDATE ON \"$schema\".audit_log " +
                            "FOR EACH ROW EXECUTE FUNCTION \"$schema\".commit_retry_failure()",
                    )
                    c.onAcquire = { if (withdraw && c.acquires > 1) c.allowed = false }
                    if (withdraw) {
                        assertThrows<OnboardingFailure> { c.adapter.execute(intent, "server-retry") }
                        assertEquals(before, f.snapshot())
                    } else {
                        assertEquals(
                            MaintenanceMutationStatus.APPLIED,
                            c.adapter.execute(intent, "server-retry").status,
                        )
                        assertEquals("2", f.database.scalar("SELECT COUNT(*) FROM target_binding_commands"))
                    }
                    assertEquals(2, c.acquires)
                    assertEquals(c.acquires, c.closes)
                    println("COMMIT_RETRY actual PostgreSQL40001 withdraw=$withdraw acquires=${c.acquires}")
                }
            }
        }
}

private suspend fun awaitCommitLatch(
    latch: CountDownLatch,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) = withContext(dispatcher) { assertTrue(latch.await(15, TimeUnit.SECONDS)) }

private fun BindingTestFixture.awaitCommitWait(name: String) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
    while (System.nanoTime() < deadline) {
        val waiting =
            database
                .rows(
                    "SELECT COUNT(*) FROM pg_stat_activity WHERE application_name='$name' AND wait_event_type='Lock'",
                ).single()
                .single()
        if (checkNotNull(waiting).toInt() > 0) return
        Thread.yield()
    }
    error("Expected real PostgreSQL lock wait")
}
