package com.example.bot.deprecated.legacy.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

fun interface LegacyHqNotifier { suspend fun notify(textHtml: String) }

object NoopLegacyHqNotifier : LegacyHqNotifier { override suspend fun notify(textHtml: String) = Unit }

class TelegramLegacyHqNotifier(
    private val token: String,
    private val chatId: String,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
) : LegacyHqNotifier {
    override suspend fun notify(textHtml: String) {
        val request = buildRequest(textHtml)
        repeat(MAX_ATTEMPTS) { attempt ->
            val response = runCatching {
                withContext(Dispatchers.IO) {
                    client.send(request, HttpResponse.BodyHandlers.discarding())
                }
            }
            val result = response.getOrNull()
            if (result != null && result.statusCode() in 200..299) {
                return
            }
            if (attempt == MAX_ATTEMPTS - 1) {
                val code = result?.statusCode() ?: -1
                logger.warn("Legacy HQ notify failed: status={} attempt={}", code, attempt + 1)
            }
        }
    }

    private fun buildRequest(textHtml: String): HttpRequest {
        val payload =
            "chat_id=${urlEncode(chatId)}&parse_mode=HTML&disable_web_page_preview=true&text=${urlEncode(textHtml)}"
        return HttpRequest.newBuilder()
            .uri(URI.create("https://api.telegram.org/bot$token/sendMessage"))
            .timeout(Duration.ofSeconds(3))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private companion object {
        private val logger = LoggerFactory.getLogger(TelegramLegacyHqNotifier::class.java)
        private const val MAX_ATTEMPTS: Int = 2
    }
}
