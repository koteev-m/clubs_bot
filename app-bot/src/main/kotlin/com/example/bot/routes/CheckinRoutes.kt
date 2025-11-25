package com.example.bot.routes

import com.example.bot.club.GuestListEntryStatus
import com.example.bot.club.GuestListRepository
import com.example.bot.data.security.Role
import com.example.bot.guestlists.QrGuestListCodec
import com.example.bot.metrics.UiCheckinMetrics
import com.example.bot.plugins.DEFAULT_CHECKIN_MAX_BYTES
import com.example.bot.plugins.MAX_CHECKIN_MAX_BYTES
import com.example.bot.plugins.MIN_CHECKIN_MAX_BYTES
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.http.respondError
import com.example.bot.security.rbac.ClubScope
import com.example.bot.security.rbac.authorize
import com.example.bot.security.rbac.clubScoped
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

@Serializable
private data class ScanPayload(val qr: String)

fun Application.checkinRoutes(
    repository: GuestListRepository,
    botTokenProvider: () -> String = { System.getenv("TELEGRAM_BOT_TOKEN") ?: error("TELEGRAM_BOT_TOKEN missing") },
    qrSecretProvider: () -> String = { System.getenv("QR_SECRET") ?: error("QR_SECRET missing") },
    oldQrSecretProvider: () -> String? = { System.getenv("QR_OLD_SECRET") },
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
                            logger.warn("checkin.scan error=unsupported_media_type clubId={}", call.parameters["clubId"])
                            call.respondError(HttpStatusCode.UnsupportedMediaType, "unsupported_media_type")
                            return@post
                        }

                        UiCheckinMetrics.timeScanSuspend {
                            val clubId =
                                call.parameters["clubId"]?.toLongOrNull()
                                    ?: run {
                                        UiCheckinMetrics.incError()
                                        call.respondError(HttpStatusCode.BadRequest, "invalid_club_id")
                                        return@timeScanSuspend
                                    }

                            val payload =
                                call.receiveScanPayloadOrNull()
                                    ?: run {
                                        UiCheckinMetrics.incError()
                                        logger.warn("checkin.scan error=invalid_json clubId={}", clubId)
                                        call.respondError(HttpStatusCode.BadRequest, "invalid_json")
                                        return@timeScanSuspend
                                    }

                            val qrValidation = com.example.bot.guestlists.quickValidateQr(payload.qr)
                            if (qrValidation != null) {
                                UiCheckinMetrics.incError()
                                logger.warn("checkin.scan error={} clubId={}", qrValidation, clubId)
                                call.respondError(HttpStatusCode.BadRequest, qrValidation)
                                return@timeScanSuspend
                            }

                            val qr = payload.qr.trim()
                            val primarySecret = qrSecretProvider()
                            val oldSecret =
                                oldQrSecretProvider()?.takeIf {
                                    it.isNotBlank() && it != primarySecret
                                }
                            val now = Instant.now(clock)
                            val decoded =
                                runCatching {
                                    QrGuestListCodec.verify(qr, now, qrTtl, primarySecret)
                                }.getOrNull()
                                    ?: oldSecret?.let {
                                        runCatching {
                                            QrGuestListCodec.verify(qr, now, qrTtl, it)
                                        }.getOrNull()?.also {
                                            UiCheckinMetrics.incOldSecretFallback()
                                        }
                                    }
                            if (decoded == null) {
                                UiCheckinMetrics.incError()
                                logger.warn("checkin.scan error=invalid_or_expired_qr clubId={}", clubId)
                                call.respondError(HttpStatusCode.BadRequest, "invalid_or_expired_qr")
                                return@timeScanSuspend
                            }

                            val list =
                                withContext(Dispatchers.IO + MDCContext()) {
                                    repository.getList(decoded.listId)
                                }
                                    ?: run {
                                        UiCheckinMetrics.incError()
                                        logger.warn(
                                            "checkin.scan error=list_not_found clubId={} listId={}",
                                            clubId,
                                            decoded.listId,
                                        )
                                        call.respondError(HttpStatusCode.NotFound, "list_not_found")
                                        return@timeScanSuspend
                                    }

                            if (list.clubId != clubId) {
                                UiCheckinMetrics.incError()
                                logger.warn(
                                    "checkin.scan error=club_scope_mismatch clubId={} listId={} listClubId={}",
                                    clubId,
                                    list.id,
                                    list.clubId,
                                )
                                call.respondError(HttpStatusCode.Forbidden, "club_scope_mismatch")
                                return@timeScanSuspend
                            }

                            val entry =
                                withContext(Dispatchers.IO + MDCContext()) {
                                    repository.findEntry(decoded.entryId)
                                }
                                    ?: run {
                                        UiCheckinMetrics.incError()
                                        logger.warn(
                                            "checkin.scan error=entry_not_found clubId={} listId={} entryId={}",
                                            clubId,
                                            decoded.listId,
                                            decoded.entryId,
                                        )
                                        call.respondError(HttpStatusCode.NotFound, "entry_not_found")
                                        return@timeScanSuspend
                                    }

                            if (entry.listId != list.id) {
                                UiCheckinMetrics.incError()
                                logger.warn(
                                    "checkin.scan error=entry_list_mismatch clubId={} listId={} " +
                                        "entryListId={} entryId={}",
                                    clubId,
                                    list.id,
                                    entry.listId,
                                    entry.id,
                                )
                                call.respondError(HttpStatusCode.BadRequest, "entry_list_mismatch")
                                return@timeScanSuspend
                            }

                            val withinWindow = isWithinWindow(now, list.arrivalWindowStart, list.arrivalWindowEnd)
                            if (!withinWindow && entry.status != GuestListEntryStatus.CALLED) {
                                UiCheckinMetrics.incError()
                                call.respondError(HttpStatusCode.Conflict, "outside_arrival_window")
                                return@timeScanSuspend
                            } else if (!withinWindow) {
                                UiCheckinMetrics.incLateOverride()
                            }

                            val marked =
                                withContext(Dispatchers.IO + MDCContext()) {
                                    repository.markArrived(entry.id, Instant.now(clock))
                                }
                            if (!marked) {
                                UiCheckinMetrics.incError()
                                logger.warn(
                                    "checkin.scan error=unable_to_mark clubId={} listId={} entryId={}",
                                    clubId,
                                    list.id,
                                    entry.id,
                                )
                                call.respondError(HttpStatusCode.Conflict, "unable_to_mark")
                                return@timeScanSuspend
                            }

                            logger.info(
                                "checkin.scan status=arrived clubId={} listId={} entryId={}",
                                clubId,
                                list.id,
                                entry.id,
                            )
                            call.respond(HttpStatusCode.OK, mapOf("status" to "ARRIVED"))
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
