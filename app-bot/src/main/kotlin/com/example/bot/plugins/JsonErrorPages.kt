package com.example.bot.plugins

import com.example.bot.http.ApiErrorHandledKey
import com.example.bot.http.ErrorCodes
import com.example.bot.http.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.requestsize.RequestTooLargeException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import org.slf4j.LoggerFactory

fun Application.installJsonErrorPages() {
    val logger = LoggerFactory.getLogger("JsonErrorPages")

    install(StatusPages) {
        status(HttpStatusCode.PayloadTooLarge) { call, _ ->
            if (!call.request.path().startsWith("/api/")) return@status
            if (call.attributes.contains(ApiErrorHandledKey)) return@status
            val contentType = call.response.headers[HttpHeaders.ContentType]
            if (contentType?.startsWith(ContentType.Application.Json.toString()) == true) {
                return@status
            }
            call.respondError(HttpStatusCode.PayloadTooLarge, ErrorCodes.payload_too_large)
        }

        exception<MiniAppAuthAbort> { _, _ -> }

        exception<RequestTooLargeException> { call, cause ->
            if (!call.request.path().startsWith("/api/")) throw cause
            call.respondError(HttpStatusCode.PayloadTooLarge, ErrorCodes.payload_too_large)
        }

        status(HttpStatusCode.Unauthorized) { call, _ ->
            if (call.attributes.contains(MiniAppAuthErrorHandledKey)) return@status
            val wwwAuthenticate = call.response.headers[HttpHeaders.WWWAuthenticate]
            if (wwwAuthenticate != null) {
                call.response.headers.append(HttpHeaders.WWWAuthenticate, wwwAuthenticate, false)
            }
            call.respondError(HttpStatusCode.Unauthorized, ErrorCodes.unauthorized)
        }

        status(HttpStatusCode.TooManyRequests) { call, _ ->
            val retryAfter = call.response.headers[HttpHeaders.RetryAfter]
            if (retryAfter != null) {
                call.response.headers.append(HttpHeaders.RetryAfter, retryAfter, false)
            }
            call.respondError(HttpStatusCode.TooManyRequests, ErrorCodes.rate_limited)
        }

        status(HttpStatusCode.Forbidden) { call, _ ->
            if (!call.request.path().startsWith("/api/")) return@status
            if (call.attributes.contains(ApiErrorHandledKey)) return@status
            call.respondError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
        }

        status(HttpStatusCode.UnsupportedMediaType) { call, _ ->
            if (!call.request.path().startsWith("/api/")) return@status
            call.respondError(HttpStatusCode.UnsupportedMediaType, ErrorCodes.unsupported_media_type)
        }

        status(HttpStatusCode.NotFound) { call, _ ->
            if (!call.request.path().startsWith("/api/")) return@status
            call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
        }

        exception<Throwable> { call, cause ->
            if (!call.request.path().startsWith("/api/")) throw cause
            logger.error("unhandled exception for API path {}", call.request.path(), cause)
            call.respondError(HttpStatusCode.InternalServerError, ErrorCodes.internal_error)
        }
    }
}
