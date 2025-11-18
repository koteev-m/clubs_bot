package com.example.bot.security.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Validates Telegram Mini App init data according to official algorithm.
 */
object InitDataValidator {
    private val json = Json { ignoreUnknownKeys = true }
    private val webAppKey = "WebAppData".toByteArray(StandardCharsets.UTF_8)
    private val defaultMaxAge: Duration = Duration.ofHours(24)
    private val defaultMaxFutureSkew: Duration = Duration.ofMinutes(2)
    private val EXCLUDED_KEYS = setOf("hash", "signature")

    /**
     * Parses and validates raw init data. Returns user information if valid, or null otherwise.
     */
    fun validate(
        initData: String,
        botToken: String,
        clock: Clock = Clock.systemUTC(),
        maxAge: Duration = defaultMaxAge,
        maxFutureSkew: Duration = defaultMaxFutureSkew,
    ): TelegramUser? {
        val params = runCatching {
            initData
                .split('&')
                .asSequence()
                .filter { it.isNotEmpty() }
                .map { part ->
                    val idx = part.indexOf('=')
                    if (idx == -1) {
                        val key = part
                        key to ""
                    } else {
                        val key = part.substring(0, idx)
                        val value = part.substring(idx + 1)
                        key to URLDecoder.decode(value, StandardCharsets.UTF_8)
                    }
                }
                .toMap()
        }.getOrElse { return null }
        val hash = params["hash"]
        val userJson = params["user"]
        val authDate = params["auth_date"]?.toLongOrNull()
        if (hash == null || userJson == null || authDate == null) {
            return null
        }
        val now = Instant.now(clock)
        val maxAgePositive = !maxAge.isNegative && !maxAge.isZero
        val authInstant = runCatching { Instant.ofEpochSecond(authDate) }.getOrNull() ?: return null
        if (maxAgePositive) {
            val cutoff = now.minus(maxAge)
            if (authInstant.isBefore(cutoff)) {
                return null
            }
        }
        val maxFutureSkewAllowed = !maxFutureSkew.isNegative
        if (maxFutureSkewAllowed) {
            val futureCutoff = now.plus(maxFutureSkew)
            if (authInstant.isAfter(futureCutoff)) {
                return null
            }
        }

        val dataCheckString =
            params
                .asSequence()
                .filter { it.key !in EXCLUDED_KEYS }
                .sortedBy { it.key }
                .joinToString("\n") { "${it.key}=${it.value}" }
        val secretKey = hmacSha256(webAppKey, botToken.toByteArray(StandardCharsets.UTF_8))
        val expected = hmacSha256(secretKey, dataCheckString.toByteArray(StandardCharsets.UTF_8))
        val provided = hash.decodeHexOrNull() ?: return null
        if (provided.size != expected.size) {
            return null
        }
        return if (MessageDigest.isEqual(expected, provided)) {
            runCatching {
                json.decodeFromString(TelegramUser.serializer(), userJson)
            }.getOrNull()
        } else {
            null
        }
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun String.decodeHexOrNull(): ByteArray? {
        val s = trim()
        if (s.length % 2 != 0) return null
        val out = ByteArray(s.length / 2)
        var i = 0
        while (i < s.length) {
            val hi = s[i].digitToIntOrNull(16) ?: return null
            val lo = s[i + 1].digitToIntOrNull(16) ?: return null
            out[i / 2] = ((hi shl 4) or lo).toByte()
            i += 2
        }
        return out
    }
}

/**
 * Minimal representation of Telegram user contained in init data.
 */
@Serializable
data class TelegramUser(
    val id: Long,
    val username: String? = null,
)
