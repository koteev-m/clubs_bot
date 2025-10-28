package com.example.bot.data.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.time.Duration
import java.util.concurrent.ThreadLocalRandom
import kotlin.system.measureNanoTime

/**
 * Настройки retry/backoff читаются из ENV; есть дефолты.
 */
private fun envInt(
    name: String,
    default: Int,
): Int = System.getenv(name)?.toIntOrNull() ?: default

private fun envDurationMillis(
    name: String,
    default: Duration,
): Duration = System.getenv(name)?.toLongOrNull()?.let(Duration::ofMillis) ?: default

private const val LOGGER_NAME = "DB.Tx"
private const val ENV_TX_MAX_RETRIES = "DB_TX_MAX_RETRIES"
private const val ENV_TX_BASE_BACKOFF = "DB_TX_BASE_BACKOFF_MS"
private const val ENV_TX_MAX_BACKOFF = "DB_TX_MAX_BACKOFF_MS"
private const val ENV_TX_JITTER = "DB_TX_JITTER_MS"
private const val ENV_SLOW_QUERY = "DB_SLOW_QUERY_MS"

private const val DEFAULT_MAX_RETRIES = 3
private const val BACKOFF_SHIFT_GUARD = 20
private val DEFAULT_BASE_BACKOFF: Duration = Duration.ofMillis(500)
private val DEFAULT_MAX_BACKOFF: Duration = Duration.ofSeconds(15)
private val DEFAULT_JITTER: Duration = Duration.ofMillis(100)
private val DEFAULT_SLOW_QUERY_THRESHOLD: Duration = Duration.ofMillis(200)

private val log = LoggerFactory.getLogger(LOGGER_NAME)

private val MAX_RETRIES: Int = envInt(ENV_TX_MAX_RETRIES, DEFAULT_MAX_RETRIES)
private val BASE_BACKOFF: Duration = envDurationMillis(ENV_TX_BASE_BACKOFF, DEFAULT_BASE_BACKOFF)
private val MAX_BACKOFF: Duration = envDurationMillis(ENV_TX_MAX_BACKOFF, DEFAULT_MAX_BACKOFF)
private val JITTER: Duration = envDurationMillis(ENV_TX_JITTER, DEFAULT_JITTER)
private val SLOW_QUERY_THRESHOLD: Duration = envDurationMillis(ENV_SLOW_QUERY, DEFAULT_SLOW_QUERY_THRESHOLD)

/**
 * Возвращает SQLState из цепочки причин, если есть.
 */
private fun sqlStateOf(ex: Throwable): String? =
    generateSequence(ex) { it.cause }
        .filterIsInstance<SQLException>()
        .firstOrNull()
        ?.sqlState

private fun isRetryableSqlState(sqlState: String?): Boolean {
    // PostgreSQL: 40P01 (deadlock detected), 40001 (serialization failure)
    return sqlState == "40P01" || sqlState == "40001"
}

/**
 * Экспоненциальный backoff с джиттером [0..JITTER].
 */
private suspend fun backoff(attempt: Int) {
    val exp = 1L shl attempt.coerceAtMost(BACKOFF_SHIFT_GUARD) // защита от переполнения
    val base = BASE_BACKOFF.multipliedBy(exp)
    val capped = if (base < MAX_BACKOFF) base else MAX_BACKOFF
    val jitterMillis =
        if (JITTER.isZero || JITTER.isNegative) {
            0L
        } else {
            ThreadLocalRandom.current().nextLong(0, JITTER.toMillis() + 1)
        }
    val totalDelay = capped.plus(Duration.ofMillis(jitterMillis))
    delay(totalDelay.toMillis())
}

/**
 * Выполнить блок внутри Exposed-транзакции (IO), с retry на deadlock/serialization,
 * и slow-query логом по порогу SLOW_QUERY_THRESHOLD.
 */
suspend fun <T> txRetrying(
    db: Database? = null,
    block: suspend () -> T,
): T {
    var attempt = 0
    var lastError: Throwable? = null

    while (attempt <= MAX_RETRIES) {
        try {
            var result: T
            val elapsed =
                measureNanoTime {
                    result =
                        newSuspendedTransaction(
                            context = Dispatchers.IO,
                            db = db,
                        ) {
                            block.invoke()
                        }
                }
            val elapsedDuration = Duration.ofNanos(elapsed)
            if (elapsedDuration > SLOW_QUERY_THRESHOLD) {
                DbMetrics.slowQueryCount.incrementAndGet()
                log.warn(
                    "Slow transaction detected: {} ms > {} ms",
                    elapsedDuration.toMillis(),
                    SLOW_QUERY_THRESHOLD.toMillis(),
                )
            }
            return result
        } catch (ex: Throwable) {
            lastError = ex
            val state = sqlStateOf(ex)
            val retryable = isRetryableSqlState(state)
            if (!retryable || attempt == MAX_RETRIES) {
                log.error(
                    "DB tx failed (attempt={} / max={}, sqlState={}): {}",
                    attempt,
                    MAX_RETRIES,
                    state,
                    ex.toString(),
                )
                throw ex
            }
            DbMetrics.txRetries.incrementAndGet()
            log.warn(
                "DB tx retrying (attempt={} / max={}, sqlState={}): {}",
                attempt + 1,
                MAX_RETRIES,
                state,
                ex.toString(),
            )
            attempt += 1
            backoff(attempt)
        }
    }
    throw lastError ?: IllegalStateException("txRetrying failed without exception")
}
