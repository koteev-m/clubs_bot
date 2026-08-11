package com.example.bot.tools

import com.example.bot.data.db.AppEnvironment
import com.example.bot.data.db.FlywayExecutionContext
import com.example.bot.data.db.FlywayMode
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationInfoService
import org.flywaydb.core.api.output.MigrateResult
import org.flywaydb.core.api.output.ValidateResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

class QuiescedMigrateMainTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `migration process invokes only Flyway migrate validate and pending checks`() {
        val flyway = successfulFlyway(migrationsExecuted = 2)
        val env = validEnvironment()

        val result =
            runQuiescedMigration(
                envProvider = { env[it] },
                propertyProvider = { null },
                flywayFactory = { url, user, password, config ->
                    assertEquals("jdbc:postgresql://db:5432/clubs", url)
                    assertEquals("clubs", user)
                    assertEquals("secret", password)
                    assertEquals(AppEnvironment.STAGE, config.appEnv)
                    assertEquals(FlywayExecutionContext.QUIESCED_MIGRATION, config.executionContext)
                    assertEquals(FlywayMode.MIGRATE_AND_VALIDATE, config.effectiveMode)
                    assertEquals(
                        listOf(
                            "classpath:db/migration/postgresql",
                            "classpath:db/migration/common",
                        ),
                        config.locations,
                    )
                    flyway
                },
            )

        assertEquals(2, result.migrationsExecuted)
        verify(exactly = 1) { flyway.migrate() }
        verify(exactly = 1) { flyway.validateWithResult() }
        verify(exactly = 1) { flyway.info() }
    }

    @Test
    fun `migration failure is propagated without retry or application startup`() {
        val failure = IllegalStateException("migration failed")
        val flyway = mockk<Flyway>()
        every { flyway.migrate() } throws failure
        val env = validEnvironment()

        val thrown =
            assertThrows(IllegalStateException::class.java) {
                runQuiescedMigration(
                    envProvider = { env[it] },
                    propertyProvider = { null },
                    flywayFactory = { _, _, _, _ -> flyway },
                )
            }

        assertSame(failure, thrown)
        verify(exactly = 1) { flyway.migrate() }
        verify(exactly = 0) { flyway.validateWithResult() }
        verify(exactly = 0) { flyway.info() }
    }

    @Test
    fun `validation failure is fatal`() {
        val flyway = successfulFlyway(validationSuccessful = false)
        val env = validEnvironment()

        assertThrows(IllegalStateException::class.java) {
            runQuiescedMigration(
                envProvider = { env[it] },
                propertyProvider = { null },
                flywayFactory = { _, _, _, _ -> flyway },
            )
        }
    }

    @Test
    fun `pending migration after migrate is fatal`() {
        val pending = arrayOf(mockk<org.flywaydb.core.api.MigrationInfo>())
        val flyway = successfulFlyway(pending = pending)
        val env = validEnvironment()

        assertThrows(IllegalStateException::class.java) {
            runQuiescedMigration(
                envProvider = { env[it] },
                propertyProvider = { null },
                flywayFactory = { _, _, _, _ -> flyway },
            )
        }
    }

    @Test
    fun `release marker is mandatory`() {
        val env = validEnvironment() - "QUIESCED_RELEASE_MIGRATION"

        assertThrows(IllegalStateException::class.java) {
            runQuiescedMigration(envProvider = { env[it] }, propertyProvider = { null })
        }
    }

    @Test
    fun `production-like environment is mandatory`() {
        val env = validEnvironment() + ("APP_ENV" to "local")

        assertThrows(IllegalStateException::class.java) {
            runQuiescedMigration(envProvider = { env[it] }, propertyProvider = { null })
        }
    }

    @Test
    fun `explicit migrate mode is mandatory`() {
        val env = validEnvironment() + ("FLYWAY_MODE" to "validate")

        assertThrows(IllegalStateException::class.java) {
            runQuiescedMigration(envProvider = { env[it] }, propertyProvider = { null })
        }
    }

    @Test
    fun `disabled Flyway is rejected`() {
        val env = validEnvironment() + ("FLYWAY_ENABLED" to "false")

        assertThrows(IllegalStateException::class.java) {
            runQuiescedMigration(envProvider = { env[it] }, propertyProvider = { null })
        }
    }

    @Test
    fun `out of order request is rejected`() {
        val env = validEnvironment() + ("FLYWAY_OUT_OF_ORDER" to "true")

        assertThrows(IllegalStateException::class.java) {
            runQuiescedMigration(envProvider = { env[it] }, propertyProvider = { null })
        }
    }

    @Test
    fun `filesystem migration location is rejected`() {
        val env = validEnvironment() + ("FLYWAY_LOCATIONS" to "filesystem:/runner/checkout/migrations")

        assertThrows(IllegalStateException::class.java) {
            runQuiescedMigration(envProvider = { env[it] }, propertyProvider = { null })
        }
    }

    @Test
    fun `non PostgreSQL database is rejected`() {
        val env = validEnvironment() + ("DATABASE_URL" to "jdbc:h2:mem:test")

        assertThrows(IllegalStateException::class.java) {
            runQuiescedMigration(envProvider = { env[it] }, propertyProvider = { null })
        }
    }

    @Test
    fun `successful process emits only the fixed safe event schema`() {
        val events = mutableListOf<String>()

        val exitCode =
            runQuiescedMigrationProcess(
                migration = { QuiescedMigrationResult(migrationsExecuted = 3) },
                eventSink = { events += renderMigrationSafeEvent(it) },
            )

        assertEquals(0, exitCode)
        assertEquals(
            listOf(
                "migration-safe:v=1 event=started",
                "migration-safe:v=1 event=completed applied=3",
            ),
            events,
        )
    }

    @Test
    fun `connection failure emits category without throwable data`() {
        val canaries =
            mapOf(
                "endpoint" to "jdbc:postgresql://migration-secret-host.invalid:5432/secret_database",
                "username" to "migration_secret_user",
                "password" to "migration_secret_password",
                "query" to "migrationSecretQuery=secret_query_value",
            )
        val rawFailure = canaries.values.joinToString(" ")
        val events = mutableListOf<String>()

        val exitCode =
            runQuiescedMigrationProcess(
                migration = { phaseObserver ->
                    phaseObserver(MigrationPhase.MIGRATION)
                    throw SQLException(rawFailure, "08001")
                },
                eventSink = { events += renderMigrationSafeEvent(it) },
            )

        assertEquals(1, exitCode)
        assertEquals(
            listOf(
                "migration-safe:v=1 event=started",
                "migration-safe:v=1 event=failed phase=migration category=connection",
            ),
            events,
        )
        assertNoSensitiveValues(events.joinToString("\n"), canaries)
    }

    @Test
    fun `authentication and validation failures use fixed categories`() {
        val authEvents = mutableListOf<String>()
        val validationEvents = mutableListOf<String>()

        val authExit =
            runQuiescedMigrationProcess(
                migration = { phaseObserver ->
                    phaseObserver(MigrationPhase.MIGRATION)
                    throw SQLException("raw authentication failure", "28P01")
                },
                eventSink = { authEvents += renderMigrationSafeEvent(it) },
            )
        val validationExit =
            runQuiescedMigrationProcess(
                migration = { phaseObserver ->
                    phaseObserver(MigrationPhase.VALIDATION)
                    error("raw validation failure")
                },
                eventSink = { validationEvents += renderMigrationSafeEvent(it) },
            )

        assertEquals(1, authExit)
        assertEquals(
            "migration-safe:v=1 event=failed phase=migration category=authentication",
            authEvents.last(),
        )
        assertEquals(1, validationExit)
        assertEquals(
            "migration-safe:v=1 event=failed phase=validation category=validation",
            validationEvents.last(),
        )
        assertFalse(authEvents.joinToString("\n").contains("raw authentication failure"))
        assertFalse(validationEvents.joinToString("\n").contains("raw validation failure"))
    }

    @Test
    fun `cancellation remains nonzero without raw exception output`() {
        val events = mutableListOf<String>()

        val exitCode =
            runQuiescedMigrationProcess(
                migration = { phaseObserver ->
                    phaseObserver(MigrationPhase.MIGRATION)
                    throw CancellationException("raw cancellation details")
                },
                eventSink = { events += renderMigrationSafeEvent(it) },
            )

        assertEquals(130, exitCode)
        assertEquals(
            "migration-safe:v=1 event=failed phase=migration category=cancelled",
            events.last(),
        )
        assertFalse(events.joinToString("\n").contains("raw cancellation details"))
    }

    @Test
    fun `fatal error uses fixed nonzero diagnostic instead of escaping`() {
        val events = mutableListOf<String>()

        val exitCode =
            runQuiescedMigrationProcess(
                migration = { phaseObserver ->
                    phaseObserver(MigrationPhase.MIGRATION)
                    throw AssertionError("raw fatal error details")
                },
                eventSink = { events += renderMigrationSafeEvent(it) },
            )

        assertEquals(1, exitCode)
        assertEquals(
            "migration-safe:v=1 event=failed phase=migration category=unexpected",
            events.last(),
        )
        assertFalse(events.joinToString("\n").contains("raw fatal error details"))
    }

    @Test
    fun `real entrypoint suppresses connection canaries and stack traces`() {
        val output = temporaryDirectory.resolve("migration-output.log")
        val julConfig = temporaryDirectory.resolve("jul-canary.properties")
        Files.writeString(
            julConfig,
            """
            handlers=java.util.logging.ConsoleHandler
            .level=FINE
            java.util.logging.ConsoleHandler.level=FINE
            org.postgresql.level=FINE
            """.trimIndent(),
        )
        val canaries =
            mapOf(
                "database" to "migration_secret_database",
                "username" to "migration_secret_user",
                "password" to "migration_secret_password",
                "query" to "migrationSecretQuery=secret_query_value",
            )
        val javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process =
            ProcessBuilder(
                javaExecutable,
                "-Dlogback.configurationFile=quiesced-migration-logback.xml",
                "-Dlogback.statusListenerClass=ch.qos.logback.core.status.NopStatusListener",
                "-Djava.util.logging.config.file=$julConfig",
                "-cp",
                System.getProperty("java.class.path"),
                "com.example.bot.tools.QuiescedMigrateMainKt",
            ).redirectErrorStream(true)
                .redirectOutput(output.toFile())
        process.environment().apply {
            put("QUIESCED_RELEASE_MIGRATION", "required")
            put("APP_ENV", "stage")
            put(
                "DATABASE_URL",
                "jdbc:postgresql://127.0.0.1:1/${canaries.getValue("database")}" +
                    "?${canaries.getValue("query")}&connectTimeout=1",
            )
            put("DATABASE_USER", canaries.getValue("username"))
            put("DATABASE_PASSWORD", canaries.getValue("password"))
            put("FLYWAY_ENABLED", "true")
            put("FLYWAY_MODE", "migrate-and-validate")
            put("FLYWAY_LOCATIONS", "classpath:db/migration/postgresql")
            put("FLYWAY_OUT_OF_ORDER", "false")
            remove("JAVA_TOOL_OPTIONS")
            remove("JDK_JAVA_OPTIONS")
            remove("_JAVA_OPTIONS")
            remove("JAVA_OPTS")
            remove("APP_BOT_MIGRATE_OPTS")
        }

        val child = process.start()
        if (!child.waitFor(30, TimeUnit.SECONDS)) {
            child.destroyForcibly()
            child.waitFor()
            throw AssertionError("migration entrypoint timed out")
        }
        assertTrue(child.exitValue() != 0, "connection failure must remain nonzero")
        val captured = Files.readString(output)

        assertEquals(
            "migration-safe:v=1 event=started\n" +
                "migration-safe:v=1 event=failed phase=migration category=connection\n",
            captured,
        )
        assertNoSensitiveValues(captured, canaries)
        assertFalse(captured.contains("jdbc:postgresql:"), "JDBC URL marker was present")
        assertFalse(captured.contains("Exception"), "exception class was present")
        assertFalse(captured.contains("Caused by"), "exception cause marker was present")
        assertFalse(captured.lineSequence().any { it.trimStart().startsWith("at ") }, "stack frame was present")
    }

    private fun assertNoSensitiveValues(
        output: String,
        canaries: Map<String, String>,
    ) {
        canaries.forEach { (label, value) ->
            assertFalse(output.contains(value), "$label sentinel was present")
        }
    }

    private fun successfulFlyway(
        migrationsExecuted: Int = 1,
        validationSuccessful: Boolean = true,
        pending: Array<org.flywaydb.core.api.MigrationInfo> = emptyArray(),
    ): Flyway {
        val flyway = mockk<Flyway>()
        val migrateResult = MigrateResult().apply { this.migrationsExecuted = migrationsExecuted }
        val validateResult =
            ValidateResult(
                "11.14.0",
                "test",
                null,
                validationSuccessful,
                0,
                emptyList(),
                emptyList(),
            )
        val info = mockk<MigrationInfoService>()
        every { info.pending() } returns pending
        every { flyway.migrate() } returns migrateResult
        every { flyway.validateWithResult() } returns validateResult
        every { flyway.info() } returns info
        return flyway
    }

    private fun validEnvironment(): Map<String, String> =
        mapOf(
            "QUIESCED_RELEASE_MIGRATION" to "required",
            "APP_ENV" to "stage",
            "DATABASE_URL" to "jdbc:postgresql://db:5432/clubs",
            "DATABASE_USER" to "clubs",
            "DATABASE_PASSWORD" to "secret",
            "FLYWAY_ENABLED" to "true",
            "FLYWAY_MODE" to "migrate-and-validate",
            "FLYWAY_LOCATIONS" to "classpath:db/migration/postgresql",
            "FLYWAY_OUT_OF_ORDER" to "false",
        )
}
