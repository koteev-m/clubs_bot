package com.example.bot.routes

import com.example.bot.admin.AdminHallsRepository
import com.example.bot.data.security.Role
import com.example.bot.http.ErrorCodes
import com.example.bot.http.ensureMiniAppNoStoreHeaders
import com.example.bot.http.matchesEtag
import com.example.bot.http.respondError
import com.example.bot.layout.HallPlansRepository
import com.example.bot.plugins.miniAppBotTokenProvider
import com.example.bot.plugins.miniAppBotTokenRequired
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.security.rbac.authorize
import com.example.bot.security.rbac.rbacContext
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.forEachPart
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import java.security.MessageDigest
import java.util.Locale
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private const val MAX_PLAN_SIZE_BYTES = 5L * 1024 * 1024
private const val PLAN_CACHE_CONTROL = "private, max-age=3600, must-revalidate"
private const val FILE_FIELD = "file"
private val ALLOWED_CONTENT_TYPES = setOf(ContentType.Image.PNG, ContentType.Image.JPEG)

@Serializable
private data class PlanUploadResponse(
    val hallId: Long,
    val sha256: String,
    val contentType: String,
    val sizeBytes: Long,
    val updatedAt: String,
)

fun Application.adminHallPlanRoutes(
    adminHallsRepository: AdminHallsRepository,
    hallPlansRepository: HallPlansRepository,
    botTokenProvider: () -> String = miniAppBotTokenProvider(),
) {
    val logger = LoggerFactory.getLogger("AdminHallPlanRoutes")

    routing {
        route("/api/admin") {
            intercept(ApplicationCallPipeline.Setup) { call.ensureMiniAppNoStoreHeaders() }
            withMiniAppAuth { botTokenProvider() }

            authorize(Role.OWNER, Role.GLOBAL_ADMIN, Role.CLUB_ADMIN) {
                put("/halls/{hallId}/plan") {
                    val hallId = call.parameters["hallId"]?.toLongOrNull()
                    if (hallId == null || hallId <= 0) {
                        return@put call.respondError(
                            HttpStatusCode.BadRequest,
                            ErrorCodes.validation_error,
                            details = mapOf("hallId" to "must_be_positive"),
                        )
                    }
                    val hall =
                        adminHallsRepository.getById(hallId)
                            ?: return@put call.respondError(HttpStatusCode.NotFound, ErrorCodes.hall_not_found)
                    if (!call.isAdminClubAllowed(hall.clubId)) {
                        return@put call.respondError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
                    }

                    val upload =
                        when (val result = call.receivePlanUpload(MAX_PLAN_SIZE_BYTES)) {
                            is UploadResult.Ok -> result
                            is UploadResult.Error -> {
                                return@put call.respondError(result.status, result.code, details = result.details)
                            }
                        }

                    val sha256 = sha256Hex(upload.bytes)
                    val stored =
                        hallPlansRepository.upsertPlan(
                            hallId = hallId,
                            contentType = upload.contentType.toString(),
                            bytes = upload.bytes,
                            sha256 = sha256,
                            sizeBytes = upload.sizeBytes,
                        )

                    logger.info(
                        "admin.halls.plan.upload hall_id={} club_id={} content_type={} size_bytes={} sha256_prefix={} by={}",
                        hallId,
                        hall.clubId,
                        upload.contentType,
                        upload.sizeBytes,
                        stored.sha256.take(12),
                        call.rbacContext().user.id,
                    )

                    call.respond(
                        HttpStatusCode.OK,
                        PlanUploadResponse(
                            hallId = hallId,
                            sha256 = stored.sha256,
                            contentType = stored.contentType,
                            sizeBytes = stored.sizeBytes,
                            updatedAt = stored.updatedAt.toString(),
                        ),
                    )
                }
            }
        }
    }
}

fun Application.hallPlanRoutes(
    hallPlansRepository: HallPlansRepository,
) {
    val logger = LoggerFactory.getLogger("HallPlanRoutes")

    routing {
        route("/api") {
            withMiniAppAuth { miniAppBotTokenRequired() }

            route("/clubs/{clubId}/halls/{hallId}/plan") {
                get { call.handleHallPlanGet(hallPlansRepository, logger) }
            }
        }
    }
}

private suspend fun ApplicationCall.handleHallPlanGet(
    hallPlansRepository: HallPlansRepository,
    logger: org.slf4j.Logger,
) {
    val clubId = parameters["clubId"]?.toLongOrNull()
    val hallId = parameters["hallId"]?.toLongOrNull()
    if (clubId == null || hallId == null || clubId <= 0 || hallId <= 0) {
        return respondPlanError(HttpStatusCode.NotFound, ErrorCodes.not_found)
    }

    val ifNoneMatch = request.headers[HttpHeaders.IfNoneMatch]
    if (!ifNoneMatch.isNullOrBlank()) {
        val meta =
            hallPlansRepository.getPlanMetaForClub(clubId, hallId)
                ?: return respondPlanError(HttpStatusCode.NotFound, ErrorCodes.not_found)
        val etag = planEtag(meta.sha256)
        if (matchesEtag(ifNoneMatch, etag)) {
            logger.debug("hall_plan.not_modified clubId={} hallId={} etag={}", clubId, hallId, etag)
            response.headers.append(HttpHeaders.ETag, etag, safeOnly = false)
            response.headers.append(HttpHeaders.CacheControl, PLAN_CACHE_CONTROL)
            respond(HttpStatusCode.NotModified)
            return
        }
    }

    val plan =
        hallPlansRepository.getPlanForClub(clubId, hallId)
            ?: return respondPlanError(HttpStatusCode.NotFound, ErrorCodes.not_found)

    val etag = planEtag(plan.sha256)

    logger.debug("hall_plan.ok clubId={} hallId={} etag={}", clubId, hallId, etag)
    response.headers.append(HttpHeaders.ETag, etag, safeOnly = false)
    response.headers.append(HttpHeaders.CacheControl, PLAN_CACHE_CONTROL)
    respondBytes(
        bytes = plan.bytes,
        contentType = ContentType.parse(plan.contentType),
        status = HttpStatusCode.OK,
    )
}

private suspend fun ApplicationCall.respondPlanError(status: HttpStatusCode, code: String) {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    respondError(status, code)
}

private sealed class UploadResult {
    data class Ok(val bytes: ByteArray, val contentType: ContentType, val sizeBytes: Long) : UploadResult()

    data class Error(
        val status: HttpStatusCode,
        val code: String,
        val details: Map<String, String>? = null,
    ) : UploadResult()
}

private suspend fun ApplicationCall.receivePlanUpload(maxBytes: Long): UploadResult {
    val multipart = receiveMultipart()
    var upload: UploadResult.Ok? = null
    var invalidContentType = false
    var error: UploadResult.Error? = null

    multipart.forEachPart { part ->
        if (error != null) {
            part.dispose()
            return@forEachPart
        }
        when (part) {
            is io.ktor.http.content.PartData.FileItem -> {
                if (part.name == FILE_FIELD && upload == null) {
                    val contentType = part.contentType
                    if (contentType == null || ALLOWED_CONTENT_TYPES.none { contentType.match(it) }) {
                        invalidContentType = true
                    } else {
                        val bytes =
                            try {
                                readLimited(part.provider, maxBytes)
                            } catch (_: PayloadTooLargeException) {
                                error = UploadResult.Error(HttpStatusCode.PayloadTooLarge, ErrorCodes.payload_too_large)
                                part.dispose()
                                return@forEachPart
                            }
                        if (bytes.isEmpty()) {
                            error =
                                UploadResult.Error(
                                    HttpStatusCode.BadRequest,
                                    ErrorCodes.validation_error,
                                    details = mapOf(FILE_FIELD to "must_be_non_empty"),
                                )
                            part.dispose()
                            return@forEachPart
                        }
                        upload = UploadResult.Ok(bytes, contentType, bytes.size.toLong())
                    }
                }
            }
            else -> Unit
        }
        part.dispose()
    }

    error?.let { return it }
    if (invalidContentType) {
        return UploadResult.Error(HttpStatusCode.UnsupportedMediaType, ErrorCodes.unsupported_media_type)
    }
    return upload ?: UploadResult.Error(
        HttpStatusCode.BadRequest,
        ErrorCodes.validation_error,
        details = mapOf(FILE_FIELD to "required"),
    )
}

private suspend fun readLimited(channelProvider: () -> ByteReadChannel, maxBytes: Long): ByteArray {
    val channel = channelProvider()
    val bytes = channel.readRemaining(maxBytes + 1).readByteArray()
    if (bytes.size > maxBytes) {
        throw PayloadTooLargeException()
    }
    return bytes
}

private fun planEtag(sha256: String): String = "\"plan-sha256-$sha256\""

private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(Locale.US, it) }
}

private class PayloadTooLargeException : RuntimeException()
