package com.example.bot.routes

import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.Role
import com.example.bot.http.ErrorCodes
import com.example.bot.http.respondError
import com.example.bot.http.ensureMiniAppNoStoreHeaders
import com.example.bot.plugins.MiniAppUserKey
import com.example.bot.plugins.miniAppBotTokenProvider
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.security.rbac.authorize
import com.example.bot.security.rbac.rbacContext
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

private val logger = LoggerFactory.getLogger("SupportRoutes")
@Serializable
private data class CreateTicketRequest(
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

private val supportGlobalRoles = setOf(Role.OWNER, Role.GLOBAL_ADMIN, Role.HEAD_MANAGER)
private val supportAdminRoles = supportGlobalRoles + Role.CLUB_ADMIN

fun Application.supportRoutes(
    supportService: SupportService,
    userRepository: UserRepository,
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
                    runCatching { call.receive<CreateTicketRequest>() }.getOrElse {
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

            route("/tickets") {
                requireSupportUser(userRepository)
                supportAdminAuthorize {
                    get {
                        val clubId = call.request.queryParameters["clubId"]?.toLongOrNull()
                        if (clubId == null || clubId <= 0) {
                            logger.warn("support.ticket.list validation_error club_id={}", call.request.queryParameters["clubId"])
                            return@get call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                        }

                        val statusRaw = call.request.queryParameters["status"]
                        val status = statusRaw?.let { TicketStatus.fromWire(it) }
                        if (statusRaw != null && status == null) {
                            logger.warn("support.ticket.list validation_error status={}", statusRaw)
                            return@get call.respondError(HttpStatusCode.BadRequest, ErrorCodes.validation_error)
                        }

                        if (!call.hasSupportClubAccess(clubId)) {
                            return@get call.respondError(HttpStatusCode.Forbidden, ErrorCodes.support_ticket_forbidden)
                        }

                        val tickets = supportService.listTicketsForClub(clubId, status)
                        call.respond(HttpStatusCode.OK, tickets.map { it.toResponse() })
                    }
                }
            }

            route("/tickets/{id}") {
                requireSupportUser(userRepository)
                supportAdminAuthorize {
                    post("/assign") {
                        val ticketId = call.parseTicketIdOrRespond("assign") ?: return@post

                        val ticket = supportService.getTicket(ticketId)
                            ?: return@post call.respondError(HttpStatusCode.NotFound, ErrorCodes.support_ticket_not_found)
                        if (!call.hasSupportClubAccess(ticket.clubId)) {
                            return@post call.respondError(HttpStatusCode.Forbidden, ErrorCodes.support_ticket_forbidden)
                        }

                        when (val result = supportService.assign(ticketId = ticketId, agentUserId = call.rbacContext().user.id)) {
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

                        val ticket = supportService.getTicket(ticketId)
                            ?: return@post call.respondError(HttpStatusCode.NotFound, ErrorCodes.support_ticket_not_found)
                        if (!call.hasSupportClubAccess(ticket.clubId)) {
                            return@post call.respondError(HttpStatusCode.Forbidden, ErrorCodes.support_ticket_forbidden)
                        }

                        val request =
                            runCatching { call.receive<UpdateStatusRequest>() }.getOrElse {
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
                                    agentUserId = call.rbacContext().user.id,
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

                        val ticket = supportService.getTicket(ticketId)
                            ?: return@post call.respondError(HttpStatusCode.NotFound, ErrorCodes.support_ticket_not_found)
                        if (!call.hasSupportClubAccess(ticket.clubId)) {
                            return@post call.respondError(HttpStatusCode.Forbidden, ErrorCodes.support_ticket_forbidden)
                        }

                        val request =
                            runCatching { call.receive<ReplyRequest>() }.getOrElse {
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
                                    agentUserId = call.rbacContext().user.id,
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
    val rawTicketId = parameters["id"]
    val ticketId = rawTicketId?.toLongOrNull()
    if (ticketId == null || ticketId <= 0) {
        logger.warn("support.ticket.{} validation_error ticket_id={}", action, rawTicketId)
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
        SupportServiceError.TicketNotFound -> HttpStatusCode.NotFound to ErrorCodes.support_ticket_not_found
        SupportServiceError.TicketForbidden -> HttpStatusCode.Forbidden to ErrorCodes.support_ticket_forbidden
        else -> HttpStatusCode.InternalServerError to ErrorCodes.internal_error
    }

private fun mapSupportError(error: SupportServiceError): Pair<HttpStatusCode, String> =
    when (error) {
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
        }
    }
}

private fun ApplicationCall.hasSupportClubAccess(clubId: Long): Boolean {
    val context = rbacContext()
    val isGlobal = context.roles.any { it in supportGlobalRoles }
    return isGlobal || clubId in context.clubIds
}

private fun Route.supportAdminAuthorize(block: Route.() -> Unit) {
    authorize(
        *supportAdminRoles.toTypedArray(),
        forbiddenHandler = { call ->
            call.respondError(HttpStatusCode.Forbidden, ErrorCodes.support_ticket_forbidden)
        },
        block = block,
    )
}
