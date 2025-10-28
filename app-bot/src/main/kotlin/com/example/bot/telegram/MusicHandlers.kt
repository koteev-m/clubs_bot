package com.example.bot.telegram

import com.example.bot.music.MusicCatalogService
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.request.SendAudio
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.response.BaseResponse

/** Telegram handlers for music commands. */
class MusicHandlers(private val send: suspend (Any) -> BaseResponse, private val service: MusicCatalogService) {
    /** Handles /music command by listing recent items. */
    suspend fun handle(update: Update) {
        val msg = update.message() ?: return
        val chatId = msg.chat().id()
        val items = service.listItems(MusicCatalogService.ItemFilter(limit = 5))
        for (item in items) {
            val fileId = item.telegramFileId
            if (fileId != null) {
                val req = SendAudio(chatId, fileId).caption(item.title)
                send(req)
            } else if (item.sourceUrl != null) {
                val req = SendMessage(chatId, "${item.title}\n${item.sourceUrl}")
                send(req)
            }
        }
    }
}
