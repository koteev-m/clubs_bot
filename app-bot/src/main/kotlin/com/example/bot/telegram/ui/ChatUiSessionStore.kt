package com.example.bot.telegram.ui

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

interface ChatUiSessionStore {
    fun putNightContext(
        chatId: Long,
        threadId: Int?,
        clubId: Long,
        startUtc: Instant,
    )

    fun getNightContext(
        chatId: Long,
        threadId: Int?,
    ): NightContext?
}

data class NightContext(
    val clubId: Long,
    val startUtc: Instant,
    val storedAt: Instant,
)

class InMemoryChatUiSessionStore(
    private val ttl: Duration = Duration.ofMinutes(10),
    private val clock: Clock = Clock.systemUTC(),
) : ChatUiSessionStore {
    private val contexts = ConcurrentHashMap<SessionKey, NightContext>()
    private val lastCleanupMillis = AtomicLong(0L)
    private val cleanupIntervalMillis: Long = Duration.ofMinutes(1).toMillis()

    override fun putNightContext(
        chatId: Long,
        threadId: Int?,
        clubId: Long,
        startUtc: Instant,
    ) {
        val now = Instant.now(clock)
        val key = SessionKey(chatId, threadId)
        contexts[key] = NightContext(clubId = clubId, startUtc = startUtc, storedAt = now)
        cleanupExpired(now)
    }

    override fun getNightContext(
        chatId: Long,
        threadId: Int?,
    ): NightContext? {
        val now = Instant.now(clock)
        cleanupExpired(now)
        val key = SessionKey(chatId, threadId)
        val context = contexts[key] ?: return null
        return if (isExpired(context, now)) {
            contexts.remove(key, context)
            null
        } else {
            context
        }
    }

    private fun cleanupExpired(now: Instant) {
        val nowMillis = now.toEpochMilli()
        val last = lastCleanupMillis.get()
        val shouldCleanup =
            nowMillis - last >= cleanupIntervalMillis && lastCleanupMillis.compareAndSet(last, nowMillis)
        if (shouldCleanup && contexts.isNotEmpty()) {
            contexts.entries.removeIf { (_, context) -> isExpired(context, now) }
        }
    }

    private fun isExpired(
        context: NightContext,
        now: Instant,
    ): Boolean {
        val expiresAt = context.storedAt.plus(ttl)
        return !expiresAt.isAfter(now)
    }

    private data class SessionKey(val chatId: Long, val threadId: Int?)
}
