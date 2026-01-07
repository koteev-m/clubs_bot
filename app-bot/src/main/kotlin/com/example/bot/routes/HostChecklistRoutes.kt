package com.example.bot.routes

import com.example.bot.clubs.EventsRepository
import com.example.bot.data.security.Role
import com.example.bot.host.ShiftChecklistItem
import com.example.bot.host.ShiftChecklistItemView
import com.example.bot.host.ShiftChecklistService
import com.example.bot.http.ErrorCodes
import com.example.bot.http.respondError
import com.example.bot.plugins.MiniAppUserKey
import com.example.bot.plugins.miniAppBotTokenProvider
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.security.rbac.authorize
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.time.Clock
import java.time.Instant
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private const val VARY_HEADER = "X-Telegram-Init-Data"
private const val NO_STORE = "no-store"
private val logger = LoggerFactory.getLogger("HostChecklistRoutes")

/**
 * C2 host checklist API exposing GET/POST /api/host/checklist, protected by mini-app auth and RBAC
 * (ENTRY_MANAGER+). All responses include Cache-Control: no-store and Vary: X-Telegram-Init-Data.
 */
fun Application.hostChecklistRoutes(
    checklistService: ShiftChecklistService,
    eventsRepository: EventsRepository,
    clock: Clock = Clock.systemUTC(),
    botTokenProvider: () -> String = miniAppBotTokenProvider(),
) {
    routing {
        route("/api/host/checklist") {
            intercept(ApplicationCallPipeline.Setup) {
                call.applyNoStoreHeaders()
            }
            withMiniAppAuth { botTokenProvider() }

            authorize(Role.ENTRY_MANAGER, Role.CLUB_ADMIN, Role.OWNER, Role.GLOBAL_ADMIN) {
                get {
                    val clubId = call.request.queryParameters["clubId"]?.toLongOrNull()
                    val eventId = call.request.queryParameters["eventId"]?.toLongOrNull()
                    if (clubId == null || clubId <= 0 || eventId == null || eventId <= 0) {
                        logger.warn("host.checklist validation_error club_id={} event_id={}", clubId, eventId)
                        return@get call.respondChecklistError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                    }

                    val event = eventsRepository.findById(clubId, eventId)
                    if (event == null) {
                        logger.warn("host.checklist not_found club_id={} event_id={}", clubId, eventId)
                        return@get call.respondChecklistError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                    }

                    val items = checklistService.getChecklist(clubId, eventId)
                    val response =
                        HostChecklistResponse(
                            clubId = clubId,
                            eventId = eventId,
                            now = Instant.now(clock).toString(),
                            items = items.map { it.toView() },
                        )

                    logger.info("host.checklist.get club_id={} event_id={} items={}", clubId, eventId, response.items.size)
                    call.respond(response)
                }

                post {
                    val clubId = call.request.queryParameters["clubId"]?.toLongOrNull()
                    val eventId = call.request.queryParameters["eventId"]?.toLongOrNull()
                    if (clubId == null || clubId <= 0 || eventId == null || eventId <= 0) {
                        logger.warn("host.checklist validation_error club_id={} event_id={}", clubId, eventId)
                        return@post call.respondChecklistError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                    }

                    val event = eventsRepository.findById(clubId, eventId)
                    if (event == null) {
                        logger.warn("host.checklist not_found club_id={} event_id={}", clubId, eventId)
                        return@post call.respondChecklistError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                    }

                    val body =
                        runCatching { call.receive<UpdateChecklistItemRequest>() }.getOrElse {
                            logger.warn("host.checklist invalid_json club_id={} event_id={}", clubId, eventId)
                            return@post call.respondChecklistError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)
                        }

                    if (body.itemId.isBlank()) {
                        logger.warn("host.checklist validation_error club_id={} event_id={} item_id={} blank", clubId, eventId, body.itemId)
                        return@post call.respondChecklistError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                    }

                    val actorId = call.attributes[MiniAppUserKey].id
                    val items =
                        try {
                            checklistService.updateItemDone(clubId, eventId, body.itemId, body.done, actorId)
                        } catch (_: IllegalArgumentException) {
                            logger.warn("host.checklist validation_error club_id={} event_id={} item_id={}", clubId, eventId, body.itemId)
                            return@post call.respondChecklistError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                        }

                    val response =
                        HostChecklistResponse(
                            clubId = clubId,
                            eventId = eventId,
                            now = Instant.now(clock).toString(),
                            items = items.map { it.toView() },
                        )

                    logger.info(
                        "host.checklist.update club_id={} event_id={} item_id={} done={}",
                        clubId,
                        eventId,
                        body.itemId,
                        body.done,
                    )
                    call.respond(response)
                }
            }
        }
    }
}

@Serializable
private data class HostChecklistResponse(
    val clubId: Long,
    val eventId: Long,
    val now: String,
    val items: List<ShiftChecklistItemView>,
)

@Serializable
private data class UpdateChecklistItemRequest(
    val itemId: String,
    val done: Boolean,
)

private fun ShiftChecklistItem.toView(): ShiftChecklistItemView =
    ShiftChecklistItemView(
        id = id,
        section = section,
        text = text,
        done = done,
        updatedAt = updatedAt?.toString(),
        actorId = actorId,
    )

private suspend fun ApplicationCall.respondChecklistError(
    status: HttpStatusCode,
    code: String,
) {
    applyNoStoreHeaders()
    respondError(status, code)
}

private fun ApplicationCall.applyNoStoreHeaders() {
    val headers = response.headers
    if (headers[HttpHeaders.CacheControl] == null) {
        headers.append(HttpHeaders.CacheControl, NO_STORE)
    }
    if (headers[HttpHeaders.Vary] == null) {
        headers.append(HttpHeaders.Vary, VARY_HEADER)
    }
}
