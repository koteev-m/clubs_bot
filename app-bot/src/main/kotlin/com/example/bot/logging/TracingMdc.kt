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
    val previousTrace = MDC.get(MdcKeys.TRACE_ID)
    val previousSpan = MDC.get(MdcKeys.SPAN_ID)
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
        restoreTraceMdc(previousTrace, previousSpan)
        scope.close()
        span.end()
    }
}

suspend inline fun <T> Tracer.spanSuspended(name: String, crossinline block: suspend (Span) -> T): T {
    val previousTrace = MDC.get(MdcKeys.TRACE_ID)
    val previousSpan = MDC.get(MdcKeys.SPAN_ID)
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
        restoreTraceMdc(previousTrace, previousSpan)
        scope.close()
        span.end()
    }
}

@PublishedApi
internal fun restoreTraceMdc(previousTrace: String?, previousSpan: String?) {
    if (previousTrace == null) MDC.remove(MdcKeys.TRACE_ID) else MDC.put(MdcKeys.TRACE_ID, previousTrace)
    if (previousSpan == null) MDC.remove(MdcKeys.SPAN_ID) else MDC.put(MdcKeys.SPAN_ID, previousSpan)
}
