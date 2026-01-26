package com.example.bot.routes

import com.example.bot.http.ErrorCodes
import com.example.bot.http.ensureMiniAppNoStoreHeaders
import com.example.bot.http.matchesEtag
import com.example.bot.music.MixtapeService
import com.example.bot.music.MusicAssetRepository
import com.example.bot.music.MusicItemRepository
import com.example.bot.music.MusicItemType
import com.example.bot.music.MusicLikesRepository
import com.example.bot.music.MusicService
import com.example.bot.plugins.MiniAppUserKey
import com.example.bot.plugins.MiniAppAuthErrorHandledKey
import com.example.bot.plugins.miniAppBotTokenRequired
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.routes.dto.MusicSetDto
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory

private const val DEFAULT_LIMIT = 100
private const val DEFAULT_SETS_LIMIT = 20
private const val MAX_SETS_LIMIT = 100
private const val MUSIC_ASSET_CACHE_CONTROL = "private, max-age=3600, must-revalidate"

fun Application.musicRoutes(
    service: MusicService,
    itemsRepository: MusicItemRepository,
    likesRepository: MusicLikesRepository,
    assetsRepository: MusicAssetRepository,
    mixtapeService: MixtapeService,
) {
    val logger = LoggerFactory.getLogger("MusicRoutes")

    routing {
        route("/api/music/items") {
            get("/{id}/audio") {
                val id = call.parameters["id"]?.toLongOrNull()
                if (id == null || id <= 0) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid id")
                    return@get
                }
                val item = itemsRepository.getById(id)
                    ?: return@get call.respond(HttpStatusCode.NotFound, "Item not found")
                if (item.publishedAt == null) {
                    return@get call.respond(HttpStatusCode.NotFound, "Item not published")
                }
                val assetId = item.audioAssetId
                    ?: return@get call.respond(HttpStatusCode.NotFound, "Audio not found")
                val meta = assetsRepository.getAssetMeta(assetId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, "Audio not found")
                val etag = meta.sha256
                val ifNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch]
                if (matchesEtag(ifNoneMatch, etag)) {
                    call.response.header(HttpHeaders.ETag, etag)
                    call.response.header(HttpHeaders.CacheControl, MUSIC_ASSET_CACHE_CONTROL)
                    call.respond(HttpStatusCode.NotModified)
                    return@get
                }
                val asset = assetsRepository.getAsset(assetId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, "Audio not found")
                call.response.header(HttpHeaders.ETag, etag)
                call.response.header(HttpHeaders.CacheControl, MUSIC_ASSET_CACHE_CONTROL)
                call.respondBytes(
                    bytes = asset.bytes,
                    contentType = io.ktor.http.ContentType.parse(meta.contentType),
                    status = HttpStatusCode.OK,
                )
            }

            get("/{id}/cover") {
                val id = call.parameters["id"]?.toLongOrNull()
                if (id == null || id <= 0) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid id")
                    return@get
                }
                val item = itemsRepository.getById(id)
                    ?: return@get call.respond(HttpStatusCode.NotFound, "Item not found")
                if (item.publishedAt == null) {
                    return@get call.respond(HttpStatusCode.NotFound, "Item not published")
                }
                val assetId = item.coverAssetId
                    ?: return@get call.respond(HttpStatusCode.NotFound, "Cover not found")
                val meta = assetsRepository.getAssetMeta(assetId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, "Cover not found")
                val etag = meta.sha256
                val ifNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch]
                if (matchesEtag(ifNoneMatch, etag)) {
                    call.response.header(HttpHeaders.ETag, etag)
                    call.response.header(HttpHeaders.CacheControl, MUSIC_ASSET_CACHE_CONTROL)
                    call.respond(HttpStatusCode.NotModified)
                    return@get
                }
                val asset = assetsRepository.getAsset(assetId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, "Cover not found")
                call.response.header(HttpHeaders.ETag, etag)
                call.response.header(HttpHeaders.CacheControl, MUSIC_ASSET_CACHE_CONTROL)
                call.respondBytes(
                    bytes = asset.bytes,
                    contentType = io.ktor.http.ContentType.parse(meta.contentType),
                    status = HttpStatusCode.OK,
                )
            }
        }

        route("/api/music") {
            withMiniAppAuth(allowMissingInitData = true) { miniAppBotTokenRequired() }

            route("/items") {
                route("") {
                    get {
                        call.ensureMiniAppNoStoreHeaders()
                        if (!call.requireMiniAppAuth()) return@get
                        val ifNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch]
                        val (etag, items) = service.listItems(limit = DEFAULT_LIMIT)
                        if (ifNoneMatch == etag) {
                            logger.debug("music.items.not_modified etag={}", etag)
                            call.respond(HttpStatusCode.NotModified)
                            return@get
                        }
                        call.response.header(HttpHeaders.ETag, etag)
                        call.respond(HttpStatusCode.OK, items)
                    }
                }
            }

            route("/sets") {
                get {
                    call.ensureMiniAppNoStoreHeaders()
                    if (!call.requireMiniAppAuth()) return@get
                    val ifNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch]
                    val limit =
                        call.parseLimit(DEFAULT_SETS_LIMIT, MAX_SETS_LIMIT)
                            ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid limit")
                    val offset =
                        call.parseOffset()
                            ?: return@get call.respond(HttpStatusCode.BadRequest, "Invalid offset")
                    val tag = call.request.queryParameters["tag"]?.trim()?.takeIf { it.isNotBlank() }
                    val q = call.request.queryParameters["q"]?.trim()?.takeIf { it.isNotBlank() }
                    val userId = call.attributes[MiniAppUserKey].id
                    val (etag, items) = service.listSets(limit = limit, offset = offset, tag = tag, q = q, userId = userId)
                    if (ifNoneMatch == etag) {
                        logger.debug("music.sets.not_modified etag={}", etag)
                        call.respond(HttpStatusCode.NotModified)
                        return@get
                    }
                    call.response.header(HttpHeaders.ETag, etag)
                    call.respond(HttpStatusCode.OK, items)
                }
            }

            route("/playlists") {
                get {
                    call.ensureMiniAppNoStoreHeaders()
                    if (!call.requireMiniAppAuth()) return@get
                    val ifNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch]
                    val (etag, playlists) = service.listPlaylists(limit = DEFAULT_LIMIT)
                    if (ifNoneMatch == etag) {
                        logger.debug("music.playlists.not_modified etag={}", etag)
                        call.respond(HttpStatusCode.NotModified)
                        return@get
                    }
                    call.response.header(HttpHeaders.ETag, etag)
                    call.respond(HttpStatusCode.OK, playlists)
                }

                get("/{id}") {
                    call.ensureMiniAppNoStoreHeaders()
                    if (!call.requireMiniAppAuth()) return@get
                    val id = call.parameters["id"]?.toLongOrNull()
                    if (id == null) {
                        logger.warn("music.playlists.invalid_id value={}", call.parameters["id"])
                        call.respond(HttpStatusCode.BadRequest, "Invalid id")
                        return@get
                    }
                    val ifNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch]
                    val result = service.getPlaylist(id)
                    if (result == null) {
                        logger.debug("music.playlists.not_found id={}", id)
                        call.respond(HttpStatusCode.NotFound, "Playlist not found")
                        return@get
                    }
                    val (etag, payload) = result
                    if (ifNoneMatch == etag) {
                        logger.debug("music.playlists.not_modified id={} etag={}", id, etag)
                        call.respond(HttpStatusCode.NotModified)
                        return@get
                    }
                    call.response.header(HttpHeaders.ETag, etag)
                    call.respond(HttpStatusCode.OK, payload)
                }
            }

            route("/mixtape") {
                get("/week") {
                    call.ensureMiniAppNoStoreHeaders()
                    if (!call.requireMiniAppAuth()) return@get
                    val userId = call.attributes[MiniAppUserKey].id
                    val mixtape = mixtapeService.buildWeeklyGlobalMixtape()
                    val items =
                        itemsRepository
                            .findByIds(mixtape.items)
                            .filter { it.publishedAt != null && it.itemType == MusicItemType.SET }
                    val counts = likesRepository.countsForItems(items.map { it.id })
                    val likedByUser = likesRepository.likedItemsForUser(userId, items.map { it.id })
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
                    call.respond(HttpStatusCode.OK, payload)
                }
            }
        }
    }
}

private suspend fun ApplicationCall.requireMiniAppAuth(): Boolean {
    if (attributes.contains(MiniAppUserKey)) return true
    attributes.put(MiniAppAuthErrorHandledKey, true)
    respond(
        HttpStatusCode.Unauthorized,
        mapOf(
            "error" to "initData missing",
            "message" to "initData missing",
            "code" to ErrorCodes.unauthorized,
        ),
    )
    return false
}

private fun ApplicationCall.parseLimit(
    defaultValue: Int,
    maxValue: Int,
): Int? {
    val raw = request.queryParameters["limit"]
    return when {
        raw == null -> defaultValue
        else -> raw.toIntOrNull()?.takeIf { it in 1..maxValue }
    }
}

private fun ApplicationCall.parseOffset(
    defaultValue: Int = 0,
): Int? {
    val raw = request.queryParameters["offset"]
    return when {
        raw == null -> defaultValue
        else -> raw.toIntOrNull()?.takeIf { it >= 0 }
    }
}
