package com.example.bot.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UiCheckinMetricsTest {
    private val registry = SimpleMeterRegistry()

    @AfterEach
    fun tearDown() {
        registry.clear()
        registry.close()
    }

    @Test
    fun `counters and timer are recorded`() {
        UiCheckinMetrics.bind(registry)

        UiCheckinMetrics.incTotal()
        UiCheckinMetrics.incError()
        UiCheckinMetrics.timeScan {
            Thread.sleep(5)
        }

        val totalCounter = requireNotNull(registry.find("ui.checkin.scan.total").counter())
        val errorCounter = requireNotNull(registry.find("ui.checkin.scan.error").counter())
        val timer = requireNotNull(registry.find("ui.checkin.scan.duration.ms").timer())

        assertTrue(totalCounter.count() > 0.0)
        assertTrue(errorCounter.count() > 0.0)
        assertTrue(timer.count() > 0)
    }

    @Test
    fun `prometheus naming is sanitized`() {
        val prometheusRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        UiCheckinMetrics.bind(prometheusRegistry)

        UiCheckinMetrics.incTotal()
        UiCheckinMetrics.incError()

        val scrape = prometheusRegistry.scrape()

        assertTrue(scrape.contains("ui_checkin_scan_total"))
        assertTrue(scrape.contains("ui_checkin_scan_error_total"))
        assertTrue(scrape.contains("ui_checkin_scan_duration_ms_seconds_count"))
    }
}
