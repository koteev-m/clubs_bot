package com.example.bot.data.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.time.Duration
import javax.sql.DataSource

object HikariFactory {
    private const val ENV_MAX_POOL_SIZE = "HIKARI_MAX_POOL_SIZE"
    private const val ENV_MIN_IDLE = "HIKARI_MIN_IDLE"
    private const val ENV_CONN_TIMEOUT = "HIKARI_CONN_TIMEOUT_MS"
    private const val ENV_VALIDATION_TIMEOUT = "HIKARI_VALIDATION_TIMEOUT_MS"
    private const val ENV_LEAK_DETECTION = "HIKARI_LEAK_DETECTION_MS"

    private const val DEFAULT_MAX_POOL_SIZE = 20
    private const val DEFAULT_MIN_IDLE = 2
    private val DEFAULT_CONNECTION_TIMEOUT: Duration = Duration.ofSeconds(5)
    private val DEFAULT_VALIDATION_TIMEOUT: Duration = Duration.ofSeconds(2)
    private val DEFAULT_LEAK_DETECTION_THRESHOLD: Duration = Duration.ofSeconds(10)

    private fun envInt(
        name: String,
        default: Int,
    ): Int = System.getenv(name)?.toIntOrNull() ?: default

    private fun envDurationMillis(
        name: String,
        default: Duration,
    ): Duration = System.getenv(name)?.toLongOrNull()?.let(Duration::ofMillis) ?: default

    fun dataSource(db: DbConfig): DataSource {
        val hc =
            HikariConfig().apply {
                jdbcUrl = db.url
                username = db.user
                password = db.password

                maximumPoolSize = envInt(ENV_MAX_POOL_SIZE, DEFAULT_MAX_POOL_SIZE)
                minimumIdle = envInt(ENV_MIN_IDLE, DEFAULT_MIN_IDLE)
                connectionTimeout =
                    envDurationMillis(
                        ENV_CONN_TIMEOUT,
                        DEFAULT_CONNECTION_TIMEOUT,
                    ).toMillis()
                validationTimeout =
                    envDurationMillis(
                        ENV_VALIDATION_TIMEOUT,
                        DEFAULT_VALIDATION_TIMEOUT,
                    ).toMillis()
                leakDetectionThreshold =
                    envDurationMillis(
                        ENV_LEAK_DETECTION,
                        DEFAULT_LEAK_DETECTION_THRESHOLD,
                    ).toMillis()

                isAutoCommit = false
                transactionIsolation = "TRANSACTION_REPEATABLE_READ"
                validate()
            }
        return HikariDataSource(hc)
    }
}
