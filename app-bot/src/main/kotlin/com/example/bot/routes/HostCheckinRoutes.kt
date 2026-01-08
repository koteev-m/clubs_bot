package com.example.bot.routes

import com.example.bot.checkin.CheckinResult
import com.example.bot.checkin.CheckinService
import com.example.bot.checkin.CheckinServiceError
import com.example.bot.checkin.CheckinServiceResult
import com.example.bot.club.CheckinResultStatus
import com.example.bot.club.CheckinSubjectType
import com.example.bot.data.security.AuthContext
import com.example.bot.data.security.Role
import com.example.bot.http.ErrorCodes
import com.example.bot.http.ensureMiniAppNoStoreHeaders
import com.example.bot.http.respondError
import com.example.bot.plugins.DEFAULT_CHECKIN_MAX_BYTES
import com.example.bot.plugins.MAX_CHECKIN_MAX_BYTES
import com.example.bot.plugins.MIN_CHECKIN_MAX_BYTES
import com.example.bot.plugins.miniAppBotTokenProvider
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.security.rbac.authorize
import com.example.bot.security.rbac.rbacContext
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.plugins.requestsize.RequestSizeLimit
import io.ktor.server.plugins.requestsize.maxRequestSize
import io.ktor.server.request.contentType
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.Locale

private val logger = LoggerFactory.getLogger("HostCheckinRoutes")

@Serializable
private data class CheckinScanRequest(
    val payload: String,
)

@Serializable
private data class CheckinManualRequest(
    val subjectType: String,
    val subjectId: String,
    val status: String,
    val denyReason: String? = null,
)

@Serializable
private data class CheckinResponse(
    val type: CheckinResponseType,
    val subjectType: String? = null,
    val subjectId: String? = null,
    val resultStatus: String? = null,
    val occurredAt: String? = null,
    val checkedBy: Long? = null,
    val displayName: String? = null,
    val existingCheckin: ExistingCheckinResponse? = null,
    val reason: String? = null,
)

@Serializable
private enum class CheckinResponseType {
    SUCCESS,
    ALREADY_USED,
    INVALID,
    FORBIDDEN,
}

@Serializable
private data class ExistingCheckinResponse(
    val occurredAt: String,
    val checkedBy: Long?,
    val resultStatus: String,
    val method: String,
)

fun Application.hostCheckinRoutes(
    checkinService: CheckinService,
    botTokenProvider: () -> String = miniAppBotTokenProvider(),
    maxBodyBytes: Long = readCheckinMaxBodyBytesFromEnvOrDefault(),
) {
    routing {
        route("/api/host/checkin") {
            intercept(ApplicationCallPipeline.Setup) {
                call.ensureMiniAppNoStoreHeaders()
            }
            install(RequestSizeLimit) {
                maxRequestSize = maxBodyBytes
            }
            withMiniAppAuth(allowInitDataFromBody = false) { botTokenProvider() }

            authorize(Role.ENTRY_MANAGER, Role.CLUB_ADMIN, Role.OWNER, Role.GLOBAL_ADMIN) {
                post("/scan") {
                    if (!call.request.contentType().match(ContentType.Application.Json)) {
                        logger.warn("host.checkin.scan invalid_content_type")
                        return@post call.respondCheckinError(
                            HttpStatusCode.UnsupportedMediaType,
                            ErrorCodes.unsupported_media_type,
                        )
                    }

                    val body = call.receiveOrNull<CheckinScanRequest>()
                        ?: return@post call.respondCheckinError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)
                    val payload = body.payload.trim()
                    if (payload.isBlank()) {
                        logger.warn("host.checkin.scan invalid_payload length=0")
                        return@post call.respondCheckinError(
                            HttpStatusCode.BadRequest,
                            ErrorCodes.checkin_invalid_payload,
                        )
                    }

                    val actor = call.toAuthContext()
                    val result = checkinService.scanQr(payload, actor)
                    call.respondCheckinResult(result)
                }

                post("/manual") {
                    if (!call.request.contentType().match(ContentType.Application.Json)) {
                        logger.warn("host.checkin.manual invalid_content_type")
                        return@post call.respondCheckinError(
                            HttpStatusCode.UnsupportedMediaType,
                            ErrorCodes.unsupported_media_type,
                        )
                    }

                    val body = call.receiveOrNull<CheckinManualRequest>()
                        ?: return@post call.respondCheckinError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)
                    val subjectType = parseSubjectType(body.subjectType)
                    val status = parseResultStatus(body.status)
                    val subjectId = body.subjectId.trim()
                    if (subjectType == null || status == null || subjectId.isBlank()) {
                        logger.warn("host.checkin.manual invalid_payload")
                        return@post call.respondCheckinError(
                            HttpStatusCode.BadRequest,
                            ErrorCodes.checkin_invalid_payload,
                        )
                    }

                    val actor = call.toAuthContext()
                    val result = checkinService.manualCheckin(
                        subjectType = subjectType,
                        subjectId = subjectId,
                        status = status,
                        denyReason = body.denyReason?.takeIf { it.isNotBlank() },
                        actor = actor,
                    )
                    call.respondCheckinResult(result)
                }
            }
        }
    }
}

private suspend fun ApplicationCall.respondCheckinResult(result: CheckinServiceResult<CheckinResult>) {
    when (result) {
        is CheckinServiceResult.Success -> respond(result.value.toResponse())
        is CheckinServiceResult.Failure -> {
            val (status, code) = result.error.toHttpError()
            respondCheckinError(status, code)
        }
    }
}

private fun CheckinServiceError.toHttpError(): Pair<HttpStatusCode, String> =
    when (this) {
        CheckinServiceError.CHECKIN_FORBIDDEN -> HttpStatusCode.Forbidden to ErrorCodes.checkin_forbidden
        CheckinServiceError.CHECKIN_INVALID_PAYLOAD -> HttpStatusCode.BadRequest to ErrorCodes.checkin_invalid_payload
        CheckinServiceError.CHECKIN_SUBJECT_NOT_FOUND -> HttpStatusCode.NotFound to ErrorCodes.checkin_subject_not_found
        CheckinServiceError.CHECKIN_DENY_REASON_REQUIRED ->
            HttpStatusCode.BadRequest to ErrorCodes.checkin_deny_reason_required
    }

private fun CheckinResult.toResponse(): CheckinResponse =
    when (this) {
        is CheckinResult.Success ->
            CheckinResponse(
                type = CheckinResponseType.SUCCESS,
                subjectType = subjectType.name,
                subjectId = subjectId,
                resultStatus = resultStatus.name,
                occurredAt = occurredAt.toString(),
                checkedBy = checkedBy,
                displayName = displayName,
            )
        is CheckinResult.AlreadyUsed ->
            CheckinResponse(
                type = CheckinResponseType.ALREADY_USED,
                subjectType = subjectType.name,
                subjectId = subjectId,
                existingCheckin = existingCheckin.toResponse(),
            )
        is CheckinResult.Invalid ->
            CheckinResponse(
                type = CheckinResponseType.INVALID,
                reason = reason.name,
            )
        CheckinResult.Forbidden ->
            CheckinResponse(type = CheckinResponseType.FORBIDDEN)
    }

private fun com.example.bot.checkin.ExistingCheckin.toResponse(): ExistingCheckinResponse =
    ExistingCheckinResponse(
        occurredAt = occurredAt.toString(),
        checkedBy = checkedBy,
        resultStatus = resultStatus.name,
        method = method.name,
    )

private fun parseSubjectType(value: String?): CheckinSubjectType? =
    value?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
        runCatching { CheckinSubjectType.valueOf(raw.uppercase(Locale.ROOT)) }.getOrNull()
    }

private fun parseResultStatus(value: String?): CheckinResultStatus? =
    value?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
        runCatching { CheckinResultStatus.valueOf(raw.uppercase(Locale.ROOT)) }.getOrNull()
    }

private suspend inline fun <reified T : Any> ApplicationCall.receiveOrNull(): T? =
    runCatching { receive<T>() }.getOrNull()

private suspend fun ApplicationCall.respondCheckinError(status: HttpStatusCode, code: String) {
    respondError(status, code)
}

private fun ApplicationCall.toAuthContext(): AuthContext {
    val context = rbacContext()
    return AuthContext(
        userId = context.user.id,
        telegramUserId = context.principal.userId,
        roles = context.roles,
    )
}

private fun readCheckinMaxBodyBytesFromEnvOrDefault(): Long =
    (System.getenv("CHECKIN_MAX_BODY_BYTES")?.toLongOrNull()
        ?.coerceIn(MIN_CHECKIN_MAX_BYTES, MAX_CHECKIN_MAX_BYTES)) ?: DEFAULT_CHECKIN_MAX_BYTES
