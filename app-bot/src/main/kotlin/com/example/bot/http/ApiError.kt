package com.example.bot.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.callid.callId
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable

@Serializable
data class ApiError(
    val code: String,
    val message: String? = null,
    val requestId: String? = null,
    val status: Int? = null,
    val details: Map<String, String>? = null,
)

suspend fun ApplicationCall.respondError(
    status: HttpStatusCode,
    code: String,
    message: String? = null,
    details: Map<String, String>? = null,
) {
    val body = ApiError(
        code = code,
        message = message,
        requestId = this.callId,
        status = status.value,
        details = details,
    )
    respond(status, body)
}
