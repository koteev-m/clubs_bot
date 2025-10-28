package com.example.bot.telemetry

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry

/**
 * Backwards-compatible holder for a global [MeterRegistry].
 */
object Telemetry {
    @Volatile
    var registry: MeterRegistry = SimpleMeterRegistry()
}
