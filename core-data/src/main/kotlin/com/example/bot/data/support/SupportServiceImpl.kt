package com.example.bot.data.support

import com.example.bot.support.SupportReplyResult
import com.example.bot.support.SupportService
import com.example.bot.support.SupportServiceError
import com.example.bot.support.SupportServiceResult
import com.example.bot.support.Ticket
import com.example.bot.support.TicketMessage
import com.example.bot.support.TicketStatus
import com.example.bot.support.TicketSummary
import com.example.bot.support.TicketTopic
import com.example.bot.support.TicketWithMessage
import java.util.UUID

class SupportServiceImpl(
    private val repository: SupportRepository,
) : SupportService {
    override suspend fun createTicket(
        clubId: Long,
        userId: Long,
        bookingId: UUID?,
        listEntryId: Long?,
        topic: TicketTopic,
        text: String,
        attachments: String?,
    ): SupportServiceResult<TicketWithMessage> =
        SupportServiceResult.Success(
            repository.createTicket(
                clubId = clubId,
                userId = userId,
                bookingId = bookingId,
                listEntryId = listEntryId,
                topic = topic,
                text = text,
                attachments = attachments,
            ),
        )

    override suspend fun listMyTickets(userId: Long): List<TicketSummary> =
        repository.listTicketsByUser(userId)

    override suspend fun addGuestMessage(
        ticketId: Long,
        userId: Long,
        text: String,
        attachments: String?,
    ): SupportServiceResult<TicketMessage> {
        val ticket = repository.findTicket(ticketId) ?: return SupportServiceResult.Failure(SupportServiceError.TicketNotFound)
        if (ticket.userId != userId) {
            return SupportServiceResult.Failure(SupportServiceError.TicketForbidden)
        }
        if (ticket.status == TicketStatus.CLOSED) {
            return SupportServiceResult.Failure(SupportServiceError.TicketClosed)
        }
        val message = repository.addGuestMessage(ticketId = ticketId, text = text, attachments = attachments)
        return SupportServiceResult.Success(message)
    }

    override suspend fun listTicketsForClub(
        clubId: Long,
        status: TicketStatus?,
    ): List<TicketSummary> =
        repository.listTicketsByClub(clubId = clubId, status = status)

    override suspend fun assign(
        ticketId: Long,
        agentUserId: Long,
    ): SupportServiceResult<Ticket> {
        val ticket = repository.findTicket(ticketId) ?: return SupportServiceResult.Failure(SupportServiceError.TicketNotFound)
        val updated = repository.assign(ticketId = ticket.id, agentUserId = agentUserId)
        return SupportServiceResult.Success(updated)
    }

    override suspend fun setStatus(
        ticketId: Long,
        agentUserId: Long,
        status: TicketStatus,
    ): SupportServiceResult<Ticket> {
        val ticket = repository.findTicket(ticketId) ?: return SupportServiceResult.Failure(SupportServiceError.TicketNotFound)
        val updated = repository.setStatus(ticketId = ticket.id, agentUserId = agentUserId, status = status)
        return SupportServiceResult.Success(updated)
    }

    override suspend fun reply(
        ticketId: Long,
        agentUserId: Long,
        text: String,
        attachments: String?,
    ): SupportServiceResult<SupportReplyResult> {
        val ticket = repository.findTicket(ticketId) ?: return SupportServiceResult.Failure(SupportServiceError.TicketNotFound)
        val result =
            repository.reply(
                ticketId = ticket.id,
                agentUserId = agentUserId,
                text = text,
                attachments = attachments,
            )
        return SupportServiceResult.Success(result)
    }

    override suspend fun setResolutionRating(
        ticketId: Long,
        userId: Long,
        rating: Int,
    ): SupportServiceResult<Ticket> {
        if (rating != 1 && rating != -1) {
            return SupportServiceResult.Failure(SupportServiceError.RatingNotAllowed)
        }
        val ticket = repository.findTicket(ticketId) ?: return SupportServiceResult.Failure(SupportServiceError.TicketNotFound)
        if (ticket.userId != userId) {
            return SupportServiceResult.Failure(SupportServiceError.TicketForbidden)
        }
        if (ticket.status != TicketStatus.ANSWERED && ticket.status != TicketStatus.CLOSED) {
            return SupportServiceResult.Failure(SupportServiceError.RatingNotAllowed)
        }
        val updated = repository.setResolutionRating(ticketId = ticketId, rating = rating)
        if (!updated) {
            return SupportServiceResult.Failure(SupportServiceError.RatingAlreadySet)
        }
        val refreshed = repository.findTicket(ticketId) ?: return SupportServiceResult.Failure(SupportServiceError.TicketNotFound)
        return SupportServiceResult.Success(refreshed)
    }

    override suspend fun getTicket(ticketId: Long): Ticket? =
        repository.findTicket(ticketId)
}
