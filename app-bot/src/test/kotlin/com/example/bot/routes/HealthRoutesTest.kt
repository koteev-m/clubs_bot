package com.example.bot.routes

import com.example.bot.di.healthModule
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin

class HealthRoutesTest : StringSpec({
    "health and readiness endpoints return status field" {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                install(Koin) { modules(healthModule) }

                healthRoutes(get())
            }

            val health = client.get("/health")
            health.status shouldBe HttpStatusCode.OK
            health.bodyAsText() shouldContain "\"status\""

            val ready = client.get("/ready")
            ready.status shouldBe HttpStatusCode.OK
            ready.bodyAsText() shouldContain "\"status\""
        }
    }
})
