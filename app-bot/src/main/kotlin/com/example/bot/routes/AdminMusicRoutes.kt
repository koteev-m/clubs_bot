package com.example.bot.routes

import com.example.bot.data.security.Role
import com.example.bot.http.ErrorCodes
import com.example.bot.http.ensureMiniAppNoStoreHeaders
import com.example.bot.http.respondError
import com.example.bot.music.MusicAssetKind
import com.example.bot.music.MusicAssetRepository
import com.example.bot.music.MusicItemCreate
import com.example.bot.music.MusicItemRepository
import com.example.bot.music.MusicItemType
import com.example.bot.music.MusicItemUpdate
import com.example.bot.music.MusicSource
import com.example.bot.plugins.miniAppBotTokenProvider
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.security.rbac.authorize
import com.example.bot.security.rbac.rbacContext
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.forEachPart
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant

private const val MAX_AUDIO_SIZE_BYTES = 50L * 1024 * 1024
private const val MAX_COVER_SIZE_BYTES = 5L * 1024 * 1024
private const val FILE_FIELD = "file"
private val AUDIO_CONTENT_TYPES =
    setOf(
        ContentType.Audio.MPEG,
        ContentType.Audio.OGG,
        ContentType("audio", "mp4"),
        ContentType("audio", "aac"),
    )
private val COVER_CONTENT_TYPES =
    setOf(
        ContentType.Image.PNG,
        ContentType.Image.JPEG,
        ContentType("image", "webp"),
    )

@Serializable
private data class AdminMusicItemCreateRequest(
    val clubId: Long? = null,
    val title: String,
    val dj: String? = null,
    val description: String? = null,
    val itemType: MusicItemType,
    val source: MusicSource,
    val sourceUrl: String? = null,
    val durationSec: Int? = null,
    val coverUrl: String? = null,
    val tags: List<String>? = null,
    val published: Boolean = false,
)

@Serializable
private data class AdminMusicItemUpdateRequest(
    val clubId: Long? = null,
    val title: String,
    val dj: String? = null,
    val description: String? = null,
    val itemType: MusicItemType,
    val source: MusicSource,
    val sourceUrl: String? = null,
    val durationSec: Int? = null,
    val coverUrl: String? = null,
    val tags: List<String>? = null,
)

@Serializable
private data class AdminMusicItemResponse(
    val id: Long,
    val clubId: Long?,
    val title: String,
    val dj: String?,
    val description: String?,
    val itemType: MusicItemType,
    val source: MusicSource,
    val sourceUrl: String?,
    val audioUrl: String?,
    val durationSec: Int?,
    val coverUrl: String?,
    val tags: List<String>?,
    val publishedAt: String?,
)

@Serializable
private data class AssetUploadResponse(
    val itemId: Long,
    val assetId: Long,
    val kind: MusicAssetKind,
    val contentType: String,
    val sha256: String,
    val sizeBytes: Long,
    val updatedAt: String,
)

fun Application.adminMusicRoutes(
    itemsRepository: MusicItemRepository,
    assetsRepository: MusicAssetRepository,
    clock: Clock = Clock.systemUTC(),
    botTokenProvider: () -> String = miniAppBotTokenProvider(),
) {
    val logger = LoggerFactory.getLogger("AdminMusicRoutes")

    routing {
        route("/api/admin") {
            intercept(ApplicationCallPipeline.Setup) { call.ensureMiniAppNoStoreHeaders() }
            withMiniAppAuth { botTokenProvider() }

            authorize(Role.OWNER, Role.GLOBAL_ADMIN, Role.CLUB_ADMIN) {
                route("/music/items") {
                    get {
                        val type = call.request.queryParameters["type"]?.uppercase()
                        val filterType = type?.let { runCatching { MusicItemType.valueOf(it) }.getOrNull() }
                        if (type != null && filterType == null) {
                            return@get call.respondError(
                                HttpStatusCode.BadRequest,
                                ErrorCodes.validation_error,
                                details = mapOf("type" to "invalid"),
                            )
                        }
                        val items =
                            itemsRepository.listAll(
                                clubId = null,
                                limit = 200,
                                offset = 0,
                                type = filterType,
                            )
                        val filtered =
                            if (call.hasGlobalAdminAccess()) {
                                items
                            } else {
                                val allowed = call.rbacContext().clubIds
                                items.filter { it.clubId != null && it.clubId in allowed }
                            }
                        call.respond(HttpStatusCode.OK, filtered.map { it.toAdminResponse() })
                    }

                    post {
                        val payload = runCatching { call.receive<AdminMusicItemCreateRequest>() }.getOrNull()
                            ?: return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)

                        if (payload.title.isBlank()) {
                            return@post call.respondError(
                                HttpStatusCode.BadRequest,
                                ErrorCodes.validation_error,
                                details = mapOf("title" to "must_be_non_empty"),
                            )
                        }
                        if (payload.durationSec != null && payload.durationSec <= 0) {
                            return@post call.respondError(
                                HttpStatusCode.BadRequest,
                                ErrorCodes.validation_error,
                                details = mapOf("durationSec" to "must_be_positive"),
                            )
                        }
                        if (payload.clubId != null && payload.clubId <= 0) {
                            return@post call.respondError(
                                HttpStatusCode.BadRequest,
                                ErrorCodes.validation_error,
                                details = mapOf("clubId" to "must_be_positive"),
                            )
                        }

                        if (!call.isAllowedForClub(payload.clubId)) {
                            return@post call.respondError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
                        }

                        val created =
                            itemsRepository.create(
                                MusicItemCreate(
                                    clubId = payload.clubId,
                                    title = payload.title.trim(),
                                    dj = payload.dj.normalized(),
                                    description = payload.description.normalized(),
                                    itemType = payload.itemType,
                                    source = payload.source,
                                    sourceUrl = payload.sourceUrl.normalized(),
                                    durationSec = payload.durationSec,
                                    coverUrl = payload.coverUrl.normalized(),
                                    tags = payload.tags?.map { it.trim() }?.filter { it.isNotBlank() },
                                    publishedAt = if (payload.published) Instant.now(clock) else null,
                                ),
                                actor = call.rbacContext().user.id,
                            )
                        logger.info("admin.music.create item_id={} by={}", created.id, call.rbacContext().user.id)
                        call.respond(HttpStatusCode.Created, created.toAdminResponse())
                    }
                }

                route("/music/items/{id}") {
                    get {
                        val id = call.parameters["id"]?.toLongOrNull()
                        if (id == null || id <= 0) {
                            return@get call.respondError(
                                HttpStatusCode.BadRequest,
                                ErrorCodes.validation_error,
                                details = mapOf("id" to "must_be_positive"),
                            )
                        }
                        val item = itemsRepository.getById(id)
                            ?: return@get call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                        if (!call.isAllowedForClub(item.clubId)) {
                            return@get call.respondError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
                        }
                        call.respond(HttpStatusCode.OK, item.toAdminResponse())
                    }

                    put {
                        val id = call.parameters["id"]?.toLongOrNull()
                        if (id == null || id <= 0) {
                            return@put call.respondError(
                                HttpStatusCode.BadRequest,
                                ErrorCodes.validation_error,
                                details = mapOf("id" to "must_be_positive"),
                            )
                        }
                        val existing = itemsRepository.getById(id)
                            ?: return@put call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                        if (!call.isAllowedForClub(existing.clubId)) {
                            return@put call.respondError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
                        }
                        val payload = runCatching { call.receive<AdminMusicItemUpdateRequest>() }.getOrNull()
                            ?: return@put call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)

                        if (payload.title.isBlank()) {
                            return@put call.respondError(
                                HttpStatusCode.BadRequest,
                                ErrorCodes.validation_error,
                                details = mapOf("title" to "must_be_non_empty"),
                            )
                        }
                        if (payload.durationSec != null && payload.durationSec <= 0) {
                            return@put call.respondError(
                                HttpStatusCode.BadRequest,
                                ErrorCodes.validation_error,
                                details = mapOf("durationSec" to "must_be_positive"),
                            )
                        }
                        if (payload.clubId != null && payload.clubId <= 0) {
                            return@put call.respondError(
                                HttpStatusCode.BadRequest,
                                ErrorCodes.validation_error,
                                details = mapOf("clubId" to "must_be_positive"),
                            )
                        }
                        if (!call.isAllowedForClub(payload.clubId)) {
                            return@put call.respondError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
                        }

                        val updated =
                            itemsRepository.update(
                                id = id,
                                req =
                                    MusicItemUpdate(
                                        clubId = payload.clubId,
                                        title = payload.title.trim(),
                                        dj = payload.dj.normalized(),
                                        description = payload.description.normalized(),
                                        itemType = payload.itemType,
                                        source = payload.source,
                                        sourceUrl = payload.sourceUrl.normalized(),
                                        durationSec = payload.durationSec,
                                        coverUrl = payload.coverUrl.normalized(),
                                        tags = payload.tags?.map { it.trim() }?.filter { it.isNotBlank() },
                                    ),
                                actor = call.rbacContext().user.id,
                            )
                                ?: return@put call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                        logger.info("admin.music.update item_id={} by={}", id, call.rbacContext().user.id)
                        call.respond(HttpStatusCode.OK, updated.toAdminResponse())
                    }
                }

                post("/music/items/{id}/publish") {
                    val id = call.parameters["id"]?.toLongOrNull()
                    if (id == null || id <= 0) {
                        return@post call.respondError(
                            HttpStatusCode.BadRequest,
                            ErrorCodes.validation_error,
                            details = mapOf("id" to "must_be_positive"),
                        )
                    }
                    val existing = itemsRepository.getById(id)
                        ?: return@post call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                    if (!call.isAllowedForClub(existing.clubId)) {
                        return@post call.respondError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
                    }
                    val updated =
                        itemsRepository.setPublished(
                            id = id,
                            publishedAt = Instant.now(clock),
                            actor = call.rbacContext().user.id,
                        )
                            ?: return@post call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                    logger.info("admin.music.publish item_id={} by={}", id, call.rbacContext().user.id)
                    call.respond(HttpStatusCode.OK, updated.toAdminResponse())
                }

                post("/music/items/{id}/unpublish") {
                    val id = call.parameters["id"]?.toLongOrNull()
                    if (id == null || id <= 0) {
                        return@post call.respondError(
                            HttpStatusCode.BadRequest,
                            ErrorCodes.validation_error,
                            details = mapOf("id" to "must_be_positive"),
                        )
                    }
                    val existing = itemsRepository.getById(id)
                        ?: return@post call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                    if (!call.isAllowedForClub(existing.clubId)) {
                        return@post call.respondError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
                    }
                    val updated =
                        itemsRepository.setPublished(
                            id = id,
                            publishedAt = null,
                            actor = call.rbacContext().user.id,
                        )
                            ?: return@post call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                    logger.info("admin.music.unpublish item_id={} by={}", id, call.rbacContext().user.id)
                    call.respond(HttpStatusCode.OK, updated.toAdminResponse())
                }

                put("/music/items/{id}/audio") {
                    val id = call.parameters["id"]?.toLongOrNull()
                    if (id == null || id <= 0) {
                        return@put call.respondError(
                            HttpStatusCode.BadRequest,
                            ErrorCodes.validation_error,
                            details = mapOf("id" to "must_be_positive"),
                        )
                    }
                    val item = itemsRepository.getById(id)
                        ?: return@put call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                    if (!call.isAllowedForClub(item.clubId)) {
                        return@put call.respondError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
                    }
                    val upload =
                        when (val result = call.receiveUpload(MAX_AUDIO_SIZE_BYTES, AUDIO_CONTENT_TYPES)) {
                            is MusicUploadResult.Ok -> result
                            is MusicUploadResult.Error -> {
                                return@put call.respondError(result.status, result.code, details = result.details)
                            }
                        }
                    val asset =
                        assetsRepository.createAsset(
                            kind = MusicAssetKind.AUDIO,
                            bytes = upload.bytes,
                            contentType = upload.contentType.toString(),
                            sha256 = upload.sha256,
                            sizeBytes = upload.sizeBytes,
                        )
                    val updated =
                        itemsRepository.attachAudioAsset(
                            id = id,
                            assetId = asset.id,
                            actor = call.rbacContext().user.id,
                        )
                            ?: return@put call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                    logger.info(
                        "admin.music.audio.upload item_id={} asset_id={} size_bytes={} by={}",
                        id,
                        asset.id,
                        asset.sizeBytes,
                        call.rbacContext().user.id,
                    )
                    call.respond(
                        HttpStatusCode.OK,
                        AssetUploadResponse(
                            itemId = updated.id,
                            assetId = asset.id,
                            kind = MusicAssetKind.AUDIO,
                            contentType = asset.contentType,
                            sha256 = asset.sha256,
                            sizeBytes = asset.sizeBytes,
                            updatedAt = asset.updatedAt.toString(),
                        ),
                    )
                }

                put("/music/items/{id}/cover") {
                    val id = call.parameters["id"]?.toLongOrNull()
                    if (id == null || id <= 0) {
                        return@put call.respondError(
                            HttpStatusCode.BadRequest,
                            ErrorCodes.validation_error,
                            details = mapOf("id" to "must_be_positive"),
                        )
                    }
                    val item = itemsRepository.getById(id)
                        ?: return@put call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                    if (!call.isAllowedForClub(item.clubId)) {
                        return@put call.respondError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
                    }
                    val upload =
                        when (val result = call.receiveUpload(MAX_COVER_SIZE_BYTES, COVER_CONTENT_TYPES)) {
                            is MusicUploadResult.Ok -> result
                            is MusicUploadResult.Error -> {
                                return@put call.respondError(result.status, result.code, details = result.details)
                            }
                        }
                    val asset =
                        assetsRepository.createAsset(
                            kind = MusicAssetKind.COVER,
                            bytes = upload.bytes,
                            contentType = upload.contentType.toString(),
                            sha256 = upload.sha256,
                            sizeBytes = upload.sizeBytes,
                        )
                    val updated =
                        itemsRepository.attachCoverAsset(
                            id = id,
                            assetId = asset.id,
                            actor = call.rbacContext().user.id,
                        )
                            ?: return@put call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                    logger.info(
                        "admin.music.cover.upload item_id={} asset_id={} size_bytes={} by={}",
                        id,
                        asset.id,
                        asset.sizeBytes,
                        call.rbacContext().user.id,
                    )
                    call.respond(
                        HttpStatusCode.OK,
                        AssetUploadResponse(
                            itemId = updated.id,
                            assetId = asset.id,
                            kind = MusicAssetKind.COVER,
                            contentType = asset.contentType,
                            sha256 = asset.sha256,
                            sizeBytes = asset.sizeBytes,
                            updatedAt = asset.updatedAt.toString(),
                        ),
                    )
                }
            }
        }
    }
}

private sealed class MusicUploadResult {
    data class Ok(
        val bytes: ByteArray,
        val contentType: ContentType,
        val sizeBytes: Long,
        val sha256: String,
    ) : MusicUploadResult()

    data class Error(
        val status: HttpStatusCode,
        val code: String,
        val details: Map<String, String>? = null,
    ) : MusicUploadResult()
}

private suspend fun ApplicationCall.receiveUpload(
    maxBytes: Long,
    allowedContentTypes: Set<ContentType>,
): MusicUploadResult {
    val multipart = receiveMultipart()
    var upload: MusicUploadResult.Ok? = null
    var invalidContentType = false
    var error: MusicUploadResult.Error? = null

    multipart.forEachPart { part ->
        if (error != null) {
            part.dispose()
            return@forEachPart
        }
        when (part) {
            is io.ktor.http.content.PartData.FileItem -> {
                if (part.name == FILE_FIELD && upload == null) {
                    val contentType = part.contentType
                    if (contentType == null || allowedContentTypes.none { contentType.match(it) }) {
                        invalidContentType = true
                    } else {
                        val result =
                            runCatching {
                                readLimitedWithSha(part.provider, maxBytes)
                            }.getOrElse {
                                error = MusicUploadResult.Error(HttpStatusCode.PayloadTooLarge, ErrorCodes.payload_too_large)
                                part.dispose()
                                return@forEachPart
                            }
                        if (result.bytes.isEmpty()) {
                            error =
                                MusicUploadResult.Error(
                                    HttpStatusCode.BadRequest,
                                    ErrorCodes.validation_error,
                                    details = mapOf(FILE_FIELD to "must_be_non_empty"),
                                )
                            part.dispose()
                            return@forEachPart
                        }
                        upload = MusicUploadResult.Ok(result.bytes, contentType, result.sizeBytes, result.sha256)
                    }
                }
            }
            else -> Unit
        }
        part.dispose()
    }

    error?.let { return it }
    if (invalidContentType) {
        return MusicUploadResult.Error(HttpStatusCode.UnsupportedMediaType, ErrorCodes.unsupported_media_type)
    }
    return upload ?: MusicUploadResult.Error(
        HttpStatusCode.BadRequest,
        ErrorCodes.validation_error,
        details = mapOf(FILE_FIELD to "required"),
    )
}

private data class ReadWithShaResult(
    val bytes: ByteArray,
    val sizeBytes: Long,
    val sha256: String,
)

private suspend fun readLimitedWithSha(channelProvider: () -> ByteReadChannel, maxBytes: Long): ReadWithShaResult {
    val channel = channelProvider()
    val output = ByteArrayOutputStream()
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(8_192)
    var total = 0L
    while (true) {
        val read = channel.readAvailable(buffer)
        if (read <= 0) break
        total += read
        if (total > maxBytes) {
            throw MusicPayloadTooLargeException()
        }
        digest.update(buffer, 0, read)
        output.write(buffer, 0, read)
    }
    val bytes = output.toByteArray()
    val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
    return ReadWithShaResult(bytes, bytes.size.toLong(), sha256)
}

private class MusicPayloadTooLargeException : RuntimeException()

private suspend fun ApplicationCall.isAllowedForClub(clubId: Long?): Boolean {
    return if (clubId == null) {
        val roles = rbacContext().roles
        roles.any { it == Role.OWNER || it == Role.GLOBAL_ADMIN }
    } else {
        isAdminClubAllowed(clubId)
    }
}

private fun com.example.bot.music.MusicItemView.toAdminResponse(): AdminMusicItemResponse =
    AdminMusicItemResponse(
        id = id,
        clubId = clubId,
        title = title,
        dj = dj,
        description = description,
        itemType = itemType,
        source = source,
        sourceUrl = sourceUrl,
        audioUrl = audioAssetId?.let { _ -> "/api/music/items/$id/audio" } ?: sourceUrl,
        durationSec = durationSec,
        coverUrl = coverAssetId?.let { _ -> "/api/music/items/$id/cover" } ?: coverUrl,
        tags = tags,
        publishedAt = publishedAt?.toString(),
    )

private fun String?.normalized(): String? = this?.trim()?.ifBlank { null }
