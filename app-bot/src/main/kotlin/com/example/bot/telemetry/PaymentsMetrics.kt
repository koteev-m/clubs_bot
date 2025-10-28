package com.example.bot.telemetry

import com.example.bot.observability.MetricsProvider
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Timer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

object PaymentsMetrics {
    enum class Path(val tag: String, internal val metricName: String) {
        Finalize("finalize", "payments.finalize.duration"),
        Cancel("cancel", "payments.cancel.duration"),
        Refund("refund", "payments.refund.duration"),
    }

    enum class Result(val tag: String) {
        Ok("ok"),
        Validation("validation"),
        Conflict("conflict"),
        Unprocessable("unprocessable"),
        Unexpected("unexpected"),
    }

    enum class Source(val tag: String) {
        MiniApp("miniapp"),
        Api("api"),
    }

    enum class ErrorKind(val tag: String) {
        Validation("validation"),
        State("state"),
        Repository("repository"),
        Unprocessable("unprocessable"),
        Unexpected("unexpected"),
    }

    private val remainderGauges = ConcurrentHashMap<String, AtomicLong>()

    fun timer(
        provider: MetricsProvider?,
        path: Path,
        source: Source? = null,
    ): TimerSample {
        if (!PaymentsObservability.isEnabled() || provider == null) {
            return TimerSample.Noop
        }
        return TimerSample.Real(provider, path, source)
    }

    fun incrementIdempotentHit(
        provider: MetricsProvider?,
        path: Path,
        source: Source? = null,
    ) {
        if (!PaymentsObservability.isEnabled() || provider == null) {
            return
        }
        val tags = buildList(path, source)
        provider.counter("payments.idempotent.hit", *tags).increment()
    }

    fun incrementOutboxEnqueued(
        provider: MetricsProvider?,
        event: String,
    ) {
        if (!PaymentsObservability.isEnabled() || provider == null) {
            return
        }
        provider.counter("payments.outbox.enqueued", "event", event).increment()
    }

    fun incrementErrors(
        provider: MetricsProvider?,
        path: Path,
        kind: ErrorKind,
    ) {
        if (!PaymentsObservability.isEnabled() || provider == null) {
            return
        }
        provider.counter("payments.errors", "path", path.tag, "kind", kind.tag).increment()
    }

    fun updateRefundRemainder(
        provider: MetricsProvider?,
        clubId: Long,
        bookingIdLabel: String,
        remainderMinor: Long,
    ) {
        if (!PaymentsObservability.debugGaugesEnabled() || provider == null) {
            return
        }
        val key = "$clubId:$bookingIdLabel"
        val holder = remainderGauges.computeIfAbsent(key) {
            val atomic = AtomicLong(remainderMinor)
            registerGauge(provider, clubId, bookingIdLabel, atomic)
            atomic
        }
        holder.set(remainderMinor)
    }

    internal fun overrideObservabilityEnabledForTest(value: Boolean?) {
        PaymentsObservability.overrideEnabled(value)
    }

    internal fun overrideDebugGaugesForTest(value: Boolean?) {
        PaymentsObservability.overrideDebug(value)
    }

    internal fun resetForTest() {
        remainderGauges.clear()
        PaymentsObservability.resetOverrides()
    }

    private fun registerGauge(
        provider: MetricsProvider,
        clubId: Long,
        bookingIdLabel: String,
        holder: AtomicLong,
    ): Gauge {
        val tags = arrayOf("club", clubId.toString(), "bookingId", bookingIdLabel)
        return provider.gauge("payments.refund.remainder", holderSupplier(holder), *tags)
    }

    private fun holderSupplier(holder: AtomicLong): java.util.function.Supplier<Number> =
        java.util.function.Supplier { holder.get().toDouble() }

    private fun buildList(path: Path, source: Source?): Array<String> {
        val tags = mutableListOf("path", path.tag)
        if (source != null) {
            tags += listOf("source", source.tag)
        }
        return tags.toTypedArray()
    }

    sealed interface TimerSample {
        fun record(result: Result)

        fun record(result: Result, duration: Duration)

        fun <T> record(result: Result, block: () -> T): T

        object Noop : TimerSample {
            override fun record(result: Result) {}

            override fun record(result: Result, duration: Duration) {}

            override fun <T> record(result: Result, block: () -> T): T = block()
        }

        class Real(
            private val provider: MetricsProvider,
            private val path: Path,
            private val source: Source?,
        ) : TimerSample {
            private val startNanos: Long = System.nanoTime()
            private val recorded = AtomicBoolean(false)

            override fun record(result: Result) {
                val elapsed = (System.nanoTime() - startNanos).toDuration(DurationUnit.NANOSECONDS)
                record(result, elapsed)
            }

            override fun record(result: Result, duration: Duration) {
                if (!recorded.compareAndSet(false, true)) {
                    return
                }
                val timer = locateTimer(result)
                timer.record(duration.toLong(DurationUnit.NANOSECONDS), TimeUnit.NANOSECONDS)
            }

            override fun <T> record(result: Result, block: () -> T): T {
                return block().also { record(result) }
            }

            private fun locateTimer(result: Result): Timer {
                val tags = mutableListOf("path", path.tag, "result", result.tag)
                if (source != null) {
                    tags += listOf("source", source.tag)
                }
                val registry = provider.registry
                val tagArray = tags.toTypedArray()
                return registry
                    .find(path.metricName)
                    .tags(*tagArray)
                    .timer()
                    ?: Timer
                        .builder(path.metricName)
                        .tags(*tagArray)
                        .register(registry)
            }
        }
    }
}
