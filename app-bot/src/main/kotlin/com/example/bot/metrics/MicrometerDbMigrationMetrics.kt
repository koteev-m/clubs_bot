package com.example.bot.metrics

import com.example.bot.data.db.DbMigrationMetrics
import com.example.bot.data.db.FlywayConfig
import com.example.bot.data.db.FlywayMode
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import java.util.concurrent.atomic.AtomicInteger

/**
 * Micrometer-биндинг для миграционных метрик:
 * - счетчики `db.migrations.validate.success|failure`, `db.migrations.migrate.success|failure`;
 * - gauge `db.migrations.pending` с последним известным количеством ожидающих миграций.
 *
 * Теги:
 * - `env` — `flywayConfig.appEnv.raw` (prod/stage/dev/local/other);
 * - `mode` — `flywayConfig.effectiveMode` в lowercase (`validate`, `migrate-and-validate`, `off`).
 *
 * `db.migrations.migrate.success` отражает общее число применённых миграций (сумма `migrationsExecuted`).
 */
class MicrometerDbMigrationMetrics(
    registry: MeterRegistry,
    flywayConfig: FlywayConfig = FlywayConfig.fromEnv(),
) : DbMigrationMetrics {
    private val pendingGaugeValue = AtomicInteger(0)
    private val commonTags = listOf(
        Tag.of("env", flywayConfig.appEnv.raw),
        Tag.of("mode", flywayConfig.effectiveMode.toTagValue()),
    )

    private val validationSuccessCounter: Counter =
        Counter
            .builder("db.migrations.validate.success")
            .description("Успешные валидации Flyway")
            .tags(commonTags)
            .register(registry)

    private val validationFailureCounter: Counter =
        Counter
            .builder("db.migrations.validate.failure")
            .description("Неуспешные валидации Flyway")
            .tags(commonTags)
            .register(registry)

    private val migrateSuccessCounter: Counter =
        Counter
            .builder("db.migrations.migrate.success")
            .description("Успешные применения миграций")
            .tags(commonTags)
            .register(registry)

    private val migrateFailureCounter: Counter =
        Counter
            .builder("db.migrations.migrate.failure")
            .description("Неуспешные попытки применения миграций")
            .tags(commonTags)
            .register(registry)

    @Suppress("unused")
    private val _pendingGauge: Gauge =
        Gauge
            .builder("db.migrations.pending", pendingGaugeValue) { it.get().toDouble() }
            .description("Последнее известное количество pending миграций")
            .tags(commonTags)
            .register(registry)

    override fun recordValidationSuccess(pendingCount: Int) {
        pendingGaugeValue.set(pendingCount)
        validationSuccessCounter.increment()
    }

    override fun recordValidationFailure(pendingCount: Int?) {
        pendingCount?.let { pendingGaugeValue.set(it) }
        validationFailureCounter.increment()
    }

    override fun recordMigrateSuccess(appliedCount: Int) {
        if (appliedCount > 0) {
            migrateSuccessCounter.increment(appliedCount.toDouble())
        }
    }

    override fun recordMigrateFailure() {
        migrateFailureCounter.increment()
    }
}

private fun FlywayMode.toTagValue(): String =
    when (this) {
        FlywayMode.MIGRATE_AND_VALIDATE -> "migrate-and-validate"
        else -> name.lowercase()
    }
