package com.example.bot.data.client.bindings

import com.example.bot.data.client.ClientAudience
import com.example.bot.data.client.assignments.AssignmentTestDatabase
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Tag("it")
internal class DurableBindingSnapshotPostgresIT {
    @Test fun `hostile TEMP relations and explicit evil operator cannot redirect durable reads`() =
        runBlocking {
            BindingTestFixture(AssignmentTestDatabase.postgres()).use { f ->
                f.issue()
                val view = f.branded()
                val ref = f.reference(view, ClientAudience.Client(101, setOf(1001)))
                f.database.dataSource.connection.use { c ->
                    val trusted = checkNotNull(c.schema)
                    val evil = "read_evil_" + UUID.randomUUID().toString().replace("-", "")
                    val tables =
                        listOf(
                            "target_bot_connections",
                            "target_bot_bindings",
                            "target_credential_revisions",
                            "target_credential_materials",
                            "target_binding_clubs",
                            "clients",
                            "client_clubs",
                            "clubs",
                            "target_client_mapping_epochs",
                        )
                    c.createStatement().use { s ->
                        s.execute("CREATE SCHEMA $evil")
                        for (table in tables) {
                            s.execute("CREATE TEMP TABLE $table (LIKE \"$trusted\".$table INCLUDING DEFAULTS)")
                            s.execute("CREATE TABLE $evil.$table (LIKE \"$trusted\".$table INCLUDING DEFAULTS)")
                        }
                        s.execute(
                            "CREATE FUNCTION $evil.bad_equal(UUID,UUID) RETURNS BOOLEAN LANGUAGE SQL AS 'SELECT FALSE'",
                        )
                        s.execute("CREATE OPERATOR $evil.= (LEFTARG=UUID,RIGHTARG=UUID,FUNCTION=$evil.bad_equal)")
                        s.execute("SET search_path TO $evil,pg_temp,pg_catalog,\"$trusted\"")
                    }
                    try {
                        val source =
                            JdbcDurableBindingSource(
                                DurableBindingReadProbe(f.source, c),
                                BindingStorageSchema.fromServerConfiguration(trusted),
                            )
                        val result = source.read(ref) as DurableBindingRead.Inactive
                        assertEquals(view.fence, result.snapshot.fence)
                        assertEquals(ref.mappingEpoch, result.snapshot.mappingEpoch)
                        c.isReadOnly = false
                        c.autoCommit = true
                        c.createStatement().use { s ->
                            s.executeQuery("SHOW search_path").use { r ->
                                assertTrue(r.next())
                                assertTrue(r.getString(1).startsWith(evil))
                            }
                            for (table in tables) {
                                s.executeQuery("SELECT COUNT(*) FROM pg_temp.$table").use { r ->
                                    assertTrue(r.next())
                                    assertEquals(0, r.getInt(1))
                                }
                            }
                        }
                    } finally {
                        if (!c.autoCommit) c.rollback()
                        c.isReadOnly = false
                        c.autoCommit = true
                        c.createStatement().use { s ->
                            tables.forEach { s.execute("DROP TABLE pg_temp.$it") }
                            s.execute("SET search_path TO \"$trusted\",public")
                            s.execute("DROP SCHEMA $evil CASCADE")
                        }
                    }
                }
            }
        }

    @Test fun `one statement never mixes versions when revoke commits during result consumption`() =
        runBlocking {
            BindingTestFixture(AssignmentTestDatabase.postgres()).use { f ->
                val first = f.issue()
                val ref = f.reference(first)
                val schema =
                    f.database.dataSource.connection
                        .use { checkNotNull(it.schema) }
                val queried = CountDownLatch(1)
                val consume = CountDownLatch(1)
                val source =
                    JdbcDurableBindingSource(
                        DurableBindingReadProbe(f.source, afterQuery = {
                            queried.countDown()
                            check(consume.await(15, TimeUnit.SECONDS))
                        }),
                        BindingStorageSchema.fromServerConfiguration(schema),
                    )
                val reading = async(start = CoroutineStart.UNDISPATCHED) { source.read(ref) }
                val revoked: BindingView
                try {
                    assertTrue(queried.await(15, TimeUnit.SECONDS))
                    revoked = f.issue(BindingCommand.State(first.rootId, first.fence, BindingState.REVOKED))
                } finally {
                    consume.countDown()
                }
                val old = (reading.await() as DurableBindingRead.Inactive).snapshot
                assertEquals(first.fence, old.fence)
                assertEquals(BindingState.PENDING, old.status)
                assertFalse(old.toString().contains(first.rootId.toString()))
                assertRejected(f.durableSource().read(ref), BindingReadRejection.FENCE)
                val current = f.durableSource().read(f.reference(revoked)) as DurableBindingRead.Revoked
                assertEquals(revoked.fence, current.snapshot.fence)
                assertEquals(BindingState.REVOKED, current.snapshot.status)
                println("DURABLE_READ concurrent revoke: coherent old snapshot, stale pins deny, fresh pins terminal")
            }
        }
}
