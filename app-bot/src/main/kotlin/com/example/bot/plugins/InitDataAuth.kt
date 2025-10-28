package com.example.bot.plugins

import com.example.bot.security.auth.InitDataValidator
import com.example.bot.security.auth.TelegramUser
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.application.pluginOrNull
import io.ktor.server.application.DuplicatePluginException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.contentType
import io.ktor.server.request.header
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.util.AttributeKey
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.Serial

val MiniAppUserKey: AttributeKey<TelegramMiniUser> = AttributeKey("miniapp.user")

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
}

private val MiniAppAuth =
    createRouteScopedPlugin("MiniAppAuth", ::MiniAppAuthConfig) {
        val tokenProvider = pluginConfig.botTokenProvider

        onCall { call ->
            val initData = extractInitData(call)
            if (initData.isNullOrBlank()) {
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

/* -------- идемпотентность установки -------- */

private val MiniAppAuthRouteMarker = AttributeKey<Boolean>("miniapp.auth.installed.route")
private val MiniAppAuthAppMarker  = AttributeKey<Boolean>("miniapp.auth.installed.app")

fun Route.withMiniAppAuth(botTokenProvider: () -> String) {
    if (attributes.contains(MiniAppAuthRouteMarker)) return
    val app = this.application
    if (app.attributes.getOrNull(MiniAppAuthAppMarker) == true) {
        attributes.put(MiniAppAuthRouteMarker, true)
        return
    }
    try {
        install(MiniAppAuth) { this.botTokenProvider = botTokenProvider }
    } catch (_: DuplicatePluginException) {
        // уже установлен выше
    }
    attributes.put(MiniAppAuthRouteMarker, true)
    if (app.attributes.getOrNull(MiniAppAuthAppMarker) != true) {
        app.attributes.put(MiniAppAuthAppMarker, true)
    }
}

fun Application.installMiniAppAuthStatusPage() {
    if (pluginOrNull(StatusPages) == null) {
        install(StatusPages) {
            exception<MiniAppAuthAbort> { _, _ -> /* 401 уже отправлен */ }
        }
    }
}

internal fun overrideMiniAppValidatorForTesting(override: (String, String) -> TelegramMiniUser?) {
    validator = override
}

internal fun resetMiniAppValidator() {
    validator = { raw, token -> InitDataValidator.validate(raw, token)?.toMiniUser() }
}

private fun TelegramUser.toMiniUser(): TelegramMiniUser =
    TelegramMiniUser(id = id, username = username)

/* -------- helpers -------- */

private suspend fun extractInitData(call: ApplicationCall): String? {
    val q = call.request.queryParameters["initData"]?.takeIf { it.isNotBlank() }
    val h =
        call.request.header("X-Telegram-InitData")?.takeIf { it.isNotBlank() }
            ?: call.request.header("X-Telegram-Init-Data")?.takeIf { it.isNotBlank() }

    if (!q.isNullOrBlank()) return q
    if (!h.isNullOrBlank()) return h
    return extractInitDataFromBodyOrNull(call)
}

private suspend fun ApplicationCall.respondUnauthorized(reason: String) {
    logger.info("Mini App request unauthorized: {}", reason)
    respond(HttpStatusCode.Unauthorized, mapOf("error" to reason))
}

private suspend fun extractInitDataFromBodyOrNull(call: ApplicationCall): String? {
    return try {
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
