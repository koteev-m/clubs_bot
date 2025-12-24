package com.example.bot.data.db

import java.time.Clock
import java.time.Duration
import java.util.ArrayDeque

class DatabaseUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

data class CircuitBreakerConfig(
    val failureThreshold: Int,
    val failureWindow: Duration,
    val openDuration: Duration,
)

private const val DEFAULT_FAILURE_THRESHOLD = 5
private val DEFAULT_FAILURE_WINDOW: Duration = Duration.ofSeconds(30)
private val DEFAULT_OPEN_DURATION: Duration = Duration.ofSeconds(20)

private const val ENV_BREAKER_THRESHOLD = "DB_BREAKER_THRESHOLD"
private const val ENV_BREAKER_WINDOW_SECONDS = "DB_BREAKER_WINDOW_SECONDS"
private const val ENV_BREAKER_OPEN_SECONDS = "DB_BREAKER_OPEN_SECONDS"

object CircuitBreakerConfigProvider {
    fun fromEnv(): CircuitBreakerConfig =
        CircuitBreakerConfig(
            failureThreshold =
                System.getenv(ENV_BREAKER_THRESHOLD)
                    ?.toIntOrNull()
                    ?.takeIf { it > 0 }
                    ?: DEFAULT_FAILURE_THRESHOLD,
            failureWindow =
                System.getenv(ENV_BREAKER_WINDOW_SECONDS)
                    ?.toLongOrNull()
                    ?.let(Duration::ofSeconds)
                    ?: DEFAULT_FAILURE_WINDOW,
            openDuration =
                System.getenv(ENV_BREAKER_OPEN_SECONDS)
                    ?.toLongOrNull()
                    ?.let(Duration::ofSeconds)
                    ?: DEFAULT_OPEN_DURATION,
        )
}

class DatabaseCircuitBreaker(
    private val config: CircuitBreakerConfig,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val failureTimestamps: ArrayDeque<Long> = ArrayDeque()

    @Volatile
    private var openedAtMillis: Long? = null

    fun isOpen(nowMillis: Long = clock.millis()): Boolean {
        val openedAt = openedAtMillis ?: return false
        if (nowMillis - openedAt >= config.openDuration.toMillis()) {
            synchronized(this) {
                if (openedAtMillis != null && nowMillis - openedAtMillis!! >= config.openDuration.toMillis()) {
                    openedAtMillis = null
                    failureTimestamps.clear()
                }
            }
            return false
        }
        return true
    }

    fun onSuccess() {
        synchronized(this) {
            failureTimestamps.clear()
            openedAtMillis = null
        }
    }

    fun onConnectionFailure(nowMillis: Long = clock.millis()): Boolean {
        synchronized(this) {
            if (openedAtMillis != null) return true

            prune(nowMillis)
            failureTimestamps += nowMillis
            val shouldOpen = failureTimestamps.size >= config.failureThreshold
            if (shouldOpen) {
                openedAtMillis = nowMillis
                return true
            }
            return false
        }
    }

    private fun prune(nowMillis: Long) {
        val threshold = nowMillis - config.failureWindow.toMillis()
        while (failureTimestamps.isNotEmpty() && failureTimestamps.first() < threshold) {
            failureTimestamps.removeFirst()
        }
    }
}
