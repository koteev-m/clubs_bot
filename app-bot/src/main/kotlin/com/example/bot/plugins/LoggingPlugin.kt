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
import java.util.UUID

private const val REQ_ID = "X-Request-ID"
private const val CORR_ID = "X-Correlation-ID"
private const val REQUEST_ID_MIN_LENGTH = 8
private const val REQUEST_ID_MAX_LENGTH = 128

fun Application.installRequestLogging() {
    install(CallId) {
        header(REQ_ID)
        header(CORR_ID)
        generate { UUID.randomUUID().toString() }
        verify { it.length in REQUEST_ID_MIN_LENGTH..REQUEST_ID_MAX_LENGTH }
        reply { call, id ->
            call.response.header(REQ_ID, id)
        }
    }
    install(CallLogging) {
        level = Level.INFO
        mdc("callId") { it.callId }
        filter { call ->
            val path = call.request.path()
            !path.startsWith("/metrics")
        }
        mdc("method") { it.request.httpMethod.value }
        mdc("path") { it.request.path() }
        mdc("requestId") { it.callId }
    }
}

suspend fun PipelineContext<Unit, io.ktor.server.application.ApplicationCall>.okText(text: String) {
    call.respondText(text)
}
