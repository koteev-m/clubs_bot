package com.example.bot.routes

import com.example.bot.club.WaitlistEntry
import com.example.bot.club.WaitlistRepository
import com.example.bot.data.security.Role
import com.example.bot.metrics.UiWaitlistMetrics
import com.example.bot.security.rbac.ClubScope
import com.example.bot.security.rbac.authorize
import com.example.bot.security.rbac.clubScoped
import com.example.bot.security.rbac.rbacContext
import com.example.bot.webapp.InitDataAuthConfig
import com.example.bot.webapp.InitDataAuthPlugin
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

fun Application.waitlistRoutes(
    repository: WaitlistRepository,
    initDataAuth: InitDataAuthConfig.() -> Unit,
) {
    routing {
        route("/api/clubs/{clubId}/waitlist") {
            install(InitDataAuthPlugin, initDataAuth)

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
                        call.respond(HttpStatusCode.OK, items.map { it.toResponse() })
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
                        call.respond(HttpStatusCode.OK, updated.toResponse())
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
                        call.respond(HttpStatusCode.OK, updated.toResponse())
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
                    UiWaitlistMetrics.incAdded()
                    call.respond(HttpStatusCode.OK, entry.toResponse())
                }
            }
        }
    }
}

@Serializable
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
)

private fun WaitlistEntry.toResponse() =
    WaitlistEntryResponse(
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

private fun String.toBooleanStrictOrNull(): Boolean? =
    when (this.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }
