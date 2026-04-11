package com.example.bot.cache

import com.example.bot.config.BotLimits
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder

data class CacheEntry(
    val etag: String,
    val bytes: ByteArray,
    val expiresAt: Instant,
)

/**
 * Простой потокобезопасный TTL + LRU кэш на LinkedHashMap(access-order).
 * Все публичные методы синхронизированы.
 */
class TtlLruCache<K, V>(
    private val maxEntries: Int,
    private val ttl: Duration,
) {
    private val map: LinkedHashMap<K, Timed<V>> =
        object : LinkedHashMap<K, Timed<V>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Timed<V>>): Boolean {
                val evict = size > maxEntries
                if (evict) {
                    HallCacheMetrics.evictions.increment()
                }
                return evict
            }
        }

    data class Timed<V>(
        val value: V,
        val expiresAt: Instant,
    )

    @Synchronized
    fun get(key: K): V? {
        val t = map[key]
        val expired = t != null && Instant.now().isAfter(t.expiresAt)
        if (expired) {
            map.remove(key)
        }
        return if (expired) null else t?.value
    }

    @Synchronized
    fun put(
        key: K,
        value: V,
    ) {
        map[key] = Timed(value, Instant.now().plus(ttl))
    }

    @Synchronized
    fun size(): Int = map.size
}

/**
 * Метрики кэша (простые Atomic/Adder; при желании подключаются к Micrometer извне).
 */
object HallCacheMetrics {
    val hits: LongAdder = LongAdder()
    val misses: LongAdder = LongAdder()
    val evictions: LongAdder = LongAdder()
    val rendersMs: AtomicLong = AtomicLong(0)
}

/**
 * Обёртка для работы с кэшем рендера и ETag.
 */
class HallRenderCache(
    maxEntries: Int =
        System.getenv("HALL_CACHE_MAX_ENTRIES")?.toIntOrNull() ?: BotLimits.Cache.DEFAULT_MAX_ENTRIES,
    ttl: Duration =
        System.getenv("HALL_CACHE_TTL_SECONDS")?.toLongOrNull()?.let(Duration::ofSeconds)
            ?: BotLimits.Cache.DEFAULT_TTL,
) {
    private val ttlDuration = ttl
    private val cache = TtlLruCache<String, CacheEntry>(maxEntries, ttlDuration)
    private val sharedDir: Path? =
        (
            System.getenv("HALL_CACHE_SHARED_DIR")
                ?: System.getProperty("HALL_CACHE_SHARED_DIR")
            )?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(Path::of)

    sealed interface Result {
        data class NotModified(
            val etag: String,
        ) : Result

        data class Ok(
            val etag: String,
            val bytes: ByteArray,
        ) : Result
    }

    /**
     * Вернёт NotModified если ifNoneMatch совпадает с актуальным etag, иначе Ok с байтами.
     * supplier должен отрисовать новые байты при отсутствии кэша.
     */
    suspend fun getOrRender(
        key: String,
        ifNoneMatch: String?,
        supplier: suspend () -> ByteArray,
    ): Result {
        val cached = cache.get(key) ?: sharedDir?.readEntry(key)
        val notModified: Boolean
        val etag: String
        val bytes: ByteArray
        if (cached != null) {
            etag = cached.etag
            notModified = ifNoneMatch != null && equalsWeakEtag(ifNoneMatch, etag)
            bytes = cached.bytes
            HallCacheMetrics.hits.increment()
        } else {
            HallCacheMetrics.misses.increment()
            val start = System.nanoTime()
            val freshBytes = supplier.invoke()
            val took = Duration.ofNanos(System.nanoTime() - start)
            HallCacheMetrics.rendersMs.addAndGet(took.toMillis())
            etag = computeEtag(freshBytes)
            cache.put(key, CacheEntry(etag, freshBytes, Instant.now().plus(ttlDuration)))
            sharedDir?.writeEntry(key, CacheEntry(etag, freshBytes, Instant.now().plus(ttlDuration)))
            notModified = ifNoneMatch != null && equalsWeakEtag(ifNoneMatch, etag)
            bytes = freshBytes
        }
        return if (notModified) Result.NotModified(etag) else Result.Ok(etag, bytes)
    }

    companion object {
        fun computeEtag(bytes: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(bytes)
            val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
            return "W/\"$b64\""
        }

        private fun equalsWeakEtag(
            ifNoneMatch: String,
            etag: String,
        ): Boolean {
            if (ifNoneMatch.trim() == "*") return true
            val normA = ifNoneMatch.trim()
            val normB = etag.trim()
            return normA == normB
        }
    }
}

private fun Path.readEntry(key: String): CacheEntry? {
    runCatching { Files.createDirectories(this) }
    val file = resolve(key.toCacheFileName())
    if (!Files.exists(file)) return null
    return runCatching {
        Files.newInputStream(file).use { input ->
            val header = ByteArray(16)
            if (input.read(header) != header.size) return null
            val expiresAtEpochSec = java.nio.ByteBuffer.wrap(header, 0, 8).long
            val etagSize = java.nio.ByteBuffer.wrap(header, 8, 4).int
            val bytesSize = java.nio.ByteBuffer.wrap(header, 12, 4).int
            if (etagSize <= 0 || bytesSize < 0) return null
            val etagBytes = ByteArray(etagSize)
            val imageBytes = ByteArray(bytesSize)
            if (input.read(etagBytes) != etagSize || input.read(imageBytes) != bytesSize) return null
            val entry =
                CacheEntry(
                    etag = String(etagBytes, Charsets.UTF_8),
                    bytes = imageBytes,
                    expiresAt = Instant.ofEpochSecond(expiresAtEpochSec),
                )
            if (Instant.now().isAfter(entry.expiresAt)) {
                Files.deleteIfExists(file)
                return null
            }
            entry
        }
    }.getOrNull()
}

private fun Path.writeEntry(key: String, entry: CacheEntry) {
    runCatching {
        Files.createDirectories(this)
        val file = resolve(key.toCacheFileName())
        Files.newOutputStream(file).use { output ->
            val etagBytes = entry.etag.toByteArray(Charsets.UTF_8)
            val header = java.nio.ByteBuffer.allocate(16)
            header.putLong(entry.expiresAt.epochSecond)
            header.putInt(etagBytes.size)
            header.putInt(entry.bytes.size)
            output.write(header.array())
            output.write(etagBytes)
            output.write(entry.bytes)
        }
    }
}

private fun String.toCacheFileName(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest) + ".bin"
}
