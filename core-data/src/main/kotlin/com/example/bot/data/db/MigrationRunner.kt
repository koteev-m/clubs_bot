package com.example.bot.data.db

import com.example.bot.data.db.DbErrorClassifier
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationInfo
import org.flywaydb.core.api.output.MigrateResult
import org.flywaydb.core.api.output.ValidateResult
import org.slf4j.LoggerFactory
import javax.sql.DataSource

class MigrationRunner(
    private val dataSource: DataSource,
    private val cfg: FlywayConfig,
    private val flywayFactory: (DataSource, FlywayConfig) -> Flyway = ::configureFlyway,
) {
    private val log = LoggerFactory.getLogger(MigrationRunner::class.java)

    sealed interface Result {
        data class Migrated(
            val migrate: MigrateResult,
        ) : Result

        data class Validated(
            val validate: ValidateResult,
        ) : Result
    }

    @Suppress("SpreadOperator")
    fun run(): Result? {
        if (!cfg.enabled) {
            log.info("Flyway is disabled (FLYWAY_ENABLED=false), skipping migrations")
            return null
        }

        val flyway = flywayFactory(dataSource, cfg)
        val metadata =
            runCatching {
                dataSource.connection.use { conn ->
                    val schema = runCatching { conn.schema }.getOrNull()
                    val catalog = runCatching { conn.catalog }.getOrNull()
                    Triple(conn.metaData?.url, schema, catalog)
                }
            }.getOrElse { Triple(null, null, null) }
        val (jdbcUrl, currentSchema, currentCatalog) = metadata

        val configuredLocations = flyway.configuration.locations.map { it.descriptor }
        val configuredSchemas = flyway.configuration.schemas.joinToString(",")
        val flywayVersion = Flyway::class.java.`package`?.implementationVersion ?: "unknown"

        log.info(
            "Flyway start: mode={} effectiveMode={} appEnv={} outOfOrder={} version={} url={} locations={} schemas={} schema={} catalog={}",
            cfg.mode,
            cfg.effectiveMode,
            cfg.appEnv,
            cfg.outOfOrderEnabled,
            flywayVersion,
            jdbcUrl ?: "unknown",
            configuredLocations.joinToString(","),
            if (configuredSchemas.isEmpty()) "" else configuredSchemas,
            currentSchema ?: "",
            currentCatalog ?: "",
        )

        if (cfg.effectiveMode == FlywayMode.OFF) {
            log.info("Flyway mode=off, skipping migrate/validate")
            log.info(
                "Flyway summary: mode=off env={} effectiveMode=off locations={} schemas={}",
                cfg.appEnv,
                configuredLocations.joinToString(","),
                configuredSchemas,
            )
            return null
        }

        if (log.isDebugEnabled) {
            val migrations =
                flyway
                    .info()
                    .all()
                    .joinToString(", ") { info ->
                        val version = info.version?.toString() ?: "<repeat>"
                        "$version:${info.script}:${info.state}"
            }
            log.debug("Flyway available migrations: {}", migrations)
        }

        return runCatching {
            when (cfg.effectiveMode) {
                FlywayMode.VALIDATE -> validateOnly(flyway)
                FlywayMode.MIGRATE_AND_VALIDATE -> migrateAndValidate(flyway)
                FlywayMode.OFF -> null
            }
        }.onSuccess { result ->
            // Метрики и summary-лог используют одно и то же актуальное значение pending после выполнения режима
            val pending = pendingMigrations(flyway).size
            when (result) {
                is Result.Validated ->
                    log.info(
                        "Flyway summary: mode=validate env={} effectiveMode={} pending={} locations={} schemas={}",
                        cfg.appEnv,
                        cfg.effectiveMode,
                        pending,
                        configuredLocations.joinToString(","),
                        configuredSchemas,
                    )
                is Result.Migrated ->
                    log.info(
                        "Flyway summary: mode=migrate-and-validate env={} effectiveMode={} applied={} pending={} locations={} schemas={}",
                        cfg.appEnv,
                        cfg.effectiveMode,
                        result.migrate.migrationsExecuted,
                        pending,
                        configuredLocations.joinToString(","),
                        configuredSchemas,
                    )
                null ->
                    log.info(
                        "Flyway summary: mode={} env={} effectiveMode={} locations={} schemas={}",
                        cfg.mode,
                        cfg.appEnv,
                        cfg.effectiveMode,
                        configuredLocations.joinToString(","),
                        configuredSchemas,
                    )
            }
        }.onFailure { t ->
            val classification = DbErrorClassifier.classify(t)
            log.error(
                "Flyway failed: mode={} effectiveMode={} appEnv={} reason={} sqlState={}",
                cfg.mode,
                cfg.effectiveMode,
                cfg.appEnv,
                classification.reason,
                classification.sqlState ?: "<none>",
                t,
            )
        }.getOrThrow()
    }

    private fun validateOnly(flyway: Flyway): Result.Validated {
        log.info("Flyway validate-only mode enabled")
        val validation =
            try {
                flyway.validateWithResult()
            } catch (t: Throwable) {
                DbMigrationMetricsHolder.metrics.recordValidationFailure(null)
                throw t
            }
        if (!validation.validationSuccessful) {
            val pendingCount = runCatching { pendingMigrations(flyway).size }.getOrNull()
            DbMigrationMetricsHolder.metrics.recordValidationFailure(pendingCount)
            log.error("Flyway validation failed: {}", validation.errorDetails?.errorMessage)
            throw IllegalStateException("Flyway validation failed")
        }

        val pendingMigrations = pendingMigrations(flyway)
        if (pendingMigrations.isNotEmpty()) {
            val pendingList = pendingMigrations.joinToString(",") { it.version?.toString() ?: "<repeatable>" }
            DbMigrationMetricsHolder.metrics.recordValidationFailure(pendingMigrations.size)
            log.error("Flyway pending migrations detected ({}), failing startup: {}", pendingMigrations.size, pendingList)
            throw IllegalStateException("Flyway has pending migrations that must be executed via CI")
        }

        DbMigrationMetricsHolder.metrics.recordValidationSuccess(pendingMigrations.size)
        return Result.Validated(validation)
    }

    private fun migrateAndValidate(flyway: Flyway): Result.Migrated {
        val res =
            try {
                flyway.migrate()
            } catch (t: Throwable) {
                DbMigrationMetricsHolder.metrics.recordMigrateFailure()
                throw t
            }
        log.info("Flyway migrated. migrationsExecuted={}", res.migrationsExecuted)

        val validation =
            try {
                flyway.validateWithResult()
            } catch (t: Throwable) {
                val pendingCount = runCatching { pendingMigrations(flyway).size }.getOrNull()
                DbMigrationMetricsHolder.metrics.recordValidationFailure(pendingCount)
                throw t
            }
        if (!validation.validationSuccessful) {
            val pendingCount = runCatching { pendingMigrations(flyway).size }.getOrNull()
            DbMigrationMetricsHolder.metrics.recordValidationFailure(pendingCount)
            log.error("Flyway post-migrate validation failed: {}", validation.errorDetails?.errorMessage)
            throw IllegalStateException("Flyway validation failed after migrate")
        }

        val pendingMigrations = pendingMigrations(flyway)
        DbMigrationMetricsHolder.metrics.recordMigrateSuccess(res.migrationsExecuted)
        DbMigrationMetricsHolder.metrics.recordValidationSuccess(pendingMigrations.size)
        return Result.Migrated(res)
    }

    private fun pendingMigrations(flyway: Flyway): List<MigrationInfo> =
        flyway
            .info()
            .pending()
            .toList()
}
