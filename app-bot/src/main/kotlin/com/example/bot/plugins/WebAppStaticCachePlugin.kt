package com.example.bot.plugins

import io.ktor.http.HttpMethod
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path

private const val DEFAULT_CACHE_SECONDS: Long = 31_536_000 // 365 days

class WebAppStaticCachePluginConfig {
    /** Path prefix for which cache headers should be appended. */
    var pathPrefix: String = DEFAULT_WEBAPP_PREFIX

    /** Cache duration for fingerprinted assets (seconds). */
    var cacheSeconds: Long = DEFAULT_CACHE_SECONDS
}

/**
 * Appends Cache-Control headers for static WebApp assets.
 * Runs on the Call phase to avoid later overrides by other plugins/handlers.
 */
val WebAppStaticCachePlugin = createApplicationPlugin("WebAppStaticCachePlugin", ::WebAppStaticCachePluginConfig) {
    val safeMethods = setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Options)

    val prefix: String =
        pluginConfig.pathPrefix
            .ifBlank { DEFAULT_WEBAPP_PREFIX }
            .trimEnd('/')
            .ifEmpty { "/" }

    val seconds = pluginConfig.cacheSeconds

    onCall { call ->
        val path = call.request.path()
        val method = call.request.httpMethod
        val pathMatches = path == prefix || path.startsWith("$prefix/")

        if (method in safeMethods && pathMatches) {
            if (call.response.headers["Cache-Control"] == null) {
                val isHtml = path.endsWith(".html") || path == prefix || path == "$prefix/"
                val value = if (isHtml) {
                    "max-age=60, must-revalidate"
                } else {
                    "public, max-age=$seconds, immutable"
                }

                call.response.headers.append("Cache-Control", value)
            }
        }
    }
}
