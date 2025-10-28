package com.example.bot.telegram.ott

import com.example.bot.config.BotLimits
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/** Маркер всех payload’ов, на которые мапится одноразовый токен. */
sealed interface OttPayload

/** Пример payload’а: действие «забронировать стол». */
data class BookTableAction(val clubId: Long, val startUtc: String, val tableId: Long) : OttPayload

/** Простейшие метрики стора. */
object OttMetrics {
    val issued = AtomicLong(0)
    val consumed = AtomicLong(0)
    val replayed = AtomicLong(0)
    val storeSize = AtomicInteger(0)
}

/** API стора: issue — выдать новый токен; consume — атомарно получить payload и удалить. */
interface OneTimeTokenStore {
    fun issue(payload: OttPayload): String

    fun consume(token: String): OttPayload?

    fun size(): Int
}

/**
 * In-memory One-Time Token Store:
 *  - TTL: истёкшие записи очищаются лениво;
 *  - LRU-ограничение по размеру: при переполнении — эвиктим в порядке вставки (упрощённо);
 *  - Потокобезопасность: ConcurrentHashMap + lock-free очереди.
 */
class InMemoryOneTimeTokenStore(
    ttlSeconds: Long =
        System.getenv("OTT_TTL_SECONDS")?.toLongOrNull() ?: BotLimits.ottTokenTtl.seconds,
    maxEntries: Int = System.getenv("OTT_MAX_ENTRIES")?.toIntOrNull() ?: BotLimits.ottMaxEntries,
) : OneTimeTokenStore {
    private data class Entry(val payload: OttPayload, val expiresAt: Instant)

    private val ttl: Duration =
        Duration.ofSeconds(ttlSeconds).let { duration ->
            if (duration < BotLimits.ottTokenMinTtl) BotLimits.ottTokenMinTtl else duration
        }
    private val maxEntries: Int = maxEntries.coerceAtLeast(BotLimits.ottMinEntries)

    private val map = ConcurrentHashMap<String, Entry>(16, 0.75f, 4)
    private val order = ConcurrentLinkedQueue<String>() // упрощённое LRU по порядку вставки
    private val cleanupThreshold = min(BotLimits.ottCleanupAbsoluteThreshold, this.maxEntries / 2)

    // CSPRNG для токенов
    private val random = SecureRandom()

    override fun issue(payload: OttPayload): String {
        cleanupIfNeeded()
        val token = generateToken()
        val entry = Entry(payload = payload, expiresAt = Instant.now().plus(ttl))
        map[token] = entry
        order.add(token)
        OttMetrics.issued.incrementAndGet()
        OttMetrics.storeSize.set(map.size)
        return token
    }

    override fun consume(token: String): OttPayload? {
        cleanupIfNeeded()
        val entry = map.remove(token)
        val result =
            if (entry == null) {
                OttMetrics.replayed.incrementAndGet()
                null
            } else {
                OttMetrics.storeSize.set(map.size)
                if (Instant.now().isAfter(entry.expiresAt)) {
                    // истёк — считаем как replay/просрочку
                    OttMetrics.replayed.incrementAndGet()
                    null
                } else {
                    OttMetrics.consumed.incrementAndGet()
                    entry.payload
                }
            }
        return result
    }

    override fun size(): Int = map.size

    private fun generateToken(): String {
        // 16–24 байта энтропии → base64url без паддинга; длина < 64
        val extraBytes =
            BotLimits.ottTokenExtraBytesRange.first + random.nextInt(BotLimits.ottTokenExtraBytesRange.count())
        val len = BotLimits.ottTokenBaseBytes + extraBytes
        val bytes = ByteArray(len)
        random.nextBytes(bytes)
        val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        // страховка на редкий случай длинее 64 (не случится при выбранных размерах)
        return if (b64.length <= BotLimits.ottTokenMaxBase64Length) {
            b64
        } else {
            b64.substring(0, BotLimits.ottTokenMaxBase64Length)
        }
    }

    private fun cleanupIfNeeded() {
        if (map.isEmpty()) return
        evictOverflowIfAny()
        evictExpiredIfLarge()
    }

    /** Эвикция при переполнении (упрощённое LRU по порядку вставки). */
    private fun evictOverflowIfAny() {
        while (map.size > maxEntries) {
            val victim = order.poll()
            if (victim == null) {
                break
            }
            map.remove(victim)
        }
        OttMetrics.storeSize.set(map.size)
    }

    /** Ленивая TTL-очистка при заметном росте. */
    private fun evictExpiredIfLarge() {
        if (map.size <= cleanupThreshold) return
        val now = Instant.now()
        var removed = 0
        for (k in order) {
            val entry = map[k]
            if (entry != null && now.isAfter(entry.expiresAt) && map.remove(k, entry)) {
                removed++
            }
        }
        if (removed > 0) OttMetrics.storeSize.set(map.size)
    }
}
