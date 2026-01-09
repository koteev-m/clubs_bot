package com.example.bot.support

import java.time.Instant
import java.util.UUID

enum class TicketTopic(val value: String) {
    ADDRESS("address"),
    DRESSCODE("dresscode"),
    BOOKING("booking"),
    INVITE("invite"),
    LOST_FOUND("lost_found"),
    COMPLAINT("complaint"),
    OTHER("other");

    override fun toString(): String = value
}

enum class TicketStatus(val value: String) {
    OPENED("opened"),
    IN_PROGRESS("in_progress"),
    ANSWERED("answered"),
    CLOSED("closed");

    override fun toString(): String = value
}

enum class TicketSenderType(val value: String) {
    GUEST("guest"),
    AGENT("agent"),
    SYSTEM("system");

    override fun toString(): String = value
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

data class TicketSummary(
    val id: Long,
    val clubId: Long,
    val userId: Long,
    val bookingId: UUID?,
    val listEntryId: Long?,
    val topic: TicketTopic,
    val status: TicketStatus,
    val updatedAt: Instant,
    val lastMessageAt: Instant?,
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
        messageText: String,
        attachments: String?,
    ): SupportServiceResult<Ticket>

    suspend fun addMessage(
        ticketId: Long,
        senderId: Long,
        senderType: TicketSenderType,
        text: String,
        attachments: String?,
    ): SupportServiceResult<TicketMessage>

    suspend fun getTicket(
        ticketId: Long,
        requesterId: Long,
    ): SupportServiceResult<Ticket>

    suspend fun listTickets(
        clubId: Long,
        requesterId: Long,
    ): SupportServiceResult<List<TicketSummary>>

    suspend fun rateTicket(
        ticketId: Long,
        userId: Long,
        rating: Int,
    ): SupportServiceResult<Ticket>
}
