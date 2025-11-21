package com.example.bot.plugins

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import org.slf4j.LoggerFactory
import kotlin.math.max
import kotlin.math.min

private val staticCacheLogger = LoggerFactory.getLogger("WebAppStaticCache")

fun Application.installWebAppImmutableCacheFromEnv(envProvider: (String) -> String? = System::getenv) {
    val raw = envProvider("WEBAPP_ENTRY_CACHE_SECONDS")?.toLongOrNull()
    val seconds = (raw ?: 31_536_000L).let { min(31_536_000L, max(60L, it)) }
    val prefix = (envProvider("WEBAPP_CSP_PATH_PREFIX")?.takeIf { it.isNotBlank() } ?: "/webapp/entry").removeSuffix("/")

    staticCacheLogger.info("Immutable Cache-Control for {} set to {} seconds", prefix, seconds)

    intercept(ApplicationCallPipeline.Call) {
        val path = call.request.path()
        val m = call.request.httpMethod
        val safe = (m == HttpMethod.Get || m == HttpMethod.Head || m == HttpMethod.Options)
        // допускаем и "/webapp/entry" и "/webapp/entry/"
        if (safe && (path == prefix || path.startsWith("$prefix/"))) {
            call.response.pipeline.intercept(ApplicationSendPipeline.After) {
                proceed()
                val statusValue = call.response.status()?.value ?: return@intercept
                val cacheable = statusValue in 200..299 || statusValue == 304
                if (cacheable && call.response.headers["Cache-Control"] == null) {
                    val isHtml = path.endsWith(".html") || path == prefix || path == "$prefix/"
                    val file = path.substringAfterLast('/')
                    // простой детектор «фингерпринта» в имени. Примеры: app.abcdef12.js, app-abcdef123456.css, img_ABCDEF12.png
                    val isFingerprinted = FINGERPRINT_RE.matches(file)
                    val value =
                        if (isHtml) {
                            "max-age=60, must-revalidate"
                        } else if (isFingerprinted) {
                            // Не трогаем API, только статику WebApp
                            "public, max-age=$seconds, immutable"
                        } else {
                            // Для не-хэшированных ассетов — короткий кэш, чтобы можно было обновить без смены имени
                            "max-age=300, must-revalidate"
                        }
                    call.response.headers.append("Cache-Control", value)
                    if (isFingerprinted) {
                        val vary = call.response.headers[HttpHeaders.Vary]
                        if (vary == null || !vary.contains("Accept-Encoding", ignoreCase = true)) {
                            call.response.headers.append(HttpHeaders.Vary, "Accept-Encoding")
                        }
                    }
                }
            }
        }
    }
}
