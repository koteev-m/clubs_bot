package com.example.bot.plugins

import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.server.application.application
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WebAppStaticCachePluginTest {
    @Test
    fun `immutable cache is set for hashed assets`() {
        testApplication {
            application {
                installWebAppImmutableCacheFromEnv { key ->
                    when (key) {
                        "WEBAPP_ENTRY_CACHE_SECONDS" -> "900"
                        else -> null
                    }
                }
                routing {
                    get("/webapp/entry/app-12345678.js") { call.respondText("// js") }
                    get("/webapp/entry/index.html") { call.respondText("<!doctype html>") }
                    get("/webapp/entry") { call.respondText("<!doctype html>") }
                    head("/webapp/entry/app-12345678.js") { call.respondText("") }
                    post("/webapp/entry/app-12345678.js") { call.respondText("// js") }
                    get("/webapp/entry/app.js") { call.respondText("// no hash") }
                    get("/api/ping") { call.respondText("pong") }
                }
            }
            val a = client.get("/webapp/entry/app-12345678.js")
            val head = client.head("/webapp/entry/app-12345678.js")
            val html = client.get("/webapp/entry/index.html")
            val rootHtml = client.get("/webapp/entry")
            val post = client.post("/webapp/entry/app-12345678.js")
            val nonHashed = client.get("/webapp/entry/app.js")
            val b = client.get("/api/ping")
            assertEquals("public, max-age=900, immutable", a.headers["Cache-Control"])
            assertEquals("public, max-age=900, immutable", head.headers["Cache-Control"])
            assertEquals("max-age=60, must-revalidate", html.headers["Cache-Control"])
            assertEquals("max-age=60, must-revalidate", rootHtml.headers["Cache-Control"])
            assertEquals("max-age=300, must-revalidate", nonHashed.headers["Cache-Control"])
            assertNull(post.headers["Cache-Control"])
            assertNull(b.headers["Cache-Control"]) // на API не вешаем этот заголовок
        }
    }

    @Test
    fun `no cache headers for 404 on webapp entry`() {
        testApplication {
            application {
                installWebAppImmutableCacheFromEnv { null }
                routing {
                    get("/api/ping") { call.respondText("pong") }
                }
            }
            val r = client.get("/webapp/entry/missing.js")
            assertEquals(404, r.status.value)
            assertNull(r.headers["Cache-Control"])
        }
    }

    @Test
    fun `fingerprinted asset returns 304 with If-None-Match`() {
        testApplication {
            application {
                installWebAppEtagForFingerprints { key ->
                    when (key) {
                        "WEBAPP_ENTRY_CACHE_SECONDS" -> "900"
                        "WEBAPP_CSP_PATH_PREFIX" -> "/webapp/entry"
                        else -> null
                    }
                }
                install(io.ktor.server.plugins.conditionalheaders.ConditionalHeaders)
                installWebAppImmutableCacheFromEnv { key ->
                    when (key) {
                        "WEBAPP_ENTRY_CACHE_SECONDS" -> "900"
                        else -> null
                    }
                }
                routing {
                    get("/webapp/entry/app-abcdef12.js") { call.respondText("// js") }
                }
            }
            val first = client.get("/webapp/entry/app-abcdef12.js")
            val etag = first.headers["ETag"]
            requireNotNull(etag)
            assertEquals("W/\"abcdef12\"", etag)

            val second = client.get("/webapp/entry/app-abcdef12.js") {
                header("If-None-Match", etag)
            }

            assertEquals(304, second.status.value)
            assertEquals("public, max-age=900, immutable", second.headers["Cache-Control"])
            assertEquals(etag, second.headers["ETag"])
        }
    }
}
