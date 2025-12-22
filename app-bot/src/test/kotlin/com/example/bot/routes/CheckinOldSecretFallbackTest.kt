package com.example.bot.routes

import com.example.bot.metrics.QrRotationConfig
import com.example.bot.metrics.UiCheckinMetrics
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CheckinOldSecretFallbackTest {
    private lateinit var registry: PrometheusMeterRegistry

    @BeforeEach
    fun setUp() {
        registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    }

    @AfterEach
    fun tearDown() {
        registry.close()
    }

    @Test
    fun `fallback counter and rotation gauges are exported to metrics endpoint`() = testApplication {
        UiCheckinMetrics.bind(
            registry = registry,
            rotationConfig =
                QrRotationConfig(
                    oldSecret = "legacy",
                    rotationDeadlineEpochSeconds = 1_725_000_000L,
                ),
        )
        application {
            metricsRoute(registry)
        }

        UiCheckinMetrics.incOldSecretFallback()

        val body = client.get("/metrics").bodyAsText()

        assertTrue(body.contains("ui_checkin_old_secret_fallback_total"))
        assertTrue(body.contains("ui_checkin_rotation_active 1.0"))
        assertTrue(body.contains("ui_checkin_rotation_deadline_seconds"))

        val fallbackCount = registry.find("ui_checkin_old_secret_fallback_total").counter()?.count()
        assertEquals(1.0, fallbackCount)
    }

    @Test
    fun `rotation gauges are absent when old secret is not configured`() = testApplication {
        UiCheckinMetrics.bind(
            registry = registry,
            rotationConfig =
                QrRotationConfig(
                    oldSecret = null,
                    rotationDeadlineEpochSeconds = null,
                ),
        )
        application {
            metricsRoute(registry)
        }

        val body = client.get("/metrics").bodyAsText()

        assertTrue(body.contains("ui_checkin_rotation_active 0.0"))
        assertFalse(body.contains("ui_checkin_rotation_deadline_seconds"))
    }
}

private fun Application.metricsRoute(registry: PrometheusMeterRegistry) {
    routing {
        get("/metrics") {
            val body = registry.scrape()
            call.respondText(
                body,
                ContentType.parse("text/plain; version=0.0.4; charset=utf-8"),
            )
        }
    }
}
