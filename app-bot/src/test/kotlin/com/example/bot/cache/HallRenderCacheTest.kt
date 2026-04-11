package com.example.bot.cache

import java.nio.file.Files
import java.time.Duration
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HallRenderCacheTest {
    @Test
    fun `shared directory allows second cache instance to hit`() {
        runBlocking {
        val dir = Files.createTempDirectory("hall-cache-test-")
        try {
            val previous = System.getProperty("HALL_CACHE_SHARED_DIR")
            System.setProperty("HALL_CACHE_SHARED_DIR", dir.toString())
            try {
                val first = HallRenderCache(maxEntries = 16, ttl = Duration.ofMinutes(1))
                val second = HallRenderCache(maxEntries = 16, ttl = Duration.ofMinutes(1))
                var renders = 0

                val firstResult =
                    first.getOrRender("club-1|night-1", null) {
                        renders += 1
                        "png-data".encodeToByteArray()
                    }
                assertTrue(firstResult is HallRenderCache.Result.Ok)

                val secondResult =
                    second.getOrRender("club-1|night-1", null) {
                        renders += 1
                        "new-data".encodeToByteArray()
                    }
                assertTrue(secondResult is HallRenderCache.Result.Ok)
                assertEquals(1, renders)
            } finally {
                if (previous == null) System.clearProperty("HALL_CACHE_SHARED_DIR") else System.setProperty("HALL_CACHE_SHARED_DIR", previous)
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
        }
    }

    @Test
    fun `etag match returns not modified`() {
        runBlocking {
            val cache = HallRenderCache(maxEntries = 16, ttl = Duration.ofMinutes(1))
            val result1 = cache.getOrRender("same-key", null) { "payload".encodeToByteArray() }
            val etag = (result1 as HallRenderCache.Result.Ok).etag

            val result2 = cache.getOrRender("same-key", etag) { error("supplier should not be called") }
            assertTrue(result2 is HallRenderCache.Result.NotModified)
            assertEquals(etag, (result2 as HallRenderCache.Result.NotModified).etag)
        }
    }
}
