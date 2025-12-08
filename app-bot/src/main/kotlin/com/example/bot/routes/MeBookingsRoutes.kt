package com.example.bot.routes

import com.example.bot.booking.a3.BookingIcsRenderer
import com.example.bot.booking.a3.BookingState
import com.example.bot.booking.a3.BookingStatus
import com.example.bot.booking.a3.MeBookingsResponse
import com.example.bot.booking.a3.MyBookingView
import com.example.bot.booking.a3.QrBookingCodec
import com.example.bot.clubs.ClubsRepository
import com.example.bot.clubs.EventsRepository
import com.example.bot.http.ErrorCodes
import com.example.bot.http.matchesEtag
import com.example.bot.plugins.MiniAppUserKey
import com.example.bot.plugins.withMiniAppAuth
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.micrometer.core.instrument.MeterRegistry
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import kotlin.text.Charsets
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val JSON = ContentType.Application.Json.withCharset(Charsets.UTF_8)
private val CALENDAR = ContentType.parse("text/calendar; charset=utf-8")
private const val VARY_HEADER = "X-Telegram-Init-Data"
private const val NO_STORE = "no-store"
private const val QR_CACHE_CONTROL = "max-age=60, must-revalidate"
private const val ICS_CACHE_CONTROL = "max-age=60, must-revalidate"

@Serializable
private data class BookingQrResponse(
    val bookingId: Long,
    val clubId: Long,
    val eventId: Long,
    val qrPayload: String,
)

fun Application.meBookingsRoutes(
    bookingState: BookingState,
    eventsRepository: EventsRepository,
    clubsRepository: ClubsRepository,
    meterRegistry: MeterRegistry? = null,
    botTokenProvider: () -> String = { System.getenv("TELEGRAM_BOT_TOKEN") ?: "" },
    qrSecretProvider: () -> String = { System.getenv("QR_SECRET") ?: "" },
) {
    val logger = LoggerFactory.getLogger("MeBookingsRoutes")

    routing {
        route("/api") {
            withMiniAppAuth { botTokenProvider() }

            get("/me/bookings") {
                val user = call.attributes[MiniAppUserKey]
                val status = call.request.queryParameters["status"]?.lowercase() ?: "upcoming"
                if (status != "upcoming" && status != "past") {
                    meterRegistry
                        ?.counter("me_bookings.list.failed", "status", status, "error_code", ErrorCodes.validation_error)
                        ?.increment()
                    logger.warn("booking.me_list error={} user_id={}", ErrorCodes.validation_error, user.id)
                    return@get call.respondMyBookingsError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                }

                val now = bookingState.now()
                val bookings = bookingState.findUserBookings(user.id)
                val filtered = bookings.filter { booking ->
                    val end = booking.arrivalWindow.second
                    val isPast =
                        (booking.status == BookingStatus.BOOKED || booking.status == BookingStatus.CANCELED) && end.isBefore(now)
                    val isUpcoming = booking.status == BookingStatus.BOOKED && !end.isBefore(now)
                    when (status) {
                        "past" -> isPast
                        else -> isUpcoming
                    }
                }

                val views = filtered
                    .sortedBy { it.arrivalWindow.first }
                    .map { booking ->
                        val snapshot = bookingState.snapshotOf(booking)
                        val canPlusOne = canAddPlusOne(booking, now)
                        val isPast = booking.arrivalWindow.second.isBefore(now)
                        MyBookingView(
                            booking = snapshot.booking,
                            arrivalWindow = snapshot.arrivalWindow,
                            latePlusOneAllowedUntil = snapshot.latePlusOneAllowedUntil,
                            canPlusOne = canPlusOne,
                            isPast = isPast,
                            arriveBy = booking.arrivalWindow.second.toString(),
                        )
                    }

                val clubTag =
                    views.map { it.booking.clubId }.distinct().let { ids ->
                        if (ids.size == 1) ids.first().toString() else "multi"
                    }
                meterRegistry
                    ?.counter("me_bookings.list.success", "status", status, "club_id", clubTag)
                    ?.increment()
                logger.info(
                    "booking.me_list ok user_id={} status={} count={}",
                    user.id,
                    status,
                    views.size,
                )
                call.applyPersonalHeaders(NO_STORE)
                call.respond(MeBookingsResponse(views))
            }

            route("/bookings") {
                get("/{id}/qr") {
                    val user = call.attributes[MiniAppUserKey]
                    val id = call.parameters["id"]?.toLongOrNull()
                        ?: return@get call.respondMyBookingsError(HttpStatusCode.NotFound, ErrorCodes.not_found)

                    val booking = bookingState.findBookingById(id)
                        ?: return@get call.respondMyBookingsError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                    if (booking.userId != user.id) {
                        meterRegistry
                            ?.counter("me_bookings.qr.failed", "club_id", booking.clubId.toString(), "error_code", ErrorCodes.forbidden)
                            ?.increment()
                        logger.warn(
                            "booking.qr error={} booking_id={} club_id={} user_id={}",
                            ErrorCodes.forbidden,
                            id,
                            booking.clubId,
                            user.id,
                        )
                        return@get call.respondMyBookingsError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
                    }
                    if (booking.status != BookingStatus.BOOKED) {
                        meterRegistry
                            ?.counter(
                                "me_bookings.qr.failed",
                                "club_id",
                                booking.clubId.toString(),
                                "error_code",
                                ErrorCodes.invalid_state,
                            )
                            ?.increment()
                        logger.warn(
                            "booking.qr error={} booking_id={} club_id={} user_id={}",
                            ErrorCodes.invalid_state,
                            id,
                            booking.clubId,
                            user.id,
                        )
                        return@get call.respondMyBookingsError(HttpStatusCode.Conflict, ErrorCodes.invalid_state)
                    }

                    val secret = qrSecretProvider().takeIf { it.isNotBlank() }
                        ?: run {
                            meterRegistry
                                ?.counter(
                                    "me_bookings.qr.failed",
                                    "club_id",
                                    booking.clubId.toString(),
                                    "error_code",
                                    ErrorCodes.internal_error,
                                )
                                ?.increment()
                            logger.warn(
                                "booking.qr error={} booking_id={} club_id={} user_id={}",
                                ErrorCodes.internal_error,
                                id,
                                booking.clubId,
                                user.id,
                            )
                            return@get call.respondMyBookingsError(
                                HttpStatusCode.InternalServerError,
                                ErrorCodes.internal_error,
                            )
                        }
                    val issuedAt = booking.updatedAt
                    val payload = QrBookingCodec.encode(booking.id, booking.eventId, issuedAt, secret)
                    val etag = etagFromString(payload)
                    val ifNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch]
                    if (matchesEtag(ifNoneMatch, etag)) {
                        call.applyPersonalHeaders(QR_CACHE_CONTROL)
                        call.response.headers.append(HttpHeaders.ETag, etag)
                        call.response.headers.append(HttpHeaders.ContentType, JSON.toString())
                        return@get call.respond(HttpStatusCode.NotModified)
                    }

                    meterRegistry
                        ?.counter("me_bookings.qr.success", "club_id", booking.clubId.toString())
                        ?.increment()
                    logger.info(
                        "booking.qr ok booking_id={} club_id={} user_id={}",
                        id,
                        booking.clubId,
                        user.id,
                    )
                    call.applyPersonalHeaders(QR_CACHE_CONTROL)
                    call.response.headers.append(HttpHeaders.ETag, etag)
                    call.respond(
                        status = HttpStatusCode.OK,
                        message =
                            BookingQrResponse(
                                bookingId = booking.id,
                                clubId = booking.clubId,
                                eventId = booking.eventId,
                                qrPayload = payload,
                            ),
                    )
                }

                get("/{id}/ics") {
                    val user = call.attributes[MiniAppUserKey]
                    val id = call.parameters["id"]?.toLongOrNull()
                        ?: return@get call.respondMyBookingsError(HttpStatusCode.NotFound, ErrorCodes.not_found)

                    val booking = bookingState.findBookingById(id)
                        ?: return@get call.respondMyBookingsError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                    if (booking.userId != user.id) {
                        meterRegistry
                            ?.counter(
                                "me_bookings.ics.failed",
                                "club_id",
                                booking.clubId.toString(),
                                "error_code",
                                ErrorCodes.forbidden,
                            )
                            ?.increment()
                        logger.warn(
                            "booking.ics error={} booking_id={} club_id={} user_id={}",
                            ErrorCodes.forbidden,
                            id,
                            booking.clubId,
                            user.id,
                        )
                        return@get call.respondMyBookingsError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
                    }

                    val event = eventsRepository.findById(booking.clubId, booking.eventId)
                    val club =
                        runCatching {
                            clubsRepository.list(null, null, null, null, 0, 50)
                                .firstOrNull { it.id == booking.clubId }
                        }.getOrNull()
                    val ics = BookingIcsRenderer.render(booking, event, club)
                    val etag = etagFromString(ics)
                    val ifNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch]
                    if (matchesEtag(ifNoneMatch, etag)) {
                        call.applyPersonalHeaders(ICS_CACHE_CONTROL)
                        call.response.headers.append(HttpHeaders.ETag, etag)
                        call.response.headers.append(HttpHeaders.ContentType, CALENDAR.toString())
                        return@get call.respond(HttpStatusCode.NotModified)
                    }

                    meterRegistry
                        ?.counter("me_bookings.ics.success", "club_id", booking.clubId.toString())
                        ?.increment()
                    logger.info(
                        "booking.ics ok booking_id={} club_id={} user_id={}",
                        id,
                        booking.clubId,
                        user.id,
                    )
                    call.applyPersonalHeaders(ICS_CACHE_CONTROL)
                    call.response.headers.append(HttpHeaders.ETag, etag)
                    call.respondText(status = HttpStatusCode.OK, contentType = CALENDAR, text = ics)
                }
            }
        }
    }
}

private fun canAddPlusOne(booking: com.example.bot.booking.a3.Booking, now: Instant): Boolean {
    val deadline = booking.latePlusOneAllowedUntil
    val capacity = booking.capacityAtHold
    val withinDeadline = deadline != null && !now.isAfter(deadline)
    val capacityOk = capacity == null || booking.guestCount < capacity
    return booking.status == BookingStatus.BOOKED && !booking.plusOneUsed && withinDeadline && capacityOk
}

private fun etagFromString(payload: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray())
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

private fun io.ktor.server.application.ApplicationCall.applyPersonalHeaders(cacheControl: String) {
    response.headers.append(HttpHeaders.CacheControl, cacheControl)
    response.headers.append(HttpHeaders.Vary, VARY_HEADER)
}

private suspend fun io.ktor.server.application.ApplicationCall.respondMyBookingsError(
    status: HttpStatusCode,
    code: String,
) {
    applyPersonalHeaders(NO_STORE)
    respondText(
        status = status,
        contentType = JSON,
        text = """{"error":{"code":"$code","message":"$code"}}""",
    )
}
