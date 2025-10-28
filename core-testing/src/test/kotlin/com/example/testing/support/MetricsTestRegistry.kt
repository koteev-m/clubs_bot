package com.example.testing.support

import io.micrometer.core.instrument.simple.SimpleMeterRegistry

/** Shared [SimpleMeterRegistry] for tests verifying metrics. */
object MetricsTestRegistry {
    val registry = SimpleMeterRegistry()
}
