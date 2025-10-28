package com.example.bot.config

import java.time.Duration

@Suppress("ktlint:standard:property-naming")
object BotLimits {
    object RateLimit {
        const val TOKEN_BUCKET_MIN_CAPACITY: Double = 1.0
        const val TOKEN_BUCKET_MIN_REFILL_PER_SECOND: Double = 0.1
        const val TOKEN_BUCKET_COST: Double = 1.0
        const val SUBJECT_CLEANUP_THRESHOLD: Int = 10_000

        const val HOT_PATH_MIN_CONFIG_PARALLELISM: Int = 2
        const val HOT_PATH_MIN_ENV_PARALLELISM: Int = 4
        val HOT_PATH_DEFAULT_RETRY_AFTER: Duration = Duration.ofSeconds(1)
    }

    object Cache {
        const val DEFAULT_MAX_ENTRIES: Int = 500
        val DEFAULT_TTL: Duration = Duration.ofSeconds(60)
    }

    object Demo {
        const val STATE_KEY: String = "v1"
        const val CLUB_ID: Long = 1L
        const val START_UTC: String = "2025-12-31T22:00:00Z"
        val TABLE_IDS: List<Long> = listOf(101L, 102L, 103L)
        const val FALLBACK_TOKEN: String = "000000:DEV"
    }

    // Idempotency
    val notifyIdempotencyTtl: Duration = Duration.ofHours(24)
    const val notifyIdempotencyCleanupSize: Int = 50_000

    // One-Time Tokens (OTT)
    val ottTokenTtl: Duration = Duration.ofSeconds(300)
    val ottTokenMinTtl: Duration = Duration.ofSeconds(30)
    const val ottMaxEntries: Int = 100_000
    const val ottMinEntries: Int = 1
    const val ottCleanupAbsoluteThreshold: Int = 10_000
    const val ottTokenBaseBytes: Int = 20
    val ottTokenExtraBytesRange: IntRange = 0..4
    const val ottTokenMaxBase64Length: Int = 64

    // Notify sender / backoff
    val notifySendBaseBackoff: Duration = Duration.ofMillis(500)
    val notifySendMaxBackoff: Duration = Duration.ofMillis(15_000)
    val notifySendJitter: Duration = Duration.ofMillis(100)
    const val notifySendMaxAttempts: Int = 3
    val notifyRetryAfterFallback: Duration = Duration.ofSeconds(1)
    const val notifyBackoffMaxShift: Int = 20
    val notifyDurationPercentiles: DoubleArray = doubleArrayOf(0.5, 0.95)

    object Webhook {
        const val maxPayloadBytes: Long = 1L * 1024 * 1024
        const val duplicateSuspiciousThreshold: Int = 3
        val dedupTtl: Duration = Duration.ofHours(24)
    }
}
