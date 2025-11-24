package com.example.bot.plugins

import io.ktor.client.request.head
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebAppHeadersPluginTest {
    @Test
    fun `POST does not append headers under webapp entry`() = testApplication {
        application {
            install(CspPlugin)
            install(WebAppStaticCachePlugin)
            routing {
                get("/webapp/entry") { call.respondText("ok", ContentType.Text.Html) }
            }
        }

        val response = client.post("/webapp/entry") { setBody("payload") }

        assertNull(response.headers["Content-Security-Policy"])
        assertNull(response.headers["Cache-Control"])
    }

    @Test
    fun `HEAD appends headers like GET`() = testApplication {
        application {
            install(CspPlugin)
            install(WebAppStaticCachePlugin)
            routing {
                get("/webapp/entry") { call.respondText("ok", ContentType.Text.Html) }
                head("/webapp/entry") { call.respond(HttpStatusCode.OK) }
            }
        }

        val response = client.head("/webapp/entry")

        assertEquals(HttpStatusCode.OK, response.status)
        val csp = response.headers["Content-Security-Policy"]
        assertTrue(csp?.contains("base-uri 'self'") == true)
        assertTrue(csp?.contains("form-action 'self'") == true)
        assertTrue(csp?.contains("object-src 'none'") == true)
        assertEquals("max-age=60, must-revalidate", response.headers["Cache-Control"])
    }

    @Test
    fun `trailing slash in prefix behaves the same`() = testApplication {
        application {
            install(CspPlugin) {
                pathPrefix = "/webapp/entry/"
            }
            install(WebAppStaticCachePlugin) {
                pathPrefix = "/webapp/entry/"
            }
            routing {
                get("/webapp/entry") { call.respondText("ok", ContentType.Text.Html) }
            }
        }

        val response = client.get("/webapp/entry")

        assertEquals("max-age=60, must-revalidate", response.headers["Cache-Control"])
        assertTrue(response.headers["Content-Security-Policy"]?.isNotBlank() == true)
    }

    @Test
    fun `fingerprinted assets get immutable cache`() = testApplication {
        application {
            install(CspPlugin)
            install(WebAppStaticCachePlugin)
            routing {
                get("/webapp/entry/app-123.js") { call.respondText("ok", ContentType.Application.JavaScript) }
            }
        }

        val response = client.get("/webapp/entry/app-123.js")

        assertEquals("public, max-age=31536000, immutable", response.headers["Cache-Control"])
    }
}
