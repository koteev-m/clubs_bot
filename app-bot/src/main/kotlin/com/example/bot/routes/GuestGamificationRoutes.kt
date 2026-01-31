package com.example.bot.routes

import com.example.bot.clubs.ClubsRepository
import com.example.bot.data.gamification.CouponStatus
import com.example.bot.data.security.Role
import com.example.bot.gamification.GuestGamificationService
import com.example.bot.http.ErrorCodes
import com.example.bot.http.ensureMiniAppNoStoreHeaders
import com.example.bot.http.respondError
import com.example.bot.plugins.miniAppBotTokenProvider
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.security.rbac.ClubScope
import com.example.bot.security.rbac.authorize
import com.example.bot.security.rbac.clubScoped
import com.example.bot.security.rbac.rbacContext
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

fun Application.guestGamificationRoutes(
    clubsRepository: ClubsRepository,
    gamificationService: GuestGamificationService,
    botTokenProvider: () -> String = miniAppBotTokenProvider(),
) {
    routing {
        route("/api/me") {
            intercept(ApplicationCallPipeline.Setup) { call.ensureMiniAppNoStoreHeaders() }
            withMiniAppAuth { botTokenProvider() }
            authorize(
                Role.OWNER,
                Role.GLOBAL_ADMIN,
                Role.HEAD_MANAGER,
                Role.CLUB_ADMIN,
                Role.MANAGER,
                Role.ENTRY_MANAGER,
                Role.PROMOTER,
                Role.GUEST,
            ) {
                clubScoped(ClubScope.Own) {
                    get("/clubs/{clubId}/gamification") {
                        val clubId = call.parameters["clubId"]?.toLongOrNull()
                            ?: return@get call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_club_id)
                        val club = clubsRepository.getById(clubId)
                            ?: return@get call.respondError(HttpStatusCode.NotFound, ErrorCodes.club_not_found)

                        val userId = call.rbacContext().user.id
                        val response =
                            gamificationService.load(
                                clubId = club.id,
                                userId = userId,
                                couponStatuses = setOf(CouponStatus.AVAILABLE),
                            )
                        call.respond(HttpStatusCode.OK, response)
                    }
                }
            }
        }
    }
}
