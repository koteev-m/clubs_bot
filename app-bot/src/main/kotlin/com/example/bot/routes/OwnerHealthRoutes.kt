package com.example.bot.routes

import com.example.bot.http.ErrorCodes
import com.example.bot.http.ensureMiniAppNoStoreHeaders
import com.example.bot.http.etagFor
import com.example.bot.http.matchesEtag
import com.example.bot.http.respondError
import com.example.bot.metrics.RouteCacheMetrics
import com.example.bot.owner.OwnerHealthGranularity
import com.example.bot.owner.OwnerHealthPeriod
import com.example.bot.owner.OwnerHealthRequest
import com.example.bot.owner.OwnerHealthService
import com.example.bot.owner.OwnerHealthWatermarks
import com.example.bot.owner.ownerHealthEtagSeed
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.security.rbac.authorize
import com.example.bot.security.rbac.rbacContext
import com.example.bot.data.security.Role
import com.example.bot.layout.LayoutRepository
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.header
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.time.Clock

private const val CACHE_CONTROL = "max-age=60, must-revalidate"

fun Application.ownerHealthRoutes(
    service: OwnerHealthService,
    layoutRepository: LayoutRepository,
    clock: Clock = Clock.systemUTC(),
    botTokenProvider: () -> String = { System.getenv("TELEGRAM_BOT_TOKEN") ?: "" },
) {
    routing {
        route("/api/owner") {
            withMiniAppAuth { botTokenProvider() }

            authorize(Role.OWNER, Role.GLOBAL_ADMIN, Role.HEAD_MANAGER) {
                route("/health") {
                    get {
                        val clubId = call.requireClubId() ?: return@get
                        if (!call.isClubAllowed(clubId)) {
                            return@get call.respondForbidden()
                        }

                        val period = call.requirePeriod() ?: return@get
                        val granularity = call.requireGranularity() ?: return@get
                        val now = clock.instant()
                        val (fromInclusive, toExclusive) = when (period) {
                            OwnerHealthPeriod.WEEK -> now.minus(java.time.Duration.ofDays(7)) to now
                            OwnerHealthPeriod.MONTH -> now.minus(java.time.Duration.ofDays(30)) to now
                        }

                        val request =
                            OwnerHealthRequest(
                                clubId = clubId,
                                period = period,
                                granularity = granularity,
                                now = now,
                            )

                        val snapshot = service.healthForClub(request)
                        if (snapshot == null) {
                            call.ensureMiniAppNoStoreHeaders()
                            return@get call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                        }

                        val watermarks =
                            OwnerHealthWatermarks(
                                currentPeriodUpdatedAt = computeWatermark(layoutRepository, clubId),
                                previousPeriodUpdatedAt = computeWatermark(layoutRepository, clubId),
                            )

                        val etagSeed = ownerHealthEtagSeed(request, fromInclusive, toExclusive, watermarks)
                        val etag = etagFor(watermarks.currentPeriodUpdatedAt, snapshot.meta.eventsCount, etagSeed)
                        val ifNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch]
                        if (matchesEtag(ifNoneMatch, etag)) {
                            RouteCacheMetrics.recordNotModified("owner_health_api")
                            call.respondNotModified(etag)
                            return@get
                        }

                        RouteCacheMetrics.recordOk("owner_health_api")
                        call.respondWithCache(etag, snapshot)
                    }
                }
            }
        }
    }
}

private suspend fun ApplicationCall.respondForbidden() {
    ensureMiniAppNoStoreHeaders()
    respondError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
}

private suspend fun ApplicationCall.respondValidationErrors(details: Map<String, String>) {
    ensureMiniAppNoStoreHeaders()
    respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error, details = details)
}

private suspend fun ApplicationCall.requireClubId(): Long? {
    val clubId = request.queryParameters["clubId"]?.toLongOrNull()
    if (clubId == null || clubId <= 0) {
        respondValidationErrors(mapOf("clubId" to "must_be_positive"))
        return null
    }
    return clubId
}

private suspend fun ApplicationCall.requirePeriod(): OwnerHealthPeriod? {
    val raw = request.queryParameters["period"] ?: return OwnerHealthPeriod.WEEK
    return when (raw.lowercase()) {
        "week" -> OwnerHealthPeriod.WEEK
        "month" -> OwnerHealthPeriod.MONTH
        else -> {
            respondValidationErrors(mapOf("period" to "must_be_week_or_month"))
            null
        }
    }
}

private suspend fun ApplicationCall.requireGranularity(): OwnerHealthGranularity? {
    val raw = request.queryParameters["granularity"] ?: return OwnerHealthGranularity.FULL
    return when (raw.lowercase()) {
        "summary" -> OwnerHealthGranularity.SUMMARY
        "full" -> OwnerHealthGranularity.FULL
        else -> {
            respondValidationErrors(mapOf("granularity" to "must_be_summary_or_full"))
            null
        }
    }
}

private fun ApplicationCall.isClubAllowed(clubId: Long): Boolean {
    val context = rbacContext()
    val elevated = context.roles.any { it in setOf(Role.OWNER, Role.GLOBAL_ADMIN, Role.HEAD_MANAGER) }
    return elevated || clubId in context.clubIds
}

private suspend fun ApplicationCall.respondWithCache(etag: String, payload: Any) {
    response.header(HttpHeaders.ETag, etag)
    response.header(HttpHeaders.CacheControl, CACHE_CONTROL)
    response.header(HttpHeaders.Vary, "X-Telegram-Init-Data")
    respond(HttpStatusCode.OK, payload)
}

private suspend fun ApplicationCall.respondNotModified(etag: String) {
    response.header(HttpHeaders.ETag, etag)
    response.header(HttpHeaders.CacheControl, CACHE_CONTROL)
    response.header(HttpHeaders.Vary, "X-Telegram-Init-Data")
    respond(HttpStatusCode.NotModified)
}

private suspend fun computeWatermark(
    layoutRepository: LayoutRepository,
    clubId: Long,
): java.time.Instant? =
    runCatching { layoutRepository.lastUpdatedAt(clubId, null) }.getOrNull()
