package com.example.bot.routes

import com.example.bot.data.security.Role
import com.example.bot.http.ErrorCodes
import com.example.bot.http.ensureMiniAppNoStoreHeaders
import com.example.bot.http.respondError
import com.example.bot.layout.AdminTableCreate
import com.example.bot.layout.AdminTableUpdate
import com.example.bot.layout.AdminTablesRepository
import com.example.bot.layout.ArrivalWindow
import com.example.bot.layout.parseArrivalWindowOrNull
import com.example.bot.layout.toRangeString
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.security.rbac.authorize
import com.example.bot.security.rbac.rbacContext
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.delete
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private const val MAX_LABEL_LENGTH = 100
private const val MAX_ZONE_LENGTH = 50
private const val MAX_CAPACITY = 100

@Serializable
private data class AdminTableCreateRequest(
    val label: String,
    val minDeposit: Long? = null,
    val capacity: Int,
    /** Zone identifier (Zone.id) from the layout. */
    val zone: String? = null,
    val arrivalWindow: String? = null,
    val mysteryEligible: Boolean = false,
)

@Serializable
private data class AdminTableUpdateRequest(
    val id: Long,
    val label: String? = null,
    val minDeposit: Long? = null,
    val capacity: Int? = null,
    /** Zone identifier (Zone.id) from the layout. */
    val zone: String? = null,
    val arrivalWindow: String? = null,
    val mysteryEligible: Boolean? = null,
)

@Serializable
private data class AdminTableResponse(
    val id: Long,
    val clubId: Long,
    val label: String,
    val minDeposit: Long,
    val capacity: Int,
    /** Zone identifier (Zone.id) from the layout. */
    val zone: String?,
    /** Человеко-понятное имя зоны (Zone.name) для UI, может быть null если зона не найдена. */
    val zoneName: String?,
    val arrivalWindow: String?,
    val mysteryEligible: Boolean,
)

fun Application.adminTablesRoutes(
    adminTablesRepository: AdminTablesRepository,
    botTokenProvider: () -> String = { System.getenv("TELEGRAM_BOT_TOKEN") ?: "" },
) {
    val logger = LoggerFactory.getLogger("AdminTablesRoutes")

    routing {
        route("/api/admin") {
            intercept(ApplicationCallPipeline.Setup) { call.ensureMiniAppNoStoreHeaders() }
            withMiniAppAuth { botTokenProvider() }

            authorize(Role.OWNER, Role.GLOBAL_ADMIN, Role.HEAD_MANAGER, Role.CLUB_ADMIN) {
                route("/tables") {
                    get {
                        val clubId = call.requireClubId() ?: return@get
                        if (!call.isClubAllowed(clubId)) {
                            return@get call.respondForbidden()
                        }

                        val page = call.requirePageOrNull() ?: return@get
                        val size = call.requireSizeOrNull() ?: return@get

                        val zones = adminTablesRepository.listZonesForClub(clubId)
                        val tables = adminTablesRepository.listForClub(clubId)
                        val sorted = tables.sortedWith(compareBy<com.example.bot.layout.Table> { it.zoneId }.thenBy { it.id })
                        val offset = page * size
                        val pageItems = if (offset >= sorted.size) emptyList() else sorted.drop(offset).take(size)
                        call.respond(HttpStatusCode.OK, pageItems.map { it.toResponse(clubId, zones) })
                    }

                    post {
                        val clubId = call.requireClubId() ?: return@post
                        if (!call.isClubAllowed(clubId)) {
                            return@post call.respondForbidden()
                        }

                        val payload = runCatching { call.receive<AdminTableCreateRequest>() }.getOrNull()
                            ?: return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)

                        val zone = payload.zone?.trim()
                        val validationErrors = mutableMapOf<String, String>()
                        val (payloadErrors, arrivalWindow) = payload.validate(zone)
                        validationErrors.putAll(payloadErrors)
                        val zones = adminTablesRepository.listZonesForClub(clubId)
                        if (
                            zone != null &&
                                "zone" !in validationErrors &&
                                zones.none { it.id == zone }
                        ) {
                            validationErrors["zone"] = "unknown_zone"
                        }
                        if (validationErrors.isNotEmpty()) {
                            return@post call.respondValidationErrors(validationErrors)
                        }

                        val created =
                            adminTablesRepository.create(
                                AdminTableCreate(
                                    clubId = clubId,
                                    label = payload.label.trim(),
                                    minDeposit = payload.minDeposit ?: 0,
                                    capacity = payload.capacity,
                                    zone = zone,
                                    arrivalWindow = arrivalWindow,
                                mysteryEligible = payload.mysteryEligible,
                            ),
                        )
                        logger.info("admin.tables.create club_id={} table_id={} by={}", clubId, created.id, call.rbacContext().user.id)
                        call.respond(HttpStatusCode.Created, created.toResponse(clubId, zones))
                    }

                    put {
                        val clubId = call.requireClubId() ?: return@put
                        if (!call.isClubAllowed(clubId)) {
                            return@put call.respondForbidden()
                        }

                        val payload = runCatching { call.receive<AdminTableUpdateRequest>() }.getOrNull()
                            ?: return@put call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)

                        val zone = payload.zone?.trim()
                        val validationErrors = mutableMapOf<String, String>()
                        val (payloadErrors, arrivalWindow) = payload.validate(zone)
                        validationErrors.putAll(payloadErrors)
                        val zones = adminTablesRepository.listZonesForClub(clubId)
                        if (
                            zone != null &&
                                "zone" !in validationErrors &&
                                zones.none { it.id == zone }
                        ) {
                            validationErrors["zone"] = "unknown_zone"
                        }
                        if (validationErrors.isNotEmpty()) {
                            return@put call.respondValidationErrors(validationErrors)
                        }

                        val updated =
                            adminTablesRepository.update(
                                AdminTableUpdate(
                                    id = payload.id,
                                    clubId = clubId,
                                    label = payload.label?.trim(),
                                    minDeposit = payload.minDeposit,
                                    capacity = payload.capacity,
                                    zone = zone,
                                    arrivalWindow = arrivalWindow,
                                    mysteryEligible = payload.mysteryEligible,
                                ),
                            )
                                ?: return@put call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                        logger.info("admin.tables.update club_id={} table_id={} by={}", clubId, updated.id, call.rbacContext().user.id)
                        call.respond(HttpStatusCode.OK, updated.toResponse(clubId, zones))
                    }

                    delete("/{id}") {
                        val clubId = call.requireClubId() ?: return@delete
                        if (!call.isClubAllowed(clubId)) {
                            return@delete call.respondForbidden()
                        }

                        val id = call.parameters["id"]?.toLongOrNull()
                        if (id == null || id <= 0) {
                            return@delete call.respondValidationErrors(mapOf("id" to "must_be_positive"))
                        }

                        val deleted = adminTablesRepository.delete(clubId, id)
                        if (!deleted) {
                            return@delete call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                        }

                        call.respond(HttpStatusCode.NoContent, Unit)
                    }
                }
            }
        }
    }
}

private fun AdminTableCreateRequest.validate(trimmedZone: String?): Pair<Map<String, String>, ArrivalWindow?> {
    val errors = mutableMapOf<String, String>()
    if (label.isBlank() || label.trim().length > MAX_LABEL_LENGTH) errors["label"] = "length_1_100"
    if ((minDeposit ?: 0) < 0) errors["minDeposit"] = "must_be_non_negative"
    if (capacity !in 1..MAX_CAPACITY) errors["capacity"] = "must_be_between_1_$MAX_CAPACITY"
    trimmedZone?.let {
        if (it.isEmpty() || it.length > MAX_ZONE_LENGTH) errors["zone"] = "length_1_$MAX_ZONE_LENGTH"
    }
    val arrival = arrivalWindow?.trim()?.let { validateArrivalWindow(it, errors) }
    return errors to arrival
}

private fun AdminTableUpdateRequest.validate(trimmedZone: String?): Pair<Map<String, String>, ArrivalWindow?> {
    val errors = mutableMapOf<String, String>()
    if (id <= 0) errors["id"] = "must_be_positive"
    label?.let { if (it.trim().isEmpty() || it.trim().length > MAX_LABEL_LENGTH) errors["label"] = "length_1_100" }
    minDeposit?.let { if (it < 0) errors["minDeposit"] = "must_be_non_negative" }
    capacity?.let { if (it !in 1..MAX_CAPACITY) errors["capacity"] = "must_be_between_1_$MAX_CAPACITY" }
    trimmedZone?.let { if (it.isEmpty() || it.length > MAX_ZONE_LENGTH) errors["zone"] = "length_1_$MAX_ZONE_LENGTH" }
    val arrival = arrivalWindow?.trim()?.let { validateArrivalWindow(it, errors) }
    return errors to arrival
}

private fun validateArrivalWindow(raw: String, errors: MutableMap<String, String>): ArrivalWindow? {
    val parsed = parseArrivalWindowOrNull(raw)
    if (parsed == null) errors["arrivalWindow"] = "invalid_format"
    return parsed
}

private suspend fun ApplicationCall.respondValidationErrors(details: Map<String, String>) {
    ensureMiniAppNoStoreHeaders()
    respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error, details = details)
}

private suspend fun ApplicationCall.respondForbidden() {
    ensureMiniAppNoStoreHeaders()
    respondError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
}

private suspend fun ApplicationCall.requireClubId(): Long? {
    val clubId = request.queryParameters["clubId"]?.toLongOrNull()
    if (clubId == null || clubId <= 0) {
        respondValidationErrors(mapOf("clubId" to "must_be_positive"))
        return null
    }
    return clubId
}

private suspend fun ApplicationCall.requirePageOrNull(): Int? {
    val raw = request.queryParameters["page"] ?: return 0
    val page = raw.toIntOrNull()
    if (page == null || page < 0) {
        respondValidationErrors(mapOf("page" to "must_be_non_negative"))
        return null
    }
    return page
}

private suspend fun ApplicationCall.requireSizeOrNull(): Int? {
    val raw = request.queryParameters["size"] ?: return 50
    val size = raw.toIntOrNull()
    if (size == null || size !in 1..200) {
        respondValidationErrors(mapOf("size" to "must_be_between_1_200"))
        return null
    }
    return size
}

private fun ApplicationCall.isClubAllowed(clubId: Long): Boolean {
    val context = rbacContext()
    val elevated = context.roles.any { it in setOf(Role.OWNER, Role.GLOBAL_ADMIN, Role.HEAD_MANAGER) }
    return elevated || clubId in context.clubIds
}

private fun com.example.bot.layout.Table.toResponse(
    clubId: Long,
    zones: List<com.example.bot.layout.Zone>,
): AdminTableResponse {
    val effectiveZoneId = zone ?: zoneId
    val zoneName = zones.firstOrNull { it.id == effectiveZoneId }?.name

    return AdminTableResponse(
        id = id,
        clubId = clubId,
        label = label,
        minDeposit = minDeposit,
        capacity = capacity,
        zone = effectiveZoneId,
        zoneName = zoneName,
        arrivalWindow = arrivalWindow?.toRangeString(),
        mysteryEligible = mysteryEligible,
    )
}
