package com.example.bot.tools

import com.example.bot.data.db.FlywayConfig
import com.example.bot.data.db.FlywayMode
import com.example.bot.data.db.configureFlyway
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.CoreMigrationType
import org.flywaydb.core.api.MigrationState
import java.io.OutputStream
import java.io.PrintStream
import java.net.URI
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.LogManager
import kotlin.system.exitProcess

private val packagedLocations =
    setOf("classpath:db/migration/postgresql", "classpath:db/migration/common")
private const val REJECTED = "schema-validation:v=1 result=rejected"
private const val COMPATIBLE = "schema-validation:v=1 result=compatible"
private const val PROCESS_TIMEOUT_MS = 60_000L
private const val MAX_PORT = 65535
private const val EXIT_CANCELLED = 130

/** No application configuration, plugins, pools, metrics or integration initialization. */
internal fun validateBootstrapSchema(
    env: Map<String, String>,
    flywayFactory: (String, String, String, FlywayConfig) -> Flyway = ::bootstrapFlyway,
) {
    val config = bootstrapConfig(env)
    val url = readOnlyPostgresUrl(checkNotNull(env["DATABASE_URL"]))
    val user = checkNotNull(env["DATABASE_USER"]).also { check(it.isNotBlank()) }
    val password = checkNotNull(env["DATABASE_PASSWORD"]).also { check(it.isNotBlank()) }
    val flyway = flywayFactory(url, user, password, config)

    // Same validate + zero-pending contract as MigrationRunner, with stricter evidence.
    val validation = flyway.validateWithResult()
    check(validation.validationSuccessful && validation.validateCount > 0)
    val info = flyway.info()
    check(info.pending().isEmpty())
    val migrations = info.all()
    check(migrations.isNotEmpty() && validation.validateCount == migrations.size)
    check(
        migrations.all {
            it.state == MigrationState.SUCCESS &&
                it.type == CoreMigrationType.SQL &&
                it.version != null &&
                it.checksum != null &&
                it.installedOn != null
        },
    )
}

internal fun bootstrapConfig(env: Map<String, String>): FlywayConfig {
    check(env["APP_ENV"] in setOf("stage", "prod"))
    check(env["APP_PROFILE"] == null || env["APP_PROFILE"] == env["APP_ENV"])
    check(env["FLYWAY_ENABLED"] == "true")
    check(env["FLYWAY_MODE"] == "validate")
    check(env["FLYWAY_BASELINE_ON_MIGRATE"] == "false")
    check(env["FLYWAY_OUT_OF_ORDER"] == null || env["FLYWAY_OUT_OF_ORDER"] == "false")
    check(env["FLYWAY_VALIDATE_ONLY"] == null || env["FLYWAY_VALIDATE_ONLY"] == "true")
    val supportedFlywayKeys =
        setOf(
            "FLYWAY_ENABLED",
            "FLYWAY_MODE",
            "FLYWAY_BASELINE_ON_MIGRATE",
            "FLYWAY_OUT_OF_ORDER",
            "FLYWAY_VALIDATE_ONLY",
            "FLYWAY_LOCATIONS",
            "FLYWAY_SCHEMAS",
        )
    check(env.keys.none { it.startsWith("FLYWAY_") && it !in supportedFlywayKeys })
    env["FLYWAY_SCHEMAS"]?.let { check(it.matches(Regex("[a-z_][a-z0-9_]{0,62}"))) }
    env["FLYWAY_LOCATIONS"]?.let { declared ->
        val locations = declared.split(",")
        check(locations.size == packagedLocations.size && locations.toSet() == packagedLocations)
    }
    val config =
        FlywayConfig.fromEnv(
            envProvider = env::get,
            propertyProvider = { null },
            locationsOverride = packagedLocations.joinToString(","),
        )
    check(config.enabled && config.mode == FlywayMode.VALIDATE && config.effectiveMode == FlywayMode.VALIDATE)
    check(!config.baselineOnMigrate && !config.outOfOrderRequested && !config.outOfOrderEnabled)
    return config
}

/** Exclude driver hooks, service files, credentials, session options and ambiguous targets. */
internal fun readOnlyPostgresUrl(url: String): String {
    check(url.startsWith("jdbc:postgresql://"))
    val uri = URI(url.removePrefix("jdbc:"))
    check(uri.host != null && uri.userInfo == null && uri.fragment == null)
    check(uri.port == -1 || uri.port in 1..MAX_PORT)
    val authority = uri.host + if (uri.port == -1) "" else ":${uri.port}"
    check(uri.rawAuthority == authority)
    check(uri.rawPath.matches(Regex("/[a-zA-Z0-9_][a-zA-Z0-9_-]{0,62}")))
    check(uri.rawQuery == null || uri.rawQuery.matches(Regex("sslmode=(disable|require|verify-ca|verify-full)")))
    val separator = if (uri.rawQuery == null) "?" else "&"
    return url + separator +
        "connectTimeout=5&socketTimeout=15&loginTimeout=5&readOnly=true&readOnlyMode=always&" +
        "options=-c%20default_transaction_read_only%3Don%20-c%20statement_timeout%3D15000"
}

internal fun bootstrapFlyway(
    url: String,
    user: String,
    password: String,
    config: FlywayConfig,
): Flyway =
    configureFlyway(
        Flyway
            .configure()
            .dataSource(url, user, password)
            .loggers("slf4j")
            .connectRetries(0)
            .createSchemas(false)
            .cleanDisabled(true)
            .failOnMissingLocations(true)
            .validateMigrationNaming(true)
            .ignoreMigrationPatterns("")
            .skipDefaultCallbacks(true),
        config,
    )

internal fun bootstrapValidationExitCode(validation: () -> Unit): Int =
    try {
        validation()
        0
    } catch (_: CancellationException) {
        // This is the terminal process boundary, not a coroutine recovery path.
        EXIT_CANCELLED
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        EXIT_CANCELLED
    } catch (_: Exception) {
        1
    } catch (_: Error) {
        1
    }

fun main(args: Array<String>) {
    val resultOutput = System.out
    val completed = AtomicBoolean(false)
    // Suppress libraries, JUL, Flyway and default exception output before initialization.
    val silentOutput = PrintStream(OutputStream.nullOutputStream())
    System.setOut(silentOutput)
    System.setErr(silentOutput)
    Thread.setDefaultUncaughtExceptionHandler { _, _ ->
        if (completed.compareAndSet(false, true)) resultOutput.println(REJECTED)
        Runtime.getRuntime().halt(1)
    }
    Thread.ofPlatform().daemon().start {
        Thread.sleep(PROCESS_TIMEOUT_MS)
        if (completed.compareAndSet(false, true)) {
            resultOutput.println(REJECTED)
            Runtime.getRuntime().halt(1)
        }
    }
    val exitCode =
        bootstrapValidationExitCode {
            check(args.isEmpty())
            LogManager.getLogManager().reset()
            validateBootstrapSchema(System.getenv())
        }
    if (completed.compareAndSet(false, true)) {
        resultOutput.println(if (exitCode == 0) COMPATIBLE else REJECTED)
    }
    exitProcess(exitCode)
}
