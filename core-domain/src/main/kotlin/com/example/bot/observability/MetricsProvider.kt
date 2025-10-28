package com.example.bot.observability

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import java.util.function.Supplier

/**
 * Helper that supplies a [MeterRegistry] and convenience factory methods for metrics.
 */
class MetricsProvider(val registry: MeterRegistry) {
    companion object {
        fun prometheusRegistry(): PrometheusMeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

        fun simpleRegistry(): SimpleMeterRegistry = SimpleMeterRegistry()
    }

    fun counter(
        name: String,
        vararg tags: String,
    ): Counter = Counter.builder(name).tags(*tags).register(registry)

    fun timer(
        name: String,
        vararg tags: String,
    ): Timer = Timer.builder(name).tags(*tags).register(registry)

    fun gauge(
        name: String,
        supplier: Supplier<Number>,
        vararg tags: String,
    ): Gauge = Gauge.builder(name, supplier).tags(*tags).register(registry)

    fun registerBuildInfo(
        version: String,
        env: String,
        commit: String,
    ) {
        Gauge
            .builder("app.build.info") { 1.0 }
            .tags("version", version, "env", env, "commit", commit)
            .register(registry)
    }
}
