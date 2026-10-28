package com.example.bot.plugins

import com.example.bot.data.client.ClientAudience
import com.example.bot.http.ErrorCodes
import com.example.bot.http.ensureMiniAppNoStoreHeaders
import com.example.bot.http.respondError
import com.example.bot.security.auth.InitDataValidator
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.request.queryString
import io.ktor.server.routing.Route
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException

/** Server wiring only. The credential provider is deliberately opaque and never a principal field. */
internal class TrustedBotConnection(
    val connectionKey: String,
    val audience: ClientAudience,
    private val tokenProvider: () -> String,
) {
    init {
        require(connectionKey.isNotBlank()) { "Client connection key is required" }
        try {
            checkedToken()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid client bot configuration")
        }
    }

    fun verify(initData: String): Long? = InitDataValidator.validate(initData, checkedToken())?.id?.takeIf { it > 0 }

    private fun checkedToken(): String =
        tokenProvider().also {
            check(TOKEN_FORMAT.matches(it)) { "Invalid client bot configuration" }
        }

    private companion object {
        val TOKEN_FORMAT = Regex("[0-9]+:[A-Za-z0-9_-]+")
    }
}

internal class VerifiedClientIdentity private constructor(
    val telegramUserId: Long,
    val connectionKey: String,
    val audience: ClientAudience,
    private val connection: TrustedBotConnection,
) {
    fun belongsTo(expected: TrustedBotConnection): Boolean = connection === expected

    companion object {
        fun validate(
            connection: TrustedBotConnection,
            initData: String,
        ): VerifiedClientIdentity? =
            connection.verify(initData)?.let {
                VerifiedClientIdentity(it, connection.connectionKey, connection.audience, connection)
            }
    }
}

private val authInstalledKey = AttributeKey<Boolean>("client.auth.installed")
private val identityKey = AttributeKey<VerifiedClientIdentity>("client.verified.identity")
private const val INIT_DATA_HEADER = "X-Telegram-Init-Data"
private const val INIT_DATA_ALIAS = "X-Telegram-InitData"
private val clientPathSegments = listOf("api", "client")

/** Runs before routing parses query values and before any handler can use legacy identity attributes. */
internal fun Application.installClientContextAuth(connection: TrustedBotConnection) {
    check(!attributes.contains(authInstalledKey)) { "Client context auth already registered" }
    attributes.put(authInstalledKey, true)
    intercept(ApplicationCallPipeline.Monitoring) {
        if (!call.matchesPathPrefix(clientPathSegments)) return@intercept
        call.ensureMiniAppNoStoreHeaders()
        val headers = call.request.headers
        val initData =
            headers.getAll(INIT_DATA_HEADER)?.singleOrNull()?.takeIf { rawHeader ->
                rawHeader.isNotBlank() && ',' !in rawHeader
            }
        if (call.hasForbiddenInitDataQuery() || headers.contains(INIT_DATA_ALIAS) || initData == null) {
            call.respondError(HttpStatusCode.Unauthorized, ErrorCodes.unauthorized)
            finish()
            return@intercept
        }
        val identity =
            try {
                VerifiedClientIdentity.validate(connection, initData)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                call.respondError(HttpStatusCode.InternalServerError, ErrorCodes.internal_error)
                finish()
                return@intercept
            }
        if (identity == null) {
            call.respondError(HttpStatusCode.Unauthorized, ErrorCodes.unauthorized)
            finish()
            return@intercept
        }
        if (call.request.queryString().isNotEmpty()) {
            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
            finish()
            return@intercept
        }
        call.attributes.put(identityKey, identity)
    }
}

private class ClientContextGuardConfig {
    lateinit var connection: TrustedBotConnection
}

private val ClientContextGuard =
    createRouteScopedPlugin("ClientContextGuard", ::ClientContextGuardConfig) {
        val connection = pluginConfig.connection
        onCall { call ->
            if (call.attributes.getOrNull(identityKey)?.belongsTo(connection) != true) {
                call.ensureMiniAppNoStoreHeaders()
                call.respondError(HttpStatusCode.Unauthorized, ErrorCodes.unauthorized)
                throw MiniAppAuthAbort()
            }
        }
    }

/** No legacy principal fallback and no second signature validation. */
internal fun Route.withClientContextAuth(connection: TrustedBotConnection) {
    install(ClientContextGuard) { this.connection = connection }
}

internal fun ApplicationCall.verifiedClientIdentity(): VerifiedClientIdentity = attributes[identityKey]
