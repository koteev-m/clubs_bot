package com.example.bot.routes

import com.example.bot.data.security.Role
import com.example.bot.http.ErrorCodes
import com.example.bot.http.ensureMiniAppNoStoreHeaders
import com.example.bot.http.respondError
import com.example.bot.plugins.miniAppBotTokenProvider
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.promoter.admin.PromoterAccessUpdateResult
import com.example.bot.promoter.admin.PromoterAdminEntry
import com.example.bot.promoter.admin.PromoterAdminService
import com.example.bot.security.rbac.authorize
import com.example.bot.security.rbac.canAccessClub
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

@Serializable
private data class PromoterAccessUpdateRequest(
    val clubId: Long,
    val enabled: Boolean,
)

@Serializable
private data class PromoterAccessUpdateResponse(
    val enabled: Boolean,
)

@Serializable
private data class PromoterAdminQuotaView(
    val tableId: Long,
    val quota: Int,
    val held: Int,
    val expiresAt: String,
)

@Serializable
private data class PromoterAdminView(
    val promoterId: Long,
    val telegramUserId: Long,
    val username: String? = null,
    val displayName: String? = null,
    val accessEnabled: Boolean,
    val quotas: List<PromoterAdminQuotaView>,
)

@Serializable
private data class PromoterAdminListResponse(
    val promoters: List<PromoterAdminView>,
)

fun Application.promoterAdminRoutes(
    promoterAdminService: PromoterAdminService,
    botTokenProvider: () -> String = miniAppBotTokenProvider(),
) {
    val logger = LoggerFactory.getLogger("PromoterAdminRoutes")

    routing {
        route("/api/admin/promoters") {
            intercept(ApplicationCallPipeline.Setup) { call.ensureMiniAppNoStoreHeaders() }
            withMiniAppAuth { botTokenProvider() }

            authorize(Role.OWNER, Role.GLOBAL_ADMIN, Role.HEAD_MANAGER, Role.CLUB_ADMIN) {
                get {
                    val clubId = call.request.queryParameters["clubId"]?.toLongOrNull()
                    if (clubId == null || clubId <= 0) {
                        return@get call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                    }
                    if (!call.rbacContext().canAccessClub(clubId)) {
                        return@get call.respondError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
                    }

                    val promoters = promoterAdminService.listPromoters(clubId)
                    logger.info("admin.promoters.list club_id={} count={}", clubId, promoters.size)
                    call.respond(HttpStatusCode.OK, PromoterAdminListResponse(promoters.map { it.toView() }))
                }

                post("/{promoterUserId}/access") {
                    val promoterId = call.parameters["promoterUserId"]?.toLongOrNull()
                    if (promoterId == null || promoterId <= 0) {
                        return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                    }

                    val payload = runCatching { call.receive<PromoterAccessUpdateRequest>() }.getOrNull()
                        ?: return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)
                    if (payload.clubId <= 0) {
                        return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                    }
                    if (!call.rbacContext().canAccessClub(payload.clubId)) {
                        return@post call.respondError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
                    }

                    when (val result = promoterAdminService.setAccess(payload.clubId, promoterId, payload.enabled)) {
                        is PromoterAccessUpdateResult.NotFound ->
                            call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                        is PromoterAccessUpdateResult.Success -> {
                            logger.info(
                                "admin.promoters.access club_id={} promoter_id={} enabled={}",
                                payload.clubId,
                                promoterId,
                                result.enabled,
                            )
                            call.respond(HttpStatusCode.OK, PromoterAccessUpdateResponse(result.enabled))
                        }
                    }
                }
            }
        }
    }
}

private fun PromoterAdminEntry.toView(): PromoterAdminView =
    PromoterAdminView(
        promoterId = profile.promoterId,
        telegramUserId = profile.telegramUserId,
        username = profile.username,
        displayName = profile.displayName,
        accessEnabled = profile.accessEnabled,
        quotas = quotas.map { quota ->
            PromoterAdminQuotaView(
                tableId = quota.tableId,
                quota = quota.quota,
                held = quota.held,
                expiresAt = quota.expiresAt.toString(),
            )
        },
    )
