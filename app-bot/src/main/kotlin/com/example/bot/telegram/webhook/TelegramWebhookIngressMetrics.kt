package com.example.bot.telegram.webhook

import com.example.bot.data.security.webhook.TelegramWebhookIngressRepository
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

class TelegramWebhookIngressMetrics(
    registry: MeterRegistry,
) {
    private val queueDepth = AtomicLong(0)
    private val oldestAgeSeconds = AtomicLong(0)
    private val processingFailures: Counter =
        Counter.builder("telegram.webhook.queue.processing.failures").register(registry)
    private val workerFailures: Counter =
        Counter.builder("telegram.webhook.queue.worker.failures").register(registry)
    private val processingLatency: Timer =
        Timer.builder("telegram.webhook.queue.processing.latency").register(registry)

    init {
        Gauge.builder("telegram.webhook.queue.depth", queueDepth) { it.get().toDouble() }.register(registry)
        Gauge.builder("telegram.webhook.queue.oldest.age.seconds", oldestAgeSeconds) { it.get().toDouble() }.register(registry)
    }

    suspend fun refreshQueueStats(
        repository: TelegramWebhookIngressRepository,
        clock: Clock,
    ) {
        val stats = repository.queueStats()
        queueDepth.set(stats.depth)
        val oldestAge =
            stats.oldestReceivedAt
                ?.let { Duration.between(it, Instant.now(clock)).seconds.coerceAtLeast(0) }
                ?: 0
        oldestAgeSeconds.set(oldestAge)
    }

    fun recordProcessed(
        receivedAt: Instant,
        processingStartedAt: Instant,
        clock: Clock,
    ) {
        val finishedAt = Instant.now(clock)
        val queuedDuration = Duration.between(receivedAt, finishedAt)
        val processingDuration = Duration.between(processingStartedAt, finishedAt)
        processingLatency.record(queuedDuration.plus(processingDuration))
    }

    fun recordProcessingFailure() {
        processingFailures.increment()
    }

    fun recordWorkerFailure() {
        workerFailures.increment()
    }
}
