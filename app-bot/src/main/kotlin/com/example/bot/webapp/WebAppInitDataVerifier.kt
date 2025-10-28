package com.example.bot.webapp

import io.ktor.http.decodeURLPart
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val MAX_INIT_DATA_LENGTH = 8192
private const val HEX_RADIX = 16
private const val HALF_BYTE_SHIFT = 4
private const val FUTURE_TOLERANCE_SECONDS = 60L
private const val HEX_PAIR_LENGTH = 2
private val DEFAULT_MAX_AGE: Duration = Duration.ofHours(24)

@Serializable
private data class TelegramUserPayload(
    val id: Long,
    val username: String? = null,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
)

private val json = Json { ignoreUnknownKeys = true }

private fun hmacSha256(
    data: ByteArray,
    key: ByteArray,
): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
}

private fun constantTimeEquals(
    first: ByteArray,
    second: ByteArray,
): Boolean {
    if (first.size != second.size) {
        return false
    }
    var result = 0
    for (index in first.indices) {
        result = result or (first[index].toInt() xor second[index].toInt())
    }
    return result == 0
}

private fun hexToBytes(hex: String): ByteArray? {
    if (hex.length % HEX_PAIR_LENGTH != 0) {
        return null
    }
    val bytes = ByteArray(hex.length / HEX_PAIR_LENGTH)
    var invalid = false
    for (index in bytes.indices) {
        val first = Character.digit(hex[index * HEX_PAIR_LENGTH], HEX_RADIX)
        val second = Character.digit(hex[index * HEX_PAIR_LENGTH + 1], HEX_RADIX)
        if (first == -1 || second == -1) {
            invalid = true
            break
        }
        bytes[index] = ((first shl HALF_BYTE_SHIFT) + second).toByte()
    }
    return if (invalid) null else bytes
}

private fun buildDataCheckString(parameters: Map<String, String>): String {
    return parameters
        .toSortedMap()
        .entries
        .joinToString("\n") { (key, value) -> "$key=$value" }
}

private fun decodeSegment(segment: String): Pair<String, String>? {
    val delimiterIndex = segment.indexOf('=')
    if (delimiterIndex <= 0) {
        return null
    }
    val keyEncoded = segment.substring(0, delimiterIndex)
    val valueEncoded = segment.substring(delimiterIndex + 1)
    val key = runCatching { keyEncoded.decodeURLPart() }.getOrNull()
    val value = runCatching { valueEncoded.decodeURLPart() }.getOrNull()
    return if (key == null || value == null) {
        null
    } else {
        key to value
    }
}

private fun parseQuery(initData: String): Map<String, String>? {
    if (initData.isEmpty()) {
        return emptyMap()
    }
    val result = mutableMapOf<String, String>()
    var invalid = false
    for (segment in initData.split('&')) {
        if (segment.isNotEmpty()) {
            val decoded = decodeSegment(segment)
            if (decoded == null) {
                invalid = true
            } else {
                result[decoded.first] = decoded.second
            }
            if (invalid) {
                break
            }
        }
    }
    return if (invalid) null else result
}

private fun parseUser(payload: String?): TelegramUserPayload? {
    if (payload.isNullOrBlank()) {
        return null
    }
    return runCatching { json.decodeFromString(TelegramUserPayload.serializer(), payload) }.getOrNull()
}

private fun isExpired(
    authDate: Instant,
    clock: Clock,
    maxAge: Duration,
): Boolean {
    val now = clock.instant()
    return authDate.isBefore(now.minus(maxAge)) || authDate.isAfter(now.plusSeconds(FUTURE_TOLERANCE_SECONDS))
}

private fun String.toInstantOrNull(): Instant? {
    val epochSeconds = this.toLongOrNull() ?: return null
    return runCatching { Instant.ofEpochSecond(epochSeconds) }.getOrNull()
}

private fun secretKey(botToken: String): ByteArray {
    return hmacSha256(
        "WebAppData".toByteArray(StandardCharsets.UTF_8),
        botToken.toByteArray(StandardCharsets.UTF_8),
    )
}

object WebAppInitDataVerifier {
    fun verify(
        initData: String,
        botToken: String,
        maxAge: Duration = DEFAULT_MAX_AGE,
        clock: Clock = Clock.systemUTC(),
    ): VerifiedInitData? {
        if (initData.length > MAX_INIT_DATA_LENGTH) {
            return null
        }
        return buildVerification(initData, botToken, maxAge, clock)
    }

    private fun buildVerification(
        initData: String,
        botToken: String,
        maxAge: Duration,
        clock: Clock,
    ): VerifiedInitData? {
        val parameters = parseQuery(initData) ?: return null
        val mutableParameters = parameters.toMutableMap()
        val hashValue = mutableParameters.remove("hash")
        var valid = true
        if (hashValue.isNullOrBlank() || mutableParameters.isEmpty()) {
            valid = false
        }
        val dataCheckString = buildDataCheckString(mutableParameters)
        val secret = secretKey(botToken)
        val calculatedHash = hmacSha256(dataCheckString.toByteArray(StandardCharsets.UTF_8), secret)
        val providedHash = hashValue?.let { hexToBytes(it.lowercase()) }
        val macMatches = providedHash?.let { constantTimeEquals(calculatedHash, it) } ?: false
        if (!macMatches) {
            valid = false
        }
        val authDateString = mutableParameters["auth_date"]
        val authDate = authDateString?.toInstantOrNull()
        if (authDate == null || isExpired(authDate, clock, maxAge)) {
            valid = false
        }
        val userPayload = parseUser(mutableParameters["user"])
        if (userPayload == null) {
            valid = false
        }
        return if (!valid || authDate == null || userPayload == null) {
            null
        } else {
            VerifiedInitData(
                userId = userPayload.id,
                username = userPayload.username,
                firstName = userPayload.firstName,
                lastName = userPayload.lastName,
                authDate = authDate,
                raw = mutableParameters.toMap(),
            )
        }
    }
}

data class VerifiedInitData(
    val userId: Long,
    val username: String?,
    val firstName: String?,
    val lastName: String?,
    val authDate: Instant,
    val raw: Map<String, String>,
)
