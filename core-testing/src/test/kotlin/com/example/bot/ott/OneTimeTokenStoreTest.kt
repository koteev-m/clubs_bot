package com.example.bot.ott

import com.example.bot.telegram.ott.BookTableAction
import com.example.bot.telegram.ott.InMemoryOneTimeTokenStore
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Duration

class OneTimeTokenStoreTest {
    @Test
    fun `issue-consume once`() {
        val store = InMemoryOneTimeTokenStore(ttlSeconds = 60, maxEntries = 100)
        val token = store.issue(BookTableAction(1L, "2025-12-31T22:00:00Z", 101L))
        val p1 = store.consume(token)
        assertNotNull(p1)
        val p2 = store.consume(token)
        assertNull(p2)
    }

    @Test
    fun `expired token returns null`() {
        val store = InMemoryOneTimeTokenStore(ttlSeconds = 30, maxEntries = 100)
        val ttlField = InMemoryOneTimeTokenStore::class.java.getDeclaredField("ttl")
        ttlField.isAccessible = true
        ttlField.set(store, Duration.ofSeconds(1))
        val token = store.issue(BookTableAction(1L, "t", 1L))
        // имитация TTL: ждём > 1с
        Thread.sleep(1200)
        val p = store.consume(token)
        assertNull(p)
    }
}
