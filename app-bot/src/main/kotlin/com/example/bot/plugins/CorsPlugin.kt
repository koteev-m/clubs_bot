package com.example.bot.plugins

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.cors.routing.CORS
import org.slf4j.LoggerFactory

private val corsLogger = LoggerFactory.getLogger("CorsPlugin")
fun Application.installCorsFromEnv(envProvider: (String) -> String? = System::getenv) {
    val allowedOrigins =
        envProvider("CORS_ALLOWED_ORIGINS")
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    val preflightMaxAge =
        envProvider("CORS_PREFLIGHT_MAX_AGE_SECONDS")
            ?.takeIf { it.isNotBlank() }
            ?.toLongOrNull()
            ?.coerceIn(60, 86_400)
            ?: 600L

    val profile = envProvider("APP_PROFILE")?.takeIf { it.isNotBlank() }?.uppercase() ?: "DEV"
    val prodLike = profile == "PROD" || profile == "STAGE"

    if (allowedOrigins.isEmpty() && prodLike) {
        corsLogger.error("CORS_ALLOWED_ORIGINS is empty in {} profile; refusing to start", profile)
        throw IllegalStateException("CORS_ALLOWED_ORIGINS must be configured for $profile")
    }

    if (allowedOrigins.isEmpty()) {
        corsLogger.info("CORS_ALLOWED_ORIGINS is empty; defaulting to anyHost() for profile {}", profile)
    }

    if (allowedOrigins.isNotEmpty()) {
        corsLogger.info(
            "CORS whitelist for profile {}: {}",
            profile,
            allowedOrigins.joinToString(separator = ",")
        )
    }

    corsLogger.info("CORS preflight max-age set to {} seconds (profile {})", preflightMaxAge, profile)

    install(CORS) {
        maxAgeInSeconds = preflightMaxAge
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
        allowMethod(HttpMethod.Patch)
        allowNonSimpleContentTypes = true

        // Разрешённые origin'ы из ENV (через запятую). В dev — anyHost().
        if (allowedOrigins.isEmpty()) {
            anyHost() // безопасно в dev. Для prod задайте CORS_ALLOWED_ORIGINS.
            // В dev не включаем allowCredentials, чтобы избежать предупреждений.
        } else {
            allowOrigins { origin ->
                allowedOrigins.any { origin.equals(it, ignoreCase = true) }
            }
            // В проде, когда origin-ы заданы явно, можно включить креды.
            allowCredentials = true
        }
    }
}
