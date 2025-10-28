package com.example.bot.guestlists

import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Короткий токен для deep-link старт-параметра Telegram (<= 64 символов).
 *
 * Формат: G_<listId>_<entryId>_<issuedEpochSec>_<hmac16hex>
 * - hmac16hex — первые 16 hex-символов от HMAC-SHA256(message, derivedKey)
 * - message = "<listId>:<entryId>:<issuedEpochSec>"
 */
object StartParamGuestListCodec {
    private const val PREFIX = "G"
    private const val HMAC_ALGO = "HmacSHA256"
    private const val HMAC_TRUNC_HEX_LEN = 16
    private val KEY_LABEL_BYTES = "StartGuestList".toByteArray(StandardCharsets.UTF_8)

    data class Decoded(val listId: Long, val entryId: Long, val issuedAt: Instant)

    fun encode(
        listId: Long,
        entryId: Long,
        issuedAt: Instant,
        secret: String,
    ): String {
        require(listId > 0) { "listId must be positive" }
        require(entryId > 0) { "entryId must be positive" }
        require(secret.isNotBlank()) { "secret must not be blank" }

        val ts = issuedAt.epochSecond
        require(ts >= 0) { "issuedAt must not be before epoch" }

        val message = "$listId:$entryId:$ts"
        val hmacHex = toHexLower(hmacSha256(message, deriveKey(secret)))
        val shortHex = hmacHex.substring(0, HMAC_TRUNC_HEX_LEN)

        // Пример: G_123_456_1730012345_deadbeefcafebabe
        return "G_${listId}_${entryId}_${ts}_$shortHex"
    }

    fun verify(
        token: String,
        now: Instant,
        ttl: Duration,
        secret: String,
        maxClockSkew: Duration = Duration.ofMinutes(2),
    ): Decoded? {
        if (secret.isBlank() || ttl.isZero || ttl.isNegative || maxClockSkew.isNegative) return null
        if (token.length > 64) return null

        val parts = token.split('_')
        if (parts.size != 5 || parts[0] != PREFIX) return null

        val listId = parts[1].toLongOrNull() ?: return null
        val entryId = parts[2].toLongOrNull() ?: return null
        val issuedSec = parts[3].toLongOrNull() ?: return null
        val providedHex = parts[4]
        if (listId <= 0 || entryId <= 0 || issuedSec < 0) return null
        if (providedHex.length != HMAC_TRUNC_HEX_LEN) return null

        val issuedAt = Instant.ofEpochSecond(issuedSec)
        if (issuedAt.isAfter(now.plus(maxClockSkew))) return null

        val message = "$listId:$entryId:$issuedSec"
        val expectedHex = toHexLower(hmacSha256(message, deriveKey(secret))).substring(0, HMAC_TRUNC_HEX_LEN)
        if (!constantTimeEqualsHex(expectedHex, providedHex)) return null

        val age = if (now.isBefore(issuedAt)) Duration.ZERO else Duration.between(issuedAt, now)
        if (age > ttl) return null

        return Decoded(listId, entryId, issuedAt)
    }

    // --- crypto helpers ---

    private fun deriveKey(secret: String): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGO)
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), HMAC_ALGO))
        return mac.doFinal(KEY_LABEL_BYTES)
    }

    private fun hmacSha256(message: String, key: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGO)
        mac.init(SecretKeySpec(key, HMAC_ALGO))
        return mac.doFinal(message.toByteArray(StandardCharsets.UTF_8))
    }

    private fun toHexLower(bytes: ByteArray): String {
        val hexChars = CharArray(bytes.size * 2)
        var i = 0
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            hexChars[i++] = HEX[v ushr 4]
            hexChars[i++] = HEX[v and 0x0F]
        }
        return String(hexChars)
    }

    private fun constantTimeEqualsHex(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var res = 0
        for (i in a.indices) res = res or (a[i].code xor b[i].code)
        return res == 0
    }

    private val HEX = charArrayOf(
        '0','1','2','3','4','5','6','7','8','9','a','b','c','d','e','f'
    )
}
