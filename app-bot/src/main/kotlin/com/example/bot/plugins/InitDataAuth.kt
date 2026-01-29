package com.example.bot.plugins

import com.example.bot.http.ErrorCodes
import com.example.bot.http.ensureMiniAppNoStoreHeaders
import com.example.bot.security.auth.InitDataValidator
import com.example.bot.security.auth.TelegramUser
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.DuplicatePluginException
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.contentType
import io.ktor.server.request.header
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.util.AttributeKey
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.Serial

val MiniAppUserKey: AttributeKey<TelegramMiniUser> = AttributeKey("miniapp.user")
internal val MiniAppAuthErrorHandledKey: AttributeKey<Boolean> =
    AttributeKey("miniapp.auth.error.handled")

@Serializable
data class TelegramMiniUser(
    val id: Long,
    val firstName: String? = null,
    val lastName: String? = null,
    val username: String? = null,
)

private val logger = LoggerFactory.getLogger("InitDataAuth")

private var validator: (String, String) -> TelegramMiniUser? = { raw, token ->
    InitDataValidator.validate(raw, token)?.toMiniUser()
}

internal class MiniAppAuthAbort : RuntimeException() {
    companion object {
        @Serial
        private const val serialVersionUID: Long = 1L
    }
}

private class MiniAppAuthConfig {
    lateinit var botTokenProvider: () -> String
    // Если true — отсутствие initData не считается ошибкой (например, на публичных маршрутах).
    var allowMissingInitData: Boolean = false
    // Если true — можно пытаться извлекать initData из JSON/form body.
    var allowInitDataFromBody: Boolean = true
    // Максимальный размер body для чтения initData (Content-Length).
    var maxInitDataBodyBytes: Long = 8_192
}

private val MiniAppAuth =
    createRouteScopedPlugin("MiniAppAuth", ::MiniAppAuthConfig) {
        val tokenProvider = pluginConfig.botTokenProvider

        onCall { call ->
            val initData =
                extractInitData(
                    call,
                    allowInitDataFromBody = pluginConfig.allowInitDataFromBody,
                    maxInitDataBodyBytes = pluginConfig.maxInitDataBodyBytes,
                )
            if (initData.isNullOrBlank()) {
                if (pluginConfig.allowMissingInitData) {
                    return@onCall
                }
                call.respondUnauthorized("initData missing")
                throw MiniAppAuthAbort()
            }

            val botToken = runCatching { tokenProvider() }.getOrElse { "" }
            if (botToken.isBlank()) {
                call.respondUnauthorized("bot token missing")
                throw MiniAppAuthAbort()
            }

            val user =
                runCatching { validator(initData, botToken) }
                    .onFailure { logger.warn("initData validation threw", it) }
                    .getOrNull()

            if (user == null) {
                call.respondUnauthorized("initData invalid")
                throw MiniAppAuthAbort()
            }

            call.attributes.put(MiniAppUserKey, user)
        }
    }

// -------- идемпотентность установки --------

private val MiniAppAuthRouteMarker = AttributeKey<Boolean>("miniapp.auth.installed.route")

fun Route.withMiniAppAuth(
    allowMissingInitData: Boolean = false,
    allowInitDataFromBody: Boolean = true,
    maxInitDataBodyBytes: Long = 8_192,
    botTokenProvider: () -> String,
) {
    if (attributes.contains(MiniAppAuthRouteMarker)) return
    try {
        install(MiniAppAuth) {
            this.botTokenProvider = botTokenProvider
            this.allowMissingInitData = allowMissingInitData
            this.allowInitDataFromBody = allowInitDataFromBody
            this.maxInitDataBodyBytes = maxInitDataBodyBytes
        }
    } catch (_: DuplicatePluginException) {
        // уже установлен выше
    }
    attributes.put(MiniAppAuthRouteMarker, true)
}

fun Application.installMiniAppAuthStatusPage() {
    if (pluginOrNull(StatusPages) == null) {
        install(StatusPages) {
            exception<MiniAppAuthAbort> { _, _ -> }
        }
    }
}

internal fun overrideMiniAppValidatorForTesting(override: (String, String) -> TelegramMiniUser?) {
    validator = override
}

internal fun resetMiniAppValidator() {
    validator = { raw, token -> InitDataValidator.validate(raw, token)?.toMiniUser() }
}

private fun TelegramUser.toMiniUser(): TelegramMiniUser = TelegramMiniUser(id = id, username = username)

// -------- helpers --------

private suspend fun extractInitData(
    call: ApplicationCall,
    allowInitDataFromBody: Boolean,
    maxInitDataBodyBytes: Long,
): String? {
    val h =
        call.request.header("X-Telegram-Init-Data")?.takeIf { it.isNotBlank() }
            ?: call.request.header("X-Telegram-InitData")?.takeIf { it.isNotBlank() }
    val q = call.request.queryParameters["initData"]?.takeIf { it.isNotBlank() }

    if (!h.isNullOrBlank()) return h
    if (!q.isNullOrBlank()) return q
    if (!allowInitDataFromBody) return null
    return extractInitDataFromBodyOrNull(call, maxInitDataBodyBytes)
}

private suspend fun ApplicationCall.respondUnauthorized(reason: String) {
    logger.info("Mini App request unauthorized: {}", reason)
    ensureMiniAppNoStoreHeaders()
    attributes.put(MiniAppAuthErrorHandledKey, true)
    respond(
        HttpStatusCode.Unauthorized,
        mapOf(
            "error" to reason,
            "message" to reason,
            "code" to ErrorCodes.unauthorized,
        ),
    )
}

private suspend fun extractInitDataFromBodyOrNull(
    call: ApplicationCall,
    maxInitDataBodyBytes: Long,
): String? {
    return try {
        val contentLengthHeader = call.request.header(HttpHeaders.ContentLength) ?: return null
        val contentLength = contentLengthHeader.toLongOrNull() ?: return null
        if (contentLength > maxInitDataBodyBytes) {
            return null
        }
        when {
            call.request.contentType().match(ContentType.Application.FormUrlEncoded) ->
                call.receiveParameters()["initData"]

            call.request.contentType().match(ContentType.Application.Json) -> {
                val body = call.receiveText()
                INIT_DATA_REGEX.find(body)?.groupValues?.getOrNull(1)
            }

            else -> null
        }
    } catch (_: Throwable) {
        null
    }
}

private val INIT_DATA_REGEX = "\"initData\"\\s*:\\s*\"([^\"]+)\"".toRegex()
