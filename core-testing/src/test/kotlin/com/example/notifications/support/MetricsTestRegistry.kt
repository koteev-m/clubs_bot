package com.example.notifications.support

import io.micrometer.core.instrument.simple.SimpleMeterRegistry

/** Provides a shared [SimpleMeterRegistry] for tests. */
object MetricsTestRegistry {
    val registry: SimpleMeterRegistry = SimpleMeterRegistry()
}
