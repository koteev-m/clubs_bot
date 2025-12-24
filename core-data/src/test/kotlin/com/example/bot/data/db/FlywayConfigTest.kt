package com.example.bot.data.db

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
        assertEquals(listOf("classpath:db/migration/postgresql"), cfg.locations)
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
        assertEquals(listOf("classpath:db/migration/h2"), cfg.locations)
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
    fun `root locations inject vendor path first`() {
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
                "classpath:db/migration",
                "classpath:db/migration/common",
            ),
            cfg.locations,
        )
    }
}
