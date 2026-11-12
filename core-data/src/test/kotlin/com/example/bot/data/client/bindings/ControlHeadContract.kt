package com.example.bot.data.client.bindings

import com.example.bot.data.client.assignments.AssignmentTestDatabase
import com.example.bot.data.client.onboarding.OnboardingFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.util.UUID

internal abstract class ControlHeadContract {
    protected abstract fun database(target: String? = null): AssignmentTestDatabase

    @Test fun `V079 upgrades populated V078 without adoption or altered legacy and binding data`() {
        database("78").use { db ->
            val f = BindingTestFixture(db)
            runBlocking { f.issue() }
            db.execute(
                "INSERT INTO client_memberships(client_id,user_id,is_active) VALUES(101,78001,FALSE)",
                "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active) " +
                    "VALUES(101,78001,1001,'client.club.read',FALSE)",
            )
            val tables = BindingTestFixture.TABLES + listOf("users", "client_memberships", "client_club_grants")
            val before = tables.associateWith { db.rows("SELECT * FROM $it ORDER BY 1") }
            assertEquals(1, db.migrate())
            assertEquals(before, tables.associateWith { db.rows("SELECT * FROM $it ORDER BY 1") })
            assertEquals("0", db.scalar("SELECT COUNT(*) FROM target_maintenance_control_heads"))
            assertEquals("0", db.scalar("SELECT COUNT(*) FROM target_maintenance_publications"))
            assertEquals(0, db.migrate())
        }
    }

    @Test fun `default publisher and an absent head never provide authority`() =
        fixture { f ->
            val denied = SyntheticControlHeadPublisher(f.binding.source, f.schema)
            assertThrows(OnboardingFailure::class.java) { runBlocking { denied.create(f.scope) } }
            f.binding.database.dataSource.connection.use { connection ->
                connection.autoCommit = false
                assertThrows(
                    OnboardingFailure::class.java,
                ) { JdbcControlHeadLease(f.schema, f.enabled).verify(connection) }
                connection.rollback()
            }
            assertEquals("0", f.binding.database.scalar("SELECT COUNT(*) FROM target_maintenance_control_heads"))
        }

    @Test fun `fence first lifecycle is monotonic and never recreates an old manifest`() =
        fixture { f ->
            val disabled = f.publisher.create(f.scope)
            assertEquals(ControlPublicationStatus.DISABLED, disabled.status)
            val enabled = f.publisher.publish(disabled, f.manifest, f.digest)
            assertEquals(f.enabled, enabled)
            assertThrows(OnboardingFailure::class.java) {
                runBlocking { f.publisher.publish(enabled, UUID.randomUUID(), f.digest) }
            }
            val fenced = f.publisher.fence(enabled)
            assertEquals(3, fenced.generation)
            assertThrows(SQLException::class.java) { runBlocking { f.publisher.publish(fenced, f.manifest, f.digest) } }
            assertEquals(fenced, f.read())
            val next = f.publisher.publish(fenced, UUID.randomUUID(), "b".repeat(64))
            assertEquals(4, next.generation)
            assertNotEquals(enabled.manifestId, next.manifestId)
            assertEquals("4", f.binding.database.scalar("SELECT COUNT(*) FROM target_maintenance_publications"))
        }

    @Test fun `lease is exact connection transaction and close bound`() =
        fixture { f ->
            f.publish()
            f.binding.database.dataSource.connection.use { connection ->
                val lease = JdbcControlHeadLease(f.schema, f.enabled)
                assertThrows(OnboardingFailure::class.java) { lease.verify(connection) }
                connection.autoCommit = false
                assertEquals(f.enabled, lease.verify(connection))
                assertEquals(f.enabled, lease.verify(connection))
                f.binding.database.dataSource.connection.use { foreign ->
                    foreign.autoCommit = false
                    assertThrows(OnboardingFailure::class.java) { lease.verify(foreign) }
                    foreign.rollback()
                }
                connection.commit()
                assertThrows(OnboardingFailure::class.java) { lease.verify(connection) }
                connection.rollback()
                val fresh = JdbcControlHeadLease(f.schema, f.enabled)
                fresh.verify(connection)
                connection.rollback()
                assertThrows(OnboardingFailure::class.java) { fresh.verify(connection) }
                fresh.close()
                assertThrows(OnboardingFailure::class.java) { fresh.verify(connection) }
                connection.rollback()
            }
        }

    @Test fun `disabled missing stale foreign scope digest and schema fail closed`() =
        fixture { f ->
            val disabled = f.publisher.create(f.scope)
            f.withConnection { connection ->
                assertThrows(
                    OnboardingFailure::class.java,
                ) { JdbcControlHeadLease(f.schema, disabled).verify(connection) }
            }
            f.publisher.publish(disabled, f.manifest, f.digest)
            for (bad in listOf(
                f.enabled.copy(version = 3, generation = 3),
                f.enabled.copy(scope = f.scope.copy(id = UUID.randomUUID())),
                f.enabled.copy(scope = f.scope.copy(rootId = UUID.randomUUID())),
                f.enabled.copy(scope = f.scope.copy(authorityConnectionId = UUID.randomUUID())),
                f.enabled.copy(manifestId = UUID.randomUUID()),
                f.enabled.copy(manifestDigest = "b".repeat(64)),
            )) {
                f.withConnection { connection ->
                    assertThrows(
                        OnboardingFailure::class.java,
                    ) { JdbcControlHeadLease(f.schema, bad).verify(connection) }
                }
            }
            f.withConnection { connection ->
                val unknown = BindingStorageSchema.fromServerConfiguration("missing_trusted_schema")
                assertThrows(SQLException::class.java) { JdbcControlHeadLease(unknown, f.enabled).verify(connection) }
            }
        }

    @Test fun `ordinary head and publication DML cannot erase identity history or rewind generation`() =
        fixture { f ->
            f.publish()
            for (sql in listOf(
                "DELETE FROM target_maintenance_control_heads",
                "DELETE FROM target_maintenance_publications",
                "UPDATE target_maintenance_control_heads SET version=1,generation=1",
                "UPDATE target_maintenance_control_heads SET root_id='${UUID.randomUUID()}'",
                "UPDATE target_maintenance_control_heads SET manifest_digest='broken'",
                "UPDATE target_maintenance_publications SET status='DISABLED'",
            )) {
                assertThrows(SQLException::class.java) { f.binding.database.execute(sql) }
                assertEquals(f.enabled, f.read())
            }
            assertThrows(SQLException::class.java) { runBlocking { f.publisher.create(f.scope) } }
            assertEquals("2", f.binding.database.scalar("SELECT COUNT(*) FROM target_maintenance_publications"))
        }

    @Test fun `stale publisher CAS writes neither head nor audit history`() =
        fixture { f ->
            f.publish()
            val before = f.rows()
            assertThrows(OnboardingFailure::class.java) {
                runBlocking { f.publisher.fence(f.enabled.copy(version = 1, generation = 1)) }
            }
            assertEquals(before, f.rows())
        }

    @Test fun `publisher authority loss cancellation and audit failure roll back all metadata`() =
        fixture { f ->
            f.publish()
            val before = f.rows()
            var checks = 0
            f.onAuthority = { if (++checks > 2) null else f.identity }
            assertThrows(OnboardingFailure::class.java) { runBlocking { f.publisher.fence(f.enabled) } }
            assertEquals(before, f.rows())
            checks = 0
            f.onAuthority = {
                if (++checks > 2) throw CancellationException("synthetic publisher cancel") else f.identity
            }
            assertThrows(CancellationException::class.java) { runBlocking { f.publisher.fence(f.enabled) } }
            f.onAuthority = { f.identity }
            f.binding.source.beforeSql = { sql, _ ->
                if (sql.contains("INSERT INTO") &&
                    sql.contains("audit_log")
                ) {
                    throw SQLException("synthetic audit", "23514")
                }
            }
            assertThrows(SQLException::class.java) { runBlocking { f.publisher.fence(f.enabled) } }
            f.binding.source.beforeSql = { _, _ -> }
            assertEquals(before, f.rows())
        }

    @Test fun `metadata read never creates grants or activates a binding`() =
        fixture { f ->
            f.publish()
            val before = f.rows()
            repeat(2) {
                f.withConnection { connection ->
                    JdbcControlHeadLease(f.schema, f.enabled).verify(connection)
                }
            }
            assertEquals(before, f.rows())
            assertEquals("PENDING", f.binding.database.scalar("SELECT status FROM target_bot_bindings"))
            for (table in listOf(
                "client_role_assignments",
                "client_permission_delegations",
                "client_club_access_state",
            )) {
                assertEquals("0", f.binding.database.scalar("SELECT COUNT(*) FROM $table"))
            }
        }

    @Test fun `bare evidence writer refuses adopted control scope before all writes`() =
        fixture { f ->
            f.publish()
            val before = f.rows()
            val view = f.binding.repository.read(BindingTestFixture.EVIDENCE, f.scope.rootId)!!
            assertThrows(OnboardingFailure::class.java) {
                runBlocking {
                    f.binding.repository.mutate(
                        BindingTestFixture.EVIDENCE,
                        BindingCommand.State(view.rootId, view.fence, BindingState.DISABLED),
                        "blocked-unleased-writer",
                    )
                }
            }
            assertEquals(before, f.rows())
        }

    @Test fun `old guarded authorization and a string marker cannot bypass an adopted DB lease`() =
        fixture { f ->
            f.publish()
            val view = f.binding.repository.read(BindingTestFixture.EVIDENCE, f.scope.rootId)!!
            val old = CommitMaintenanceFixture(f.binding)
            val intent = old.intent(view)
            val before = f.rows()
            assertThrows(OnboardingFailure::class.java) { runBlocking { old.adapter.execute(intent, "old-guarded") } }
            val markerOnly =
                BindingMaintenanceAuthorization {
                    object : BindingMaintenanceLease {
                        override val intentBinding = "not-a-database-lease"

                        override fun identity(connection: java.sql.Connection): MaintenanceIdentity =
                            f.binding.providers.maintenance(BindingTestFixture.EVIDENCE, f.binding.clock.instant())!!

                        override fun close() = Unit
                    }
                }
            val forged = JdbcCommitTimeBindingMaintenance(f.binding.source, f.schema, markerOnly, f.binding.clock)
            assertThrows(OnboardingFailure::class.java) { runBlocking { forged.execute(intent, "string-marker") } }
            assertEquals(before, f.rows())
            f.publisher.fence(f.enabled)
            val fenced = f.rows()
            assertThrows(OnboardingFailure::class.java) { runBlocking { old.adapter.execute(intent, "after-fence") } }
            assertEquals(fenced, f.rows())
        }

    private fun fixture(block: suspend (ControlHeadFixture) -> Unit) {
        BindingTestFixture(database()).use { binding ->
            runBlocking { block(ControlHeadFixture.create(binding)) }
        }
    }

    @Test fun `branded roots and initial enabled heads cannot be adopted by publisher`() =
        fixture { f ->
            val root = UUID.randomUUID()
            val audit = f.binding.database.scalar("SELECT MIN(id) FROM audit_log")
            f.binding.database.execute(
                "INSERT INTO target_bot_connections(id,telegram_bot_id,kind," +
                    "client_id,provenance_id,created_by,created_audit_id,last_audit_id) " +
                    "VALUES('$root',780009,'BRANDED',101,'${UUID.randomUUID()}',78001,$audit,$audit)",
            )
            assertThrows(SQLException::class.java) {
                runBlocking { f.publisher.create(f.scope.copy(rootId = root)) }
            }
            assertThrows(SQLException::class.java) {
                f.binding.database.execute(
                    "INSERT INTO target_maintenance_control_heads(id,root_id,authority_connection_id,provenance_id," +
                        "version,generation,status,manifest_id,manifest_digest,last_audit_id) VALUES " +
                        "('${f.scope.id}','${f.scope.rootId}','${f.scope.authorityConnectionId}'," +
                        "'${UUID.randomUUID()}',1,1,'ENABLED','${f.manifest}','${f.digest}',$audit)",
                )
            }
            assertEquals("0", f.binding.database.scalar("SELECT COUNT(*) FROM target_maintenance_control_heads"))
        }
}

internal class ControlHeadFixture private constructor(
    val binding: BindingTestFixture,
    val scope: MaintenanceControlScope,
) {
    val schema =
        BindingStorageSchema.fromServerConfiguration(
            binding.database.dataSource.connection
                .use { it.schema },
        )
    val manifest: UUID = UUID.randomUUID()
    val digest = "a".repeat(64)
    val identity = SyntheticControlPublisherIdentity(BindingTestFixture.ACTOR, UUID.randomUUID())
    var onAuthority: () -> SyntheticControlPublisherIdentity? = { identity }
    val publisher = SyntheticControlHeadPublisher(binding.source, schema, { onAuthority() }, binding.clock)
    val enabled get() = MaintenanceControlHead(scope, 2, 2, ControlPublicationStatus.ENABLED, manifest, digest)

    suspend fun publish(): MaintenanceControlHead = publisher.publish(publisher.create(scope), manifest, digest)

    fun read(): MaintenanceControlHead? =
        binding.database.dataSource.connection.use {
            readControlHead(it, schema, scope.id, "")
        }

    fun rows(): Map<String, List<List<String?>>> =
        (
            BindingTestFixture.TABLES +
                listOf("target_maintenance_control_heads", "target_maintenance_publications", "audit_log")
        ).associateWith { binding.database.rows("SELECT * FROM $it ORDER BY 1") }

    fun withConnection(block: (java.sql.Connection) -> Unit) {
        binding.database.dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                block(connection)
            } finally {
                connection.rollback()
            }
        }
    }

    companion object {
        suspend fun create(binding: BindingTestFixture): ControlHeadFixture {
            val root = binding.issue()
            return ControlHeadFixture(
                binding,
                MaintenanceControlScope(UUID.randomUUID(), root.rootId, UUID.randomUUID()),
            )
        }
    }
}
