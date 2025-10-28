package com.example.bot.telegram

import com.example.bot.config.BotLimits
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

interface NotifyIdempotencyStore {
    fun seen(key: String): Boolean

    fun mark(key: String)
}

class InMemoryNotifyIdempotencyStore(
    ttl: Duration =
        System.getenv("NOTIFY_IDEMPOTENCY_TTL_HOURS")?.toLongOrNull()?.let(Duration::ofHours)
            ?: BotLimits.notifyIdempotencyTtl,
) : NotifyIdempotencyStore {
    private data class Entry(val timestamp: Instant)

    private val ttl: Duration = ttl
    private val map: ConcurrentHashMap<String, Entry> = ConcurrentHashMap()
    private val cleaning: AtomicBoolean = AtomicBoolean(false)

    override fun seen(key: String): Boolean {
        cleanupIfNeeded()
        val entry = map[key]
        val isSeen =
            if (entry == null) {
                false
            } else {
                val expired = Instant.now().isAfter(entry.timestamp.plus(ttl))
                if (expired) {
                    map.remove(key)
                    false
                } else {
                    true
                }
            }
        return isSeen
    }

    override fun mark(key: String) {
        cleanupIfNeeded()
        map[key] = Entry(Instant.now())
    }

    private fun cleanupIfNeeded() {
        if (map.size <= BotLimits.notifyIdempotencyCleanupSize || !cleaning.compareAndSet(false, true)) return
        val now = Instant.now()
        try {
            map.entries.removeIf { now.isAfter(it.value.timestamp.plus(ttl)) }
        } finally {
            cleaning.set(false)
        }
    }
}
