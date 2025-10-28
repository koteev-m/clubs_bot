package com.example.bot.telegram

import com.example.bot.promo.PromoAttributionService
import com.example.bot.promo.PromoLinkIssueResult
import com.example.bot.promo.PromoStartResult
import com.example.bot.text.BotTexts
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.response.BaseResponse

/**
 * Handles guest flow interactions for Telegram updates.
 */
@Suppress("UnusedPrivateMember")
class GuestFlowHandler(
    private val send: suspend (Any) -> BaseResponse,
    private val texts: BotTexts,
    private val keyboards: Keyboards,
    private val promoService: PromoAttributionService,
) {
    /**
     * Processes incoming [update] and reacts to supported commands.
     */
    suspend fun handle(update: Update) {
        val msg = update.message() ?: return
        val chatId = msg.chat().id()
        val lang = msg.from()?.languageCode()
        val text = msg.text() ?: return
        when {
            text.startsWith("/start") -> {
                val parts = text.split(" ", limit = 2)
                val token = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
                val fromId = msg.from()?.id()
                if (token != null && fromId != null) {
                    val result = promoService.registerStart(fromId, token)
                    val ack =
                        when (result) {
                            PromoStartResult.Stored -> "Промо отмечена ✅"
                            PromoStartResult.Invalid -> "Некорректная промо-ссылка"
                        }
                    send(SendMessage(chatId, ack))
                }
            }

            text.equals("Моя промо-ссылка", ignoreCase = true) -> {
                val fromId = msg.from()?.id()
                val response =
                    if (fromId != null) {
                        when (val issued = promoService.issuePromoLink(fromId)) {
                            is PromoLinkIssueResult.Success -> "Твоя промо-ссылка: /start ${issued.token}"
                            PromoLinkIssueResult.NotAuthorized -> "Команда доступна только промоутерам"
                        }
                    } else {
                        "Команда доступна только промоутерам"
                    }
                send(SendMessage(chatId, response))
            }
        }
    }
}
