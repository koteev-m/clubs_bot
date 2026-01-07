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
import com.example.bot.data.club.GuestListEntryDbRepository
import com.example.bot.data.security.Role
import com.example.bot.http.ErrorCodes
import com.example.bot.http.respondError
import com.example.bot.plugins.miniAppBotTokenProvider
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.security.rbac.RbacContext
import com.example.bot.security.rbac.authorize
import com.example.bot.security.rbac.rbacContext
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.time.Clock
import java.time.Instant
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
    clock: Clock = Clock.systemUTC(),
    botTokenProvider: () -> String = miniAppBotTokenProvider(),
) {
    routing {
        route("/api/promoter") {
            withMiniAppAuth { botTokenProvider() }
            authorize(
                Role.PROMOTER,
                Role.MANAGER,
                Role.CLUB_ADMIN,
                Role.HEAD_MANAGER,
                Role.GLOBAL_ADMIN,
                Role.OWNER,
            ) {
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

                    val payload = runCatching { call.receive<BulkEntriesRequest>() }.getOrNull()
                        ?: return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)

                    val list = loadAccessibleList(call, guestListRepository, listId) ?: return@post

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

                    val payload = runCatching { call.receive<AddEntryRequest>() }.getOrNull()
                        ?: return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)

                    val list = loadAccessibleList(call, guestListRepository, listId) ?: return@post

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

                    val list = loadAccessibleList(call, guestListRepository, listId) ?: return@post
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
    setOf(Role.OWNER, Role.GLOBAL_ADMIN, Role.HEAD_MANAGER)

private suspend fun loadAccessibleList(
    call: ApplicationCall,
    guestListRepository: GuestListRepository,
    listId: Long,
): GuestList? {
    val list =
        guestListRepository.getList(listId)
            ?: run {
                call.respondError(HttpStatusCode.NotFound, ErrorCodes.guest_list_not_found)
                return null
            }
    val context = call.rbacContext()
    if (!canAccessList(context, list)) {
        call.respondError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
        return null
    }
    return list
}

private fun canAccessList(
    context: RbacContext,
    list: GuestList,
): Boolean {
    val isGlobal = context.roles.any { it in GLOBAL_ROLES }
    val isClubScoped =
        (Role.CLUB_ADMIN in context.roles || Role.MANAGER in context.roles) &&
            (list.clubId in context.clubIds)
    val isPromoterOwnList =
        (Role.PROMOTER in context.roles) &&
            list.ownerType == GuestListOwnerType.PROMOTER &&
            list.ownerUserId == context.user.id

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
