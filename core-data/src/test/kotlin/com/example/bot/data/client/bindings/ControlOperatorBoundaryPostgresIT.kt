package com.example.bot.data.client.bindings

import com.example.bot.data.client.assignments.AssignmentTestDatabase
import com.example.bot.data.client.onboarding.OnboardingFailure
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.postgresql.PGStatement
import java.sql.Connection
import java.util.UUID

/** Original review attacks, performed as an ordinary principal owning only its hostile schema. */
@Tag("it")
internal class ControlOperatorBoundaryPostgresIT {
    @Test fun `always true UUID operator cannot admit a nonexistent control ID`() =
        runBlocking {
            fixture { f ->
                val wrong = f.enabled.copy(scope = f.scope.copy(id = UUID.randomUUID()))
                hostileControlOperator(f, "RETURN TRUE;") { c, _ ->
                    c.autoCommit = false
                    assertThrows<OnboardingFailure> { JdbcControlHeadLease(f.schema, wrong).use { it.verify(c) } }
                    c.rollback()
                    assertEquals(f.enabled, f.read())
                }
            }
        }

    @Test fun `conditional UPDATE UUID operator cannot fence B through fence A`() =
        runBlocking {
            fixture { a ->
                val b = a.binding
                val root = b.issue(b.create().copy(botId = BindingTestFixture.BOT + 1))
                val scope = MaintenanceControlScope(UUID.randomUUID(), root.rootId, UUID.randomUUID())
                val publisher = SyntheticControlHeadPublisher(b.source, a.schema, { a.identity }, b.clock)
                val other = publisher.publish(publisher.create(scope), UUID.randomUUID(), "b".repeat(64))
                val body =
                    "IF pg_catalog.strpos(pg_catalog.current_query(),'UPDATE ')=1 THEN " +
                        "RETURN lhs OPERATOR(pg_catalog.=) '${scope.id}'::pg_catalog.uuid; END IF; " +
                        "RETURN lhs OPERATOR(pg_catalog.=) rhs;"
                hostileControlOperator(a, body) { c, _ ->
                    val selected =
                        SyntheticControlHeadPublisher(
                            DurableBindingReadProbe(b.source, c),
                            a.schema,
                            { a.identity },
                            b.clock,
                        )
                    val returned = runBlocking { selected.fence(a.enabled) }
                    val actualOther = b.source.connection.use { readControlHead(it, a.schema, scope.id, "") }
                    assertEquals(other, actualOther)
                    assertEquals(a.scope, returned.scope)
                    assertEquals(3, returned.generation)
                    assertEquals(ControlPublicationStatus.DISABLED, returned.status)
                    assertEquals(returned, a.read())
                    assertEquals(
                        "3",
                        b.database.scalar(
                            "SELECT COUNT(*) FROM target_maintenance_publications " +
                                "WHERE control_id='${a.scope.id}'",
                        ),
                    )
                }
            }
        }

    @Test fun `standalone metadata SELECT uses actual identity and catalog equality even in autocommit`() =
        runBlocking {
            fixture { f ->
                hostileControlOperator(f, "RETURN TRUE;") { c, _ ->
                    assertTrue(c.autoCommit)
                    assertEquals(f.enabled, readControlHead(c, f.schema, f.scope.id, ""))
                    assertEquals(null, readControlHead(c, f.schema, UUID.randomUUID(), ""))
                }
            }
        }

    @Test fun `TEMP cannot capture publisher SELECT UPDATE RETURNING audit or history`() =
        runBlocking {
            fixture { f ->
                hostileControlOperator(f, "RETURN TRUE;") { c, _ ->
                    c.createStatement().use { query ->
                        listOf("target_maintenance_control_heads", "target_maintenance_publications", "audit_log")
                            .forEach { query.execute("CREATE TEMP TABLE $it AS SELECT * FROM ${f.schema.quoted}.$it") }
                    }
                    val publisher =
                        SyntheticControlHeadPublisher(
                            DurableBindingReadProbe(f.binding.source, c),
                            f.schema,
                            { f.identity },
                            f.binding.clock,
                        )
                    val fenced = runBlocking { publisher.fence(f.enabled) }
                    assertEquals(f.scope, fenced.scope)
                    assertEquals(3, fenced.generation)
                    assertEquals(
                        4,
                        runBlocking { publisher.publish(fenced, UUID.randomUUID(), "c".repeat(64)) }.generation,
                    )
                    assertEquals(2, scalarLong(c, "SELECT generation FROM pg_temp.target_maintenance_control_heads"))
                    assertEquals(2, scalarLong(c, "SELECT COUNT(*) FROM pg_temp.target_maintenance_publications"))
                    assertEquals(
                        4,
                        scalarLong(c, "SELECT COUNT(*) FROM ${f.schema.quoted}.target_maintenance_publications"),
                    )
                    assertEquals(
                        scalarLong(c, "SELECT COUNT(*) FROM pg_temp.audit_log") + 2,
                        scalarLong(c, "SELECT COUNT(*) FROM ${f.schema.quoted}.audit_log"),
                    )
                }
            }
        }

    @Test fun `create uses trusted non public schema with hostile operators and no automatic authorization`() =
        runBlocking {
            BindingTestFixture(AssignmentTestDatabase.postgres()).use { b ->
                val f = ControlHeadFixture.create(b)
                assertFalse(f.schema.quoted == "\"public\"")
                hostileControlOperator(f, "RETURN TRUE;") { c, _ ->
                    val publisher =
                        SyntheticControlHeadPublisher(
                            DurableBindingReadProbe(b.source, c),
                            f.schema,
                            { f.identity },
                            b.clock,
                        )
                    val created = runBlocking { publisher.create(f.scope) }
                    assertEquals(f.scope, created.scope)
                    assertEquals(1, created.generation)
                    assertEquals(ControlPublicationStatus.DISABLED, created.status)
                    c.autoCommit = false
                    assertThrows<OnboardingFailure> { JdbcControlHeadLease(f.schema, created).verify(c) }
                    c.rollback()
                }
            }
        }

    @Test fun `pool cache and savepoint rollback cannot retain hostile operators`() =
        runBlocking {
            fixture { f ->
                hostileControlOperator(f, "RETURN TRUE;") { _, context ->
                    val ds = f.binding.database.dataSource
                    HikariDataSource(
                        HikariConfig().apply {
                            jdbcUrl = ds.jdbcUrl
                            username = ds.username
                            password = ds.password
                            maximumPoolSize = 1
                            connectionInitSql = "SET ROLE ${context.role}; SET search_path=${context.path}"
                        },
                    ).use { pool ->
                        val firstState = pool.connection.use { exerciseCachedStatement(it, f) }
                        pool.connection.use { c ->
                            assertEquals(firstState.first, scalarLong(c, "SELECT pg_catalog.pg_backend_pid()"))
                            assertEquals(firstState.second, scalarString(c, "SHOW search_path"))
                            c.autoCommit = false
                            assertEquals(f.enabled, JdbcControlHeadLease(f.schema, f.enabled).verify(c))
                            c.rollback()
                            assertEquals(firstState.second, scalarString(c, "SHOW search_path"))
                        }
                    }
                }
            }
        }

    @Test fun `persisted ID mismatch is independently denied rather than relabelled as requested identity`() =
        runBlocking {
            fixture { f ->
                val source = ControlIdentityProbe(f.binding.source, updateOnly = false)
                source.connection.use { c ->
                    assertThrows<OnboardingFailure> { readControlHead(c, f.schema, f.scope.id, "") }
                }
            }
        }

    @Test fun `wrong RETURNING identity rolls back update audit and immutable publication history`() =
        runBlocking {
            fixture { f ->
                val before = f.rows()
                val source = ControlIdentityProbe(f.binding.source, updateOnly = true)
                val publisher = SyntheticControlHeadPublisher(source, f.schema, { f.identity }, f.binding.clock)
                assertThrows<OnboardingFailure> { publisher.fence(f.enabled) }
                assertEquals(before, f.rows())
            }
        }

    private fun exerciseCachedStatement(
        c: Connection,
        f: ControlHeadFixture,
    ): Pair<Long, String> {
        val pid = scalarLong(c, "SELECT pg_catalog.pg_backend_pid()")
        val before = scalarString(c, "SHOW search_path")
        c.autoCommit = false
        val savepoint = c.setSavepoint()
        val query = "SELECT id FROM ${f.schema.quoted}.target_maintenance_control_heads WHERE id=?"
        c.prepareStatement(query).use { cached ->
            cached.unwrap(PGStatement::class.java).prepareThreshold = 1
            cached.setObject(1, UUID.randomUUID())
            repeat(2) { cached.executeQuery().use { rows -> assertTrue(rows.next()) } }
            JdbcControlHeadLease(f.schema, f.enabled).use { lease ->
                assertEquals(f.enabled, lease.verify(c))
                cached.executeQuery().use { assertFalse(it.next()) }
                c.rollback(savepoint)
                assertEquals(before, scalarString(c, "SHOW search_path"))
                assertEquals(f.enabled, lease.verify(c))
                cached.executeQuery().use { assertFalse(it.next()) }
                c.commit()
                assertEquals(before, scalarString(c, "SHOW search_path"))
                assertThrows<OnboardingFailure> { lease.verify(c) }
                c.rollback()
            }
        }
        return pid to before
    }

    private suspend fun fixture(block: suspend (ControlHeadFixture) -> Unit) {
        BindingTestFixture(AssignmentTestDatabase.postgres()).use { b ->
            block(ControlHeadFixture.create(b).also { it.publish() })
        }
    }
}

private fun scalarLong(
    c: Connection,
    sql: String,
): Long = scalarString(c, sql).toLong()

private fun scalarString(
    c: Connection,
    sql: String,
): String =
    c.createStatement().use { query ->
        query.executeQuery(sql).use { result ->
            check(result.next())
            result.getString(1)
        }
    }
