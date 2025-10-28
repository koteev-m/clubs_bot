package com.example.bot.cache

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Duration

class HallRenderCacheEtagTest {
    @Test
    fun `returns 304 on If-None-Match`() =
        runBlocking {
            val cache = HallRenderCache(maxEntries = 10, ttl = Duration.ofSeconds(60))
            val key = "k"
            val first = cache.getOrRender(key, null) { "hello".toByteArray() }
            require(first is HallRenderCache.Result.Ok)
            val etag = first.etag

            val second = cache.getOrRender(key, etag) { "updated".toByteArray() }
            assert(second is HallRenderCache.Result.NotModified)
            val nm = second as HallRenderCache.Result.NotModified
            assertEquals(etag, nm.etag)
        }
}
