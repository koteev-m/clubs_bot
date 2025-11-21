package com.example.bot.plugins

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import org.slf4j.LoggerFactory
import kotlin.math.max
import kotlin.math.min

private val etagLogger = LoggerFactory.getLogger("WebAppEtag")

fun Application.installWebAppEtagForFingerprints(envProvider: (String) -> String? = System::getenv) {
    val prefix = (envProvider("WEBAPP_CSP_PATH_PREFIX")?.takeIf { it.isNotBlank() } ?: "/webapp/entry").removeSuffix("/")
    val rawSeconds = envProvider("WEBAPP_ENTRY_CACHE_SECONDS")?.toLongOrNull()
    val cacheSeconds = (rawSeconds ?: 31_536_000L).let { min(31_536_000L, max(60L, it)) }

    etagLogger.info("Weak ETag for fingerprinted assets enabled at prefix {}", prefix)

    intercept(ApplicationCallPipeline.Plugins) {
        val method = call.request.httpMethod
        if (method != HttpMethod.Get && method != HttpMethod.Head) return@intercept

        val path = call.request.path()
        if (path != prefix && !path.startsWith("$prefix/")) return@intercept

        val file = path.substringAfterLast('/')
        if (FINGERPRINT_RE.matches(file) && call.response.headers[HttpHeaders.ETag] == null) {
            val fingerprint = extractFingerprintComponent(file)
            if (!fingerprint.isNullOrEmpty()) {
                val etag = "W/\"$fingerprint\""
                call.response.headers.append(HttpHeaders.ETag, etag)

                val ifNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch]
                etagLogger.debug("If-None-Match raw={} path={}", ifNoneMatch, path)
                val tokens = ifNoneMatch
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?: emptyList()

                val matches = tokens.any { token ->
                    val stripped = token.removePrefix("W/").trim().trim('"')
                    stripped.equals(fingerprint, ignoreCase = true)
                }

                if (matches) {
                    if (call.response.headers[HttpHeaders.CacheControl] == null) {
                        call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=$cacheSeconds, immutable")
                    }
                    if (call.response.headers[HttpHeaders.Vary]?.contains("Accept-Encoding", ignoreCase = true) != true) {
                        call.response.headers.append(HttpHeaders.Vary, "Accept-Encoding")
                    }
                    call.response.status(HttpStatusCode.NotModified)
                    if (call.response is OutgoingContent.NoContent) return@intercept
                    finish()
                }
            }
        }
    }
}
