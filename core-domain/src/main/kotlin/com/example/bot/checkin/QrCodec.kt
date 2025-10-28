package com.example.bot.checkin

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Utility for encoding and decoding guest list QR codes.
 *
 * QR format: `GL1:<clubId>:<eventId>:E:<entryId>:S:<hmac>` where `hmac`
 * is a Base64URL-encoded HMAC-SHA256 of the base string
 * `GL1:<clubId>:<eventId>:E:<entryId>` using the provided key.
 */
private const val PREFIX = "GL1"
private const val ENTRY_MARK = "E"
private const val SIG_MARK = "S"
private const val PARTS_COUNT = 7
private const val BASE_PARTS = 5
private const val PREFIX_IDX = 0
private const val CLUB_IDX = 1
private const val EVENT_IDX = 2
private const val ENTRY_TAG_IDX = 3
private const val ENTRY_IDX = 4
private const val SIG_TAG_IDX = 5
private const val HMAC_IDX = 6

class QrCodec(private val key: ByteArray) {
    /** Data extracted from a QR code. */
    data class Data(val clubId: Long, val eventId: Long, val entryId: Long)

    private fun mac(): Mac =
        Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(key, algorithm))
        }

    /** Encodes the given [data] into a QR string. */
    fun encode(data: Data): String {
        val base = "GL1:${data.clubId}:${data.eventId}:E:${data.entryId}"
        val hmac = mac().doFinal(base.toByteArray(StandardCharsets.UTF_8))
        val hmacBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(hmac)
        return "$base:S:$hmacBase64"
    }

    /**
     * Decodes and verifies the given [qr] string.
     * Returns [Data] on success or `null` if the QR is invalid or signature mismatch.
     */
    fun decode(qr: String): Data? {
        val parts = qr.split(":")
        if (!hasValidHeader(parts)) return null

        val clubId = parts[CLUB_IDX].toLongOrNull()
        val eventId = parts[EVENT_IDX].toLongOrNull()
        val entryId = parts[ENTRY_IDX].toLongOrNull()
        val provided = runCatching { Base64.getUrlDecoder().decode(parts[HMAC_IDX]) }.getOrNull()
        val base = parts.take(BASE_PARTS).joinToString(":")
        val expected = mac().doFinal(base.toByteArray(StandardCharsets.UTF_8))
        val valid =
            clubId != null &&
                eventId != null &&
                entryId != null &&
                provided != null &&
                MessageDigest.isEqual(provided, expected)
        return if (valid) Data(clubId!!, eventId!!, entryId!!) else null
    }

    private fun hasValidHeader(parts: List<String>) =
        parts.size == PARTS_COUNT &&
            parts[PREFIX_IDX] == PREFIX &&
            parts[ENTRY_TAG_IDX] == ENTRY_MARK &&
            parts[SIG_TAG_IDX] == SIG_MARK
}
