package com.example.bot.workers

import com.example.bot.data.booking.core.BookingCoreResult
import com.example.bot.data.booking.core.OutboxMessage
import com.example.bot.data.booking.core.OutboxRepository
import com.example.bot.observability.MetricsProvider
import com.example.bot.payments.provider.ProviderRefundClient
import com.example.bot.payments.provider.ProviderRefundCommand
import com.example.bot.payments.provider.ProviderRefundResult
import com.example.bot.telemetry.spanSuspending
import io.micrometer.core.instrument.Timer
import io.micrometer.tracing.Tracer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Supplier

private val SUPPORTED_TOPICS = setOf("payment.refunded", "booking.cancelled")

data class RefundWorkerConfig(
    val enabled: Boolean = envBool("REFUND_WORKER_ENABLED", default = true),
    val interval: Duration = envDuration("REFUND_WORKER_INTERVAL_MS", Duration.ofSeconds(1)),
    val batchSize: Int = envInt("REFUND_WORKER_BATCH", 50),
) {
    companion object {
        fun fromEnv(): RefundWorkerConfig = RefundWorkerConfig()
    }
}

class RefundOutboxWorker(
    private val outbox: OutboxRepository,
    private val client: ProviderRefundClient,
    private val metrics: MetricsProvider?,
    private val tracer: Tracer?,
    private val clock: Clock = Clock.systemUTC(),
    private val config: RefundWorkerConfig = RefundWorkerConfig(),
) {
    private val logger = LoggerFactory.getLogger(RefundOutboxWorker::class.java)
    private val backlogGaugeValue = AtomicLong(0)
    private val backlogGauge = metrics?.gauge("outbox.refund.backlog", Supplier<Number> { backlogGaugeValue.get() })

    suspend fun runOnce(): Boolean {
        updateBacklogGauge()
        val batch =
            runCatching {
                outbox.pickBatchForTopics(config.batchSize, SUPPORTED_TOPICS)
            }.onFailure { ex ->
                logger.error("Failed to fetch refund outbox batch", ex)
            }.getOrElse { return false }
        if (batch.isEmpty()) {
            return false
        }
        batch.forEach { message ->
            runCatching { processMessage(message) }.onFailure { ex ->
                logger.error("Unexpected error processing refund outbox message {}", message.id, ex)
            }
        }
        return true
    }

    suspend fun runLoop() {
        while (currentCoroutineContext().isActive) {
            val processed = runOnce()
            if (!processed) {
                delay(config.interval.toMillis())
            }
        }
    }

    private suspend fun processMessage(message: OutboxMessage) {
        val attempt = message.attempts + 1
        val bookingId = message.payload["bookingId"]?.jsonPrimitive?.contentOrNull
        val timer = metrics?.timer("provider.refund.duration", "topic", message.topic)
        val startNanos = System.nanoTime()
        val result =
            tracer.spanSuspending("provider.refund") {
                currentRequestId()?.let { setAttribute("request.id", it) }
                setAttribute("outbox.topic", message.topic)
                setAttribute("outbox.message_id", message.id)
                bookingId?.let { setAttribute("booking.id", it) }
                setAttribute("attempt", attempt.toLong())
                val command =
                    ProviderRefundCommand(
                        topic = message.topic,
                        payload = message.payload,
                        idempotencyKey = message.id.toString(),
                    )
                val outcome =
                    runCatching { client.send(command) }
                        .onFailure { failure ->
                            logger.warn(
                                "Provider refund request failed for message {} attempt {}", message.id, attempt, failure,
                            )
                        }
                        .getOrElse { throwable ->
                            ProviderRefundResult.Retry(status = null, retryAfter = null, cause = throwable)
                        }
                if (outcome is ProviderRefundResult.Retry && outcome.retryAfter != null) {
                    setAttribute("retryAfterMs", outcome.retryAfter.toMillis())
                }
                outcome
            }
        recordDuration(timer, startNanos)
        when (result) {
            ProviderRefundResult.Success -> handleSuccess(message)
            is ProviderRefundResult.Failure -> handlePermanentFailure(message, result)
            is ProviderRefundResult.Retry -> handleRetry(message, result, attempt)
        }
    }

    private suspend fun handleSuccess(message: OutboxMessage) {
        when (val update = outbox.markSent(message.id)) {
            is BookingCoreResult.Success -> {
                metrics?.counter("provider.refund.ok", "topic", message.topic)?.increment()
                logger.info("Refund outbox message {} delivered", message.id)
            }
            is BookingCoreResult.Failure -> {
                logger.warn("Failed to mark refund outbox message {} as sent: {}", message.id, update.error)
            }
        }
    }

    private suspend fun handleRetry(
        message: OutboxMessage,
        result: ProviderRefundResult.Retry,
        attempt: Int,
    ) {
        val delayDuration = result.retryAfter ?: computeBackoff(attempt)
        val nextAttemptAt = Instant.now(clock).plus(delayDuration)
        val reason = buildRetryReason(result)
        when (val update = outbox.markFailedWithRetry(message.id, reason, nextAttemptAt)) {
            is BookingCoreResult.Success -> {
                metrics?.counter("provider.refund.retry", "topic", message.topic, "status", reason)?.increment()
                logger.info(
                    "Retry scheduled for refund outbox message {} in {} ms (reason={})",
                    message.id,
                    delayDuration.toMillis(),
                    reason,
                )
            }
            is BookingCoreResult.Failure ->
                logger.warn(
                    "Failed to schedule retry for refund outbox message {}: {}", message.id, update.error,
                )
        }
    }

    private suspend fun handlePermanentFailure(
        message: OutboxMessage,
        result: ProviderRefundResult.Failure,
    ) {
        val reason = "HTTP ${result.status}"
        when (val update = outbox.markFailedPermanently(message.id, reason)) {
            is BookingCoreResult.Success -> {
                metrics?.counter("provider.refund.fail", "topic", message.topic, "status", reason)?.increment()
                logger.error(
                    "Refund outbox message {} failed permanently with status {}: {}",
                    message.id,
                    result.status,
                    result.body ?: "<empty>",
                )
            }
            is BookingCoreResult.Failure -> {
                logger.warn(
                    "Failed to mark refund outbox message {} as failed: {}", message.id, update.error,
                )
            }
        }
    }

    private fun recordDuration(timer: Timer?, startNanos: Long) {
        if (timer == null) {
            return
        }
        val elapsed = System.nanoTime() - startNanos
        val duration = Duration.ofNanos(elapsed)
        timer.record(duration)
    }

    private fun computeBackoff(attempt: Int): Duration {
        val base = Duration.ofSeconds(1)
        val max = Duration.ofMinutes(5)
        val multiplier = 1L shl (attempt - 1).coerceAtMost(10)
        val candidate = base.multipliedBy(multiplier)
        return if (candidate.compareTo(max) > 0) max else candidate
    }

    private fun buildRetryReason(result: ProviderRefundResult.Retry): String {
        return when {
            result.status != null -> "HTTP ${result.status}"
            result.cause != null -> result.cause.message ?: result.cause.javaClass.simpleName
            else -> "retry"
        }
    }

    private suspend fun updateBacklogGauge() {
        val pending =
            runCatching { outbox.countPending(SUPPORTED_TOPICS) }
                .onFailure { ex -> logger.warn("Failed to update refund backlog gauge", ex) }
                .getOrNull()
                ?: return
        backlogGaugeValue.set(pending)
    }
}

private fun currentRequestId(): String? = MDC.get("requestId") ?: MDC.get("callId")

private fun envBool(name: String, default: Boolean): Boolean {
    val raw = System.getenv(name) ?: return default
    return raw.equals("true", ignoreCase = true)
}

private fun envInt(name: String, default: Int): Int {
    return System.getenv(name)?.toIntOrNull()?.takeIf { it > 0 } ?: default
}

private fun envDuration(name: String, default: Duration): Duration {
    val millis = System.getenv(name)?.toLongOrNull()
    return if (millis != null && millis > 0) Duration.ofMillis(millis) else default
}
