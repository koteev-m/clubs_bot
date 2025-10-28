package com.example.bot.plugins

import com.example.bot.observability.TracingProvider
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.call
import io.ktor.server.plugins.callid.callId
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.micrometer.tracing.Tracer
import org.slf4j.MDC

private const val REQUEST_ID_KEY = "requestId"
private const val CALL_ID_KEY = "callId"

fun Application.installTracing(tracer: Tracer) {
    intercept(ApplicationCallPipeline.Setup) {
        val span = tracer.nextSpan().name("${call.request.httpMethod.value} ${call.request.path()}").start()
        val scope = tracer.withSpan(span)
        try {
            val ctx = span.context()
            MDC.put("traceId", ctx.traceId())
            MDC.put("spanId", ctx.spanId())

            val callId = call.callId
            val requestId = callId ?: call.request.headers["X-Request-ID"]
            if (callId != null) {
                MDC.put(CALL_ID_KEY, callId)
                span.tag("http.call_id", callId)
                span.tag("call.id", callId)
            }
            if (!requestId.isNullOrBlank()) {
                MDC.put(REQUEST_ID_KEY, requestId)
                span.tag("http.request_id", requestId)
                span.tag("request.id", requestId)
            }

            span.tag("http.method", call.request.httpMethod.value)
            span.tag("http.target", call.request.path())

            proceed()
        } finally {
            MDC.remove("traceId")
            MDC.remove("spanId")
            MDC.remove(CALL_ID_KEY)
            MDC.remove(REQUEST_ID_KEY)
            scope.close()
            span.end()
        }
    }
}

fun Application.installTracingFromEnv(): TracingProvider.Tracing? {
    val endpoint = System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT")?.takeIf { it.isNotBlank() }
        ?: run {
            environment.log.info("Tracing disabled: OTEL_EXPORTER_OTLP_ENDPOINT not set")
            return null
        }
    val headers = System.getenv("OTEL_EXPORTER_OTLP_HEADERS")?.let(::parseHeaders).orEmpty()

    return runCatching {
        val tracing = TracingProvider.create(endpoint, headers)
        environment.log.info("Tracing enabled via OTEL exporter at {}", endpoint)
        installTracing(tracing.tracer)
        monitor.subscribe(ApplicationStopped) {
            tracing.sdk.close()
        }
        tracing
    }.getOrElse { error ->
        environment.log.error("Failed to initialise tracing", error)
        null
    }
}

private fun parseHeaders(raw: String): Map<String, String> {
    return raw
        .split(',', ';')
        .mapNotNull { part ->
            val trimmed = part.trim()
            if (trimmed.isEmpty()) {
                return@mapNotNull null
            }
            val delimiterIndex = trimmed.indexOf('=')
            if (delimiterIndex <= 0) {
                return@mapNotNull null
            }
            val key = trimmed.substring(0, delimiterIndex).trim()
            val value = trimmed.substring(delimiterIndex + 1).trim()
            if (key.isEmpty()) null else key to value
        }
        .toMap()
}
