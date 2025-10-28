package com.example.bot.data.db

import java.util.concurrent.atomic.AtomicLong

/**
 * Простейшие счётчики. При желании их можно связать с Micrometer.
 */
object DbMetrics {
    val txRetries: AtomicLong = AtomicLong(0)
    val slowQueryCount: AtomicLong = AtomicLong(0)

    // Пример интегратора с Micrometer (по желанию):
    // fun maybeBindMicrometer(registry: io.micrometer.core.instrument.MeterRegistry) { ... }
}
