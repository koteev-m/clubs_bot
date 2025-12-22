package com.example.bot.telemetry

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Tracer

object Tracing {
    val openTelemetry: OpenTelemetry by lazy { GlobalOpenTelemetry.get() }
    val tracer: Tracer by lazy { openTelemetry.getTracer("app-bot", "1.0.0") }
}
