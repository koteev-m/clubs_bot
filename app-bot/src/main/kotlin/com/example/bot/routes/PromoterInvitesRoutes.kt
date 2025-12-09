package com.example.bot.routes

import com.example.bot.http.ErrorCodes
import com.example.bot.plugins.MiniAppUserKey
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.promoter.invites.PromoterInviteQrCodec
import com.example.bot.promoter.invites.PromoterInviteService
import com.example.bot.promoter.invites.PromoterInviteView
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.micrometer.core.instrument.MeterRegistry
import java.time.Clock
import java.time.Instant
import kotlin.text.Charsets
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val JSON = ContentType.Application.Json.withCharset(Charsets.UTF_8)
private val CSV = ContentType.parse("text/csv; charset=utf-8")
private const val VARY_HEADER = "X-Telegram-Init-Data"
private const val NO_STORE = "no-store"

@Serializable
private data class IssueInviteRequest(
    val clubId: Long,
    val eventId: Long,
    val guestName: String,
    val guestCount: Int,
)

@Serializable
private data class IssueInviteResponse(
    val invite: PromoterInviteView,
    val qr: InviteQrPayload,
)

@Serializable
private data class InviteQrPayload(
    val payload: String,
    val format: String = "text",
)

@Serializable
private data class InvitesResponse(val invites: List<PromoterInviteView>)

fun Application.promoterInvitesRoutes(
    promoterInviteService: PromoterInviteService,
    meterRegistry: MeterRegistry? = null,
    botTokenProvider: () -> String = { System.getenv("TELEGRAM_BOT_TOKEN") ?: "" },
    qrSecretProvider: () -> String = { System.getenv("QR_SECRET") ?: "" },
    clock: Clock = Clock.systemUTC(),
) {
    val logger = LoggerFactory.getLogger("PromoterInvitesRoutes")

    routing {
        route("/api/promoter") {
            withMiniAppAuth { botTokenProvider() }

            post("/invites") {
                val user = call.attributes[MiniAppUserKey]
                val request = runCatching { call.receive<IssueInviteRequest>() }.getOrNull()
                    ?: return@post call.respondPromoterError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)

                val validationError =
                    when {
                        request.clubId <= 0 || request.eventId <= 0 -> ErrorCodes.validation_error
                        request.guestName.isBlank() || request.guestName.length > 100 -> ErrorCodes.validation_error
                        request.guestCount !in 1..10 -> ErrorCodes.validation_error
                        else -> null
                    }
                if (validationError != null) {
                    meterRegistry
                        ?.counter(
                            "promoter.invite.issue.failed",
                            "error_code",
                            validationError,
                        )
                        ?.increment()
                    logger.warn("promoter.invite.issue error={} promoter_id={} event={} club={}", validationError, user.id, request.eventId, request.clubId)
                    return@post call.respondPromoterError(HttpStatusCode.BadRequest, validationError)
                }

                val secret = qrSecretProvider()
                if (secret.isBlank()) {
                    meterRegistry?.counter("promoter.invite.issue.failed", "error_code", ErrorCodes.internal_error)?.increment()
                    logger.warn("promoter.invite.issue error={} promoter_id={}", ErrorCodes.internal_error, user.id)
                    return@post call.respondPromoterError(HttpStatusCode.InternalServerError, ErrorCodes.internal_error)
                }

                val now = Instant.now(clock)
                val invite = promoterInviteService.issueInvite(
                    promoterId = user.id,
                    clubId = request.clubId,
                    eventId = request.eventId,
                    guestName = request.guestName.trim(),
                    guestCount = request.guestCount,
                    now = now,
                )
                val qrPayload = PromoterInviteQrCodec.encode(invite.id, invite.eventId, now, secret)
                meterRegistry?.counter("promoter.invite.issue.success", "event_id", request.eventId.toString())?.increment()
                logger.info("promoter.invite.issue ok promoter_id={} invite_id={} event_id={} club_id={}", user.id, invite.id, invite.eventId, invite.clubId)
                call.applyPromoterHeaders(NO_STORE)
                call.respond(
                    status = HttpStatusCode.Created,
                    message = IssueInviteResponse(invite = invite, qr = InviteQrPayload(payload = qrPayload)),
                )
            }

            get("/invites") {
                val user = call.attributes[MiniAppUserKey]
                val eventId = call.requireEventIdOrValidationError()
                    ?: return@get call.respondPromoterError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)

                val invites = promoterInviteService.listInvites(user.id, eventId)
                logger.info("promoter.invite.list ok promoter_id={} event_id={} count={}", user.id, eventId, invites.size)
                call.applyPromoterHeaders(NO_STORE)
                call.respond(InvitesResponse(invites))
            }

            post("/invites/{id}/revoke") {
                val user = call.attributes[MiniAppUserKey]
                val id = call.parameters["id"]?.toLongOrNull()
                    ?: return@post call.respondPromoterError(HttpStatusCode.NotFound, ErrorCodes.not_found)

                when (val result = promoterInviteService.revokeInvite(user.id, id)) {
                    is PromoterInviteService.RevokeResult.Success -> {
                        logger.info("promoter.invite.revoke ok promoter_id={} invite_id={}", user.id, id)
                        call.applyPromoterHeaders(NO_STORE)
                        call.respond(result.invite)
                    }

                    PromoterInviteService.RevokeResult.NotFound -> {
                        logger.warn("promoter.invite.revoke error={} invite_id={} promoter_id={}", ErrorCodes.not_found, id, user.id)
                        call.respondPromoterError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                    }

                    PromoterInviteService.RevokeResult.Forbidden -> {
                        logger.warn("promoter.invite.revoke error={} invite_id={} promoter_id={}", ErrorCodes.forbidden, id, user.id)
                        call.respondPromoterError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
                    }

                    PromoterInviteService.RevokeResult.InvalidState -> {
                        logger.warn("promoter.invite.revoke error={} invite_id={} promoter_id={}", ErrorCodes.invalid_state, id, user.id)
                        call.respondPromoterError(HttpStatusCode.Conflict, ErrorCodes.invalid_state)
                    }
                }
            }

            get("/invites/export.csv") {
                val user = call.attributes[MiniAppUserKey]
                val eventId = call.requireEventIdOrValidationError()
                    ?: return@get call.respondPromoterError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)

                val csv = promoterInviteService.exportCsv(user.id, eventId)
                logger.info("promoter.invite.export ok promoter_id={} event_id={} rows={}", user.id, eventId, csv.lineSequence().count() - 1)
                call.applyPromoterHeaders(NO_STORE)
                call.respondText(csv, contentType = CSV)
            }
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.applyPromoterHeaders(cacheControl: String) {
    response.headers.append(HttpHeaders.CacheControl, cacheControl)
    response.headers.append(HttpHeaders.Vary, VARY_HEADER)
}

private fun ApplicationCall.requireEventIdOrValidationError(): Long? {
    val raw = request.queryParameters["eventId"] ?: return null
    val id = raw.toLongOrNull() ?: return null
    if (id <= 0) return null
    return id
}

private suspend fun io.ktor.server.application.ApplicationCall.respondPromoterError(
    status: HttpStatusCode,
    code: String,
) {
    applyPromoterHeaders(NO_STORE)
    respondText(
        status = status,
        contentType = JSON,
        text = """{"error":{"code":"$code","message":"$code"}}""",
    )
}
