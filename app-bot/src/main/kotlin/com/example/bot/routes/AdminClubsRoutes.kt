package com.example.bot.routes

import com.example.bot.admin.AdminClub
import com.example.bot.admin.AdminClubCreate
import com.example.bot.admin.AdminClubUpdate
import com.example.bot.admin.AdminClubsRepository
import com.example.bot.data.security.Role
import com.example.bot.http.ErrorCodes
import com.example.bot.http.ensureMiniAppNoStoreHeaders
import com.example.bot.http.respondError
import com.example.bot.plugins.miniAppBotTokenProvider
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.security.rbac.authorize
import com.example.bot.security.rbac.rbacContext
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private const val MAX_CLUB_NAME_LENGTH = 255
private const val MAX_CITY_LENGTH = 128

@Serializable
private data class AdminClubCreateRequest(
    val name: String,
    val city: String,
    val isActive: Boolean = true,
)

@Serializable
private data class AdminClubUpdateRequest(
    val name: String? = null,
    val city: String? = null,
    val isActive: Boolean? = null,
)

@Serializable
private data class AdminClubResponse(
    val id: Long,
    val name: String,
    val city: String,
    val isActive: Boolean,
    val createdAt: String,
    val updatedAt: String,
)

fun Application.adminClubsRoutes(
    adminClubsRepository: AdminClubsRepository,
    botTokenProvider: () -> String = miniAppBotTokenProvider(),
) {
    val logger = LoggerFactory.getLogger("AdminClubsRoutes")

    routing {
        route("/api/admin") {
            intercept(ApplicationCallPipeline.Setup) { call.ensureMiniAppNoStoreHeaders() }
            withMiniAppAuth { botTokenProvider() }

            authorize(Role.OWNER, Role.GLOBAL_ADMIN, Role.CLUB_ADMIN) {
                route("/clubs") {
                    get {
                        val clubs = adminClubsRepository.list()
                        val response =
                            if (call.hasGlobalAdminAccess()) {
                                clubs
                            } else {
                                clubs.filter { club -> call.isAdminClubAllowed(club.id) }
                            }
                        call.respond(HttpStatusCode.OK, response.map { it.toResponse() })
                    }

                    post {
                        if (!call.hasGlobalAdminAccess()) {
                            return@post call.respondError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
                        }

                        val payload = runCatching { call.receive<AdminClubCreateRequest>() }.getOrNull()
                            ?: return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)

                        val errors = payload.validateCreate()
                        if (errors.isNotEmpty()) {
                            return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error, details = errors)
                        }

                        val created =
                            adminClubsRepository.create(
                                AdminClubCreate(
                                    name = payload.name.trim(),
                                    city = payload.city.trim(),
                                    isActive = payload.isActive,
                                ),
                            )
                        logger.info("admin.clubs.create club_id={} by={}", created.id, call.rbacContext().user.id)
                        call.respond(HttpStatusCode.Created, created.toResponse())
                    }

                    patch("/{clubId}") {
                        val clubId = call.parameters["clubId"]?.toLongOrNull()
                        if (clubId == null || clubId <= 0) {
                            return@patch call.respondError(
                                HttpStatusCode.BadRequest,
                                ErrorCodes.validation_error,
                                details = mapOf("clubId" to "must_be_positive"),
                            )
                        }
                        if (!call.isAdminClubAllowed(clubId)) {
                            return@patch call.respondError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
                        }

                        val payload = runCatching { call.receive<AdminClubUpdateRequest>() }.getOrNull()
                            ?: return@patch call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)
                        val errors = payload.validateUpdate()
                        if (errors.isNotEmpty()) {
                            return@patch call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error, details = errors)
                        }

                        val updated =
                            adminClubsRepository.update(
                                clubId,
                                AdminClubUpdate(
                                    name = payload.name?.trim(),
                                    city = payload.city?.trim(),
                                    isActive = payload.isActive,
                                ),
                            )
                                ?: return@patch call.respondError(HttpStatusCode.NotFound, ErrorCodes.club_not_found)

                        logger.info("admin.clubs.update club_id={} by={}", updated.id, call.rbacContext().user.id)
                        call.respond(HttpStatusCode.OK, updated.toResponse())
                    }

                    delete("/{clubId}") {
                        val clubId = call.parameters["clubId"]?.toLongOrNull()
                        if (clubId == null || clubId <= 0) {
                            return@delete call.respondError(
                                HttpStatusCode.BadRequest,
                                ErrorCodes.validation_error,
                                details = mapOf("clubId" to "must_be_positive"),
                            )
                        }
                        if (!call.isAdminClubAllowed(clubId)) {
                            return@delete call.respondError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
                        }

                        val deleted = adminClubsRepository.delete(clubId)
                        if (!deleted) {
                            return@delete call.respondError(HttpStatusCode.NotFound, ErrorCodes.club_not_found)
                        }

                        logger.info("admin.clubs.delete club_id={} by={}", clubId, call.rbacContext().user.id)
                        call.respond(HttpStatusCode.NoContent, Unit)
                    }
                }
            }
        }
    }
}

private fun AdminClubCreateRequest.validateCreate(): Map<String, String> {
    val errors = mutableMapOf<String, String>()
    if (name.isBlank() || name.trim().length > MAX_CLUB_NAME_LENGTH) errors["name"] = "length_1_$MAX_CLUB_NAME_LENGTH"
    if (city.isBlank() || city.trim().length > MAX_CITY_LENGTH) errors["city"] = "length_1_$MAX_CITY_LENGTH"
    return errors
}

private fun AdminClubUpdateRequest.validateUpdate(): Map<String, String> {
    val errors = mutableMapOf<String, String>()
    if (name == null && city == null && isActive == null) {
        errors["payload"] = "must_include_field"
        return errors
    }
    name?.let { if (it.trim().isEmpty() || it.trim().length > MAX_CLUB_NAME_LENGTH) errors["name"] = "length_1_$MAX_CLUB_NAME_LENGTH" }
    city?.let { if (it.trim().isEmpty() || it.trim().length > MAX_CITY_LENGTH) errors["city"] = "length_1_$MAX_CITY_LENGTH" }
    return errors
}

private fun AdminClub.toResponse(): AdminClubResponse =
    AdminClubResponse(
        id = id,
        name = name,
        city = city,
        isActive = isActive,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )
