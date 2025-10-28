package com.example.bot.observability

import com.example.bot.plugins.installMetrics
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MetricsRouteTest {
    @Test
    fun `exposes metrics`() =
        testApplication {
            application {
                installMetrics()
            }
            val res = client.get("/metrics")
            assertEquals(HttpStatusCode.OK, res.status)
            val contentType = res.headers[HttpHeaders.ContentType]
            assertTrue(contentType?.contains("text/plain") == true)
            val body = res.bodyAsText()
            assertTrue(body.contains("http_server_requests"))
        }
}
