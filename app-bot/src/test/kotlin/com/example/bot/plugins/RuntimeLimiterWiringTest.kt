package com.example.bot.plugins

import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeLimiterWiringTest {
    @Test
    fun `rate limiter returns 429 for webhook when limit is exceeded`() =
        testApplication {
            environment {
                config =
                    MapApplicationConfig(
                        "app.env.RL_IP_RPS" to "0.1",
                        "app.env.RL_IP_BURST" to "1",
                        "app.env.RL_SUBJECT_ENABLED" to "false",
                    )
            }
            application {
                installRateLimitPluginDefaults()
                routing {
                    post("/telegram/webhook") { call.respondText("ok") }
                }
            }

            val first = client.post("/telegram/webhook")
            val second = client.post("/telegram/webhook")
            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals(HttpStatusCode.TooManyRequests, second.status)
        }
}
