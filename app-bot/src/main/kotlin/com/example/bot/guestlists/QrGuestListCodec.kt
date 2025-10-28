package com.example.bot.guestlists

import java.nio.charset.StandardCharsets
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Результат успешной верификации QR-токена.
 */
data class QrDecoded(
    val listId: Long,
    val entryId: Long,
    val issuedAt: Instant,
)

/**
 * Кодек QR для гостевых списков.
 *
 * Формат токена (ASCII):
 *   GL:<listId>:<entryId>:<ts>:<hmacHexLower>
 *
 * где:
 *   - <listId>, <entryId>, <ts> — десятичные целые (unsigned-диапазон проверяем в коде)
 *   - <ts> — UNIX epoch seconds, когда был создан токен
 *   - <hmacHexLower> — hex нижним регистром HMAC_SHA256(message="<listId>:<entryId>:<ts>",
 *       key=HMAC_SHA256("QrGuestList", secret))
 *
 * Примеры:
 *   GL:12345:67890:1732390400:0a1b2c... (64 hex)
 */
object QrGuestListCodec {
    private const val TOKEN_PREFIX = "GL:"
    private const val TOKEN_PART_COUNT = 4
    private const val TOKEN_SEPARATOR = ':'
    private const val TOKEN_SUFFIX_SEPARATOR_COUNT = 1
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val HMAC_HEX_LENGTH = 64
    private const val HMAC_BYTE_LENGTH = 32
    private const val ZERO_CHAR_CODE = 48
    private const val NIBBLE_SHIFT = 4
    private const val BYTE_MASK = 0xFF
    private const val LOW_NIBBLE_MASK = 0x0F
    private const val HEX_ALPHA_OFFSET = 10
    private const val MIN_IDENTIFIER_VALUE = 1L
    private const val MIN_TIMESTAMP_SECONDS = 0L
    private const val MESSAGE_SEPARATOR_COUNT = 2
    private const val PART_INDEX_LIST_ID = 0
    private const val PART_INDEX_ENTRY_ID = 1
    private const val PART_INDEX_TIMESTAMP = 2
    private const val PART_INDEX_HMAC = 3
    private val KEY_LABEL_BYTES = "QrGuestList".toByteArray(StandardCharsets.UTF_8)
    private val HEX_DIGITS = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f')

    private data class ParsedToken(
        val listId: Long,
        val entryId: Long,
        val issuedAt: Instant,
        val message: String,
        val hmac: ByteArray,
    )

    /**
     * Генерация QR-токена.
     */
    fun encode(
        listId: Long,
        entryId: Long,
        issuedAt: Instant,
        secret: String,
    ): String {
        require(listId >= MIN_IDENTIFIER_VALUE) { "listId must be positive" }
        require(entryId >= MIN_IDENTIFIER_VALUE) { "entryId must be positive" }
        require(secret.isNotEmpty()) { "secret must not be blank" }
        val timestamp = issuedAt.epochSecond
        require(timestamp >= MIN_TIMESTAMP_SECONDS) { "issuedAt must not be before epoch" }
        val message = buildMessage(listId, entryId, timestamp)
        val derivedKey = deriveKey(secret)
        val hmacHex = toHexLower(hmacSha256(message, derivedKey))
        val capacity = message.length + TOKEN_PREFIX.length + TOKEN_SUFFIX_SEPARATOR_COUNT + hmacHex.length
        return buildString(capacity) {
            append(TOKEN_PREFIX)
            append(message)
            append(TOKEN_SEPARATOR)
            append(hmacHex)
        }
    }

    /**
     * Проверка токена.
     */
    fun verify(
        token: String,
        now: Instant,
        ttl: Duration,
        secret: String,
        maxClockSkew: Duration = Duration.ofMinutes(2),
    ): QrDecoded? {
        val secretPresent = secret.isNotEmpty()
        val ttlValid = !ttl.isZero && !ttl.isNegative
        val skewValid = !maxClockSkew.isNegative
        val parametersValid = secretPresent && ttlValid && skewValid
        val parsed =
            if (parametersValid) {
                parseToken(token)
            } else {
                null
            }
        return parsed?.let { parsedToken ->
            val derivedKey = deriveKey(secret)
            val expectedHmac = hmacSha256(parsedToken.message, derivedKey)
            val withinSkew = !parsedToken.issuedAt.isAfter(now.plus(maxClockSkew))
            val hmacValid = constantTimeEquals(expectedHmac, parsedToken.hmac)
            val age =
                if (now.isBefore(parsedToken.issuedAt)) {
                    Duration.ZERO
                } else {
                    Duration.between(parsedToken.issuedAt, now)
                }
            val notExpired = age.compareTo(ttl) <= 0
            if (withinSkew && hmacValid && notExpired) {
                QrDecoded(parsedToken.listId, parsedToken.entryId, parsedToken.issuedAt)
            } else {
                null
            }
        }
    }

    private fun parseToken(token: String): ParsedToken? {
        if (!token.startsWith(TOKEN_PREFIX)) {
            return null
        }
        val withoutPrefix = token.substring(TOKEN_PREFIX.length)
        val parts = withoutPrefix.split(TOKEN_SEPARATOR)
        var invalid = parts.size != TOKEN_PART_COUNT || parts.any { it.isEmpty() }
        var listId = 0L
        var entryId = 0L
        var timestamp = 0L
        var issuedAt: Instant? = null
        var hmac: ByteArray? = null
        if (!invalid) {
            val parsedListId = parseDecimal(parts[PART_INDEX_LIST_ID], MIN_IDENTIFIER_VALUE)
            if (parsedListId == null) {
                invalid = true
            } else {
                listId = parsedListId
            }
        }
        if (!invalid) {
            val parsedEntryId = parseDecimal(parts[PART_INDEX_ENTRY_ID], MIN_IDENTIFIER_VALUE)
            if (parsedEntryId == null) {
                invalid = true
            } else {
                entryId = parsedEntryId
            }
        }
        if (!invalid) {
            val parsedTimestamp = parseDecimal(parts[PART_INDEX_TIMESTAMP], MIN_TIMESTAMP_SECONDS)
            if (parsedTimestamp == null) {
                invalid = true
            } else {
                timestamp = parsedTimestamp
            }
        }
        if (!invalid) {
            val decodedHmac = decodeHex(parts[PART_INDEX_HMAC])
            if (decodedHmac == null) {
                invalid = true
            } else {
                hmac = decodedHmac
            }
        }
        if (!invalid) {
            issuedAt =
                try {
                    Instant.ofEpochSecond(timestamp)
                } catch (_: DateTimeException) {
                    invalid = true
                    null
                }
        }
        return if (!invalid && issuedAt != null && hmac != null) {
            ParsedToken(listId, entryId, issuedAt, buildMessage(listId, entryId, timestamp), hmac)
        } else {
            null
        }
    }

    private fun buildMessage(
        listId: Long,
        entryId: Long,
        timestamp: Long,
    ): String {
        val listIdString = listId.toString()
        val entryIdString = entryId.toString()
        val timestampString = timestamp.toString()
        val capacity = listIdString.length + entryIdString.length + timestampString.length + MESSAGE_SEPARATOR_COUNT
        return buildString(capacity) {
            append(listIdString)
            append(TOKEN_SEPARATOR)
            append(entryIdString)
            append(TOKEN_SEPARATOR)
            append(timestampString)
        }
    }

    private fun deriveKey(secret: String): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        val keySpec = SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), HMAC_ALGORITHM)
        mac.init(keySpec)
        return mac.doFinal(KEY_LABEL_BYTES)
    }

    private fun hmacSha256(
        message: String,
        key: ByteArray,
    ): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        val keySpec = SecretKeySpec(key, HMAC_ALGORITHM)
        mac.init(keySpec)
        return mac.doFinal(message.toByteArray(StandardCharsets.UTF_8))
    }

    private fun toHexLower(bytes: ByteArray): String {
        val result = CharArray(bytes.size * 2)
        var index = 0
        for (byte in bytes) {
            val value = byte.toInt() and BYTE_MASK
            result[index++] = HEX_DIGITS[value ushr NIBBLE_SHIFT]
            result[index++] = HEX_DIGITS[value and LOW_NIBBLE_MASK]
        }
        return String(result)
    }

    private fun decodeHex(hex: String): ByteArray? {
        if (hex.length != HMAC_HEX_LENGTH) {
            return null
        }
        val result = ByteArray(HMAC_BYTE_LENGTH)
        var invalid = false
        var index = 0
        while (index < HMAC_HEX_LENGTH) {
            if (!invalid) {
                val high =
                    when (val ch = hex[index]) {
                        in '0'..'9' -> ch.code - ZERO_CHAR_CODE
                        in 'a'..'f' -> ch.code - 'a'.code + HEX_ALPHA_OFFSET
                        in 'A'..'F' -> ch.code - 'A'.code + HEX_ALPHA_OFFSET
                        else -> -1
                    }
                val low =
                    when (val ch = hex[index + 1]) {
                        in '0'..'9' -> ch.code - ZERO_CHAR_CODE
                        in 'a'..'f' -> ch.code - 'a'.code + HEX_ALPHA_OFFSET
                        in 'A'..'F' -> ch.code - 'A'.code + HEX_ALPHA_OFFSET
                        else -> -1
                    }
                if (high < 0 || low < 0) {
                    invalid = true
                } else {
                    result[index / 2] = ((high shl NIBBLE_SHIFT) or low).toByte()
                }
            }
            index += 2
        }
        return if (!invalid) {
            result
        } else {
            null
        }
    }

    private fun parseDecimal(
        value: String,
        minimum: Long,
    ): Long? {
        if (value.isEmpty() || value.any { it !in '0'..'9' }) {
            return null
        }
        val parsed = value.toLongOrNull()
        return if (parsed != null && parsed >= minimum) {
            parsed
        } else {
            null
        }
    }

    private fun constantTimeEquals(
        a: ByteArray,
        b: ByteArray,
    ): Boolean {
        if (a.size != b.size) {
            return false
        }
        var diff = 0
        for (index in a.indices) {
            diff = diff or (a[index].toInt() xor b[index].toInt())
        }
        return diff == 0
    }
}
