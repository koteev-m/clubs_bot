package com.example.bot.webapp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal const val TEST_BOT_TOKEN: String = "111111:TEST_BOT_TOKEN"

internal object WebAppInitDataTestHelper {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class TelegramUser(
        val id: Long,
        val username: String? = null,
        @SerialName("first_name") val firstName: String? = null,
        @SerialName("last_name") val lastName: String? = null,
    )

    fun encodeUser(
        id: Long,
        username: String? = null,
        firstName: String? = null,
        lastName: String? = null,
    ): String {
        return json.encodeToString(TelegramUser.serializer(), TelegramUser(id, username, firstName, lastName))
    }

    fun createInitData(
        botToken: String,
        rawParams: Map<String, String>,
    ): String {
        val secretKey =
            hmacSha256(
                botToken.toByteArray(StandardCharsets.UTF_8),
                "WebAppData".toByteArray(StandardCharsets.UTF_8),
            )
        val dataCheckString =
            rawParams
                .filterKeys { it != "hash" }
                .toSortedMap()
                .entries
                .joinToString("\n") { (key, value) -> "$key=$value" }
        val hash = hexLower(hmacSha256(secretKey, dataCheckString.toByteArray(StandardCharsets.UTF_8)))
        val encodedPairs =
            rawParams.entries.joinToString("&") { (key, value) ->
                "${URLEncoder.encode(key, StandardCharsets.UTF_8)}=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
            }
        return "$encodedPairs&hash=$hash"
    }

    private fun hmacSha256(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun hexLower(bytes: ByteArray): String {
        val builder = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            builder.append(Character.forDigit(value ushr 4, 16))
            builder.append(Character.forDigit(value and 0x0F, 16))
        }
        return builder.toString()
    }
}
