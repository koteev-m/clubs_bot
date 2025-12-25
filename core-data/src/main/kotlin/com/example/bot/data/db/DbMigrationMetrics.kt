package com.example.bot.data.db

/**
 * Абстракция для метрик миграций Flyway (Micrometer/Prometheus-биндинг находится в app-слое).
 * По умолчанию используется [NoOpDbMigrationMetrics], чтобы core-слой не зависел от конкретного стека метрик.
 */
interface DbMigrationMetrics {
    fun recordValidationSuccess(pendingCount: Int)

    fun recordValidationFailure(pendingCount: Int?)

    fun recordMigrateSuccess(appliedCount: Int)

    fun recordMigrateFailure()
}

object DbMigrationMetricsHolder {
    @Volatile
    var metrics: DbMigrationMetrics = NoOpDbMigrationMetrics
        private set

    fun configure(metrics: DbMigrationMetrics) {
        this.metrics = metrics
    }
}

object NoOpDbMigrationMetrics : DbMigrationMetrics {
    override fun recordValidationSuccess(pendingCount: Int) = Unit

    override fun recordValidationFailure(pendingCount: Int?) = Unit

    override fun recordMigrateSuccess(appliedCount: Int) = Unit

    override fun recordMigrateFailure() = Unit
}
