package com.example.ktor

import com.example.bot.plugins.installMetrics
import com.example.bot.routes.healthRoute
import com.example.bot.routes.readinessRoute
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RoutesSmokeTest {
    @Test
    fun `health and metrics exposed`() =
        testApplication {
            application {
                installMetrics()
                routing {
                    healthRoute()
                    readinessRoute()
                }
            }
            val health = client.get("/health")
            assertEquals(HttpStatusCode.ServiceUnavailable, health.status)
            val metrics = client.get("/metrics")
            assertEquals(HttpStatusCode.OK, metrics.status)
            val ctype = metrics.headers[HttpHeaders.ContentType]
            assertTrue(ctype?.contains("text/plain") == true)
            val body = metrics.bodyAsText()
            assertTrue(body.contains("http_server_requests"))
        }
}
