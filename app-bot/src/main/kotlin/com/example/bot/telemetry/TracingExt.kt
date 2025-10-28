package com.example.bot.telemetry

import io.micrometer.tracing.Span
import io.micrometer.tracing.Tracer
import java.io.Closeable
import java.util.UUID
import org.slf4j.MDC

data class PaymentsTraceMetadata(
    val httpRoute: String? = null,
    val paymentsPath: String? = null,
    val idempotencyKeyPresent: Boolean? = null,
    val bookingIdMasked: String? = null,
    val requestId: String? = null,
)

interface PaymentsSpanScope {
    fun setAttribute(key: String, value: String)

    fun setAttribute(key: String, value: Boolean)

    fun setAttribute(key: String, value: Long)
}

fun maskBookingId(bookingId: UUID): String = bookingId.toString().takeLast(8)

fun <T> Tracer?.span(
    name: String,
    metadata: PaymentsTraceMetadata? = null,
    block: PaymentsSpanScope.() -> T,
): T {
    if (this == null || !PaymentsObservability.isEnabled()) {
        return block(NoopPaymentsSpanScope)
    }
    val realScope = startPaymentsSpan(name)
    metadata?.applyToScope(realScope)
    return try {
        block(realScope)
    } finally {
        realScope.close()
    }
}

suspend fun <T> Tracer?.spanSuspending(
    name: String,
    metadata: PaymentsTraceMetadata? = null,
    block: suspend PaymentsSpanScope.() -> T,
): T {
    if (this == null || !PaymentsObservability.isEnabled()) {
        return block(NoopPaymentsSpanScope)
    }
    val realScope = startPaymentsSpan(name)
    metadata?.applyToScope(realScope)
    return try {
        block(realScope)
    } finally {
        realScope.close()
    }
}

fun PaymentsSpanScope.setResult(result: PaymentsMetrics.Result) {
    setAttribute("payments.result", result.tag)
}

fun PaymentsSpanScope.setRefundAmount(amountMinor: Long) {
    setAttribute("payments.refund.amountMinor", amountMinor)
}

private fun Tracer.startPaymentsSpan(name: String): RealPaymentsSpanScope {
    val span = nextSpan().name(name).start()
    val requestId = MDC.get("requestId") ?: MDC.get("callId")
    if (!requestId.isNullOrBlank()) {
        span.tag("request.id", requestId)
    }
    MDC.get("callId")?.takeIf { it.isNotBlank() }?.let { span.tag("call.id", it) }
    val scope = withSpan(span)
    return RealPaymentsSpanScope(span, scope)
}

private fun PaymentsTraceMetadata.applyToScope(scope: PaymentsSpanScope) {
    httpRoute?.let { scope.setAttribute("http.route", it) }
    paymentsPath?.let { scope.setAttribute("payments.path", it) }
    idempotencyKeyPresent?.let { scope.setAttribute("idempotency.key-present", it) }
    bookingIdMasked?.let { scope.setAttribute("booking.id", it) }
    requestId?.let {
        scope.setAttribute("request.id", it)
        scope.setAttribute("call.id", it)
    }
}

private object NoopPaymentsSpanScope : PaymentsSpanScope {
    override fun setAttribute(key: String, value: String) {}

    override fun setAttribute(key: String, value: Boolean) {}

    override fun setAttribute(key: String, value: Long) {}
}

private class RealPaymentsSpanScope(
    private val span: Span,
    private val scope: Tracer.SpanInScope,
) : PaymentsSpanScope, Closeable {
    override fun setAttribute(key: String, value: String) {
        span.tag(key, value)
    }

    override fun setAttribute(key: String, value: Boolean) {
        span.tag(key, value.toString())
    }

    override fun setAttribute(key: String, value: Long) {
        span.tag(key, value.toString())
    }

    override fun close() {
        try {
            scope.close()
        } finally {
            span.end()
        }
    }
}
