package com.example.bot.promoter.invites

import java.nio.charset.StandardCharsets
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object PromoterInviteQrCodec {
    private const val TOKEN_PREFIX = "INV:"
    private const val TOKEN_SEPARATOR = ':'
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val HMAC_HEX_LENGTH = 64
    private const val MIN_IDENTIFIER_VALUE = 1L
    private const val MIN_TIMESTAMP_SECONDS = 0L
    private val KEY_LABEL_BYTES = "QrPromoterInvite".toByteArray(StandardCharsets.UTF_8)
    private val HEX_DIGITS = "0123456789abcdef".toCharArray()

    data class Decoded(val inviteId: Long, val eventId: Long, val issuedAt: Instant)

    fun encode(inviteId: Long, eventId: Long, issuedAt: Instant, secret: String): String {
        require(secret.isNotEmpty()) { "secret must not be blank" }
        require(inviteId >= MIN_IDENTIFIER_VALUE) { "inviteId must be positive" }
        require(eventId >= MIN_IDENTIFIER_VALUE) { "eventId must be positive" }
        val ts = issuedAt.epochSecond
        require(ts >= MIN_TIMESTAMP_SECONDS) { "issuedAt must not be before epoch" }
        val message = "$inviteId$TOKEN_SEPARATOR$eventId$TOKEN_SEPARATOR$ts"
        val hmac = hmacSha256(message, deriveKey(secret))
        val hmacHex = toHexLower(hmac)
        return buildString(TOKEN_PREFIX.length + message.length + 1 + HMAC_HEX_LENGTH) {
            append(TOKEN_PREFIX)
            append(message)
            append(TOKEN_SEPARATOR)
            append(hmacHex)
        }
    }

    fun tryDecode(token: String, secret: String): Decoded? {
        val parsed = parse(token) ?: return null
        if (secret.isBlank()) return null
        val derivedKey = deriveKey(secret)
        val expected = hmacSha256(parsed.message, derivedKey)
        val hmacValid = constantTimeEquals(expected, parsed.hmac)
        return if (hmacValid) Decoded(parsed.inviteId, parsed.eventId, parsed.issuedAt) else null
    }

    private data class ParsedToken(
        val inviteId: Long,
        val eventId: Long,
        val issuedAt: Instant,
        val message: String,
        val hmac: ByteArray,
    )

    private fun parse(token: String): ParsedToken? {
        if (!token.startsWith(TOKEN_PREFIX)) return null
        val parts = token.removePrefix(TOKEN_PREFIX).split(TOKEN_SEPARATOR)
        if (parts.size != 4 || parts.any { it.isEmpty() }) return null
        val inviteId = parts[0].toLongOrNull() ?: return null
        val eventId = parts[1].toLongOrNull() ?: return null
        val ts = parts[2].toLongOrNull() ?: return null
        if (inviteId < MIN_IDENTIFIER_VALUE || eventId < MIN_IDENTIFIER_VALUE || ts < MIN_TIMESTAMP_SECONDS) return null
        val issuedAt = runCatching { Instant.ofEpochSecond(ts) }.getOrNull() ?: return null
        val hmac = decodeHex(parts[3]) ?: return null
        val message = "$inviteId:$eventId:$ts"
        return ParsedToken(inviteId, eventId, issuedAt, message, hmac)
    }

    private fun deriveKey(secret: String): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(KEY_LABEL_BYTES, HMAC_ALGORITHM))
        return mac.doFinal(secret.toByteArray(StandardCharsets.UTF_8))
    }

    private fun hmacSha256(message: String, key: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        return mac.doFinal(message.toByteArray(StandardCharsets.UTF_8))
    }

    private fun toHexLower(bytes: ByteArray): String {
        val result = CharArray(bytes.size * 2)
        bytes.forEachIndexed { index, b ->
            val unsigned = b.toInt() and 0xFF
            result[index * 2] = HEX_DIGITS[unsigned ushr 4]
            result[index * 2 + 1] = HEX_DIGITS[unsigned and 0x0F]
        }
        return String(result)
    }

    private fun decodeHex(value: String): ByteArray? {
        if (value.length != HMAC_HEX_LENGTH) return null
        val result = ByteArray(value.length / 2)
        var i = 0
        while (i < value.length) {
            val hi = value[i].digitToIntOrNull(16) ?: return null
            val lo = value[i + 1].digitToIntOrNull(16) ?: return null
            result[i / 2] = ((hi shl 4) or lo).toByte()
            i += 2
        }
        return result
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].toInt() xor b[i].toInt())
        }
        return diff == 0
    }
}
