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
import com.example.bot.plugins.miniAppBotTokenProvider
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
import io.ktor.server.routing.patch
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
    val tableNumber: Int? = null,
    val x: Double? = null,
    val y: Double? = null,
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
    val tableNumber: Int? = null,
    val x: Double? = null,
    val y: Double? = null,
)

@Serializable
private data class AdminTablePatchRequest(
    val label: String? = null,
    val minDeposit: Long? = null,
    val capacity: Int? = null,
    /** Zone identifier (Zone.id) from the layout. */
    val zone: String? = null,
    val arrivalWindow: String? = null,
    val mysteryEligible: Boolean? = null,
    val tableNumber: Int? = null,
    val x: Double? = null,
    val y: Double? = null,
)

@Serializable
private data class AdminTableResponse(
    val id: Long,
    val hallId: Long,
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
    val tableNumber: Int,
    val x: Double,
    val y: Double,
)

fun Application.adminTablesRoutes(
    adminTablesRepository: AdminTablesRepository,
    adminHallsRepository: com.example.bot.admin.AdminHallsRepository,
    botTokenProvider: () -> String = miniAppBotTokenProvider(),
) {
    val logger = LoggerFactory.getLogger("AdminTablesRoutes")

    routing {
        route("/api/admin") {
            intercept(ApplicationCallPipeline.Setup) { call.ensureMiniAppNoStoreHeaders() }
            withMiniAppAuth { botTokenProvider() }

            authorize(Role.OWNER, Role.GLOBAL_ADMIN, Role.CLUB_ADMIN) {
                route("/halls/{hallId}/tables") {
                    get {
                        val hallId = call.requireHallId() ?: return@get
                        val hall = adminHallsRepository.getById(hallId)
                            ?: return@get call.respondError(HttpStatusCode.NotFound, ErrorCodes.hall_not_found)
                        if (!call.isAdminClubAllowed(hall.clubId)) {
                            return@get call.respondForbidden()
                        }

                        val zones = adminTablesRepository.listZonesForHall(hallId)
                        val tables = adminTablesRepository.listForHall(hallId)
                        val sorted = tables.sortedWith(compareBy<com.example.bot.layout.Table> { it.zoneId }.thenBy { it.id })
                        call.respond(HttpStatusCode.OK, sorted.map { it.toResponse(hall.clubId, hallId, zones) })
                    }

                    post {
                        val hallId = call.requireHallId() ?: return@post
                        val hall = adminHallsRepository.getById(hallId)
                            ?: return@post call.respondError(HttpStatusCode.NotFound, ErrorCodes.hall_not_found)
                        if (!call.isAdminClubAllowed(hall.clubId)) {
                            return@post call.respondForbidden()
                        }

                        val payload = runCatching { call.receive<AdminTableCreateRequest>() }.getOrNull()
                            ?: return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)

                        val zone = payload.zone?.trim()
                        val validationErrors = mutableMapOf<String, String>()
                        val capacityError = payload.validateCapacity(validationErrors)
                        val coordsError = payload.validateCoords(validationErrors)
                        val (payloadErrors, arrivalWindow) = payload.validate(zone)
                        validationErrors.putAll(payloadErrors)
                        val zones = adminTablesRepository.listZonesForHall(hallId)
                        if (
                            zone != null &&
                                "zone" !in validationErrors &&
                                zones.none { it.id == zone }
                        ) {
                            validationErrors["zone"] = "unknown_zone"
                        }
                        if (payload.tableNumber != null && payload.tableNumber <= 0) {
                            validationErrors["tableNumber"] = "must_be_positive"
                        }
                        if (coordsError) {
                            return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_table_coords, details = validationErrors)
                        }
                        if (capacityError) {
                            return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_capacity, details = validationErrors)
                        }
                        if (validationErrors.isNotEmpty()) {
                            return@post call.respondValidationErrors(validationErrors)
                        }
                        if (payload.tableNumber != null && adminTablesRepository.isTableNumberTaken(hallId, payload.tableNumber)) {
                            return@post call.respondError(HttpStatusCode.Conflict, ErrorCodes.table_number_conflict)
                        }

                        val created =
                            adminTablesRepository.createForHall(
                                AdminTableCreate(
                                    clubId = hall.clubId,
                                    label = payload.label.trim(),
                                    minDeposit = payload.minDeposit ?: 0,
                                    capacity = payload.capacity,
                                    zone = zone,
                                    arrivalWindow = arrivalWindow,
                                    mysteryEligible = payload.mysteryEligible,
                                    tableNumber = payload.tableNumber,
                                    x = payload.x,
                                    y = payload.y,
                                    hallId = hallId,
                                ),
                            )
                        logger.info("admin.halls.tables.create hall_id={} table_id={} by={}", hallId, created.id, call.rbacContext().user.id)
                        call.respond(HttpStatusCode.Created, created.toResponse(hall.clubId, hallId, zones))
                    }
                }

                route("/halls/{hallId}/tables/{id}") {
                    patch {
                        val hallId = call.requireHallId() ?: return@patch
                        val tableId = call.parameters["id"]?.toLongOrNull()
                        if (tableId == null || tableId <= 0) {
                            return@patch call.respondValidationErrors(mapOf("id" to "must_be_positive"))
                        }
                        val hall = adminHallsRepository.getById(hallId)
                            ?: return@patch call.respondError(HttpStatusCode.NotFound, ErrorCodes.hall_not_found)
                        if (!call.isAdminClubAllowed(hall.clubId)) {
                            return@patch call.respondForbidden()
                        }

                        val payload = runCatching { call.receive<AdminTablePatchRequest>() }.getOrNull()
                            ?: return@patch call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)

                        val zone = payload.zone?.trim()
                        val validationErrors = mutableMapOf<String, String>()
                        val capacityError = payload.validateCapacity(validationErrors)
                        val coordsError = payload.validateCoords(validationErrors)
                        val (payloadErrors, arrivalWindow) = payload.validate(zone)
                        validationErrors.putAll(payloadErrors)
                        if (payload.tableNumber != null && payload.tableNumber <= 0) {
                            validationErrors["tableNumber"] = "must_be_positive"
                        }
                        val zones = adminTablesRepository.listZonesForHall(hallId)
                        if (
                            zone != null &&
                                "zone" !in validationErrors &&
                                zones.none { it.id == zone }
                        ) {
                            validationErrors["zone"] = "unknown_zone"
                        }
                        if (coordsError) {
                            return@patch call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_table_coords, details = validationErrors)
                        }
                        if (capacityError) {
                            return@patch call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_capacity, details = validationErrors)
                        }
                        if (validationErrors.isNotEmpty()) {
                            return@patch call.respondValidationErrors(validationErrors)
                        }
                        if (payload.tableNumber != null && adminTablesRepository.isTableNumberTaken(hallId, payload.tableNumber, tableId)) {
                            return@patch call.respondError(HttpStatusCode.Conflict, ErrorCodes.table_number_conflict)
                        }

                        val updated =
                            adminTablesRepository.updateForHall(
                                AdminTableUpdate(
                                    id = tableId,
                                    clubId = hall.clubId,
                                    label = payload.label?.trim(),
                                    minDeposit = payload.minDeposit,
                                    capacity = payload.capacity,
                                    zone = zone,
                                    arrivalWindow = arrivalWindow,
                                    mysteryEligible = payload.mysteryEligible,
                                    tableNumber = payload.tableNumber,
                                    x = payload.x,
                                    y = payload.y,
                                    hallId = hallId,
                                ),
                            )
                                ?: return@patch call.respondError(HttpStatusCode.NotFound, ErrorCodes.table_not_found)

                        logger.info("admin.halls.tables.update hall_id={} table_id={} by={}", hallId, updated.id, call.rbacContext().user.id)
                        call.respond(HttpStatusCode.OK, updated.toResponse(hall.clubId, hallId, zones))
                    }

                    delete {
                        val hallId = call.requireHallId() ?: return@delete
                        val tableId = call.parameters["id"]?.toLongOrNull()
                        if (tableId == null || tableId <= 0) {
                            return@delete call.respondValidationErrors(mapOf("id" to "must_be_positive"))
                        }
                        val hall = adminHallsRepository.getById(hallId)
                            ?: return@delete call.respondError(HttpStatusCode.NotFound, ErrorCodes.hall_not_found)
                        if (!call.isAdminClubAllowed(hall.clubId)) {
                            return@delete call.respondForbidden()
                        }

                        val deleted = adminTablesRepository.deleteForHall(hallId, tableId)
                        if (!deleted) {
                            return@delete call.respondError(HttpStatusCode.NotFound, ErrorCodes.table_not_found)
                        }

                        call.respond(HttpStatusCode.NoContent, Unit)
                    }
                }

                route("/tables") {
                    get {
                        val clubId = call.requireClubId() ?: return@get
                        if (!call.isAdminClubAllowed(clubId)) {
                            return@get call.respondForbidden()
                        }

                        val page = call.requirePageOrNull() ?: return@get
                        val size = call.requireSizeOrNull() ?: return@get
                        val activeHall = adminHallsRepository.findActiveForClub(clubId)
                        if (activeHall == null) {
                            call.respond(HttpStatusCode.OK, emptyList<AdminTableResponse>())
                            return@get
                        }

                        val zones = adminTablesRepository.listZonesForClub(clubId)
                        val tables = adminTablesRepository.listForClub(clubId)
                        val sorted = tables.sortedWith(compareBy<com.example.bot.layout.Table> { it.zoneId }.thenBy { it.id })
                        val offset = page * size
                        val pageItems = if (offset >= sorted.size) emptyList() else sorted.drop(offset).take(size)
                        call.respond(HttpStatusCode.OK, pageItems.map { it.toResponse(clubId, activeHall.id, zones) })
                    }

                    get("/{id}") {
                        val clubId = call.requireClubId() ?: return@get
                        if (!call.isAdminClubAllowed(clubId)) {
                            return@get call.respondForbidden()
                        }

                        val id = call.parameters["id"]?.toLongOrNull()
                        if (id == null || id <= 0) {
                            return@get call.respondValidationErrors(mapOf("id" to "must_be_positive"))
                        }

                        val activeHall = adminHallsRepository.findActiveForClub(clubId)
                            ?: return@get call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                        val zones = adminTablesRepository.listZonesForClub(clubId)
                        val table = adminTablesRepository.findById(clubId, id)
                            ?: return@get call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)

                        call.respond(HttpStatusCode.OK, table.toResponse(clubId, activeHall.id, zones))
                    }

                    post {
                        val clubId = call.requireClubId() ?: return@post
                        if (!call.isAdminClubAllowed(clubId)) {
                            return@post call.respondForbidden()
                        }

                        val payload = runCatching { call.receive<AdminTableCreateRequest>() }.getOrNull()
                            ?: return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)

                        val zone = payload.zone?.trim()
                        val validationErrors = mutableMapOf<String, String>()
                        payload.validateCapacity(validationErrors)
                        payload.validateCoords(validationErrors)
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

                        val activeHall = adminHallsRepository.findActiveForClub(clubId)
                            ?: return@post call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                        if (payload.tableNumber != null && adminTablesRepository.isTableNumberTaken(activeHall.id, payload.tableNumber)) {
                            return@post call.respondError(HttpStatusCode.Conflict, ErrorCodes.table_number_conflict)
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
                                    tableNumber = payload.tableNumber,
                                    x = payload.x,
                                    y = payload.y,
                                    hallId = activeHall.id,
                                ),
                            )
                        logger.info("admin.tables.create club_id={} table_id={} by={}", clubId, created.id, call.rbacContext().user.id)
                        call.respond(HttpStatusCode.Created, created.toResponse(clubId, activeHall.id, zones))
                    }

                    put {
                        val clubId = call.requireClubId() ?: return@put
                        if (!call.isAdminClubAllowed(clubId)) {
                            return@put call.respondForbidden()
                        }

                        val payload = runCatching { call.receive<AdminTableUpdateRequest>() }.getOrNull()
                            ?: return@put call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)

                        val zone = payload.zone?.trim()
                        val validationErrors = mutableMapOf<String, String>()
                        payload.validateCapacity(validationErrors)
                        payload.validateCoords(validationErrors)
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

                        val activeHall = adminHallsRepository.findActiveForClub(clubId)
                            ?: return@put call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                        if (payload.tableNumber != null && adminTablesRepository.isTableNumberTaken(activeHall.id, payload.tableNumber, payload.id)) {
                            return@put call.respondError(HttpStatusCode.Conflict, ErrorCodes.table_number_conflict)
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
                                    tableNumber = payload.tableNumber,
                                    x = payload.x,
                                    y = payload.y,
                                    hallId = activeHall.id,
                                ),
                            )
                                ?: return@put call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                        logger.info("admin.tables.update club_id={} table_id={} by={}", clubId, updated.id, call.rbacContext().user.id)
                        call.respond(HttpStatusCode.OK, updated.toResponse(clubId, activeHall.id, zones))
                    }

                    delete("/{id}") {
                        val clubId = call.requireClubId() ?: return@delete
                        if (!call.isAdminClubAllowed(clubId)) {
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
    trimmedZone?.let {
        if (it.isEmpty() || it.length > MAX_ZONE_LENGTH) errors["zone"] = "length_1_$MAX_ZONE_LENGTH"
    }
    if (tableNumber != null && tableNumber <= 0) errors["tableNumber"] = "must_be_positive"
    val arrival = arrivalWindow?.trim()?.let { validateArrivalWindow(it, errors) }
    return errors to arrival
}

private fun AdminTableUpdateRequest.validate(trimmedZone: String?): Pair<Map<String, String>, ArrivalWindow?> {
    val errors = mutableMapOf<String, String>()
    if (id <= 0) errors["id"] = "must_be_positive"
    label?.let { if (it.trim().isEmpty() || it.trim().length > MAX_LABEL_LENGTH) errors["label"] = "length_1_100" }
    minDeposit?.let { if (it < 0) errors["minDeposit"] = "must_be_non_negative" }
    trimmedZone?.let { if (it.isEmpty() || it.length > MAX_ZONE_LENGTH) errors["zone"] = "length_1_$MAX_ZONE_LENGTH" }
    tableNumber?.let { if (it <= 0) errors["tableNumber"] = "must_be_positive" }
    val arrival = arrivalWindow?.trim()?.let { validateArrivalWindow(it, errors) }
    return errors to arrival
}

private fun AdminTablePatchRequest.validate(trimmedZone: String?): Pair<Map<String, String>, ArrivalWindow?> {
    val errors = mutableMapOf<String, String>()
    if (
        label == null &&
            minDeposit == null &&
            capacity == null &&
            zone == null &&
            arrivalWindow == null &&
            mysteryEligible == null &&
            tableNumber == null &&
            x == null &&
            y == null
    ) {
        errors["payload"] = "must_include_field"
        return errors to null
    }
    label?.let { if (it.trim().isEmpty() || it.trim().length > MAX_LABEL_LENGTH) errors["label"] = "length_1_100" }
    minDeposit?.let { if (it < 0) errors["minDeposit"] = "must_be_non_negative" }
    trimmedZone?.let { if (it.isEmpty() || it.length > MAX_ZONE_LENGTH) errors["zone"] = "length_1_$MAX_ZONE_LENGTH" }
    tableNumber?.let { if (it <= 0) errors["tableNumber"] = "must_be_positive" }
    val arrival = arrivalWindow?.trim()?.let { validateArrivalWindow(it, errors) }
    return errors to arrival
}

private fun AdminTableCreateRequest.validateCapacity(errors: MutableMap<String, String>): Boolean {
    if (capacity !in 1..MAX_CAPACITY) {
        errors["capacity"] = "must_be_between_1_$MAX_CAPACITY"
        return true
    }
    return false
}

private fun AdminTableUpdateRequest.validateCapacity(errors: MutableMap<String, String>): Boolean {
    if (capacity != null && capacity !in 1..MAX_CAPACITY) {
        errors["capacity"] = "must_be_between_1_$MAX_CAPACITY"
        return true
    }
    return false
}

private fun AdminTablePatchRequest.validateCapacity(errors: MutableMap<String, String>): Boolean {
    if (capacity != null && capacity !in 1..MAX_CAPACITY) {
        errors["capacity"] = "must_be_between_1_$MAX_CAPACITY"
        return true
    }
    return false
}

private fun AdminTableCreateRequest.validateCoords(errors: MutableMap<String, String>): Boolean =
    validateCoords(x, y, errors)

private fun AdminTableUpdateRequest.validateCoords(errors: MutableMap<String, String>): Boolean =
    validateCoords(x, y, errors)

private fun AdminTablePatchRequest.validateCoords(errors: MutableMap<String, String>): Boolean =
    validateCoords(x, y, errors)

private fun validateCoords(x: Double?, y: Double?, errors: MutableMap<String, String>): Boolean {
    if (x == null && y == null) return false
    if (x == null || y == null) {
        errors["coords"] = "both_required"
        return true
    }
    val xValid = x.isFinite() && x in 0.0..1.0
    val yValid = y.isFinite() && y in 0.0..1.0
    if (!xValid) errors["x"] = "must_be_between_0_1"
    if (!yValid) errors["y"] = "must_be_between_0_1"
    return !xValid || !yValid
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

private suspend fun ApplicationCall.requireHallId(): Long? {
    val hallId = parameters["hallId"]?.toLongOrNull()
    if (hallId == null || hallId <= 0) {
        respondValidationErrors(mapOf("hallId" to "must_be_positive"))
        return null
    }
    return hallId
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

private fun com.example.bot.layout.Table.toResponse(
    clubId: Long,
    hallId: Long,
    zones: List<com.example.bot.layout.Zone>,
): AdminTableResponse {
    val effectiveZoneId = zone ?: zoneId
    val zoneName = zones.firstOrNull { it.id == effectiveZoneId }?.name

    return AdminTableResponse(
        id = id,
        hallId = hallId,
        clubId = clubId,
        label = label,
        minDeposit = minDeposit,
        capacity = capacity,
        zone = effectiveZoneId,
        zoneName = zoneName,
        arrivalWindow = arrivalWindow?.toRangeString(),
        mysteryEligible = mysteryEligible,
        tableNumber = tableNumber,
        x = x,
        y = y,
    )
}
