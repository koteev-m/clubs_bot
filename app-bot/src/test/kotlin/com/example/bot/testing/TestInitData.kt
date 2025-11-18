package com.example.bot.testing

import com.example.bot.webapp.TEST_BOT_TOKEN
import com.example.bot.webapp.WebAppInitDataTestHelper
import io.ktor.client.request.HttpRequestBuilder

/**
 * Утилиты initData для тестов. initData подписывается тем же токеном, что и боевое окружение.
 */
public fun createInitData(
    userId: Long = 123_456_789L,
    username: String? = "test_user",
    clubId: Long? = null,
): String {
    val params =
        linkedMapOf(
            "user" to WebAppInitDataTestHelper.encodeUser(id = userId, username = username),
            "auth_date" to System.currentTimeMillis().div(1000).toString(),
        )

    if (clubId != null) {
        params += "state" to """{"clubId":$clubId}"""
    }

    return WebAppInitDataTestHelper.createInitData(TEST_BOT_TOKEN, params)
}

/**
 * Вешаем оба заголовка-алиаса, чтобы покрыть X-Telegram-Init-Data и X-Telegram-InitData.
 */
public fun HttpRequestBuilder.withInitData(initData: String) {
    headers.append("X-Telegram-Init-Data", initData)
    headers.append("X-Telegram-InitData", initData)
}
