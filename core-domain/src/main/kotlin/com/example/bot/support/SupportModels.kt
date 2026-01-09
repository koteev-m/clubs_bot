package com.example.bot.support

import java.time.Instant
import java.util.UUID

enum class TicketTopic(val wire: String) {
    ADDRESS("address"),
    DRESSCODE("dresscode"),
    BOOKING("booking"),
    INVITE("invite"),
    LOST_FOUND("lost_found"),
    COMPLAINT("complaint"),
    OTHER("other");

    companion object {
        fun fromWire(value: String): TicketTopic? = entries.firstOrNull { it.wire == value }
    }
}

enum class TicketStatus(val wire: String) {
    OPENED("opened"),
    IN_PROGRESS("in_progress"),
    ANSWERED("answered"),
    CLOSED("closed");

    companion object {
        fun fromWire(value: String): TicketStatus? = entries.firstOrNull { it.wire == value }
    }
}

enum class TicketSenderType(val wire: String) {
    GUEST("guest"),
    AGENT("agent"),
    SYSTEM("system");

    companion object {
        fun fromWire(value: String): TicketSenderType? = entries.firstOrNull { it.wire == value }
    }
}

data class Ticket(
    val id: Long,
    val clubId: Long,
    val userId: Long,
    val bookingId: UUID?,
    val listEntryId: Long?,
    val topic: TicketTopic,
    val status: TicketStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
    val lastAgentId: Long?,
    val resolutionRating: Int?,
)

data class TicketMessage(
    val id: Long,
    val ticketId: Long,
    val senderType: TicketSenderType,
    val text: String,
    val attachments: String?,
    val createdAt: Instant,
)

data class TicketWithMessage(
    val ticket: Ticket,
    val initialMessage: TicketMessage,
)

data class SupportReplyResult(
    val ticket: Ticket,
    val replyMessage: TicketMessage,
)

data class TicketSummary(
    val id: Long,
    val clubId: Long,
    val topic: TicketTopic,
    val status: TicketStatus,
    val updatedAt: Instant,
    val lastMessagePreview: String?,
    val lastSenderType: TicketSenderType?,
)

sealed interface SupportServiceError {
    data object TicketNotFound : SupportServiceError
    data object TicketForbidden : SupportServiceError
    data object TicketClosed : SupportServiceError
    data object RatingNotAllowed : SupportServiceError
    data object RatingAlreadySet : SupportServiceError
}

sealed interface SupportServiceResult<out T> {
    data class Success<T>(val value: T) : SupportServiceResult<T>

    data class Failure(val error: SupportServiceError) : SupportServiceResult<Nothing>
}

interface SupportService {
    suspend fun createTicket(
        clubId: Long,
        userId: Long,
        bookingId: UUID?,
        listEntryId: Long?,
        topic: TicketTopic,
        text: String,
        attachments: String?,
    ): SupportServiceResult<TicketWithMessage>

    suspend fun listMyTickets(
        userId: Long,
    ): List<TicketSummary>

    suspend fun addGuestMessage(
        ticketId: Long,
        userId: Long,
        text: String,
        attachments: String?,
    ): SupportServiceResult<TicketMessage>

    suspend fun listTicketsForClub(
        clubId: Long,
        status: TicketStatus?,
    ): List<TicketSummary>

    suspend fun assign(
        ticketId: Long,
        agentUserId: Long,
    ): SupportServiceResult<Ticket>

    suspend fun setStatus(
        ticketId: Long,
        agentUserId: Long,
        status: TicketStatus,
    ): SupportServiceResult<Ticket>

    suspend fun reply(
        ticketId: Long,
        agentUserId: Long,
        text: String,
        attachments: String?,
    ): SupportServiceResult<SupportReplyResult>

    suspend fun setResolutionRating(
        ticketId: Long,
        userId: Long,
        rating: Int,
    ): SupportServiceResult<Ticket>

    suspend fun getTicket(
        ticketId: Long,
    ): Ticket?
}
