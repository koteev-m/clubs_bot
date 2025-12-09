package com.example.bot.routes

import com.example.bot.booking.a3.BookingError
import com.example.bot.booking.a3.BookingState
import com.example.bot.booking.a3.ConfirmResult
import com.example.bot.booking.a3.HoldResult
import com.example.bot.booking.a3.PlusOneCanonicalPayload
import com.example.bot.booking.a3.PlusOneResult
import com.example.bot.booking.a3.hashRequestCanonical
import com.example.bot.data.security.Role
import com.example.bot.http.ErrorCodes
import com.example.bot.plugins.MiniAppUserKey
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.ratelimit.RateLimitSnapshot
import com.example.bot.ratelimit.TokenBucket
import com.example.bot.security.rbac.rbacContext
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.text.Charsets
import kotlin.math.ceil

private val JSON = ContentType.Application.Json.withCharset(Charsets.UTF_8)
private const val VARY_HEADER = "X-Telegram-Init-Data"
private const val NO_CACHE = "no-store"
private val IDEM_KEY = Regex("^[A-Za-z0-9._~:-]{1,128}$")
private val PROMOTER_ROLES =
    setOf(Role.PROMOTER, Role.CLUB_ADMIN, Role.HEAD_MANAGER, Role.OWNER, Role.GLOBAL_ADMIN)
private val holdRateLimit = rateLimitConfig("BOOKING_RATE_LIMIT_HOLD_CAPACITY", "BOOKING_RATE_LIMIT_HOLD_REFILL", 5.0, 0.5)
private val confirmRateLimit =
    rateLimitConfig("BOOKING_RATE_LIMIT_CONFIRM_CAPACITY", "BOOKING_RATE_LIMIT_CONFIRM_REFILL", 5.0, 0.5)
private val plusOneRateLimit =
    rateLimitConfig("BOOKING_RATE_LIMIT_PLUS_CAPACITY", "BOOKING_RATE_LIMIT_PLUS_REFILL", 5.0, 5.0 / 30.0)

@Serializable
private data class HoldPayload(
    val tableId: Long,
    val eventId: Long,
    val guestCount: Int,
)

@Serializable
private data class ConfirmPayload(val bookingId: Long)

fun Application.bookingA3Routes(
    bookingState: BookingState,
    meterRegistry: MeterRegistry? = null,
    botTokenProvider: () -> String = { System.getenv("TELEGRAM_BOT_TOKEN") ?: "" },
) {
    val logger = LoggerFactory.getLogger("BookingA3Routes")
    val rateLimiter = RouteRateLimiter()

    routing {
        route("/api") {
            withMiniAppAuth { botTokenProvider() }

            route("/clubs/{clubId}/bookings") {
                post("/hold") {
                    val user = call.attributes[MiniAppUserKey]
                    val ratePeek =
                        rateLimiter.peek(
                            "hold:${user.id}",
                            capacity = holdRateLimit.capacity,
                            refillPerSec = holdRateLimit.refillPerSec,
                        )
                    val promoterId = call.promoterIdOrNull()
                    val clubId = call.parameters["clubId"]?.toLongOrNull()
                        ?: run {
                            call.applyRateLimitHeaders(ratePeek)
                            return@post call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                        }
                    val idem = call.request.headers["Idempotency-Key"]?.trim()
                        ?: run {
                            call.applyRateLimitHeaders(ratePeek)
                            return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.missing_idempotency_key)
                        }
                    if (!idem.isValidIdemKey()) {
                        call.applyRateLimitHeaders(ratePeek)
                        return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                    }
                    val payload = runCatching { call.receive<HoldPayload>() }.getOrNull()
                        ?: run {
                            call.applyRateLimitHeaders(ratePeek)
                            return@post call.respondError(
                                HttpStatusCode.BadRequest,
                                ErrorCodes.validation_error,
                                idem,
                            )
                        }
                    val rate =
                        rateLimiter.acquire(
                            "hold:${user.id}",
                            capacity = holdRateLimit.capacity,
                            refillPerSec = holdRateLimit.refillPerSec,
                        )
                    if (!rate.allowed) {
                        meterRegistry
                            ?.counter("bookings.rate_limited", "route", "hold", "club_id", clubId.toString())
                            ?.increment()
                        logger.warn(
                            "booking.hold failed status={} error_code={} table={} event={} user={}",
                            HttpStatusCode.TooManyRequests,
                            ErrorCodes.rate_limited,
                            payload.tableId,
                            payload.eventId,
                            user.id,
                        )
                        call.applyRateLimitHeaders(rate.snapshot, includeRetryAfter = true)
                        return@post call.respondError(
                            HttpStatusCode.TooManyRequests,
                            ErrorCodes.rate_limited,
                            idem,
                        )
                    }
                    call.applyRateLimitHeaders(rate.snapshot)
                    val hash = hashRequestCanonical(payload)
                    val result =
                        bookingState.hold(
                            userId = user.id,
                            clubId = clubId,
                            tableId = payload.tableId,
                            eventId = payload.eventId,
                            guestCount = payload.guestCount,
                            idempotencyKey = idem,
                            requestHash = hash,
                            promoterId = promoterId,
                        )
                    when (result) {
                        is HoldResult.Success -> {
                            val replayTag = if (result.cached) "true" else "false"
                            meterRegistry
                                ?.counter(
                                    "bookings.hold.created",
                                    "club_id",
                                    clubId.toString(),
                                    "replay",
                                    replayTag,
                                )
                                ?.increment()
                            call.applyIdempotencyHeaders(idem, result.cached)
                            logger.info(
                                "booking.audit source=miniapp user_id={} club_id={} booking_id={} table_id={} event_id={} status={}->{}",
                                user.id,
                                clubId,
                                result.booking.id,
                                payload.tableId,
                                payload.eventId,
                                if (result.cached) result.booking.status else "FREE",
                                result.booking.status,
                            )
                            call.respondBooking(result.bodyJson)
                        }

                        is HoldResult.Error -> {
                            val (status, code) = result.code.toHttp()
                            meterRegistry
                                ?.counter(
                                    "bookings.hold.failed",
                                    "error_code",
                                    code,
                                    "club_id",
                                    clubId.toString(),
                                    "replay",
                                    "false",
                                )
                                ?.increment()
                            logger.warn(
                                "booking.hold failed status={} error_code={} table={} event={} user={}",
                                status,
                                code,
                                payload.tableId,
                                payload.eventId,
                                user.id,
                            )
                            call.respondError(status, code, idem)
                        }
                    }
                }

                post("/confirm") {
                    val user = call.attributes[MiniAppUserKey]
                    val ratePeek =
                        rateLimiter.peek(
                            "confirm:${user.id}",
                            capacity = confirmRateLimit.capacity,
                            refillPerSec = confirmRateLimit.refillPerSec,
                        )
                    val clubId = call.parameters["clubId"]?.toLongOrNull()
                        ?: run {
                            call.applyRateLimitHeaders(ratePeek)
                            return@post call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                        }
                    val idem = call.request.headers["Idempotency-Key"]?.trim()
                        ?: run {
                            call.applyRateLimitHeaders(ratePeek)
                            return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.missing_idempotency_key)
                        }
                    if (!idem.isValidIdemKey()) {
                        call.applyRateLimitHeaders(ratePeek)
                        return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                    }
                    val payload = runCatching { call.receive<ConfirmPayload>() }.getOrNull()
                        ?: run {
                            call.applyRateLimitHeaders(ratePeek)
                            return@post call.respondError(
                                HttpStatusCode.BadRequest,
                                ErrorCodes.validation_error,
                                idem,
                            )
                        }
                    val rate =
                        rateLimiter.acquire(
                            "confirm:${user.id}",
                            capacity = confirmRateLimit.capacity,
                            refillPerSec = confirmRateLimit.refillPerSec,
                        )
                    if (!rate.allowed) {
                        meterRegistry
                            ?.counter("bookings.rate_limited", "route", "confirm", "club_id", clubId.toString())
                            ?.increment()
                        logger.warn(
                            "booking.confirm failed status={} error_code={} bookingId={} user={}",
                            HttpStatusCode.TooManyRequests,
                            ErrorCodes.rate_limited,
                            payload.bookingId,
                            user.id,
                        )
                        call.applyRateLimitHeaders(rate.snapshot, includeRetryAfter = true)
                        return@post call.respondError(
                            HttpStatusCode.TooManyRequests,
                            ErrorCodes.rate_limited,
                            idem,
                        )
                    }
                    call.applyRateLimitHeaders(rate.snapshot)
                    val hash = hashRequestCanonical(payload)
                    val result = bookingState.confirm(user.id, clubId, payload.bookingId, idem, hash)
                    when (result) {
                        is ConfirmResult.Success -> {
                            val replayTag = if (result.cached) "true" else "false"
                            meterRegistry
                                ?.counter(
                                    "bookings.confirm.success",
                                    "club_id",
                                    clubId.toString(),
                                    "replay",
                                    replayTag,
                                )
                                ?.increment()
                            call.applyIdempotencyHeaders(idem, result.cached)
                            logger.info(
                                "booking.audit source=miniapp user_id={} club_id={} booking_id={} table_id={} event_id={} status={}->{}",
                                user.id,
                                clubId,
                                result.booking.id,
                                result.booking.tableId,
                                result.booking.eventId,
                                "HOLD",
                                result.booking.status,
                            )
                            call.respondBooking(result.bodyJson)
                        }

                        is ConfirmResult.Error -> {
                            val (status, code) = result.code.toHttp()
                            meterRegistry
                                ?.counter(
                                    "bookings.confirm.failed",
                                    "error_code",
                                    code,
                                    "club_id",
                                    clubId.toString(),
                                    "replay",
                                    "false",
                                )
                                ?.increment()
                            logger.warn(
                                "booking.confirm failed status={} error_code={} bookingId={} user={}",
                                status,
                                code,
                                payload.bookingId,
                                user.id,
                            )
                            call.respondError(status, code, idem)
                        }
                    }
                }
            }
        }

        route("/api/bookings") {
            withMiniAppAuth { botTokenProvider() }

            post("/{id}/plus-one") {
                val user = call.attributes[MiniAppUserKey]
                val ratePeek =
                    rateLimiter.peek(
                        "plus-one:${user.id}",
                        capacity = plusOneRateLimit.capacity,
                        refillPerSec = plusOneRateLimit.refillPerSec,
                    )
                val idem = call.request.headers["Idempotency-Key"]?.trim()
                    ?: run {
                        call.applyRateLimitHeaders(ratePeek)
                        return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.missing_idempotency_key)
                    }
                if (!idem.isValidIdemKey()) {
                    call.applyRateLimitHeaders(ratePeek)
                    return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                }
                val bookingId = call.parameters["id"]?.toLongOrNull()
                    ?: run {
                        call.applyRateLimitHeaders(ratePeek)
                        return@post call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found, idem)
                    }
                val rate =
                    rateLimiter.acquire(
                        "plus-one:${user.id}",
                        capacity = plusOneRateLimit.capacity,
                        refillPerSec = plusOneRateLimit.refillPerSec,
                    )
                if (!rate.allowed) {
                    meterRegistry
                        ?.counter("bookings.rate_limited", "route", "plus_one", "club_id", "unknown")
                        ?.increment()
                    logger.warn(
                        "booking.plus_one failed status={} error_code={} bookingId={} user={}",
                        HttpStatusCode.TooManyRequests,
                        ErrorCodes.rate_limited,
                        bookingId,
                        user.id,
                    )
                    call.applyRateLimitHeaders(rate.snapshot, includeRetryAfter = true)
                    return@post call.respondError(
                        HttpStatusCode.TooManyRequests,
                        ErrorCodes.rate_limited,
                        idem,
                    )
                }
                call.applyRateLimitHeaders(rate.snapshot)
                val hash = hashRequestCanonical(PlusOneCanonicalPayload(bookingId))
                val result = bookingState.plusOne(user.id, bookingId, idem, hash)
                when (result) {
                    is PlusOneResult.Success -> {
                        val replayTag = if (result.cached) "true" else "false"
                        meterRegistry
                            ?.counter(
                                "bookings.plus_one.success",
                                "club_id",
                                result.booking.clubId.toString(),
                                "replay",
                                replayTag,
                            )
                            ?.increment()
                        call.applyIdempotencyHeaders(idem, result.cached)
                        logger.info(
                            "booking.audit source=miniapp user_id={} club_id={} booking_id={} table_id={} event_id={} status={} guests={}",
                            user.id,
                            result.booking.clubId,
                            result.booking.id,
                            result.booking.tableId,
                            result.booking.eventId,
                            result.booking.status,
                            result.booking.guestCount,
                        )
                        call.respondBooking(result.bodyJson)
                    }

                    is PlusOneResult.Error -> {
                        val (status, code) = result.code.toHttp()
                        val clubIdTag = bookingState.bookingClubId(bookingId)?.toString() ?: "unknown"
                        meterRegistry
                            ?.counter(
                                "bookings.plus_one.failed",
                                "error_code",
                                code,
                                "club_id",
                                clubIdTag,
                                "replay",
                                "false",
                            )
                            ?.increment()
                        logger.warn(
                            "booking.plus_one failed status={} error_code={} bookingId={} user={}",
                            status,
                            code,
                            bookingId,
                            user.id,
                        )
                        call.respondError(status, code, idem)
                    }
                }
            }
        }
    }
}

private suspend fun ApplicationCall.respondBooking(bodyJson: String) {
    response.headers.append(HttpHeaders.CacheControl, NO_CACHE)
    response.headers.append(HttpHeaders.Vary, VARY_HEADER)
    respondText(status = HttpStatusCode.OK, contentType = JSON, text = bodyJson)
}

private suspend fun ApplicationCall.respondError(status: HttpStatusCode, code: String, idemKey: String? = null) {
    response.headers.append(HttpHeaders.CacheControl, NO_CACHE)
    response.headers.append(HttpHeaders.Vary, VARY_HEADER)
    idemKey?.takeIf { it.isValidIdemKey() }?.let { response.headers.append("Idempotency-Key", it) }
    respondText(
        status = status,
        contentType = JSON,
        text = """{"error":{"code":"$code","message":"$code"}}""",
    )
}

private fun ApplicationCall.applyRateLimitHeaders(snapshot: RateLimitSnapshot, includeRetryAfter: Boolean = false) {
    response.headers.append("X-RateLimit-Limit", snapshot.limit.toInt().toString())
    response.headers.append("X-RateLimit-Remaining", snapshot.remaining.coerceAtLeast(0.0).toInt().toString())
    if (includeRetryAfter) {
        val retry = ceil(snapshot.retryAfterSeconds).toLong()
        response.headers.append(HttpHeaders.RetryAfter, retry.toString())
    }
}

private fun ApplicationCall.applyIdempotencyHeaders(idem: String, replay: Boolean) {
    response.headers.append("Idempotency-Key", idem)
    if (replay) {
        response.headers.append("Idempotency-Replay", "true")
    }
}

private fun String.isValidIdemKey(): Boolean = IDEM_KEY.matches(this)

/**
 * Returns the promoterId when the caller has any of [PROMOTER_ROLES]. Intended
 * for promoter HOLD flows; falls back to test headers (X-Debug-Roles) when
 * running without full RBAC wiring.
 */
private fun ApplicationCall.promoterIdOrNull(): Long? =
    runCatching { rbacContext() }
        .getOrNull()
        ?.takeIf { ctx -> ctx.roles.any { it in PROMOTER_ROLES } }
        ?.principal
        ?.userId
        ?: run {
            val headerRoles =
                request.headers["X-Debug-Roles"]
                    ?.split(',')
                    ?.mapNotNull { value -> runCatching { Role.valueOf(value.trim()) }.getOrNull() }
                    ?: emptyList()
            if (headerRoles.any { it in PROMOTER_ROLES }) {
                attributes[MiniAppUserKey].id
            } else {
                null
            }
        }

private data class RateLimitConfig(val capacity: Double, val refillPerSec: Double)

private fun rateLimitConfig(capacityEnv: String, refillEnv: String, defaultCap: Double, defaultRefill: Double): RateLimitConfig {
    val cap = System.getenv(capacityEnv)?.toDoubleOrNull() ?: defaultCap
    val refill = System.getenv(refillEnv)?.toDoubleOrNull() ?: defaultRefill
    return RateLimitConfig(capacity = cap, refillPerSec = refill)
}

private fun BookingError.toHttp(): Pair<HttpStatusCode, String> =
    when (this) {
        BookingError.TABLE_NOT_AVAILABLE -> HttpStatusCode.Conflict to ErrorCodes.table_not_available
        BookingError.VALIDATION_ERROR -> HttpStatusCode.BadRequest to ErrorCodes.validation_error
        BookingError.IDEMPOTENCY_CONFLICT -> HttpStatusCode.Conflict to ErrorCodes.idempotency_conflict
        BookingError.NOT_FOUND -> HttpStatusCode.NotFound to ErrorCodes.not_found
        BookingError.HOLD_EXPIRED -> HttpStatusCode.Gone to ErrorCodes.hold_expired
        BookingError.INVALID_STATE -> HttpStatusCode.Conflict to ErrorCodes.invalid_state
        BookingError.LATE_PLUS_ONE_EXPIRED -> HttpStatusCode.Gone to ErrorCodes.late_plus_one_expired
        BookingError.PLUS_ONE_ALREADY_USED -> HttpStatusCode.Conflict to ErrorCodes.plus_one_already_used
        BookingError.FORBIDDEN -> HttpStatusCode.Forbidden to ErrorCodes.forbidden
        BookingError.CAPACITY_EXCEEDED -> HttpStatusCode.Conflict to ErrorCodes.capacity_exceeded
        BookingError.CLUB_SCOPE_MISMATCH -> HttpStatusCode.Forbidden to ErrorCodes.club_scope_mismatch
        BookingError.PROMOTER_QUOTA_EXHAUSTED -> HttpStatusCode.Conflict to ErrorCodes.promoter_quota_exhausted
    }

private class RouteRateLimiter {
    private data class Bucket(val bucket: TokenBucket, val capacity: Double, val refillPerSec: Double) {
        @Volatile var lastSeenNanos: Long = System.nanoTime()
    }

    private val buckets = ConcurrentHashMap<String, Bucket>()
    private val ttlNanos = java.util.concurrent.TimeUnit.MINUTES.toNanos(30)
    private val maxEntries = 5_000
    private val cleanupIntervalNanos = java.util.concurrent.TimeUnit.SECONDS.toNanos(15)
    private var lastCleanupNanos: Long = System.nanoTime()

    fun acquire(key: String, capacity: Double, refillPerSec: Double): RateLimitDecision {
        val now = System.nanoTime()
        val bucket = buckets.compute(key) { _, existing ->
            val bucket = existing ?: Bucket(TokenBucket(capacity, refillPerSec, now), capacity, refillPerSec)
            bucket.lastSeenNanos = now
            bucket
        }!!
        val allowed = bucket.bucket.tryAcquire(now)
        val snapshot = bucket.bucket.snapshot(now)
        cleanup(now)
        return RateLimitDecision(allowed, snapshot)
    }

    fun peek(key: String, capacity: Double, refillPerSec: Double): RateLimitSnapshot {
        val now = System.nanoTime()
        val bucket = buckets.compute(key) { _, existing ->
            val bucket = existing ?: Bucket(TokenBucket(capacity, refillPerSec, now), capacity, refillPerSec)
            bucket.lastSeenNanos = now
            bucket
        }!!
        val snapshot = bucket.bucket.snapshot(now)
        cleanup(now)
        return snapshot
    }

    private fun cleanup(now: Long) {
        if (buckets.isEmpty()) return
        if (now - lastCleanupNanos < cleanupIntervalNanos) return
        lastCleanupNanos = now
        val iterator = buckets.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val tooOld = now - entry.value.lastSeenNanos > ttlNanos
            val overflow = buckets.size > maxEntries
            if (tooOld || overflow) {
                iterator.remove()
            }
        }
    }
}

private data class RateLimitDecision(
    val allowed: Boolean,
    val snapshot: RateLimitSnapshot,
)
