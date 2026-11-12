package com.example.bot.data.client.bindings

import com.example.bot.data.client.assignments.AssignmentTestDatabase
import com.example.bot.data.client.onboarding.OnboardingFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.SQLException
import java.util.UUID

internal abstract class InactiveBindingContract {
    protected lateinit var fixture: BindingTestFixture

    protected abstract fun database(): AssignmentTestDatabase

    @BeforeEach fun prepare() {
        fixture = BindingTestFixture(database())
    }

    @AfterEach fun close() {
        if (::fixture.isInitialized) fixture.close()
    }

    @Test fun `new tables are empty and default providers never create connections or authority`() =
        runBlocking {
            val f = fixture
            BindingTestFixture.TABLES.forEach { assertEquals("0", f.database.scalar("SELECT COUNT(*) FROM $it")) }
            val before = f.snapshot()
            val denied = JdbcInactiveBindingFoundation(f.source, BindingTestFixture.TELEGRAM, clock = f.clock)
            assertThrows<OnboardingFailure> {
                runBlocking { denied.mutate(BindingTestFixture.EVIDENCE, f.create(), "unknown-source") }
            }
            assertEquals(before, f.snapshot())
        }

    @Test fun `create read and unknown resolution are inactive metadata without staff permissions`() =
        runBlocking {
            val f = fixture
            val legacy = f.legacy()
            val command = f.create()
            val unknown = f.repository.resolve(BindingTestFixture.EVIDENCE, command, "first")
            assertEquals(BindingResolutionStatus.UNKNOWN, unknown.status)
            assertNull(unknown.current)
            val current = f.issue(command, "first")
            assertEquals(BindingState.PENDING, current.status)
            assertEquals(BindingKind.PLATFORM, current.kind)
            assertNull(current.clientId)
            assertTrue(current.clubs.isEmpty())
            assertEquals(BindingFence(command.bindingId, 1, 1), current.fence)
            val before = f.snapshot()
            assertEquals(current, f.repository.read(BindingTestFixture.EVIDENCE, current.rootId))
            assertEquals(
                BindingResolutionStatus.COMPLETED_CURRENT,
                f.repository.resolve(BindingTestFixture.EVIDENCE, command, "first").status,
            )
            assertEquals(before, f.snapshot())
            assertEquals(legacy, f.legacy())
        }

    @Test fun `duplicate bot root and incarnation reject without partial audit`() =
        runBlocking {
            val f = fixture
            val command = f.create()
            f.issue(command)
            val before = f.snapshot()
            assertThrows<OnboardingFailure> { runBlocking { f.issue(command) } }
            assertThrows<SQLException> { runBlocking { f.issue(f.create()) } }
            assertEquals(before, f.snapshot())
        }

    @Test fun `disable revoke and recreate use monotonic independent incarnation IDs`() =
        runBlocking {
            val f = fixture
            val first = f.issue()
            val disabled = f.issue(BindingCommand.State(first.rootId, first.fence, BindingState.DISABLED))
            assertEquals(3L, disabled.fence.generation)
            val revoked = f.issue(BindingCommand.State(first.rootId, disabled.fence, BindingState.REVOKED))
            val nextId = UUID.randomUUID()
            val next = f.issue(BindingCommand.Replace(first.rootId, revoked.fence, nextId, UUID.randomUUID()))
            assertEquals(BindingState.PENDING, next.status)
            assertEquals(BindingFence(nextId, 7, 7), next.fence)
            assertEquals(
                "REVOKED",
                f.database.scalar("SELECT status FROM target_bot_bindings WHERE id='${first.fence.bindingId}'"),
            )
            val before = f.snapshot()
            assertThrows<OnboardingFailure> {
                runBlocking { f.issue(BindingCommand.State(first.rootId, first.fence, BindingState.DISABLED)) }
            }
            assertEquals(before, f.snapshot())
        }

    @Test fun `activation is unavailable even with current maintenance authority`() =
        runBlocking {
            val f = fixture
            val first = f.issue()
            val before = f.snapshot()
            assertThrows<OnboardingFailure> {
                runBlocking { f.issue(BindingCommand.State(first.rootId, first.fence, BindingState.ACTIVE)) }
            }
            assertThrows<SQLException> { f.database.execute("UPDATE target_bot_bindings SET status='ACTIVE'") }
            assertEquals(before, f.snapshot())
        }

    @Test fun `terminal tombstones cannot be directly updated deleted or reused`() =
        runBlocking {
            val f = fixture
            val first = f.issue()
            val revoked = f.issue(BindingCommand.State(first.rootId, first.fence, BindingState.REVOKED))
            val before = f.snapshot()
            assertThrows<OnboardingFailure> {
                runBlocking { f.issue(BindingCommand.State(first.rootId, revoked.fence, BindingState.ACTIVE)) }
            }
            for (sql in listOf(
                "UPDATE target_bot_bindings SET status='PENDING',revoked_at=NULL,revoked_audit_id=NULL",
                "DELETE FROM target_bot_bindings",
                "DELETE FROM target_bot_connections",
                "DELETE FROM target_binding_commands",
                "UPDATE target_binding_commands SET result_generation=result_generation+1",
                "UPDATE target_bot_connections SET generation=1,version=1",
            )) {
                assertThrows<SQLException> { f.database.execute(sql) }
            }
            assertEquals(before, f.snapshot())
        }

    @Test fun `old incarnation selection and changed replay intent deny`() =
        runBlocking {
            val f = fixture
            val command = f.create()
            val first = f.issue(command, "immutable-intent")
            assertThrows<OnboardingFailure> {
                runBlocking { f.issue(command.copy(bindingId = UUID.randomUUID()), "immutable-intent") }
            }
            val revoked = f.issue(BindingCommand.State(first.rootId, first.fence, BindingState.REVOKED))
            val replacement = BindingCommand.Replace(first.rootId, revoked.fence, UUID.randomUUID(), UUID.randomUUID())
            val second = f.issue(replacement)
            f.issue(BindingCommand.State(first.rootId, second.fence, BindingState.REVOKED))
            val before = f.snapshot()
            assertThrows<SQLException> {
                f.database.execute(
                    "UPDATE target_bot_connections SET current_binding_id='${first.fence.bindingId}'," +
                        "version=version+1,generation=generation+1 WHERE id='${first.rootId}'",
                )
            }
            assertEquals(before, f.snapshot())
        }

    @Test fun `replay is current-only and stale receipt cannot execute after revoke`() =
        runBlocking {
            val f = fixture
            val command = f.create()
            val first = f.issue(command, "same-key")
            val before = f.snapshot()
            assertTrue(f.repository.mutate(BindingTestFixture.EVIDENCE, command, "same-key").replayed)
            assertEquals(before, f.snapshot())
            f.issue(BindingCommand.State(first.rootId, first.fence, BindingState.REVOKED))
            val revoked = f.snapshot()
            assertThrows<OnboardingFailure> { runBlocking { f.issue(command, "same-key") } }
            assertEquals(
                BindingResolutionStatus.COMPLETED_STALE,
                f.repository.resolve(BindingTestFixture.EVIDENCE, command, "same-key").status,
            )
            assertEquals(revoked, f.snapshot())
        }

    @Test fun `reference retirement fences aliases and new incarnation does not select old material`() =
        runBlocking {
            val f = fixture
            val first = f.issue()
            val attach = f.attach(first)
            val attached = f.issue(attach)
            assertEquals(attach.referenceId, attached.credentialReference)
            val retired = f.issue(BindingCommand.RetireMaterial(first.rootId, attached.fence, attach.materialId))
            assertEquals(BindingState.DISABLED, retired.status)
            assertNull(retired.credentialReference)
            val before = f.snapshot()
            assertThrows<OnboardingFailure> { runBlocking { f.issue(f.attach(retired, attach.materialId, 2)) } }
            assertEquals(before, f.snapshot())
            assertThrows<SQLException> {
                f.database.execute(
                    "UPDATE target_credential_materials SET status='AVAILABLE',retired_at=NULL,retired_audit_id=NULL",
                )
            }
            val fresh = f.issue(f.attach(retired, revision = 2))
            val revoked = f.issue(BindingCommand.State(first.rootId, fresh.fence, BindingState.REVOKED))
            val next =
                f.issue(
                    BindingCommand.Replace(first.rootId, revoked.fence, UUID.randomUUID(), UUID.randomUUID()),
                )
            assertNull(next.credentialReference)
            assertThrows<OnboardingFailure> { runBlocking { f.issue(f.attach(next, attach.materialId, 3)) } }
        }

    @Test fun `missing bot or material evidence and stale versions fail closed`() =
        runBlocking {
            val f = fixture
            f.botAllowed = false
            assertThrows<OnboardingFailure> { runBlocking { f.issue() } }
            f.botAllowed = true
            val first = f.issue()
            f.referencesAllowed = false
            val before = f.snapshot()
            assertThrows<OnboardingFailure> { runBlocking { f.issue(f.attach(first)) } }
            f.referencesAllowed = true
            assertThrows<OnboardingFailure> {
                runBlocking {
                    f.issue(
                        BindingCommand.State(first.rootId, first.fence.copy(generation = 9), BindingState.DISABLED),
                    )
                }
            }
            assertEquals(before, f.snapshot())
        }

    @Test fun `audit or receipt failure rolls back roots references materials and generation`() =
        runBlocking {
            val f = fixture
            for (prefix in listOf("INSERT INTO audit_log", "INSERT INTO target_binding_commands")) {
                val before = f.snapshot()
                f.source.beforeSql =
                    { sql, _ -> if (sql.startsWith(prefix)) throw SQLException("synthetic failure", "23514") }
                assertThrows<SQLException> { runBlocking { f.issue() } }
                f.source.beforeSql = { _, _ -> }
                assertEquals(before, f.snapshot())
            }
        }

    @Test fun `schema enforces branded finite subset and forbids cross-client scope without activating it`() =
        runBlocking {
            val f = fixture
            f.issue()
            val root = UUID.randomUUID()
            val binding = UUID.randomUUID()
            val provenance = UUID.randomUUID()
            val audit = f.database.scalar("SELECT MIN(id) FROM audit_log")
            f.database.execute(
                "INSERT INTO target_bot_connections(id,telegram_bot_id,kind,client_id,provenance_id," +
                    "created_by,created_audit_id,last_audit_id) " +
                    "VALUES ('$root',780002,'BRANDED',101,'$provenance',78001,$audit,$audit)",
                "INSERT INTO target_bot_bindings(id,root_id,provenance_id,created_audit_id) VALUES ('$binding','$root','$provenance',$audit)",
                "UPDATE target_bot_connections SET current_binding_id='$binding' WHERE id='$root'",
                "INSERT INTO target_binding_clubs(root_id,binding_id,client_id,club_id) VALUES ('$root','$binding',101,1001)",
                "INSERT INTO client_clubs(client_id,club_id) VALUES (101,1003)",
            )
            assertEquals(listOf(1001L), f.repository.read(BindingTestFixture.EVIDENCE, root)?.clubs)
            for (sql in listOf(
                "INSERT INTO target_binding_clubs(root_id,binding_id,client_id,club_id) VALUES ('$root','$binding',101,2001)",
                "INSERT INTO target_binding_clubs(root_id,binding_id,client_id,club_id) VALUES ('$root','$binding',102,2001)",
                "UPDATE target_bot_connections SET client_id=102,version=2,generation=2 WHERE id='$root'",
            )) {
                assertThrows<SQLException> { f.database.execute(sql) }
            }
            val view = checkNotNull(f.repository.read(BindingTestFixture.EVIDENCE, root))
            assertThrows<OnboardingFailure> {
                runBlocking { f.issue(BindingCommand.State(root, view.fence, BindingState.DISABLED)) }
            }
            assertEquals(BindingState.PENDING, view.status)
        }

    @Test fun `authority expiry cancellation and SQL retry do not issue partial state`() =
        runBlocking {
            val f = fixture
            val before = f.snapshot()
            f.clock.now = BindingTestFixture.START.plusSeconds(240)
            assertThrows<OnboardingFailure> { runBlocking { f.issue() } }
            f.clock.now = BindingTestFixture.START
            f.source.beforeSql =
                {
                        sql,
                        _,
                    ->
                    if (sql.startsWith("INSERT INTO target_bot_connections")) throw CancellationException("synthetic")
                }
            assertThrows<CancellationException> { runBlocking { f.issue() } }
            assertEquals(before, f.snapshot())
            var failed = false
            f.source.beforeSql =
                { sql, _ ->
                    if (!failed &&
                        sql.startsWith("INSERT INTO target_bot_connections")
                    ) {
                        failed = true
                        f.identityAllowed = false
                        throw SQLException("synthetic retry", "40001")
                    }
                }
            assertThrows<OnboardingFailure> { runBlocking { f.issue() } }
            assertTrue(failed)
            assertEquals(before, f.snapshot())
        }
}
