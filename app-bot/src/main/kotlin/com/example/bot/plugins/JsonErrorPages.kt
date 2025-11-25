package com.example.bot.plugins

import com.example.bot.http.respondError
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
        exception<RequestTooLargeException> { call, _ ->
            call.respondError(HttpStatusCode.PayloadTooLarge, "payload_too_large")
        }

        status(HttpStatusCode.Unauthorized) { call, _ ->
            val wwwAuthenticate = call.response.headers[HttpHeaders.WWWAuthenticate]
            if (wwwAuthenticate != null) {
                call.response.headers.append(HttpHeaders.WWWAuthenticate, wwwAuthenticate, false)
            }
            call.respondError(HttpStatusCode.Unauthorized, "unauthorized")
        }

        status(HttpStatusCode.TooManyRequests) { call, _ ->
            val retryAfter = call.response.headers[HttpHeaders.RetryAfter]
            if (retryAfter != null) {
                call.response.headers.append(HttpHeaders.RetryAfter, retryAfter, false)
            }
            call.respondError(HttpStatusCode.TooManyRequests, "rate_limited")
        }

        status(HttpStatusCode.Forbidden) { call, _ ->
            if (!call.request.path().startsWith("/api/")) return@status
            call.respondError(HttpStatusCode.Forbidden, "forbidden")
        }

        status(HttpStatusCode.UnsupportedMediaType) { call, _ ->
            if (!call.request.path().startsWith("/api/")) return@status
            call.respondError(HttpStatusCode.UnsupportedMediaType, "unsupported_media_type")
        }

        status(HttpStatusCode.NotFound) { call, _ ->
            if (!call.request.path().startsWith("/api/")) return@status
            call.respondError(HttpStatusCode.NotFound, "not_found")
        }

        exception<Throwable> { call, cause ->
            if (!call.request.path().startsWith("/api/")) throw cause
            logger.error("unhandled exception for API path {}", call.request.path(), cause)
            call.respondError(HttpStatusCode.InternalServerError, "internal_error")
        }
    }
}
