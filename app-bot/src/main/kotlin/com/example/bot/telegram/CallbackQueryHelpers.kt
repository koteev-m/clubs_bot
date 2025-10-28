package com.example.bot.telegram

import com.pengrad.telegrambot.model.CallbackQuery

/**
 * Centralized access to the deprecated Java API in pengrad.
 *
 * Ktlint forbids suppressions on individual arguments in a call, so we keep a single helper
 * to unwrap the optional message and thread identifier from the callback query.
 */
@Suppress("DEPRECATION")
fun extractChatAndThread(cq: CallbackQuery): Pair<Long?, Int?> {
    val msg = cq.message() ?: return null to null
    val chatId = msg.chat()?.id()
    val threadId = runCatching { msg.messageThreadId() }.getOrNull()
    return chatId to threadId
}
