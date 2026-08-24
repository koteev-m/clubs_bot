package com.example.bot.support

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

internal class FakeTelegramBotApi(
    private val botUserId: Long = DEFAULT_BOT_USER_ID,
) : AutoCloseable {
    data class RecordedRequest(
        val endpoint: String,
        val chatId: Long?,
        val text: String?,
        val replyMarkupPresent: Boolean,
        val order: Long,
    )

    private val recordedRequests = CopyOnWriteArrayList<RecordedRequest>()
    private val requestSequence = AtomicLong()
    private val closed = AtomicBoolean()
    private val threadSequence = AtomicInteger()
    private val executor: ExecutorService =
        Executors.newFixedThreadPool(HTTP_THREAD_COUNT) { task ->
            Thread(task, "fake-telegram-bot-api-${threadSequence.incrementAndGet()}").apply {
                isDaemon = true
            }
        }
    private val server: HttpServer =
        HttpServer.create(
            InetSocketAddress(InetAddress.getByName(LOOPBACK_ADDRESS), 0),
            HTTP_BACKLOG,
        )

    val port: Int
        get() = server.address.port

    /** Base URL expected by Pengrad's TelegramBot.Builder.apiUrl. */
    val apiUrl: String
        get() = "http://$LOOPBACK_ADDRESS:$port/bot"

    val requests: List<RecordedRequest>
        get() = recordedRequests.toList().sortedBy(RecordedRequest::order)

    init {
        require(botUserId > 0L) { "botUserId must be positive" }
        server.executor = executor
        server.createContext(ROOT_CONTEXT, ::handle)
        server.start()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        server.stop(IMMEDIATE_STOP_DELAY_SECONDS)
        executor.shutdownNow()
        try {
            executor.awaitTermination(EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun handle(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != POST_METHOD) {
                respond(exchange, HTTP_METHOD_NOT_ALLOWED, METHOD_NOT_ALLOWED_RESPONSE)
                return
            }

            when (endpointFrom(exchange)) {
                GET_ME_ENDPOINT -> handleGetMe(exchange)
                SEND_MESSAGE_ENDPOINT -> handleSendMessage(exchange)
                else -> respond(exchange, HTTP_NOT_FOUND, NOT_FOUND_RESPONSE)
            }
        } finally {
            exchange.close()
        }
    }

    private fun handleGetMe(exchange: HttpExchange) {
        record(
            endpoint = GET_ME_ENDPOINT,
            chatId = null,
            text = null,
            replyMarkupPresent = false,
        )
        respond(
            exchange,
            HTTP_OK,
            """{"ok":true,"result":{"id":$botUserId,"is_bot":true,"first_name":"Restart E2E Bot","username":"restart_e2e_bot"}}""",
        )
    }

    private fun handleSendMessage(exchange: HttpExchange) {
        val parameters = parseForm(exchange)
        val chatId = parameters[CHAT_ID_PARAMETER]?.toLongOrNull()
        val text = parameters[TEXT_PARAMETER]
        val order =
            record(
                endpoint = SEND_MESSAGE_ENDPOINT,
                chatId = chatId,
                text = text,
                replyMarkupPresent = parameters.containsKey(REPLY_MARKUP_PARAMETER),
            )
        respond(
            exchange,
            HTTP_OK,
            """{"ok":true,"result":{"message_id":$order,"date":$FIXED_MESSAGE_DATE,"chat":{"id":${chatId ?: 1L},"type":"private"},"text":"accepted"}}""",
        )
    }

    private fun record(
        endpoint: String,
        chatId: Long?,
        text: String?,
        replyMarkupPresent: Boolean,
    ): Long {
        val order = requestSequence.incrementAndGet()
        recordedRequests +=
            RecordedRequest(
                endpoint = endpoint,
                chatId = chatId,
                text = text,
                replyMarkupPresent = replyMarkupPresent,
                order = order,
            )
        return order
    }

    private fun endpointFrom(exchange: HttpExchange): String? =
        when {
            exchange.requestURI.path.endsWith("/$GET_ME_ENDPOINT") -> GET_ME_ENDPOINT
            exchange.requestURI.path.endsWith("/$SEND_MESSAGE_ENDPOINT") -> SEND_MESSAGE_ENDPOINT
            else -> null
        }

    private fun parseForm(exchange: HttpExchange): Map<String, String> {
        val contentType =
            exchange.requestHeaders
                .getFirst(CONTENT_TYPE_HEADER)
                ?.substringBefore(';')
                ?.trim()
        return if (contentType != FORM_CONTENT_TYPE) {
            emptyMap()
        } else {
            val bytes = exchange.requestBody.readNBytes(MAX_FORM_BODY_BYTES + 1)
            require(bytes.size <= MAX_FORM_BODY_BYTES) { "Telegram test request is too large" }
            bytes
                .toString(StandardCharsets.UTF_8)
                .split('&')
                .filter(String::isNotEmpty)
                .associate { field ->
                    val separatorIndex = field.indexOf('=')
                    val name = if (separatorIndex >= 0) field.substring(0, separatorIndex) else field
                    val value = if (separatorIndex >= 0) field.substring(separatorIndex + 1) else ""
                    decodeFormComponent(name) to decodeFormComponent(value)
                }
        }
    }

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        json: String,
    ) {
        val body = json.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set(CONTENT_TYPE_HEADER, JSON_CONTENT_TYPE)
        exchange.responseHeaders.set(CACHE_CONTROL_HEADER, NO_STORE)
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.write(body)
    }

    private companion object {
        const val LOOPBACK_ADDRESS = "127.0.0.1"
        const val ROOT_CONTEXT = "/"
        const val POST_METHOD = "POST"
        const val GET_ME_ENDPOINT = "getMe"
        const val SEND_MESSAGE_ENDPOINT = "sendMessage"
        const val CHAT_ID_PARAMETER = "chat_id"
        const val TEXT_PARAMETER = "text"
        const val REPLY_MARKUP_PARAMETER = "reply_markup"
        const val CONTENT_TYPE_HEADER = "Content-Type"
        const val CACHE_CONTROL_HEADER = "Cache-Control"
        const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded"
        const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
        const val NO_STORE = "no-store"
        const val HTTP_BACKLOG = 0
        const val HTTP_THREAD_COUNT = 4
        const val IMMEDIATE_STOP_DELAY_SECONDS = 0
        const val EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 5L
        const val MAX_FORM_BODY_BYTES = 1_048_576
        const val HTTP_OK = 200
        const val HTTP_METHOD_NOT_ALLOWED = 405
        const val HTTP_NOT_FOUND = 404
        const val FIXED_MESSAGE_DATE = 1_700_000_000
        const val DEFAULT_BOT_USER_ID = 9_000_000_001L
        const val METHOD_NOT_ALLOWED_RESPONSE =
            """{"ok":false,"error_code":405,"description":"Method Not Allowed"}"""
        const val NOT_FOUND_RESPONSE =
            """{"ok":false,"error_code":404,"description":"Not Found"}"""

        fun decodeFormComponent(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)
    }
}
