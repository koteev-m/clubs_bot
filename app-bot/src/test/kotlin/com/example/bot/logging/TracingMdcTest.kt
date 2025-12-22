package com.example.bot.logging

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import org.slf4j.MDC

class TracingMdcTest :
    StringSpec({
        beforeTest {
            MDC.clear()
            runCatching { GlobalOpenTelemetry.resetForTest() }
        }

        afterTest {
            MDC.clear()
            runCatching { GlobalOpenTelemetry.resetForTest() }
        }

        "span restores previous MDC trace and span ids" {
            val tracerProvider = SdkTracerProvider.builder().build()
            val openTelemetry =
                OpenTelemetrySdk
                    .builder()
                    .setTracerProvider(tracerProvider)
                    .build()
            val tracer = openTelemetry.getTracer("tracing-mdc-test")

            try {
                tracer.span("outer") { _ ->
                    val outerTrace = MDC.get(MdcKeys.TRACE_ID).shouldNotBeNull()
                    val outerSpan = MDC.get(MdcKeys.SPAN_ID).shouldNotBeNull()

                    tracer.span("inner") { _ ->
                        val innerTrace = MDC.get(MdcKeys.TRACE_ID).shouldNotBeNull()
                        val innerSpan = MDC.get(MdcKeys.SPAN_ID).shouldNotBeNull()

                        innerTrace shouldBe outerTrace
                        innerSpan shouldNotBe outerSpan
                    }

                    MDC.get(MdcKeys.TRACE_ID) shouldBe outerTrace
                    MDC.get(MdcKeys.SPAN_ID) shouldBe outerSpan
                }
                MDC.get(MdcKeys.TRACE_ID).shouldBeNull()
                MDC.get(MdcKeys.SPAN_ID).shouldBeNull()
            } finally {
                tracerProvider.close()
            }
        }
    })
