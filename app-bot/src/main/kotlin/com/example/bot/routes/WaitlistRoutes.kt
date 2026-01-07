package com.example.bot.routes

import com.example.bot.club.WaitlistEntry
import com.example.bot.club.WaitlistRepository
import com.example.bot.data.security.Role
import com.example.bot.metrics.UiWaitlistMetrics
import com.example.bot.notifications.NotificationService
import com.example.bot.plugins.miniAppBotTokenProvider
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.security.rbac.ClubScope
import com.example.bot.security.rbac.authorize
import com.example.bot.security.rbac.clubScoped
import com.example.bot.security.rbac.rbacContext
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
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private const val VARY_HEADER = "X-Telegram-Init-Data"
private const val NO_STORE = "no-store"
private val logger = LoggerFactory.getLogger("WaitlistRoutes")

/**
 * Waitlist endpoints for club events:
 * - GET /api/clubs/{clubId}/waitlist?eventId=
 * - POST /api/clubs/{clubId}/waitlist
 * - POST /api/clubs/{clubId}/waitlist/{id}/call
 * - POST /api/clubs/{clubId}/waitlist/{id}/expire
 *
 * Responses are enriched with SLA hints (`reserveExpiresAt`, `remainingSeconds`) computed relative to [clock];
 * `remainingSeconds` is never negative. Cache headers (`Cache-Control: no-store`, `Vary: X-Telegram-Init-Data`) are
 * applied via a pipeline interceptor. [NotificationService.notifyEnqueuedGuest] is invoked after successful enqueue.
 */
fun Application.waitlistRoutes(
    repository: WaitlistRepository,
    notificationService: NotificationService,
    clock: Clock = Clock.systemUTC(),
    botTokenProvider: () -> String = miniAppBotTokenProvider(),
) {
    routing {
        route("/api/clubs/{clubId}/waitlist") {
            intercept(ApplicationCallPipeline.Setup) {
                call.applyNoStoreHeaders()
            }
            withMiniAppAuth { botTokenProvider() }

            // --- GET: текущая очередь (Host / менеджмент) ---
            authorize(
                Role.OWNER,
                Role.GLOBAL_ADMIN,
                Role.HEAD_MANAGER,
                Role.CLUB_ADMIN,
                Role.MANAGER,
                Role.ENTRY_MANAGER,
            ) {
                clubScoped(ClubScope.Own) {
                    get {
                        val clubId =
                            call.parameters["clubId"]?.toLongOrNull()
                                ?: return@get call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf(
                                        "error" to "invalid_club_id",
                                    ),
                                )
                        val eventId =
                            call.request.queryParameters["eventId"]?.toLongOrNull()
                                ?: return@get call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf(
                                        "error" to "invalid_event_id",
                                    ),
                                )

                        val items = repository.listQueue(clubId, eventId)
                        val now = Instant.now(clock)
                        logger.info(
                            "waitlist.list club_id={} event_id={} items={}",
                            clubId,
                            eventId,
                            items.size,
                        )
                        call.respond(HttpStatusCode.OK, items.map { it.toResponse(now) })
                    }

                    // --- CALL: позвать гостя (резерв на N минут) ---
                    @Serializable
                    data class CallPayload(
                        val reserveMinutes: Int? = 15,
                    )

                    post("{id}/call") {
                        val clubId =
                            call.parameters["clubId"]?.toLongOrNull()
                                ?: return@post call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf(
                                        "error" to "invalid_club_id",
                                    ),
                                )
                        val id =
                            call.parameters["id"]?.toLongOrNull()
                                ?: return@post call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf(
                                        "error" to "invalid_id",
                                    ),
                                )

                        val payload = runCatching { call.receive<CallPayload>() }.getOrNull()
                        val reserve = (payload?.reserveMinutes ?: 15).coerceIn(5, 120)

                        val updated =
                            repository.callEntry(clubId, id, reserve)
                                ?: return@post call.respond(
                                    HttpStatusCode.Conflict,
                                    mapOf(
                                        "error" to "not_waiting_or_not_found",
                                    ),
                                )

                        UiWaitlistMetrics.incClaimed()
                        logger.info(
                            "waitlist.call club_id={} entry_id={} reserveMinutes={}",
                            clubId,
                            id,
                            reserve,
                        )
                        call.respond(HttpStatusCode.OK, updated.toResponse(Instant.now(clock)))
                    }

                    // --- EXPIRE: вернуть в очередь или закрыть ---
                    post("{id}/expire") {
                        val clubId =
                            call.parameters["clubId"]?.toLongOrNull()
                                ?: return@post call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf(
                                        "error" to "invalid_club_id",
                                    ),
                                )
                        val id =
                            call.parameters["id"]?.toLongOrNull()
                                ?: return@post call.respond(
                                    HttpStatusCode.BadRequest,
                                    mapOf(
                                        "error" to "invalid_id",
                                    ),
                                )
                        val close = call.request.queryParameters["close"]?.toBooleanStrictOrNull() ?: false

                        val updated =
                            repository.expireEntry(clubId, id, close)
                                ?: return@post call.respond(
                                    HttpStatusCode.NotFound,
                                    mapOf(
                                        "error" to "not_found",
                                    ),
                                )

                        UiWaitlistMetrics.incExpired()
                        logger.info(
                            "waitlist.expire club_id={} entry_id={} close={}",
                            clubId,
                            id,
                            close,
                        )
                        call.respond(HttpStatusCode.OK, updated.toResponse(Instant.now(clock)))
                    }
                }
            }

            // --- POST: встать в очередь (гость / админ), без ClubScope ---
            authorize(
                Role.OWNER,
                Role.GLOBAL_ADMIN,
                Role.HEAD_MANAGER,
                Role.CLUB_ADMIN,
                Role.MANAGER,
                Role.PROMOTER,
                Role.GUEST,
            ) {
                @Serializable
                data class EnqueuePayload(
                    val eventId: Long,
                    val partySize: Int,
                )

                post {
                    val clubId =
                        call.parameters["clubId"]?.toLongOrNull()
                            ?: return@post call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf(
                                    "error" to "invalid_club_id",
                                ),
                            )
                    val payload =
                        runCatching { call.receive<EnqueuePayload>() }.getOrNull()
                            ?: return@post call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf(
                                    "error" to "invalid_payload",
                                ),
                            )

                    if (payload.partySize <= 0) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf(
                                "error" to "invalid_party_size",
                            ),
                        )
                    }

                    val context = call.rbacContext()
                    val userId = context.user.id

                    val entry =
                        repository.enqueue(
                            clubId = clubId,
                            eventId = payload.eventId,
                            userId = userId,
                            partySize = payload.partySize,
                        )
                    notificationService.notifyEnqueuedGuest(entry)
                    UiWaitlistMetrics.incAdded()
                    logger.info(
                        "waitlist.enqueue club_id={} event_id={} user_id={} partySize={}",
                        clubId,
                        payload.eventId,
                        userId,
                        payload.partySize,
                    )
                    call.respond(HttpStatusCode.OK, entry.toResponse(Instant.now(clock)))
                }
            }
        }
    }
}

@Serializable
/**
 * Wire модель для /api/clubs/{clubId}/waitlist.
 *
 * `reserveExpiresAt` совпадает с `expiresAt` и показывает до какого момента держится резерв (UTC) или `null`, когда окна нет.
 * `remainingSeconds` — количество секунд до окончания резерва на момент формирования ответа; `null`, если резерв не задан,
 * `0`, если время уже прошло.
 */
private data class WaitlistEntryResponse(
    val id: Long,
    val clubId: Long,
    val eventId: Long,
    val userId: Long,
    val partySize: Int,
    val status: String,
    val calledAt: String?,
    val expiresAt: String?,
    val createdAt: String,
    val reserveExpiresAt: String?,
    val remainingSeconds: Long?,
)

private fun WaitlistEntry.toResponse(now: Instant): WaitlistEntryResponse {
    val remainingSeconds =
        expiresAt?.let { expiry ->
            Duration.between(now, expiry).seconds.coerceAtLeast(0)
        }

    return WaitlistEntryResponse(
        id = id,
        clubId = clubId,
        eventId = eventId,
        userId = userId,
        partySize = partySize,
        status = status.name,
        calledAt = calledAt?.toString(),
        expiresAt = expiresAt?.toString(),
        createdAt = createdAt.toString(),
        reserveExpiresAt = expiresAt?.toString(),
        remainingSeconds = remainingSeconds,
    )
}

private fun String.toBooleanStrictOrNull(): Boolean? =
    when (this.lowercase(Locale.ROOT)) {
        "true" -> true
        "false" -> false
        else -> null
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
