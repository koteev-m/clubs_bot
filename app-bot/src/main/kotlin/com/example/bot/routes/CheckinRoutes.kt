package com.example.bot.routes

import com.example.bot.club.GuestListEntryStatus
import com.example.bot.club.GuestListRepository
import com.example.bot.data.security.Role
import com.example.bot.http.ErrorCodes
import com.example.bot.guestlists.QrGuestListCodec
import com.example.bot.guestlists.QrVerificationResult
import com.example.bot.logging.MdcContext
import com.example.bot.logging.maskQrToken
import com.example.bot.logging.spanSuspended
import com.example.bot.metrics.UiCheckinMetrics
import com.example.bot.promoter.invites.PromoterInviteQrCodec
import com.example.bot.promoter.invites.PromoterInviteService
import com.example.bot.plugins.DEFAULT_CHECKIN_MAX_BYTES
import com.example.bot.plugins.MAX_CHECKIN_MAX_BYTES
import com.example.bot.plugins.MIN_CHECKIN_MAX_BYTES
import com.example.bot.plugins.miniAppBotTokenRequired
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.http.respondError
import com.example.bot.metrics.QrRotationConfig
import com.example.bot.security.rbac.ClubScope
import com.example.bot.security.rbac.authorize
import com.example.bot.security.rbac.clubScoped
import com.example.bot.security.rbac.rbacContext
import com.example.bot.telemetry.Tracing
import com.example.bot.telemetry.CheckinScanResult
import com.example.bot.telemetry.setCheckinResult
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.requestsize.RequestSizeLimit
import io.ktor.server.plugins.requestsize.maxRequestSize
import io.ktor.server.request.contentType
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

private val DEFAULT_QR_TTL: Duration = Duration.ofHours(12)

private const val SCAN_TYPE_PROMOTER_INVITE = "promoter_invite"
private const val SCAN_STATUS_ARRIVED = "ARRIVED"

@Serializable
private data class ScanPayload(val qr: String)

@Serializable
private data class PromoterInviteScanResponse(
    val status: String,
    val type: String,
    val inviteId: Long,
)

fun Application.checkinRoutes(
    repository: GuestListRepository,
    promoterInviteService: PromoterInviteService? = null,
    botTokenProvider: () -> String = { miniAppBotTokenRequired() },
    qrSecretProvider: () -> String = { System.getenv("QR_SECRET") ?: error("QR_SECRET missing") },
    rotationConfig: QrRotationConfig = QrRotationConfig.fromEnv(),
    oldQrSecretProvider: () -> String? = { rotationConfig.oldSecret },
    clock: Clock = Clock.systemUTC(),
    qrTtl: Duration = DEFAULT_QR_TTL,
) {
    val logger = LoggerFactory.getLogger("CheckinRoutes")

    routing {
        route("/api/clubs/{clubId}/checkin") {
            // ЕДИНЫЙ механизм WebApp-авторизации
            withMiniAppAuth { botTokenProvider() }

                authorize(Role.CLUB_ADMIN, Role.MANAGER, Role.ENTRY_MANAGER) {
                    clubScoped(ClubScope.Own) {
                        val maxBytes: Long = (System.getenv("CHECKIN_MAX_BODY_BYTES")?.toLongOrNull()
                            ?.coerceIn(MIN_CHECKIN_MAX_BYTES, MAX_CHECKIN_MAX_BYTES)) ?: DEFAULT_CHECKIN_MAX_BYTES
                        install(RequestSizeLimit) {
                        maxRequestSize = maxBytes
                    }

                    post("/scan") {
                        // Считаем каждую попытку сканирования (включая ранние отказы)
                        UiCheckinMetrics.incTotal()
                        // Быстрый отказ, если не JSON
                        val ct = call.request.contentType()
                        if (!ct.match(ContentType.Application.Json)) {
                            UiCheckinMetrics.incError()
                            logger.warn(
                                "checkin.scan error={} clubId={}",
                                ErrorCodes.unsupported_media_type,
                                call.parameters["clubId"],
                            )
                            call.respondError(HttpStatusCode.UnsupportedMediaType, ErrorCodes.unsupported_media_type)
                            return@post
                        }

                        UiCheckinMetrics.timeScanSuspend {
                            val clubId =
                                call.parameters["clubId"]?.toLongOrNull()
                                    ?: run {
                                        UiCheckinMetrics.incError()
                                        call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_club_id)
                                        return@timeScanSuspend
                                    }

                            MdcContext.withIds(clubId = clubId) {
                                val tracer = Tracing.tracer
                                tracer.spanSuspended("checkin.scan") { span ->
                                    span.setAttribute("club.id", clubId.toLong())
                                    var scanResult = CheckinScanResult.INVALID
                                    fun setResult(result: CheckinScanResult) {
                                        scanResult = result
                                        span.setCheckinResult(result)
                                    }

                                    val payload =
                                        call.receiveScanPayloadOrNull()
                                            ?: run {
                                                UiCheckinMetrics.incError()
                                                setResult(CheckinScanResult.INVALID_JSON)
                                                logger.warn(
                                                    "checkin.scan error={} clubId={}",
                                                    ErrorCodes.invalid_json,
                                                    clubId,
                                                )
                                                call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)
                                                return@spanSuspended
                                            }

                                    val qr = payload.qr.trim()
                                    span.setAttribute(
                                        "checkin.scan.type",
                                        if (qr.startsWith("INV:")) SCAN_TYPE_PROMOTER_INVITE else "guestlist",
                                    )
                                    val primarySecret = qrSecretProvider()
                                    val oldSecret =
                                        oldQrSecretProvider()?.takeIf {
                                            it.isNotBlank() && it != primarySecret
                                        }
                                    val now = Instant.now(clock)
                                    if (qr.startsWith("INV:")) {
                                        val decodedInvite =
                                            PromoterInviteQrCodec.tryDecode(qr, primarySecret)
                                                ?: oldSecret?.let {
                                                    PromoterInviteQrCodec.tryDecode(qr, it)?.also {
                                                        UiCheckinMetrics.incOldSecretFallback()
                                                    }
                                                }
                                        if (decodedInvite == null) {
                                            UiCheckinMetrics.incQrInvalid()
                                            UiCheckinMetrics.incError()
                                            setResult(CheckinScanResult.INVALID)
                                            logger.warn(
                                                "checkin.scan promoter_invite error={} clubId={} qr={}",
                                                ErrorCodes.invalid_or_expired_qr,
                                                clubId,
                                                maskQrToken(qr),
                                            )
                                            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_or_expired_qr)
                                            return@spanSuspended
                                        }

                                        val marked = promoterInviteService?.markArrivedById(decodedInvite.inviteId, now) ?: false
                                        if (!marked) {
                                            UiCheckinMetrics.incError()
                                            setResult(CheckinScanResult.INVALID_STATE)
                                            logger.warn(
                                                "checkin.scan promoter_invite error={} clubId={} inviteId={}",
                                                ErrorCodes.invalid_state,
                                                clubId,
                                                decodedInvite.inviteId,
                                            )
                                            call.respondError(HttpStatusCode.Conflict, ErrorCodes.invalid_state)
                                            return@spanSuspended
                                        }

                                        setResult(CheckinScanResult.SUCCESS)
                                        logger.info(
                                            "checkin.scan promoter_invite status=arrived clubId={} inviteId={} eventId={}",
                                            clubId,
                                            decodedInvite.inviteId,
                                            decodedInvite.eventId,
                                        )
                                        call.respond(
                                            HttpStatusCode.OK,
                                            PromoterInviteScanResponse(
                                                status = SCAN_STATUS_ARRIVED,
                                                type = SCAN_TYPE_PROMOTER_INVITE,
                                                inviteId = decodedInvite.inviteId,
                                            ),
                                        )
                                        return@spanSuspended
                                    }

                                    val qrValidation = com.example.bot.guestlists.quickValidateQr(qr)
                                    if (qrValidation != null) {
                                        UiCheckinMetrics.incQrInvalid()
                                        UiCheckinMetrics.incError()
                                        setResult(CheckinScanResult.INVALID_FORMAT)
                                        logger.warn(
                                            "checkin.scan error={} clubId={} qr={}",
                                            qrValidation,
                                            clubId,
                                            maskQrToken(qr),
                                        )
                                        call.respondError(HttpStatusCode.BadRequest, qrValidation)
                                        return@spanSuspended
                                    }

                                    val primaryVerification =
                                        runCatching {
                                            QrGuestListCodec.verifyWithReason(qr, now, qrTtl, primarySecret)
                                        }.getOrDefault(QrVerificationResult.Invalid)
                                    val oldSecretVerification =
                                        if (primaryVerification is QrVerificationResult.Valid) {
                                            null
                                        } else {
                                            oldSecret?.let {
                                                runCatching {
                                                    QrGuestListCodec.verifyWithReason(qr, now, qrTtl, it)
                                                }.getOrDefault(QrVerificationResult.Invalid)
                                            }
                                        }
                                    val decoded =
                                        when {
                                            primaryVerification is QrVerificationResult.Valid -> primaryVerification.decoded
                                            oldSecretVerification is QrVerificationResult.Valid -> {
                                                UiCheckinMetrics.incOldSecretFallback()
                                                oldSecretVerification.decoded
                                            }
                                            else -> null
                                        }
                                    if (decoded == null) {
                                        when {
                                            primaryVerification is QrVerificationResult.Expired ||
                                                oldSecretVerification is QrVerificationResult.Expired -> {
                                                UiCheckinMetrics.incQrExpired()
                                                setResult(CheckinScanResult.EXPIRED)
                                            }
                                            else -> {
                                                UiCheckinMetrics.incQrInvalid()
                                                setResult(CheckinScanResult.INVALID)
                                            }
                                        }
                                        UiCheckinMetrics.incError()
                                        logger.warn(
                                            "checkin.scan error={} clubId={} qr={}",
                                            ErrorCodes.invalid_or_expired_qr,
                                            clubId,
                                            maskQrToken(qr),
                                        )
                                        call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_or_expired_qr)
                                        return@spanSuspended
                                    }

                                    span.setAttribute("guestlist.list_id", decoded.listId)
                                    span.setAttribute("guestlist.entry_id", decoded.entryId)
                                    var currentResult = scanResult
                                    MdcContext.withIds(listId = decoded.listId, entryId = decoded.entryId) {
                                        val list =
                                            withContext(Dispatchers.IO + MDCContext()) {
                                                repository.getList(decoded.listId)
                                            }
                                                ?: run {
                                                    UiCheckinMetrics.incError()
                                                    currentResult = CheckinScanResult.LIST_NOT_FOUND
                                                    logger.warn(
                                                        "checkin.scan error={} clubId={} listId={}",
                                                        ErrorCodes.list_not_found,
                                                        clubId,
                                                        decoded.listId,
                                                    )
                                                    call.respondError(HttpStatusCode.NotFound, ErrorCodes.list_not_found)
                                                    return@withIds
                                                }

                                        if (list.clubId != clubId) {
                                            UiCheckinMetrics.incQrScopeMismatch()
                                            UiCheckinMetrics.incError()
                                            currentResult = CheckinScanResult.SCOPE_MISMATCH
                                            logger.warn(
                                                "checkin.scan error={} clubId={} listId={} listClubId={}",
                                                ErrorCodes.club_scope_mismatch,
                                                clubId,
                                                list.id,
                                                list.clubId,
                                            )
                                            call.respondError(HttpStatusCode.Forbidden, ErrorCodes.club_scope_mismatch)
                                            return@withIds
                                        }

                                        val entry =
                                            withContext(Dispatchers.IO + MDCContext()) {
                                                repository.findEntry(decoded.entryId)
                                            }
                                                ?: run {
                                                    UiCheckinMetrics.incError()
                                                    currentResult = CheckinScanResult.ENTRY_NOT_FOUND
                                                    logger.warn(
                                                        "checkin.scan error={} clubId={} listId={} entryId={}",
                                                        ErrorCodes.entry_not_found,
                                                        clubId,
                                                        decoded.listId,
                                                        decoded.entryId,
                                                    )
                                                    call.respondError(HttpStatusCode.NotFound, ErrorCodes.entry_not_found)
                                                    return@withIds
                                                }

                                        if (entry.listId != list.id) {
                                            UiCheckinMetrics.incQrScopeMismatch()
                                            UiCheckinMetrics.incError()
                                            currentResult = CheckinScanResult.ENTRY_LIST_MISMATCH
                                            logger.warn(
                                                "checkin.scan error={} clubId={} listId={} entryListId={} entryId={}",
                                                ErrorCodes.entry_list_mismatch,
                                                clubId,
                                                list.id,
                                                entry.listId,
                                                entry.id,
                                            )
                                            call.respondError(HttpStatusCode.BadRequest, ErrorCodes.entry_list_mismatch)
                                            return@withIds
                                        }

                                        if (entry.status in alreadyCheckedInStatuses && entry.checkedInAt != null) {
                                            UiCheckinMetrics.incError()
                                            currentResult = CheckinScanResult.ALREADY_CHECKED_IN
                                            call.respondError(
                                                HttpStatusCode.Conflict,
                                                ErrorCodes.already_checked_in,
                                                message = ErrorCodes.already_checked_in,
                                                details = alreadyCheckedInDetails(entry),
                                            )
                                            return@withIds
                                        }

                                        val withinWindow = isWithinWindow(now, list.arrivalWindowStart, list.arrivalWindowEnd)
                                        if (!withinWindow && entry.status != GuestListEntryStatus.CALLED) {
                                            UiCheckinMetrics.incError()
                                            currentResult = CheckinScanResult.OUTSIDE_ARRIVAL_WINDOW
                                            call.respondError(
                                                HttpStatusCode.Conflict,
                                                ErrorCodes.outside_arrival_window,
                                            )
                                            return@withIds
                                        } else if (!withinWindow) {
                                            UiCheckinMetrics.incLateOverride()
                                        }

                                        val checkedInAt = Instant.now(clock)
                                        val marked =
                                            withContext(Dispatchers.IO + MDCContext()) {
                                                repository.setEntryStatus(
                                                    entryId = entry.id,
                                                    status = GuestListEntryStatus.CHECKED_IN,
                                                    checkedInBy = call.rbacContext().user.id,
                                                    at = checkedInAt,
                                                )
                                            }
                                        if (marked == null) {
                                            val updatedEntry =
                                                withContext(Dispatchers.IO + MDCContext()) {
                                                    repository.findEntry(entry.id)
                                                }
                                            if (updatedEntry != null &&
                                                updatedEntry.status in alreadyCheckedInStatuses &&
                                                updatedEntry.checkedInAt != null
                                            ) {
                                                UiCheckinMetrics.incError()
                                                currentResult = CheckinScanResult.ALREADY_CHECKED_IN
                                                call.respondError(
                                                    HttpStatusCode.Conflict,
                                                    ErrorCodes.already_checked_in,
                                                    message = ErrorCodes.already_checked_in,
                                                    details = alreadyCheckedInDetails(updatedEntry),
                                                )
                                                return@withIds
                                            }

                                            UiCheckinMetrics.incError()
                                            currentResult = CheckinScanResult.UNABLE_TO_MARK
                                            logger.warn(
                                                "checkin.scan error={} clubId={} listId={} entryId={}",
                                                ErrorCodes.unable_to_mark,
                                                clubId,
                                                list.id,
                                                entry.id,
                                            )
                                            call.respondError(HttpStatusCode.Conflict, ErrorCodes.unable_to_mark)
                                            return@withIds
                                        }

                                        currentResult = CheckinScanResult.SUCCESS
                                        logger.info(
                                            "checkin.scan status=arrived clubId={} listId={} entryId={}",
                                            clubId,
                                            list.id,
                                            entry.id,
                                        )
                                        call.respond(HttpStatusCode.OK, mapOf("status" to "ARRIVED"))
                                    }
                                    scanResult = currentResult
                                    span.setCheckinResult(scanResult)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private suspend fun ApplicationCall.receiveScanPayloadOrNull(): ScanPayload? =
    runCatching { receive<ScanPayload>() }.getOrNull()

private fun isWithinWindow(
    now: Instant,
    start: Instant?,
    end: Instant?,
): Boolean {
    val afterStart = start?.let { !now.isBefore(it) } ?: true
    val beforeEnd = end?.let { !now.isAfter(it) } ?: true
    return afterStart && beforeEnd
}

private val alreadyCheckedInStatuses = setOf(
    GuestListEntryStatus.CHECKED_IN,
    GuestListEntryStatus.ARRIVED,
    GuestListEntryStatus.LATE,
)

private fun alreadyCheckedInDetails(entry: com.example.bot.club.GuestListEntry): Map<String, String> {
    val details = mutableMapOf<String, String>()
    entry.checkedInAt?.let { details["occurred_at"] = it.toString() }
    entry.checkedInBy?.let { details["checked_by"] = it.toString() }
    return details
}
