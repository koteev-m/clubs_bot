package com.example.bot.plugins

import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.post
import io.ktor.server.application.application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class CspPluginTest {
    @Test
    fun `csp disabled by default`() {
        testApplication {
            application {
                installWebAppCspFromEnv { null } // CSP_ENABLED не задан -> false
                routing {
                    get("/webapp/entry/app.js") { call.respondText("// ok") }
                    get("/api/ping") { call.respondText("pong") }
                }
            }
            val a = client.get("/webapp/entry/app.js")
            val b = client.get("/api/ping")
            assertNull(a.headers["Content-Security-Policy"])
            assertNull(a.headers["Content-Security-Policy-Report-Only"])
            assertNull(b.headers["Content-Security-Policy"])
            assertNull(b.headers["Content-Security-Policy-Report-Only"])
        }
    }

    @Test
    fun `csp report-only on webapp only`() {
        testApplication {
            application {
                installWebAppCspFromEnv { key ->
                    when (key) {
                        "CSP_ENABLED" -> "true"
                        "CSP_REPORT_ONLY" -> "true"
                        else -> null
                    }
                }
                routing {
                    get("/webapp/entry/app.js") { call.respondText("// ok") }
                    get("/webapp/entry") { call.respondText("<!doctype html>") }
                    post("/webapp/entry/app.js") { call.respondText("// ok") }
                    get("/api/ping") { call.respondText("pong") }
                }
            }
            val a = client.get("/webapp/entry/app.js")
            val root = client.get("/webapp/entry")
            val post = client.post("/webapp/entry/app.js")
            val b = client.get("/api/ping")
            assertNotNull(a.headers["Content-Security-Policy-Report-Only"])
            assertNotNull(root.headers["Content-Security-Policy-Report-Only"])
            assertNull(a.headers["Content-Security-Policy"])
            assertNull(post.headers["Content-Security-Policy-Report-Only"])
            assertNull(b.headers["Content-Security-Policy-Report-Only"])
        }
    }

    @Test
    fun `csp enforce when report-only disabled`() {
        testApplication {
            application {
                installWebAppCspFromEnv { key ->
                    when (key) {
                        "CSP_ENABLED" -> "true"
                        "CSP_REPORT_ONLY" -> "false"
                        "CSP_VALUE" -> "default-src 'self'; script-src 'self';"
                        else -> null
                    }
                }
                routing {
                    get("/webapp/entry/index.html") { call.respondText("<!doctype html>") }
                }
            }
            val r = client.get("/webapp/entry/index.html")
            assertEquals("default-src 'self'; script-src 'self';", r.headers["Content-Security-Policy"])
        }
    }

    @Test
    fun `head request gets csp with trailing slash prefix`() {
        testApplication {
            application {
                installWebAppCspFromEnv { key ->
                    when (key) {
                        "CSP_ENABLED" -> "true"
                        "CSP_REPORT_ONLY" -> "true"
                        "WEBAPP_CSP_PATH_PREFIX" -> "/webapp/entry/"
                        else -> null
                    }
                }
                routing {
                    head("/webapp/entry/app.js") { call.respondText("") }
                }
            }
            val response = client.head("/webapp/entry/app.js")
            assertNotNull(response.headers["Content-Security-Policy-Report-Only"])
        }
    }
}
