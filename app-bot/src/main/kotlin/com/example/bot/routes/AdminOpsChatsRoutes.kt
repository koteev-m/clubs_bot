package com.example.bot.routes

import com.example.bot.data.security.Role
import com.example.bot.http.ErrorCodes
import com.example.bot.http.respondError
import com.example.bot.opschat.ClubOpsChatConfig
import com.example.bot.opschat.ClubOpsChatConfigRepository
import com.example.bot.opschat.ClubOpsChatConfigUpsert
import com.example.bot.plugins.miniAppBotTokenProvider
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.security.rbac.authorize
import com.example.bot.security.rbac.canAccessClub
import com.example.bot.security.rbac.rbacContext
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private const val VARY_HEADER = "X-Telegram-Init-Data"
private const val NO_STORE = "no-store"

@Serializable
private data class OpsChatConfigPayload(
    val clubId: Long,
    val chatId: Long,
    val bookingsThreadId: Int?,
    val checkinThreadId: Int?,
    val guestListsThreadId: Int?,
    val supportThreadId: Int?,
    val alertsThreadId: Int?,
)

@Serializable
private data class OpsChatConfigView(
    val clubId: Long,
    val chatId: Long,
    val bookingsThreadId: Int?,
    val checkinThreadId: Int?,
    val guestListsThreadId: Int?,
    val supportThreadId: Int?,
    val alertsThreadId: Int?,
    val updatedAt: String,
)

@Serializable
private data class OpsChatConfigResponse(val config: OpsChatConfigView?)

fun Application.adminOpsChatsRoutes(
    repository: ClubOpsChatConfigRepository,
    botTokenProvider: () -> String = miniAppBotTokenProvider(),
) {
    val logger = LoggerFactory.getLogger("AdminOpsChatsRoutes")

    routing {
        route("/api/admin/ops-chats") {
            withMiniAppAuth { botTokenProvider() }

            authorize(Role.OWNER, Role.GLOBAL_ADMIN, Role.HEAD_MANAGER, Role.CLUB_ADMIN) {
                get {
                    val clubId = call.request.queryParameters["clubId"]?.toLongOrNull()
                    if (clubId == null || clubId <= 0) {
                        return@get call.respondAdminError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                    }
                    if (!call.rbacContext().canAccessClub(clubId)) {
                        return@get call.respondAdminError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
                    }

                    val config = repository.getByClubId(clubId)
                    call.respondWithConfig(config?.toView())
                }

                put {
                    val payload =
                        try {
                            call.receive<OpsChatConfigPayload>()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            return@put call.respondAdminError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)
                        }

                    val upsert =
                        payload.toUpsertOrNull()
                            ?: return@put call.respondAdminError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                    if (!call.rbacContext().canAccessClub(upsert.clubId)) {
                        return@put call.respondAdminError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
                    }

                    val saved = repository.upsert(upsert)
                    logger.info(
                        "admin.ops_chats.upsert club_id={} chat_id={} by={}",
                        saved.clubId,
                        saved.chatId,
                        call.rbacContext().user.id,
                    )
                    call.respondWithConfig(saved.toView())
                }
            }
        }
    }
}

private fun OpsChatConfigPayload.toUpsertOrNull(): ClubOpsChatConfigUpsert? {
    if (clubId <= 0) return null
    if (chatId == 0L) return null
    if (bookingsThreadId != null && bookingsThreadId <= 0) return null
    if (checkinThreadId != null && checkinThreadId <= 0) return null
    if (guestListsThreadId != null && guestListsThreadId <= 0) return null
    if (supportThreadId != null && supportThreadId <= 0) return null
    if (alertsThreadId != null && alertsThreadId <= 0) return null
    return ClubOpsChatConfigUpsert(
        clubId = clubId,
        chatId = chatId,
        bookingsThreadId = bookingsThreadId,
        checkinThreadId = checkinThreadId,
        guestListsThreadId = guestListsThreadId,
        supportThreadId = supportThreadId,
        alertsThreadId = alertsThreadId,
    )
}

private fun ClubOpsChatConfig.toView(): OpsChatConfigView =
    OpsChatConfigView(
        clubId = clubId,
        chatId = chatId,
        bookingsThreadId = bookingsThreadId,
        checkinThreadId = checkinThreadId,
        guestListsThreadId = guestListsThreadId,
        supportThreadId = supportThreadId,
        alertsThreadId = alertsThreadId,
        updatedAt = updatedAt.toString(),
    )

private suspend fun ApplicationCall.respondWithConfig(config: OpsChatConfigView?) {
    applyAdminCacheHeaders()
    respond(HttpStatusCode.OK, OpsChatConfigResponse(config))
}

private suspend fun ApplicationCall.respondAdminError(status: HttpStatusCode, code: String) {
    applyAdminCacheHeaders()
    respondError(status, code)
}

private fun ApplicationCall.applyAdminCacheHeaders() {
    response.headers.append(HttpHeaders.CacheControl, NO_STORE)
    response.headers.append(HttpHeaders.Vary, VARY_HEADER)
}
