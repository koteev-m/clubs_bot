package com.example.bot.deprecated.legacy.web

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

fun interface LegacyHqNotifier { fun notify(textHtml: String) }

object NoopLegacyHqNotifier : LegacyHqNotifier { override fun notify(textHtml: String) = Unit }

class TelegramLegacyHqNotifier(
    private val token: String,
    private val chatId: String,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
) : LegacyHqNotifier {
    override fun notify(textHtml: String) {
        repeat(2) { attempt ->
            runCatching {
                val payload = "chat_id=${URLEncoder.encode(chatId, "UTF-8")}&parse_mode=HTML&disable_web_page_preview=true&text=${URLEncoder.encode(textHtml, "UTF-8")}"
                val request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.telegram.org/bot$token/sendMessage"))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build()
                client.send(request, HttpResponse.BodyHandlers.discarding())
            }.onSuccess { return }.onFailure { if (attempt == 1) return }
        }
    }
}
