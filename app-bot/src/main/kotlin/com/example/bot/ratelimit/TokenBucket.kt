package com.example.bot.ratelimit

import com.example.bot.config.BotLimits
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

private val ONE_SECOND_NANOS: Double = Duration.ofSeconds(1).toNanos().toDouble()

/**
 * Простой потокобезопасный токен-бакет:
 *  - capacity: максимум токенов (burst)
 *  - refillPerSec: скорость пополнения в токенах/сек
 */
class TokenBucket(capacity: Double, refillPerSec: Double, nowNanos: Long = System.nanoTime()) {
    private val capacity = capacity.coerceAtLeast(BotLimits.RateLimit.TOKEN_BUCKET_MIN_CAPACITY)
    private val refillPerSec = refillPerSec.coerceAtLeast(BotLimits.RateLimit.TOKEN_BUCKET_MIN_REFILL_PER_SECOND)

    @Volatile private var tokens: Double = this.capacity

    @Volatile private var lastRefillNs: Long = nowNanos

    /**
     * Пытается взять 1 токен.
     * Возвращает true, если удалось (не блокирует), иначе false.
     */
    @Synchronized
    fun tryAcquire(nowNanos: Long = System.nanoTime()): Boolean {
        refill(nowNanos)
        return if (tokens >= BotLimits.RateLimit.TOKEN_BUCKET_COST) {
            tokens -= BotLimits.RateLimit.TOKEN_BUCKET_COST
            true
        } else {
            false
        }
    }

    @Synchronized
    private fun refill(nowNanos: Long) {
        val elapsedNs = max(0L, nowNanos - lastRefillNs)
        if (elapsedNs <= 0) return
        val elapsedSec = elapsedNs.toDouble() / ONE_SECOND_NANOS
        tokens = min(capacity, tokens + elapsedSec * refillPerSec)
        lastRefillNs = nowNanos
    }
}

/**
 * Хранилище subject-бакетов с TTL (удаляем неиспользуемые).
 */
class SubjectBucketStore(private val capacity: Double, private val refillPerSec: Double, private val ttl: Duration) {
    private data class Entry(
        val bucket: TokenBucket,
        @Volatile var lastSeen: Instant,
    )

    private val map = ConcurrentHashMap<String, Entry>()
    private val sizeCounter = AtomicLong(0)

    fun tryAcquire(subjectKey: String): Boolean {
        val now = Instant.now()
        val entry =
            map.compute(subjectKey) { _, old ->
                val e =
                    if (old == null) {
                        Entry(TokenBucket(capacity, refillPerSec), now).also { sizeCounter.incrementAndGet() }
                    } else {
                        old.lastSeen = now
                        old
                    }
                e
            }!!
        val ok = entry.bucket.tryAcquire()
        if (!ok) {
            cleanupIfNeeded(now)
        }
        return ok
    }

    fun size(): Long = sizeCounter.get()

    private fun cleanupIfNeeded(now: Instant) {
        // Ленивая очистка: если карта разрослась, удалим протухшие
        if (map.size < BotLimits.RateLimit.SUBJECT_CLEANUP_THRESHOLD) return
        var removed = 0
        for ((k, v) in map.entries) {
            val expired = Duration.between(v.lastSeen, now).compareTo(ttl) >= 0
            if (expired) {
                if (map.remove(k, v)) removed++
            }
        }
        if (removed > 0) {
            sizeCounter.addAndGet(-removed.toLong())
        }
    }
}
