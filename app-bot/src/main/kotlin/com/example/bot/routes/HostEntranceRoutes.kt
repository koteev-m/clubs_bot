package com.example.bot.routes

import com.example.bot.data.security.Role
import com.example.bot.host.HostEntranceService
import com.example.bot.http.ErrorCodes
import com.example.bot.http.respondError
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.security.rbac.authorize
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory

private const val VARY_HEADER = "X-Telegram-Init-Data"
private const val NO_STORE = "no-store"
private val logger = LoggerFactory.getLogger("HostEntranceRoutes")

fun Application.hostEntranceRoutes(
    service: HostEntranceService,
    botTokenProvider: () -> String = { System.getenv("TELEGRAM_BOT_TOKEN") ?: "" },
) {
    routing {
        route("/api/host/entrance") {
            intercept(ApplicationCallPipeline.Setup) {
                call.applyNoStoreHeaders()
            }
            withMiniAppAuth { botTokenProvider() }

            authorize(Role.ENTRY_MANAGER, Role.CLUB_ADMIN, Role.OWNER, Role.GLOBAL_ADMIN) {
                get {
                    val clubId = call.request.queryParameters["clubId"]?.toLongOrNull()
                    val eventId = call.request.queryParameters["eventId"]?.toLongOrNull()
                    if (clubId == null || clubId <= 0 || eventId == null || eventId <= 0) {
                        logger.warn("host.entrance validation_error club_id={} event_id={} ", clubId, eventId)
                        return@get call.respondEntranceError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                    }

                    val snapshot = service.buildEntranceSnapshot(clubId, eventId)
                    if (snapshot == null) {
                        logger.warn("host.entrance not_found club_id={} event_id={}", clubId, eventId)
                        return@get call.respondEntranceError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                    }

                    logger.info(
                        "host.entrance ok club_id={} event_id={} gl={} bookings={} waitlist={}",
                        clubId,
                        eventId,
                        snapshot.expected.guestList.size,
                        snapshot.expected.bookings.size,
                        snapshot.waitlist.activeCount,
                    )
                    call.respond(snapshot)
                }
            }
        }
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondEntranceError(
    status: HttpStatusCode,
    code: String,
) {
    applyNoStoreHeaders()
    respondError(status, code)
}

private fun io.ktor.server.application.ApplicationCall.applyNoStoreHeaders() {
    val headers = response.headers
    if (headers[HttpHeaders.CacheControl] == null) {
        headers.append(HttpHeaders.CacheControl, NO_STORE)
    }
    if (headers[HttpHeaders.Vary] == null) {
        headers.append(HttpHeaders.Vary, VARY_HEADER)
    }
}
