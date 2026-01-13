package com.example.bot.telegram

import com.pengrad.telegrambot.model.Update

class TelegramCallbackRouter(
    private val supportHandler: suspend (Update) -> Unit,
    private val invitationHandler: suspend (Update) -> Unit,
) {
    suspend fun route(update: Update) {
        val callbackData = update.callbackQuery()?.data()
        when {
            callbackData == null -> invitationHandler(update)
            SupportCallbacks.isRateCallback(callbackData) -> supportHandler(update)
            callbackData.startsWith(InvitationTelegramHandler.CALLBACK_CONFIRM_PREFIX) ||
                callbackData.startsWith(InvitationTelegramHandler.CALLBACK_DECLINE_PREFIX) ->
                invitationHandler(update)
            else -> Unit
        }
    }
}
