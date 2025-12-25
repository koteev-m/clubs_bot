package com.example.bot.metrics

import com.example.bot.data.db.DbMigrationMetricsHolder
import com.example.bot.data.db.NoOpDbMigrationMetrics
import com.example.bot.data.db.AppEnvironment
import com.example.bot.data.db.FlywayConfig
import com.example.bot.data.db.FlywayMode
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MicrometerDbMigrationMetricsTest {
    @AfterEach
    fun tearDown() {
        DbMigrationMetricsHolder.configure(NoOpDbMigrationMetrics)
    }

    @Test
    fun `records migrate success counter`() {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerDbMigrationMetrics(registry)
        DbMigrationMetricsHolder.configure(metrics)

        DbMigrationMetricsHolder.metrics.recordMigrateSuccess(2)
        DbMigrationMetricsHolder.metrics.recordValidationSuccess(0)

        val migrateCounter = registry.find("db.migrations.migrate.success").counter()
        val pendingGauge = registry.find("db.migrations.pending").gauge()

        assertEquals(2.0, migrateCounter?.count())
        assertEquals(0.0, pendingGauge?.value())
    }

    @Test
    fun `uses normalized mode tag`() {
        val registry = SimpleMeterRegistry()
        val flywayConfig =
            FlywayConfig(
                mode = FlywayMode.MIGRATE_AND_VALIDATE,
                appEnv = AppEnvironment.LOCAL,
            )
        val metrics = MicrometerDbMigrationMetrics(registry, flywayConfig)

        metrics.recordValidationSuccess(0)

        val migrateCounter =
            registry
                .find("db.migrations.validate.success")
                .tags("mode", "migrate-and-validate")
                .counter()

        assertEquals(1.0, migrateCounter?.count())
    }
}
