package com.example.bot.routes

import com.example.bot.data.privacy.PrivacyAdminActor
import com.example.bot.data.privacy.PrivacyService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.util.getOrFail
import kotlinx.serialization.Serializable

fun Application.adminPrivacyRoutes(
    privacyService: PrivacyService
) {
    routing {
        route("/api/admin/privacy") {
                post("/users/{userId}/anonymize") {
                    val userId = call.parameters.getOrFail("userId").toLongOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid userId"))
                    val request = call.receive<AnonymizeUserRequest>()
                    if (request.reason.isBlank()) {
                        return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "reason required"))
                    }
                    val result =
                        privacyService.anonymizeUser(
                            userId = userId,
                            actor = PrivacyAdminActor(request.actorUserId, request.actorRole),
                            reason = request.reason,
                        )
                    call.respond(result)
                }

            post("/retention/run") {
                val request = call.receive<RunRetentionRequest>()
                val result =
                    privacyService.runRetention(
                        actor = request.actorUserId?.let { PrivacyAdminActor(it, request.actorRole ?: "UNKNOWN") },
                    )
                call.respond(result)
            }
        }
    }
}

@Serializable
private data class AnonymizeUserRequest(
    val actorUserId: Long,
    val actorRole: String,
    val reason: String,
)

@Serializable
private data class RunRetentionRequest(
    val actorUserId: Long? = null,
    val actorRole: String? = null,
)
