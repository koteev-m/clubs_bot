package com.example.bot.data.db

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class FlywayConfigTest {
    @Test
    fun `prod defaults validate and forbid out of order`() {
        val env =
            mapOf(
                "APP_ENV" to "prod",
                "DATABASE_URL" to "jdbc:postgresql://localhost:5432/postgres",
                "FLYWAY_OUT_OF_ORDER" to "true",
            )

        val cfg = FlywayConfig.fromEnv(envProvider = { env[it] }, propertyProvider = { null })

        assertEquals(FlywayMode.VALIDATE, cfg.mode)
        assertEquals(FlywayMode.VALIDATE, cfg.effectiveMode)
        assertFalse(cfg.outOfOrderEnabled)
        assertEquals(
            listOf(
                "classpath:db/migration/common",
                "classpath:db/migration/postgresql",
            ),
            cfg.locations,
        )
    }

    @Test
    fun `dev defaults migrate and allow out of order`() {
        val env =
            mapOf(
                "APP_ENV" to "dev",
                "DATABASE_URL" to "jdbc:h2:mem:test",
                "FLYWAY_OUT_OF_ORDER" to "true",
            )

        val cfg = FlywayConfig.fromEnv(envProvider = { env[it] }, propertyProvider = { null })

        assertEquals(FlywayMode.MIGRATE_AND_VALIDATE, cfg.mode)
        assertEquals(FlywayMode.MIGRATE_AND_VALIDATE, cfg.effectiveMode)
        assertTrue(cfg.outOfOrderEnabled)
        assertEquals(
            listOf(
                "classpath:db/migration/common",
                "classpath:db/migration/h2",
            ),
            cfg.locations,
        )
    }

    @Test
    fun `legacy validate flag overrides mode`() {
        val env =
            mapOf(
                "APP_ENV" to "dev",
                "DATABASE_URL" to "jdbc:postgresql://localhost:5432/postgres",
                "FLYWAY_MODE" to "migrate-and-validate",
                "FLYWAY_VALIDATE_ONLY" to "true",
            )

        val cfg = FlywayConfig.fromEnv(envProvider = { env[it] }, propertyProvider = { null })

        assertEquals(FlywayMode.VALIDATE, cfg.mode)
        assertEquals(FlywayMode.VALIDATE, cfg.effectiveMode)
    }

    @Test
    fun `root locations are normalized to vendor path and append common`() {
        val env =
            mapOf(
                "APP_ENV" to "local",
                "DATABASE_URL" to "jdbc:h2:mem:test",
                "FLYWAY_LOCATIONS" to "classpath:db/migration,classpath:db/migration/common",
            )

        val cfg = FlywayConfig.fromEnv(envProvider = { env[it] }, propertyProvider = { null })

        assertEquals(
            listOf(
                "classpath:db/migration/h2",
                "classpath:db/migration/common",
            ),
            cfg.locations,
        )
    }

    @Test
    fun `vendor-only locations still append common for parity`() {
        val env =
            mapOf(
                "APP_ENV" to "local",
                "DATABASE_URL" to "jdbc:postgresql://localhost:5432/postgres",
                "FLYWAY_LOCATIONS" to "classpath:db/migration/postgresql",
            )

        val cfg = FlywayConfig.fromEnv(envProvider = { env[it] }, propertyProvider = { null })

        assertEquals(
            listOf(
                "classpath:db/migration/postgresql",
                "classpath:db/migration/common",
            ),
            cfg.locations,
        )
    }

    @Test
    fun `execution uses common plus vendor migrations for default h2 resolution`() {
        val cfg =
            FlywayConfig.fromEnv(
                envProvider = { key -> if (key == "DATABASE_URL") "jdbc:h2:mem:test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1" else null },
                propertyProvider = { null },
            )

        val applied = migrateAndCollectApplied(cfg.locations)

        assertTrue(applied.any { it.script == "V6__telegram_notify_endpoints.sql" })
        assertTrue(applied.any { it.script == "V1__init.sql" })
    }

    @Test
    fun `execution tolerates root override with explicit common without duplicate migrations`() {
        val cfg =
            FlywayConfig.fromEnv(
                envProvider = { key -> if (key == "DATABASE_URL") "jdbc:h2:mem:test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1" else null },
                propertyProvider = { null },
                locationsOverride = "classpath:db/migration,classpath:db/migration/common",
            )

        val applied = migrateAndCollectApplied(cfg.locations)

        assertTrue(applied.any { it.script == "V6__telegram_notify_endpoints.sql" })
        assertTrue(applied.any { it.script == "V1__init.sql" })
    }

    @Test
    fun `execution tolerates vendor-only override and still executes common migrations`() {
        val cfg =
            FlywayConfig.fromEnv(
                envProvider = { key -> if (key == "DATABASE_URL") "jdbc:h2:mem:test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1" else null },
                propertyProvider = { null },
                locationsOverride = "classpath:db/migration/h2",
            )

        val applied = migrateAndCollectApplied(cfg.locations)

        assertTrue(applied.any { it.script == "V6__telegram_notify_endpoints.sql" })
        assertTrue(applied.any { it.script == "V1__init.sql" })
    }

    private fun migrateAndCollectApplied(locations: List<String>): List<MigrationInfo> {
        val dbName = "flyway-config-test-${UUID.randomUUID()}"
        val jdbcUrl = "jdbc:h2:mem:$dbName;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"
        val flyway =
            Flyway
                .configure()
                .dataSource(jdbcUrl, "sa", "")
                .locations(*locations.toTypedArray())
                .load()

        val migrateResult = flyway.migrate()
        val infoAfterMigrate = flyway.info().all().toList()
        val validateResult = flyway.validateWithResult()

        assertTrue(validateResult.validationSuccessful)
        assertTrue(migrateResult.migrationsExecuted > 0)

        return infoAfterMigrate.filter { it.state.isApplied }
    }
}
