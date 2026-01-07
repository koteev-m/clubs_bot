package com.example.bot.routes

import com.example.bot.data.security.Role
import com.example.bot.http.ErrorCodes
import com.example.bot.http.respondError
import com.example.bot.plugins.MiniAppUserKey
import com.example.bot.plugins.miniAppBotTokenProvider
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.promoter.rating.PromoterPeriod
import com.example.bot.promoter.rating.PromoterRatingPage
import com.example.bot.promoter.rating.PromoterRatingRow
import com.example.bot.promoter.rating.PromoterRatingService
import com.example.bot.promoter.rating.PromoterScorecard
import com.example.bot.security.rbac.authorize
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private const val VARY_HEADER = "X-Telegram-Init-Data"
private const val NO_STORE = "no-store"
private val logger = LoggerFactory.getLogger("PromoterRatingRoutes")

@Serializable
private data class PromoterScorecardView(
    val period: String,
    val from: String,
    val to: String,
    val invited: Int,
    val arrivals: Int,
    val noShows: Int,
    val conversionScore: Double,
)

@Serializable
private data class PromoterRatingRowView(
    val promoterId: Long,
    val invited: Int,
    val arrivals: Int,
    val noShows: Int,
    val conversionScore: Double,
)

@Serializable
private data class PromoterRatingPageView(
    val clubId: Long,
    val period: String,
    val from: String,
    val to: String,
    val page: Int,
    val size: Int,
    val total: Int,
    val items: List<PromoterRatingRowView>,
)

fun Application.promoterRatingRoutes(
    promoterRatingService: PromoterRatingService,
    botTokenProvider: () -> String = miniAppBotTokenProvider(),
) {
    routing {
        route("/api/promoter") {
            withMiniAppAuth { botTokenProvider() }

            get("/scorecard") {
                val promoterId = call.attributes[MiniAppUserKey].id
                val period = parsePeriodOrError(call) ?: return@get

                val scorecard = promoterRatingService.scorecardForPromoter(promoterId, period)
                logger.info("promoter.scorecard period={} promoter_id={}", period.value, promoterId)
                call.applyCacheHeaders(NO_STORE)
                call.respond(scorecard.toView())
            }
        }

        route("/api/admin/promoters/rating") {
            withMiniAppAuth { botTokenProvider() }

            authorize(Role.CLUB_ADMIN, Role.OWNER, Role.GLOBAL_ADMIN) {
                get {
                    val clubId = call.request.queryParameters["clubId"]?.toLongOrNull()
                    if (clubId == null || clubId <= 0) {
                        logger.warn("promoter.rating invalid_club_id club_id={}", clubId)
                        return@get call.respondRatingError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                    }

                    val period = parsePeriodOrError(call) ?: return@get
                    val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                    val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 20
                    val invalidPagination = page < 1 || size !in 1..100
                    if (invalidPagination) {
                        logger.warn("promoter.rating invalid_pagination club_id={} page={} size={}", clubId, page, size)
                        return@get call.respondRatingError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                    }

                    val rating = promoterRatingService.ratingForClub(clubId, period, page, size)
                    logger.info(
                        "promoter.rating club_id={} period={} page={} size={} total={}",
                        clubId,
                        period.value,
                        page,
                        size,
                        rating.total,
                    )
                    call.applyCacheHeaders(NO_STORE)
                    call.respond(rating.toView())
                }
            }
        }
    }
}

private suspend fun parsePeriodOrError(call: ApplicationCall): PromoterPeriod? {
    val raw = call.request.queryParameters["period"]
    val period = PromoterPeriod.parse(raw)
    if (period == null) {
        logger.warn("promoter.rating invalid_period value={}", raw)
        call.respondRatingError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
        return null
    }
    return period
}

private suspend fun ApplicationCall.respondRatingError(status: HttpStatusCode, code: String) {
    applyCacheHeaders(NO_STORE)
    respondError(status, code)
}

private fun ApplicationCall.applyCacheHeaders(cacheControl: String) {
    response.headers.append(HttpHeaders.CacheControl, cacheControl)
    response.headers.append(HttpHeaders.Vary, VARY_HEADER)
}

private fun PromoterScorecard.toView(): PromoterScorecardView =
    PromoterScorecardView(
        period = period,
        from = from.toString(),
        to = to.toString(),
        invited = invited,
        arrivals = arrivals,
        noShows = noShows,
        conversionScore = conversionScore,
    )

private fun PromoterRatingRow.toView(): PromoterRatingRowView =
    PromoterRatingRowView(
        promoterId = promoterId,
        invited = invited,
        arrivals = arrivals,
        noShows = noShows,
        conversionScore = conversionScore,
    )

private fun PromoterRatingPage.toView(): PromoterRatingPageView =
    PromoterRatingPageView(
        clubId = clubId,
        period = period,
        from = from.toString(),
        to = to.toString(),
        page = page,
        size = size,
        total = total,
        items = items.map { it.toView() },
    )
