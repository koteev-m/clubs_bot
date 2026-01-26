package com.example.bot.routes

import com.example.bot.booking.a3.CanonJson
import com.example.bot.data.security.Role
import com.example.bot.http.ensureMiniAppNoStoreHeaders
import com.example.bot.http.matchesEtag
import com.example.bot.music.MixtapeService
import com.example.bot.music.MusicLikesRepository
import com.example.bot.music.MusicItemRepository
import com.example.bot.music.MusicItemType
import com.example.bot.plugins.miniAppBotTokenProvider
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.routes.dto.MusicSetDto
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
    val items: List<MusicSetDto>,
    val generatedAt: String,
)

/**
 * Routes for music likes and personal mixtape feed secured by mini-app auth and RBAC.
 */
fun Application.musicLikesRoutes(
    likesRepository: MusicLikesRepository,
    mixtapeService: MixtapeService,
    itemsRepository: MusicItemRepository,
    clock: Clock = Clock.systemUTC(),
    botTokenProvider: () -> String = miniAppBotTokenProvider(),
) {
    val logger = LoggerFactory.getLogger("MusicLikesRoutes")

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
                Role.ENTRY_MANAGER,
                Role.PROMOTER,
                Role.GUEST,
            ) {
                post("/items/{id}/like") {
                    val itemId = call.validItemId(logger) ?: return@post
                    if (!call.ensurePublishedSet(itemsRepository, itemId, logger)) return@post

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
                    if (!call.ensurePublishedSet(itemsRepository, itemId, logger)) return@delete

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
            intercept(ApplicationCallPipeline.Setup) { call.ensureMiniAppNoStoreHeaders() }
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
                    val items =
                        itemsRepository
                            .findByIds(mixtape.items)
                            .filter { it.publishedAt != null && it.itemType == MusicItemType.SET }
                    val counts = likesRepository.countsForItems(items.map { it.id })
                    val likedByUser =
                        likesRepository.likedItemsForUser(context.user.id, items.map { it.id })
                    val payload =
                        items
                            .sortedBy { mixtape.items.indexOf(it.id) }
                            .map {
                                MusicSetDto(
                                    id = it.id,
                                    title = it.title,
                                    dj = it.dj,
                                    description = it.description,
                                    durationSec = it.durationSec,
                                    coverUrl = it.coverAssetId?.let { _ -> "/api/music/items/${it.id}/cover" } ?: it.coverUrl,
                                    audioUrl = it.audioAssetId?.let { _ -> "/api/music/items/${it.id}/audio" } ?: it.sourceUrl,
                                    tags = it.tags,
                                    likesCount = counts[it.id] ?: 0,
                                    likedByMe = it.id in likedByUser,
                                )
                            }
                    val generatedAt = Instant.now(clock)
                    val response =
                        MixtapeResponse(
                            userId = mixtape.userId,
                            weekStart = mixtape.weekStart.toString(),
                            items = payload,
                            generatedAt = generatedAt.toString(),
                        )
                    val etag = etagForMixtapePayload(context.user.id, mixtape.weekStart, payload.map { it.id })
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

@Serializable
private data class MixtapeEtagPayload(
    val userId: Long,
    val weekStart: String,
    val items: List<Long>,
)

private fun etagForMixtapePayload(userId: Long, weekStart: Instant, items: List<Long>): String {
    val json =
        CanonJson.encodeToString(
            MixtapeEtagPayload(
                userId = userId,
                weekStart = weekStart.toString(),
                items = items,
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

private suspend fun ApplicationCall.ensurePublishedSet(
    itemsRepository: MusicItemRepository,
    itemId: Long,
    logger: Logger,
): Boolean {
    val item = itemsRepository.getById(itemId)
    if (item == null || item.publishedAt == null || item.itemType != MusicItemType.SET) {
        logger.info("music.likes.item_not_found item_id={}", itemId)
        respond(
            HttpStatusCode.NotFound,
            mapOf("error" to "item_not_found"),
        )
        return false
    }
    return true
}
