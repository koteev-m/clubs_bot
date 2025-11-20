package com.example.bot.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import org.slf4j.LoggerFactory

private val secLogger = LoggerFactory.getLogger("SecurityHeadersPlugin")

/**
 * Устанавливает безопасные заголовки по умолчанию и HSTS по профилям.
 * Профиль берётся из APP_PROFILE (DEV/STAGE/PROD; по умолчанию DEV).
 * В DEV HSTS не выставляется.
 */
fun Application.installHttpSecurityFromEnv(envProvider: (String) -> String? = System::getenv) {
    val profile = envProvider("APP_PROFILE")?.takeIf { it.isNotBlank() }?.uppercase() ?: "DEV"
    val prodLike = profile == "PROD" || profile == "STAGE"
    secLogger.info("Installing security headers for profile={}", profile)

    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
        header("Referrer-Policy", "no-referrer")
        // Минимальная политика — без доступов к чувствительным API.
        header("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
        if (prodLike) {
            // В проде/стейдже включаем HSTS на уровне приложения (edge тоже включим отдельно).
            header("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
        }
    }
}
