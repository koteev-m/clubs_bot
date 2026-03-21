package com.example.bot.routes

import com.example.bot.data.privacy.PrivacyAdminActor
import com.example.bot.data.privacy.PrivacyService
import com.example.bot.data.security.Role
import com.example.bot.http.ErrorCodes
import com.example.bot.http.respondError
import com.example.bot.plugins.miniAppBotTokenProvider
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.security.rbac.authorize
import com.example.bot.security.rbac.rbacContext
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.util.getOrFail
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

fun Application.adminPrivacyRoutes(
    privacyService: PrivacyService,
    botTokenProvider: () -> String = miniAppBotTokenProvider(),
) {
    routing {
        route("/api/admin/privacy") {
            withMiniAppAuth { botTokenProvider() }

            authorize(Role.OWNER, Role.GLOBAL_ADMIN, Role.HEAD_MANAGER) {
                post("/users/{userId}/anonymize") {
                    val userId =
                        call.parameters.getOrFail("userId").toLongOrNull()
                            ?: return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                    val request =
                        try {
                            call.receive<AnonymizeUserRequest>()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)
                        }
                    if (request.reason.isBlank()) {
                        return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                    }
                    val result =
                        privacyService.anonymizeUser(
                            userId = userId,
                            actor = call.toPrivacyAdminActor(),
                            reason = request.reason,
                        )
                    call.respond(result)
                }

                post("/retention/run") {
                    val result = privacyService.runRetention(actor = call.toPrivacyAdminActor())
                    call.respond(result)
                }

                post("/backfill/run") {
                    val updatedRows = privacyService.backfillPhoneProtection()
                    call.respond(mapOf("updatedRows" to updatedRows))
                }
            }
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.toPrivacyAdminActor(): PrivacyAdminActor {
    val context = rbacContext()
    return PrivacyAdminActor(
        userId = context.user.id,
        role = context.roles.firstOrNull()?.name ?: "UNKNOWN",
    )
}

@Serializable
private data class AnonymizeUserRequest(
    val reason: String,
    val actorUserId: Long? = null,
    val actorRole: String? = null,
)
