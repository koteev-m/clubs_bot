package com.example.bot.workers

import com.example.bot.data.booking.core.OutboxQueueStats
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

class OutboxQueueMetrics(
    registry: MeterRegistry,
) {
    private val queueDepth = AtomicLong(0)
    private val oldestAgeSeconds = AtomicLong(0)

    init {
        Gauge.builder("outbox_queue_depth", queueDepth) { it.get().toDouble() }.register(registry)
        Gauge.builder("outbox_queue_oldest_age", oldestAgeSeconds) { it.get().toDouble() }.register(registry)
    }

    fun record(
        stats: OutboxQueueStats,
        clock: Clock,
    ) {
        queueDepth.set(stats.depth)
        val oldestAge =
            stats.oldestCreatedAt
                ?.let { Duration.between(it, Instant.now(clock)).seconds.coerceAtLeast(0) }
                ?: 0
        oldestAgeSeconds.set(oldestAge)
    }
}
