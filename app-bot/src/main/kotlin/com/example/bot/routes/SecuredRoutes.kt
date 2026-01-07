package com.example.bot.routes

import com.example.bot.booking.BookingService
import com.example.bot.data.security.Role
import com.example.bot.plugins.miniAppBotTokenRequired
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.security.rbac.ClubScope
import com.example.bot.security.rbac.authorize
import com.example.bot.security.rbac.clubScoped
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

/**
 * Пример защищённых HTTP-маршрутов с RBAC.
 * Важно: Mini App авторизация больше не ставится на корневой узел,
 * чтобы не перехватывать /health, /ready и не конфликтовать с другими ветками.
 */
fun Application.securedRoutes(
    bookingService: BookingService,
    botTokenProvider: () -> String = { miniAppBotTokenRequired() },
) {
    routing {
        // Админская зона
        route("/api/admin") {
            // Безопасная установка плагина ровно на эту ветку
            withMiniAppAuth { botTokenProvider() }

            authorize(Role.OWNER, Role.GLOBAL_ADMIN, Role.HEAD_MANAGER) {
                get("/overview") {
                    call.respondText("overview")
                }
            }
        }

        // Клубные маршруты
        route("/api/clubs/{clubId}") {
            // Точно так же ставим плагин только на ветку клуба
            withMiniAppAuth { botTokenProvider() }

            // Пример защищённого GET
            authorize(Role.CLUB_ADMIN, Role.MANAGER, Role.ENTRY_MANAGER) {
                clubScoped(ClubScope.Own) {
                    get("/bookings") { call.respondText("bookings") }
                }
            }

            // Пример защищённого POST
            authorize(Role.PROMOTER, Role.CLUB_ADMIN, Role.MANAGER, Role.GUEST) {
                clubScoped(ClubScope.Own) {
                    post("/tables/{tableId}/booking") { call.respondText("booked") }
                }
            }
        }

        // Ветка с бронированиями, регистрируем как есть (без установки плагина здесь),
        // чтобы избежать дубликатов — внутренний модуль сам решает, где ставить авторизацию.
        securedBookingRoutes(bookingService)
    }
}
