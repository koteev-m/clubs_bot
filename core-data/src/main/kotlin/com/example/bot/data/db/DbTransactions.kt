package com.example.bot.data.db

import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.min
import kotlin.coroutines.cancellation.CancellationException

private const val ENV_TX_MAX_RETRIES = "DB_TX_MAX_RETRIES"
private const val ENV_TX_BASE_BACKOFF = "DB_TX_BASE_BACKOFF_MS"
private const val ENV_TX_MAX_BACKOFF = "DB_TX_MAX_BACKOFF_MS"
private const val ENV_TX_JITTER = "DB_TX_JITTER_MS"
private const val ENV_SLOW_QUERY_MS = "DB_SLOW_QUERY_MS"

private const val DEFAULT_MAX_RETRIES = 3
private val DEFAULT_BASE_BACKOFF: Duration = Duration.ofMillis(500)
private val DEFAULT_MAX_BACKOFF: Duration = Duration.ofSeconds(15)
private val DEFAULT_JITTER: Duration = Duration.ofMillis(100)
private const val DEFAULT_SLOW_QUERY_MS = 200L

private val log = LoggerFactory.getLogger("DbTx")

data class RetryConfig(
    val maxRetries: Int,
    val baseBackoff: Duration,
    val maxBackoff: Duration,
    val jitter: Duration,
)

private val retryConfig: RetryConfig =
    RetryConfig(
        maxRetries = System.getenv(ENV_TX_MAX_RETRIES)?.toIntOrNull()?.takeIf { it >= 0 } ?: DEFAULT_MAX_RETRIES,
        baseBackoff =
            System.getenv(ENV_TX_BASE_BACKOFF)?.toLongOrNull()?.let(Duration::ofMillis) ?: DEFAULT_BASE_BACKOFF,
        maxBackoff =
            System.getenv(ENV_TX_MAX_BACKOFF)?.toLongOrNull()?.let(Duration::ofMillis) ?: DEFAULT_MAX_BACKOFF,
        jitter = System.getenv(ENV_TX_JITTER)?.toLongOrNull()?.let(Duration::ofMillis) ?: DEFAULT_JITTER,
    )

internal fun resolveSlowQueryThresholdMs(envValue: String?): Long? {
    val parsed = envValue?.toLongOrNull()
    return when {
        envValue == null -> DEFAULT_SLOW_QUERY_MS
        parsed == null -> DEFAULT_SLOW_QUERY_MS
        parsed > 0 -> parsed
        else -> null
    }
}

private val slowQueryThresholdMs: Long? = resolveSlowQueryThresholdMs(System.getenv(ENV_SLOW_QUERY_MS))

internal interface TransactionExecutor {
    suspend fun <T> execute(readOnly: Boolean, database: Database?, block: suspend () -> T): T
}

internal object ExposedTransactionExecutor : TransactionExecutor {
    override suspend fun <T> execute(readOnly: Boolean, database: Database?, block: suspend () -> T): T =
        newSuspendedTransaction(context = Dispatchers.IO, db = database) {
            block()
        }
}

@Volatile
internal var transactionExecutor: TransactionExecutor = ExposedTransactionExecutor

@Volatile
internal var sleep: suspend (Long) -> Unit = { delay(it) }

internal fun computeBackoffMillis(attempt: Int, config: RetryConfig = retryConfig): Long {
    if (attempt <= 0) return 0
    val expShift = attempt.coerceAtMost(20)
    val multiplier = 1L shl (expShift - 1)
    val base = config.baseBackoff.toMillis() * multiplier
    val capped = min(base, config.maxBackoff.toMillis())
    val jitter =
        if (config.jitter.isZero || config.jitter.isNegative) {
            0L
        } else {
            ThreadLocalRandom.current().nextLong(0, config.jitter.toMillis() + 1)
        }
    return capped + jitter
}

private val circuitBreakerConfig: CircuitBreakerConfig = CircuitBreakerConfigProvider.fromEnv()

@Volatile
internal var circuitBreaker: DatabaseCircuitBreaker = DatabaseCircuitBreaker(circuitBreakerConfig)

@Suppress("unused")
private val configLog: Unit = run {
    val slowQueryLabel = slowQueryThresholdMs?.toString() ?: "off"
    log.info(
        "DbTx config: maxRetries={} baseBackoffMs={} maxBackoffMs={} jitterMs={} slowQueryMs={} breaker(threshold={}, windowSec={}, openSec={})",
        retryConfig.maxRetries,
        retryConfig.baseBackoff.toMillis(),
        retryConfig.maxBackoff.toMillis(),
        retryConfig.jitter.toMillis(),
        slowQueryLabel,
        circuitBreakerConfig.failureThreshold,
        circuitBreakerConfig.failureWindow.seconds,
        circuitBreakerConfig.openDuration.seconds,
    )
}

suspend fun <T> withRetriedTx(
    name: String? = null,
    readOnly: Boolean = false,
    database: Database? = null,
    manageTransaction: Boolean = true,
    block: suspend () -> T,
): T {
    val txName = name?.let { " [$it]" } ?: ""
    val metrics = DbMetricsHolder.metrics
    val totalAttempts = retryConfig.maxRetries + 1

    if (circuitBreaker.isOpen()) {
        metrics.recordTxFailure(DbErrorReason.CONNECTION.label)
        log.error("DB circuit breaker is open, rejecting transaction{}", txName)
        throw DatabaseUnavailableException("Database circuit breaker is open")
    }

    var attempt = 0
    var lastError: Throwable? = null

    while (attempt < totalAttempts) {
        try {
            val result: T
            val started = System.nanoTime()
            result =
                if (manageTransaction) {
                    transactionExecutor.execute(readOnly, database, block)
                } else {
                    block()
                }
            val elapsedNanos = System.nanoTime() - started
            val elapsedMillis = Duration.ofNanos(elapsedNanos).toMillis()
            metrics.recordTxDuration(readOnly, elapsedMillis)
            if (slowQueryThresholdMs != null && elapsedMillis > slowQueryThresholdMs) {
                log.warn(
                    "Slow DB transaction{} took {} ms (>{} ms)",
                    txName,
                    elapsedMillis,
                    slowQueryThresholdMs,
                )
            }
            circuitBreaker.onSuccess()
            return result
        } catch (ex: Throwable) {
            if (ex is CancellationException) throw ex
            if (ex is DatabaseUnavailableException) throw ex
            lastError = ex
            val classification = DbErrorClassifier.classify(ex)
            val reasonLabel = classification.reason.label

            val opened = if (classification.isConnectionIssue) circuitBreaker.onConnectionFailure() else false
            if (opened) {
                log.error("DB circuit breaker opened after connection failures, fast-failing{}", txName)
                metrics.markBreakerOpened()
                metrics.recordTxFailure(DbErrorReason.CONNECTION.label)
                throw DatabaseUnavailableException("Database circuit breaker opened", ex)
            }

            val shouldRetry = classification.retryable && attempt < retryConfig.maxRetries
            if (shouldRetry) {
                metrics.recordTxRetry(reasonLabel)
                val backoffMillis = computeBackoffMillis(attempt + 1)
                log.warn(
                    "Retrying transaction{} attempt {}/{} after {} (sqlState={}, cause={})",
                    txName,
                    attempt + 1,
                    totalAttempts,
                    reasonLabel,
                    classification.sqlState,
                    ex.javaClass.simpleName,
                )
                sleep(backoffMillis)
                attempt += 1
                continue
            }

            metrics.recordTxFailure(reasonLabel)
            log.error(
                "DB transaction{} failed after {} attempt(s), reason={} sqlState={} message={}",
                txName,
                attempt + 1,
                reasonLabel,
                classification.sqlState ?: "<none>",
                ex.message,
                ex,
            )
            throw ex
        }
    }

    throw lastError ?: IllegalStateException("withRetriedTx failed without exception")
}

@Deprecated("Use withRetriedTx with explicit name/readOnly parameters")
suspend fun <T> withTxRetry(block: suspend () -> T): T =
    withRetriedTx(manageTransaction = false, block = block)
