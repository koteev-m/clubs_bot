package com.example.bot.plugins

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.cors.routing.CORS

fun Application.installCorsFromEnv() {
    install(CORS) {
        // Заголовки/методы, необходимые Mini App
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader("X-Telegram-Init-Data")
        // Алиас, который иногда используют клиенты/тесты
        allowHeader("X-Telegram-InitData")
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowNonSimpleContentTypes = true

        // Разрешённые origin'ы из ENV (через запятую). В dev — anyHost().
        val origins =
            System.getenv("CORS_ALLOWED_ORIGINS")
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()

        if (origins.isEmpty()) {
            anyHost() // безопасно в dev. Для prod задайте CORS_ALLOWED_ORIGINS.
            // В dev не включаем allowCredentials, чтобы избежать предупреждений.
        } else {
            allowOrigins { origin ->
                origins.any { origin.equals(it, ignoreCase = true) }
            }
            // В проде, когда origin-ы заданы явно, можно включить креды.
            allowCredentials = true
        }
    }
}
