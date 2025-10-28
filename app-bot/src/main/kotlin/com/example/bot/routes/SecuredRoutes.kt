package com.example.bot.routes

import com.example.bot.booking.BookingService
import com.example.bot.data.security.Role
import com.example.bot.security.rbac.ClubScope
import com.example.bot.security.rbac.authorize
import com.example.bot.security.rbac.clubScoped
import com.example.bot.webapp.InitDataAuthConfig
import com.example.bot.webapp.InitDataAuthPlugin
import io.ktor.server.application.Application
import io.ktor.server.application.DuplicatePluginException
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

/**
 * Пример защищённых HTTP-маршрутов с RBAC.
 * Важно: плагин InitDataAuthPlugin больше не ставится на корневой узел,
 * чтобы не перехватывать /health, /ready и не конфликтовать с другими ветками.
 */
fun Application.securedRoutes(
    bookingService: BookingService,
    initDataAuth: InitDataAuthConfig.() -> Unit,
) {
    routing {
        // Админская зона
        route("/api/admin") {
            // Безопасная установка плагина ровно на эту ветку
            try {
                install(InitDataAuthPlugin, initDataAuth)
            } catch (_: DuplicatePluginException) {
                // Плагин уже установлен на том же pipeline — игнорируем
            }

            authorize(Role.OWNER, Role.GLOBAL_ADMIN, Role.HEAD_MANAGER) {
                get("/overview") {
                    call.respondText("overview")
                }
            }
        }

        // Клубные маршруты
        route("/api/clubs/{clubId}") {
            // Точно так же ставим плагин только на ветку клуба
            try {
                install(InitDataAuthPlugin, initDataAuth)
            } catch (_: DuplicatePluginException) {
                // Уже установлен — пропускаем
            }

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
