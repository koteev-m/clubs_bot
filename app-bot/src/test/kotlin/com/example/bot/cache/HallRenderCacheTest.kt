package com.example.bot.cache

import java.nio.ByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
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
    fun `shared hit warms local cache and avoids repeated disk reads`() {
        runBlocking {
            val dir = Files.createTempDirectory("hall-cache-test-")
            try {
                val previous = System.getProperty("HALL_CACHE_SHARED_DIR")
                System.setProperty("HALL_CACHE_SHARED_DIR", dir.toString())
                try {
                    val writer = HallRenderCache(maxEntries = 16, ttl = Duration.ofMinutes(1))
                    val reader = HallRenderCache(maxEntries = 16, ttl = Duration.ofMinutes(1))
                    var renders = 0

                    writer.getOrRender("club-2|night-1", null) {
                        "shared-payload".encodeToByteArray()
                    }

                    val firstRead =
                        reader.getOrRender("club-2|night-1", null) {
                            renders += 1
                            "unexpected-render".encodeToByteArray()
                        }
                    assertTrue(firstRead is HallRenderCache.Result.Ok)
                    assertEquals(0, renders)

                    val cacheFile = singleCacheFile(dir)
                    Files.deleteIfExists(cacheFile)

                    val secondRead =
                        reader.getOrRender("club-2|night-1", null) {
                            renders += 1
                            "unexpected-render-2".encodeToByteArray()
                        }
                    assertTrue(secondRead is HallRenderCache.Result.Ok)
                    assertEquals(0, renders)
                } finally {
                    if (previous == null) {
                        System.clearProperty("HALL_CACHE_SHARED_DIR")
                    } else {
                        System.setProperty("HALL_CACHE_SHARED_DIR", previous)
                    }
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `shared warm-up preserves original expiresAt`() {
        runBlocking {
            val dir = Files.createTempDirectory("hall-cache-test-")
            try {
                val previous = System.getProperty("HALL_CACHE_SHARED_DIR")
                System.setProperty("HALL_CACHE_SHARED_DIR", dir.toString())
                try {
                    val writer = HallRenderCache(maxEntries = 16, ttl = Duration.ofSeconds(2))
                    val reader = HallRenderCache(maxEntries = 16, ttl = Duration.ofMinutes(1))
                    var renders = 0

                    writer.getOrRender("club-ttl|night-1", null) { "payload".encodeToByteArray() }

                    val warmed =
                        reader.getOrRender("club-ttl|night-1", null) {
                            renders += 1
                            "unexpected-render".encodeToByteArray()
                        }
                    assertTrue(warmed is HallRenderCache.Result.Ok)
                    assertEquals(0, renders)

                    val cacheFile = singleCacheFile(dir)
                    Files.deleteIfExists(cacheFile)
                    Thread.sleep(2_200)

                    val afterOriginalExpiry =
                        reader.getOrRender("club-ttl|night-1", null) {
                            renders += 1
                            "render-after-expiry".encodeToByteArray()
                        }
                    assertTrue(afterOriginalExpiry is HallRenderCache.Result.Ok)
                    assertEquals(1, renders)
                } finally {
                    if (previous == null) {
                        System.clearProperty("HALL_CACHE_SHARED_DIR")
                    } else {
                        System.setProperty("HALL_CACHE_SHARED_DIR", previous)
                    }
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `corrupt shared file is deleted and treated as miss`() {
        runBlocking {
            val dir = Files.createTempDirectory("hall-cache-test-")
            try {
                val previous = System.getProperty("HALL_CACHE_SHARED_DIR")
                System.setProperty("HALL_CACHE_SHARED_DIR", dir.toString())
                try {
                    val writer = HallRenderCache(maxEntries = 16, ttl = Duration.ofMinutes(1))
                    writer.getOrRender("club-3|night-1", null) { "payload".encodeToByteArray() }

                    val cacheFile = singleCacheFile(dir)
                    Files.write(cacheFile, byteArrayOf(1, 2, 3, 4))

                    val reader = HallRenderCache(maxEntries = 16, ttl = Duration.ofMinutes(1))
                    var renders = 0
                    val result =
                        reader.getOrRender("club-3|night-1", null) {
                            renders += 1
                            "fresh".encodeToByteArray()
                        }
                    assertTrue(result is HallRenderCache.Result.Ok)
                    assertEquals(1, renders)
                    assertContentEquals("fresh".encodeToByteArray(), Files.readAllBytes(cacheFile).takeLast(5).toByteArray())
                } finally {
                    if (previous == null) {
                        System.clearProperty("HALL_CACHE_SHARED_DIR")
                    } else {
                        System.setProperty("HALL_CACHE_SHARED_DIR", previous)
                    }
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    }

    @Test
    fun `malformed shared header with oversized dimensions is treated as miss and rewritten`() {
        runBlocking {
            val dir = Files.createTempDirectory("hall-cache-test-")
            try {
                val previous = System.getProperty("HALL_CACHE_SHARED_DIR")
                System.setProperty("HALL_CACHE_SHARED_DIR", dir.toString())
                try {
                    val writer = HallRenderCache(maxEntries = 16, ttl = Duration.ofMinutes(1))
                    writer.getOrRender("club-4|night-1", null) { "payload".encodeToByteArray() }
                    val cacheFile = singleCacheFile(dir)
                    Files.write(
                        cacheFile,
                        ByteBuffer.allocate(16)
                            .putLong(Instant.now().plusSeconds(60).epochSecond)
                            .putInt(128)
                            .putInt(Int.MAX_VALUE)
                            .array(),
                    )

                    val reader = HallRenderCache(maxEntries = 16, ttl = Duration.ofMinutes(1))
                    var renders = 0
                    val result =
                        reader.getOrRender("club-4|night-1", null) {
                            renders += 1
                            "fresh-rebuilt".encodeToByteArray()
                        }

                    assertTrue(result is HallRenderCache.Result.Ok)
                    assertEquals(1, renders)

                    val warmedResult =
                        reader.getOrRender("club-4|night-1", null) {
                            renders += 1
                            "should-not-render".encodeToByteArray()
                        }
                    assertTrue(warmedResult is HallRenderCache.Result.Ok)
                    assertEquals(1, renders)
                } finally {
                    if (previous == null) {
                        System.clearProperty("HALL_CACHE_SHARED_DIR")
                    } else {
                        System.setProperty("HALL_CACHE_SHARED_DIR", previous)
                    }
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

    @Test
    fun `shared write falls back when atomic move is not supported`() {
        runBlocking {
            val dir = Files.createTempDirectory("hall-cache-test-")
            try {
                val previous = System.getProperty("HALL_CACHE_SHARED_DIR")
                System.setProperty("HALL_CACHE_SHARED_DIR", dir.toString())
                try {
                    val moveOps = RecordingFallbackMoveOps()
                    val writer = HallRenderCache(maxEntries = 16, ttl = Duration.ofMinutes(1), sharedMoveOps = moveOps)
                    val reader = HallRenderCache(maxEntries = 16, ttl = Duration.ofMinutes(1))
                    var renders = 0

                    writer.getOrRender("club-5|night-1", null) {
                        renders += 1
                        "payload".encodeToByteArray()
                    }

                    val result =
                        reader.getOrRender("club-5|night-1", null) {
                            renders += 1
                            "unexpected-render".encodeToByteArray()
                        }
                    assertTrue(result is HallRenderCache.Result.Ok)
                    assertEquals(1, renders)
                    assertEquals(1, moveOps.atomicAttempts)
                    assertEquals(1, moveOps.fallbackAttempts)
                } finally {
                    if (previous == null) {
                        System.clearProperty("HALL_CACHE_SHARED_DIR")
                    } else {
                        System.setProperty("HALL_CACHE_SHARED_DIR", previous)
                    }
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    }

    private fun singleCacheFile(dir: Path): Path {
        Files.newDirectoryStream(dir, "*.bin").use { stream ->
            return stream.first()
        }
    }

    private class RecordingFallbackMoveOps : SharedCacheMoveOps {
        var atomicAttempts: Int = 0
        var fallbackAttempts: Int = 0

        override fun move(
            source: Path,
            target: Path,
            vararg options: CopyOption,
        ) {
            if (options.contains(java.nio.file.StandardCopyOption.ATOMIC_MOVE)) {
                atomicAttempts += 1
                throw AtomicMoveNotSupportedException(source.toString(), target.toString(), "unsupported in test")
            }
            fallbackAttempts += 1
            Files.move(source, target, *options)
        }
    }
}
