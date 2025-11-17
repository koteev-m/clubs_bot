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

private fun hmacSha256(data: ByteArray, key: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data)
}

private fun constantTimeEquals(first: ByteArray, second: ByteArray): Boolean {
    if (first.size != second.size) return false
    var r = 0
    for (i in first.indices) r = r or (first[i].toInt() xor second[i].toInt())
    return r == 0
}

private fun hexToBytes(hex: String): ByteArray? {
    if (hex.length % HEX_PAIR_LENGTH != 0) return null
    val out = ByteArray(hex.length / HEX_PAIR_LENGTH)
    for (i in out.indices) {
        val a = Character.digit(hex[i * 2], HEX_RADIX)
        val b = Character.digit(hex[i * 2 + 1], HEX_RADIX)
        if (a == -1 || b == -1) return null
        out[i] = ((a shl HALF_BYTE_SHIFT) + b).toByte()
    }
    return out
}

private fun buildDataCheckString(parameters: Map<String, String>): String =
    parameters.toSortedMap().entries.joinToString("\n") { (k, v) -> "$k=$v" }

private fun decodeSegment(segment: String): Pair<String, String>? {
    val idx = segment.indexOf('=')
    if (idx <= 0) return null
    val k = runCatching { segment.substring(0, idx).decodeURLPart() }.getOrNull()
    val v = runCatching { segment.substring(idx + 1).decodeURLPart() }.getOrNull()
    return if (k == null || v == null) null else k to v
}

private fun parseQuery(initData: String): Map<String, String>? {
    if (initData.isEmpty()) return emptyMap()
    val result = mutableMapOf<String, String>()
    for (seg in initData.split('&')) {
        if (seg.isNotEmpty()) {
            val kv = decodeSegment(seg) ?: return null
            result[kv.first] = kv.second
        }
    }
    return result
}

private fun parseUser(payload: String?): TelegramUserPayload? =
    payload?.takeIf { it.isNotBlank() }?.let {
        runCatching { json.decodeFromString(TelegramUserPayload.serializer(), it) }.getOrNull()
    }

private fun isExpired(authDate: Instant, clock: Clock, maxAge: Duration): Boolean {
    val now = clock.instant()
    return authDate.isBefore(now.minus(maxAge)) || authDate.isAfter(now.plusSeconds(FUTURE_TOLERANCE_SECONDS))
}

private fun String.toInstantOrNull(): Instant? =
    this.toLongOrNull()?.let { runCatching { Instant.ofEpochSecond(it) }.getOrNull() }

private fun secretKey(botToken: String): ByteArray =
    hmacSha256("WebAppData".toByteArray(StandardCharsets.UTF_8), botToken.toByteArray(StandardCharsets.UTF_8))

object WebAppInitDataVerifier {
    fun verify(
        initData: String,
        botToken: String,
        maxAge: Duration = DEFAULT_MAX_AGE,
        clock: Clock = Clock.systemUTC(),
    ): VerifiedInitData? {
        if (initData.length > MAX_INIT_DATA_LENGTH) return null
        return buildVerification(initData, botToken, maxAge, clock)
    }

    private fun buildVerification(
        initData: String,
        botToken: String,
        maxAge: Duration,
        clock: Clock,
    ): VerifiedInitData? {
        val params = parseQuery(initData) ?: return null
        val mutable = params.toMutableMap()
        val hash = mutable.remove("hash") ?: return null
        if (mutable.isEmpty()) return null

        val dataCheckString = buildDataCheckString(mutable)
        val secret = secretKey(botToken)
        val calcHash = hmacSha256(dataCheckString.toByteArray(StandardCharsets.UTF_8), secret)
        val provided = hexToBytes(hash.lowercase()) ?: return null
        if (!constantTimeEquals(calcHash, provided)) return null

        val authDate = mutable["auth_date"]?.toInstantOrNull() ?: return null
        if (isExpired(authDate, clock, maxAge)) return null

        val user = parseUser(mutable["user"]) ?: return null

        return VerifiedInitData(
            userId = user.id,
            username = user.username,
            firstName = user.firstName,
            lastName = user.lastName,
            authDate = authDate,
            raw = mutable.toMap()
        )
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
