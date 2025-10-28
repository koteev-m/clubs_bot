package com.example.bot.data.db

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.output.MigrateResult
import org.flywaydb.core.api.output.ValidateResult
import org.slf4j.LoggerFactory
import javax.sql.DataSource

class MigrationRunner(private val dataSource: DataSource, private val cfg: FlywayConfig) {
    private val log = LoggerFactory.getLogger(MigrationRunner::class.java)

    sealed interface Result {
        data class Migrated(val migrate: MigrateResult) : Result

        data class Validated(val validate: ValidateResult) : Result
    }

    @Suppress("SpreadOperator")
    fun run(): Result? {
        if (!cfg.enabled) {
            log.info("Flyway is disabled (FLYWAY_ENABLED=false), skipping migrations")
            return null
        }

        val builder =
            Flyway
                .configure()
                .dataSource(dataSource)
                .locations(*cfg.locations.toTypedArray())
                .baselineOnMigrate(cfg.baselineOnMigrate)
                .validateOnMigrate(true)

        if (cfg.schemas.isNotEmpty()) {
            builder.schemas(*cfg.schemas.toTypedArray())
        }

        val flyway = builder.load()
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
            "Flyway migrate: version={} url={} locations={} schemas={} schema={} catalog={}",
            flywayVersion,
            jdbcUrl ?: "unknown",
            configuredLocations.joinToString(","),
            if (configuredSchemas.isEmpty()) "" else configuredSchemas,
            currentSchema ?: "",
            currentCatalog ?: "",
        )

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

        return if (cfg.validateOnly) {
            log.info("Flyway validate-only mode enabled")
            val res = flyway.validateWithResult()
            if (!res.validationSuccessful) {
                log.error("Flyway validation failed: {}", res.errorDetails?.errorMessage)
                throw IllegalStateException("Flyway validation failed")
            }
            Result.Validated(res)
        } else {
            val res = flyway.migrate()
            log.info("Flyway migrated. migrationsExecuted={}", res.migrationsExecuted)
            Result.Migrated(res)
        }
    }
}
