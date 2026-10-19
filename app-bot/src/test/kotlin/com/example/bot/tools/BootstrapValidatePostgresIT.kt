package com.example.bot.tools

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/** Synthetic history only: no application migrations, real backup or application data. */
@Tag("it")
class BootstrapValidatePostgresIT {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `PostgreSQL 16 validation preserves full dump on positive and negative evidence`() {
        PostgreSQLContainer<Nothing>("postgres:16-alpine").use { postgres ->
            postgres.start()
            val jdbcUrl = "jdbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/${postgres.databaseName}"
            val env =
                bootstrapTestEnvironment() +
                    mapOf(
                        "DATABASE_URL" to jdbcUrl,
                        "DATABASE_USER" to postgres.username,
                        "DATABASE_PASSWORD" to postgres.password,
                    )
            val flyway =
                bootstrapFlyway(
                    readOnlyPostgresUrl(jdbcUrl),
                    postgres.username,
                    postgres.password,
                    bootstrapConfig(env),
                )
            val resolved = flyway.info().all()
            assertTrue(resolved.isNotEmpty())
            DriverManager.getConnection(jdbcUrl, postgres.username, postgres.password).use { fixture ->
                verifyFixture(postgres, env, flyway, resolved, fixture)
            }
        }
    }

    private fun verifyFixture(
        postgres: PostgreSQLContainer<Nothing>,
        env: Map<String, String>,
        flyway: Flyway,
        resolved: Array<MigrationInfo>,
        fixture: Connection,
    ) {
        fixture.createStatement().use { sql ->
            sql.execute("CREATE TABLE sentinel (id integer PRIMARY KEY, value text NOT NULL)")
            sql.execute("INSERT INTO sentinel VALUES (1, 'synthetic_canary')")
        }
        assertReadOnly(flyway)
        createHistory(fixture, resolved)
        assertUnchanged(postgres, expectedExit = 0) { validateBootstrapSchema(env) }
        assertChildUnchanged(postgres, env, expectedExit = 0)

        val corruptions =
            listOf(
                "DELETE FROM flyway_schema_history WHERE installed_rank = ${resolved.size}",
                "UPDATE flyway_schema_history SET checksum = checksum + 1 WHERE installed_rank = 1",
                "UPDATE flyway_schema_history SET success = false WHERE installed_rank = 1",
                "UPDATE flyway_schema_history SET type = 'BASELINE', checksum = NULL WHERE installed_rank = 1",
                "UPDATE flyway_schema_history SET version = '999' WHERE installed_rank = ${resolved.size}",
                "TRUNCATE flyway_schema_history",
                "DROP TABLE flyway_schema_history",
            )
        corruptions.forEach { corruption ->
            fixture.createStatement().use { it.execute(corruption) }
            assertUnchanged(postgres, expectedExit = 1) { validateBootstrapSchema(env) }
            assertChildUnchanged(postgres, env, expectedExit = 1)
            createHistory(fixture, resolved)
        }
        // A matching history can be validated with SELECT privileges only.
        fixture.createStatement().use { sql ->
            sql.execute("CREATE ROLE synthetic_reader LOGIN PASSWORD 'synthetic_only'")
            sql.execute("GRANT USAGE ON SCHEMA public TO synthetic_reader")
            sql.execute("GRANT SELECT ON flyway_schema_history TO synthetic_reader")
        }
        val reader = env + mapOf("DATABASE_USER" to "synthetic_reader", "DATABASE_PASSWORD" to "synthetic_only")
        assertUnchanged(postgres, expectedExit = 0) { validateBootstrapSchema(reader) }
        fixture.createStatement().use {
            it.execute(
                "REVOKE SELECT ON flyway_schema_history FROM synthetic_reader",
            )
        }
        assertUnchanged(postgres, expectedExit = 1) { validateBootstrapSchema(reader) }
    }

    private fun assertReadOnly(flyway: Flyway) {
        flyway.configuration.dataSource.connection.use { readOnly ->
            readOnly.createStatement().use { sql ->
                sql.executeQuery("SHOW transaction_read_only").use { result ->
                    check(result.next())
                    assertEquals("on", result.getString(1))
                }
                val failure = assertThrows(SQLException::class.java) { sql.execute("DELETE FROM sentinel") }
                assertEquals("25006", failure.sqlState)
            }
        }
    }

    private fun createHistory(
        connection: Connection,
        resolved: Array<MigrationInfo>,
    ) {
        connection.createStatement().use { sql ->
            sql.execute("DROP TABLE IF EXISTS flyway_schema_history")
            sql.execute(
                """
                CREATE TABLE flyway_schema_history (
                    installed_rank integer PRIMARY KEY, version varchar(50), description varchar(200) NOT NULL,
                    type varchar(20) NOT NULL, script varchar(1000) NOT NULL, checksum integer,
                    installed_by varchar(100) NOT NULL, installed_on timestamp NOT NULL DEFAULT now(),
                    execution_time integer NOT NULL, success boolean NOT NULL
                )
                """.trimIndent(),
            )
        }
        connection
            .prepareStatement(
                """
                INSERT INTO flyway_schema_history
                    (installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)
                VALUES (?, ?, ?, 'SQL', ?, ?, 'synthetic_fixture', 0, true)
                """.trimIndent(),
            ).use { sql ->
                resolved.forEachIndexed { index, migration ->
                    sql.setInt(1, index + 1)
                    sql.setString(2, migration.version.toString())
                    sql.setString(3, migration.description)
                    sql.setString(4, migration.script)
                    sql.setInt(5, checkNotNull(migration.checksum))
                    sql.addBatch()
                }
                sql.executeBatch()
            }
    }

    private fun assertUnchanged(
        postgres: PostgreSQLContainer<Nothing>,
        expectedExit: Int,
        validation: () -> Unit,
    ) {
        val before = dump(postgres)
        assertEquals(expectedExit, bootstrapValidationExitCode(validation))
        assertEquals(before, dump(postgres), "schema, history and sentinel dump must remain byte-identical")
    }

    private fun dump(postgres: PostgreSQLContainer<Nothing>): String {
        val result = postgres.execInContainer("pg_dump", "-U", postgres.username, postgres.databaseName)
        assertEquals(0, result.exitCode)
        // Recent pg_dump versions randomize only these client-side guard tokens.
        return result.stdout
            .lineSequence()
            .filterNot { it.startsWith("\\restrict ") || it.startsWith("\\unrestrict ") }
            .joinToString("\n")
    }

    private fun assertChildUnchanged(
        postgres: PostgreSQLContainer<Nothing>,
        env: Map<String, String>,
        expectedExit: Int,
    ) {
        val before = dump(postgres)
        val result = if (expectedExit == 0) "compatible" else "rejected"
        assertEquals(
            expectedExit to "schema-validation:v=1 result=$result\n",
            bootstrapChildResult(env, temporaryDirectory.resolve("child-output")),
        )
        assertEquals(before, dump(postgres))
    }
}
