package com.example.bot.metrics

import com.example.bot.plugins.RateLimitMetrics
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry

/**
 * Разовая привязка простых Atomic-метрик к Micrometer registry.
 * Вызывать один раз после установки MicrometerMetrics.
 */
object AppMetricsBinder {
    fun bindAll(registry: MeterRegistry) {
        UiBookingMetrics.bind(registry)
        UiCheckinMetrics.bind(registry)
        RateLimitMicrometerMetrics.bind(registry)
    }
}

private object RateLimitMicrometerMetrics {
    @Volatile
    private var bound: Boolean = false

    fun bind(registry: MeterRegistry) {
        if (bound) return

        registerGauge(registry, "ratelimit.ip.blocked", RateLimitMetrics.ipBlocked)
        registerGauge(registry, "ratelimit.subject.blocked", RateLimitMetrics.subjectBlocked)
        registerGauge(registry, "ratelimit.subject.store.size", RateLimitMetrics.subjectStoreSize)
        bound = true
    }

    private fun registerGauge(
        registry: MeterRegistry,
        name: String,
        counter: java.util.concurrent.atomic.AtomicLong,
    ) {
        val existing = registry.find(name).gauge()
        if (existing != null) return
        Gauge.builder(name) { counter.get().toDouble() }
            .strongReference(true)
            .register(registry)
    }
}
