package com.example.bot.plugins

import com.example.bot.data.db.DbMetricsHolder
import com.example.bot.data.db.DbMigrationMetricsHolder
import com.example.bot.metrics.HikariMetrics
import com.example.bot.metrics.MicrometerDbMetrics
import com.example.bot.metrics.MicrometerDbMigrationMetrics
import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
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

    runCatching {
        DbMetricsHolder.configure(MicrometerDbMetrics(Metrics.globalRegistry))
        log.info("DB metrics bound to Micrometer")
    }.onFailure {
        log.warn("Failed to bind DB metrics: {}", it.message)
    }

    runCatching {
        DbMigrationMetricsHolder.configure(MicrometerDbMigrationMetrics(Metrics.globalRegistry))
        log.info("DB migration metrics bound to Micrometer")
    }.onFailure {
        log.warn("Failed to bind DB migration metrics: {}", it.message)
    }

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
                ContentType.parse("text/plain; version=0.0.4; charset=utf-8"),
            )
        }
    }

    fun bindHikariMetricsIfPresent() {
        val ds = DataSourceHolder.dataSource
        val hikari = ds as? HikariDataSource ?: return
        runCatching {
            HikariMetrics(hikari).bindTo(Metrics.globalRegistry)
        }.onSuccess {
            log.info("Hikari metrics registered for pool {}", hikari.poolName)
        }.onFailure {
            log.warn("Failed to register Hikari metrics: {}", it.message)
        }
    }

    // Пробуем сразу (для тестов с заранее инициализированным DataSource) и повторяем при старте.
    bindHikariMetricsIfPresent()
    environment.monitor.subscribe(ApplicationStarted) {
        bindHikariMetricsIfPresent()
    }
}
