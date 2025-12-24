package com.example.bot.metrics

import com.example.bot.data.db.DbMetrics
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

class MicrometerDbMetrics(
    private val registry: MeterRegistry,
) : DbMetrics {
    private val retryCounters: MutableMap<String, Counter> = ConcurrentHashMap()
    private val failureCounters: MutableMap<String, Counter> = ConcurrentHashMap()
    private val durationTimers: MutableMap<Boolean, Timer> = ConcurrentHashMap()
    private val breakerCounter: Counter by lazy {
        Counter
            .builder("db.breaker.opened")
            .description("Количество открытий circuit breaker БД")
            .register(registry)
    }

    override fun recordTxRetry(reason: String) {
        retryCounters
            .computeIfAbsent(reason) {
                Counter
                    .builder("db.tx.retries")
                    .description("Количество ретраев транзакций")
                    .tag("reason", it)
                    .register(registry)
            }
            .increment()
    }

    override fun recordTxFailure(reason: String) {
        failureCounters
            .computeIfAbsent(reason) {
                Counter
                    .builder("db.tx.failures")
                    .description("Падения транзакций")
                    .tag("reason", it)
                    .register(registry)
            }
            .increment()
    }

    override fun recordTxDuration(readOnly: Boolean, durationMillis: Long) {
        val timer =
            durationTimers.computeIfAbsent(readOnly) {
                Timer
                    .builder("db.tx.duration")
                    .description("Длительность транзакций")
                    .tag("readOnly", readOnly.toString())
                    .register(registry)
            }
        timer.record(Duration.ofMillis(durationMillis))
    }

    override fun markBreakerOpened() {
        breakerCounter.increment()
    }
}
