package com.example.bot.plugins

import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.application
import io.ktor.server.response.respondText
import io.ktor.server.plugins.callid.callId
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CallIdPluginTest {
    @Test
    fun `generates id when header missing`() = testApplication {
        application {
            configureLoggingAndRequestId()
            routing {
                get("/rid") { call.respondText(call.callId ?: "-") }
            }
        }

        val response = client.get("/rid")
        val responseId = response.headers["X-Request-Id"]

        assertNotNull(responseId)
        assertTrue(responseId.isNotBlank())
        assertEquals(responseId, response.bodyAsText())
    }

    @Test
    fun `propagates client id`() = testApplication {
        application {
            configureLoggingAndRequestId()
            routing {
                get("/rid") { call.respondText(call.callId ?: "-") }
            }
        }

        val response = client.get("/rid") {
            headers { append("X-Request-Id", "abc-1234") }
        }

        assertEquals("abc-1234", response.headers["X-Request-Id"])
        assertEquals("abc-1234", response.bodyAsText())
    }

    @Test
    fun `propagates legacy client id`() = testApplication {
        application {
            configureLoggingAndRequestId()
            routing {
                get("/rid") { call.respondText(call.callId ?: "-") }
            }
        }

        val response = client.get("/rid") {
            headers { append("X-Request-ID", "legacy-1234") }
        }

        assertEquals("legacy-1234", response.headers["X-Request-Id"])
        assertEquals("legacy-1234", response.bodyAsText())
    }

    @Test
    fun `rejects invalid characters and regenerates`() = testApplication {
        application {
            configureLoggingAndRequestId()
            routing {
                get("/rid") { call.respondText(call.callId ?: "-") }
            }
        }

        val response = client.get("/rid") {
            headers { append("X-Request-Id", "abc 12345") }
        }

        val responseId = response.headers["X-Request-Id"]

        assertNotNull(responseId)
        assertEquals(responseId, response.bodyAsText())
        assertTrue(responseId != "abc 12345")
    }
}
