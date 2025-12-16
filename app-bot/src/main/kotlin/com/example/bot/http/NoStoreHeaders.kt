package com.example.bot.http

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall

const val MINI_APP_VARY_HEADER: String = "X-Telegram-Init-Data"
const val NO_STORE_CACHE_CONTROL: String = "no-store"

/**
 * Обеспечивает per-user no-store семантику для mini-app эндпоинтов:
 *   Cache-Control: no-store
 *   Vary: X-Telegram-Init-Data
 * Если заголовки уже выставлены, повторно не добавляет.
 */
fun ApplicationCall.ensureMiniAppNoStoreHeaders() {
    val headers = response.headers
    if (headers[HttpHeaders.CacheControl] == null) {
        headers.append(HttpHeaders.CacheControl, NO_STORE_CACHE_CONTROL)
    }
    if (headers[HttpHeaders.Vary] == null) {
        headers.append(HttpHeaders.Vary, MINI_APP_VARY_HEADER)
    }
}
