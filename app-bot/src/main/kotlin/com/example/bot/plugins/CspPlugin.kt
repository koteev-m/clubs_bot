package com.example.bot.plugins

import io.ktor.http.HttpMethod
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path

internal const val DEFAULT_WEBAPP_PREFIX = "/webapp/entry"

private const val DEFAULT_CSP =
    "default-src 'self'; " +
        "img-src 'self' data: blob; " +
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import org.slf4j.LoggerFactory

private val cspLogger = LoggerFactory.getLogger("CspPlugin")

/**
 * CSP для статического WebApp.
 * Управление через ENV:
 *  - CSP_ENABLED=true|false (по умолчанию false)
 *  - CSP_REPORT_ONLY=true|false (по умолчанию true — постепенное включение)
 *  - CSP_VALUE=<строка политики>. Значение по умолчанию смотрите ниже.
 *  - WEBAPP_CSP_PATH_PREFIX (по умолчанию "/webapp/entry")
 *
 * Важно: мы не задаём frame-ancestors из-за WebView Telegram.
 */
fun Application.installWebAppCspFromEnv(envProvider: (String) -> String? = System::getenv) {
    val enabled =
        (envProvider("CSP_ENABLED") ?: "false").equals("true", ignoreCase = true)
    if (!enabled) {
        cspLogger.info("CSP disabled")
        return
    }

    val reportOnly =
        (envProvider("CSP_REPORT_ONLY") ?: "true").equals("true", ignoreCase = true)
    val headerName =
        if (reportOnly) "Content-Security-Policy-Report-Only" else "Content-Security-Policy"
    val prefix = (envProvider("WEBAPP_CSP_PATH_PREFIX")?.takeIf { it.isNotBlank() } ?: "/webapp/entry").removeSuffix("/")

    val defaultPolicy =
        "default-src 'self'; " +
        "img-src 'self' data: blob:; " +
        "style-src 'self' 'unsafe-inline'; " +
        "script-src 'self'; " +
        "connect-src 'self' https://t.me https://telegram.org; " +
        "base-uri 'self'; " +
        "form-action 'self'; " +
        "object-src 'none';"

class CspPluginConfig {
    /** Path prefix for which CSP headers should be appended. */
    var pathPrefix: String = DEFAULT_WEBAPP_PREFIX

    /** Optional CSP value override (falls back to env/app config, then to [DEFAULT_CSP]). */
    var cspValue: String? = null
}

/**
 * Adds a Content-Security-Policy header for Mini App responses.
 * The plugin runs on the Call phase to reduce the chance of later overrides.
 */
val CspPlugin = createApplicationPlugin(name = "CspPlugin", ::CspPluginConfig) {
    val safeMethods = setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Options)

    val prefix: String =
        pluginConfig.pathPrefix
            .ifBlank { DEFAULT_WEBAPP_PREFIX }
            .trimEnd('/')
            .ifEmpty { "/" }

    val cspValue: String =
        pluginConfig.cspValue
            ?: application.envString("CSP_VALUE")
            ?: DEFAULT_CSP

    onCall { call ->
        val path = call.request.path()
        val method = call.request.httpMethod
        val pathMatches = path == prefix || path.startsWith("$prefix/")

        if (method in safeMethods && pathMatches) {
            if (call.response.headers["Content-Security-Policy"] == null) {
                call.response.headers.append("Content-Security-Policy", cspValue)
            }
        }
    }
}
    val value =
        envProvider("CSP_VALUE")?.trim().takeUnless { it.isNullOrBlank() } ?: defaultPolicy

    cspLogger.info("CSP enabled in mode={}, prefix={}, header={}", if (reportOnly) "report-only" else "enforce", prefix, headerName)

    // Навешиваем CSP только на /webapp/entry/*
    intercept(ApplicationCallPipeline.Call) {
        val path = call.request.path()
        val m = call.request.httpMethod
        val safe = (m == HttpMethod.Get || m == HttpMethod.Head || m == HttpMethod.Options)
        if (safe && (path == prefix || path.startsWith("$prefix/"))) {
            if (call.response.headers[headerName] == null) {
                call.response.headers.append(headerName, value)
            }
        }
    }
}

/**
 * Алиас для единообразия именования: делегирует в [installWebAppCspFromEnv].
 * Сохраняем совместимость с прежним названием installCspFromEnv().
 */
fun Application.installCspFromEnv(envProvider: (String) -> String? = System::getenv) =
    installWebAppCspFromEnv(envProvider)
