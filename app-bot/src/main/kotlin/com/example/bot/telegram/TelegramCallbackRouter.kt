package com.example.bot.telegram

import com.pengrad.telegrambot.model.Update

class TelegramCallbackRouter(
    private val supportHandler: suspend (Update) -> Unit,
    private val invitationHandler: suspend (Update) -> Unit,
    private val guestFallbackHandler: suspend (Update) -> Boolean,
    private val paymentsHandler: PaymentsHandlers,
) {
    suspend fun route(update: Update) {
        update.preCheckoutQuery()?.let {
            paymentsHandler.handlePreCheckout(it)
            return
        }

        val message = update.message()
        if (message?.successfulPayment() != null) {
            paymentsHandler.handleSuccessfulPayment(message)
            return
        }

        val callbackData = update.callbackQuery()?.data()
        when {
            callbackData == null -> {
                val handledByFallback = guestFallbackHandler(update)
                if (!handledByFallback) {
                    invitationHandler(update)
                }
            }
            SupportCallbacks.isRateCallback(callbackData) -> supportHandler(update)
            callbackData.startsWith(InvitationTelegramHandler.CALLBACK_CONFIRM_PREFIX) ||
                callbackData.startsWith(InvitationTelegramHandler.CALLBACK_DECLINE_PREFIX) ->
                invitationHandler(update)
            callbackData.startsWith("ask:club:") -> guestFallbackHandler(update)
            else -> Unit
        }
    }
}
