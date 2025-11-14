package com.example.bot.observability

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.example.bot.plugins.installMetrics
import com.example.bot.plugins.installTracing
import io.ktor.client.request.get
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
                routing {
                    get("/ping") { call.respondText("pong") }
                }
            }
            assertEquals(200, client.get("/ping").status.value)
        }

    @Test
    fun `tracing enabled`() =
        testApplication {
            val exporter = InMemorySpanExporter.create()
            val tracer = TracingProvider.create(exporter).tracer
            val list = ListAppender<ILoggingEvent>().apply { start() }
            val logger = LoggerFactory.getLogger("io.ktor.test") as Logger
            logger.addAppender(list)

            application {
                installMetrics()
                installTracing(tracer)

                routing {
                    get("/ping") {
                        // Нам не нужен отдельный плагин request logging — сами пишем строку
                        // и проверяем, что плагин трассировки положил traceId в MDC.
                        logger.info("handling /ping")
                        call.respondText("pong")
                    }
                }
            }

            // Делаем запрос, чтобы сгенерировался хотя бы один спан
            client.get("/ping")

            // Проверяем, что спаны выгрузились в память
            assertTrue(exporter.finishedSpanItems.isNotEmpty(), "spans")

            // Ищем любое лог-событие, где появился traceId в MDC
            val eventWithTrace = list.list.firstOrNull { it.mdcPropertyMap.containsKey("traceId") }
            assertTrue(eventWithTrace != null && eventWithTrace.mdcPropertyMap["traceId"]?.isNotEmpty() == true)

            logger.detachAppender(list)
        }
}
