package com.example.bot.payments.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Клиент провайдера рефандов.
 * Сетевые таймауты и URL/токен берутся из ENV см. [ProviderRefundClientConfig.fromEnv].
 */
fun interface ProviderRefundClient {
    suspend fun send(command: ProviderRefundCommand): ProviderRefundResult
}

/** Контракт пингаемости провайдера (health). */
interface ProviderRefundHealth {
    /** Должен бросить исключение, если провайдер недоступен/отвечает не 2xx. */
    suspend fun ping()
}

/** Команда провайдеру: событие + JSON-payload + идемпотентный ключ. */
data class ProviderRefundCommand(
    val topic: String,
    val payload: JsonObject,
    val idempotencyKey: String,
)

/** Результат отправки: Success | Retry(429/5xx) | Failure(4xx). */
sealed interface ProviderRefundResult {
    data object Success : ProviderRefundResult

    data class Retry(
        val status: Int?,
        val retryAfter: Duration?,
        val body: String? = null,
        val cause: Throwable? = null,
    ) : ProviderRefundResult

    data class Failure(val status: Int, val body: String?) : ProviderRefundResult
}

/**
 * Конфигурация клиента: URL, токен и таймауты.
 * ENV:
 *  - REFUND_PROVIDER_URL (обязателен)
 *  - REFUND_PROVIDER_TOKEN ИЛИ REFUND_PROVIDER_API_KEY (обязателен хотя бы один)
 *  - REFUND_HTTP_CONNECT_TIMEOUT_MS / REFUND_HTTP_REQUEST_TIMEOUT_MS / REFUND_HTTP_READ_TIMEOUT_MS (опционально)
 *  - REFUND_PROVIDER_HEALTH_PATH (опционально, по умолчанию "/health")
 */
data class ProviderRefundClientConfig(
    val url: String,
    val token: String,
    val connectTimeout: Duration = Duration.ofSeconds(3),
    val requestTimeout: Duration = Duration.ofSeconds(10),
    val readTimeout: Duration = Duration.ofSeconds(10),
    val healthPath: String = "/health",
) {
    companion object {
        private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
        private fun envMs(name: String, default: Long): Duration =
            env(name)?.toLongOrNull()?.let(Duration::ofMillis) ?: Duration.ofMillis(default)

        fun fromEnv(): ProviderRefundClientConfig {
            val rawUrl: String = env("REFUND_PROVIDER_URL")
                ?: error("REFUND_PROVIDER_URL is not configured")
            val token: String = env("REFUND_PROVIDER_TOKEN")
                ?: env("REFUND_PROVIDER_API_KEY")
                ?: error("REFUND_PROVIDER_TOKEN is not configured (or REFUND_PROVIDER_API_KEY)")

            val url = rawUrl.trim().removeSuffix("/")
            val healthPath = env("REFUND_PROVIDER_HEALTH_PATH")?.let { it.trim().ifEmpty { "/health" } } ?: "/health"

            return ProviderRefundClientConfig(
                url = url,
                token = token,
                connectTimeout = envMs("REFUND_HTTP_CONNECT_TIMEOUT_MS", 3_000),
                requestTimeout = envMs("REFUND_HTTP_REQUEST_TIMEOUT_MS", 10_000),
                readTimeout = envMs("REFUND_HTTP_READ_TIMEOUT_MS", 10_000),
                healthPath = if (healthPath.startsWith("/")) healthPath else "/$healthPath",
            )
        }
    }
}

/**
 * HTTP-реализация на Ktor CIO.
 * JSON — через ContentNegotiation, таймауты — через HttpTimeout.
 */
class HttpProviderRefundClient(
    private val config: ProviderRefundClientConfig,
    private val clock: Clock = Clock.systemUTC(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    },
) : ProviderRefundClient, ProviderRefundHealth {

    private val logger = LoggerFactory.getLogger(HttpProviderRefundClient::class.java)

    private val client: HttpClient =
        HttpClient(CIO) {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                connectTimeoutMillis = config.connectTimeout.toMillis()
                requestTimeoutMillis = config.requestTimeout.toMillis()
                socketTimeoutMillis = config.readTimeout.toMillis()
            }
            // Не включаем подробное логирование тел/заголовков, чтобы не утечь секретами.
        }

    override suspend fun send(command: ProviderRefundCommand): ProviderRefundResult {
        return try {
            val response: HttpResponse =
                client.post(config.url) {
                    applyCommonHeaders(this, command)
                    contentType(ContentType.Application.Json)
                    setBody(ProviderRefundRequest(event = command.topic, payload = command.payload))
                }
            mapResponse(response)
        } catch (ex: Exception) {
            logger.warn("Provider refund call failed: {}", ex.toString())
            ProviderRefundResult.Retry(status = null, retryAfter = null, cause = ex)
        }
    }

    /** Health-пинг: GET {baseUrl}{healthPath}. Бросаем исключение при не-2xx. */
    override suspend fun ping() {
        val response: HttpResponse =
            client.get(config.url + config.healthPath) {
                header(HttpHeaders.Authorization, "Bearer ${config.token}")
                header(HttpHeaders.Accept, ContentType.Application.Json)
            }
        val code = response.status.value
        if (code !in 200..299) {
            val body = runCatching { response.bodyAsText() }.getOrNull()
            throw IllegalStateException("Refund provider health is not OK: HTTP $code, body=$body")
        }
    }

    /** Общие заголовки для POST-запроса. */
    private fun applyCommonHeaders(
        builder: HttpRequestBuilder,
        command: ProviderRefundCommand,
    ) {
        builder.header(HttpHeaders.Authorization, "Bearer ${config.token}")
        builder.header("Idempotency-Key", command.idempotencyKey)
        builder.header(HttpHeaders.Accept, ContentType.Application.Json)
    }

    /** Маппинг ответа: 2xx → Success; 429/5xx → Retry; остальное → Failure. */
    private suspend fun mapResponse(response: HttpResponse): ProviderRefundResult {
        val status: Int = response.status.value
        val body: String? = runCatching { response.bodyAsText() }.getOrNull()

        return when {
            status in 200..299 -> ProviderRefundResult.Success
            status == 429 -> ProviderRefundResult.Retry(status, parseRetryAfter(response), body)
            status >= 500 -> ProviderRefundResult.Retry(status, parseRetryAfter(response), body)
            else -> ProviderRefundResult.Failure(status, body)
        }
    }

    /**
     * Разбор Retry-After:
     *  - целое число секунд,
     *  - или HTTP-дата (RFC 7231 / RFC 1123).
     */
    private fun parseRetryAfter(response: HttpResponse): Duration? {
        val header: String = response.headers[HttpHeaders.RetryAfter] ?: return null
        header.toLongOrNull()?.let { seconds -> return Duration.ofSeconds(seconds.coerceAtLeast(0)) }

        return runCatching {
            val whenAllowed: Instant = Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(header))
            val now: Instant = Instant.now(clock)
            if (whenAllowed.isAfter(now)) Duration.between(now, whenAllowed) else Duration.ZERO
        }.getOrNull()
    }

    @Serializable
    private data class ProviderRefundRequest(
        val event: String,
        val payload: JsonObject,
    )
}
