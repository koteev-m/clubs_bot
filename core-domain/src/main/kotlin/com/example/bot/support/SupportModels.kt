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
    NEW("new"),
    OPENED("opened"),
    IN_PROGRESS("in_progress"),
    ANSWERED("answered"),
    RESOLVED("resolved"),
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

enum class SupportReplyDeliveryStatus(
    val wire: String,
) {
    PENDING("pending"),
    SENDING("sending"),
    DELIVERED("delivered"),
    FAILED("failed"),
    UNCONFIRMED("unconfirmed"),
    ;

    companion object {
        fun fromWire(value: String): SupportReplyDeliveryStatus? = entries.firstOrNull { it.wire == value }
    }
}

enum class SupportReplyDeliveryFailureCode(
    val wire: String,
) {
    RECIPIENT_UNAVAILABLE("recipient_unavailable"),
    CLIENT_UNAVAILABLE("client_unavailable"),
    TELEGRAM_REJECTED("telegram_rejected"),
    TIMEOUT("timeout"),
    TRANSPORT_ERROR("transport_error"),
    CANCELED("canceled"),
    ;

    companion object {
        fun fromWire(value: String): SupportReplyDeliveryFailureCode? = entries.firstOrNull { it.wire == value }
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
    val deliveryId: Long,
    val deliveryStatus: SupportReplyDeliveryStatus,
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

data class GuestTicketDetails(
    val id: Long,
    val clubId: Long,
    val topic: TicketTopic,
    val status: TicketStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class GuestTicketMessage(
    val id: Long,
    val senderType: TicketSenderType,
    val text: String,
    val attachments: String?,
    val createdAt: Instant,
)

data class GuestTicketThread(
    val ticket: GuestTicketDetails,
    val messages: List<GuestTicketMessage>,
)

data class StaffTicketDetails(
    val id: Long,
    val clubId: Long,
    val topic: TicketTopic,
    val status: TicketStatus,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class StaffTicketMessage(
    val id: Long,
    val senderType: TicketSenderType,
    val text: String,
    val attachments: String?,
    val createdAt: Instant,
    val deliveryStatus: SupportReplyDeliveryStatus?,
)

data class StaffTicketThread(
    val ticket: StaffTicketDetails,
    val messages: List<StaffTicketMessage>,
)

sealed interface SupportReplyDeliveryOutcome {
    data object Delivered : SupportReplyDeliveryOutcome

    data object Failed : SupportReplyDeliveryOutcome

    data object Unconfirmed : SupportReplyDeliveryOutcome

    data object PersistenceFailure : SupportReplyDeliveryOutcome
}

fun interface SupportReplyDeliveryService {
    suspend fun deliver(deliveryId: Long): SupportReplyDeliveryOutcome
}

sealed interface SupportServiceError {
    data object PersistenceFailure : SupportServiceError

    data object InvalidState : SupportServiceError

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

interface SupportService : SupportLifecycleService {
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

    suspend fun getMyTicket(
        ticketId: Long,
        userId: Long,
    ): SupportServiceResult<GuestTicketThread> = SupportServiceResult.Failure(SupportServiceError.PersistenceFailure)

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

interface StaffSupportReadService {
    suspend fun listStaffTicketsForClub(
        clubId: Long,
        status: TicketStatus?,
    ): SupportServiceResult<List<TicketSummary>>

    suspend fun getStaffTicket(
        ticketId: Long,
        permittedClubIds: Set<Long>,
    ): SupportServiceResult<StaffTicketThread>

    suspend fun getStaffMutationTicket(
        ticketId: Long,
        permittedClubIds: Set<Long>,
    ): SupportServiceResult<Long>
}
