package com.example.bot.webapp

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.response.respond
import io.ktor.util.AttributeKey
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration

private const val MAX_HEADER_LENGTH = 8192

data class TelegramPrincipal(
    val userId: Long,
    val username: String?,
    val firstName: String?,
    val lastName: String?,
)

class InitDataAuthConfig {
    lateinit var botTokenProvider: () -> String
    var maxAge: Duration = Duration.ofHours(24)
    var clock: Clock = Clock.systemUTC()
    var headerName: String = "X-Telegram-Init-Data"
    var headerAliases: List<String> = listOf("X-Telegram-InitData")

    internal fun validate() {
        check(::botTokenProvider.isInitialized) { "botTokenProvider must be provided" }
    }
}

val InitDataPrincipalKey: AttributeKey<TelegramPrincipal> =
    AttributeKey("webapp.principal")

/**
 * Route-scoped плагин для валидации Telegram WebApp initData.
 * Ищет заголовок X-Telegram-Init-Data (либо его алиасы), проверяет подпись/время,
 * и кладёт в attributes проверенного пользователя.
 */
val InitDataAuthPlugin = createRouteScopedPlugin(
    name = "InitDataAuthPlugin",
    createConfiguration = ::InitDataAuthConfig
) {
    pluginConfig.validate()
    val logger = LoggerFactory.getLogger("InitDataAuth")

    onCall { call ->
        val header =
            sequenceOf(pluginConfig.headerName)
                .plus(pluginConfig.headerAliases)
                .mapNotNull { name -> call.request.headers[name] }
                .firstOrNull()

        if (header == null || header.length > MAX_HEADER_LENGTH) {
            logger.warn("initData header missing or too large")
            call.respond(HttpStatusCode.Unauthorized)
            return@onCall
        }

        val botToken = pluginConfig.botTokenProvider.invoke()
        val verified = WebAppInitDataVerifier.verify(
            initData = header,
            botToken = botToken,
            maxAge = pluginConfig.maxAge,
            clock = pluginConfig.clock
        )

        if (verified == null) {
            logger.warn("initData verification failed")
            call.respond(HttpStatusCode.Unauthorized)
            return@onCall
        }

        val principal = TelegramPrincipal(
            userId = verified.userId,
            username = verified.username,
            firstName = verified.firstName,
            lastName = verified.lastName,
        )
        call.attributes.put(InitDataPrincipalKey, principal)
    }
}
