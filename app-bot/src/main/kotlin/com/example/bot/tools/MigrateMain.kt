package com.example.bot.tools

import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory

fun main() {
    val log = LoggerFactory.getLogger("Migrations")

    val url = System.getenv("DATABASE_URL") ?: error("DATABASE_URL is not set")
    val user = System.getenv("DATABASE_USER") ?: error("DATABASE_USER is not set")
    val pass = System.getenv("DATABASE_PASSWORD") ?: error("DATABASE_PASSWORD is not set")

    // Берём список локаций из ENV и РАЗБИВАЕМ по запятой — это важно!
    val locationsRaw =
        System.getenv("FLYWAY_LOCATIONS")
            ?: "classpath:db/migration/postgresql,classpath:db/migration/common"

    val locations =
        locationsRaw
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toTypedArray()

    log.info("Flyway locations (resolved): ${locations.joinToString()}")

    // Важно: здесь не нужен Hikari — Flyway сам создаст DataSource по URL/USER/PASS
    val flyway =
        Flyway
            .configure()
            .dataSource(url, user, pass)
            .locations(*locations) // <— vararg, а не одна строка
            .baselineOnMigrate(true) // на случай "непустой" схемы без истории миграций
            .load()

    val result = flyway.migrate()
    log.info("Migrations applied: ${result.migrationsExecuted}")
}
