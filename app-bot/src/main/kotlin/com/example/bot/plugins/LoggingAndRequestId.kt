package com.example.bot.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.calllogging.CallLogging
import java.util.UUID
import org.slf4j.event.Level
import org.slf4j.LoggerFactory

private const val REQUEST_ID_HEADER = "X-Request-Id"
private const val LEGACY_REQUEST_ID_HEADER = "X-Request-ID"
private val REQUEST_ID_REGEX = Regex("^[A-Za-z0-9._-]{8,128}$")
private val logger = LoggerFactory.getLogger("com.example.bot.plugins.LoggingAndRequestId")

fun Application.configureLoggingAndRequestId() {
    install(CallId) {
        retrieve { call ->
            call.request.headers[REQUEST_ID_HEADER]
                ?: call.request.headers[LEGACY_REQUEST_ID_HEADER]?.also {
                    logger.debug("request_id_legacy_used")
                }
        }
        generate { UUID.randomUUID().toString() }
        verify { value -> REQUEST_ID_REGEX.matches(value) }
        replyToHeader(REQUEST_ID_HEADER)
    }

    install(CallLogging) {
        level = Level.INFO
        mdc("requestId") { call -> call.callId }
    }
}
