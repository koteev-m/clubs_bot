package com.example.bot.data.db

import com.zaxxer.hikari.HikariConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HikariFactoryTest {
    private val dbConfig =
        DbConfig(
            url = "jdbc:postgresql://localhost:5432/postgres",
            user = "test",
            password = "test",
        )

    @Test
    fun `applies env overrides for hikari`() {
        val env =
            mapOf(
                "HIKARI_MAX_POOL_SIZE" to "15",
                "HIKARI_MIN_IDLE" to "5",
                "HIKARI_CONN_TIMEOUT_MS" to "7000",
                "HIKARI_VALIDATION_TIMEOUT_MS" to "3000",
                "HIKARI_LEAK_DETECTION_MS" to "0",
            )

        val cfg: HikariConfig = HikariFactory.buildHikariConfig(dbConfig) { env[it] }

        assertEquals(15, cfg.maximumPoolSize)
        assertEquals(5, cfg.minimumIdle)
        assertEquals(7000, cfg.connectionTimeout)
        assertEquals(3000, cfg.validationTimeout)
        assertEquals(0, cfg.leakDetectionThreshold)
    }

    @Test
    fun `invalid env values fall back to defaults`() {
        val env =
            mapOf(
                "HIKARI_MAX_POOL_SIZE" to "-1",
                "HIKARI_MIN_IDLE" to "99",
                "HIKARI_CONN_TIMEOUT_MS" to "abc",
                "HIKARI_VALIDATION_TIMEOUT_MS" to "10",
                "HIKARI_LEAK_DETECTION_MS" to "9999999",
            )

        val cfg: HikariConfig = HikariFactory.buildHikariConfig(dbConfig) { env[it] }

        assertEquals(20, cfg.maximumPoolSize)
        assertEquals(2, cfg.minimumIdle)
        assertEquals(5_000, cfg.connectionTimeout)
        assertEquals(2_000, cfg.validationTimeout)
        assertEquals(10_000, cfg.leakDetectionThreshold)
    }
}
