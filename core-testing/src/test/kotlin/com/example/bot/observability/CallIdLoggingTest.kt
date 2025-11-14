package com.example.bot.observability

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.example.bot.logging.callIdMdc
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.util.UUID

private const val REQUEST_ID_HEADER = "X-Request-Id"
private const val LEGACY_REQUEST_ID_HEADER = "X-Request-ID"
private val SAFE_REQUEST_ID_REGEX = Regex("^[A-Za-z0-9._-]{8,128}$")

private fun Application.installRequestIdForTest() {
    install(CallId) {
        retrieve { call ->
            call.request.headers[REQUEST_ID_HEADER]
                ?: call.request.headers[LEGACY_REQUEST_ID_HEADER]
        }
        verify { value -> SAFE_REQUEST_ID_REGEX.matches(value) }
        generate {
            UUID.randomUUID()
                .toString()
                .replace("-", "")
                .lowercase()
                .take(32)
        }
        reply { call, id ->
            call.response.header(REQUEST_ID_HEADER, id)
            call.response.header(LEGACY_REQUEST_ID_HEADER, id)
        }
    }
    install(CallLogging) {
        callIdMdc("callId")
    }
}

class CallIdLoggingTest {
    @Test
    fun `call id propagated`() {
        testApplication {
            val list = ListAppender<ILoggingEvent>()
            val logger = LoggerFactory.getLogger("io.ktor.test") as Logger
            list.start()
            logger.addAppender(list)

            application {
                installRequestIdForTest()
                routing { get("/ping") { call.respondText("pong") } }
            }

            val res =
                client.get("/ping") {
                    header(LEGACY_REQUEST_ID_HEADER, "abc12345")
                    header("X-Correlation-ID", "abc12345")
                }

            assertEquals("abc12345", res.headers[LEGACY_REQUEST_ID_HEADER])
            assertEquals("abc12345", res.headers[REQUEST_ID_HEADER])

            val event = list.list.firstOrNull { it.mdcPropertyMap.containsKey("callId") }
            assertEquals("abc12345", event?.mdcPropertyMap?.get("callId"))

            logger.detachAppender(list)
        }
    }

    @Test
    fun `invalid request id header is sanitized`() {
        testApplication {
            val list = ListAppender<ILoggingEvent>()
            val logger = LoggerFactory.getLogger("io.ktor.test") as Logger
            list.start()
            logger.addAppender(list)

            application {
                installRequestIdForTest()
                routing { get("/ping") { call.respondText("pong") } }
            }

            val tooLongId = "a".repeat(200)

            val response =
                client.get("/ping") {
                    header(REQUEST_ID_HEADER, tooLongId)
                }

            val returnedId = response.headers[REQUEST_ID_HEADER]
            assertNotNull(returnedId)
            assertNotEquals(tooLongId, returnedId)
            assertTrue(SAFE_REQUEST_ID_REGEX.matches(returnedId!!))

            val callLog = list.list.firstOrNull { it.mdcPropertyMap.containsKey("callId") }
            assertEquals(returnedId, callLog?.mdcPropertyMap?.get("callId"))

            logger.detachAppender(list)
        }
    }
}
