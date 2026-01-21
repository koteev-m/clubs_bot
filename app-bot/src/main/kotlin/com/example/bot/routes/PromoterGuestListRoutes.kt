package com.example.bot.routes

import com.example.bot.club.GuestList
import com.example.bot.club.GuestListEntryInfo
import com.example.bot.club.GuestListInfo
import com.example.bot.club.GuestListOwnerType
import com.example.bot.club.GuestListRepository
import com.example.bot.club.GuestListService
import com.example.bot.club.GuestListServiceError
import com.example.bot.club.GuestListServiceResult
import com.example.bot.club.GuestListStats
import com.example.bot.club.InvitationChannel
import com.example.bot.club.InvitationCreateResult
import com.example.bot.club.InvitationService
import com.example.bot.club.InvitationServiceResult
import com.example.bot.admin.AdminHallsRepository
import com.example.bot.booking.a3.BookingError
import com.example.bot.booking.a3.BookingState
import com.example.bot.booking.a3.ConfirmResult
import com.example.bot.booking.a3.HoldResult
import com.example.bot.clubs.Club
import com.example.bot.clubs.ClubsRepository
import com.example.bot.clubs.Event
import com.example.bot.clubs.EventsRepository
import com.example.bot.booking.a3.hashRequestCanonical
import com.example.bot.data.club.GuestListDbRepository
import com.example.bot.data.club.GuestListEntryDbRepository
import com.example.bot.data.club.GuestListEntryRecord
import com.example.bot.data.club.GuestListRecord
import com.example.bot.data.security.Role
import com.example.bot.http.ErrorCodes
import com.example.bot.http.ensureMiniAppNoStoreHeaders
import com.example.bot.http.respondError
import com.example.bot.layout.AdminTablesRepository
import com.example.bot.layout.toRangeString
import com.example.bot.plugins.miniAppBotTokenProvider
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.data.promoter.PromoterBookingAssignmentsRepository
import com.example.bot.security.rbac.RbacContext
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
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.serialization.Serializable

@Serializable
private data class CreateGuestListRequest(
    val clubId: Long,
    val eventId: Long,
    val arrivalWindowStart: String? = null,
    val arrivalWindowEnd: String? = null,
    val limit: Int,
    val name: String? = null,
)

@Serializable
private data class CreateGuestListResponse(
    val guestList: GuestListDto,
    val stats: GuestListStatsDto,
)

@Serializable
private data class BulkEntriesRequest(
    val rawText: String,
)

@Serializable
private data class BulkEntriesResponse(
    val addedCount: Int,
    val skippedDuplicatesCount: Int,
    val totalCount: Int,
    val stats: GuestListStatsDto,
)

@Serializable
private data class AddEntryRequest(
    val displayName: String,
)

@Serializable
private data class AddEntryResponse(
    val entry: GuestListEntryDto,
    val stats: GuestListStatsDto,
)

@Serializable
private data class CreateInvitationRequest(
    val channel: InvitationChannelDto,
)

@Serializable
private data class CreateInvitationResponse(
    val token: String,
    val deepLinkUrl: String,
    val qrPayload: String,
    val expiresAt: String,
)

@Serializable
private data class PromoterMeResponse(
    val userId: Long,
    val roles: List<String>,
    val clubIds: List<Long>,
)

@Serializable
private data class PromoterClubDto(
    val id: Long,
    val name: String,
    val city: String,
)

@Serializable
private data class PromoterEventDto(
    val id: Long,
    val clubId: Long,
    val startUtc: String,
    val endUtc: String,
    val title: String?,
    val isSpecial: Boolean,
)

@Serializable
private data class PromoterHallDto(
    val id: Long,
    val clubId: Long,
    val name: String,
    val isActive: Boolean,
)

@Serializable
private data class PromoterTableDto(
    val id: Long,
    val hallId: Long,
    val clubId: Long,
    val label: String,
    val minDeposit: Long,
    val capacity: Int,
    val zone: String?,
    val zoneName: String?,
    val arrivalWindow: String?,
    val mysteryEligible: Boolean,
    val tableNumber: Int,
    val x: Double,
    val y: Double,
)

@Serializable
private data class PromoterGuestListsResponse(
    val guestLists: List<GuestListDto>,
)

@Serializable
private data class PromoterGuestListDetailsResponse(
    val guestList: GuestListDto,
    val entries: List<GuestListEntryDto>,
    val stats: GuestListStatsDto,
)

@Serializable
private data class PromoterInvitationEntryDto(
    val entry: GuestListEntryDto,
    val invitationUrl: String,
    val qrPayload: String,
    val expiresAt: String,
)

@Serializable
private data class PromoterInvitationsResponse(
    val entries: List<PromoterInvitationEntryDto>,
)

@Serializable
private data class PromoterBookingAssignRequest(
    val guestListEntryId: Long,
    val hallId: Long,
    val tableId: Long,
    val eventId: Long,
)

@Serializable
private data class PromoterBookingAssignResponse(
    val bookingId: Long,
    val tableId: Long,
    val eventId: Long,
)

@Serializable
private data class PromoterStatsItemDto(
    val clubId: Long,
    val eventId: Long,
    val totalAdded: Int,
    val totalArrived: Int,
    val conversion: Double,
)

@Serializable
private data class PromoterStatsResponse(
    val totalAdded: Int,
    val totalArrived: Int,
    val conversion: Double,
    val items: List<PromoterStatsItemDto>,
)

@Serializable
private data class GuestListDto(
    val id: Long,
    val clubId: Long,
    val eventId: Long,
    val promoterId: Long?,
    val ownerType: String,
    val ownerUserId: Long,
    val name: String,
    val limit: Int,
    val arrivalWindowStart: String?,
    val arrivalWindowEnd: String?,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
private data class GuestListEntryDto(
    val id: Long,
    val guestListId: Long,
    val displayName: String,
    val telegramUserId: Long?,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
private data class GuestListStatsDto(
    val added: Int,
    val invited: Int,
    val confirmed: Int,
    val declined: Int,
    val arrived: Int,
    val noShow: Int,
)

@Serializable
private enum class InvitationChannelDto {
    TELEGRAM,
    EXTERNAL,
}

fun Application.promoterGuestListRoutes(
    guestListRepository: GuestListRepository,
    guestListService: GuestListService,
    guestListEntryRepository: GuestListEntryDbRepository,
    invitationService: InvitationService,
    guestListDbRepository: GuestListDbRepository,
    clubsRepository: ClubsRepository,
    eventsRepository: EventsRepository,
    adminHallsRepository: AdminHallsRepository,
    adminTablesRepository: AdminTablesRepository,
    bookingState: BookingState,
    promoterAssignments: PromoterBookingAssignmentsRepository,
    clock: Clock = Clock.systemUTC(),
    botTokenProvider: () -> String = miniAppBotTokenProvider(),
) {
    routing {
        route("/api/promoter") {
            intercept(ApplicationCallPipeline.Setup) { call.ensureMiniAppNoStoreHeaders() }
            withMiniAppAuth(allowInitDataFromBody = false) { botTokenProvider() }
            authorize(
                Role.PROMOTER,
                Role.CLUB_ADMIN,
                Role.GLOBAL_ADMIN,
                Role.OWNER,
            ) {
                get("/me") {
                    val context = call.rbacContext()
                    call.respond(
                        PromoterMeResponse(
                            userId = context.user.id,
                            roles = context.roles.map { it.name }.sorted(),
                            clubIds = context.clubIds.toList().sorted(),
                        ),
                    )
                }

                get("/clubs") {
                    val context = call.rbacContext()
                    val clubs =
                        if (context.roles.any { it in GLOBAL_ROLES }) {
                            clubsRepository.list(city = null, query = null, tag = null, genre = null, offset = 0, limit = 200)
                        } else {
                            context.clubIds.mapNotNull { clubsRepository.getById(it) }
                        }
                    call.respond(clubs.map { it.toDto() })
                }

                get("/club-events") {
                    val clubId = call.request.queryParameters["clubId"]?.toLongOrNull()
                        ?: return@get call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                    val date = call.request.queryParameters["date"]?.let { parseDate(it) }
                        ?: return@get call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                    val context = call.rbacContext()
                    if (!context.canAccessClub(clubId)) {
                        return@get call.respondError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
                    }
                    val from = date.atStartOfDay().toInstant(ZoneOffset.UTC)
                    val to = date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
                    val events = eventsRepository.list(clubId = clubId, city = null, from = from, to = to, offset = 0, limit = 200)
                    call.respond(events.map { it.toDto() })
                }

                get("/clubs/{clubId}/halls") {
                    val clubId = call.parameters["clubId"]?.toLongOrNull()
                        ?: return@get call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                    val context = call.rbacContext()
                    if (!context.canAccessClub(clubId)) {
                        return@get call.respondError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
                    }
                    val halls = adminHallsRepository.listForClub(clubId)
                    call.respond(halls.map { it.toDto() })
                }

                get("/halls/{hallId}/tables") {
                    val hallId = call.parameters["hallId"]?.toLongOrNull()
                        ?: return@get call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                    val hall = adminHallsRepository.getById(hallId)
                        ?: return@get call.respondError(HttpStatusCode.NotFound, ErrorCodes.hall_not_found)
                    val context = call.rbacContext()
                    if (!context.canAccessClub(hall.clubId)) {
                        return@get call.respondError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
                    }
                    val zones = adminTablesRepository.listZonesForHall(hallId)
                    val tables = adminTablesRepository.listForHall(hallId)
                    val sorted = tables.sortedWith(compareBy<com.example.bot.layout.Table> { it.zoneId }.thenBy { it.id })
                    call.respond(sorted.map { it.toPromoterResponse(hall.clubId, hallId, zones) })
                }

                get("/guest-lists") {
                    val clubId = call.request.queryParameters["clubId"]?.toLongOrNull()
                    val from = call.request.queryParameters["from"]?.let { parseDate(it) }
                    val to = call.request.queryParameters["to"]?.let { parseDate(it) }
                    if (call.hasInvalidDateRange(from, to)) {
                        return@get call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                    }
                    val context = call.rbacContext()
                    if (clubId != null && !context.canAccessClub(clubId)) {
                        return@get call.respondError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
                    }
                    val lists =
                        when {
                            Role.PROMOTER in context.roles ->
                                if (context.clubIds.isEmpty()) {
                                    emptyList()
                                } else {
                                    guestListDbRepository
                                        .listByPromoter(context.user.id, clubId = clubId)
                                        .filter { it.clubId in context.clubIds }
                                }
                            clubId != null ->
                                guestListDbRepository.listByClub(clubId)
                            else -> emptyList()
                        }
                    val filtered = lists.filterByDateRange(from, to)
                    call.respond(PromoterGuestListsResponse(filtered.map { it.toDto() }))
                }

                get("/guest-lists/{id}") {
                    val listId = call.parameters["id"]?.toLongOrNull()
                        ?: return@get call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                    val list = call.requireAccessibleGuestList(guestListRepository, listId) ?: return@get
                    val entries = guestListEntryRepository.listByGuestList(listId)
                    val stats = guestListService.getStats(listId, clock.instant())
                    when (stats) {
                        is GuestListServiceResult.Failure -> {
                            val (status, code) = stats.error.toHttpError()
                            call.respondError(status, code)
                        }
                        is GuestListServiceResult.Success -> {
                            call.respond(
                                PromoterGuestListDetailsResponse(
                                    guestList = list.toDto(),
                                    entries = entries.map { it.toDto() },
                                    stats = stats.value.toDto(),
                                ),
                            )
                        }
                    }
                }

                get("/guest-lists/{id}/invitations") {
                    val listId = call.parameters["id"]?.toLongOrNull()
                        ?: return@get call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                    call.requireAccessibleGuestList(guestListRepository, listId) ?: return@get
                    val entries = guestListEntryRepository.listByGuestList(listId)
                    val context = call.rbacContext()
                    val invitations = mutableListOf<PromoterInvitationEntryDto>()
                    for (entry in entries) {
                        when (
                            val created =
                                invitationService.createInvitation(
                                    entryId = entry.id,
                                    channel = InvitationChannel.EXTERNAL,
                                    createdBy = context.user.id,
                                )
                        ) {
                            is InvitationServiceResult.Failure -> {
                                val (status, code) = created.error.toHttpError()
                                return@get call.respondError(status, code)
                            }
                            is InvitationServiceResult.Success -> {
                                invitations += entry.toInvitationDto(created.value)
                            }
                        }
                    }
                    call.respond(PromoterInvitationsResponse(invitations))
                }

                post("/bookings/assign") {
                    val payload = runCatching { call.receive<PromoterBookingAssignRequest>() }.getOrNull()
                        ?: return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)
                    if (payload.guestListEntryId <= 0 || payload.tableId <= 0 || payload.eventId <= 0 || payload.hallId <= 0) {
                        return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                    }
                    val entry = guestListEntryRepository.findById(payload.guestListEntryId)
                        ?: return@post call.respondError(HttpStatusCode.NotFound, ErrorCodes.entry_not_found)
                    val list = guestListRepository.getList(entry.guestListId)
                        ?: return@post call.respondError(HttpStatusCode.NotFound, ErrorCodes.guest_list_not_found)
                    val context = call.rbacContext()
                    if (!canAccessList(context, list)) {
                        return@post call.respondError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
                    }
                    if (list.eventId != payload.eventId) {
                        return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                    }
                    val hall = adminHallsRepository.getById(payload.hallId)
                        ?: return@post call.respondError(HttpStatusCode.NotFound, ErrorCodes.hall_not_found)
                    if (hall.clubId != list.clubId) {
                        return@post call.respondError(HttpStatusCode.Forbidden, ErrorCodes.club_scope_mismatch)
                    }
                    if (!context.canAccessClub(hall.clubId)) {
                        return@post call.respondError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
                    }
                    val table = adminTablesRepository.findByIdForHall(payload.hallId, payload.tableId)
                        ?: return@post call.respondError(HttpStatusCode.NotFound, ErrorCodes.table_not_found)
                    val existingBooking = promoterAssignments.findBookingIdForEntry(entry.id)
                    if (existingBooking != null) {
                        return@post call.respondError(
                            HttpStatusCode.Conflict,
                            ErrorCodes.invalid_state,
                            message = "Этому гостю уже назначен стол",
                        )
                    }
                    val idem = "promoter-assign:${entry.id}"
                    val holdHash = hashRequestCanonical(mapOf("tableId" to payload.tableId, "eventId" to payload.eventId, "guests" to 1))
                    when (
                        val hold =
                            bookingState.hold(
                                userId = context.user.id,
                                clubId = list.clubId,
                                tableId = table.id,
                                eventId = payload.eventId,
                                guestCount = 1,
                                idempotencyKey = idem,
                                requestHash = holdHash,
                                promoterId = context.user.id,
                            )
                    ) {
                        is HoldResult.Error -> {
                            val (status, code) = hold.code.toHttp()
                            return@post call.respondError(status, code)
                        }
                        is HoldResult.Success -> {
                            val confirmHash = hashRequestCanonical(mapOf("bookingId" to hold.booking.id))
                            when (
                                val confirm =
                                    bookingState.confirm(
                                        userId = context.user.id,
                                        clubId = list.clubId,
                                        bookingId = hold.booking.id,
                                        idempotencyKey = "promoter-confirm:${hold.booking.id}",
                                        requestHash = confirmHash,
                                    )
                            ) {
                                is ConfirmResult.Error -> {
                                    val (status, code) = confirm.code.toHttp()
                                    return@post call.respondError(status, code)
                                }
                                is ConfirmResult.Success -> {
                                    val recorded = promoterAssignments.assignIfAbsent(entry.id, confirm.booking.id)
                                    if (!recorded) {
                                        return@post call.respondError(
                                            HttpStatusCode.Conflict,
                                            ErrorCodes.invalid_state,
                                            message = "Этому гостю уже назначен стол",
                                        )
                                    }
                                    call.respond(
                                        PromoterBookingAssignResponse(
                                            bookingId = confirm.booking.id,
                                            tableId = table.id,
                                            eventId = payload.eventId,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }

                get("/me/stats") {
                    val context = call.rbacContext()
                    val lists =
                        if (Role.PROMOTER in context.roles && context.clubIds.isNotEmpty()) {
                            guestListDbRepository
                                .listByPromoter(context.user.id)
                                .filter { it.clubId in context.clubIds }
                        } else if (context.roles.any { it in GLOBAL_ROLES }) {
                            val clubIds =
                                if (context.clubIds.isNotEmpty()) {
                                    context.clubIds
                                } else {
                                    clubsRepository.list(city = null, query = null, tag = null, genre = null, offset = 0, limit = 200)
                                        .map { it.id }
                                        .toSet()
                                }
                            guestListDbRepository.listByClubs(clubIds)
                        } else {
                            emptyList()
                        }
                    val grouped = mutableMapOf<Pair<Long, Long>, MutableList<GuestListEntryRecord>>()
                    var totalAdded = 0
                    var totalArrived = 0
                    for (list in lists) {
                        val entries = guestListEntryRepository.listByGuestList(list.id)
                        totalAdded += entries.size
                        totalArrived += entries.count { it.isArrived() }
                        grouped.getOrPut(list.clubId to list.eventId) { mutableListOf() }.addAll(entries)
                    }
                    val items =
                        grouped.map { (key, entries) ->
                            val arrived = entries.count { it.isArrived() }
                            val added = entries.size
                            PromoterStatsItemDto(
                                clubId = key.first,
                                eventId = key.second,
                                totalAdded = added,
                                totalArrived = arrived,
                                conversion = if (added == 0) 0.0 else arrived.toDouble() / added.toDouble(),
                            )
                        }
                    call.respond(
                        PromoterStatsResponse(
                            totalAdded = totalAdded,
                            totalArrived = totalArrived,
                            conversion = if (totalAdded == 0) 0.0 else totalArrived.toDouble() / totalAdded.toDouble(),
                            items = items.sortedWith(compareBy({ it.clubId }, { it.eventId })),
                        ),
                    )
                }

                post("/guest-lists") {
                    val payload = runCatching { call.receive<CreateGuestListRequest>() }.getOrNull()
                        ?: return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)

                    if (payload.clubId <= 0 || payload.eventId <= 0) {
                        return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                    }

                    val arrivalWindowStart = parseInstant(payload.arrivalWindowStart)
                    val arrivalWindowEnd = parseInstant(payload.arrivalWindowEnd)
                    if (
                        payload.arrivalWindowStart != null && arrivalWindowStart == null ||
                        payload.arrivalWindowEnd != null && arrivalWindowEnd == null
                    ) {
                        return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                    }

                    val context = call.rbacContext()
                    if (!context.canAccessClub(payload.clubId)) {
                        return@post call.respondError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
                    }
                    val created =
                        guestListService.createGuestList(
                            promoterId = context.user.id,
                            clubId = payload.clubId,
                            eventId = payload.eventId,
                            ownerType = GuestListOwnerType.PROMOTER,
                            ownerUserId = context.user.id,
                            arrivalWindowStart = arrivalWindowStart,
                            arrivalWindowEnd = arrivalWindowEnd,
                            limit = payload.limit,
                            title = payload.name,
                        )

                    when (created) {
                        is GuestListServiceResult.Failure -> {
                            val (status, code) = created.error.toHttpError()
                            call.respondError(status, code)
                        }
                        is GuestListServiceResult.Success -> {
                            val stats = guestListService.getStats(created.value.id, clock.instant())
                            when (stats) {
                                is GuestListServiceResult.Failure -> {
                                    val (status, code) = stats.error.toHttpError()
                                    call.respondError(status, code)
                                }
                                is GuestListServiceResult.Success -> {
                                    call.respond(
                                        HttpStatusCode.Created,
                                        CreateGuestListResponse(
                                            guestList = created.value.toDto(),
                                            stats = stats.value.toDto(),
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }

                post("/guest-lists/{id}/entries/bulk") {
                    val listId = call.parameters["id"]?.toLongOrNull()
                        ?: return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)

                    call.requireAccessibleGuestList(guestListRepository, listId) ?: return@post

                    val payload = runCatching { call.receive<BulkEntriesRequest>() }.getOrNull()
                        ?: return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)

                    when (val result = guestListService.addEntriesBulk(listId, payload.rawText)) {
                        is GuestListServiceResult.Failure -> {
                            val (status, code) = result.error.toHttpError()
                            call.respondError(status, code)
                        }
                        is GuestListServiceResult.Success -> {
                            val value = result.value
                            call.respond(
                                BulkEntriesResponse(
                                    addedCount = value.addedCount,
                                    skippedDuplicatesCount = value.skippedDuplicatesCount,
                                    totalCount = value.totalCount,
                                    stats = value.stats.toDto(),
                                ),
                            )
                        }
                    }
                }

                post("/guest-lists/{id}/entries") {
                    val listId = call.parameters["id"]?.toLongOrNull()
                        ?: return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)

                    call.requireAccessibleGuestList(guestListRepository, listId) ?: return@post

                    val payload = runCatching { call.receive<AddEntryRequest>() }.getOrNull()
                        ?: return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)

                    val added = guestListService.addEntrySingle(listId, payload.displayName)
                    when (added) {
                        is GuestListServiceResult.Failure -> {
                            val (status, code) = added.error.toHttpError()
                            call.respondError(status, code)
                        }
                        is GuestListServiceResult.Success -> {
                            val stats = guestListService.getStats(listId, clock.instant())
                            when (stats) {
                                is GuestListServiceResult.Failure -> {
                                    val (status, code) = stats.error.toHttpError()
                                    call.respondError(status, code)
                                }
                                is GuestListServiceResult.Success -> {
                                    call.respond(
                                        AddEntryResponse(
                                            entry = added.value.toDto(),
                                            stats = stats.value.toDto(),
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }

                post("/guest-lists/{id}/entries/{entryId}/invitation") {
                    val listId =
                        call.parameters["id"]?.toLongOrNull()
                            ?: return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                    val entryId = call.parameters["entryId"]?.toLongOrNull()
                        ?: return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)

                    call.requireAccessibleGuestList(guestListRepository, listId) ?: return@post
                    val context = call.rbacContext()

                    val entry =
                        guestListEntryRepository.findById(entryId)
                            ?: return@post call.respondError(HttpStatusCode.NotFound, ErrorCodes.entry_not_found)
                    if (entry.guestListId != listId) {
                        return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.entry_list_mismatch)
                    }

                    val payload = runCatching { call.receive<CreateInvitationRequest>() }.getOrNull()
                        ?: return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)

                    val channel = payload.channel.toDomain()
                    when (
                        val result = invitationService.createInvitation(
                            entryId = entryId,
                            channel = channel,
                            createdBy = context.user.id,
                        )
                    ) {
                        is InvitationServiceResult.Failure -> {
                            val (status, code) = result.error.toHttpError()
                            call.respondError(status, code)
                        }
                        is InvitationServiceResult.Success -> {
                            call.respond(HttpStatusCode.Created, result.value.toResponse())
                        }
                    }
                }
            }
        }
    }
}

private val GLOBAL_ROLES: Set<Role> =
    setOf(Role.OWNER, Role.GLOBAL_ADMIN)

private suspend fun ApplicationCall.requireAccessibleGuestList(
    repo: GuestListRepository,
    listId: Long,
): GuestList? {
    val list =
        repo.getList(listId)
            ?: run {
                respondError(HttpStatusCode.NotFound, ErrorCodes.guest_list_not_found)
                return null
            }
    val context = rbacContext()
    if (!canAccessList(context, list)) {
        respondError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
        return null
    }
    return list
}

private fun canAccessList(
    context: RbacContext,
    list: GuestList,
): Boolean {
    val isGlobal = context.roles.any { it in GLOBAL_ROLES }
    val clubScopeOk = list.clubId in context.clubIds
    val isClubScoped =
        (Role.CLUB_ADMIN in context.roles) && clubScopeOk
    val isPromoterOwnList =
        (Role.PROMOTER in context.roles) &&
            list.ownerType == GuestListOwnerType.PROMOTER &&
            list.ownerUserId == context.user.id &&
            clubScopeOk

    return isGlobal || isClubScoped || isPromoterOwnList
}

private fun parseInstant(value: String?): Instant? = value?.let { runCatching { Instant.parse(it) }.getOrNull() }

private fun GuestListServiceError.toHttpError(): Pair<HttpStatusCode, String> =
    when (this) {
        GuestListServiceError.GuestListNotFound -> HttpStatusCode.NotFound to ErrorCodes.guest_list_not_found
        GuestListServiceError.GuestListNotActive -> HttpStatusCode.Conflict to ErrorCodes.guest_list_not_active
        GuestListServiceError.GuestListLimitExceeded ->
            HttpStatusCode.Conflict to ErrorCodes.guest_list_limit_exceeded
        GuestListServiceError.BulkParseTooLarge -> HttpStatusCode.PayloadTooLarge to ErrorCodes.bulk_parse_too_large
        GuestListServiceError.InvalidArrivalWindow,
        GuestListServiceError.InvalidLimit,
        GuestListServiceError.InvalidDisplayName -> HttpStatusCode.BadRequest to ErrorCodes.validation_error
    }

private fun InvitationChannelDto.toDomain(): InvitationChannel =
    when (this) {
        InvitationChannelDto.TELEGRAM -> InvitationChannel.TELEGRAM
        InvitationChannelDto.EXTERNAL -> InvitationChannel.EXTERNAL
    }

private fun GuestListInfo.toDto(): GuestListDto =
    GuestListDto(
        id = id,
        clubId = clubId,
        eventId = eventId,
        promoterId = promoterId,
        ownerType = ownerType.name,
        ownerUserId = ownerUserId,
        name = title,
        limit = capacity,
        arrivalWindowStart = arrivalWindowStart?.toString(),
        arrivalWindowEnd = arrivalWindowEnd?.toString(),
        status = status.name,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )

private fun GuestList.toDto(): GuestListDto =
    GuestListDto(
        id = id,
        clubId = clubId,
        eventId = eventId,
        promoterId = null,
        ownerType = ownerType.name,
        ownerUserId = ownerUserId,
        name = title,
        limit = capacity,
        arrivalWindowStart = arrivalWindowStart?.toString(),
        arrivalWindowEnd = arrivalWindowEnd?.toString(),
        status = status.name,
        createdAt = createdAt.toString(),
        updatedAt = createdAt.toString(),
    )

private fun GuestListEntryInfo.toDto(): GuestListEntryDto =
    GuestListEntryDto(
        id = id,
        guestListId = guestListId,
        displayName = displayName,
        telegramUserId = telegramUserId,
        status = status.name,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )

private fun GuestListStats.toDto(): GuestListStatsDto =
    GuestListStatsDto(
        added = added,
        invited = invited,
        confirmed = confirmed,
        declined = declined,
        arrived = arrived,
        noShow = noShow,
    )

private fun InvitationCreateResult.toResponse(): CreateInvitationResponse =
    CreateInvitationResponse(
        token = token,
        deepLinkUrl = deepLinkUrl,
        qrPayload = qrPayload,
        expiresAt = expiresAt.toString(),
    )

private fun GuestListRecord.toDto(): GuestListDto =
    GuestListDto(
        id = id,
        clubId = clubId,
        eventId = eventId,
        promoterId = promoterId,
        ownerType = ownerType.name,
        ownerUserId = ownerUserId,
        name = title,
        limit = capacity,
        arrivalWindowStart = arrivalWindowStart?.toString(),
        arrivalWindowEnd = arrivalWindowEnd?.toString(),
        status = status.name,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )

private fun GuestListEntryRecord.toDto(): GuestListEntryDto =
    GuestListEntryDto(
        id = id,
        guestListId = guestListId,
        displayName = displayName,
        telegramUserId = telegramUserId,
        status = status.name,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )

private fun GuestListEntryRecord.toInvitationDto(invitation: InvitationCreateResult): PromoterInvitationEntryDto =
    PromoterInvitationEntryDto(
        entry = toDto(),
        invitationUrl = invitation.deepLinkUrl,
        qrPayload = invitation.qrPayload,
        expiresAt = invitation.expiresAt.toString(),
    )

private fun Club.toDto(): PromoterClubDto =
    PromoterClubDto(
        id = id,
        name = name,
        city = city,
    )

private fun Event.toDto(): PromoterEventDto =
    PromoterEventDto(
        id = id,
        clubId = clubId,
        startUtc = startUtc.toString(),
        endUtc = endUtc.toString(),
        title = title,
        isSpecial = isSpecial,
    )

private fun com.example.bot.admin.AdminHall.toDto(): PromoterHallDto =
    PromoterHallDto(
        id = id,
        clubId = clubId,
        name = name,
        isActive = isActive,
    )

private fun com.example.bot.layout.Table.toPromoterResponse(
    clubId: Long,
    hallId: Long,
    zones: List<com.example.bot.layout.Zone>,
): PromoterTableDto {
    val zoneName = zones.firstOrNull { it.id == zoneId }?.name
    return PromoterTableDto(
        id = id,
        hallId = hallId,
        clubId = clubId,
        label = label,
        minDeposit = minDeposit,
        capacity = capacity,
        zone = zone,
        zoneName = zoneName,
        arrivalWindow = arrivalWindow?.toRangeString(),
        mysteryEligible = mysteryEligible,
        tableNumber = tableNumber ?: 0,
        x = x,
        y = y,
    )
}

private fun parseDate(value: String): LocalDate? =
    runCatching { LocalDate.parse(value) }.getOrNull()

private fun ApplicationCall.hasInvalidDateRange(from: LocalDate?, to: LocalDate?): Boolean {
    if (from == null && to == null) return false
    if (from == null || to == null) return true
    return to.isBefore(from)
}

private fun List<GuestListRecord>.filterByDateRange(
    from: LocalDate?,
    to: LocalDate?,
): List<GuestListRecord> {
    if (from == null && to == null) return this
    return filter { list ->
        val date = list.arrivalWindowStart?.atZone(ZoneOffset.UTC)?.toLocalDate()
            ?: list.createdAt.atZone(ZoneOffset.UTC).toLocalDate()
        val afterFrom = from == null || !date.isBefore(from)
        val beforeTo = to == null || !date.isAfter(to)
        afterFrom && beforeTo
    }
}

private fun RbacContext.canAccessClub(clubId: Long): Boolean {
    if (roles.any { it in GLOBAL_ROLES }) return true
    return clubId in clubIds
}

private fun GuestListEntryRecord.isArrived(): Boolean =
    status == com.example.bot.club.GuestListEntryStatus.ARRIVED ||
        status == com.example.bot.club.GuestListEntryStatus.LATE ||
        status == com.example.bot.club.GuestListEntryStatus.CHECKED_IN

private suspend fun GuestListDbRepository.listByClubs(clubIds: Set<Long>): List<GuestListRecord> =
    if (clubIds.isEmpty()) {
        emptyList()
    } else {
        buildList {
            for (clubId in clubIds) {
                addAll(listByClub(clubId))
            }
        }
    }

private fun BookingError.toHttp(): Pair<HttpStatusCode, String> =
    when (this) {
        BookingError.TABLE_NOT_AVAILABLE -> HttpStatusCode.Conflict to ErrorCodes.table_not_available
        BookingError.VALIDATION_ERROR -> HttpStatusCode.BadRequest to ErrorCodes.validation_error
        BookingError.IDEMPOTENCY_CONFLICT -> HttpStatusCode.Conflict to ErrorCodes.idempotency_conflict
        BookingError.NOT_FOUND -> HttpStatusCode.NotFound to ErrorCodes.not_found
        BookingError.HOLD_EXPIRED -> HttpStatusCode.Gone to ErrorCodes.hold_expired
        BookingError.INVALID_STATE -> HttpStatusCode.Conflict to ErrorCodes.invalid_state
        BookingError.LATE_PLUS_ONE_EXPIRED -> HttpStatusCode.Gone to ErrorCodes.late_plus_one_expired
        BookingError.PLUS_ONE_ALREADY_USED -> HttpStatusCode.Conflict to ErrorCodes.plus_one_already_used
        BookingError.FORBIDDEN -> HttpStatusCode.Forbidden to ErrorCodes.forbidden
        BookingError.CAPACITY_EXCEEDED -> HttpStatusCode.Conflict to ErrorCodes.capacity_exceeded
        BookingError.CLUB_SCOPE_MISMATCH -> HttpStatusCode.Forbidden to ErrorCodes.club_scope_mismatch
        BookingError.PROMOTER_QUOTA_EXHAUSTED -> HttpStatusCode.Conflict to ErrorCodes.promoter_quota_exhausted
    }
