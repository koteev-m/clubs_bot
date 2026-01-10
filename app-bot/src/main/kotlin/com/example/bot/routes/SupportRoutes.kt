package com.example.bot.routes

import com.example.bot.data.security.UserRepository
import com.example.bot.http.ErrorCodes
import com.example.bot.http.respondError
import com.example.bot.http.ensureMiniAppNoStoreHeaders
import com.example.bot.plugins.MiniAppUserKey
import com.example.bot.plugins.miniAppBotTokenProvider
import com.example.bot.plugins.withMiniAppAuth
import com.example.bot.support.SupportService
import com.example.bot.support.SupportServiceError
import com.example.bot.support.SupportServiceResult
import com.example.bot.support.TicketMessage
import com.example.bot.support.TicketSummary
import com.example.bot.support.TicketTopic
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
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

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
private data class MessageResponse(
    val messageId: Long,
    val ticketId: Long,
    val senderType: String,
    val createdAt: String,
)

fun Application.supportRoutes(
    supportService: SupportService,
    userRepository: UserRepository,
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

private fun TicketMessage.toResponse(): MessageResponse =
    MessageResponse(
        messageId = id,
        ticketId = ticketId,
        senderType = senderType.wire,
        createdAt = createdAt.toString(),
    )

private fun mapSupportError(error: SupportServiceError): Pair<HttpStatusCode, String> =
    when (error) {
        SupportServiceError.TicketNotFound -> HttpStatusCode.NotFound to ErrorCodes.support_ticket_not_found
        SupportServiceError.TicketForbidden -> HttpStatusCode.Forbidden to ErrorCodes.support_ticket_forbidden
        SupportServiceError.TicketClosed -> HttpStatusCode.Conflict to ErrorCodes.support_ticket_closed
        SupportServiceError.RatingNotAllowed -> HttpStatusCode.Conflict to ErrorCodes.invalid_state
        SupportServiceError.RatingAlreadySet -> HttpStatusCode.Conflict to ErrorCodes.invalid_state
    }
