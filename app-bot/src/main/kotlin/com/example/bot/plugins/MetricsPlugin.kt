package com.example.bot.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import java.util.concurrent.atomic.AtomicReference

private val prometheusRegistryRef =
    AtomicReference(createPrometheusRegistry())

private fun createPrometheusRegistry(): PrometheusMeterRegistry {
    return PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
}

fun meterRegistry(): MeterRegistry = prometheusRegistryRef.get()

fun Application.installMetrics() {
    val newRegistry = createPrometheusRegistry()
    prometheusRegistryRef.set(newRegistry)
    install(MicrometerMetrics) {
        registry = newRegistry
        timers { _, _ ->
            io.micrometer.core.instrument.Timer
                .builder("http.server.requests")
        }
    }
    routing {
        metricsRoute(newRegistry)
    }
}

fun Route.metricsRoute(registry: PrometheusMeterRegistry = prometheusRegistryRef.get()) {
    get("/metrics") {
        val scrape = registry.scrape()
        call.respondText(
            text = scrape,
            contentType =
                io.ktor.http.ContentType
                    .parse("text/plain; version=0.0.4; charset=utf-8"),
        )
    }
}
