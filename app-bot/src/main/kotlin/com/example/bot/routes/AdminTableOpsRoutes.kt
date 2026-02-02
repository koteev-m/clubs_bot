package com.example.bot.routes

import com.example.bot.audit.AuditLogger
import com.example.bot.data.booking.AllocationInput
import com.example.bot.data.booking.TableDeposit
import com.example.bot.data.booking.TableDepositRepository
import com.example.bot.data.booking.TableSession
import com.example.bot.data.booking.TableSessionRepository
import com.example.bot.data.security.Role
import com.example.bot.data.visits.NightOverrideRepository
import com.example.bot.data.visits.VisitCheckInInput
import com.example.bot.data.visits.VisitRepository
import com.example.bot.http.ErrorCodes
import com.example.bot.http.ensureMiniAppNoStoreHeaders
import com.example.bot.http.respondError
import com.example.bot.layout.AdminTablesRepository
import com.example.bot.layout.Table
import com.example.bot.plugins.miniAppBotTokenProvider
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.security.rbac.authorize
import com.example.bot.security.rbac.rbacContext
import com.example.bot.tables.GuestQrResolveResult
import com.example.bot.tables.GuestQrResolver
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private const val MAX_NOTE_LENGTH = 500

@Serializable
private data class TableDepositAllocationDto(
    val categoryCode: String,
    val amountMinor: Long,
)

@Serializable
private data class TableDepositDto(
    val id: Long,
    val amountMinor: Long,
    val guestUserId: Long? = null,
    val allocations: List<TableDepositAllocationDto>,
)

@Serializable
private data class AdminNightTableResponse(
    val tableId: Long,
    val label: String,
    val tableNumber: Int,
    val isOccupied: Boolean,
    val activeSessionId: Long? = null,
    val activeDeposit: TableDepositDto? = null,
)

@Serializable
private data class AdminSeatTableRequest(
    val mode: SeatMode,
    val guestPassQr: String? = null,
    val guestUserId: Long? = null,
    val depositAmount: Long? = null,
    val allocations: List<AdminDepositAllocationRequest> = emptyList(),
    val note: String? = null,
)

@Serializable
private data class AdminDepositAllocationRequest(
    val categoryCode: String,
    val amount: Long,
)

@Serializable
private data class AdminSeatTableResponse(
    val sessionId: Long,
    val depositId: Long,
    val table: AdminNightTableResponse,
)

@Serializable
private data class AdminFreeTableResponse(
    val closedSessionId: Long,
    val table: AdminNightTableResponse,
)

@Serializable
private data class AdminUpdateDepositRequest(
    val amount: Long,
    val allocations: List<AdminDepositAllocationRequest> = emptyList(),
    val reason: String,
)

@Serializable
private data class AdminUpdateDepositResponse(
    val deposit: TableDepositDto,
)

@Serializable
private enum class SeatMode {
    WITH_QR,
    NO_QR,
}

fun Application.adminTableOpsRoutes(
    adminTablesRepository: AdminTablesRepository,
    tableSessionRepository: TableSessionRepository,
    tableDepositRepository: TableDepositRepository,
    visitRepository: VisitRepository,
    nightOverrideRepository: NightOverrideRepository,
    gamificationSettingsRepository: com.example.bot.data.gamification.GamificationSettingsRepository,
    auditLogger: AuditLogger,
    guestQrResolver: GuestQrResolver,
    botTokenProvider: () -> String = miniAppBotTokenProvider(),
    clock: Clock = Clock.systemUTC(),
) {
    val logger = LoggerFactory.getLogger("AdminTableOpsRoutes")

    routing {
        route("/api/admin") {
            intercept(ApplicationCallPipeline.Setup) { call.ensureMiniAppNoStoreHeaders() }
            withMiniAppAuth { botTokenProvider() }

            authorize(Role.OWNER, Role.GLOBAL_ADMIN, Role.HEAD_MANAGER, Role.CLUB_ADMIN, Role.MANAGER) {
                route("/clubs/{clubId}/nights/{nightStartUtc}") {
                    get("/tables") {
                        val clubId = call.requireClubIdPath() ?: return@get
                        val nightStartUtc = call.requireNightStartUtc() ?: return@get
                        if (!call.isAdminClubAllowed(clubId)) {
                            return@get call.respondForbidden()
                        }

                        val tables = adminTablesRepository.listForClub(clubId)
                        val sessions = tableSessionRepository.listActive(clubId, nightStartUtc)
                        val sessionsByTable = sessions.associateBy { it.tableId }
                        val depositsBySession = mutableMapOf<Long, TableDeposit?>()
                        for (session in sessions) {
                            val deposits = tableDepositRepository.listDepositsForSession(clubId, session.id)
                            depositsBySession[session.id] =
                                deposits.maxWithOrNull(
                                    compareBy<TableDeposit> { it.createdAt }.thenBy { it.id },
                                )
                        }
                        val response =
                            tables.sortedBy { it.id }.map { table ->
                                val session = sessionsByTable[table.id]
                                val activeDeposit = session?.let { depositsBySession[it.id] }
                                table.toNightResponse(session, activeDeposit)
                            }
                        call.respond(HttpStatusCode.OK, response)
                    }

                    post("/tables/{tableId}/seat") {
                        val clubId = call.requireClubIdPath() ?: return@post
                        val nightStartUtc = call.requireNightStartUtc() ?: return@post
                        val tableId = call.requireTableId() ?: return@post
                        if (!call.isAdminClubAllowed(clubId)) {
                            return@post call.respondForbidden()
                        }

                        val table = adminTablesRepository.findById(clubId, tableId)
                            ?: return@post call.respondError(HttpStatusCode.NotFound, ErrorCodes.table_not_found)

                        val payload = runCatching { call.receive<AdminSeatTableRequest>() }.getOrNull()
                            ?: return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)

                        val validationErrors = payload.validateSeatPayload()
                        if (validationErrors.isNotEmpty()) {
                            return@post call.respondValidationErrors(validationErrors)
                        }

                        val now = Instant.now(clock)
                        val actorId = call.rbacContext().user.id
                        val actorRole = call.rbacContext().roles.firstOrNull()

                        val (resolvedGuestUserId, resolvedEventId) =
                            resolveGuestUserId(
                                payload = payload,
                                guestQrResolver = guestQrResolver,
                                clubId = clubId,
                                now = now,
                                call = call,
                            ) ?: return@post

                        val note = payload.note?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_NOTE_LENGTH)
                        val session =
                            tableSessionRepository.openSession(
                                clubId = clubId,
                                nightStartUtc = nightStartUtc,
                                tableId = tableId,
                                actorId = actorId,
                                now = now,
                                note = note,
                            )
                        val allocations = payload.allocations.toAllocationInputs()
                        val amountMinor = payload.depositAmount ?: 0L
                        val deposit =
                            runCatching {
                                tableDepositRepository.createDeposit(
                                    clubId = clubId,
                                    nightStartUtc = nightStartUtc,
                                    tableId = tableId,
                                    sessionId = session.id,
                                    guestUserId = resolvedGuestUserId,
                                    bookingId = null,
                                    paymentId = null,
                                    amountMinor = amountMinor,
                                    allocations = allocations,
                                    actorId = actorId,
                                    now = now,
                                )
                            }.getOrElse { ex ->
                                logger.warn(
                                    "admin.tables.seat.deposit_failed clubId={} tableId={} sessionId={}",
                                    clubId,
                                    tableId,
                                    session.id,
                                    ex,
                                )
                                return@post call.respondValidationErrors(mapOf("allocations" to "invalid"))
                            }

                        auditLogger.tableSessionOpened(
                            clubId = clubId,
                            nightStartUtc = nightStartUtc,
                            tableId = tableId,
                            sessionId = session.id,
                            actorUserId = actorId,
                            actorRole = actorRole?.name,
                            guestUserId = resolvedGuestUserId,
                            note = note,
                        )
                        auditLogger.tableDepositCreated(
                            clubId = clubId,
                            nightStartUtc = nightStartUtc,
                            tableId = tableId,
                            sessionId = session.id,
                            depositId = deposit.id,
                            guestUserId = resolvedGuestUserId,
                            amountMinor = deposit.amountMinor,
                            allocations = deposit.allocations.map { it.categoryCode to it.amountMinor },
                            actorUserId = actorId,
                            actorRole = actorRole?.name,
                        )

                        if (resolvedGuestUserId != null) {
                            markHasTableIfPossible(
                                visitRepository = visitRepository,
                                nightOverrideRepository = nightOverrideRepository,
                                settingsRepository = gamificationSettingsRepository,
                                clubId = clubId,
                                nightStartUtc = nightStartUtc,
                                eventId = resolvedEventId,
                                guestUserId = resolvedGuestUserId,
                                actorId = actorId,
                                actorRole = actorRole,
                                now = now,
                                logger = logger,
                            )
                        }

                        val responseTable = table.toNightResponse(session, deposit)
                        call.respond(
                            HttpStatusCode.Created,
                            AdminSeatTableResponse(
                                sessionId = session.id,
                                depositId = deposit.id,
                                table = responseTable,
                            ),
                        )
                    }

                    post("/tables/{tableId}/free") {
                        val clubId = call.requireClubIdPath() ?: return@post
                        val nightStartUtc = call.requireNightStartUtc() ?: return@post
                        val tableId = call.requireTableId() ?: return@post
                        if (!call.isAdminClubAllowed(clubId)) {
                            return@post call.respondForbidden()
                        }

                        val table = adminTablesRepository.findById(clubId, tableId)
                            ?: return@post call.respondError(HttpStatusCode.NotFound, ErrorCodes.table_not_found)

                        val session =
                            tableSessionRepository.findActiveSession(clubId, nightStartUtc, tableId)
                                ?: return@post call.respondError(HttpStatusCode.Conflict, ErrorCodes.invalid_state)

                        val now = Instant.now(clock)
                        val actorId = call.rbacContext().user.id
                        val actorRole = call.rbacContext().roles.firstOrNull()
                        val closed = tableSessionRepository.closeSession(session.id, clubId, actorId, now)
                        if (!closed) {
                            return@post call.respondError(HttpStatusCode.Conflict, ErrorCodes.invalid_state)
                        }

                        val lastDeposit =
                            tableDepositRepository.listDepositsForSession(clubId, session.id)
                                .maxWithOrNull(compareBy<TableDeposit> { it.createdAt }.thenBy { it.id })
                        auditLogger.tableSessionClosed(
                            clubId = clubId,
                            nightStartUtc = nightStartUtc,
                            tableId = tableId,
                            sessionId = session.id,
                            actorUserId = actorId,
                            actorRole = actorRole?.name,
                            guestUserId = lastDeposit?.guestUserId,
                        )

                        val responseTable = table.toNightResponse(null, null)
                        call.respond(
                            HttpStatusCode.OK,
                            AdminFreeTableResponse(
                                closedSessionId = session.id,
                                table = responseTable,
                            ),
                        )
                    }

                    put("/deposits/{depositId}") {
                        val clubId = call.requireClubIdPath() ?: return@put
                        val nightStartUtc = call.requireNightStartUtc() ?: return@put
                        val depositId = call.requireDepositId() ?: return@put
                        if (!call.isAdminClubAllowed(clubId)) {
                            return@put call.respondForbidden()
                        }

                        val payload = runCatching { call.receive<AdminUpdateDepositRequest>() }.getOrNull()
                            ?: return@put call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)

                        val validationErrors = payload.validateDepositUpdate()
                        if (validationErrors.isNotEmpty()) {
                            return@put call.respondValidationErrors(validationErrors)
                        }

                        val existing =
                            tableDepositRepository.findById(clubId, depositId)
                                ?: return@put call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                        if (existing.nightStartUtc != nightStartUtc) {
                            return@put call.respondError(HttpStatusCode.NotFound, ErrorCodes.not_found)
                        }

                        val now = Instant.now(clock)
                        val actorId = call.rbacContext().user.id
                        val actorRole = call.rbacContext().roles.firstOrNull()
                        val allocations = payload.allocations.toAllocationInputs()
                        val updated =
                            tableDepositRepository.updateDeposit(
                                clubId = clubId,
                                depositId = depositId,
                                amountMinor = payload.amount,
                                allocations = allocations,
                                reason = payload.reason.trim(),
                                actorId = actorId,
                                now = now,
                            )
                        auditLogger.tableDepositUpdated(
                            clubId = clubId,
                            nightStartUtc = nightStartUtc,
                            tableId = updated.tableId,
                            sessionId = updated.tableSessionId,
                            depositId = updated.id,
                            guestUserId = updated.guestUserId,
                            amountMinor = updated.amountMinor,
                            allocations = updated.allocations.map { it.categoryCode to it.amountMinor },
                            reason = payload.reason.trim(),
                            actorUserId = actorId,
                            actorRole = actorRole?.name,
                        )
                        call.respond(HttpStatusCode.OK, AdminUpdateDepositResponse(deposit = updated.toDto()))
                    }
                }
            }
        }
    }
}

private fun Table.toNightResponse(
    session: TableSession?,
    deposit: TableDeposit?,
): AdminNightTableResponse =
    AdminNightTableResponse(
        tableId = id,
        label = label,
        tableNumber = tableNumber,
        isOccupied = session != null,
        activeSessionId = session?.id,
        activeDeposit = deposit?.toDto(),
    )

private fun TableDeposit.toDto(): TableDepositDto =
    TableDepositDto(
        id = id,
        amountMinor = amountMinor,
        guestUserId = guestUserId,
        allocations = allocations.map { TableDepositAllocationDto(it.categoryCode, it.amountMinor) },
    )

private suspend fun resolveGuestUserId(
    payload: AdminSeatTableRequest,
    guestQrResolver: GuestQrResolver,
    clubId: Long,
    now: Instant,
    call: ApplicationCall,
): Pair<Long?, Long?>? {
    var guestUserId = payload.guestUserId?.takeIf { it > 0 }
    var eventId: Long? = null
    if (payload.mode == SeatMode.WITH_QR) {
        val qr = payload.guestPassQr?.trim().orEmpty()
        val resolution = guestQrResolver.resolveGuest(clubId, qr, now)
        when (resolution) {
            is GuestQrResolveResult.Failure -> {
                call.respondError(resolution.status, resolution.errorCode)
                return null
            }

            is GuestQrResolveResult.Success -> {
                eventId = resolution.eventId
                if (guestUserId == null) {
                    guestUserId = resolution.guestUserId
                }
            }
        }
    }
    return guestUserId to eventId
}

private fun AdminSeatTableRequest.validateSeatPayload(): Map<String, String> {
    val errors = mutableMapOf<String, String>()
    if (depositAmount == null) {
        errors["depositAmount"] = "required"
    } else if (depositAmount < 0) {
        errors["depositAmount"] = "must_be_non_negative"
    }
    if (guestUserId != null && guestUserId <= 0) {
        errors["guestUserId"] = "must_be_positive"
    }
    if (mode == SeatMode.WITH_QR && guestPassQr.isNullOrBlank()) {
        errors["guestPassQr"] = "required"
    }
    if (note != null && note.length > MAX_NOTE_LENGTH) {
        errors["note"] = "too_long"
    }
    errors.putAll(validateAllocations(depositAmount ?: 0L, allocations))
    return errors
}

private fun AdminUpdateDepositRequest.validateDepositUpdate(): Map<String, String> {
    val errors = mutableMapOf<String, String>()
    if (amount < 0) {
        errors["amount"] = "must_be_non_negative"
    }
    if (reason.isBlank()) {
        errors["reason"] = "required"
    }
    errors.putAll(validateAllocations(amount, allocations))
    return errors
}

private fun validateAllocations(
    amountMinor: Long,
    allocations: List<AdminDepositAllocationRequest>,
): Map<String, String> {
    val errors = mutableMapOf<String, String>()
    val normalized =
        allocations.mapIndexed { index, allocation ->
            val code = allocation.categoryCode.trim()
            if (code.isBlank()) {
                errors["allocations[$index].categoryCode"] = "required"
            }
            if (allocation.amount < 0) {
                errors["allocations[$index].amount"] = "must_be_non_negative"
            }
            code.uppercase(Locale.ROOT)
        }
    val duplicates =
        normalized.groupBy { it }
            .filterValues { it.size > 1 }
            .keys
            .filter { it.isNotBlank() }
    if (duplicates.isNotEmpty()) {
        errors["allocations"] = "duplicate_category"
    }
    val total = allocations.sumOf { it.amount }
    if (amountMinor == 0L && total != 0L) {
        errors["allocations"] = "total_mismatch"
    } else if (amountMinor > 0 && total != amountMinor) {
        errors["allocations"] = "total_mismatch"
    }
    return errors
}

private fun List<AdminDepositAllocationRequest>.toAllocationInputs(): List<AllocationInput> =
    map { AllocationInput(categoryCode = it.categoryCode.trim(), amountMinor = it.amount) }

private suspend fun markHasTableIfPossible(
    visitRepository: VisitRepository,
    nightOverrideRepository: NightOverrideRepository,
    settingsRepository: com.example.bot.data.gamification.GamificationSettingsRepository,
    clubId: Long,
    nightStartUtc: Instant,
    eventId: Long?,
    guestUserId: Long,
    actorId: Long,
    actorRole: Role?,
    now: Instant,
    logger: org.slf4j.Logger,
) {
    val effectiveEarlyCutoff =
        runCatching {
            nightOverrideRepository.getOverride(clubId, nightStartUtc)?.earlyCutoffAt
                ?: settingsRepository.getByClubId(clubId)?.earlyWindowMinutes
                    ?.let { minutes -> nightStartUtc.plus(Duration.ofMinutes(minutes.toLong())) }
        }.getOrNull()

    val input =
        VisitCheckInInput(
            clubId = clubId,
            nightStartUtc = nightStartUtc,
            eventId = eventId,
            userId = guestUserId,
            actorUserId = actorId,
            actorRole = actorRole,
            entryType = "TABLE_DEPOSIT",
            firstCheckinAt = now,
            effectiveEarlyCutoffAt = effectiveEarlyCutoff,
        )
    runCatching { visitRepository.tryCheckIn(input) }
        .onFailure { ex ->
            logger.warn("admin.tables.visit_create_failed clubId={} nightStartUtc={}", clubId, nightStartUtc, ex)
            return
        }
    runCatching { visitRepository.markHasTable(clubId, nightStartUtc, guestUserId, hasTable = true) }
        .onFailure { ex ->
            logger.warn("admin.tables.mark_has_table_failed clubId={} nightStartUtc={}", clubId, nightStartUtc, ex)
        }
}

private suspend fun ApplicationCall.requireClubIdPath(): Long? {
    val clubId = parameters["clubId"]?.toLongOrNull()
    if (clubId == null || clubId <= 0) {
        respondValidationErrors(mapOf("clubId" to "must_be_positive"))
        return null
    }
    return clubId
}

private suspend fun ApplicationCall.requireNightStartUtc(): Instant? {
    val raw = parameters["nightStartUtc"]
    val instant = raw?.let { runCatching { Instant.parse(it) }.getOrNull() }
    if (instant == null) {
        respondValidationErrors(mapOf("nightStartUtc" to "invalid_format"))
        return null
    }
    return instant
}

private suspend fun ApplicationCall.requireTableId(): Long? {
    val id = parameters["tableId"]?.toLongOrNull()
    if (id == null || id <= 0) {
        respondValidationErrors(mapOf("tableId" to "must_be_positive"))
        return null
    }
    return id
}

private suspend fun ApplicationCall.requireDepositId(): Long? {
    val id = parameters["depositId"]?.toLongOrNull()
    if (id == null || id <= 0) {
        respondValidationErrors(mapOf("depositId" to "must_be_positive"))
        return null
    }
    return id
}

private suspend fun ApplicationCall.respondValidationErrors(details: Map<String, String>) {
    ensureMiniAppNoStoreHeaders()
    respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error, details = details)
}

private suspend fun ApplicationCall.respondForbidden() {
    ensureMiniAppNoStoreHeaders()
    respondError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
}
