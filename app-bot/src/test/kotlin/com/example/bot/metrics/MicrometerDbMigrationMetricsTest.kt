package com.example.bot.metrics

import com.example.bot.data.db.DbMigrationMetricsHolder
import com.example.bot.data.db.NoOpDbMigrationMetrics
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

        assertEquals(1.0, migrateCounter?.count())
        assertEquals(0.0, pendingGauge?.value())
    }
}
