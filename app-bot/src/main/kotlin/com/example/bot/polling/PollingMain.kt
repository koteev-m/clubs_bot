package com.example.bot.polling

import com.example.bot.config.AppConfig
import com.example.bot.dedup.UpdateDeduplicator
import com.example.bot.telegram.TelegramClient
import com.pengrad.telegrambot.model.Update
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("PollingRunner")

private val allowedUpdates =
    listOf(
        "message",
        "edited_message",
        "callback_query",
        "contact",
        "pre_checkout_query",
        "successful_payment",
    )

/** Runs the long polling loop using [client] and [handler]. */
class PollingRunner(
    private val client: TelegramClient,
    private val handler: suspend (Update) -> Unit,
    private val deduplicator: UpdateDeduplicator = UpdateDeduplicator(),
) {
    private var offset: Long = 0

    suspend fun runOnce() {
        val updates = client.getUpdates(offset, allowedUpdates)
        updates.forEach { upd ->
            val id = upd.updateId().toLong()
            if (deduplicator.isDuplicate(id)) {
                logger.debug("duplicate {}", id)
            } else {
                handler(upd)
                offset = maxOf(offset, id + 1)
            }
        }
    }
}

/** Entry point that starts the polling runner in an infinite loop. */
object PollingMain {
    @JvmStatic
    fun main(args: Array<String>) {
        runBlocking {
            val config = AppConfig.fromEnv()
            val token = config.bot.token
            val apiUrl = config.localApi.baseUrl.takeIf { config.localApi.enabled }
            val client = TelegramClient(token, apiUrl)
            val runner = PollingRunner(client, handler = { /* integrate domain handlers here */ })
            while (true) runner.runOnce()
        }
    }
}
