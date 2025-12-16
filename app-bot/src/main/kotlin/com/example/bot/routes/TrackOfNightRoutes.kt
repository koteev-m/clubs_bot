package com.example.bot.routes

import com.example.bot.data.security.Role
import com.example.bot.http.ensureMiniAppNoStoreHeaders
import com.example.bot.music.MusicPlaylistRepository
import com.example.bot.music.TrackOfNightRepository
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.security.rbac.authorize
import com.example.bot.security.rbac.rbacContext
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.time.Clock
import java.time.Instant
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

fun Application.trackOfNightRoutes(
    trackOfNightRepository: TrackOfNightRepository,
    playlistsRepository: MusicPlaylistRepository,
    clock: Clock = Clock.systemUTC(),
    botTokenProvider: () -> String = { System.getenv("TELEGRAM_BOT_TOKEN") ?: "" },
) {
    val logger = LoggerFactory.getLogger("MusicTrackOfNightRoutes")

    routing {
        route("/api/music") {
            intercept(ApplicationCallPipeline.Setup) { call.ensureMiniAppNoStoreHeaders() }
            withMiniAppAuth { botTokenProvider() }

            authorize(
                Role.OWNER,
                Role.GLOBAL_ADMIN,
                Role.HEAD_MANAGER,
                Role.CLUB_ADMIN,
                Role.MANAGER,
            ) {
                post("/sets/{setId}/track-of-night") {
                    val setId = call.parameters["setId"]?.toLongOrNull()?.takeIf { it > 0 }
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "invalid_set_id"),
                        )

                    val body =
                        runCatching { call.receive<TrackOfNightRequest>() }.getOrElse {
                            return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_json"))
                        }

                    val trackId = body.trackId?.takeIf { it > 0 }
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "invalid_track_id"),
                        )

                    val set = playlistsRepository.getFull(setId)
                        ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "not_found"))

                    val context = call.rbacContext()
                    val clubId = set.clubId
                    val hasGlobalAccess = context.roles.contains(Role.GLOBAL_ADMIN) || context.roles.contains(Role.OWNER)
                    if (clubId != null && clubId !in context.clubIds && !hasGlobalAccess) {
                        return@post call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
                    }

                    if (set.items.none { it.id == trackId }) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "track_not_in_set"),
                        )
                    }

                    val now = Instant.now(clock)
                    val trackOfNight =
                        trackOfNightRepository.setTrackOfNight(
                            setId = setId,
                            trackId = trackId,
                            actorId = context.user.id,
                            markedAt = now,
                        )

                    logger.info(
                        "music.track_of_night.update user_id={} set_id={} track_id={} marked_at={}",
                        context.user.id,
                        setId,
                        trackId,
                        now,
                    )

                    call.respond(
                        HttpStatusCode.OK,
                        TrackOfNightResponse(
                            setId = trackOfNight.setId,
                            trackId = trackOfNight.trackId,
                            isTrackOfNight = true,
                            markedAt = trackOfNight.markedAt.toString(),
                        ),
                    )
                }
            }
        }
    }
}

@Serializable
private data class TrackOfNightRequest(val trackId: Long?)

@Serializable
private data class TrackOfNightResponse(
    val setId: Long,
    val trackId: Long,
    val isTrackOfNight: Boolean,
    val markedAt: String,
)
