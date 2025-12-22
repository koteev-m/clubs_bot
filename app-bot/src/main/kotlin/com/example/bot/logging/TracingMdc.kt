package com.example.bot.logging

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import kotlinx.coroutines.withContext
import kotlinx.coroutines.slf4j.MDCContext
import org.slf4j.MDC

object TracingMdc {
    fun updateFromSpan(span: Span) {
        val spanContext = span.spanContext
        if (spanContext.isValid) {
            MDC.put(MdcKeys.TRACE_ID, spanContext.traceId)
            MDC.put(MdcKeys.SPAN_ID, spanContext.spanId)
        }
    }
}

inline fun <T> Tracer.span(name: String, block: (Span) -> T): T {
    val span = spanBuilder(name).startSpan()
    TracingMdc.updateFromSpan(span)
    val scope = span.makeCurrent()
    try {
        return block(span)
    } catch (t: Throwable) {
        span.recordException(t)
        span.setStatus(StatusCode.ERROR)
        throw t
    } finally {
        scope.close()
        span.end()
    }
}

suspend inline fun <T> Tracer.spanSuspended(name: String, crossinline block: suspend (Span) -> T): T {
    val span = spanBuilder(name).startSpan()
    TracingMdc.updateFromSpan(span)
    val scope = span.makeCurrent()
    try {
        return withContext(MDCContext()) { block(span) }
    } catch (t: Throwable) {
        span.recordException(t)
        span.setStatus(StatusCode.ERROR)
        throw t
    } finally {
        scope.close()
        span.end()
    }
}
