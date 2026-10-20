package com.example.bot.tools

import com.example.bot.data.db.FlywayMode
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.CoreMigrationType
import org.flywaydb.core.api.MigrationInfo
import org.flywaydb.core.api.MigrationInfoService
import org.flywaydb.core.api.MigrationState
import org.flywaydb.core.api.MigrationVersion
import org.flywaydb.core.api.output.ValidateResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Date
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

internal fun bootstrapTestEnvironment(): Map<String, String> =
    mapOf(
        "APP_ENV" to "stage",
        "DATABASE_URL" to "jdbc:postgresql://127.0.0.1:1/synthetic",
        "DATABASE_USER" to "synthetic_user",
        "DATABASE_PASSWORD" to "synthetic_password",
        "FLYWAY_ENABLED" to "true",
        "FLYWAY_MODE" to "validate",
        "FLYWAY_BASELINE_ON_MIGRATE" to "false",
    )

internal fun bootstrapChildResult(
    env: Map<String, String>,
    output: Path,
): Pair<Int, String> {
    val process =
        ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Dlogback.configurationFile=bootstrap-validation-logback.xml",
            "-Dlogback.statusListenerClass=ch.qos.logback.core.status.NopStatusListener",
            "-cp",
            System.getProperty("java.class.path"),
            "com.example.bot.tools.BootstrapValidateMainKt",
        ).redirectErrorStream(true).redirectOutput(output.toFile())
    process.environment().apply {
        keys.toList().filter { it.startsWith("FLYWAY_") || it.endsWith("JAVA_OPTIONS") }.forEach(::remove)
        remove("JAVA_TOOL_OPTIONS")
        remove("APP_PROFILE")
        putAll(env)
        put("TELEGRAM_BOT_TOKEN", "invalid_secret_telegram_canary")
        put("PAYMENTS_PROVIDER_TOKEN", "secret_payment_canary")
    }
    val child = process.start()
    if (!child.waitFor(30, TimeUnit.SECONDS)) {
        child.destroyForcibly()
        throw AssertionError("validation process timed out")
    }
    return child.exitValue() to Files.readString(output)
}

class BootstrapValidateMainTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `matching evidence validates without any mutator`() {
        val flyway = evidence()
        validateBootstrapSchema(bootstrapTestEnvironment()) { url, _, _, config ->
            assertTrue(url.contains("default_transaction_read_only%3Don"))
            assertEquals(FlywayMode.VALIDATE, config.effectiveMode)
            assertFalse(config.baselineOnMigrate)
            assertFalse(config.outOfOrderEnabled)
            flyway
        }
        verify(exactly = 1) { flyway.validateWithResult() }
        verify(exactly = 1) { flyway.info() }
        verify(exactly = 0) { flyway.migrate() }
        verify(exactly = 0) { flyway.baseline() }
        verify(exactly = 0) { flyway.repair() }
        verify(exactly = 0) { flyway.clean() }
        confirmVerified(flyway)
    }

    @Test
    fun `unsafe and ambiguous configuration never constructs Flyway`() {
        val invalid =
            mapOf(
                "APP_ENV" to listOf(null, "", "local", "production", "STAGE"),
                "APP_PROFILE" to listOf("prod"),
                "FLYWAY_ENABLED" to listOf(null, "false", "TRUE", "invalid"),
                "FLYWAY_MODE" to listOf(null, "", "off", "migrate-and-validate", "unknown"),
                "FLYWAY_BASELINE_ON_MIGRATE" to listOf(null, "true", "invalid"),
                "FLYWAY_OUT_OF_ORDER" to listOf("true", "invalid"),
                "FLYWAY_VALIDATE_ONLY" to listOf("false", "invalid"),
                "FLYWAY_LOCATIONS" to listOf("", "classpath:db/migration/h2", "filesystem:/tmp/sql"),
                "FLYWAY_SCHEMAS" to listOf("", "public,other", "public;sql", " public"),
                "FLYWAY_IGNORE_MIGRATION_PATTERNS" to listOf("*:future"),
                "DATABASE_USER" to listOf(null, ""),
                "DATABASE_PASSWORD" to listOf(null, ""),
            )
        invalid.forEach { (key, values) ->
            values.forEach { value ->
                val env = bootstrapTestEnvironment().toMutableMap()
                if (value == null) env.remove(key) else env[key] = value
                assertThrows(Exception::class.java, {
                    validateBootstrapSchema(env) { _, _, _, _ -> throw AssertionError("factory reached") }
                }, "$key=$value")
            }
        }
    }

    @Test
    fun `production and exact packaged locations are accepted`() {
        val env =
            bootstrapTestEnvironment() +
                mapOf(
                    "APP_ENV" to "prod",
                    "APP_PROFILE" to "prod",
                    "FLYWAY_LOCATIONS" to "classpath:db/migration/common,classpath:db/migration/postgresql",
                    "FLYWAY_SCHEMAS" to "public",
                    "FLYWAY_VALIDATE_ONLY" to "true",
                    "FLYWAY_OUT_OF_ORDER" to "false",
                )
        assertEquals(listOf("public"), bootstrapConfig(env).schemas)
    }

    @Test
    fun `driver hooks and ambiguous PostgreSQL targets are rejected`() {
        listOf(
            "jdbc:h2:mem:test",
            "jdbc:postgresql:db",
            "jdbc:postgresql:///db",
            "jdbc:postgresql://user:secret@localhost/db",
            "jdbc:postgresql://localhost/db#x",
            "jdbc:postgresql://localhost/db?options=-c%20default_transaction_read_only=off",
            "jdbc:postgresql://localhost/db?socketFactory=evil.Factory",
            "jdbc:postgresql://localhost/db?loggerFile=/tmp/leak",
            "jdbc:postgresql://localhost/db?sslmode=require&sslmode=disable",
            "jdbc:postgresql://localhost/db?",
            "jdbc:postgresql://localhost:0/db",
            "jdbc:postgresql://localhost:65536/db",
            "jdbc:postgresql://localhost:/db",
            "jdbc:postgresql://localhost:05432/db",
            "jdbc:postgresql://localhost/a/b",
        ).forEach { assertThrows(Exception::class.java) { readOnlyPostgresUrl(it) } }
        assertTrue(readOnlyPostgresUrl("jdbc:postgresql://localhost/db?sslmode=verify-full").contains("&readOnly=true"))
    }

    @Test
    fun `failed validation empty evidence and pending fail closed`() {
        listOf(
            evidence(valid = false),
            evidence(count = 0),
            evidence(pending = true),
            evidence(checksum = null),
            evidence(installed = null),
            evidence(rows = 0),
        ).forEach { flyway ->
            assertThrows(Exception::class.java) {
                validateBootstrapSchema(bootstrapTestEnvironment()) { _, _, _, _ -> flyway }
            }
        }
    }

    @Test
    fun `every non success history state is rejected`() {
        MigrationState.entries.filter { it != MigrationState.SUCCESS }.forEach { state ->
            assertThrows(Exception::class.java, {
                validateBootstrapSchema(bootstrapTestEnvironment()) { _, _, _, _ -> evidence(state = state) }
            }, state.name)
        }
    }

    @Test
    fun `process converts sensitive exceptions and fatal errors to fixed exit codes`() {
        listOf(IllegalStateException("secret SQL jdbc password"), AssertionError("sensitive")).forEach { failure ->
            assertEquals(1, bootstrapValidationExitCode { throw failure })
        }
        assertEquals(130, bootstrapValidationExitCode { throw CancellationException("secret") })
        assertEquals(0, bootstrapValidationExitCode {})
    }

    @Test
    fun `real main emits one safe line without application or integration startup`() {
        val output = temporaryDirectory.resolve("output")
        assertEquals(
            1 to "schema-validation:v=1 result=rejected\n",
            bootstrapChildResult(bootstrapTestEnvironment(), output),
        )
    }

    private fun evidence(
        valid: Boolean = true,
        count: Int = 1,
        rows: Int = 1,
        state: MigrationState = MigrationState.SUCCESS,
        checksum: Int? = 123,
        installed: Date? = Date(),
        pending: Boolean = false,
    ): Flyway {
        val migration = mockk<MigrationInfo>()
        every { migration.state } returns state
        every { migration.type } returns CoreMigrationType.SQL
        every { migration.version } returns MigrationVersion.fromVersion("1")
        every { migration.checksum } returns checksum
        every { migration.installedOn } returns installed
        val info = mockk<MigrationInfoService>()
        every { info.pending() } returns if (pending) arrayOf(migration) else emptyArray()
        every { info.all() } returns Array(rows) { migration }
        val flyway = mockk<Flyway>()
        every { flyway.info() } returns info
        every { flyway.validateWithResult() } returns
            ValidateResult("11.14.0", "synthetic", null, valid, count, emptyList(), emptyList())
        return flyway
    }
}
