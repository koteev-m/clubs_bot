package com.example.bot.routes

import com.example.bot.http.ErrorCodeInfo
import com.example.bot.http.ErrorCodesPayload
import com.example.bot.http.ErrorRegistry
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import kotlin.text.Charsets
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.routing

// Allows tolerant matching of weak or wildcard ETags for practical caching interoperability
private fun matchesEtag(inm: String, etag: String): Boolean {
    val normTarget = etag.trim().removePrefix("W/").trim('"')
    return inm.split(',')
        .map { it.trim() }
        .any { raw ->
            if (raw == "*") return true
            val norm = raw.removePrefix("W/").trim('"')
            norm == normTarget
        }
}

fun Application.errorCodesRoutes() {
    val jsonContentType = ContentType.Application.Json.withCharset(Charsets.UTF_8)

    routing {
        head("/api/.well-known/errors") {
            val inm = call.request.headers[HttpHeaders.IfNoneMatch]
            if (inm != null && matchesEtag(inm, ErrorRegistry.etag)) {
                call.response.header(HttpHeaders.ETag, ErrorRegistry.etag)
                call.response.header(HttpHeaders.CacheControl, ErrorRegistry.cacheControl)
                call.response.header(HttpHeaders.ContentType, jsonContentType.toString())
                call.respond(HttpStatusCode.NotModified)
                return@head
            }

            call.response.header(HttpHeaders.CacheControl, ErrorRegistry.cacheControl)
            call.response.header(HttpHeaders.ETag, ErrorRegistry.etag)
            call.response.header(HttpHeaders.ContentType, jsonContentType.toString())
            call.respond(HttpStatusCode.OK)
        }

        get("/api/.well-known/errors") {
            val inm = call.request.headers[HttpHeaders.IfNoneMatch]
            if (inm != null && matchesEtag(inm, ErrorRegistry.etag)) {
                call.response.header(HttpHeaders.ETag, ErrorRegistry.etag)
                call.response.header(HttpHeaders.CacheControl, ErrorRegistry.cacheControl)
                call.response.header(HttpHeaders.ContentType, jsonContentType.toString())
                call.respond(HttpStatusCode.NotModified)
                return@get
            }

            call.response.header(HttpHeaders.CacheControl, ErrorRegistry.cacheControl)
            call.response.header(HttpHeaders.ETag, ErrorRegistry.etag)
            call.respond(ErrorCodesPayload(version = ErrorRegistry.version, codes = ErrorRegistry.codes))
        }
    }
}
