package com.example.bot.metrics

import com.zaxxer.hikari.HikariDataSource
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import org.slf4j.LoggerFactory

/**
 * Micrometer binder для метрик пула Hikari.
 *
 * Регистрирует основные gauges по состоянию пула, чтобы Prometheus видел их через /metrics.
 */
class HikariMetrics(
    private val dataSource: HikariDataSource,
    private val poolName: String = dataSource.poolName ?: "default",
) : MeterBinder {
    private val log = LoggerFactory.getLogger(HikariMetrics::class.java)

    override fun bindTo(registry: MeterRegistry) {
        val mxBean =
            runCatching { dataSource.hikariPoolMXBean }
                .onFailure { log.warn("Failed to access HikariPoolMXBean for pool {}: {}", poolName, it.message) }
                .getOrNull()
                ?: run {
                    log.warn("HikariPoolMXBean is not available for pool {}", poolName)
                    return
                }

        registerGauge(registry, "db_pool_active_connections", "Active connections in the pool") {
            mxBean.activeConnections.toDouble()
        }
        registerGauge(registry, "db_pool_idle_connections", "Idle connections in the pool") {
            mxBean.idleConnections.toDouble()
        }
        registerGauge(registry, "db_pool_pending_connections", "Pending connections waiting for checkout") {
            mxBean.threadsAwaitingConnection.toDouble()
        }
        registerGauge(registry, "db_pool_max_connections", "Maximum connections configured for the pool") {
            runCatching { dataSource.hikariConfigMXBean.maximumPoolSize.toDouble() }
                .getOrDefault(mxBean.totalConnections.toDouble())
        }
        registerGauge(
            registry,
            "db_pool_min_idle_connections",
            "Minimum idle connections configured for the pool",
        ) {
            dataSource.minimumIdle.toDouble()
        }
    }

    private fun registerGauge(
        registry: MeterRegistry,
        name: String,
        description: String,
        supplier: () -> Double,
    ) {
        val existing = registry.find(name).tag("pool", poolName).gauge()
        if (existing != null) return

        Gauge
            .builder(name) {
                runCatching { supplier() }.getOrElse { ex ->
                    log.warn("Failed to sample Hikari metric {} for pool {}: {}", name, poolName, ex.message)
                    Double.NaN
                }
            }
            .description(description)
            .tag("pool", poolName)
            .strongReference(true)
            .register(registry)
    }
}
