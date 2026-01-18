package com.example.bot.routes

import com.example.bot.admin.AdminHall
import com.example.bot.admin.AdminHallCreate
import com.example.bot.admin.AdminHallUpdate
import com.example.bot.admin.AdminHallsRepository
import com.example.bot.admin.AdminClubsRepository
import com.example.bot.admin.InvalidHallGeometryException
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

private const val MAX_HALL_NAME_LENGTH = 255

@Serializable
private data class AdminHallCreateRequest(
    val name: String,
    val geometryJson: String,
    val isActive: Boolean = false,
)

@Serializable
private data class AdminHallUpdateRequest(
    val name: String? = null,
    val geometryJson: String? = null,
)

@Serializable
private data class AdminHallResponse(
    val id: Long,
    val clubId: Long,
    val name: String,
    val isActive: Boolean,
    val layoutRevision: Long,
    val geometryFingerprint: String,
    val createdAt: String,
    val updatedAt: String,
)

fun Application.adminHallsRoutes(
    adminHallsRepository: AdminHallsRepository,
    adminClubsRepository: AdminClubsRepository,
    botTokenProvider: () -> String = miniAppBotTokenProvider(),
) {
    val logger = LoggerFactory.getLogger("AdminHallsRoutes")

    routing {
        route("/api/admin") {
            intercept(ApplicationCallPipeline.Setup) { call.ensureMiniAppNoStoreHeaders() }
            withMiniAppAuth { botTokenProvider() }

            authorize(Role.OWNER, Role.GLOBAL_ADMIN, Role.CLUB_ADMIN) {
                route("/clubs/{clubId}/halls") {
                    get {
                        val clubId = call.parameters["clubId"]?.toLongOrNull()
                        if (clubId == null || clubId <= 0) {
                            return@get call.respondError(
                                HttpStatusCode.BadRequest,
                                ErrorCodes.validation_error,
                                details = mapOf("clubId" to "must_be_positive"),
                            )
                        }
                        if (!call.isAdminClubAllowed(clubId)) {
                            return@get call.respondError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
                        }
                        val club = adminClubsRepository.getById(clubId)
                            ?: return@get call.respondError(HttpStatusCode.NotFound, ErrorCodes.club_not_found)

                        val halls = adminHallsRepository.listForClub(club.id)
                        call.respond(HttpStatusCode.OK, halls.map { it.toResponse() })
                    }

                    post {
                        val clubId = call.parameters["clubId"]?.toLongOrNull()
                        if (clubId == null || clubId <= 0) {
                            return@post call.respondError(
                                HttpStatusCode.BadRequest,
                                ErrorCodes.validation_error,
                                details = mapOf("clubId" to "must_be_positive"),
                            )
                        }
                        if (!call.isAdminClubAllowed(clubId)) {
                            return@post call.respondError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
                        }
                        adminClubsRepository.getById(clubId)
                            ?: return@post call.respondError(HttpStatusCode.NotFound, ErrorCodes.club_not_found)

                        val payload = runCatching { call.receive<AdminHallCreateRequest>() }.getOrNull()
                            ?: return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)
                        val errors = payload.validateCreate()
                        if (errors.isNotEmpty()) {
                            return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error, details = errors)
                        }

                        val trimmedName = payload.name.trim()
                        if (adminHallsRepository.isHallNameTaken(clubId, trimmedName)) {
                            return@post call.respondError(HttpStatusCode.Conflict, ErrorCodes.hall_name_conflict)
                        }

                        val hasActive = adminHallsRepository.findActiveForClub(clubId) != null
                        val shouldBeActive = payload.isActive || !hasActive
                        val created =
                            try {
                                adminHallsRepository.create(
                                    clubId,
                                    AdminHallCreate(
                                        name = trimmedName,
                                        geometryJson = payload.geometryJson,
                                        isActive = shouldBeActive,
                                    ),
                                )
                            } catch (_: InvalidHallGeometryException) {
                                return@post call.respondError(
                                    HttpStatusCode.BadRequest,
                                    ErrorCodes.validation_error,
                                    details = mapOf("geometryJson" to "invalid_zones"),
                                )
                            }
                        logger.info("admin.halls.create club_id={} hall_id={} by={}", clubId, created.id, call.rbacContext().user.id)
                        call.respond(HttpStatusCode.Created, created.toResponse())
                    }
                }

                route("/halls/{hallId}") {
                    patch {
                        val hallId = call.parameters["hallId"]?.toLongOrNull()
                        if (hallId == null || hallId <= 0) {
                            return@patch call.respondError(
                                HttpStatusCode.BadRequest,
                                ErrorCodes.validation_error,
                                details = mapOf("hallId" to "must_be_positive"),
                            )
                        }
                        val hall = adminHallsRepository.getById(hallId)
                            ?: return@patch call.respondError(HttpStatusCode.NotFound, ErrorCodes.hall_not_found)
                        if (!call.isAdminClubAllowed(hall.clubId)) {
                            return@patch call.respondError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
                        }

                        val payload = runCatching { call.receive<AdminHallUpdateRequest>() }.getOrNull()
                            ?: return@patch call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)
                        val errors = payload.validateUpdate()
                        if (errors.isNotEmpty()) {
                            return@patch call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error, details = errors)
                        }

                        val trimmedName = payload.name?.trim()
                        if (trimmedName != null && adminHallsRepository.isHallNameTaken(hall.clubId, trimmedName, hallId)) {
                            return@patch call.respondError(HttpStatusCode.Conflict, ErrorCodes.hall_name_conflict)
                        }

                        val updated =
                            try {
                                adminHallsRepository.update(
                                    hallId,
                                    AdminHallUpdate(
                                        name = trimmedName,
                                        geometryJson = payload.geometryJson,
                                    ),
                                )
                            } catch (_: InvalidHallGeometryException) {
                                return@patch call.respondError(
                                    HttpStatusCode.BadRequest,
                                    ErrorCodes.validation_error,
                                    details = mapOf("geometryJson" to "invalid_zones"),
                                )
                            }
                                ?: return@patch call.respondError(HttpStatusCode.NotFound, ErrorCodes.hall_not_found)

                        logger.info("admin.halls.update club_id={} hall_id={} by={}", hall.clubId, hallId, call.rbacContext().user.id)
                        call.respond(HttpStatusCode.OK, updated.toResponse())
                    }

                    delete {
                        val hallId = call.parameters["hallId"]?.toLongOrNull()
                        if (hallId == null || hallId <= 0) {
                            return@delete call.respondError(
                                HttpStatusCode.BadRequest,
                                ErrorCodes.validation_error,
                                details = mapOf("hallId" to "must_be_positive"),
                            )
                        }
                        val hall = adminHallsRepository.getById(hallId)
                            ?: return@delete call.respondError(HttpStatusCode.NotFound, ErrorCodes.hall_not_found)
                        if (!call.isAdminClubAllowed(hall.clubId)) {
                            return@delete call.respondError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
                        }

                        val halls = adminHallsRepository.listForClub(hall.clubId)
                        if (hall.isActive && halls.size <= 1) {
                            return@delete call.respondError(
                                HttpStatusCode.BadRequest,
                                ErrorCodes.validation_error,
                                details = mapOf("hall" to "last_active"),
                            )
                        }

                        val deleted = adminHallsRepository.delete(hallId)
                        if (!deleted) {
                            return@delete call.respondError(HttpStatusCode.NotFound, ErrorCodes.hall_not_found)
                        }
                        logger.info("admin.halls.delete club_id={} hall_id={} by={}", hall.clubId, hallId, call.rbacContext().user.id)
                        call.respond(HttpStatusCode.NoContent, Unit)
                    }
                }

                post("/halls/{hallId}/make-active") {
                    val hallId = call.parameters["hallId"]?.toLongOrNull()
                    if (hallId == null || hallId <= 0) {
                        return@post call.respondError(
                            HttpStatusCode.BadRequest,
                            ErrorCodes.validation_error,
                            details = mapOf("hallId" to "must_be_positive"),
                        )
                    }
                    val hall = adminHallsRepository.getById(hallId)
                        ?: return@post call.respondError(HttpStatusCode.NotFound, ErrorCodes.hall_not_found)
                    if (!call.isAdminClubAllowed(hall.clubId)) {
                        return@post call.respondError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
                    }

                    val updated = adminHallsRepository.makeActive(hallId)
                        ?: return@post call.respondError(HttpStatusCode.NotFound, ErrorCodes.hall_not_found)

                    logger.info("admin.halls.make_active club_id={} hall_id={} by={}", hall.clubId, hallId, call.rbacContext().user.id)
                    call.respond(HttpStatusCode.OK, updated.toResponse())
                }
            }
        }
    }
}

private fun AdminHallCreateRequest.validateCreate(): Map<String, String> {
    val errors = mutableMapOf<String, String>()
    if (name.isBlank() || name.trim().length > MAX_HALL_NAME_LENGTH) errors["name"] = "length_1_$MAX_HALL_NAME_LENGTH"
    if (geometryJson.isBlank()) errors["geometryJson"] = "must_be_non_empty"
    return errors
}

private fun AdminHallUpdateRequest.validateUpdate(): Map<String, String> {
    val errors = mutableMapOf<String, String>()
    if (name == null && geometryJson == null) {
        errors["payload"] = "must_include_field"
        return errors
    }
    name?.let { if (it.trim().isEmpty() || it.trim().length > MAX_HALL_NAME_LENGTH) errors["name"] = "length_1_$MAX_HALL_NAME_LENGTH" }
    geometryJson?.let { if (it.isBlank()) errors["geometryJson"] = "must_be_non_empty" }
    return errors
}

private fun AdminHall.toResponse(): AdminHallResponse =
    AdminHallResponse(
        id = id,
        clubId = clubId,
        name = name,
        isActive = isActive,
        layoutRevision = layoutRevision,
        geometryFingerprint = geometryFingerprint,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )
