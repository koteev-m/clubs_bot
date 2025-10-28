package com.example.bot.security.dedup

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private const val DEFAULT_TTL_MINUTES = 15L

/**
 * In-memory state store for mapping short callback tokens to opaque state objects.
 * Entries expire after configured [ttl].
 */
class CallbackStateStore<T>(private val ttl: Duration = Duration.ofMinutes(DEFAULT_TTL_MINUTES)) {
    private data class Entry<V>(val value: V, val expiresAt: Instant)

    private val store = ConcurrentHashMap<String, Entry<T>>()

    /**
     * Associates [token] with [state]. Any existing value is replaced and the TTL refreshed.
     */
    fun put(
        token: String,
        state: T,
    ) {
        val expiry = Instant.now().plus(ttl)
        store[token] = Entry(state, expiry)
    }

    /**
     * Retrieves state associated with [token] or null if missing or expired.
     */
    fun get(token: String): T? {
        val now = Instant.now()
        val entry = store[token] ?: return null
        return if (entry.expiresAt.isAfter(now)) {
            entry.value
        } else {
            store.remove(token)
            null
        }
    }
}
