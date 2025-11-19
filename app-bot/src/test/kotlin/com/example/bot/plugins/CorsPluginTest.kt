package com.example.bot.plugins

import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.application
import io.ktor.server.response.respondText
import io.ktor.server.routing.options
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CorsPluginTest {
    @Test
    fun `prod profile rejects empty whitelist`() {
        assertFailsWith<IllegalStateException> {
            testApplication {
                application {
                    installCorsFromEnv { key ->
                        when (key) {
                            "APP_PROFILE" -> "prod"
                            else -> null
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `dev profile allows empty whitelist`() {
        testApplication {
            application {
                installCorsFromEnv { null }
                routing {
                    options("/ping") {
                        call.respondText("ok")
                    }
                }
            }

            val response = client.options("/ping") {
                header(HttpHeaders.Origin, "https://example.test")
                header(HttpHeaders.AccessControlRequestMethod, HttpMethod.Post.value)
            }
            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `stage profile respects explicit origins and exposes cors headers`() {
        testApplication {
            application {
                installCorsFromEnv { key ->
                    when (key) {
                        "APP_PROFILE" -> "stage"
                        "CORS_ALLOWED_ORIGINS" -> "https://t.me,https://web.telegram.org"
                        else -> null
                    }
                }
                routing {
                    options("/check") {
                        call.respondText("ok")
                    }
                }
            }

            val response = client.options("/check") {
                header(HttpHeaders.Origin, "https://t.me")
                header(HttpHeaders.AccessControlRequestMethod, HttpMethod.Post.value)
                header(
                    HttpHeaders.AccessControlRequestHeaders,
                    "X-Telegram-Init-Data, Content-Type"
                )
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("https://t.me", response.headers[HttpHeaders.AccessControlAllowOrigin])
            assertEquals("true", response.headers[HttpHeaders.AccessControlAllowCredentials])
            assertEquals("600", response.headers[HttpHeaders.AccessControlMaxAge])

            val allowHeaders = response.headers[HttpHeaders.AccessControlAllowHeaders]
            assertNotNull(allowHeaders)
            val normalized = allowHeaders.split(',').map { it.trim() }.toSet()
            assertTrue(normalized.contains("X-Telegram-Init-Data"))
            assertTrue(normalized.contains(HttpHeaders.ContentType))
            assertTrue(normalized.contains(HttpHeaders.Authorization))
            assertTrue(normalized.contains("X-Telegram-InitData"))
            assertTrue(response.headers[HttpHeaders.Vary]?.contains("Origin") == true)
        }
    }

    @Test
    fun `stage profile denies origins outside whitelist`() {
        testApplication {
            application {
                installCorsFromEnv { key ->
                    when (key) {
                        "APP_PROFILE" -> "stage"
                        "CORS_ALLOWED_ORIGINS" -> "https://t.me"
                        else -> null
                    }
                }
                routing {
                    options("/check") {
                        call.respondText("ok")
                    }
                }
            }

            val response = client.options("/check") {
                header(HttpHeaders.Origin, "https://malicious.example")
                header(HttpHeaders.AccessControlRequestMethod, HttpMethod.Post.value)
            }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
            assertNull(response.headers[HttpHeaders.AccessControlAllowCredentials])
        }
    }

    @Test
    fun `stage profile respects preflight max age override`() {
        testApplication {
            application {
                installCorsFromEnv { key ->
                    when (key) {
                        "APP_PROFILE" -> "stage"
                        "CORS_ALLOWED_ORIGINS" -> "https://t.me"
                        "CORS_PREFLIGHT_MAX_AGE_SECONDS" -> "900"
                        else -> null
                    }
                }
                routing {
                    options("/check") { call.respondText("ok") }
                }
            }

            val response = client.options("/check") {
                header(HttpHeaders.Origin, "https://t.me")
                header(HttpHeaders.AccessControlRequestMethod, HttpMethod.Post.value)
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("900", response.headers[HttpHeaders.AccessControlMaxAge])
        }
    }

    @Test
    fun `post requests from non whitelisted origins lack allow origin header`() {
        testApplication {
            application {
                installCorsFromEnv { key ->
                    when (key) {
                        "APP_PROFILE" -> "stage"
                        "CORS_ALLOWED_ORIGINS" -> "https://t.me"
                        else -> null
                    }
                }
                routing {
                    post("/check") { call.respondText("ok") }
                }
            }

            val response = client.post("/check") {
                header(HttpHeaders.Origin, "https://malicious.example")
            }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
        }
    }
}
