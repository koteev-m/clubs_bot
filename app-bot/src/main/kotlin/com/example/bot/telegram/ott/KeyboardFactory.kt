@file:Suppress("SpreadOperator")

package com.example.bot.telegram.ott

import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup

object KeyboardFactory {
    /**
     * Пример: клавиатура со столами, где в callback_data — одноразовый токен.
     * @param items пары (label, payload)
     */
    fun tableKeyboard(
        service: CallbackTokenService,
        items: List<Pair<String, BookTableAction>>,
    ): InlineKeyboardMarkup {
        val buttons =
            items.map { (label, payload) ->
                val token = service.issueToken(payload)
                InlineKeyboardButton(label).callbackData(token)
            }
        // Разложим по рядам по 2 кнопки
        val rows = buttons.chunked(2).map { it.toTypedArray() }.toTypedArray()
        return InlineKeyboardMarkup(*rows)
    }
}
