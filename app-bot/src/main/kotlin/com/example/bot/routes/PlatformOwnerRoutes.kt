package com.example.bot.routes

import com.example.bot.http.ErrorCodes
import com.example.bot.http.ensureMiniAppNoStoreHeaders
import com.example.bot.http.respondError
import com.example.bot.plugins.MiniAppUserKey
import com.example.bot.plugins.rejectMiniAppInitDataQuery
import com.example.bot.plugins.withMiniAppAuth
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
private data class PlatformOwnerAccessResponse(
    val platformOwner: Boolean = true,
)

/** Cabinet identity check only. It neither grants nor reinterprets any RBAC role. */
fun Application.platformOwnerRoutes(
    configuredOwnerId: Long?,
    botTokenProvider: () -> String,
) {
    // This new endpoint is header-only in every profile, including local development.
    rejectMiniAppInitDataQuery("/api/platform/access")
    routing {
        route("/api/platform/access") {
            withMiniAppAuth(allowInitDataFromBody = false, botTokenProvider = botTokenProvider)
            get {
                call.ensureMiniAppNoStoreHeaders()
                val user = call.attributes[MiniAppUserKey]
                if (configuredOwnerId == null || configuredOwnerId <= 0 || user.id != configuredOwnerId) {
                    return@get call.respondError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
                }
                call.respond(PlatformOwnerAccessResponse())
            }
        }
    }
}
