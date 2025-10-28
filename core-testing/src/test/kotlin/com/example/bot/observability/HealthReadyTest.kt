package com.example.bot.observability

import com.example.bot.plugins.MigrationState
import com.example.bot.routes.healthRoute
import com.example.bot.routes.readinessRoute
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HealthReadyTest {
    @Test
    fun `health and readiness`() =
        testApplication {
            MigrationState.migrationsApplied = false
            application {
                routing {
                    healthRoute()
                    readinessRoute()
                }
            }
            val h1 = client.get("/health")
            assertEquals(HttpStatusCode.ServiceUnavailable, h1.status)
            val r1 = client.get("/ready")
            assertEquals(HttpStatusCode.ServiceUnavailable, r1.status)
            MigrationState.migrationsApplied = true
            val r2 = client.get("/ready")
            assertEquals(HttpStatusCode.OK, r2.status)
        }
}
