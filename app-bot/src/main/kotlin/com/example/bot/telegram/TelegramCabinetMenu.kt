package com.example.bot.telegram

import com.pengrad.telegrambot.model.MenuButtonWebApp
import com.pengrad.telegrambot.model.WebAppInfo
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.request.SetChatMenuButton
import com.pengrad.telegrambot.response.BaseResponse
import java.net.URI
import java.net.URISyntaxException
import kotlin.coroutines.cancellation.CancellationException

/** The default menu also covers recipients who open a direct invitation without sending /start. */
internal suspend fun installDefaultCabinetMenu(
    configuredMiniAppUrl: String?,
    send: suspend (BaseRequest<*, *>) -> BaseResponse,
): Boolean {
    val url = cabinetMenuUrl(configuredMiniAppUrl) ?: return false
    return try {
        // No chat_id changes the bot's default menu. Every launch still checks signed server authority.
        send(SetChatMenuButton().menuButton(MenuButtonWebApp("Открыть кабинет", WebAppInfo(url)))).isOk
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // Telegram errors may carry credentials; the caller emits only a fixed category.
        false
    }
}

private fun cabinetMenuUrl(value: String?): String? =
    parseCabinetMenuUri(value)?.takeIf(::isSafeCabinetMenuUri)?.let { it.toASCIIString() + "?mode=cabinet" }

private fun parseCabinetMenuUri(value: String?): URI? =
    try {
        value?.let(::URI)
    } catch (_: URISyntaxException) {
        null
    }

private fun isSafeCabinetMenuUri(uri: URI): Boolean =
    when {
        uri.scheme != "https" -> false
        uri.host.isNullOrBlank() || uri.rawUserInfo != null -> false
        uri.rawQuery != null || uri.rawFragment != null -> false
        uri.port == 0 || uri.port > MAX_CABINET_URL_PORT -> false
        else -> true
    }

private const val MAX_CABINET_URL_PORT = 65_535
