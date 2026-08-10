package com.example.bot.tools

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.core.ConsoleAppender
import com.example.bot.data.db.AppEnvironment
import com.example.bot.data.db.FlywayConfig
import com.example.bot.data.db.FlywayExecutionContext
import com.example.bot.data.db.FlywayMode
import com.example.bot.data.db.configureFlyway
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import java.net.ConnectException
import java.net.SocketException
import java.net.UnknownHostException
import java.sql.SQLException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.LogManager
import kotlin.system.exitProcess
import java.util.logging.Logger as JulLogger

private const val REQUIRED_RELEASE_MARKER = "required"
private const val POSTGRESQL_JDBC_PREFIX = "jdbc:postgresql:"
private const val MIGRATION_LOG_CONFIG = "quiesced-migration-logback.xml"
private const val MIGRATION_LOG_CONTEXT = "quiesced-migration"
private const val MIGRATION_LOGGER_NAME = "QuiescedMigrations"
private const val MIGRATION_LOG_APPENDER = "MIGRATION_SAFE_CONSOLE"
private const val MIGRATION_STATUS_LISTENER = "ch.qos.logback.core.status.NopStatusListener"
private const val EXIT_FAILURE = 1
private const val EXIT_CANCELLED = 130
private const val MAX_CAUSE_CHAIN_DEPTH = 32

internal enum class MigrationPhase(
    val wireValue: String,
) {
    BOOTSTRAP("bootstrap"),
    CONFIGURATION("configuration"),
    MIGRATION("migration"),
    VALIDATION("validation"),
    PENDING_CHECK("pending-check"),
}

internal enum class MigrationFailureCategory(
    val wireValue: String,
) {
    CONFIGURATION("configuration"),
    CONNECTION("connection"),
    AUTHENTICATION("authentication"),
    MIGRATION("migration"),
    VALIDATION("validation"),
    CANCELLED("cancelled"),
    UNEXPECTED("unexpected"),
}

internal sealed interface MigrationSafeEvent {
    data object Started : MigrationSafeEvent

    data class Completed(
        val migrationsApplied: Int,
    ) : MigrationSafeEvent

    data class Failed(
        val phase: MigrationPhase,
        val category: MigrationFailureCategory,
    ) : MigrationSafeEvent
}

internal fun renderMigrationSafeEvent(event: MigrationSafeEvent): String =
    when (event) {
        MigrationSafeEvent.Started -> "migration-safe:v=1 event=started"
        is MigrationSafeEvent.Completed -> {
            require(event.migrationsApplied >= 0)
            "migration-safe:v=1 event=completed applied=${event.migrationsApplied}"
        }
        is MigrationSafeEvent.Failed ->
            "migration-safe:v=1 event=failed phase=${event.phase.wireValue} category=${event.category.wireValue}"
    }

internal data class QuiescedMigrationResult(
    val migrationsExecuted: Int,
)

internal fun runQuiescedMigration(
    envProvider: (String) -> String? = System::getenv,
    propertyProvider: (String) -> String? = System::getProperty,
    flywayFactory: (String, String, String, FlywayConfig) -> Flyway = ::createFlyway,
    phaseObserver: (MigrationPhase) -> Unit = {},
): QuiescedMigrationResult {
    phaseObserver(MigrationPhase.CONFIGURATION)
    val marker = envProvider("QUIESCED_RELEASE_MIGRATION")
    check(marker == REQUIRED_RELEASE_MARKER) {
        "QUIESCED_RELEASE_MIGRATION must be exactly $REQUIRED_RELEASE_MARKER"
    }

    val databaseUrl = requiredEnv("DATABASE_URL", envProvider)
    val databaseUser = requiredEnv("DATABASE_USER", envProvider)
    val databasePassword = requiredEnv("DATABASE_PASSWORD", envProvider)
    check(databaseUrl.startsWith(POSTGRESQL_JDBC_PREFIX, ignoreCase = true)) {
        "Quiesced release migration requires PostgreSQL"
    }

    val config =
        FlywayConfig.fromQuiescedMigrationEnv(
            envProvider = envProvider,
            propertyProvider = propertyProvider,
        )
    check(config.appEnv == AppEnvironment.PROD || config.appEnv == AppEnvironment.STAGE) {
        "Quiesced release migration requires APP_ENV=prod or APP_ENV=stage"
    }
    check(config.executionContext == FlywayExecutionContext.QUIESCED_MIGRATION)
    check(config.enabled) { "Flyway must be enabled for quiesced release migration" }
    check(config.mode == FlywayMode.MIGRATE_AND_VALIDATE) {
        "FLYWAY_MODE must be explicitly set to migrate-and-validate"
    }
    check(config.effectiveMode == FlywayMode.MIGRATE_AND_VALIDATE)
    check(!config.outOfOrderRequested && !config.outOfOrderEnabled) {
        "Out-of-order Flyway migration is forbidden for quiesced releases"
    }
    check(!config.baselineOnMigrate) {
        "Baseline-on-migrate is forbidden for quiesced releases"
    }
    check(config.locations.all { it.startsWith("classpath:db/migration/") }) {
        "Quiesced release migrations must use image classpath resources"
    }
    validateDeclaredLocations(envProvider, propertyProvider, config)

    phaseObserver(MigrationPhase.MIGRATION)
    val flyway = flywayFactory(databaseUrl, databaseUser, databasePassword, config)
    val migrateResult = flyway.migrate()
    phaseObserver(MigrationPhase.VALIDATION)
    val validation = flyway.validateWithResult()
    check(validation.validationSuccessful) { "Flyway validation failed after migrate" }
    phaseObserver(MigrationPhase.PENDING_CHECK)
    val pending = flyway.info().pending()
    check(pending.isEmpty()) { "Flyway has pending migrations after migrate" }

    return QuiescedMigrationResult(migrationsExecuted = migrateResult.migrationsExecuted)
}

internal fun runQuiescedMigrationProcess(
    migration: ((MigrationPhase) -> Unit) -> QuiescedMigrationResult = { phaseObserver ->
        runQuiescedMigration(phaseObserver = phaseObserver)
    },
    eventSink: (MigrationSafeEvent) -> Unit,
    phaseSink: (MigrationPhase) -> Unit = {},
): Int {
    var phase = MigrationPhase.BOOTSTRAP

    fun advance(next: MigrationPhase) {
        phase = next
        phaseSink(next)
    }

    eventSink(MigrationSafeEvent.Started)
    return try {
        val result = migration(::advance)
        eventSink(MigrationSafeEvent.Completed(result.migrationsExecuted))
        0
    } catch (_: CancellationException) {
        eventSink(MigrationSafeEvent.Failed(phase, MigrationFailureCategory.CANCELLED))
        EXIT_CANCELLED
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        eventSink(MigrationSafeEvent.Failed(phase, MigrationFailureCategory.CANCELLED))
        EXIT_CANCELLED
    } catch (failure: Exception) {
        eventSink(MigrationSafeEvent.Failed(phase, classifyFailure(phase, failure)))
        EXIT_FAILURE
    } catch (_: Error) {
        // Fatal JVM failures are not classified from their text and never escape to the
        // default stack-trace printer. main() terminates the dedicated process immediately.
        eventSink(MigrationSafeEvent.Failed(phase, MigrationFailureCategory.UNEXPECTED))
        EXIT_FAILURE
    }
}

private fun createFlyway(
    url: String,
    user: String,
    password: String,
    config: FlywayConfig,
): Flyway =
    configureFlyway(
        Flyway.configure().loggers("slf4j").dataSource(url, user, password),
        config,
    )

private fun classifyFailure(
    phase: MigrationPhase,
    failure: Exception,
): MigrationFailureCategory {
    val causes = failure.causeChain()
    val sqlState = causes.filterIsInstance<SQLException>().firstNotNullOfOrNull { it.sqlState }
    val isAuthenticationFailure = sqlState?.startsWith("28") == true
    val hasConnectionSqlState = sqlState?.startsWith("08") == true
    val hasConnectionCause =
        causes.any { cause ->
            cause is ConnectException || cause is SocketException || cause is UnknownHostException
        }
    val isConnectionFailure = hasConnectionSqlState || hasConnectionCause
    return when {
        isAuthenticationFailure -> MigrationFailureCategory.AUTHENTICATION
        isConnectionFailure -> MigrationFailureCategory.CONNECTION
        else -> phase.defaultFailureCategory()
    }
}

private fun MigrationPhase.defaultFailureCategory(): MigrationFailureCategory =
    when (this) {
        MigrationPhase.BOOTSTRAP,
        MigrationPhase.CONFIGURATION,
        -> MigrationFailureCategory.CONFIGURATION
        MigrationPhase.MIGRATION -> MigrationFailureCategory.MIGRATION
        MigrationPhase.VALIDATION,
        MigrationPhase.PENDING_CHECK,
        -> MigrationFailureCategory.VALIDATION
    }

private fun Throwable.causeChain(): List<Throwable> {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val causes = mutableListOf<Throwable>()
    var current: Throwable? = this
    while (current != null && seen.add(current) && causes.size < MAX_CAUSE_CHAIN_DEPTH) {
        causes += current
        current = current.cause
    }
    return causes
}

private fun requiredEnv(
    name: String,
    envProvider: (String) -> String?,
): String =
    envProvider(name)?.takeIf { it.isNotBlank() }
        ?: error("$name is not set")

private fun validateDeclaredLocations(
    envProvider: (String) -> String?,
    propertyProvider: (String) -> String?,
    config: FlywayConfig,
) {
    val declared = propertyProvider("FLYWAY_LOCATIONS") ?: envProvider("FLYWAY_LOCATIONS") ?: return
    val declaredConfig =
        FlywayConfig.fromEnv(
            envProvider = envProvider,
            propertyProvider = propertyProvider,
            locationsOverride = declared,
        )
    check(declaredConfig.locations.toSet() == config.locations.toSet()) {
        "FLYWAY_LOCATIONS must resolve only to packaged PostgreSQL and common migrations"
    }
}

private fun createMigrationEventSink(): (MigrationSafeEvent) -> Unit {
    check(System.getProperty("logback.configurationFile") == MIGRATION_LOG_CONFIG)
    check(System.getProperty("logback.statusListenerClass") == MIGRATION_STATUS_LISTENER)
    checkNotNull(QuiescedMigrateMain::class.java.classLoader.getResource(MIGRATION_LOG_CONFIG))

    disableJulLogging()
    val context = LoggerFactory.getILoggerFactory() as? LoggerContext ?: error("migration logging backend is invalid")
    check(context.name == MIGRATION_LOG_CONTEXT)
    val rootLogger = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
    check(rootLogger.effectiveLevel == Level.OFF && !rootLogger.iteratorForAppenders().hasNext())
    val flywayLoggers =
        context.loggerList.filter { logger ->
            logger.name == "org.flywaydb" || logger.name.startsWith("org.flywaydb.")
        }
    check(flywayLoggers.isNotEmpty())
    check(
        flywayLoggers.all { logger ->
            logger.effectiveLevel == Level.OFF && !logger.iteratorForAppenders().hasNext()
        },
    )

    val logger = context.getLogger(MIGRATION_LOGGER_NAME)
    check(logger.effectiveLevel == Level.INFO && !logger.isAdditive)
    val appenderNames =
        logger
            .iteratorForAppenders()
            .asSequence()
            .map { it.name }
            .toList()
    check(appenderNames == listOf(MIGRATION_LOG_APPENDER))
    val appender =
        logger.getAppender(MIGRATION_LOG_APPENDER) as? ConsoleAppender<*>
            ?: error("migration logger appender is invalid")
    check(appender.target == "System.out")
    val encoder =
        appender.encoder as? PatternLayoutEncoder
            ?: error("migration logger encoder is invalid")
    check(encoder.pattern == "%msg%n%nopex")
    return { event -> logger.info(renderMigrationSafeEvent(event)) }
}

private fun disableJulLogging() {
    LogManager.getLogManager().reset()
    JulLogger.getLogger("org.postgresql").apply {
        level = java.util.logging.Level.OFF
        useParentHandlers = false
        handlers.forEach(::removeHandler)
    }
}

private object QuiescedMigrateMain

fun main() {
    val phase = AtomicReference(MigrationPhase.BOOTSTRAP)
    Thread.setDefaultUncaughtExceptionHandler { _, failure ->
        System.err.println(
            renderMigrationSafeEvent(
                MigrationSafeEvent.Failed(
                    phase.get(),
                    MigrationFailureCategory.UNEXPECTED,
                ),
            ),
        )
    }

    val eventSink =
        try {
            createMigrationEventSink()
        } catch (_: Exception) {
            System.err.println(
                renderMigrationSafeEvent(
                    MigrationSafeEvent.Failed(
                        MigrationPhase.BOOTSTRAP,
                        MigrationFailureCategory.CONFIGURATION,
                    ),
                ),
            )
            exitProcess(EXIT_FAILURE)
        }

    val exitCode =
        runQuiescedMigrationProcess(
            eventSink = eventSink,
            phaseSink = phase::set,
        )
    if (exitCode != 0) {
        exitProcess(exitCode)
    }
}
