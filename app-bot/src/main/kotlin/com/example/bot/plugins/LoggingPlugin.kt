package com.example.bot.plugins

import com.example.bot.logging.callIdMdc
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.response.header
import java.util.UUID

private const val REQUEST_ID_HEADER = "X-Request-Id"
private const val LEGACY_REQUEST_ID_HEADER = "X-Request-ID"
private const val GENERATED_ID_LENGTH = 32
private val REQUEST_ID_REGEX = Regex("^[A-Za-z0-9._-]{8,64}$")

fun Application.configureLoggingAndRequestId() {
    install(CallId) {
        retrieve { call ->
            call.request.headers[REQUEST_ID_HEADER]
                ?: call.request.headers[LEGACY_REQUEST_ID_HEADER]
        }
        verify { value -> REQUEST_ID_REGEX.matches(value) }
        generate {
            UUID.randomUUID()
                .toString()
                .replace("-", "")
                .lowercase()
                .take(GENERATED_ID_LENGTH)
        }
        reply { call, id ->
            call.response.header(REQUEST_ID_HEADER, id)
            call.response.header(LEGACY_REQUEST_ID_HEADER, id)
        }
    }

    install(CallLogging) {
        callIdMdc("request_id")
    }
}
