package com.example.bot.data.db

import java.util.concurrent.atomic.AtomicLong

interface DbMetrics {
    fun recordTxRetry(reason: String)
    fun recordTxFailure(reason: String)
    fun recordTxDuration(readOnly: Boolean, durationMillis: Long)
    fun markBreakerOpened()
}

object DbMetricsHolder {
    @Volatile
    var metrics: DbMetrics = NoOpDbMetrics

    fun configure(metrics: DbMetrics) {
        this.metrics = metrics
    }
}

object NoOpDbMetrics : DbMetrics {
    override fun recordTxRetry(reason: String) {}

    override fun recordTxFailure(reason: String) {}

    override fun recordTxDuration(readOnly: Boolean, durationMillis: Long) {}

    override fun markBreakerOpened() {}
}

class InMemoryDbMetrics : DbMetrics {
    val retries: AtomicLong = AtomicLong()
    val failures: AtomicLong = AtomicLong()
    val breakerOpens: AtomicLong = AtomicLong()
    val durations: MutableList<Pair<Boolean, Long>> = mutableListOf()

    override fun recordTxRetry(reason: String) {
        retries.incrementAndGet()
    }

    override fun recordTxFailure(reason: String) {
        failures.incrementAndGet()
    }

    override fun recordTxDuration(readOnly: Boolean, durationMillis: Long) {
        durations += readOnly to durationMillis
    }

    override fun markBreakerOpened() {
        breakerOpens.incrementAndGet()
    }
}
