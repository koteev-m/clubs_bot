package com.example.bot.testing

import io.ktor.client.request.HttpRequestBuilder
import java.util.Base64
import kotlin.text.Charsets.UTF_8

/**
 * Утилиты initData для тестов. Подпись не проверяется (профиль TEST).
 */
public fun createInitData(
    userId: Long = 123_456_789L,
    username: String? = "test_user",
    clubId: Long? = null,
): String {
    val encoder = Base64.getEncoder()
    val userJson =
        buildString {
            append('{')
            append("\"id\":")
            append(userId)
            if (username != null) {
                append(',')
                append("\"username\":\"")
                append(username)
                append('\"')
            }
            append('}')
        }

    val parts = mutableListOf("user=${encoder.encodeToString(userJson.toByteArray(UTF_8))}")
    clubId?.let { club ->
        val stateJson = """{"clubId":$club}"""
        parts += "state=${encoder.encodeToString(stateJson.toByteArray(UTF_8))}"
    }
    return parts.joinToString("&")
}

/**
 * Вешаем оба заголовка-алиаса, чтобы покрыть X-Telegram-Init-Data и X-Telegram-InitData.
 */
public fun HttpRequestBuilder.withInitData(initData: String) {
    headers.append("X-Telegram-Init-Data", initData)
    headers.append("X-Telegram-InitData", initData)
}
