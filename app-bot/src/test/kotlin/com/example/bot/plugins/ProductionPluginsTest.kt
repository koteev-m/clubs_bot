package com.example.bot.plugins

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.pluginOrNull
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

class ProductionPluginsTest :
    StringSpec({
        "rate limit plugin can be disabled via flag" {
            testApplication {
                environment {
                    config = MapApplicationConfig("app.flags.RATE_LIMIT_ENABLED" to "false")
                }
                application {
                    if (resolveFlag("RATE_LIMIT_ENABLED", default = true)) {
                        installRateLimitPluginDefaults()
                    }
                    pluginOrNull(RateLimitPlugin).shouldBeNull()
                }
            }
        }

        "rate limit plugin blocks second request and records request id" {
            RateLimitMetrics.ipBlocked.set(0)
            RateLimitMetrics.subjectBlocked.set(0)
            RateLimitMetrics.subjectStoreSize.set(0)
            RateLimitMetrics.lastBlockedRequestId.set(null)

            testApplication {
                environment {
                    config =
                        MapApplicationConfig(
                            "app.flags.RATE_LIMIT_ENABLED" to "true",
                            "app.env.RL_IP_RPS" to "1",
                            "app.env.RL_IP_BURST" to "1",
                            "app.env.RL_RETRY_AFTER_SECONDS" to "5",
                        )
                }
                application {
                    installRateLimitPluginDefaults()
                    routing {
                        get("/ping") { call.respondText("OK") }
                    }
                }

                val first =
                    client.get("/ping")
                first.status shouldBe HttpStatusCode.OK

                val second =
                    client.get("/ping") {
                        header(HttpHeaders.XRequestId, "req-123")
                    }

                second.status shouldBe HttpStatusCode.TooManyRequests
                second.headers[HttpHeaders.RetryAfter] shouldBe "5"
                RateLimitMetrics.ipBlocked.get() shouldBe 1
                RateLimitMetrics.lastBlockedRequestId.get() shouldBe "req-123"
            }
        }

        "hot path limiter can be disabled via flag" {
            testApplication {
                environment {
                    config = MapApplicationConfig("app.flags.HOT_PATH_ENABLED" to "false")
                }
                application {
                    if (resolveFlag("HOT_PATH_ENABLED", default = true)) {
                        installHotPathLimiterDefaults()
                    }
                    pluginOrNull(HotPathLimiter).shouldBeNull()
                }
            }
        }

        "hot path limiter throttles concurrent calls" {
            HotPathMetrics.throttled.set(0)
            HotPathMetrics.active.set(0)
            HotPathMetrics.availablePermits.set(0)

            testApplication {
                environment {
                    config =
                        MapApplicationConfig(
                            "app.flags.HOT_PATH_ENABLED" to "true",
                            "app.env.HOT_PATH_MAX_CONCURRENT" to "1",
                            "app.env.HOT_PATH_RETRY_AFTER_SEC" to "7",
                        )
                }
                application {
                    installHotPathLimiterDefaults()
                    routing {
                        get("/webhook/test") {
                            delay(200)
                            call.respondText("OK")
                        }
                    }
                }

                val httpClient = client
                withTimeout(5_000) {
                    coroutineScope {
                        val first = async { httpClient.get("/webhook/test") }
                        delay(50)
                        val second = httpClient.get("/webhook/test")

                        val firstResponse = first.await()
                        firstResponse.status shouldBe HttpStatusCode.OK
                        firstResponse.bodyAsText() shouldBe "OK"

                        second.status shouldBe HttpStatusCode.TooManyRequests
                        second.headers[HttpHeaders.RetryAfter] shouldBe "7"
                        HotPathMetrics.throttled.get() shouldBe 1
                    }
                }
            }
        }
    })
