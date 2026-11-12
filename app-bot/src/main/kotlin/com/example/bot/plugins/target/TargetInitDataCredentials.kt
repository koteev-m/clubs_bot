package com.example.bot.plugins.target

import io.ktor.http.Headers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Instant

/** Strict TARGET-only parsing. HMAC stays exclusively in the existing InitDataValidator. */
internal object TargetInitDataCredentials {
    const val MAX_AGE_SECONDS = 300L
    private const val MAX_BYTES = 8192
    private const val HEX_RADIX = 16
    private const val CANONICAL_HEADER = "X-Telegram-Init-Data"
    private val alternateHeaders = setOf("X-Telegram-InitData", "X-Init-Data", "Authorization")
    private val keyPattern = Regex("[a-zA-Z0-9_]+")
    private val integerPattern = Regex("0|[1-9][0-9]*")
    private val hashPattern = Regex("[0-9a-fA-F]{64}")
    private val credentialKeys = setOf("user", "hash", "auth_date", "signature")

    fun header(
        headers: Headers,
        rawQuery: String,
    ): String? {
        val values = headers.getAll(CANONICAL_HEADER).orEmpty()
        val invalid = rawQuery.isNotEmpty() || alternateHeaders.any(headers::contains) || values.size != 1
        if (invalid) return null
        return values.single().takeIf { value ->
            value.isNotEmpty() && value.length <= MAX_BYTES && value.all { it in '!'..'~' && it != ',' }
        }
    }

    fun parse(raw: String): TargetInitDataFacts {
        val fields = fields(raw)
        val userText = requireNotNull(fields["user"])
        val date = requireNotNull(fields["auth_date"]?.takeIf(integerPattern::matches)?.toLongOrNull())
        require(hashPattern.matches(fields["hash"].orEmpty()) && TargetJsonKeys.unique(userText))
        val user = requireNotNull(Json.parseToJsonElement(userText) as? JsonObject)
        val id = requireNotNull(user["id"] as? JsonPrimitive)
        val userId = requireNotNull(id.takeIf { !it.isString && integerPattern.matches(it.content) }?.longOrNull)
        require(userId > 0)
        val at = Instant.ofEpochSecond(date)
        return TargetInitDataFacts(userId, at, at.plusSeconds(MAX_AGE_SECONDS))
    }

    private fun fields(raw: String): Map<String, String> {
        require(raw.isNotEmpty() && raw.length <= MAX_BYTES && raw.all { it in '!'..'~' })
        val result = mutableMapOf<String, String>()
        for (part in raw.split('&')) {
            val separator = part.indexOf('=')
            require(separator > 0)
            val key = part.substring(0, separator)
            val canonicalKey = key.lowercase(java.util.Locale.ROOT)
            val ambiguous = canonicalKey in credentialKeys && canonicalKey != key
            require(keyPattern.matches(key) && !ambiguous && key !in result)
            val value = decode(part.substring(separator + 1))
            require(value.none { it < ' ' || it == '\u007f' })
            result[key] = value
        }
        return result
    }

    private fun decode(value: String): String {
        val bytes = ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val character = value[index]
            when (character) {
                '%' -> {
                    require(index + 2 < value.length)
                    val high = requireNotNull(value[index + 1].digitToIntOrNull(HEX_RADIX))
                    val low = requireNotNull(value[index + 2].digitToIntOrNull(HEX_RADIX))
                    bytes.write(high * HEX_RADIX + low)
                    index += 2
                }
                '+' -> bytes.write(' '.code)
                else -> bytes.write(character.code)
            }
            index++
        }
        return StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes.toByteArray()))
            .toString()
    }
}
