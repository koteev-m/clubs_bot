package com.example.bot.data.client.bindings

import com.example.bot.data.client.ClientAudience
import com.example.bot.data.client.assignments.AssignmentTestDatabase
import com.example.bot.data.client.onboarding.OnboardingFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.SQLException
import java.util.UUID

internal abstract class CommitMaintenanceContract {
    protected abstract fun database(): AssignmentTestDatabase

    @Test fun `default absent authority cannot write with a valid read snapshot`() =
        runBlocking<Unit> {
            BindingTestFixture(database()).use { f ->
                val c = CommitMaintenanceFixture(f)
                val intent = c.intent(f.issue())
                val before = f.snapshot()
                val denied = JdbcCommitTimeBindingMaintenance(f.source, c.schema, clock = f.clock)
                assertThrows<OnboardingFailure> { denied.execute(intent, "no-authority") }
                assertEquals(before, f.snapshot())
            }
        }

    @Test fun `existing writer emits one atomic outcome and honest current or stale replay`() =
        runBlocking<Unit> {
            BindingTestFixture(database()).use { f ->
                val c = CommitMaintenanceFixture(f)
                val intent = c.intent(f.issue())
                val legacy = f.legacy()
                val applied = c.adapter.execute(intent, "one-command")
                assertEquals(MaintenanceMutationStatus.APPLIED, applied.status)
                assertTrue(applied.currentFence.generation > intent.expected.fence.generation)
                val after = f.snapshot()
                val replay = c.adapter.execute(intent, "one-command")
                assertEquals(MaintenanceMutationStatus.REPLAY_CURRENT, replay.status)
                assertEquals(applied.currentFence, replay.currentFence)
                assertEquals(after, f.snapshot())
                assertEquals(
                    "idempotency_conflict",
                    assertThrows<OnboardingFailure> {
                        c.adapter.execute(intent.copy(action = InactiveMaintenanceAction.REVOKE), "one-command")
                    }.code,
                )
                assertEquals(after, f.snapshot())
                val current = checkNotNull(f.repository.read(BindingTestFixture.EVIDENCE, intent.expected.rootId))
                f.issue(BindingCommand.State(current.rootId, current.fence, BindingState.REVOKED))
                val revoked = f.snapshot()
                assertEquals(MaintenanceMutationStatus.REPLAY_STALE, c.adapter.execute(intent, "one-command").status)
                assertEquals(revoked, f.snapshot())
                assertEquals(legacy, f.legacy())
                assertEquals(c.acquires, c.closes)
            }
        }

    @Test fun `actor database identity is revalidated after acquiring actor lock`() =
        runBlocking<Unit> {
            BindingTestFixture(database()).use { f ->
                val c = CommitMaintenanceFixture(f)
                val intent = c.intent(f.issue())
                val before = f.snapshot()
                var changed = false
                f.source.beforeSql = { sql, _ ->
                    if (!changed && sql.startsWith("SELECT id FROM users WHERE id=")) {
                        changed = true
                        f.database.execute(
                            "UPDATE users SET telegram_user_id=878999 WHERE id=${BindingTestFixture.ACTOR}",
                        )
                    }
                }
                assertThrows<OnboardingFailure> { c.adapter.execute(intent, "remapped-actor") }
                f.source.beforeSql = { _, _ -> }
                assertTrue(changed)
                assertEquals(before, f.snapshot())
            }
        }

    @Test fun `expired or withdrawn authority denies new work and committed replay`() =
        runBlocking<Unit> {
            BindingTestFixture(database()).use { f ->
                val c = CommitMaintenanceFixture(f)
                val intent = c.intent(f.issue())
                c.adapter.execute(intent, "committed")
                val before = f.snapshot()
                c.allowed = false
                assertThrows<OnboardingFailure> { c.adapter.execute(intent, "committed") }
                assertThrows<OnboardingFailure> { c.adapter.execute(intent, "new-key") }
                c.allowed = true
                f.clock.now = BindingTestFixture.START.plusSeconds(300)
                assertThrows<OnboardingFailure> { c.adapter.execute(intent, "committed") }
                assertEquals(before, f.snapshot())
                assertEquals(c.acquires, c.closes)
            }
        }

    @Test fun `child fence and revoked root reject stale CAS without audit or receipt`() =
        runBlocking<Unit> {
            BindingTestFixture(database()).use { f ->
                val c = CommitMaintenanceFixture(f)
                val intent = c.intent(f.issue())
                f.database.execute(
                    "UPDATE target_bot_bindings SET status='DISABLED' WHERE id='${intent.expected.fence.bindingId}'",
                )
                val afterChild = f.snapshot()
                assertEquals(
                    "version_conflict",
                    assertThrows<OnboardingFailure> { c.adapter.execute(intent, "stale") }.code,
                )
                assertEquals(afterChild, f.snapshot())
                val current = checkNotNull(f.repository.read(BindingTestFixture.EVIDENCE, intent.expected.rootId))
                f.issue(BindingCommand.State(current.rootId, current.fence, BindingState.REVOKED))
                val afterRevoke = f.snapshot()
                assertThrows<OnboardingFailure> { c.adapter.execute(intent, "revoked-before-lock") }
                assertEquals(afterRevoke, f.snapshot())
            }
        }

    @Test fun `selected material retirement is scoped and token-free`() =
        runBlocking<Unit> {
            BindingTestFixture(database()).use { f ->
                val c = CommitMaintenanceFixture(f)
                val view = f.issue(f.attach(f.issue()))
                val intent = c.intent(view, InactiveMaintenanceAction.RETIRE_SELECTED_MATERIAL, 1)
                val before = f.snapshot()
                assertThrows<OnboardingFailure> {
                    c.adapter.execute(
                        intent.copy(materialId = UUID.randomUUID()),
                        "foreign-material",
                    )
                }
                assertEquals(before, f.snapshot())
                val result = c.adapter.execute(intent, "retire")
                val current = checkNotNull(f.repository.read(BindingTestFixture.EVIDENCE, view.rootId))
                assertEquals(result.currentFence, current.fence)
                assertEquals(BindingState.DISABLED, current.status)
                assertEquals(null, current.credentialReference)
                assertEquals(
                    "RETIRED",
                    f.database.scalar("SELECT status FROM target_credential_materials WHERE id='${intent.materialId}'"),
                )
                assertEquals(MaintenanceMutationStatus.REPLAY_CURRENT, c.adapter.execute(intent, "retire").status)
            }
        }

    @Test fun `retired credential after selection and altered pin cannot bypass full state comparison`() =
        runBlocking<Unit> {
            BindingTestFixture(database()).use { f ->
                val c = CommitMaintenanceFixture(f)
                val view = f.issue(f.attach(f.issue()))
                val intent = c.intent(view, InactiveMaintenanceAction.REVOKE, 1)
                val fake =
                    intent.copy(
                        expected =
                            intent.expected.copy(
                                credential = intent.expected.credential?.copy(referenceId = UUID.randomUUID()),
                            ),
                    )
                val before = f.snapshot()
                assertThrows<OnboardingFailure> { c.adapter.execute(fake, "fabricated-pin") }
                assertEquals(before, f.snapshot())
                f.issue(
                    BindingCommand.RetireMaterial(
                        view.rootId,
                        view.fence,
                        checkNotNull(intent.expected.credential).materialId,
                    ),
                )
                val after = f.snapshot()
                assertThrows<OnboardingFailure> { c.adapter.execute(intent, "retired-before-lock") }
                assertEquals(after, f.snapshot())
            }
        }

    @Test fun `audit completion or receipt failure rolls all child fences and rows back`() =
        runBlocking<Unit> {
            for (target in listOf("INSERT INTO audit_log", "UPDATE audit_log", "INSERT INTO target_binding_commands")) {
                BindingTestFixture(database()).use { f ->
                    val c = CommitMaintenanceFixture(f)
                    val view = f.issue(f.attach(f.issue()))
                    val intent = c.intent(view, InactiveMaintenanceAction.REVOKE, 1)
                    val before = f.snapshot()
                    f.source.beforeSql =
                        { sql, _ -> if (sql.startsWith(target)) throw SQLException("synthetic failure", "ZX001") }
                    assertThrows<SQLException> { c.adapter.execute(intent, "atomic") }
                    f.source.beforeSql = { _, _ -> }
                    assertEquals(before, f.snapshot())
                    assertEquals(c.acquires, c.closes)
                }
            }
        }

    @Test fun `retry reacquires authority and never refreshes the old expected pin`() =
        runBlocking<Unit> {
            for (state in listOf("40001", "40P01", "55P03")) {
                BindingTestFixture(database()).use { f ->
                    val c = CommitMaintenanceFixture(f)
                    val intent = c.intent(f.issue())
                    val before = f.snapshot()
                    var failures = 0
                    f.source.beforeSql = { sql, _ ->
                        if (sql.startsWith("INSERT INTO audit_log") &&
                            failures++ == 0
                        ) {
                            throw SQLException("synthetic retry", state)
                        }
                    }
                    c.onAcquire = { if (c.acquires > 1) c.allowed = false }
                    if (state == "55P03") {
                        assertThrows<SQLException> { c.adapter.execute(intent, "retry") }
                    } else {
                        assertThrows<OnboardingFailure> { c.adapter.execute(intent, "retry") }
                    }
                    f.source.beforeSql = { _, _ -> }
                    assertEquals(if (state == "55P03") 1 else 2, c.acquires)
                    assertEquals(before, f.snapshot())
                    assertEquals(c.acquires, c.closes)
                }
            }
        }

    @Test fun `allowed retry has exactly one final audit receipt and final aggregate fence`() =
        runBlocking<Unit> {
            BindingTestFixture(database()).use { f ->
                val c = CommitMaintenanceFixture(f)
                val intent = c.intent(f.issue())
                val audits = checkNotNull(f.database.scalar("SELECT COUNT(*) FROM audit_log")).toLong()
                var failed = false
                f.source.beforeSql = { sql, _ ->
                    if (sql.startsWith("INSERT INTO target_binding_commands") && !failed) {
                        failed = true
                        throw SQLException("synthetic retry after writes", "40001")
                    }
                }
                val result = c.adapter.execute(intent, "retry-once")
                f.source.beforeSql = { _, _ -> }
                assertEquals(2, c.acquires)
                assertEquals(c.acquires, c.closes)
                assertEquals(audits + 1, checkNotNull(f.database.scalar("SELECT COUNT(*) FROM audit_log")).toLong())
                assertEquals(
                    result.currentFence.generation.toString(),
                    f.database.scalar(
                        "SELECT result_generation FROM target_binding_commands WHERE key_hash='${bindingDigest(
                            "retry-once",
                        )}'",
                    ),
                )
            }
        }

    @Test fun `cancellation and changed authority before commit roll back atomic command`() =
        runBlocking<Unit> {
            for (cancel in listOf(true, false)) {
                BindingTestFixture(database()).use { f ->
                    val c = CommitMaintenanceFixture(f)
                    val intent = c.intent(f.issue(), InactiveMaintenanceAction.REVOKE)
                    val before = f.snapshot()
                    f.source.beforeSql = { sql, _ ->
                        if (sql.startsWith("INSERT INTO target_binding_commands")) {
                            if (cancel) throw CancellationException("synthetic cancel") else c.allowed = false
                        }
                    }
                    if (cancel) {
                        assertThrows<CancellationException> { c.adapter.execute(intent, "precommit") }
                    } else {
                        assertThrows<OnboardingFailure> { c.adapter.execute(intent, "precommit") }
                    }
                    f.source.beforeSql = { _, _ -> }
                    assertEquals(before, f.snapshot())
                    assertEquals(c.acquires, c.closes)
                }
            }
        }

    @Test fun `branded foreign scope and mapping epoch are unsupported rather than guessed`() =
        runBlocking<Unit> {
            BindingTestFixture(database()).use { f ->
                val c = CommitMaintenanceFixture(f)
                val intent = c.intent(f.issue())
                val before = f.snapshot()
                for (scope in listOf(
                    ClientAudience.Client(101, setOf(1001)),
                    ClientAudience.Client(102, setOf(2001)),
                )) {
                    val branded =
                        intent.copy(
                            expected =
                                intent.expected.copy(
                                    kind = BindingKind.BRANDED,
                                    audience = scope,
                                    mappingEpoch = f.epoch(scope.clientId),
                                ),
                        )
                    assertThrows<OnboardingFailure> { c.adapter.execute(branded, "branded") }
                }
                assertThrows<OnboardingFailure> {
                    c.adapter.execute(intent.copy(expected = intent.expected.copy(mappingEpoch = 1)), "foreign-epoch")
                }
                assertEquals(0, c.acquires)
                assertEquals(before, f.snapshot())
            }
        }
}
