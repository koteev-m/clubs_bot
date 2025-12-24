package com.example.bot.data.db

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.equals.shouldBeEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import org.jetbrains.exposed.sql.Database
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.ArrayDeque

class DbTransactionsTest :
    StringSpec({
        beforeTest {
            DbMetricsHolder.configure(InMemoryDbMetrics())
            sleep = { }
            transactionExecutor = ExposedTransactionExecutor
            circuitBreaker = DatabaseCircuitBreaker(CircuitBreakerConfigProvider.fromEnv())
        }

        afterTest {
            DbMetricsHolder.configure(NoOpDbMetrics)
            sleep = { delay(it) }
            transactionExecutor = ExposedTransactionExecutor
            circuitBreaker = DatabaseCircuitBreaker(CircuitBreakerConfigProvider.fromEnv())
        }

        "retryable error succeeds after retry" {
            val metrics = InMemoryDbMetrics()
            DbMetricsHolder.configure(metrics)
            val delays = mutableListOf<Long>()
            sleep = { delays += it }
            val failures = ArrayDeque<Throwable>()
            failures.add(sqlException("40001"))
            transactionExecutor = QueueExecutor(failures)

            val result = withRetriedTx(name = "test") { "ok" }

            result shouldBe "ok"
            metrics.retries.get() shouldBe 1
            metrics.failures.get() shouldBe 0
            delays.shouldHaveSize(1)
        }

        "retryable error exhausts attempts" {
            val metrics = InMemoryDbMetrics()
            DbMetricsHolder.configure(metrics)
            val delays = mutableListOf<Long>()
            sleep = { delays += it }
            transactionExecutor =
                QueueExecutor(
                    ArrayDeque(
                        listOf(
                            sqlException("40P01"),
                            sqlException("40P01"),
                            sqlException("40P01"),
                            sqlException("40P01"),
                        ),
                    ),
                )

            shouldThrow<SQLException> {
                withRetriedTx(name = "fail") { "never" }
            }

            metrics.retries.get() shouldBe 3
            metrics.failures.get() shouldBe 1
            delays.shouldHaveSize(3)
        }

        "non retryable error fails fast" {
            val metrics = InMemoryDbMetrics()
            DbMetricsHolder.configure(metrics)
            transactionExecutor = QueueExecutor(ArrayDeque(listOf(sqlException("23505"))))

            shouldThrow<SQLException> {
                withRetriedTx(name = "constraint") { "never" }
            }

            metrics.retries.get() shouldBe 0
            metrics.failures.get() shouldBe 1
        }

        "circuit breaker opens and recovers" {
            val metrics = InMemoryDbMetrics()
            DbMetricsHolder.configure(metrics)
            val fakeClock = MutableClock()
            circuitBreaker = DatabaseCircuitBreaker(
                CircuitBreakerConfig(
                    failureThreshold = 2,
                    failureWindow = Duration.ofSeconds(30),
                    openDuration = Duration.ofSeconds(10),
                ),
                fakeClock,
            )
            transactionExecutor = QueueExecutor(ArrayDeque(generateSequence { SQLTransientConnectionException("conn", "08006") }.take(5).toList()))

            shouldThrow<DatabaseUnavailableException> {
                withRetriedTx(name = "breaker") { "never" }
            }
            metrics.breakerOpens.get() shouldBe 1

            shouldThrow<DatabaseUnavailableException> {
                withRetriedTx(name = "breaker") { "never" }
            }

            fakeClock.advance(Duration.ofSeconds(15))
            transactionExecutor = QueueExecutor(ArrayDeque())
            val value = withRetriedTx(name = "breaker") { "ok" }
            value shouldBeEqual "ok"
        }
    })

class DbErrorClassifierTest :
    StringSpec({
        "classifies retryable and non-retryable states" {
            DbErrorClassifier.classify(sqlException("40001")).retryable shouldBe true
            DbErrorClassifier.classify(sqlException("40P01")).reason shouldBe DbErrorReason.DEADLOCK
            DbErrorClassifier.classify(sqlException("23505")).retryable shouldBe false
            DbErrorClassifier.classify(sqlException("08006")).reason shouldBe DbErrorReason.CONNECTION
            DbErrorClassifier.classify(SQLTransientConnectionException("t", "08001")).retryable shouldBe true
            DbErrorClassifier.classify(RuntimeException("boom")).retryable shouldBe false
        }

        "backoff is exponential without jitter" {
            val cfg = RetryConfig(maxRetries = 3, baseBackoff = Duration.ofMillis(10), maxBackoff = Duration.ofMillis(80), jitter = Duration.ZERO)
            computeBackoffMillis(1, cfg) shouldBe 10
            computeBackoffMillis(2, cfg) shouldBe 20
            computeBackoffMillis(3, cfg) shouldBe 40
            computeBackoffMillis(4, cfg) shouldBe 80
        }
    })

private class QueueExecutor(private val failures: ArrayDeque<Throwable>) : TransactionExecutor {
    override suspend fun <T> execute(readOnly: Boolean, database: Database?, block: suspend () -> T): T {
        if (failures.isNotEmpty()) {
            throw failures.removeFirst()
        }
        return block()
    }
}

private class MutableClock(
    private var instant: Instant = Instant.now(),
    private val zone: ZoneId = ZoneId.of("UTC"),
) : Clock() {
    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId?): Clock = MutableClock(instant, zone ?: this.zone)

    override fun instant(): Instant = instant

    fun advance(duration: Duration) {
        instant = instant.plus(duration)
    }
}

private fun sqlException(state: String): SQLException = SQLException("error", state)
