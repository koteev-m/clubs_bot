package com.example.bot.data.db

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

        val effectiveMode = cfg.effectiveMode
        if (cfg.mode == FlywayMode.MIGRATE_AND_VALIDATE && cfg.appEnv.isProdLike) {
            log.warn(
                "Flyway migrate-and-validate mode is not allowed for {} environment, falling back to validate-only",
                cfg.appEnv,
            )
        }
        if (cfg.outOfOrderEnabled) {
            log.warn("Flyway out-of-order migrations enabled for {}", cfg.appEnv)
        }
        if (cfg.outOfOrderRequested && !cfg.appEnv.allowsOutOfOrder) {
            log.warn("Out-of-order migrations are disabled in {} environment", cfg.appEnv)
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
            "Flyway start: mode={} validateOnMigrate={} outOfOrder={} version={} url={} locations={} schemas={} schema={} catalog={}",
            effectiveMode,
            true,
            cfg.outOfOrderEnabled,
            flywayVersion,
            jdbcUrl ?: "unknown",
            configuredLocations.joinToString(","),
            if (configuredSchemas.isEmpty()) "" else configuredSchemas,
            currentSchema ?: "",
            currentCatalog ?: "",
        )

        if (effectiveMode == FlywayMode.OFF) {
            log.info("Flyway mode=off, skipping migrate/validate")
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

        return when (effectiveMode) {
            FlywayMode.VALIDATE -> validateOnly(flyway)
            FlywayMode.MIGRATE_AND_VALIDATE -> migrateAndValidate(flyway)
            FlywayMode.OFF -> null
        }
    }

    private fun validateOnly(flyway: Flyway): Result.Validated {
        log.info("Flyway validate-only mode enabled")
        val validation = flyway.validateWithResult()
        if (!validation.validationSuccessful) {
            log.error("Flyway validation failed: {}", validation.errorDetails?.errorMessage)
            throw IllegalStateException("Flyway validation failed")
        }

        val pendingMigrations = pendingMigrations(flyway)
        if (pendingMigrations.isNotEmpty()) {
            val pendingList = pendingMigrations.joinToString(",") { it.version?.toString() ?: "<repeatable>" }
            log.error("Flyway pending migrations detected ({}), failing startup: {}", pendingMigrations.size, pendingList)
            throw IllegalStateException("Flyway has pending migrations that must be executed via CI")
        }

        return Result.Validated(validation)
    }

    private fun migrateAndValidate(flyway: Flyway): Result.Migrated {
        val res = flyway.migrate()
        log.info("Flyway migrated. migrationsExecuted={}", res.migrationsExecuted)

        val validation = flyway.validateWithResult()
        if (!validation.validationSuccessful) {
            log.error("Flyway post-migrate validation failed: {}", validation.errorDetails?.errorMessage)
            throw IllegalStateException("Flyway validation failed after migrate")
        }

        return Result.Migrated(res)
    }

    private fun pendingMigrations(flyway: Flyway): List<MigrationInfo> =
        flyway
            .info()
            .pending()
            .toList()
}
