package com.example.bot.routes

import com.example.bot.clubs.ClubsRepository
import com.example.bot.data.security.PermissionCode
import com.example.bot.data.security.PermissionCodes
import com.example.bot.data.security.Role
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRolePermissionRepository
import com.example.bot.http.ErrorCodes
import com.example.bot.http.respondError
import com.example.bot.http.ensureMiniAppNoStoreHeaders
import com.example.bot.plugins.MiniAppUserKey
import com.example.bot.plugins.miniAppBotTokenProvider
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.support.GuestTicketThread
import com.example.bot.support.StaffTicketThread
import com.example.bot.support.StaffSupportReadService
import com.example.bot.support.SupportService
import com.example.bot.support.SupportServiceError
import com.example.bot.support.SupportServiceResult
import com.example.bot.support.Ticket
import com.example.bot.support.TicketMessage
import com.example.bot.support.TicketStatus
import com.example.bot.support.TicketSummary
import com.example.bot.support.TicketTopic
import com.example.bot.support.buildSupportReplyMessage
import com.example.bot.telegram.SupportCallbacks
import com.example.bot.opschat.NoopOpsNotificationPublisher
import com.example.bot.opschat.OpsDomainNotification
import com.example.bot.opschat.OpsNotificationEvent
import com.example.bot.opschat.OpsNotificationPublisher
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.response.BaseResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.intercept
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import kotlinx.coroutines.launch
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

private val logger = LoggerFactory.getLogger("SupportRoutes")
private val canonicalPositiveTicketId = Regex("[1-9][0-9]*")
private val supportUserIdKey = AttributeKey<Long>("support.user.id")
@Serializable
internal data class CreateTicketRequest(
    val clubId: Long? = null,
    val topic: String? = null,
    val text: String? = null,
    val attachments: String? = null,
)

@Serializable
private data class TicketResponse(
    val id: Long,
    val clubId: Long,
    val topic: String,
    val status: String,
    val updatedAt: String,
)

@Serializable
private data class TicketSummaryResponse(
    val id: Long,
    val clubId: Long,
    val topic: String,
    val status: String,
    val updatedAt: String,
    val lastMessagePreview: String? = null,
    val lastSenderType: String? = null,
)

@Serializable
private data class GuestTicketThreadResponse(
    val ticket: GuestTicketDetailsResponse,
    val messages: List<GuestTicketMessageResponse>,
)

@Serializable
private data class GuestTicketDetailsResponse(
    val id: Long,
    val clubId: Long,
    val topic: String,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
private data class GuestTicketMessageResponse(
    val id: Long,
    val senderType: String,
    val text: String,
    val attachments: String?,
    val createdAt: String,
)

@Serializable
private data class SupportStaffClubResponse(
    val id: Long,
    val name: String,
)

@Serializable
private data class StaffTicketThreadResponse(
    val ticket: StaffTicketDetailsResponse,
    val messages: List<StaffTicketMessageResponse>,
)

@Serializable
private data class StaffTicketDetailsResponse(
    val id: Long,
    val clubId: Long,
    val topic: String,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
private data class StaffTicketMessageResponse(
    val id: Long,
    val senderType: String,
    val text: String,
    val attachments: String?,
    val createdAt: String,
)

@Serializable
private data class AddMessageRequest(
    val text: String? = null,
    val attachments: String? = null,
)

@Serializable
private data class UpdateStatusRequest(
    val status: String? = null,
)

@Serializable
private data class ReplyRequest(
    val text: String? = null,
    val attachments: String? = null,
)

@Serializable
private data class MessageResponse(
    val messageId: Long,
    val ticketId: Long,
    val senderType: String,
    val createdAt: String,
)

@Serializable
private data class SupportReplyResponse(
    val ticketId: Long,
    val clubId: Long,
    val ownerUserId: Long,
    val replyMessageId: Long,
    val replyCreatedAt: String,
    val ticketStatus: String,
)

private val operationalSupportRoles = setOf(Role.MANAGER, Role.CLUB_ADMIN)

fun Application.supportRoutes(
    supportService: SupportService,
    staffSupportReadService: StaffSupportReadService,
    userRepository: UserRepository,
    userRolePermissionRepository: UserRolePermissionRepository,
    clubsRepository: ClubsRepository,
    sendTelegram: suspend (BaseRequest<*, *>) -> BaseResponse,
    clubNameProvider: suspend (clubId: Long) -> String? = { null },
    opsPublisher: OpsNotificationPublisher = NoopOpsNotificationPublisher,
    botTokenProvider: () -> String = miniAppBotTokenProvider(),
) {
    routing {
        route("/api/support") {
            intercept(ApplicationCallPipeline.Setup) { call.ensureMiniAppNoStoreHeaders() }
            withMiniAppAuth(allowInitDataFromBody = false) { botTokenProvider() }

            post("/tickets") {
                val request =
                    receiveCreateTicketRequestOrNull { call.receive<CreateTicketRequest>() } ?: run {
                        logger.warn("support.ticket.create invalid_json")
                        return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)
                    }

                val clubId = request.clubId
                val topic = request.topic?.let { TicketTopic.fromWire(it) }
                val text = normalizeText(request.text)
                if (clubId == null || clubId <= 0 || topic == null || text == null) {
                    logger.warn("support.ticket.create validation_error club_id={} topic={}", clubId, request.topic)
                    return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                }

                val userId = call.userIdOrNull(userRepository)
                    ?: return@post call.respondError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)

                when (
                    val result =
                        supportService.createTicket(
                            clubId = clubId,
                            userId = userId,
                            bookingId = null,
                            listEntryId = null,
                            topic = topic,
                            text = text,
                            attachments = request.attachments,
                        )
                ) {
                    is SupportServiceResult.Success -> {
                        val ticket = result.value.ticket
                        logger.info("support.ticket.create id={} club_id={}", ticket.id, clubId)
                        runCatching {
                            opsPublisher.enqueue(
                                OpsDomainNotification(
                                    clubId = ticket.clubId,
                                    event = OpsNotificationEvent.SUPPORT_QUESTION_CREATED,
                                    subjectId = ticket.id.toString(),
                                    occurredAt = ticket.createdAt,
                                ),
                            )
                        }
                        call.respond(
                            HttpStatusCode.Created,
                            TicketResponse(
                                id = ticket.id,
                                clubId = ticket.clubId,
                                topic = ticket.topic.wire,
                                status = ticket.status.wire,
                                updatedAt = ticket.updatedAt.toString(),
                            ),
                        )
                    }

                    is SupportServiceResult.Failure -> {
                        logger.warn("support.ticket.create internal_error club_id={}", clubId)
                        call.respondError(HttpStatusCode.InternalServerError, ErrorCodes.internal_error)
                    }
                }
            }

            get("/tickets/my") {
                val userId = call.userIdOrNull(userRepository)
                    ?: return@get call.respondError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
                val tickets = supportService.listMyTickets(userId)
                call.respond(HttpStatusCode.OK, tickets.map { it.toResponse() })
            }

            get("/tickets/my/{ticketId}") {
                val ticketId = call.parseGuestTicketIdOrRespond() ?: return@get
                val userId =
                    call.userIdOrNull(userRepository)
                        ?: return@get call.respondError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)

                when (val result = supportService.getMyTicket(ticketId = ticketId, userId = userId)) {
                    is SupportServiceResult.Success ->
                        call.respond(HttpStatusCode.OK, result.value.toResponse())

                    is SupportServiceResult.Failure ->
                        when (result.error) {
                            SupportServiceError.TicketNotFound -> {
                                logger.info("support.ticket.my.detail not_found ticket_id={}", ticketId)
                                call.respondError(HttpStatusCode.NotFound, ErrorCodes.support_ticket_not_found)
                            }

                            else -> {
                                logger.warn("support.ticket.my.detail internal_error ticket_id={}", ticketId)
                                call.respondError(HttpStatusCode.InternalServerError, ErrorCodes.internal_error)
                            }
                        }
                }
            }

            post("/tickets/{id}/messages") {
                val ticketId = call.parameters["id"]?.toLongOrNull()
                if (ticketId == null || ticketId <= 0) {
                    logger.warn("support.ticket.message validation_error ticket_id={}", call.parameters["id"])
                    return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                }

                val request =
                    runCatching { call.receive<AddMessageRequest>() }.getOrElse {
                        logger.warn("support.ticket.message invalid_json ticket_id={}", ticketId)
                        return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)
                    }

                val text = normalizeText(request.text)
                if (text == null) {
                    logger.warn("support.ticket.message validation_error ticket_id={}", ticketId)
                    return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                }

                val userId = call.userIdOrNull(userRepository)
                    ?: return@post call.respondError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)

                when (
                    val result =
                        supportService.addGuestMessage(
                            ticketId = ticketId,
                            userId = userId,
                            text = text,
                            attachments = request.attachments,
                        )
                ) {
                    is SupportServiceResult.Success -> {
                        val message = result.value
                        call.respond(
                            HttpStatusCode.OK,
                            message.toResponse(),
                        )
                    }

                    is SupportServiceResult.Failure -> {
                        val (status, code) = mapSupportError(result.error)
                        call.respondError(status, code)
                    }
                }
            }

            route("/staff") {
                requireSupportUser(userRepository)
                get("/clubs") {
                    val userId = call.supportUserId()
                    val permittedClubIds =
                        call.listPermittedSupportClubIdsOrRespond(
                            repository = userRolePermissionRepository,
                            userId = userId,
                            permission = PermissionCodes.SUPPORT_VIEW,
                            action = "clubs",
                        ) ?: return@get
                    val clubs =
                        call.loadPermittedSupportClubsOrRespond(
                            repository = clubsRepository,
                            clubIds = permittedClubIds,
                        ) ?: return@get
                    call.respond(HttpStatusCode.OK, clubs)
                }
            }

            route("/tickets") {
                requireSupportUser(userRepository)
                get {
                    val clubId = call.parseCanonicalQueryIdOrRespond("clubId", "list") ?: return@get
                    val statusRaw = call.request.queryParameters["status"]
                    val status = statusRaw?.let { TicketStatus.fromWire(it) }
                    if (statusRaw != null && status == null) {
                        logger.warn("support.ticket.list validation_error status={}", statusRaw)
                        return@get call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                    }

                    val userId = call.supportUserId()
                    val permitted =
                        call.hasSupportClubPermissionOrRespond(
                            repository = userRolePermissionRepository,
                            userId = userId,
                            clubId = clubId,
                            permission = PermissionCodes.SUPPORT_VIEW,
                            action = "list",
                        ) ?: return@get
                    if (!permitted) {
                        return@get call.respondError(HttpStatusCode.Forbidden, ErrorCodes.support_ticket_forbidden)
                    }

                    when (val result = staffSupportReadService.listStaffTicketsForClub(clubId, status)) {
                        is SupportServiceResult.Success ->
                            call.respond(HttpStatusCode.OK, result.value.map { it.toResponse() })
                        is SupportServiceResult.Failure -> {
                            val (statusCode, code) = mapSupportStaffReadError(result.error)
                            call.respondError(statusCode, code)
                        }
                    }
                }
            }

            route("/tickets/{ticketId}") {
                requireSupportUser(userRepository)
                get {
                    val ticketId = call.parseTicketIdOrRespond("detail") ?: return@get
                    val userId = call.supportUserId()
                    val permittedClubIds =
                        call.listPermittedSupportClubIdsOrRespond(
                            repository = userRolePermissionRepository,
                            userId = userId,
                            permission = PermissionCodes.SUPPORT_VIEW,
                            action = "detail",
                        ) ?: return@get
                    when (val result = staffSupportReadService.getStaffTicket(ticketId, permittedClubIds)) {
                        is SupportServiceResult.Success ->
                            call.respond(HttpStatusCode.OK, result.value.toResponse())
                        is SupportServiceResult.Failure -> {
                            val (statusCode, code) = mapSupportStaffReadError(result.error)
                            call.respondError(statusCode, code)
                        }
                    }
                }

                post("/assign") {
                    val ticketId = call.parseTicketIdOrRespond("assign") ?: return@post
                    val ticket =
                        call.loadStaffMutationTicketOrRespond(
                            staffSupportReadService = staffSupportReadService,
                            permissionRepository = userRolePermissionRepository,
                            ticketId = ticketId,
                            permission = PermissionCodes.SUPPORT_STATUS_MANAGE,
                            action = "assign",
                        ) ?: return@post

                    when (val result = supportService.assign(ticketId = ticketId, agentUserId = call.supportUserId())) {
                        is SupportServiceResult.Success -> {
                            logger.info("support.ticket.assign ticket_id={} club_id={}", ticketId, ticket.clubId)
                            call.respond(HttpStatusCode.OK, result.value.toResponse())
                        }
                        is SupportServiceResult.Failure -> {
                            val (status, code) = mapSupportAdminError(result.error)
                            call.respondError(status, code)
                        }
                    }
                }

                post("/status") {
                    val ticketId = call.parseTicketIdOrRespond("status") ?: return@post
                    val ticket =
                        call.loadStaffMutationTicketOrRespond(
                            staffSupportReadService = staffSupportReadService,
                            permissionRepository = userRolePermissionRepository,
                            ticketId = ticketId,
                            permission = PermissionCodes.SUPPORT_STATUS_MANAGE,
                            action = "status",
                        ) ?: return@post

                    val request =
                        receiveSupportRequestOrNull { call.receive<UpdateStatusRequest>() } ?: run {
                            logger.warn("support.ticket.status invalid_json ticket_id={}", ticketId)
                            return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)
                        }

                    val status = request.status?.let { TicketStatus.fromWire(it) }
                    if (status == null) {
                        logger.warn("support.ticket.status validation_error ticket_id={}", ticketId)
                        return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                    }

                    when (
                        val result =
                            supportService.setStatus(
                                ticketId = ticketId,
                                agentUserId = call.supportUserId(),
                                status = status,
                            )
                    ) {
                        is SupportServiceResult.Success -> {
                            logger.info("support.ticket.status ticket_id={} club_id={}", ticketId, ticket.clubId)
                            call.respond(HttpStatusCode.OK, result.value.toResponse())
                        }
                        is SupportServiceResult.Failure -> {
                            val (statusCode, code) = mapSupportAdminError(result.error)
                            call.respondError(statusCode, code)
                        }
                    }
                }

                post("/reply") {
                    val ticketId = call.parseTicketIdOrRespond("reply") ?: return@post
                    call.loadStaffMutationTicketOrRespond(
                        staffSupportReadService = staffSupportReadService,
                        permissionRepository = userRolePermissionRepository,
                        ticketId = ticketId,
                        permission = PermissionCodes.SUPPORT_REPLY,
                        action = "reply",
                    ) ?: return@post

                    val request =
                        receiveSupportRequestOrNull { call.receive<ReplyRequest>() } ?: run {
                            logger.warn("support.ticket.reply invalid_json ticket_id={}", ticketId)
                            return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.invalid_json)
                        }

                    val text = normalizeText(request.text)
                    if (text == null) {
                        logger.warn("support.ticket.reply validation_error ticket_id={}", ticketId)
                        return@post call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                    }

                    when (
                        val result =
                            supportService.reply(
                                ticketId = ticketId,
                                agentUserId = call.supportUserId(),
                                text = text,
                                attachments = request.attachments,
                            )
                    ) {
                        is SupportServiceResult.Success -> {
                            val reply = result.value
                            logger.info("support.ticket.reply ticket_id={} club_id={}", ticketId, reply.ticket.clubId)
                            call.respond(
                                HttpStatusCode.OK,
                                SupportReplyResponse(
                                    ticketId = reply.ticket.id,
                                    clubId = reply.ticket.clubId,
                                    ownerUserId = reply.ticket.userId,
                                    replyMessageId = reply.replyMessage.id,
                                    replyCreatedAt = reply.replyMessage.createdAt.toString(),
                                    ticketStatus = reply.ticket.status.wire,
                                ),
                            )
                            call.application.launch(MDCContext()) {
                                sendSupportReplyNotification(
                                    sendTelegram = sendTelegram,
                                    userRepository = userRepository,
                                    ticket = reply.ticket,
                                    replyText = text,
                                    clubNameProvider = clubNameProvider,
                                )
                            }
                        }
                        is SupportServiceResult.Failure -> {
                            val (statusCode, code) = mapSupportAdminError(result.error)
                            call.respondError(statusCode, code)
                        }
                    }
                }
            }
        }
    }
}

private suspend fun ApplicationCall.userIdOrNull(userRepository: UserRepository): Long? {
    val telegramUserId = attributes[MiniAppUserKey].id
    val user = userRepository.getByTelegramId(telegramUserId)
    if (user == null) {
        logger.warn("support.ticket.forbidden user_not_found")
    }
    return user?.id
}

private suspend fun ApplicationCall.parseTicketIdOrRespond(action: String): Long? {
    val rawTicketId = parameters["ticketId"]
    val ticketId = parseCanonicalPositiveLong(rawTicketId)
    if (ticketId == null) {
        logger.warn("support.ticket.{} validation_error", action)
        respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
        return null
    }
    return ticketId
}

private suspend fun ApplicationCall.parseCanonicalQueryIdOrRespond(
    parameter: String,
    action: String,
): Long? {
    val value = parseCanonicalPositiveLong(request.queryParameters[parameter])
    if (value == null) {
        logger.warn("support.ticket.{} validation_error parameter={}", action, parameter)
        respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
        return null
    }
    return value
}

private fun parseCanonicalPositiveLong(raw: String?): Long? =
    raw
        ?.takeIf(canonicalPositiveTicketId::matches)
        ?.toLongOrNull()

private suspend fun ApplicationCall.parseGuestTicketIdOrRespond(): Long? {
    val rawTicketId = parameters["ticketId"]
    val ticketId =
        rawTicketId
            ?.takeIf(canonicalPositiveTicketId::matches)
            ?.toLongOrNull()
    if (ticketId == null) {
        logger.warn("support.ticket.my.detail validation_error")
        respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
        return null
    }
    return ticketId
}

private fun normalizeText(text: String?): String? {
    val trimmed = text?.trim() ?: return null
    if (trimmed.isBlank() || trimmed.length > 2000) {
        return null
    }
    return trimmed
}

private fun TicketSummary.toResponse(): TicketSummaryResponse =
    TicketSummaryResponse(
        id = id,
        clubId = clubId,
        topic = topic.wire,
        status = status.wire,
        updatedAt = updatedAt.toString(),
        lastMessagePreview = lastMessagePreview,
        lastSenderType = lastSenderType?.wire,
    )

private fun GuestTicketThread.toResponse(): GuestTicketThreadResponse =
    GuestTicketThreadResponse(
        ticket =
            GuestTicketDetailsResponse(
                id = ticket.id,
                clubId = ticket.clubId,
                topic = ticket.topic.wire,
                status = ticket.status.wire,
                createdAt = ticket.createdAt.toString(),
                updatedAt = ticket.updatedAt.toString(),
            ),
        messages =
            messages.map { message ->
                GuestTicketMessageResponse(
                    id = message.id,
                    senderType = message.senderType.wire,
                    text = message.text,
                    attachments = message.attachments,
                    createdAt = message.createdAt.toString(),
                )
            },
    )

private fun StaffTicketThread.toResponse(): StaffTicketThreadResponse =
    StaffTicketThreadResponse(
        ticket =
            StaffTicketDetailsResponse(
                id = ticket.id,
                clubId = ticket.clubId,
                topic = ticket.topic.wire,
                status = ticket.status.wire,
                createdAt = ticket.createdAt.toString(),
                updatedAt = ticket.updatedAt.toString(),
            ),
        messages =
            messages.map { message ->
                StaffTicketMessageResponse(
                    id = message.id,
                    senderType = message.senderType.wire,
                    text = message.text,
                    attachments = message.attachments,
                    createdAt = message.createdAt.toString(),
                )
            },
    )

private fun Ticket.toResponse(): TicketResponse =
    TicketResponse(
        id = id,
        clubId = clubId,
        topic = topic.wire,
        status = status.wire,
        updatedAt = updatedAt.toString(),
    )

private fun TicketMessage.toResponse(): MessageResponse =
    MessageResponse(
        messageId = id,
        ticketId = ticketId,
        senderType = senderType.wire,
        createdAt = createdAt.toString(),
    )

private suspend fun sendSupportReplyNotification(
    sendTelegram: suspend (BaseRequest<*, *>) -> BaseResponse,
    userRepository: UserRepository,
    ticket: Ticket,
    replyText: String,
    clubNameProvider: suspend (clubId: Long) -> String? = { null },
) {
    try {
        val user = userRepository.getById(ticket.userId) ?: return
        val telegramUserId = user.telegramId
        val clubName =
            try {
                clubNameProvider(ticket.clubId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                null
            }
        val message = buildSupportReplyMessage(clubName, replyText)
        val keyboard = buildSupportRatingKeyboard(ticket.id)
        val request = SendMessage(telegramUserId, message)
        if (keyboard != null) {
            request.replyMarkup(keyboard)
        }
        sendTelegram(request)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        logger.warn(
            "support.ticket.reply.notify_failed ticket_id={} club_id={} error={}",
            ticket.id,
            ticket.clubId,
            e::class.java.simpleName,
        )
    }
}

private fun buildSupportRatingKeyboard(ticketId: Long): InlineKeyboardMarkup? {
    val up = SupportCallbacks.buildRate(ticketId, up = true)
    val down = SupportCallbacks.buildRate(ticketId, up = false)
    if (!SupportCallbacks.fits(up) || !SupportCallbacks.fits(down)) {
        return null
    }
    return InlineKeyboardMarkup(
        arrayOf(
            InlineKeyboardButton("👍").callbackData(up),
            InlineKeyboardButton("👎").callbackData(down),
        ),
    )
}

private fun mapSupportAdminError(error: SupportServiceError): Pair<HttpStatusCode, String> =
    when (error) {
        SupportServiceError.InvalidState -> HttpStatusCode.Conflict to ErrorCodes.invalid_state
        SupportServiceError.TicketNotFound -> HttpStatusCode.NotFound to ErrorCodes.support_ticket_not_found
        SupportServiceError.TicketForbidden -> HttpStatusCode.Forbidden to ErrorCodes.support_ticket_forbidden
        else -> HttpStatusCode.InternalServerError to ErrorCodes.internal_error
    }

private fun mapSupportStaffReadError(error: SupportServiceError): Pair<HttpStatusCode, String> =
    when (error) {
        SupportServiceError.TicketNotFound,
        SupportServiceError.TicketForbidden,
        -> HttpStatusCode.NotFound to ErrorCodes.support_ticket_not_found
        else -> HttpStatusCode.InternalServerError to ErrorCodes.internal_error
    }

private fun mapSupportError(error: SupportServiceError): Pair<HttpStatusCode, String> =
    when (error) {
        SupportServiceError.PersistenceFailure -> HttpStatusCode.InternalServerError to ErrorCodes.internal_error
        SupportServiceError.InvalidState -> HttpStatusCode.Conflict to ErrorCodes.invalid_state
        SupportServiceError.TicketNotFound -> HttpStatusCode.NotFound to ErrorCodes.support_ticket_not_found
        SupportServiceError.TicketForbidden -> HttpStatusCode.Forbidden to ErrorCodes.support_ticket_forbidden
        SupportServiceError.TicketClosed -> HttpStatusCode.Conflict to ErrorCodes.support_ticket_closed
        SupportServiceError.RatingNotAllowed -> HttpStatusCode.Conflict to ErrorCodes.invalid_state
        SupportServiceError.RatingAlreadySet -> HttpStatusCode.Conflict to ErrorCodes.invalid_state
    }

private fun Route.requireSupportUser(userRepository: UserRepository) {
    intercept(ApplicationCallPipeline.Plugins) {
        val userId = call.userIdOrNull(userRepository)
        if (userId == null) {
            call.respondError(HttpStatusCode.Forbidden, ErrorCodes.forbidden)
            finish()
        } else if (!call.attributes.contains(supportUserIdKey)) {
            call.attributes.put(supportUserIdKey, userId)
        }
    }
}

private fun ApplicationCall.supportUserId(): Long = attributes[supportUserIdKey]

private suspend fun ApplicationCall.hasSupportClubPermissionOrRespond(
    repository: UserRolePermissionRepository,
    userId: Long,
    clubId: Long,
    permission: PermissionCode,
    action: String,
): Boolean? =
    try {
        repository.hasClubPermission(
            userId = userId,
            clubId = clubId,
            allowedRoles = operationalSupportRoles,
            permission = permission,
        )
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        logger.warn("support.ticket.{} permission_lookup_failed", action)
        respondError(HttpStatusCode.InternalServerError, ErrorCodes.internal_error)
        null
    }

private suspend fun ApplicationCall.listPermittedSupportClubIdsOrRespond(
    repository: UserRolePermissionRepository,
    userId: Long,
    permission: PermissionCode,
    action: String,
): Set<Long>? =
    try {
        repository.listClubIdsForPermission(
            userId = userId,
            allowedRoles = operationalSupportRoles,
            permission = permission,
        )
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        logger.warn("support.ticket.{} permission_lookup_failed", action)
        respondError(HttpStatusCode.InternalServerError, ErrorCodes.internal_error)
        null
    }

private suspend fun ApplicationCall.loadPermittedSupportClubsOrRespond(
    repository: ClubsRepository,
    clubIds: Set<Long>,
): List<SupportStaffClubResponse>? =
    try {
        val clubs = mutableListOf<SupportStaffClubResponse>()
        for (clubId in clubIds.sorted()) {
            val club = repository.getById(clubId) ?: continue
            clubs += SupportStaffClubResponse(id = club.id, name = club.name)
        }
        clubs.sortedWith(
            compareBy(String.CASE_INSENSITIVE_ORDER, SupportStaffClubResponse::name)
                .thenBy(SupportStaffClubResponse::id),
        )
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        logger.warn("support.staff.clubs persistence_failure")
        respondError(HttpStatusCode.InternalServerError, ErrorCodes.internal_error)
        null
    }

private suspend fun ApplicationCall.loadStaffMutationTicketOrRespond(
    staffSupportReadService: StaffSupportReadService,
    permissionRepository: UserRolePermissionRepository,
    ticketId: Long,
    permission: PermissionCode,
    action: String,
): Ticket? {
    val permittedClubIds =
        listPermittedSupportClubIdsOrRespond(
            repository = permissionRepository,
            userId = supportUserId(),
            permission = permission,
            action = action,
        ) ?: return null
    return when (val result = staffSupportReadService.getStaffMutationTicket(ticketId, permittedClubIds)) {
        is SupportServiceResult.Success -> result.value
        is SupportServiceResult.Failure -> {
            val (status, code) = mapSupportAdminError(result.error)
            respondError(status, code)
            null
        }
    }
}

private suspend fun <T> receiveSupportRequestOrNull(receive: suspend () -> T): T? =
    try {
        receive()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

internal suspend fun receiveCreateTicketRequestOrNull(
    receive: suspend () -> CreateTicketRequest,
): CreateTicketRequest? =
    try {
        receive()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
