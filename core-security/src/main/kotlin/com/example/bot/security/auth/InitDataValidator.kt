package com.example.bot.security.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Validates Telegram Mini App init data according to official algorithm.
 */
object InitDataValidator {
    private val json = Json

    /**
     * Parses and validates raw init data. Returns user information if valid, or null otherwise.
     */
    fun validate(
        initData: String,
        botToken: String,
    ): TelegramUser? {
        val params =
            initData
                .split('&')
                .map { part ->
                    val idx = part.indexOf('=')
                    if (idx == -1) {
                        part to ""
                    } else {
                        val key = part.substring(0, idx)
                        val value = part.substring(idx + 1)
                        key to URLDecoder.decode(value, StandardCharsets.UTF_8)
                    }
                }.toMap()
        val hash = params["hash"]
        val userJson = params["user"]
        if (hash == null || userJson == null) {
            return null
        }
        val dataCheckString =
            params
                .filterKeys { it != "hash" }
                .toList()
                .sortedBy { it.first }
                .joinToString("\n") { "${it.first}=${it.second}" }
        val secretKey = MessageDigest.getInstance("SHA-256").digest(botToken.toByteArray())
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretKey, "HmacSHA256"))
        val check = mac.doFinal(dataCheckString.toByteArray())
        val hex = check.joinToString("") { "%02x".format(it) }
        return if (hex == hash) {
            json.decodeFromString(TelegramUser.serializer(), userJson)
        } else {
            null
        }
    }
}

/**
 * Minimal representation of Telegram user contained in init data.
 */
@Serializable
data class TelegramUser(val id: Long, val username: String? = null)
