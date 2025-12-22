package com.example.bot.routes

import com.example.bot.plugins.installWebAppImmutableCacheFromEnv
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EdgeHeadersTest {

    @Test
    fun `html responses are short cached`() = testApplication {
        configureTestApplication()

        val response = client.get("/webapp/entry/index.html")

        assertEquals(HttpStatusCode.OK, response.status)
        val contentType = response.headers[HttpHeaders.ContentType]
        assertNotNull(contentType)
        assertTrue(
            contentType.startsWith(ContentType.Text.Html.toString(), ignoreCase = true),
            "Expected HTML content type, got: $contentType"
        )
        val cacheControl = response.headers[HttpHeaders.CacheControl]
        assertEquals("max-age=60, must-revalidate", cacheControl)
        assertFalse(cacheControl!!.contains("immutable", ignoreCase = true))
        assertFalse(cacheControl.contains("public", ignoreCase = true))
    }

    @Test
    fun `fingerprinted assets are cached as immutable`() = testApplication {
        configureTestApplication()

        val response = client.get("/webapp/entry/test-hash.1234567890.js")

        assertEquals(HttpStatusCode.OK, response.status)
        val cacheControl = response.headers[HttpHeaders.CacheControl]
        assertNotNull(cacheControl)
        assertTrue(cacheControl.contains("public", ignoreCase = true))
        assertTrue(cacheControl.contains("max-age=31536000", ignoreCase = true))
        assertTrue(cacheControl.contains("immutable", ignoreCase = true))
        assertFalse(cacheControl.contains("must-revalidate", ignoreCase = true))
    }

    @Test
    fun `fingerprinted css assets are cached as immutable`() = testApplication {
        configureTestApplication()

        val response = client.get("/webapp/entry/test-hash.1234567890.css")

        assertEquals(HttpStatusCode.OK, response.status)
        val cacheControl = response.headers[HttpHeaders.CacheControl]
        assertNotNull(cacheControl)
        assertTrue(cacheControl.contains("public", ignoreCase = true))
        assertTrue(cacheControl.contains("max-age=31536000", ignoreCase = true))
        assertTrue(cacheControl.contains("immutable", ignoreCase = true))
        assertFalse(cacheControl.contains("must-revalidate", ignoreCase = true))
    }

    @Test
    fun `non webapp routes are not affected by immutable cache plugin`() = testApplication {
        application {
            installWebAppImmutableCacheFromEnv { null }
            routing {
                get("/api/ping") {
                    call.respondText("pong", ContentType.Text.Plain)
                }
            }
        }

        val response = client.get("/api/ping")

        assertEquals(HttpStatusCode.OK, response.status)
        val cacheControl = response.headers[HttpHeaders.CacheControl]
        if (cacheControl != null) {
            assertFalse(cacheControl.contains("immutable", ignoreCase = true))
            assertFalse(cacheControl.contains("max-age=31536000", ignoreCase = true))
        }
    }

    private fun ApplicationTestBuilder.configureTestApplication() {
        application {
            installWebAppImmutableCacheFromEnv { null }
            routing {
                get("/webapp/entry/index.html") { call.respondText("<html>ok</html>", ContentType.Text.Html) }
                get("/webapp/entry/test-hash.1234567890.js") {
                    call.respondText("console.log('ok')", ContentType.Application.JavaScript)
                }
                get("/webapp/entry/test-hash.1234567890.css") {
                    call.respondText("body { }", ContentType.Text.CSS)
                }
            }
        }
    }
}
