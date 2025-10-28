package com.example.bot.observability

import io.micrometer.tracing.Tracer
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext
import io.micrometer.tracing.otel.bridge.OtelTracer
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter

/**
 * Factory for Micrometer tracing backed by OpenTelemetry.
 */
object TracingProvider {
    data class Tracing(val tracer: Tracer, val sdk: SdkTracerProvider)

    fun create(
        endpoint: String,
        headers: Map<String, String> = emptyMap(),
    ): Tracing {
        val exporter =
            OtlpGrpcSpanExporter
                .builder()
                .setEndpoint(endpoint)
                .apply {
                    headers.forEach { (k, v) -> addHeader(k, v) }
                }.build()
        return create(exporter)
    }

    fun create(exporter: SpanExporter): Tracing {
        val sdkTracerProvider =
            SdkTracerProvider
                .builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build()
        val openTelemetry: OpenTelemetry =
            OpenTelemetrySdk
                .builder()
                .setTracerProvider(sdkTracerProvider)
                .build()
        val tracer = OtelTracer(openTelemetry.getTracer("bot-app"), OtelCurrentTraceContext()) { }
        return Tracing(tracer, sdkTracerProvider)
    }
}
