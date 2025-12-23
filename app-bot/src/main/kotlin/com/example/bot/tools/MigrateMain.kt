package com.example.bot.tools

import com.example.bot.data.db.FlywayConfig
import com.example.bot.data.db.FlywayMode
import com.example.bot.data.db.configureFlyway
import org.slf4j.LoggerFactory

fun main() {
    val log = LoggerFactory.getLogger("Migrations")

    val url = System.getenv("DATABASE_URL") ?: error("DATABASE_URL is not set")
    val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is not set")
    val pass = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is not set")

    val locationsRaw =
        System.getenv("FLYWAY_LOCATIONS")
            ?: "classpath:db/migration/postgresql,classpath:db/migration/common"

    // Важно: здесь не нужен Hikari — Flyway сам создаст DataSource по URL/USER/PASS
    val flywayConfig =
        FlywayConfig.fromEnv(
            locationsOverride = locationsRaw,
        )
    log.info("Flyway locations (resolved): ${flywayConfig.locations.joinToString()}")
    val flyway =
        configureFlyway(
            org.flywaydb.core.Flyway.configure().dataSource(url, user, pass),
            flywayConfig,
        )

    if (flywayConfig.effectiveMode != FlywayMode.MIGRATE_AND_VALIDATE) {
        error(
            "Flyway mode is ${flywayConfig.effectiveMode}; set FLYWAY_MODE=migrate-and-validate " +
                "when running the standalone migration tool",
        )
    }

    val result = flyway.migrate()
    flyway.validateWithResult()
    log.info("Migrations applied: ${result.migrationsExecuted}")
}
