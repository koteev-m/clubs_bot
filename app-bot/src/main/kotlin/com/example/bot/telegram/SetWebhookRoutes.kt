package com.example.bot.telegram

import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * Administrative routes for managing webhook configuration.
 */
fun Route.telegramSetupRoutes(
    client: TelegramClient,
    baseUrl: String,
    secret: String,
    maxConnections: Int,
    allowedUpdates: List<String>,
) {
    get("/telegram/setup-webhook") {
        val url = "$baseUrl/webhook"
        val resp = client.setWebhook(url, secret, maxConnections, allowedUpdates)
        call.respond(mapOf("ok" to resp.isOk))
    }

    get("/telegram/delete-webhook") {
        val drop = call.request.queryParameters["drop"]?.toBoolean() ?: false
        val resp = client.deleteWebhook(drop)
        call.respond(mapOf("ok" to resp.isOk))
    }

    get("/telegram/webhook-info") {
        val info = client.getWebhookInfo()
        call.respond(mapOf("info" to info.toString()))
    }
}
