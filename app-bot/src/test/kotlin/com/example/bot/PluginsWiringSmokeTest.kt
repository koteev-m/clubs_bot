package com.example.bot

import com.example.bot.plugins.installHotPathLimiterDefaults
import com.example.bot.plugins.installRateLimitPluginDefaults
import com.example.bot.plugins.resolveFlag
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication

class PluginsWiringSmokeTest :
    StringSpec({
        "default environment returns 200 for ping" {
            testApplication {
                application {
                    installRateLimitPluginDefaults()
                    installHotPathLimiterDefaults()
                    routing {
                        get("/ping") { call.respondText("OK") }
                    }
                }

                val response = client.get("/ping")
                response.status shouldBe HttpStatusCode.OK
            }
        }

        "hot path limiter allows import route when enabled" {
            testApplication {
                environment {
                    config = MapApplicationConfig("app.flags.HOT_PATH_ENABLED" to "true")
                }
                application {
                    if (resolveFlag("HOT_PATH_ENABLED", default = true)) {
                        installHotPathLimiterDefaults()
                    }
                    routing {
                        post("/api/guest-lists/import") { call.respond(HttpStatusCode.Accepted) }
                    }
                }

                val response: HttpResponse = client.post("/api/guest-lists/import")
                response.status.shouldBe(HttpStatusCode.Accepted)
            }
        }

        "rate limit plugin does not reject quick sequential requests by default" {
            testApplication {
                environment {
                    config = MapApplicationConfig("app.flags.RATE_LIMIT_ENABLED" to "true")
                }
                application {
                    if (resolveFlag("RATE_LIMIT_ENABLED", default = true)) {
                        installRateLimitPluginDefaults()
                    }
                    routing {
                        get("/ping") { call.respondText("OK") }
                    }
                }

                val first = client.get("/ping")
                val second = client.get("/ping")
                first.status shouldBe HttpStatusCode.OK
                second.status shouldBe HttpStatusCode.OK
            }
        }
    })
