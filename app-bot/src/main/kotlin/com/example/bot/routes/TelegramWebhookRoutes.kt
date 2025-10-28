package com.example.bot.routes

import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.utility.BotUtils
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory

private const val SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token"

fun Application.telegramWebhookRoutes(
    bot: TelegramBot,
    expectedSecret: String?,
    onUpdate: (Update) -> Unit,
) {
    val logger = LoggerFactory.getLogger("TelegramWebhookRoutes")

    routing {
        post("/telegram/webhook") {
            if (!expectedSecret.isNullOrBlank()) {
                val providedSecret = call.request.header(SECRET_HEADER)
                if (providedSecret != expectedSecret) {
                    logger.warn("webhook: invalid secret token")
                    call.respond(HttpStatusCode.Unauthorized)
                    return@post
                }
            }

            val body = call.receiveText()
            val update = runCatching { BotUtils.parseUpdate(body) }.getOrNull()
            if (update == null) {
                logger.warn("webhook: invalid update payload")
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }

            runCatching { onUpdate(update) }
                .onFailure { t -> logger.warn("webhook: handler failed: {}", t.toString()) }

            call.respond(HttpStatusCode.OK, "OK")
        }
    }
}
