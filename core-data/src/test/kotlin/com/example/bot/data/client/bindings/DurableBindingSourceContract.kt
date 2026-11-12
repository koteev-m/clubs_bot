package com.example.bot.data.client.bindings

import com.example.bot.data.client.CanonicalAudienceRecordingSource
import com.example.bot.data.client.ClientAudience
import com.example.bot.data.client.assignments.AssignmentTestDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.SQLException
import java.util.UUID

internal abstract class DurableBindingSourceContract {
    protected abstract fun database(): AssignmentTestDatabase

    @Test fun `platform draft is metadata only and repeated reads have no database effects`() =
        runBlocking<Unit> {
            BindingTestFixture(database()).use { f ->
                val view = f.issue()
                val source = f.durableSource()
                val reference = f.reference(view)
                val before = f.snapshot() to f.legacy()
                f.source.statements.clear()
                repeat(2) {
                    val snapshot = (source.read(reference) as DurableBindingRead.Inactive).snapshot
                    assertEquals(view.rootId, snapshot.rootId)
                    assertEquals(view.fence, snapshot.fence)
                    assertEquals(BindingState.PENDING, snapshot.status)
                    assertEquals(ClientAudience.Platform, snapshot.audience)
                    assertEquals(null, snapshot.mappingEpoch)
                    assertEquals(null, snapshot.credential)
                    assertTrue(snapshot.toString().contains("not-authorized"))
                    assertFalse(snapshot.toString().contains(view.rootId.toString()))
                }
                assertEquals(2, f.source.statements.size)
                assertTrue(f.source.statements.all { it.trimStart().startsWith("SELECT") })
                assertEquals(before, f.snapshot() to f.legacy())
            }
        }

    @Test fun `missing selector absent records and wrong root never select a fallback`() =
        runBlocking<Unit> {
            BindingTestFixture(database()).use { f ->
                val view = f.issue()
                val source = f.durableSource()
                f.source.statements.clear()
                assertEquals(DurableBindingRead.MissingSelection, source.read(null))
                assertTrue(f.source.statements.isEmpty())
                val ref = f.reference(view)
                assertRejected(
                    source.read(ref.copy(audience = ClientAudience.Client(101, setOf(1001)))),
                    BindingReadRejection.SCOPE,
                )
                assertEquals(DurableBindingRead.NotFound, source.read(ref.copy(rootId = UUID.randomUUID())))
                assertEquals(
                    DurableBindingRead.NotFound,
                    source.read(ref.copy(fence = ref.fence.copy(bindingId = UUID.randomUUID()))),
                )
                val root = f.create().copy(botId = 780009)
                val other = f.issue(root)
                assertEquals(DurableBindingRead.Inconsistent, source.read(ref.copy(rootId = other.rootId)))
                assertRejected(source.read(ref.copy(telegramBotId = view.botId + 1)), BindingReadRejection.IDENTITY)
            }
        }

    @Test fun `aggregate version generation incarnation and credential pins are exact`() =
        runBlocking<Unit> {
            BindingTestFixture(database()).use { f ->
                val first = f.issue()
                val ref = f.reference(first)
                val source = f.durableSource()
                assertRejected(source.read(ref.copy(fence = ref.fence.copy(version = 2))), BindingReadRejection.FENCE)
                assertRejected(
                    source.read(ref.copy(fence = ref.fence.copy(generation = 2))),
                    BindingReadRejection.FENCE,
                )
                val next = f.issue(f.attach(first))
                assertRejected(source.read(ref), BindingReadRejection.FENCE)
                assertRejected(source.read(f.reference(next)), BindingReadRejection.CREDENTIAL_REVISION)
                val snapshot = (source.read(f.reference(next, revision = 1)) as DurableBindingRead.Inactive).snapshot
                assertEquals(next.credentialReference, snapshot.credential?.referenceId)
                assertEquals(CredentialMaterialState.AVAILABLE, snapshot.credential?.state)
                assertFalse(snapshot.credential.toString().contains(next.credentialReference.toString()))
            }
        }

    @Test fun `terminal revoke replacement and selected retired material stay non-authorizing`() =
        runBlocking<Unit> {
            BindingTestFixture(database()).use { f ->
                val initial = f.issue()
                val command = f.attach(initial)
                val attached = f.issue(command)
                val audit = f.database.scalar("SELECT MIN(id) FROM audit_log")
                f.database.execute(
                    "UPDATE target_credential_materials SET status='RETIRED',retired_at=CURRENT_TIMESTAMP," +
                        "retired_audit_id=$audit WHERE id='${command.materialId}'",
                )
                val current = checkNotNull(f.repository.read(BindingTestFixture.EVIDENCE, initial.rootId))
                val source = f.durableSource()
                assertRejected(source.read(f.reference(attached, revision = 1)), BindingReadRejection.FENCE)
                val retired = source.read(f.reference(current, revision = 1)) as DurableBindingRead.Inactive
                assertEquals(CredentialMaterialState.RETIRED, retired.snapshot.credential?.state)
                val revoked = f.issue(BindingCommand.State(current.rootId, current.fence, BindingState.REVOKED))
                assertTrue(source.read(f.reference(revoked, revision = 1)) is DurableBindingRead.Revoked)
                val replacement =
                    f.issue(
                        BindingCommand.Replace(revoked.rootId, revoked.fence, UUID.randomUUID(), UUID.randomUUID()),
                    )
                assertRejected(source.read(f.reference(revoked, revision = 1)), BindingReadRejection.FENCE)
                assertTrue(source.read(f.reference(replacement)) is DurableBindingRead.Inactive)
                assertTrue(replacement.fence.generation > revoked.fence.generation)
            }
        }

    @Test fun `branded scope is an exact finite same-client subset including private clubs`() =
        runBlocking<Unit> {
            BindingTestFixture(database()).use { f ->
                f.issue()
                val view = f.branded()
                val source = f.durableSource()
                val ref = f.reference(view, ClientAudience.Client(101, setOf(1001)))
                val snapshot = (source.read(ref) as DurableBindingRead.Inactive).snapshot
                assertEquals(setOf(1001L), (snapshot.audience as ClientAudience.Client).allowedClubIds)
                assertNotNull(snapshot.clientLifecycleVersion)
                val invalid =
                    listOf(
                        ClientAudience.Platform,
                        ClientAudience.Client(102, setOf(2001)),
                        ClientAudience.Client(101, setOf(1002)),
                        ClientAudience.Client(101, setOf(1001, 1002)),
                        ClientAudience.Client(101, emptySet()),
                    )
                for (audience in invalid) {
                    assertRejected(
                        source.read(ref.copy(audience = audience)),
                        BindingReadRejection.SCOPE,
                    )
                }
                f.database.execute("INSERT INTO client_clubs(client_id,club_id) VALUES (101,1003)")
                assertRejected(source.read(ref), BindingReadRejection.MAPPING_EPOCH)
                val fresh = ref.copy(mappingEpoch = f.epoch())
                assertTrue(source.read(fresh) is DurableBindingRead.Inactive)
                assertRejected(
                    source.read(fresh.copy(audience = ClientAudience.Client(101, setOf(1001, 1003)))),
                    BindingReadRejection.SCOPE,
                )
                f.database.execute(
                    "INSERT INTO target_binding_clubs(root_id,binding_id,client_id,club_id) " +
                        "VALUES ('${view.rootId}','${view.fence.bindingId}',101,1002)",
                )
                val expanded = checkNotNull(f.repository.read(BindingTestFixture.EVIDENCE, view.rootId))
                val explicit = f.reference(expanded, ClientAudience.Client(101, setOf(1001, 1002)))
                val two = source.read(explicit) as DurableBindingRead.Inactive
                assertEquals(setOf(1001L, 1002L), (two.snapshot.audience as ClientAudience.Client).allowedClubIds)
                assertRejected(source.read(explicit.copy(audience = ref.audience)), BindingReadRejection.SCOPE)
            }
        }

    @Test fun `inactive archived missing and changed canonical scope fail closed`() =
        runBlocking<Unit> {
            BindingTestFixture(database()).use { f ->
                f.issue()
                val view = f.branded()
                val source = f.durableSource()
                val audience = ClientAudience.Client(101, setOf(1001))
                f.database.execute("UPDATE clients SET is_active=FALSE WHERE id=101")
                assertRejected(source.read(f.reference(view, audience)), BindingReadRejection.CLIENT_STATE)
                f.database.execute("UPDATE clients SET is_active=TRUE,archived_at=CURRENT_TIMESTAMP WHERE id=101")
                assertRejected(source.read(f.reference(view, audience)), BindingReadRejection.CLIENT_STATE)
                f.database.execute("UPDATE clients SET archived_at=NULL WHERE id=101")
                f.database.execute("DELETE FROM target_binding_clubs WHERE binding_id='${view.fence.bindingId}'")
                val current = checkNotNull(f.repository.read(BindingTestFixture.EVIDENCE, view.rootId))
                assertRejected(source.read(f.reference(current, audience)), BindingReadRejection.SCOPE)
                assertThrows<SQLException> {
                    f.database.execute(
                        "INSERT INTO target_binding_clubs(root_id,binding_id,client_id,club_id) " +
                            "VALUES ('${view.rootId}','${view.fence.bindingId}',101,2001)",
                    )
                }
            }
        }

    @Test fun `durable epoch detects mapping ABA and projection head mismatch`() =
        runBlocking<Unit> {
            BindingTestFixture(database()).use { f ->
                f.issue()
                val view = f.branded()
                val source = f.durableSource()
                val ref = f.reference(view, ClientAudience.Client(101, setOf(1001)))
                f.database.execute(
                    "DELETE FROM client_clubs WHERE client_id=101 AND club_id=1002",
                    "INSERT INTO client_clubs(client_id,club_id,created_at) VALUES (101,1002,'2026-11-11T00:00:00Z')",
                )
                assertEquals(ref.mappingEpoch!! + 2, f.epoch())
                assertRejected(source.read(ref), BindingReadRejection.MAPPING_EPOCH)
                assertTrue(source.read(ref.copy(mappingEpoch = f.epoch())) is DurableBindingRead.Inactive)
                f.database.execute("UPDATE target_client_mapping_epochs SET epoch=epoch+1 WHERE client_id=101")
                assertRejected(source.read(ref.copy(mappingEpoch = f.epoch())), BindingReadRejection.MAPPING_EPOCH)
            }
        }

    @Test fun `child changes stale receipts and next read but rollback preserves original snapshot`() =
        runBlocking<Unit> {
            BindingTestFixture(database()).use { f ->
                val create = f.create()
                val first = f.issue(create, "durable-create")
                val ref = f.reference(first)
                val source = f.durableSource()
                val before = source.read(ref)
                f.database.dataSource.connection.use { c ->
                    c.autoCommit = false
                    c.createStatement().use {
                        it.execute(
                            "UPDATE target_bot_bindings SET status='DISABLED' WHERE id='${first.fence.bindingId}'",
                        )
                    }
                    c.rollback()
                }
                assertEquals(before, source.read(ref))
                f.database.execute(
                    "UPDATE target_bot_bindings SET status='DISABLED' WHERE id='${first.fence.bindingId}'",
                )
                assertRejected(source.read(ref), BindingReadRejection.FENCE)
                val current = checkNotNull(f.repository.read(BindingTestFixture.EVIDENCE, first.rootId))
                assertEquals(first.fence.generation + 1, current.fence.generation)
                assertEquals(
                    BindingState.DISABLED,
                    (source.read(f.reference(current)) as DurableBindingRead.Inactive).snapshot.status,
                )
                assertEquals(
                    BindingResolutionStatus.COMPLETED_STALE,
                    f.repository.resolve(BindingTestFixture.EVIDENCE, create, "durable-create").status,
                )
            }
        }

    @Test fun `SQL errors do not leak and cancellation propagates instead of becoming denial`() =
        runBlocking<Unit> {
            BindingTestFixture(database()).use { f ->
                val ref = f.reference(f.issue())
                val source = f.durableSource()
                val before = f.snapshot()
                f.source.beforeSql = { _, _ -> throw SQLException("synthetic confidential driver detail") }
                assertEquals(DurableBindingRead.ReadFailed, source.read(ref))
                f.source.beforeSql = { _, _ -> throw CancellationException("synthetic cancellation") }
                assertThrows<CancellationException> { source.read(ref) }
                f.source.beforeSql = { _, _ -> }
                assertEquals(before, f.snapshot())
                assertTrue(source.read(ref) is DurableBindingRead.Inactive)
                assertThrows<IllegalArgumentException> {
                    BindingStorageSchema.fromServerConfiguration(
                        "untrusted;schema",
                    )
                }
                for (name in listOf("pg_temp", "pg_temp_3", "PG_CATALOG", "information_schema")) {
                    assertThrows<IllegalArgumentException> { BindingStorageSchema.fromServerConfiguration(name) }
                }
                val absentSchema =
                    JdbcDurableBindingSource(f.source, BindingStorageSchema.fromServerConfiguration("no_such_schema"))
                assertEquals(DurableBindingRead.ReadFailed, absentSchema.read(ref))
            }
        }

    @Test fun `cleanup failure cannot produce success or mask cancellation`() =
        runBlocking<Unit> {
            BindingTestFixture(database()).use { f ->
                val ref = f.reference(f.issue())
                val recording = CanonicalAudienceRecordingSource(f.database.dataSource)
                val schema =
                    f.database.dataSource.connection
                        .use { checkNotNull(it.schema) }
                val source = JdbcDurableBindingSource(recording, BindingStorageSchema.fromServerConfiguration(schema))
                recording.rollbackFailure = SQLException("synthetic cleanup detail")
                assertEquals(DurableBindingRead.ReadFailed, source.read(ref))
                recording.afterQuery = { throw CancellationException("cancel with cleanup failure") }
                assertThrows<CancellationException> { source.read(ref) }
                assertEquals(listOf(true, true), recording.readOnly)
                assertEquals(listOf(false, false), recording.autoCommit)
                assertEquals(2, recording.rollbacks)
            }
        }

    @Test fun `ACTIVE remains prohibited at SQL and service boundaries`() =
        runBlocking<Unit> {
            BindingTestFixture(database()).use { f ->
                val first = f.issue()
                val before = f.snapshot()
                assertThrows<SQLException> {
                    f.database.execute(
                        "UPDATE target_bot_bindings SET status='ACTIVE' WHERE id='${first.fence.bindingId}'",
                    )
                }
                assertThrows<RuntimeException> {
                    f.issue(
                        BindingCommand.State(first.rootId, first.fence, BindingState.ACTIVE),
                    )
                }
                assertEquals(before, f.snapshot())
                assertTrue(f.durableSource().read(f.reference(first)) is DurableBindingRead.Inactive)
            }
        }
}

internal fun BindingTestFixture.durableSource(): JdbcDurableBindingSource {
    val schema = database.dataSource.connection.use { it.schema }
    return JdbcDurableBindingSource(source, BindingStorageSchema.fromServerConfiguration(schema))
}

internal fun BindingTestFixture.reference(
    view: BindingView,
    audience: ClientAudience = ClientAudience.Platform,
    revision: Long? = null,
): ServerSelectedBindingReference =
    ServerSelectedBindingReference(
        view.rootId,
        view.fence,
        view.botId,
        audience,
        if (audience is ClientAudience.Client) epoch(audience.clientId) else null,
        revision,
    )

internal fun BindingTestFixture.branded(): BindingView {
    val root = UUID.randomUUID()
    val binding = UUID.randomUUID()
    val audit = database.scalar("SELECT MIN(id) FROM audit_log")
    database.execute(
        "INSERT INTO target_bot_connections(id,telegram_bot_id,kind,client_id,provenance_id," +
            "created_by,created_audit_id,last_audit_id) " +
            "VALUES ('$root',780002,'BRANDED',101,'${UUID.randomUUID()}',78001,$audit,$audit)",
        "INSERT INTO target_bot_bindings(id,root_id,provenance_id,created_audit_id) " +
            "VALUES ('$binding','$root','${UUID.randomUUID()}',$audit)",
        "UPDATE target_bot_connections SET current_binding_id='$binding' WHERE id='$root'",
        "INSERT INTO target_binding_clubs(root_id,binding_id,client_id,club_id) VALUES ('$root','$binding',101,1001)",
    )
    return runBlocking { checkNotNull(repository.read(BindingTestFixture.EVIDENCE, root)) }
}

internal fun assertRejected(
    result: DurableBindingRead,
    reason: BindingReadRejection,
) {
    assertEquals(DurableBindingRead.Rejected(reason), result)
}
