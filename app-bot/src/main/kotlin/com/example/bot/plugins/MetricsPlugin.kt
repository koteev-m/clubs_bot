package com.example.bot.plugins

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.micrometer.core.instrument.Metrics
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import org.slf4j.LoggerFactory

/**
 * Поднимает PrometheusMeterRegistry и эндпоинт /metrics (текстовый формат Prometheus 0.0.4).
 */
fun Application.installMetrics() {
    val log = LoggerFactory.getLogger("Metrics")

    val promRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    Metrics.addRegistry(promRegistry)
    log.info("PrometheusMeterRegistry registered")

    try {
        install(MicrometerMetrics) {
            registry = Metrics.globalRegistry
        }
        log.info("MicrometerMetrics plugin installed")
    } catch (t: Throwable) {
        log.warn("MicrometerMetrics already installed? ${t.message}")
    }

    routing {
        get("/metrics") {
            val body = promRegistry.scrape()
            call.respondText(
                body,
                ContentType.parse("text/plain; version=0.0.4; charset=utf-8")
            )
        }
    }
}
