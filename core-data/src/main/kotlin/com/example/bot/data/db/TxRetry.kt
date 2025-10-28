package com.example.bot.data.db

import com.example.bot.config.BotLimits
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.time.Duration

private val txRetryLogger = LoggerFactory.getLogger("TxRetry")

private const val SQL_STATE_DEADLOCK = "40P01"
private const val SQL_STATE_SERIALIZATION_FAILURE = "40001"
private const val SQL_STATE_UNIQUE_VIOLATION = "23505"

private fun Throwable.sqlState(): String? {
    return generateSequence(this) { it.cause }
        .filterIsInstance<SQLException>()
        .firstOrNull()
        ?.sqlState
}

private fun isRetryable(state: String?): Boolean {
    return state == SQL_STATE_DEADLOCK || state == SQL_STATE_SERIALIZATION_FAILURE
}

private fun computeBackoff(attempt: Int): Duration {
    val shift = attempt.coerceAtMost(BotLimits.notifyBackoffMaxShift)
    val multiplier = 1L shl shift
    val candidate = BotLimits.notifySendBaseBackoff.multipliedBy(multiplier)
    val maxBackoff = BotLimits.notifySendMaxBackoff
    return if (candidate <= maxBackoff) candidate else maxBackoff
}

suspend fun <T> withTxRetry(
    retries: Int = 3,
    block: suspend () -> T,
): T {
    require(retries >= 0) { "retries must be non-negative" }
    var attempt = 0
    var lastError: Throwable? = null
    while (attempt <= retries) {
        try {
            return block()
        } catch (ex: Throwable) {
            lastError = ex
            val state = ex.sqlState()
            if (!isRetryable(state) || attempt == retries) {
                throw ex
            }
            val backoff = computeBackoff(attempt + 1)
            txRetryLogger.warn(
                "Retrying transaction after {} on attempt {} of {} (delay={} ms)",
                state,
                attempt + 1,
                retries,
                backoff.toMillis(),
                ex,
            )
            delay(backoff.toMillis())
            attempt += 1
        }
    }
    throw lastError ?: IllegalStateException("withTxRetry failed without exception")
}

fun Throwable.isUniqueViolation(): Boolean {
    return generateSequence(this) { it.cause }
        .filterIsInstance<SQLException>()
        .any { it.sqlState == SQL_STATE_UNIQUE_VIOLATION }
}

fun Throwable.isRetryLimitExceeded(): Boolean {
    val state = this.sqlState()
    return isRetryable(state)
}
