package com.example.bot.deprecated.legacy.web

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

fun interface LegacyHqNotifier {
    suspend fun notify(textHtml: String)
}

object NoopLegacyHqNotifier : LegacyHqNotifier {
    override suspend fun notify(textHtml: String) = Unit
}

class TelegramLegacyHqNotifier(
    private val token: String,
    private val chatId: String,
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
) : LegacyHqNotifier {
    override suspend fun notify(textHtml: String) {
        val request = buildRequest(textHtml)
        var lastStatusCode: Int? = null
        var lastFailure: IOException? = null

        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val response = send(request)
                lastStatusCode = response.statusCode()
                lastFailure = null
                if (response.statusCode() in HTTP_SUCCESS_STATUS_CODES) {
                    return
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw CancellationException("Legacy HQ notify interrupted").also { it.initCause(e) }
            } catch (e: IOException) {
                lastFailure = e
                lastStatusCode = null
            }

            if (attempt == MAX_ATTEMPTS - 1) {
                logger.warn(
                    "Legacy HQ notify failed: status={} transportFailure={} attempt={}",
                    lastStatusCode ?: UNKNOWN_STATUS_CODE,
                    lastFailure?.javaClass?.simpleName ?: "none",
                    attempt + 1,
                )
            }
        }
    }

    private suspend fun send(request: HttpRequest): HttpResponse<Void> =
        withContext(Dispatchers.IO) {
            client.send(request, HttpResponse.BodyHandlers.discarding())
        }

    private fun buildRequest(textHtml: String): HttpRequest {
        val payload =
            "chat_id=${urlEncode(chatId)}&parse_mode=HTML&disable_web_page_preview=true&text=${urlEncode(textHtml)}"
        return HttpRequest.newBuilder()
            .uri(URI.create("https://api.telegram.org/bot$token/sendMessage"))
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private companion object {
        private val logger = LoggerFactory.getLogger(TelegramLegacyHqNotifier::class.java)
        private const val MAX_ATTEMPTS: Int = 2
        private const val HTTP_SUCCESS_STATUS_MIN: Int = 200
        private const val HTTP_SUCCESS_STATUS_MAX: Int = 299
        private const val UNKNOWN_STATUS_CODE: Int = -1
        private const val REQUEST_TIMEOUT_SECONDS: Long = 3
        private val HTTP_SUCCESS_STATUS_CODES = HTTP_SUCCESS_STATUS_MIN..HTTP_SUCCESS_STATUS_MAX
    }
}
