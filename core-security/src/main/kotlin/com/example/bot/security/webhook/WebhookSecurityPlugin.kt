package com.example.bot.security.webhook

import com.example.bot.config.BotLimits
import com.example.bot.data.security.webhook.DedupResult
import com.example.bot.data.security.webhook.SuspiciousIpReason
import com.example.bot.data.security.webhook.SuspiciousIpRepository
import com.example.bot.data.security.webhook.WebhookUpdateDedupRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.request.contentType
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.util.AttributeKey
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Job
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.io.ByteArrayOutputStream
import kotlin.text.Charsets

const val TELEGRAM_SECRET_HEADER: String = "X-Telegram-Bot-Api-Secret-Token"
private const val IDEMPOTENCY_HEADER = "Idempotency-Key"
private const val IDEMPOTENCY_MDC_KEY = "idempotency_key"
private const val UPDATE_ID_MDC_KEY = "update_id"
private const val READ_BUFFER_SIZE = 8 * 1024
private val webhookBodyKey = AttributeKey<ByteArray>("webhook.raw.body")
private val updateIdKey = AttributeKey<Long>("webhook.update.id")
private val idempotencyKeyAttr = AttributeKey<String>("webhook.idempotency.key")

class WebhookSecurityConfig {
    var requireSecret: Boolean = true
    var secretToken: String? = null
    var maxBodySizeBytes: Long = BotLimits.Webhook.maxPayloadBytes
    var dedupRepository: WebhookUpdateDedupRepository? = null
    var suspiciousIpRepository: SuspiciousIpRepository? = null
    var duplicateSuspicionThreshold: Int = BotLimits.Webhook.duplicateSuspiciousThreshold
    var json: Json = Json { ignoreUnknownKeys = true }
}

val WebhookSecurity =
    createRouteScopedPlugin(name = "WebhookSecurity", createConfiguration = ::WebhookSecurityConfig) {
        val logger = LoggerFactory.getLogger("WebhookSecurity")
        val state =
            WebhookSecurityState(
                requireSecret = pluginConfig.requireSecret,
                secretToken = pluginConfig.secretToken,
                maxBodySize = pluginConfig.maxBodySizeBytes,
                dedupRepository =
                    pluginConfig.dedupRepository
                        ?: error("WebhookUpdateDedupRepository must be provided"),
                suspiciousRepository =
                    pluginConfig.suspiciousIpRepository
                        ?: error("SuspiciousIpRepository must be provided"),
                duplicateThreshold = pluginConfig.duplicateSuspicionThreshold,
                json = pluginConfig.json,
                logger = logger,
            )

        onCall { call ->
            val forwarded = call.request.header(HttpHeaders.XForwardedFor)?.substringBefore(',')?.trim()
            val remoteIp =
                forwarded?.takeIf { it.isNotEmpty() }
                    ?: call.request.header("X-Real-IP")?.takeIf { it.isNotBlank() }
                    ?: "unknown"
            val userAgent = call.request.header(HttpHeaders.UserAgent)

            if (call.request.httpMethod != HttpMethod.Post) {
                state.recordSuspicious(
                    remoteIp,
                    userAgent,
                    SuspiciousIpReason.INVALID_METHOD,
                    call.request.httpMethod.value,
                )
                call.respondText("Method Not Allowed", status = HttpStatusCode.MethodNotAllowed)
                return@onCall
            }

            if (!state.checkSecret(call, remoteIp, userAgent)) {
                return@onCall
            }

            val contentType = call.request.contentType().withoutParameters()
            if (contentType != ContentType.Application.Json) {
                state.recordSuspicious(
                    remoteIp,
                    userAgent,
                    SuspiciousIpReason.INVALID_CONTENT_TYPE,
                    contentType.toString(),
                )
                call.respondText("Unsupported content type", status = HttpStatusCode.UnsupportedMediaType)
                return@onCall
            }

            val contentLength = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
            if (contentLength != null && contentLength > state.maxBodySize) {
                state.recordSuspicious(
                    remoteIp,
                    userAgent,
                    SuspiciousIpReason.PAYLOAD_TOO_LARGE,
                    contentLength.toString(),
                )
                call.respondText("Payload too large", status = HttpStatusCode.PayloadTooLarge)
                return@onCall
            }

            val channel = call.receiveChannel()
            val bodyBytes = channel.readBodyLimited(state.maxBodySize)
            if (bodyBytes == null) {
                state.recordSuspicious(remoteIp, userAgent, SuspiciousIpReason.PAYLOAD_TOO_LARGE, "stream")
                call.respondText("Payload too large", status = HttpStatusCode.PayloadTooLarge)
                return@onCall
            }
            val payload = bodyBytes.toString(Charsets.UTF_8)
            if (payload.isBlank()) {
                state.recordSuspicious(remoteIp, userAgent, SuspiciousIpReason.EMPTY_BODY, null)
                call.respondText("Empty body", status = HttpStatusCode.BadRequest)
                return@onCall
            }

            val updateId = state.extractUpdateId(payload)
            if (updateId == null) {
                state.recordSuspicious(remoteIp, userAgent, SuspiciousIpReason.MALFORMED_JSON, null)
                call.respondText("Invalid payload", status = HttpStatusCode.BadRequest)
                return@onCall
            }

            call.attributes.put(webhookBodyKey, bodyBytes)
            call.attributes.put(updateIdKey, updateId)
            call.extractIdempotencyKey()?.let { key -> call.attributes.put(idempotencyKeyAttr, key) }

            val mdcKeys = state.applyMdc(call)
            state.registerMdcCleanup(call, mdcKeys)
            when (val result = state.dedupRepository.mark(updateId)) {
                is DedupResult.FirstSeen -> Unit
                is DedupResult.Duplicate -> {
                    state.logger.debug("Duplicate update {} from {}", updateId, remoteIp)
                    if (result.duplicateCount >= state.duplicateThreshold) {
                        state.recordSuspicious(
                            remoteIp,
                            userAgent,
                            SuspiciousIpReason.DUPLICATE_UPDATE,
                            "count=${result.duplicateCount}",
                        )
                    }
                    call.respondText("Duplicate update", status = HttpStatusCode.Conflict)
                    return@onCall
                }
            }
        }
    }

private data class WebhookSecurityState(
    val requireSecret: Boolean,
    val secretToken: String?,
    val maxBodySize: Long,
    val dedupRepository: WebhookUpdateDedupRepository,
    val suspiciousRepository: SuspiciousIpRepository,
    val duplicateThreshold: Int,
    val json: Json,
    val logger: Logger,
) {
    suspend fun checkSecret(
        call: ApplicationCall,
        remoteIp: String,
        userAgent: String?,
    ): Boolean {
        if (!requireSecret) {
            return true
        }
        val provided = call.request.header(TELEGRAM_SECRET_HEADER)
        val expected = secretToken
        val valid = provided != null && (expected == null || provided == expected)
        if (!valid) {
            val details = if (provided == null) "missing" else "mismatch"
            recordSuspicious(remoteIp, userAgent, SuspiciousIpReason.SECRET_MISMATCH, details)
            call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
        }
        return valid
    }

    suspend fun recordSuspicious(
        ip: String,
        userAgent: String?,
        reason: SuspiciousIpReason,
        details: String?,
    ) {
        suspiciousRepository.record(ip, userAgent, reason, details)
        logger.warn("suspicious_ip reason={} ip={} details={}", reason, ip, details)
    }

    fun extractUpdateId(payload: String): Long? {
        return try {
            val element: JsonElement = json.parseToJsonElement(payload)
            element.jsonObject["update_id"]?.jsonPrimitive?.long
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun applyMdc(call: ApplicationCall): List<String> {
        val keys = mutableListOf<String>()
        val idempotency =
            call.attributeOrNull(idempotencyKeyAttr)
                ?: call.parameters["idempotency_key"]
        if (idempotency != null) {
            MDC.put(IDEMPOTENCY_MDC_KEY, idempotency)
            keys += IDEMPOTENCY_MDC_KEY
        }
        val updateId = call.attributeOrNull(updateIdKey)
        if (updateId != null) {
            MDC.put(UPDATE_ID_MDC_KEY, updateId.toString())
            keys += UPDATE_ID_MDC_KEY
        }
        return keys
    }

    fun clearMdc(keys: List<String>) {
        keys.forEach { MDC.remove(it) }
    }

    fun registerMdcCleanup(
        call: ApplicationCall,
        keys: List<String>,
    ) {
        if (keys.isEmpty()) {
            return
        }
        val job = call.coroutineContext[Job]
        if (job == null) {
            clearMdc(keys)
        } else {
            job.invokeOnCompletion { clearMdc(keys) }
        }
    }
}

private suspend fun ByteReadChannel.readBodyLimited(limit: Long): ByteArray? {
    require(limit <= Int.MAX_VALUE) { "Limit $limit exceeds supported buffer size" }
    val buffer = ByteArray(READ_BUFFER_SIZE)
    val output = ByteArrayOutputStream()
    var totalRead = 0L
    while (true) {
        val read = readAvailable(buffer, 0, buffer.size)
        if (read == -1) {
            break
        }
        if (read > 0) {
            totalRead += read
            if (totalRead > limit) {
                return null
            }
            output.write(buffer, 0, read)
        }
    }
    return output.toByteArray()
}

private fun ApplicationCall.extractIdempotencyKey(): String? {
    return request.header(IDEMPOTENCY_HEADER)?.takeIf { it.isNotBlank() }
        ?: parameters["idempotency_key"]?.takeIf { it.isNotBlank() }
}

fun ApplicationCall.webhookRawBody(): ByteArray = attributes[webhookBodyKey]

fun ApplicationCall.webhookUpdateId(): Long = attributes[updateIdKey]

fun ApplicationCall.webhookIdempotencyKey(): String? = attributeOrNull(idempotencyKeyAttr)

fun ApplicationCall.hasWebhookPayload(): Boolean = attributes.contains(webhookBodyKey)

private fun <T : Any> ApplicationCall.attributeOrNull(key: AttributeKey<T>): T? =
    if (attributes.contains(key)) attributes[key] else null
