package com.example.bot.plugins

import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.util.pipeline.PipelineContext
import org.slf4j.event.Level
import java.security.SecureRandom

private const val REQUEST_ID_HEADER = "X-Request-Id"
private const val LEGACY_REQUEST_ID_HEADER = "X-Request-ID"
private val REQUEST_ID_REGEX = Regex("[A-Za-z0-9._-]+")
private val secureRandom: SecureRandom by lazy { SecureRandom() }

private fun generateRequestId(): String {
    val bytes = ByteArray(10)
    secureRandom.nextBytes(bytes)
    return buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            append(((byte.toInt() and 0xff).toString(16)).padStart(2, '0'))
        }
    }
}

fun Application.installRequestLogging() {
    install(CallId) {
        header(REQUEST_ID_HEADER)
        header(LEGACY_REQUEST_ID_HEADER)
        generate { generateRequestId() }
        verify { id -> id.isNotBlank() && REQUEST_ID_REGEX.matches(id) }
        reply { call, id -> call.response.header(REQUEST_ID_HEADER, id) }
    }
    install(CallLogging) {
        level = Level.INFO
        mdc("request_id") { it.callId }
        filter { call ->
            val path = call.request.path()
            !path.startsWith("/metrics")
        }
        mdc("method") { it.request.httpMethod.value }
        mdc("path") { it.request.path() }
    }
}

suspend fun PipelineContext<Unit, io.ktor.server.application.ApplicationCall>.okText(text: String) {
    call.respondText(text)
}
