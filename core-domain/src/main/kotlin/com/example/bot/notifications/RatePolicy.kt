package com.example.bot.notifications

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil

// Говорящие константы вместо «магических» чисел
private const val MS: Long = 1_000
private const val DEFAULT_GLOBAL_RPS: Int = 25
private const val DEFAULT_CHAT_RPS: Double = 1.5

/** TTL «мягкой» жизни пер-чатовых вёдер (10 минут). */
private const val BUCKET_TTL_MS: Long = 10 * 60 * MS

/** Result of token acquisition. */
data class Permit(val granted: Boolean, val retryAfterMs: Long = 0L)

/** Rate policy with global and per-chat token buckets. */
interface RatePolicy {
    val timeSource: TimeSource

    fun acquireGlobal(
        n: Int = 1,
        now: Long = timeSource.nowMs(),
    ): Permit

    fun acquireChat(
        chatId: Long,
        n: Int = 1,
        now: Long = timeSource.nowMs(),
    ): Permit

    fun on429(
        chatId: Long?,
        retryAfter: Long,
        now: Long = timeSource.nowMs(),
    )
}

private const val SCALE = MS

private class TokenBucket(
    private val capacity: Double,
    private val refillPerSec: Double,
    private val timeSource: TimeSource,
) {
    private val current = AtomicLong((capacity * SCALE).toLong())
    private val lastRefillMs = AtomicLong(timeSource.nowMs())
    private val blockedUntilMs = AtomicLong(0L)

    @Suppress("LoopWithTooManyJumpStatements")
    fun tryAcquire(
        n: Int,
        nowMs: Long,
    ): Permit {
        val blocked = blockedUntilMs.get()
        if (nowMs < blocked) {
            return Permit(false, blocked - nowMs)
        }

        refill(nowMs)

        val need = n * SCALE
        var result: Permit
        while (true) {
            val cur = current.get()
            if (cur >= need) {
                if (current.compareAndSet(cur, cur - need)) {
                    result = Permit(true)
                    break
                }
            } else {
                val shortage = need - cur
                val retry =
                    ceil(shortage.toDouble() / (refillPerSec * SCALE) * MS.toDouble()).toLong()
                result = Permit(false, retry)
                break
            }
        }
        return result
    }

    fun blockUntil(untilMs: Long) {
        blockedUntilMs.updateAndGet { prev -> if (prev < untilMs) untilMs else prev }
    }

    private fun refill(nowMs: Long) {
        while (true) {
            val last = lastRefillMs.get()
            if (nowMs <= last) return
            val elapsed = nowMs - last
            val added =
                (elapsed * refillPerSec * SCALE / MS.toDouble()).toLong()
            val currentVal = current.get()
            val newVal = (currentVal + added).coerceAtMost((capacity * SCALE).toLong())
            if (current.compareAndSet(currentVal, newVal)) {
                lastRefillMs.compareAndSet(last, nowMs)
                return
            }
        }
    }
}

class DefaultRatePolicy(
    globalRps: Int = DEFAULT_GLOBAL_RPS,
    private val chatRps: Double = DEFAULT_CHAT_RPS,
    override val timeSource: TimeSource = SystemTimeSource,
    private val chatTtlMs: Long = BUCKET_TTL_MS,
) : RatePolicy {
    private val globalBucket = TokenBucket(globalRps.toDouble(), globalRps.toDouble(), timeSource)

    private data class Holder(val bucket: TokenBucket, val lastUsed: AtomicLong)

    private val chats = ConcurrentHashMap<Long, Holder>()
    private val lastCleanup = AtomicLong(timeSource.nowMs())

    override fun acquireGlobal(
        n: Int,
        now: Long,
    ): Permit = globalBucket.tryAcquire(n, now)

    override fun acquireChat(
        chatId: Long,
        n: Int,
        now: Long,
    ): Permit {
        val holder =
            chats.compute(chatId) { _, existing ->
                if (existing == null) {
                    Holder(TokenBucket(chatRps, chatRps, timeSource), AtomicLong(now))
                } else {
                    existing.lastUsed.set(now)
                    existing
                }
            }!!
        cleanup(now)
        return holder.bucket.tryAcquire(n, now)
    }

    override fun on429(
        chatId: Long?,
        retryAfter: Long,
        now: Long,
    ) {
        val until = now + retryAfter
        globalBucket.blockUntil(until)
        if (chatId != null) {
            val holder =
                chats.computeIfAbsent(chatId) {
                    Holder(TokenBucket(chatRps, chatRps, timeSource), AtomicLong(now))
                }
            holder.lastUsed.set(now)
            holder.bucket.blockUntil(until)
        }
    }

    private fun cleanup(now: Long) {
        val last = lastCleanup.get()
        if (now - last < chatTtlMs) return
        if (!lastCleanup.compareAndSet(last, now)) return
        val threshold = now - chatTtlMs
        for ((id, holder) in chats.entries) {
            if (holder.lastUsed.get() < threshold) {
                chats.remove(id, holder)
            }
        }
    }
}
