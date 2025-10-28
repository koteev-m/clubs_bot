package com.example.notifications.support

import com.example.bot.telegram.MediaSpec
import com.example.bot.telegram.SendResult

/** Simple in-memory fake of sender that records sent messages. */
class FakeNotifySender {
    data class Sent(val timestamp: Long, val chatId: Long, val method: String)

    val sent = mutableListOf<Sent>()
    private val scripted = ArrayDeque<SendResult>()

    fun enqueue(result: SendResult) {
        scripted.add(result)
    }

    private fun nextResult(): SendResult = scripted.removeFirstOrNull() ?: SendResult.Ok(messageId = null)

    suspend fun sendMessage(
        chatId: Long,
        text: String,
        threadId: Int? = null,
        dedupKey: String? = null,
    ): SendResult {
        sent += Sent(System.currentTimeMillis(), chatId, "message")
        return nextResult()
    }

    suspend fun sendPhoto(
        chatId: Long,
        photoUrlOrFileId: String,
        caption: String? = null,
        threadId: Int? = null,
        dedupKey: String? = null,
    ): SendResult {
        sent += Sent(System.currentTimeMillis(), chatId, "photo")
        return nextResult()
    }

    suspend fun sendMediaGroup(
        chatId: Long,
        media: List<MediaSpec>,
        threadId: Int? = null,
        dedupKey: String? = null,
    ): SendResult {
        sent += Sent(System.currentTimeMillis(), chatId, "mediaGroup")
        return nextResult()
    }
}

/** Placeholder utility for seeding outbox messages in tests. */
object TestOutboxSeeder
