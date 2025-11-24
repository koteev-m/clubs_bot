package com.example.bot.plugins

import io.ktor.http.HttpMethod
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path

internal const val DEFAULT_WEBAPP_PREFIX = "/webapp/entry"

private const val DEFAULT_CSP =
    "default-src 'self'; " +
        "img-src 'self' data: blob; " +
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
