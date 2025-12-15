package com.example.bot.routes

import com.example.bot.booking.a3.CanonJson
import com.example.bot.data.security.Role
import com.example.bot.http.matchesEtag
import com.example.bot.music.Mixtape
import com.example.bot.music.MixtapeService
import com.example.bot.music.MusicLikesRepository
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.security.rbac.authorize
import com.example.bot.security.rbac.rbacContext
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory

private const val VARY_HEADER = "X-Telegram-Init-Data"
private const val NO_STORE = "no-store"

@Serializable
private data class LikeResponse(
    val itemId: Long,
    val liked: Boolean,
    val likedAt: String? = null,
)

@Serializable
private data class MixtapeResponse(
    val userId: Long,
    val weekStart: String,
    val items: List<Long>,
    val generatedAt: String,
)

/**
 * Routes for music likes and personal mixtape feed secured by mini-app auth and RBAC.
 */
fun Application.musicLikesRoutes(
    likesRepository: MusicLikesRepository,
    mixtapeService: MixtapeService,
    clock: Clock = Clock.systemUTC(),
    botTokenProvider: () -> String = { System.getenv("TELEGRAM_BOT_TOKEN") ?: "" },
) {
    val logger = LoggerFactory.getLogger("MusicLikesRoutes")

    routing {
        route("/api/music") {
            intercept(ApplicationCallPipeline.Setup) { call.applyPersonalHeaders() }
            withMiniAppAuth { botTokenProvider() }

            authorize(
                Role.OWNER,
                Role.GLOBAL_ADMIN,
                Role.HEAD_MANAGER,
                Role.CLUB_ADMIN,
                Role.MANAGER,
                Role.ENTRY_MANAGER,
                Role.PROMOTER,
                Role.GUEST,
            ) {
                post("/items/{id}/like") {
                    val itemId = call.validItemId(logger) ?: return@post

                    val userId = call.rbacContext().user.id
                    val now = Instant.now(clock)
                    likesRepository.like(userId, itemId, now)
                    val like = likesRepository.find(userId, itemId)
                    val createdAt = like?.createdAt ?: now
                    logger.info("music.like user_id={} item_id={} created={}", userId, itemId, createdAt)
                    call.respond(
                        HttpStatusCode.OK,
                        LikeResponse(
                            itemId = itemId,
                            liked = true,
                            likedAt = createdAt.toString(),
                        ),
                    )
                }

                delete("/items/{id}/like") {
                    val itemId = call.validItemId(logger) ?: return@delete

                    val userId = call.rbacContext().user.id
                    likesRepository.unlike(userId, itemId)
                    logger.info("music.unlike user_id={} item_id={}", userId, itemId)
                    call.respond(
                        HttpStatusCode.OK,
                        LikeResponse(
                            itemId = itemId,
                            liked = false,
                        ),
                    )
                }
            }
        }

        route("/api/me") {
            intercept(ApplicationCallPipeline.Setup) { call.applyPersonalHeaders() }
            withMiniAppAuth { botTokenProvider() }
            authorize(
                Role.OWNER,
                Role.GLOBAL_ADMIN,
                Role.HEAD_MANAGER,
                Role.CLUB_ADMIN,
                Role.MANAGER,
                Role.ENTRY_MANAGER,
                Role.PROMOTER,
                Role.GUEST,
            ) {
                get("/mixtape") {
                    val context = call.rbacContext()
                    val mixtape = mixtapeService.buildWeeklyMixtape(context.user.id)
                    val generatedAt = Instant.now(clock)
                    val response =
                        MixtapeResponse(
                            userId = mixtape.userId,
                            weekStart = mixtape.weekStart.toString(),
                            items = mixtape.items,
                            generatedAt = generatedAt.toString(),
                        )
                    val etag = etagForMixtape(mixtape)
                    if (matchesEtag(call.request.headers[HttpHeaders.IfNoneMatch], etag)) {
                        logger.debug("music.mixtape.not_modified user_id={} etag={}", context.user.id, etag)
                        call.response.header(HttpHeaders.ETag, etag)
                        return@get call.respond(HttpStatusCode.NotModified)
                    }
                    call.response.header(HttpHeaders.ETag, etag)
                    call.respond(HttpStatusCode.OK, response)
                }
            }
        }
    }
}

private fun ApplicationCall.applyPersonalHeaders() {
    val headers = response.headers
    if (headers[HttpHeaders.CacheControl] == null) {
        headers.append(HttpHeaders.CacheControl, NO_STORE)
    }
    if (headers[HttpHeaders.Vary] == null) {
        headers.append(HttpHeaders.Vary, VARY_HEADER)
    }
}

@Serializable
private data class MixtapeEtagPayload(
    val userId: Long,
    val weekStart: String,
    val items: List<Long>,
)

private fun etagForMixtape(mixtape: Mixtape): String {
    val json =
        CanonJson.encodeToString(
            MixtapeEtagPayload(
                userId = mixtape.userId,
                weekStart = mixtape.weekStart.toString(),
                items = mixtape.items,
            ),
        )
    val digest = MessageDigest.getInstance("SHA-256").digest(json.toByteArray())
    return digest.joinToString("") { byte -> "%02x".format(byte) }
}

private suspend fun ApplicationCall.validItemId(logger: Logger): Long? {
    val raw = parameters["id"]
    val id = raw?.toLongOrNull()
    if (id == null || id <= 0) {
        logger.warn("music.likes.invalid_item_id raw={}", raw)
        respond(
            HttpStatusCode.BadRequest,
            mapOf("error" to "invalid_item_id"),
        )
        return null
    }
    return id
}
