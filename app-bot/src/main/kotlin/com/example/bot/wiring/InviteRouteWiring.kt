package com.example.bot.wiring

import com.example.bot.club.GuestListRepository
import com.example.bot.routes.guestListInviteRoutes
import com.example.bot.webapp.InitDataAuthConfig
import io.ktor.server.application.Application
import org.koin.ktor.ext.inject

/**
 * Подключает эндпоинт:
 * POST /api/guest-lists/{listId}/entries/{entryId}/invite
 *
 * Требует:
 * - Koin с биндингом GuestListRepository
 * - TELEGRAM_BOT_TOKEN (для InitData HMAC)
 * - QR_SECRET (для подписи токенов)
 * - [опционально] TELEGRAM_BOT_USERNAME (чтобы отдавать готовую ссылку t.me/...).
 */
fun Application.installGuestListInviteRoute() {
    val guestListRepository by inject<GuestListRepository>()

    val initDataAuth: InitDataAuthConfig.() -> Unit = {
        botTokenProvider = {
            System.getenv("TELEGRAM_BOT_TOKEN")
                ?: error("TELEGRAM_BOT_TOKEN missing")
        }
    }

    guestListInviteRoutes(
        repository = guestListRepository,
        initDataAuth = initDataAuth,
    )
}
