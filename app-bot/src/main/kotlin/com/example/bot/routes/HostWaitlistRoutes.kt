package com.example.bot.routes

import com.example.bot.club.WaitlistRepository
import com.example.bot.data.security.Role
import com.example.bot.http.ErrorCodes
import com.example.bot.http.ensureMiniAppNoStoreHeaders
import com.example.bot.http.respondError
import com.example.bot.plugins.miniAppBotTokenProvider
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.security.rbac.ClubScope
import com.example.bot.security.rbac.authorize
import com.example.bot.security.rbac.clubScoped
import com.example.bot.security.rbac.rbacContext
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("HostWaitlistRoutes")

@Serializable
private data class HostWaitlistInviteRequest(
    val clubId: Long,
    val eventId: Long,
)

@Serializable
private data class HostWaitlistEntryResponse(
    val id: Long,
    val clubId: Long,
    val eventId: Long,
    val userId: Long,
    val partySize: Int,
    val status: String,
    val calledAt: String? = null,
    val expiresAt: String? = null,
    val createdAt: String,
)

@Serializable
private data class HostWaitlistInviteResponse(
    val enabled: Boolean,
    val reason: String? = null,
)

fun Application.hostWaitlistRoutes(
    repository: WaitlistRepository,
    botTokenProvider: () -> String = miniAppBotTokenProvider(),
) {
    routing {
        route("/api/host/waitlist") {
            intercept(ApplicationCallPipeline.Setup) {
                call.ensureMiniAppNoStoreHeaders()
            }
            withMiniAppAuth { botTokenProvider() }

            authorize(
                Role.ENTRY_MANAGER,
                Role.MANAGER,
                Role.CLUB_ADMIN,
                Role.HEAD_MANAGER,
                Role.OWNER,
                Role.GLOBAL_ADMIN,
            ) {
                clubScoped(ClubScope.Own) {
                    get {
                        val clubId = call.request.queryParameters["clubId"]?.toLongOrNull()
                        val eventId = call.request.queryParameters["eventId"]?.toLongOrNull()
                        if (clubId == null || clubId <= 0 || eventId == null || eventId <= 0) {
                            logger.warn("host.waitlist validation_error club_id={} event_id={}", clubId, eventId)
                            return@get call.respondWaitlistError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                        }
                        if (!call.rbacContext().canAccessClub(clubId)) {
                            return@get call.respondWaitlistError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
                        }

                        val items = repository.listQueue(clubId, eventId)
                        val response = items.map { it.toResponse() }
                        call.respond(response)
                    }

                    post("{id}/invite") {
                        val id = call.parameters["id"]?.toLongOrNull()
                        if (id == null || id <= 0) {
                            logger.warn("host.waitlist invite invalid_id={}", id)
                            return@post call.respondWaitlistError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                        }

                        val body = runCatching { call.receive<HostWaitlistInviteRequest>() }.getOrNull()
                            ?: return@post call.respondWaitlistError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)
                        if (body.clubId <= 0 || body.eventId <= 0) {
                            logger.warn("host.waitlist invite validation_error club_id={} event_id={}", body.clubId, body.eventId)
                            return@post call.respondWaitlistError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                        }
                        if (!call.rbacContext().canAccessClub(body.clubId)) {
                            return@post call.respondWaitlistError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
                        }

                        val entry = repository.get(id)
                        if (entry == null || entry.clubId != body.clubId || entry.eventId != body.eventId) {
                            logger.warn("host.waitlist invite not_found id={} club_id={} event_id={}", id, body.clubId, body.eventId)
                            return@post call.respondWaitlistError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                        }

                        logger.info("host.waitlist invite disabled id={} club_id={} event_id={}", id, body.clubId, body.eventId)
                        call.respond(
                            HostWaitlistInviteResponse(
                                enabled = false,
                                reason = "invitation_unavailable",
                            ),
                        )
                    }
                }
            }
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondWaitlistError(
    status: HttpStatusCode,
    code: String,
) {
    respondError(status, code)
}

private fun com.example.bot.club.WaitlistEntry.toResponse(): HostWaitlistEntryResponse =
    HostWaitlistEntryResponse(
        id = id,
        clubId = clubId,
        eventId = eventId,
        userId = userId,
        partySize = partySize,
        status = status.name,
        calledAt = calledAt?.toString(),
        expiresAt = expiresAt?.toString(),
        createdAt = createdAt.toString(),
    )

private val GLOBAL_ROLES: Set<Role> =
    setOf(Role.OWNER, Role.GLOBAL_ADMIN, Role.HEAD_MANAGER)

private fun com.example.bot.security.rbac.RbacContext.canAccessClub(clubId: Long): Boolean {
    if (roles.any { it in GLOBAL_ROLES }) return true
    return clubId in clubIds
}
