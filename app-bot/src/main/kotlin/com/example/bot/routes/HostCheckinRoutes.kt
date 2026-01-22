package com.example.bot.routes

import com.example.bot.checkin.CheckinService
import com.example.bot.checkin.CheckinServiceError
import com.example.bot.checkin.CheckinServiceResult
import com.example.bot.checkin.HostCheckinAction
import com.example.bot.checkin.HostCheckinOutcome
import com.example.bot.checkin.HostCheckinRequest
import com.example.bot.checkin.HostCheckinSubject
import com.example.bot.data.security.AuthContext
import com.example.bot.data.security.Role
import com.example.bot.host.HostSearchItem
import com.example.bot.host.HostSearchService
import com.example.bot.http.ErrorCodes
import com.example.bot.http.ensureMiniAppNoStoreHeaders
import com.example.bot.http.respondError
import com.example.bot.plugins.DEFAULT_CHECKIN_MAX_BYTES
import com.example.bot.plugins.MAX_CHECKIN_MAX_BYTES
import com.example.bot.plugins.MIN_CHECKIN_MAX_BYTES
import com.example.bot.plugins.miniAppBotTokenProvider
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.security.rbac.ClubScope
import com.example.bot.security.rbac.authorize
import com.example.bot.security.rbac.clubScoped
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
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.util.Locale
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("HostCheckinRoutes")

@Serializable
private data class HostCheckinRequestPayload(
    val clubId: Long,
    val eventId: Long,
    val bookingId: String? = null,
    val guestListEntryId: Long? = null,
    val invitationToken: String? = null,
    val action: String? = null,
    val denyReason: String? = null,
)

@Serializable
private data class HostScanRequest(
    val clubId: Long,
    val eventId: Long,
    val qrPayload: String,
)

@Serializable
private data class HostCheckinResponse(
    val outcomeStatus: String,
    val denyReason: String? = null,
    val subject: HostCheckinSubjectResponse,
    val bookingStatus: String? = null,
    val entryStatus: String? = null,
    val occurredAt: String? = null,
)

@Serializable
private data class HostCheckinSubjectResponse(
    val kind: String,
    val bookingId: String? = null,
    val guestListEntryId: Long? = null,
    val invitationId: Long? = null,
)

@Serializable
private data class HostSearchItemResponse(
    val kind: String,
    val displayName: String,
    val bookingId: String? = null,
    val guestListEntryId: Long? = null,
    val status: String,
    val guestCount: Int,
    val arrived: Boolean,
    val tableNumber: Int? = null,
    val arrivalWindowStart: String? = null,
    val arrivalWindowEnd: String? = null,
)

fun Application.hostCheckinRoutes(
    checkinService: CheckinService,
    hostSearchService: HostSearchService,
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

            authorize(
                Role.ENTRY_MANAGER,
                Role.MANAGER,
                Role.CLUB_ADMIN,
                Role.HEAD_MANAGER,
                Role.OWNER,
                Role.GLOBAL_ADMIN,
            ) {
                clubScoped(ClubScope.Own) {
                    post {
                        if (!call.request.contentType().match(ContentType.Application.Json)) {
                            logger.warn("host.checkin invalid_content_type")
                            return@post call.respondCheckinError(
                                HttpStatusCode.UnsupportedMediaType,
                                ErrorCodes.unsupported_media_type,
                            )
                        }

                        val body = call.receiveOrNull<HostCheckinRequestPayload>()
                            ?: return@post call.respondCheckinError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)
                        val action = parseAction(body.action)
                            ?: return@post call.respondCheckinError(
                                HttpStatusCode.BadRequest,
                                ErrorCodes.checkin_invalid_payload,
                            )
                        val payload =
                            HostCheckinRequest(
                                clubId = body.clubId,
                                eventId = body.eventId,
                                bookingId = body.bookingId?.trim()?.takeIf { it.isNotBlank() },
                                guestListEntryId = body.guestListEntryId,
                                invitationToken = body.invitationToken?.trim()?.takeIf { it.isNotBlank() },
                                action = action,
                                denyReason = body.denyReason?.trim()?.takeIf { it.isNotBlank() },
                            )
                        if (!call.rbacContext().canAccessClub(payload.clubId)) {
                            return@post call.respondCheckinError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
                        }
                        val actor = call.toAuthContext()
                        val result = checkinService.hostCheckin(payload, actor)
                        call.respondCheckinResult(result)
                    }

                    post("/scan") {
                        if (!call.request.contentType().match(ContentType.Application.Json)) {
                            logger.warn("host.checkin.scan invalid_content_type")
                            return@post call.respondCheckinError(
                                HttpStatusCode.UnsupportedMediaType,
                                ErrorCodes.unsupported_media_type,
                            )
                        }

                        val body = call.receiveOrNull<HostScanRequest>()
                            ?: return@post call.respondCheckinError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)
                        val payload = body.qrPayload.trim()
                        if (payload.isBlank()) {
                            logger.warn("host.checkin.scan invalid_payload length=0")
                            return@post call.respondCheckinError(
                                HttpStatusCode.BadRequest,
                                ErrorCodes.checkin_invalid_payload,
                            )
                        }
                        if (!call.rbacContext().canAccessClub(body.clubId)) {
                            return@post call.respondCheckinError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
                        }

                        val actor = call.toAuthContext()
                        val result = checkinService.hostScan(payload, body.clubId, body.eventId, actor)
                        call.respondCheckinResult(result)
                    }

                    get("/search") {
                        val clubId = call.request.queryParameters["clubId"]?.toLongOrNull()
                        val eventId = call.request.queryParameters["eventId"]?.toLongOrNull()
                        val query = call.request.queryParameters["query"]?.trim().orEmpty()
                        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
                        if (clubId == null || clubId <= 0 || eventId == null || eventId <= 0) {
                            logger.warn("host.checkin.search validation_error club_id={} event_id={}", clubId, eventId)
                            return@get call.respondCheckinError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                        }
                        if (query.length < 2) {
                            logger.warn("host.checkin.search validation_error length={}", query.length)
                            return@get call.respondCheckinError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                        }
                        if (!call.rbacContext().canAccessClub(clubId)) {
                            return@get call.respondCheckinError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
                        }

                        val results = hostSearchService.search(clubId, eventId, query, limit)
                        val response = results.map { it.toResponse() }
                        call.respond(response)
                    }
                }
            }
        }
    }
}

private suspend fun ApplicationCall.respondCheckinResult(result: CheckinServiceResult<HostCheckinOutcome>) {
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

private fun HostCheckinOutcome.toResponse(): HostCheckinResponse =
    HostCheckinResponse(
        outcomeStatus = outcomeStatus.name,
        denyReason = denyReason,
        subject = subject.toResponse(),
        bookingStatus = bookingStatus?.name,
        entryStatus = entryStatus?.name,
        occurredAt = occurredAt?.toString(),
    )

private fun HostCheckinSubject.toResponse(): HostCheckinSubjectResponse =
    HostCheckinSubjectResponse(
        kind = kind.name,
        bookingId = bookingId,
        guestListEntryId = guestListEntryId,
        invitationId = invitationId,
    )

private fun HostSearchItem.toResponse(): HostSearchItemResponse =
    HostSearchItemResponse(
        kind = kind.name,
        displayName = displayName,
        bookingId = bookingId,
        guestListEntryId = guestListEntryId,
        status = status,
        guestCount = guestCount,
        arrived = arrived,
        tableNumber = tableNumber,
        arrivalWindowStart = arrivalWindowStart?.toString(),
        arrivalWindowEnd = arrivalWindowEnd?.toString(),
    )

private fun parseAction(value: String?): HostCheckinAction? {
    val normalized = value?.trim()?.uppercase(Locale.ROOT)
    return when (normalized) {
        null, "", "ARRIVE", "ARRIVED" -> HostCheckinAction.AUTO
        "DENY" -> HostCheckinAction.DENY
        else -> null
    }
}

private val GLOBAL_ROLES: Set<Role> =
    setOf(Role.OWNER, Role.GLOBAL_ADMIN, Role.HEAD_MANAGER)

private fun com.example.bot.security.rbac.RbacContext.canAccessClub(clubId: Long): Boolean {
    if (roles.any { it in GLOBAL_ROLES }) return true
    return clubId in clubIds
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
