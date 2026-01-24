package com.example.bot.notifications

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration

class OpsNotificationServiceConfigTest {
    @Test
    fun `fromEnv uses defaults when values missing`() {
        val config = OpsNotificationServiceConfig.fromEnv { null }
        assertEquals(500, config.queueCapacity)
        assertEquals(Duration.ofSeconds(3), config.sendTimeout)
        assertEquals(3, config.maxAttempts)
        assertEquals(Duration.ofMillis(200), config.retryDelay)
    }

    @Test
    fun `fromEnv applies overrides when valid`() {
        val env =
            mapOf(
                "OPS_NOTIFY_QUEUE_CAPACITY" to "1000",
                "OPS_NOTIFY_SEND_TIMEOUT_MS" to "5000",
                "OPS_NOTIFY_MAX_ATTEMPTS" to "5",
                "OPS_NOTIFY_RETRY_DELAY_MS" to "750",
            )
        val config = OpsNotificationServiceConfig.fromEnv(env::get)
        assertEquals(1000, config.queueCapacity)
        assertEquals(Duration.ofMillis(5000), config.sendTimeout)
        assertEquals(5, config.maxAttempts)
        assertEquals(Duration.ofMillis(750), config.retryDelay)
    }

    @Test
    fun `fromEnv falls back on invalid values`() {
        val env =
            mapOf(
                "OPS_NOTIFY_QUEUE_CAPACITY" to "-1",
                "OPS_NOTIFY_SEND_TIMEOUT_MS" to "0",
                "OPS_NOTIFY_MAX_ATTEMPTS" to "0",
                "OPS_NOTIFY_RETRY_DELAY_MS" to "-1",
            )
        val config = OpsNotificationServiceConfig.fromEnv(env::get)
        assertEquals(500, config.queueCapacity)
        assertEquals(Duration.ofSeconds(3), config.sendTimeout)
        assertEquals(3, config.maxAttempts)
        assertEquals(Duration.ofMillis(200), config.retryDelay)
    }
}
