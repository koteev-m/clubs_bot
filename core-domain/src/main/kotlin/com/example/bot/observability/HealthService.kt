package com.example.bot.observability

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import java.lang.management.ManagementFactory
import java.sql.SQLException
import javax.sql.DataSource

@Serializable
enum class CheckStatus { UP, DOWN }

@Serializable
data class HealthCheck(val name: String, val status: CheckStatus, val details: Map<String, String>? = null)

@Serializable
data class HealthReport(val status: CheckStatus, val checks: List<HealthCheck>)

interface HealthService {
    suspend fun health(): HealthReport

    suspend fun readiness(): HealthReport
}

class DefaultHealthService(
    private val dataSource: DataSource? = null,
    private val dbTimeoutMs: Long = 150,
    private val migrationsApplied: () -> Boolean = { true },
    private val outboxLagSeconds: () -> Long = { 0L },
    private val outboxLagThreshold: Long = 300,
    private val workersRunning: () -> Boolean = { true },
) : HealthService {
    override suspend fun health(): HealthReport {
        val checks = mutableListOf<HealthCheck>()
        checks += uptime()
        checks += memory()
        checks += threads()
        dbCheck()?.let { checks += it }
        val overall = if (checks.all { it.status == CheckStatus.UP }) CheckStatus.UP else CheckStatus.DOWN
        return HealthReport(overall, checks)
    }

    override suspend fun readiness(): HealthReport {
        val checks = mutableListOf<HealthCheck>()
        val base = health()
        checks += base.checks
        checks += migrationCheck()
        checks += outboxCheck()
        checks += workersCheck()
        val overall = if (checks.all { it.status == CheckStatus.UP }) CheckStatus.UP else CheckStatus.DOWN
        return HealthReport(overall, checks)
    }

    private fun uptime(): HealthCheck {
        val up = ManagementFactory.getRuntimeMXBean().uptime
        return HealthCheck("uptime", CheckStatus.UP, mapOf("ms" to up.toString()))
    }

    private fun memory(): HealthCheck {
        val rt = Runtime.getRuntime()
        val used = rt.totalMemory() - rt.freeMemory()
        return HealthCheck(
            "memory",
            CheckStatus.UP,
            mapOf("used" to used.toString(), "max" to rt.maxMemory().toString()),
        )
    }

    private fun threads(): HealthCheck {
        val count = ManagementFactory.getThreadMXBean().threadCount
        return HealthCheck("threads", CheckStatus.UP, mapOf("count" to count.toString()))
    }

    private suspend fun dbCheck(): HealthCheck? {
        val ds = dataSource ?: return null
        return try {
            withTimeout(dbTimeoutMs) {
                ds.connection.use { conn ->
                    conn.createStatement().use { it.execute("SELECT 1") }
                }
            }
            HealthCheck("db", CheckStatus.UP)
        } catch (e: SQLException) {
            HealthCheck("db", CheckStatus.DOWN, mapOf("error" to (e.message ?: "error")))
        } catch (e: TimeoutCancellationException) {
            HealthCheck("db", CheckStatus.DOWN, mapOf("error" to (e.message ?: "timeout")))
        }
    }

    private fun migrationCheck(): HealthCheck =
        if (migrationsApplied()) {
            HealthCheck("migrations", CheckStatus.UP)
        } else {
            HealthCheck("migrations", CheckStatus.DOWN)
        }

    private fun outboxCheck(): HealthCheck {
        val lag = outboxLagSeconds()
        val status = if (lag <= outboxLagThreshold) CheckStatus.UP else CheckStatus.DOWN
        return HealthCheck("outbox", status, mapOf("lagSeconds" to lag.toString()))
    }

    private fun workersCheck(): HealthCheck =
        if (workersRunning()) HealthCheck("workers", CheckStatus.UP) else HealthCheck("workers", CheckStatus.DOWN)
}
