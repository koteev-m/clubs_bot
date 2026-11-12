package com.example.bot.data.client.bindings

import com.example.bot.data.client.assignments.AssignmentTestDatabase
import com.example.bot.data.client.onboarding.OnboardingFailure
import com.example.bot.data.client.onboarding.OnboardingSql
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit

@Tag("it")
@Timeout(40)
internal class ControlHeadProcessesPostgresIT {
    @Test fun `two independent JVM shared holders block a third JVM publisher until both transactions end`() =
        fixture { f ->
            worker(f, "lease").use { first ->
                worker(f, "lease").use { second ->
                    assertEquals("READY", first.line())
                    assertEquals("READY", second.line())
                    worker(f, "publisher").use { publisher ->
                        publisher.send("GO")
                        awaitWait(f, publisher.name)
                        first.send("COMMIT")
                        assertEquals("STALE_HANDLE", first.line())
                        awaitWait(f, publisher.name)
                        second.send("ROLLBACK")
                        assertEquals("STALE_HANDLE", second.line())
                        assertEquals("FENCED:3", publisher.line())
                        assertEquals(ControlPublicationStatus.DISABLED, f.read()?.status)
                    }
                }
            }
        }

    @Test fun `shared worker waiting behind committed exclusive publication rejects old manifest`() =
        fixture { f ->
            f.binding.database.dataSource.connection.use { connection ->
                connection.autoCommit = false
                connection.createStatement().use {
                    it.executeQuery("SELECT id FROM target_maintenance_control_heads FOR UPDATE").close()
                }
                worker(f, "lease", waitReady = false).use { waiting ->
                    awaitWait(f, waiting.name)
                    val audit = newAudit(connection)
                    connection.createStatement().use {
                        it.executeUpdate(
                            "UPDATE target_maintenance_control_heads SET status='DISABLED',manifest_id=NULL," +
                                "manifest_digest=NULL,version=version+1,generation=generation+1,last_audit_id=$audit",
                        )
                    }
                    connection.commit()
                    assertEquals("DENIED", waiting.line())
                }
            }
        }

    @Test fun `crashed JVM releases actual DB lock and restarted stale worker is denied`() =
        fixture { f ->
            worker(f, "lease").use { crashed ->
                assertEquals("READY", crashed.line())
                worker(f, "publisher").use { publisher ->
                    publisher.send("GO")
                    awaitWait(f, publisher.name)
                    crashed.send("CRASH")
                    assertTrue(crashed.process.waitFor(5, TimeUnit.SECONDS))
                    assertEquals(17, crashed.process.exitValue())
                    assertEquals("FENCED:3", publisher.line())
                    worker(f, "lease", waitReady = false).use { restarted -> assertEquals("DENIED", restarted.line()) }
                }
            }
        }

    @Test fun `shared lock also blocks ordinary non-key UPDATE in another JVM`() =
        fixture { f ->
            worker(f, "lease").use { holder ->
                assertEquals("READY", holder.line())
                worker(f, "raw").use { raw ->
                    raw.send("GO")
                    awaitWait(f, raw.name)
                    holder.send("COMMIT")
                    assertEquals("STALE_HANDLE", holder.line())
                    assertEquals("FENCED:3", raw.line())
                }
            }
        }

    @Test fun `trusted schema ignores TEMP head history and hostile search path while guards fence raw DML`() =
        fixture { f ->
            f.withConnection { connection ->
                connection.createStatement().use {
                    it.execute(
                        "CREATE TEMP TABLE target_maintenance_control_heads AS SELECT * " +
                            "FROM target_maintenance_control_heads",
                    )
                    it.execute(
                        "CREATE TEMP TABLE target_maintenance_publications AS SELECT * " +
                            "FROM target_maintenance_publications",
                    )
                    it.execute("SET LOCAL search_path=pg_temp,public,pg_catalog")
                }
                assertEquals(f.enabled, JdbcControlHeadLease(f.schema, f.enabled).verify(connection))
                val audit = newAudit(connection, f.schema.quoted + ".")
                connection.createStatement().use {
                    it.executeUpdate(
                        "UPDATE ${f.schema.quoted}.target_maintenance_control_heads SET " +
                            "status='DISABLED',manifest_id=NULL," +
                            "manifest_digest=NULL,version=version+1,generation=generation+1,last_audit_id=$audit",
                    )
                    it.executeQuery("SELECT generation FROM pg_temp.target_maintenance_control_heads").use { rows ->
                        assertTrue(rows.next())
                        assertEquals(2, rows.getLong(1))
                    }
                    it
                        .executeQuery(
                            "SELECT COUNT(*) FROM " +
                                "${f.schema.quoted}.target_maintenance_publications",
                        ).use { rows ->
                            assertTrue(rows.next())
                            assertEquals(3, rows.getLong(1))
                        }
                }
                assertThrows<OnboardingFailure> { JdbcControlHeadLease(f.schema, f.enabled).verify(connection) }
            }
            assertEquals(f.enabled, f.read()) // Whole direct publication/audit/history transaction rolled back.
        }

    @Test fun `control bootstrap waits on old root writer and later bare writes are blocked`() {
        BindingTestFixture(AssignmentTestDatabase.postgres()).use { binding ->
            val f = runBlocking { ControlHeadFixture.create(binding) }
            f.withConnection { connection ->
                connection.createStatement().use { query ->
                    query.executeQuery("SELECT id FROM target_bot_connections FOR UPDATE").close()
                }
                worker(f, "create").use { bootstrap ->
                    bootstrap.send("GO")
                    awaitWait(f, bootstrap.name)
                    connection.createStatement().use { query ->
                        query.executeUpdate("UPDATE target_bot_bindings SET status='DISABLED'")
                    }
                    connection.commit()
                    assertEquals("FENCED:1", bootstrap.line())
                }
            }
            val before = f.rows()
            val current = runBlocking { binding.repository.read(BindingTestFixture.EVIDENCE, f.scope.rootId)!! }
            assertThrows<OnboardingFailure> {
                runBlocking {
                    binding.repository.mutate(
                        BindingTestFixture.EVIDENCE,
                        BindingCommand.State(current.rootId, current.fence, BindingState.REVOKED),
                        "blocked-after-adoption",
                    )
                }
            }
            assertEquals(before, f.rows())
        }
    }

    @Test fun `ordinary principal can lock canonical head but TEMP cannot intercept its guarded update`() =
        fixture { f ->
            val role = "control_probe_" + UUID.randomUUID().toString().replace("-", "")
            val schema = f.schema.quoted
            f.binding.database.execute(
                "CREATE ROLE $role NOLOGIN",
                "GRANT USAGE ON SCHEMA $schema TO $role",
                "GRANT SELECT,UPDATE ON $schema.target_maintenance_control_heads TO $role",
                "GRANT SELECT,INSERT ON $schema.target_maintenance_publications,$schema.audit_log TO $role",
                "GRANT USAGE ON ALL SEQUENCES IN SCHEMA $schema TO $role",
            )
            try {
                f.withConnection { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute("SET LOCAL ROLE $role")
                        statement.execute(
                            "CREATE TEMP TABLE target_maintenance_control_heads AS " +
                                "SELECT * FROM $schema.target_maintenance_control_heads",
                        )
                        statement.execute(
                            "CREATE TEMP TABLE target_maintenance_publications AS " +
                                "SELECT * FROM $schema.target_maintenance_publications",
                        )
                        statement.execute("SET LOCAL search_path=pg_temp,public,pg_catalog")
                    }
                    assertEquals(f.enabled, JdbcControlHeadLease(f.schema, f.enabled).verify(connection))
                    val audit = newAudit(connection, "$schema.")
                    connection.createStatement().use { statement ->
                        statement.executeUpdate(
                            "UPDATE $schema.target_maintenance_control_heads SET status='DISABLED'," +
                                "manifest_id=NULL,manifest_digest=NULL,version=version+1," +
                                "generation=generation+1,last_audit_id=$audit",
                        )
                        statement.executeQuery("SELECT generation FROM pg_temp.target_maintenance_control_heads").use {
                            assertTrue(it.next())
                            assertEquals(2, it.getLong(1))
                        }
                        statement.executeQuery("SELECT COUNT(*) FROM $schema.target_maintenance_publications").use {
                            assertTrue(it.next())
                            assertEquals(3, it.getLong(1))
                        }
                    }
                }
                assertEquals(f.enabled, f.read())
            } finally {
                f.binding.database.execute("DROP OWNED BY $role", "DROP ROLE $role")
            }
        }

    private fun fixture(block: (ControlHeadFixture) -> Unit) {
        BindingTestFixture(AssignmentTestDatabase.postgres()).use { binding ->
            val f = runBlocking { ControlHeadFixture.create(binding).also { it.publish() } }
            block(f)
        }
    }

    private fun newAudit(
        connection: java.sql.Connection,
        prefix: String = "",
    ): Long =
        OnboardingSql(connection).insert(
            "INSERT INTO ${prefix}audit_log(created_at,actor_user_id," +
                "entity_type,entity_id,action,fingerprint,metadata_json) " +
                "VALUES (CURRENT_TIMESTAMP,?,'CLIENT_ONBOARDING',NULL,'synthetic.publication',?,'{}')",
            BindingTestFixture.ACTOR,
            UUID.randomUUID().toString(),
        )

    private fun awaitWait(
        f: ControlHeadFixture,
        name: String,
    ) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < until) {
            val count =
                f.binding.database.scalar(
                    "SELECT COUNT(*) FROM pg_stat_activity WHERE application_name='$name' AND wait_event_type='Lock'",
                )
            if (count == "1") return
            Thread.sleep(10)
        }
        error("Expected actual control-head lock wait")
    }

    private fun worker(
        f: ControlHeadFixture,
        mode: String,
        waitReady: Boolean = true,
    ): ControlWorker {
        val result = ControlWorker(f, mode)
        val started = result.line()
        assertTrue(started.startsWith("STARTED:"), started)
        println("control-process-worker $mode $started ${result.name}")
        if (waitReady) check(result.process.isAlive)
        return result
    }
}

private class ControlWorker(
    f: ControlHeadFixture,
    mode: String,
) : AutoCloseable {
    val name = "control-worker-" + UUID.randomUUID()
    private val metadata: Path = Files.createTempFile("control-worker-", ".properties")
    val process: Process
    private val input: java.io.BufferedReader

    init {
        val values =
            Properties().apply {
                setProperty("url", f.binding.database.dataSource.jdbcUrl)
                setProperty("user", f.binding.database.dataSource.username)
                setProperty("password", f.binding.database.dataSource.password)
                setProperty("schema", f.schema.quoted.removeSurrounding("\""))
                setProperty("application", name)
                setProperty("id", f.scope.id.toString())
                setProperty("root", f.scope.rootId.toString())
                setProperty("realm", f.scope.authorityConnectionId.toString())
                setProperty("manifest", f.manifest.toString())
                setProperty("actor", BindingTestFixture.ACTOR.toString())
                setProperty("provenance", f.identity.provenance.toString())
            }
        Files.newOutputStream(metadata).use { values.store(it, "synthetic disposable metadata") }
        val paths = mutableSetOf<String>()
        var loader: ClassLoader? = javaClass.classLoader
        while (loader != null) {
            if (loader is URLClassLoader) loader.urLs.forEach { paths.add(Path.of(it.toURI()).toString()) }
            loader = loader.parent
        }
        paths.addAll(System.getProperty("java.class.path").split(java.io.File.pathSeparator))
        process =
            ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                paths.joinToString(java.io.File.pathSeparator),
                ControlHeadProcessWorker::class.java.name,
                metadata.toString(),
                mode,
            ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        input = process.inputStream.bufferedReader()
    }

    fun line(): String = checkNotNull(input.readLine()) { "Worker ended: ${process.exitValue()}" }

    fun send(command: String) {
        process.outputStream.write((command + "\n").toByteArray())
        process.outputStream.flush()
    }

    override fun close() {
        if (process.isAlive) process.destroyForcibly()
        process.waitFor(5, TimeUnit.SECONDS)
        input.close()
        process.outputStream.close()
        Files.deleteIfExists(metadata)
    }
}
