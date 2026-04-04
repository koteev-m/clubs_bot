package com.example.bot.workers

import com.example.bot.config.BotLimits
import com.example.bot.data.booking.core.BookingCoreResult
import com.example.bot.data.booking.core.OutboxMessage
import com.example.bot.data.booking.core.OutboxRepository
import io.micrometer.tracing.Tracer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

interface SendPort {
    suspend fun send(
        topic: String,
        payload: JsonObject,
    ): SendOutcome
}

sealed interface SendOutcome {
    data object Ok : SendOutcome

    data class RetryableError(
        val cause: Throwable,
    ) : SendOutcome

    data class FatalError(
        val cause: Throwable,
    ) : SendOutcome
}

class OutboxWorker(
    private val repository: OutboxRepository,
    private val sendPort: SendPort,
    private val limit: Int = 10,
    private val idleDelay: Duration = Duration.ofSeconds(1),
    private val clock: Clock = Clock.systemUTC(),
    private val random: Random = Random.Default,
    private val tracer: Tracer? = null,
    private val queueMetrics: OutboxQueueMetrics? = null,
    private val parallelism: Int = 4,
) {
    private enum class ProcessResult {
        SENT,
        RETRYABLE,
        FATAL,
    }

    private val logger = LoggerFactory.getLogger(OutboxWorker::class.java)

    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            refreshQueueMetrics()
            val batch = repository.pickBatchForSend(limit)
            when {
                batch.isEmpty() -> delay(idleDelay.toMillis())
                else -> processBatch(batch)
            }
        }
    }

    suspend fun runOnce(): Boolean {
        refreshQueueMetrics()
        val batch = repository.pickBatchForSend(limit)
        if (batch.isEmpty()) {
            return false
        }
        processBatch(batch)
        return true
    }

    private suspend fun processBatch(batch: List<OutboxMessage>) {
        val permits = parallelism.coerceAtLeast(1)
        val semaphore = Semaphore(permits)
        val startedAt = Instant.now(clock)
        val sent = AtomicInteger(0)
        val retried = AtomicInteger(0)
        withContext(Dispatchers.IO.limitedParallelism(permits)) {
            batch.map { message ->
                async {
                    semaphore.withPermit {
                        val result = processMessage(message)
                        when (result) {
                            ProcessResult.SENT -> sent.incrementAndGet()
                            ProcessResult.RETRYABLE -> retried.incrementAndGet()
                            ProcessResult.FATAL -> Unit
                        }
                    }
                }
            }.awaitAll()
        }
        val elapsedMinutes = Duration.between(startedAt, Instant.now(clock)).seconds.coerceAtLeast(1) / 60.0
        queueMetrics?.recordSendRate((sent.get() / elapsedMinutes).toInt())
        queueMetrics?.recordRetryRate((retried.get() / elapsedMinutes).toInt())
    }

    private suspend fun refreshQueueMetrics() {
        val queueMetrics = queueMetrics ?: return
        try {
            val stats = repository.queueStats()
            queueMetrics.record(stats, clock)
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            logger.debug("Unable to refresh outbox queue metrics", ex)
        }
    }
    private suspend fun processMessage(message: OutboxMessage): ProcessResult {
        val tracer = tracer
        if (tracer == null) {
            return processMessageInternal(message)
        }
        val span = tracer.nextSpan().name("outbox.process").start()
        span.tag("outbox.topic", message.topic)
        span.tag("outbox.message_id", message.id.toString())
        span.tag("outbox.attempt", (message.attempts + 1).toString())
        currentRequestId()?.let { span.tag("request.id", it) }
        val scope = tracer.withSpan(span)
        try {
            return processMessageInternal(message)
        } finally {
            scope.close()
            span.end()
        }
    }

    private suspend fun processMessageInternal(message: OutboxMessage): ProcessResult {
        val outcome =
            try {
                sendPort.send(message.topic, message.payload)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Throwable) {
                SendOutcome.RetryableError(ex)
            }
        when (outcome) {
            SendOutcome.Ok -> {
                handleSuccess(message)
                return ProcessResult.SENT
            }
            is SendOutcome.RetryableError -> {
                handleRetryable(message, outcome.cause)
                return ProcessResult.RETRYABLE
            }
            is SendOutcome.FatalError -> {
                handleFatal(message, outcome.cause)
                return ProcessResult.FATAL
            }
        }
    }

    private suspend fun handleSuccess(message: OutboxMessage) {
        when (val result = repository.markSent(message.id)) {
            is BookingCoreResult.Success -> logger.debug("Outbox message {} marked as sent", message.id)
            is BookingCoreResult.Failure ->
                logger.warn("Failed to mark outbox message {} as sent: {}", message.id, result.error)
        }
    }

    private suspend fun handleRetryable(
        message: OutboxMessage,
        cause: Throwable,
    ) {
        val delayDuration = computeBackoff(message.attempts + 1)
        val nextAttemptAt = clock.instant().plus(delayDuration)
        val reason = cause.message ?: cause.javaClass.simpleName
        val update =
            repository.markFailedWithRetry(
                id = message.id,
                reason = reason,
                nextAttemptAt = nextAttemptAt,
            )
        when (update) {
            is BookingCoreResult.Success ->
                logger.info(
                    "Retry scheduled for outbox message {} in {} ms",
                    message.id,
                    delayDuration.toMillis(),
                )
            is BookingCoreResult.Failure ->
                logger.warn(
                    "Failed to schedule retry for outbox message {}: {}",
                    message.id,
                    update.error,
                )
        }
    }

    private suspend fun handleFatal(
        message: OutboxMessage,
        cause: Throwable,
    ) {
        logger.error("Fatal error sending outbox message {}", message.id, cause)
        val delayDuration = computeBackoff(1)
        val nextAttemptAt = clock.instant().plus(delayDuration)
        val reason = cause.message ?: cause.javaClass.simpleName
        val update =
            repository.markFailedWithRetry(
                id = message.id,
                reason = reason,
                nextAttemptAt = nextAttemptAt,
            )
        when (update) {
            is BookingCoreResult.Success ->
                logger.info(
                    "Fatal error retry scheduled for message {} in {} ms",
                    message.id,
                    delayDuration.toMillis(),
                )
            is BookingCoreResult.Failure ->
                logger.warn(
                    "Failed to record fatal retry for message {}: {}",
                    message.id,
                    update.error,
                )
        }
    }

    private fun computeBackoff(attempts: Int): Duration =
        computeBackoffDelay(
            attempts = attempts,
            base = BotLimits.notifySendBaseBackoff,
            max = BotLimits.notifySendMaxBackoff,
            jitter = BotLimits.notifySendJitter,
            maxShift = BotLimits.notifyBackoffMaxShift,
            random = random,
        )
}

private fun currentRequestId(): String? = MDC.get("requestId") ?: MDC.get("callId")

internal fun computeBackoffDelay(
    attempts: Int,
    base: Duration,
    max: Duration,
    jitter: Duration,
    maxShift: Int,
    random: Random,
): Duration {
    val safeAttempts = max(attempts, 1)
    val shift = min(safeAttempts - 1, maxShift)
    val baseMillis = base.toMillis()
    val maxMillis = max.toMillis()
    val jitterMillis = jitter.toMillis()
    val raw = baseMillis shl shift
    val capped = min(raw, maxMillis)
    val jitterOffset =
        if (jitterMillis == 0L) {
            0L
        } else {
            random.nextLong(-jitterMillis, jitterMillis + 1)
        }
    val candidate = (capped + jitterOffset).coerceAtLeast(baseMillis)
    val bounded = min(candidate, maxMillis)
    return Duration.ofMillis(bounded)
}
