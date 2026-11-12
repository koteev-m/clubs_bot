package com.example.bot.data.client.bindings

import com.example.bot.data.client.assignments.AssignmentTestDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.SQLException
import java.util.UUID

internal abstract class BindingChildFenceContract {
    protected abstract fun database(): AssignmentTestDatabase

    @Test fun `ordinary child change fences the original receipt`() =
        runBlocking {
            BindingTestFixture(database()).use { f ->
                val command = f.create()
                val before = f.issue(command, "child-fence")
                val legacy = f.legacy()
                f.database.execute(
                    "UPDATE target_bot_bindings SET status='DISABLED' WHERE id='${before.fence.bindingId}'",
                )
                val after = checkNotNull(f.repository.read(BindingTestFixture.EVIDENCE, before.rootId))
                val resolved = f.repository.resolve(BindingTestFixture.EVIDENCE, command, "child-fence")
                println("CHILD_FENCE before=${before.fence} after=${after.fence} resolution=${resolved.status}")
                assertEquals(before.fence.version + 1, after.fence.version)
                assertEquals(before.fence.generation + 1, after.fence.generation)
                assertEquals(BindingState.DISABLED, after.status)
                assertEquals(BindingResolutionStatus.COMPLETED_STALE, resolved.status)
                assertEquals(legacy, f.legacy())
            }
        }

    @Test fun `credential child inserts selection and retirement each fence receipts`() =
        runBlocking {
            BindingTestFixture(database()).use { f ->
                val create = f.create()
                val first = f.issue(create, "credential-create")
                val material = UUID.randomUUID()
                val reference = UUID.randomUUID()
                val audit = f.database.scalar("SELECT MIN(id) FROM audit_log")

                fun fence() =
                    runBlocking { checkNotNull(f.repository.read(BindingTestFixture.EVIDENCE, first.rootId)).fence }
                var prior = fence()
                val statements =
                    listOf(
                        "INSERT INTO target_credential_materials(id,root_id,created_audit_id) VALUES ('$material','${first.rootId}',$audit)",
                        "INSERT INTO target_credential_revisions(id,root_id,material_id,revision," +
                            "verified_bot_id,attestation_id,created_audit_id) " +
                            "VALUES ('$reference','${first.rootId}','$material',1,${first.botId},'${UUID.randomUUID()}',$audit)",
                        "UPDATE target_bot_bindings SET credential_ref='$reference' WHERE id='${first.fence.bindingId}'",
                        "UPDATE target_credential_materials SET status='RETIRED',retired_at=CURRENT_TIMESTAMP,retired_audit_id=$audit WHERE id='$material'",
                    )
                for (sql in statements) {
                    f.database.execute(sql)
                    val next = fence()
                    assertEquals(prior.version + 1, next.version)
                    assertEquals(prior.generation + 1, next.generation)
                    prior = next
                    assertEquals(
                        BindingResolutionStatus.COMPLETED_STALE,
                        f.repository.resolve(BindingTestFixture.EVIDENCE, create, "credential-create").status,
                    )
                }
                val before = f.snapshot()
                assertThrows<SQLException> { f.database.execute("UPDATE target_credential_revisions SET revision=2") }
                assertThrows<SQLException> {
                    f.database.execute(
                        "UPDATE target_credential_materials SET status='AVAILABLE',retired_at=NULL,retired_audit_id=NULL",
                    )
                }
                assertEquals(before, f.snapshot())
            }
        }

    @Test fun `failed child foreign key and explicit rollback restore root fence too`() =
        runBlocking {
            BindingTestFixture(database()).use { f ->
                val create = f.create()
                val first = f.issue(create, "rollback-create")
                val before = f.snapshot()
                assertThrows<SQLException> {
                    f.database.execute(
                        "UPDATE target_bot_bindings SET credential_ref='${UUID.randomUUID()}' WHERE id='${first.fence.bindingId}'",
                    )
                }
                assertEquals(before, f.snapshot())
                f.database.dataSource.connection.use { c ->
                    c.autoCommit = false
                    c.createStatement().use {
                        it.execute(
                            "UPDATE target_bot_bindings SET status='DISABLED' WHERE id='${first.fence.bindingId}'",
                        )
                    }
                    c.rollback()
                }
                assertEquals(before, f.snapshot())
                assertEquals(
                    BindingResolutionStatus.COMPLETED_CURRENT,
                    f.repository.resolve(BindingTestFixture.EVIDENCE, create, "rollback-create").status,
                )
            }
        }

    @Test fun `scope member changes and replacement insertion fence the same exact root`() =
        runBlocking {
            BindingTestFixture(database()).use { f ->
                val platform = f.issue()
                val root = UUID.randomUUID()
                val binding = UUID.randomUUID()
                val audit = f.database.scalar("SELECT MIN(id) FROM audit_log")
                f.database.execute(
                    "INSERT INTO target_bot_connections(id,telegram_bot_id,kind,client_id,provenance_id," +
                        "created_by,created_audit_id,last_audit_id) " +
                        "VALUES ('$root',780002,'BRANDED',101,'${UUID.randomUUID()}',78001,$audit,$audit)",
                )
                assertThrows<SQLException> {
                    f.database.execute(
                        "INSERT INTO target_credential_materials(id,root_id,created_audit_id) " +
                            "VALUES ('${UUID.randomUUID()}','$root',$audit)",
                    )
                }
                f.database.execute(
                    "INSERT INTO target_bot_bindings(id,root_id,provenance_id,created_audit_id) VALUES ('$binding','$root','${UUID.randomUUID()}',$audit)",
                    "UPDATE target_bot_connections SET current_binding_id='$binding' WHERE id='$root'",
                )
                val before = checkNotNull(f.repository.read(BindingTestFixture.EVIDENCE, root))
                f.database.execute(
                    "INSERT INTO target_binding_clubs(root_id,binding_id,client_id,club_id) VALUES ('$root','$binding',101,1001)",
                )
                val assigned = checkNotNull(f.repository.read(BindingTestFixture.EVIDENCE, root))
                assertEquals(before.fence.generation + 1, assigned.fence.generation)
                f.database.execute("DELETE FROM target_binding_clubs WHERE binding_id='$binding'")
                val removed = checkNotNull(f.repository.read(BindingTestFixture.EVIDENCE, root))
                assertEquals(assigned.fence.generation + 1, removed.fence.generation)
                assertEquals(platform, f.repository.read(BindingTestFixture.EVIDENCE, platform.rootId))
                val frozen = f.snapshot()
                assertThrows<SQLException> {
                    f.database.execute(
                        "UPDATE target_bot_connections SET current_binding_id=NULL," +
                            "version=version+1,generation=generation+1 WHERE id='$root'",
                    )
                }
                assertThrows<SQLException> {
                    f.database.execute(
                        "UPDATE target_bot_bindings SET root_id='${platform.rootId}' WHERE id='$binding'",
                    )
                }
                assertEquals(frozen, f.snapshot())
                val revoked = f.issue(BindingCommand.State(platform.rootId, platform.fence, BindingState.REVOKED))
                f.database.execute(
                    "INSERT INTO target_bot_bindings(id,root_id,provenance_id,created_audit_id) VALUES ('${UUID.randomUUID()}','${platform.rootId}','${UUID.randomUUID()}',$audit)",
                )
                val staged = checkNotNull(f.repository.read(BindingTestFixture.EVIDENCE, platform.rootId))
                assertEquals(revoked.fence.generation + 1, staged.fence.generation)
                assertEquals(BindingState.REVOKED, staged.status)
                assertTrue(staged.fence.bindingId == revoked.fence.bindingId)
            }
        }

    @Test fun `child cannot wait backward on an aggregate lock`() =
        runBlocking {
            BindingTestFixture(database()).use { f ->
                val create = f.create()
                val first = f.issue(create, "nowait-create")
                val before = f.snapshot()
                f.database.dataSource.connection.use { holder ->
                    holder.autoCommit = false
                    holder.createStatement().use {
                        it
                            .executeQuery(
                                "SELECT id FROM target_bot_connections WHERE id='${first.rootId}' FOR UPDATE",
                            ).close()
                    }
                    val failure =
                        assertThrows<SQLException> {
                            f.database.execute(
                                "UPDATE target_bot_bindings SET status='DISABLED' WHERE id='${first.fence.bindingId}'",
                            )
                        }
                    assertTrue(failure.sqlState in setOf("55P03", "HYT00"))
                    holder.rollback()
                }
                assertEquals(before, f.snapshot())
                assertEquals(
                    BindingResolutionStatus.COMPLETED_CURRENT,
                    f.repository.resolve(BindingTestFixture.EVIDENCE, create, "nowait-create").status,
                )
            }
        }
}
