package com.example.bot.routes

import com.example.bot.data.security.Role
import com.example.bot.http.ErrorCodes
import com.example.bot.http.ensureMiniAppNoStoreHeaders
import com.example.bot.http.matchesEtag
import com.example.bot.http.respondError
import com.example.bot.music.MusicAssetRepository
import com.example.bot.music.MusicBattleService
import com.example.bot.music.MusicItemType
import com.example.bot.music.MusicStemsRepository
import com.example.bot.plugins.MiniAppUserKey
import com.example.bot.plugins.miniAppBotTokenProvider
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.routes.dto.MusicSetDto
import com.example.bot.security.rbac.authorize
import com.example.bot.security.rbac.rbacContext
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.receiveNullable
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

private const val MUSIC_ASSET_CACHE_CONTROL = "private, max-age=3600, must-revalidate"
private const val DEFAULT_BATTLES_LIMIT = 20
private const val MAX_BATTLES_LIMIT = 100

@Serializable
private data class VoteRequest(
    val chosenItemId: Long? = null,
)

@Serializable
private data class BattleVotesResponse(
    val countA: Int,
    val countB: Int,
    val percentA: Int,
    val percentB: Int,
    val myVote: Long? = null,
)

@Serializable
private data class BattleResponse(
    val id: Long,
    val clubId: Long?,
    val status: String,
    val startsAt: String,
    val endsAt: String,
    val itemA: MusicSetDto,
    val itemB: MusicSetDto,
    val votes: BattleVotesResponse,
)

@Serializable
private data class FanStatsResponse(
    val votesCast: Int,
    val likesGiven: Int,
    val points: Int,
    val rank: Int,
)

@Serializable
private data class FanDistributionResponse(
    val totalFans: Int,
    val topPoints: List<Int>,
    val p50: Int,
    val p90: Int,
    val p99: Int,
)

@Serializable
private data class FanRankingResponse(
    val myStats: FanStatsResponse,
    val distribution: FanDistributionResponse,
)

fun Application.musicBattleRoutes(
    battleService: MusicBattleService,
    itemsRepository: com.example.bot.music.MusicItemRepository,
    stemsRepository: MusicStemsRepository,
    assetsRepository: MusicAssetRepository,
    botTokenProvider: () -> String = miniAppBotTokenProvider(),
) {
    routing {
        route("/api/music") {
            intercept(ApplicationCallPipeline.Setup) { call.ensureMiniAppNoStoreHeaders() }
            withMiniAppAuth(allowMissingInitData = true) { botTokenProvider() }

            get("/battles/current") {
                val clubId = call.queryLong("clubId") ?: return@get call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                val userId = if (call.attributes.contains(MiniAppUserKey)) call.attributes[MiniAppUserKey].id else null
                val battle = battleService.getCurrent(clubId = clubId, userId = userId)
                    ?: return@get call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                call.respond(HttpStatusCode.OK, battle.toResponse())
            }

            get("/battles") {
                val clubId = call.queryLong("clubId") ?: return@get call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                val limit = call.queryInt("limit", DEFAULT_BATTLES_LIMIT, 1, MAX_BATTLES_LIMIT)
                    ?: return@get call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                val offset = call.queryInt("offset", 0, 0, Int.MAX_VALUE)
                    ?: return@get call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                val userId = if (call.attributes.contains(MiniAppUserKey)) call.attributes[MiniAppUserKey].id else null
                val battles = battleService.list(clubId = clubId, limit = limit, offset = offset, userId = userId)
                call.respond(HttpStatusCode.OK, battles.map { it.toResponse() })
            }

            get("/battles/{battleId}") {
                val battleId = call.pathLong("battleId") ?: return@get call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                val userId = if (call.attributes.contains(MiniAppUserKey)) call.attributes[MiniAppUserKey].id else null
                val battle = battleService.getById(battleId, userId)
                    ?: return@get call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                call.respond(HttpStatusCode.OK, battle.toResponse())
            }

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
                post("/battles/{battleId}/vote") {
                    if (!call.attributes.contains(MiniAppUserKey)) {
                        return@post call.respondError(HttpStatusCode.Unauthorized, ErrorCodes.unauthorized)
                    }
                    val battleId = call.pathLong("battleId") ?: return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                    val body = runCatching { call.receiveNullable<VoteRequest>() }.getOrNull()
                        ?: return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)
                    val chosenItemId = body.chosenItemId?.takeIf { it > 0 }
                        ?: return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                    val userId = call.rbacContext().user.id

                    val details =
                        try {
                            battleService.vote(battleId = battleId, userId = userId, chosenItemId = chosenItemId).details
                        } catch (_: MusicBattleService.BattleNotFoundException) {
                            return@post call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                        } catch (_: MusicBattleService.InvalidBattleChoiceException) {
                            return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                        } catch (_: MusicBattleService.BattleInvalidStateException) {
                            return@post call.respondError(HttpStatusCode.Conflict, ErrorCodes.invalid_state)
                        }
                    call.respond(HttpStatusCode.OK, details.toResponse())
                }
            }

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
                get("/fans/ranking") {
                    val clubId = call.queryLong("clubId") ?: return@get call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                    val windowDays = call.queryInt("windowDays", 30, 1, 365)
                        ?: return@get call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                    val ranking = battleService.fanRanking(clubId, windowDays, call.rbacContext().user.id)
                    call.respond(
                        HttpStatusCode.OK,
                        FanRankingResponse(
                            myStats =
                                FanStatsResponse(
                                    votesCast = ranking.myStats.votesCast,
                                    likesGiven = ranking.myStats.likesGiven,
                                    points = ranking.myStats.points,
                                    rank = ranking.myStats.rank,
                                ),
                            distribution =
                                FanDistributionResponse(
                                    totalFans = ranking.distribution.totalFans,
                                    topPoints = ranking.distribution.topPoints,
                                    p50 = ranking.distribution.p50,
                                    p90 = ranking.distribution.p90,
                                    p99 = ranking.distribution.p99,
                                ),
                        ),
                    )
                }
            }
        }

        route("/api/music/items/{itemId}/stems") {
            withMiniAppAuth { botTokenProvider() }
            authorize(Role.OWNER, Role.GLOBAL_ADMIN, Role.HEAD_MANAGER) {
                get {
                    val itemId = call.pathLong("itemId") ?: return@get call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                    val item = itemsRepository.getById(itemId)
                        ?.takeIf { it.itemType == MusicItemType.SET }
                        ?: return@get call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                    val stems = stemsRepository.getStemAsset(item.id)
                        ?: return@get call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                    val meta = assetsRepository.getAssetMeta(stems.assetId)
                        ?: return@get call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                    val etag = meta.sha256
                    val ifNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch]
                    if (matchesEtag(ifNoneMatch, etag)) {
                        call.response.header(HttpHeaders.ETag, etag)
                        call.response.header(HttpHeaders.CacheControl, MUSIC_ASSET_CACHE_CONTROL)
                        return@get call.respond(HttpStatusCode.NotModified)
                    }

                    val asset = assetsRepository.getAsset(stems.assetId)
                        ?: return@get call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                    call.response.header(HttpHeaders.ETag, etag)
                    call.response.header(HttpHeaders.CacheControl, MUSIC_ASSET_CACHE_CONTROL)
                    call.respondBytes(
                        bytes = asset.bytes,
                        contentType = ContentType.parse(meta.contentType),
                        status = HttpStatusCode.OK,
                    )
                }
            }
        }
    }
}

private fun ApplicationCall.pathLong(name: String): Long? = parameters[name]?.toLongOrNull()?.takeIf { it > 0 }

private fun ApplicationCall.queryLong(name: String): Long? = request.queryParameters[name]?.toLongOrNull()?.takeIf { it > 0 }

private fun ApplicationCall.queryInt(name: String, defaultValue: Int, min: Int, max: Int): Int? {
    val raw = request.queryParameters[name] ?: return defaultValue
    val parsed = raw.toIntOrNull() ?: return null
    return parsed.takeIf { it in min..max }
}

private fun MusicBattleService.BattleDetails.toResponse(): BattleResponse =
    BattleResponse(
        id = id,
        clubId = clubId,
        status = status.name,
        startsAt = startsAt.toString(),
        endsAt = endsAt.toString(),
        itemA = itemA.toSetDto(),
        itemB = itemB.toSetDto(),
        votes =
            BattleVotesResponse(
                countA = votes.countA,
                countB = votes.countB,
                percentA = votes.percentA,
                percentB = votes.percentB,
                myVote = votes.myVote,
            ),
    )

private fun com.example.bot.music.MusicItemView.toSetDto(): MusicSetDto =
    MusicSetDto(
        id = id,
        title = title,
        dj = dj,
        description = description,
        durationSec = durationSec,
        coverUrl = coverAssetId?.let { _ -> "/api/music/items/$id/cover" } ?: coverUrl,
        audioUrl = audioAssetId?.let { _ -> "/api/music/items/$id/audio" } ?: sourceUrl,
        tags = tags,
        likesCount = 0,
        likedByMe = false,
    )
