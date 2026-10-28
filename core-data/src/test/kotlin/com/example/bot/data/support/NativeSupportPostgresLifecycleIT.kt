package com.example.bot.data.support

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIf
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.ResultSet

@Tag("it")
@EnabledIf("com.example.bot.data.support.NativeSupportPostgres#requested")
internal class NativeSupportPostgresLifecycleIT {
    @Test
    fun `repeated successful runs stop only their child and preserve foreign cluster data`() {
        NativeSupportPostgres.configured().use { other ->
            val sentinel = other.createDatabase("support_native")
            connection(other, sentinel).use {
                it.createStatement().use { sql ->
                    sql.execute("CREATE TABLE sentinel(id int)")
                }
            }
            repeat(2) {
                val postgres = NativeSupportPostgres.configured()
                postgres.use {
                    listOf("support_native", "support_current", "support_v057_drift").forEach { prefix ->
                        connection(postgres, postgres.createDatabase(prefix)).use { assertTrue(it.isValid(1)) }
                    }
                    assertTrue(postgres.isAlive)
                }
                assertFalse(postgres.isAlive)
                postgres.close()
                assertTrue(other.isAlive)
                connection(other, sentinel).use { connection ->
                    connection.createStatement().use { sql ->
                        sql.executeQuery("SELECT count(*) FROM sentinel").use { rows ->
                            assertTrue(rows.next())
                            assertEquals(0, rows.getInt(1))
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `database name replaced after initial identity verification is never dropped by teardown`() {
        val postgres = NativeSupportPostgres.configured()
        val databaseDirectories = mutableListOf<Path>()
        postgres.use {
            val url = postgres.createDatabase("support_native")
            val name = url.substringAfterLast('/')
            val admin = postgres.createDatabase("support_current")
            databaseDirectories.addAll(replaceDatabase(postgres, admin, name))
            connection(postgres, url).use { connection ->
                connection.createStatement().use { it.execute("CREATE TABLE replacement_sentinel(id int)") }
            }
        }
        assertFalse(postgres.isAlive)
        assertEquals(2, databaseDirectories.size)
        // DROP DATABASE would remove the replacement's database directory. Both remain after shutdown.
        databaseDirectories.forEach { assertTrue(Files.isDirectory(it)) }
        val statements = Files.readString(postgres.directory.resolve("postgres.log"))
        assertTrue(statements.contains("ALTER DATABASE"))
        assertFalse(Regex("\\bDROP\\s+DATABASE\\b", RegexOption.IGNORE_CASE).containsMatchIn(statements))
    }

    @Test
    fun `replaced PID file cannot redirect teardown to another running cluster`() {
        NativeSupportPostgres.configured().use { other ->
            val sentinel = other.createDatabase("support_native")
            val foreignPid = Files.readAllLines(other.directory.resolve("data/postmaster.pid")).first()
            val postgres = NativeSupportPostgres.configured()
            postgres.use {
                postgres.createDatabase("support_native")
                val pidFile = postgres.directory.resolve("data/postmaster.pid")
                val lines = Files.readAllLines(pidFile)
                assertFalse(lines.first() == foreignPid)
                // A pathname/PID based stopper would target the other cluster after this identity loss.
                Files.write(pidFile, listOf(foreignPid) + lines.drop(1))
                postgres.close()
                assertFalse(postgres.isAlive)
                assertTrue(other.isAlive)
                connection(other, sentinel).use { held -> assertTrue(held.isValid(1)) }
            }
        }
    }

    @Test
    fun `partial setup cleanup works before pools exist and after the legacy pool opens`() {
        listOf("start", "current").forEach { failureStage ->
            NativeSupportPostgres.configured().use { postgres ->
                val urls = mutableListOf<String>()
                val fixture =
                    object : SupportTicketCreationContract() {
                        override val jdbcUrl: String get() = urls.first()
                        override val username: String get() = postgres.username
                        override val password: String get() = postgres.password

                        override fun startPostgres() {
                            urls += postgres.createDatabase("support_native")
                            check(failureStage != "start") { "setup sentinel" }
                        }

                        override fun createDatabase(prefix: String): String {
                            urls += postgres.createDatabase(prefix)
                            error("setup sentinel")
                        }

                        override fun stopPostgres() = postgres.close()
                    }
                try {
                    val failure = assertThrows(IllegalStateException::class.java) { fixture.migrateFromLegacySchema() }
                    assertEquals("setup sentinel", failure.message)
                } finally {
                    fixture.closeDatabase()
                }
                assertEquals(if (failureStage == "start") 1 else 2, urls.size)
                assertFalse(postgres.isAlive)
            }
        }
    }

    @Test
    fun `cleanup timeout is suppressed on original failure and does not kill a live session`() {
        NativeSupportPostgres.configured().use { configured ->
            val postgres = NativeSupportPostgres(Path.of(System.getenv("CLB_SUPPORT_CREATION_TEST_PG_BIN")), 1)
            try {
                val url = postgres.createDatabase("support_native")
                connection(postgres, url).use { held ->
                    val failure =
                        assertThrows(IllegalStateException::class.java) {
                            postgres.use { error("setup sentinel") }
                        }
                    assertEquals("setup sentinel", failure.message)
                    assertTrue(
                        failure.suppressed
                            .single()
                            .message
                            .orEmpty()
                            .contains("did not stop"),
                    )
                    assertTrue(held.isValid(1))
                    assertTrue(postgres.isAlive)
                    assertFalse(configured.isAlive)
                }
            } finally {
                postgres.close()
            }
            assertFalse(postgres.isAlive)
        }
    }

    private fun replaceDatabase(
        postgres: NativeSupportPostgres,
        admin: String,
        name: String,
    ): List<Path> {
        val directories = mutableListOf<Path>()
        connection(postgres, admin).use { connection ->
            connection.createStatement().use { sql ->
                // Deterministic ownership loss after createDatabase's initial cluster identity check.
                // No DROP, even in the regression: retain the original and install another DB at its name.
                sql.execute("ALTER DATABASE $name RENAME TO displaced_original")
                sql.execute("CREATE DATABASE $name")
                sql.execute("CREATE ROLE replacement_owner NOLOGIN")
                sql.execute("ALTER DATABASE $name OWNER TO replacement_owner")
                sql
                    .executeQuery("SELECT oid FROM pg_database WHERE datname IN ('$name', 'displaced_original')")
                    .use { rows ->
                        directories.addAll(databaseDirectories(rows, postgres.directory))
                    }
            }
        }
        return directories
    }

    private fun databaseDirectories(
        rows: ResultSet,
        directory: Path,
    ): List<Path> =
        buildList {
            while (rows.next()) add(directory.resolve("data/base/" + rows.getLong(1)))
        }

    private fun connection(
        postgres: NativeSupportPostgres,
        url: String,
    ) = DriverManager.getConnection(url, postgres.username, postgres.password)
}
