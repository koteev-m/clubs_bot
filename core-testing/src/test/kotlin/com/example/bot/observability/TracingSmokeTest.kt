package com.example.bot.observability

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.example.bot.plugins.installMetrics
import com.example.bot.plugins.installRequestLogging
import com.example.bot.plugins.installTracing
import io.ktor.client.request.get
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class TracingSmokeTest {
    @Test
    fun `tracing disabled`() =
        testApplication {
            application {
                installMetrics()
                installRequestLogging()
                routing { get("/ping") { call.respondText("pong") } }
            }
            assertEquals(200, client.get("/ping").status.value)
        }

    @Test
    fun `tracing enabled`() =
        testApplication {
            val exporter = InMemorySpanExporter.create()
            val tracer = TracingProvider.create(exporter).tracer
            val list = ListAppender<ILoggingEvent>()
            val logger = LoggerFactory.getLogger("io.ktor.test") as Logger
            list.start()
            logger.addAppender(list)
            application {
                installMetrics()
                installRequestLogging()
                installTracing(tracer)
                routing { get("/ping") { call.respondText("pong") } }
            }
            client.get("/ping")
            assertTrue(exporter.finishedSpanItems.isNotEmpty(), "spans")
            val event = list.list.firstOrNull { it.mdcPropertyMap.containsKey("traceId") }
            assertTrue(event != null && event.mdcPropertyMap["traceId"]?.isNotEmpty() == true)
            logger.detachAppender(list)
        }
}
