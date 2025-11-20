package com.example.bot.plugins

import io.ktor.client.request.get
import io.ktor.server.application.application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SecurityHeadersPluginTest {
    @Test
    fun `dev profile sets base headers without hsts`() {
        testApplication {
            application {
                // envProvider возвращает null -> профиль по умолчанию DEV
                installHttpSecurityFromEnv { null }
                routing {
                    get("/ping") { call.respondText("pong") }
                }
            }

            val response = client.get("/ping")
            // Базовые заголовки всегда есть
            assertEquals("nosniff", response.headers["X-Content-Type-Options"])
            assertEquals("no-referrer", response.headers["Referrer-Policy"])
            assertEquals("camera=(), microphone=(), geolocation=()", response.headers["Permissions-Policy"])
            // HSTS в DEV отсутствует
            assertNull(response.headers["Strict-Transport-Security"])
        }
    }

    @Test
    fun `stage profile adds hsts with correct value`() {
        testApplication {
            application {
                installHttpSecurityFromEnv { key ->
                    when (key) {
                        "APP_PROFILE" -> "stage"
                        else -> null
                    }
                }
                routing {
                    get("/ping") { call.respondText("pong") }
                }
            }

            val response = client.get("/ping")
            assertEquals("nosniff", response.headers["X-Content-Type-Options"])
            assertEquals("no-referrer", response.headers["Referrer-Policy"])
            assertEquals("camera=(), microphone=(), geolocation=()", response.headers["Permissions-Policy"])
            val hsts = response.headers["Strict-Transport-Security"]
            assertNotNull(hsts)
            assertEquals("max-age=31536000; includeSubDomains", hsts)
        }
    }

    @Test
    fun `prod profile adds hsts with correct value`() {
        testApplication {
            application {
                installHttpSecurityFromEnv { key ->
                    when (key) {
                        "APP_PROFILE" -> "prod"
                        else -> null
                    }
                }
                routing {
                    get("/ping") { call.respondText("pong") }
                }
            }

            val response = client.get("/ping")
            assertEquals("nosniff", response.headers["X-Content-Type-Options"])
            assertEquals("no-referrer", response.headers["Referrer-Policy"])
            assertEquals("camera=(), microphone=(), geolocation=()", response.headers["Permissions-Policy"])
            val hsts = response.headers["Strict-Transport-Security"]
            assertNotNull(hsts)
            assertEquals("max-age=31536000; includeSubDomains", hsts)
        }
    }
}
