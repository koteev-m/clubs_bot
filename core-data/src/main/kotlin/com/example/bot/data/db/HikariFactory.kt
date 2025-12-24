package com.example.bot.data.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.time.Duration
import org.slf4j.LoggerFactory
import javax.sql.DataSource
import kotlin.math.min

object HikariFactory {
    private val log = LoggerFactory.getLogger(HikariFactory::class.java)

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

    fun dataSource(
        db: DbConfig,
        envProvider: (String) -> String? = System::getenv,
    ): DataSource = HikariDataSource(buildHikariConfig(db, envProvider))

    internal fun buildHikariConfig(
        db: DbConfig,
        envProvider: (String) -> String? = System::getenv,
    ): HikariConfig =
        HikariConfig().apply {
            jdbcUrl = db.url
            username = db.user
            password = db.password

            val maxPoolSize =
                envInt(
                    ENV_MAX_POOL_SIZE,
                    DEFAULT_MAX_POOL_SIZE,
                    min = 1,
                    max = 50,
                    envProvider = envProvider,
                    log = log,
                )
            val minIdleRequested =
                envInt(
                    ENV_MIN_IDLE,
                    DEFAULT_MIN_IDLE,
                    min = 0,
                    max = 50,
                    envProvider = envProvider,
                    log = log,
                )
            val minIdle = min(minIdleRequested, maxPoolSize)
            if (minIdle < minIdleRequested) {
                log.warn(
                    "HIKARI_MIN_IDLE clamped to maxPoolSize ({} -> {})",
                    minIdleRequested,
                    minIdle,
                )
            }

            val connectionTimeoutMs =
                envLong(
                    ENV_CONN_TIMEOUT,
                    DEFAULT_CONNECTION_TIMEOUT.toMillis(),
                    min = 1_000,
                    max = 120_000,
                    envProvider = envProvider,
                    log = log,
                )
            val validationTimeoutMs =
                envLong(
                    ENV_VALIDATION_TIMEOUT,
                    DEFAULT_VALIDATION_TIMEOUT.toMillis(),
                    min = 500,
                    max = 60_000,
                    envProvider = envProvider,
                    log = log,
                )
            val leakDetectionMs =
                envLong(
                    ENV_LEAK_DETECTION,
                    DEFAULT_LEAK_DETECTION_THRESHOLD.toMillis(),
                    min = 0,
                    max = 600_000,
                    envProvider = envProvider,
                    log = log,
                )
            val leakLabel = if (leakDetectionMs == 0L) "0 (disabled)" else leakDetectionMs.toString()

            maximumPoolSize = maxPoolSize
            minimumIdle = minIdle
            connectionTimeout = connectionTimeoutMs
            validationTimeout = validationTimeoutMs
            leakDetectionThreshold = leakDetectionMs

            log.info(
                "Hikari config: maxPoolSize={} minIdle={} connTimeoutMs={} validationTimeoutMs={} leakDetection={}",
                maxPoolSize,
                minIdle,
                connectionTimeoutMs,
                validationTimeoutMs,
                leakLabel,
            )

            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            validate()
        }
}
