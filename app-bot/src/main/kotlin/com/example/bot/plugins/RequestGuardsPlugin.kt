package com.example.bot.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.requesttimeout.RequestTimeout

internal const val DEFAULT_CHECKIN_MAX_BYTES: Long = 4_096L // 4 KB
internal const val MIN_CHECKIN_MAX_BYTES: Long = 512L
internal const val MAX_CHECKIN_MAX_BYTES: Long = 32_768L
internal const val MIN_HTTP_REQUEST_TIMEOUT_MS: Long = 100L
internal const val MAX_HTTP_REQUEST_TIMEOUT_MS: Long = 30_000L
internal const val DEFAULT_HTTP_REQUEST_TIMEOUT_MS: Long = 3_000L

/**
 * Включает RequestTimeout (глобально) и лимит тела для чек‑ина.
 * ENV:
 *  - HTTP_REQUEST_TIMEOUT_MS (по умолчанию 3000)
 *  - CHECKIN_MAX_BODY_BYTES (по умолчанию 4096; 512..32768)
 */
fun Application.installRequestGuardsFromEnv(envProvider: (String) -> String? = System::getenv) {
    val requestTimeoutMs =
        envProvider("HTTP_REQUEST_TIMEOUT_MS")
            ?.toLongOrNull()
            ?.coerceIn(MIN_HTTP_REQUEST_TIMEOUT_MS, MAX_HTTP_REQUEST_TIMEOUT_MS)
            ?: DEFAULT_HTTP_REQUEST_TIMEOUT_MS
    install(RequestTimeout) {
        requestTimeoutMillis = requestTimeoutMs
    }

    // Локальный лимит только для чек‑ина:
    // применим через route-scoped установку (см. правку checkinRoutes)
    // На всякий случай: если вдруг тело пришло без Content-Length, RequestSizeLimit всё равно ограничит чтение.
}
