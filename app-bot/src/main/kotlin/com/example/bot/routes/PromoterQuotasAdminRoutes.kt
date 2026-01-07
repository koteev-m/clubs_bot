package com.example.bot.routes

import com.example.bot.data.security.Role
import com.example.bot.http.ErrorCodes
import com.example.bot.http.respondError
import com.example.bot.plugins.miniAppBotTokenProvider
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.promoter.quotas.PromoterQuota
import com.example.bot.promoter.quotas.PromoterQuotaService
import com.example.bot.security.rbac.authorize
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.time.Clock
import java.time.Instant
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private const val VARY_HEADER = "X-Telegram-Init-Data"
private const val NO_STORE = "no-store"

@Serializable
private data class PromoterQuotaPayload(
    val clubId: Long,
    val promoterId: Long,
    val tableId: Long,
    val quota: Int,
    val expiresAt: String,
)

@Serializable
private data class PromoterQuotaView(
    val clubId: Long,
    val promoterId: Long,
    val tableId: Long,
    val quota: Int,
    val held: Int,
    val expiresAt: String,
)

@Serializable
private data class PromoterQuotasResponse(val quotas: List<PromoterQuotaView>)

@Serializable
private data class PromoterQuotaResponse(val quota: PromoterQuotaView)

fun Application.promoterQuotasAdminRoutes(
    promoterQuotaService: PromoterQuotaService,
    botTokenProvider: () -> String = miniAppBotTokenProvider(),
    clock: Clock = Clock.systemUTC(),
) {
    val logger = LoggerFactory.getLogger("PromoterQuotasAdminRoutes")

    routing {
        route("/api/admin/quotas") {
            withMiniAppAuth { botTokenProvider() }

            authorize(Role.CLUB_ADMIN, Role.OWNER, Role.GLOBAL_ADMIN) {
                get {
                    val clubId = call.request.queryParameters["clubId"]?.toLongOrNull()
                    if (clubId == null || clubId <= 0) {
                        return@get call.respondAdminError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                    }

                    val quotas = promoterQuotaService.listByClub(clubId, Instant.now(clock))
                    logger.info("promoter.quotas.list club_id={} count={}", clubId, quotas.size)
                    call.respondWithQuotas(PromoterQuotasResponse(quotas.map { it.toView() }))
                }

                post {
                    val payload = runCatching { call.receive<PromoterQuotaPayload>() }.getOrNull()
                        ?: return@post call.respondAdminError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)

                    val quota = payload.toQuotaOrNull()
                        ?: return@post call.respondAdminError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)

                    val saved = promoterQuotaService.createOrReplace(quota)
                    logger.info(
                        "promoter.quotas.upsert club_id={} promoter_id={} table_id={} action=created",
                        saved.clubId,
                        saved.promoterId,
                        saved.tableId,
                    )
                    call.respondWithQuotas(PromoterQuotaResponse(saved.toView()))
                }

                put {
                    val payload = runCatching { call.receive<PromoterQuotaPayload>() }.getOrNull()
                        ?: return@put call.respondAdminError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)

                    val quota = payload.toQuotaOrNull()
                        ?: return@put call.respondAdminError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)

                    val updated = promoterQuotaService.updateExisting(quota)
                        ?: return@put call.respondAdminError(HttpStatusCode.NotFound, ErrorCodes.not_found)

                    logger.info(
                        "promoter.quotas.upsert club_id={} promoter_id={} table_id={} action=updated",
                        updated.clubId,
                        updated.promoterId,
                        updated.tableId,
                    )
                    call.respondWithQuotas(PromoterQuotaResponse(updated.toView()))
                }
            }
        }
    }
}

private fun PromoterQuotaPayload.toQuotaOrNull(): PromoterQuota? {
    val expiresAt = runCatching { Instant.parse(this.expiresAt) }.getOrNull() ?: return null
    if (clubId <= 0 || promoterId <= 0 || tableId <= 0) return null
    if (quota < 1) return null
    return PromoterQuota(
        clubId = clubId,
        promoterId = promoterId,
        tableId = tableId,
        quota = quota,
        held = 0,
        expiresAt = expiresAt,
    )
}

private suspend fun ApplicationCall.respondWithQuotas(body: Any) {
    applyAdminCacheHeaders()
    respond(HttpStatusCode.OK, body)
}

private suspend fun ApplicationCall.respondAdminError(status: HttpStatusCode, code: String) {
    applyAdminCacheHeaders()
    respondError(status, code)
}

private fun ApplicationCall.applyAdminCacheHeaders() {
    response.headers.append(HttpHeaders.CacheControl, NO_STORE)
    response.headers.append(HttpHeaders.Vary, VARY_HEADER)
}

private fun PromoterQuota.toView(): PromoterQuotaView =
    PromoterQuotaView(
        clubId = clubId,
        promoterId = promoterId,
        tableId = tableId,
        quota = quota,
        held = held,
        expiresAt = expiresAt.toString(),
    )
