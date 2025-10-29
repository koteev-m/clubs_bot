package com.example.bot.observability

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.example.bot.plugins.installRequestLogging
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class CallIdLoggingTest {
    private val safeRequestIdPattern = Regex("^[A-Za-z0-9._-]{8,128}$")

    @Test
    fun `call id propagated`() =
        testApplication {
            val list = ListAppender<ILoggingEvent>()
            val logger = LoggerFactory.getLogger("io.ktor.test") as Logger
            list.start()
            logger.addAppender(list)
            application {
                installRequestLogging()
                routing { get("/ping") { call.respondText("pong") } }
            }
            val res =
                client.get("/ping") {
                    header("X-Request-ID", "abc12345")
                    header("X-Correlation-ID", "abc12345")
                }
            assertEquals("abc12345", res.headers["X-Request-ID"])
            val event = list.list.firstOrNull { it.mdcPropertyMap.containsKey("callId") }
            assertEquals("abc12345", event?.mdcPropertyMap?.get("callId"))
            logger.detachAppender(list)
        }

    @Test
    fun `invalid request id header is sanitized`() =
        testApplication {
            val list = ListAppender<ILoggingEvent>()
            val logger = LoggerFactory.getLogger("io.ktor.test") as Logger
            list.start()
            logger.addAppender(list)
            application {
                installRequestLogging()
                routing { get("/ping") { call.respondText("pong") } }
            }
            val maliciousId = "abcd1234\nmalicious"
            val response =
                client.get("/ping") {
                    header("X-Request-ID", maliciousId)
                }
            val returnedId = response.headers["X-Request-ID"]
            assertNotNull(returnedId)
            assertNotEquals(maliciousId, returnedId)
            assertTrue(safeRequestIdPattern.matches(returnedId!!))
            val callLog = list.list.firstOrNull { it.mdcPropertyMap.containsKey("callId") }
            assertEquals(returnedId, callLog?.mdcPropertyMap?.get("callId"))
            assertFalse(list.list.any { event -> event.mdcPropertyMap["callId"] == maliciousId })
            logger.detachAppender(list)
        }
}
