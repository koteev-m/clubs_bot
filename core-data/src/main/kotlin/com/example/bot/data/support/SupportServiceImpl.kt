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
        return when (
            val result =
                repository.addGuestMessage(
                    ticketId = ticketId,
                    userId = userId,
                    text = text,
                    attachments = attachments,
                )
        ) {
            is AddGuestMessageResult.Success -> SupportServiceResult.Success(result.message)
            is AddGuestMessageResult.Failure ->
                when (result.reason) {
                    AddGuestMessageFailure.NotFound ->
                        SupportServiceResult.Failure(SupportServiceError.TicketNotFound)
                    AddGuestMessageFailure.Forbidden ->
                        SupportServiceResult.Failure(SupportServiceError.TicketForbidden)
                    AddGuestMessageFailure.Closed ->
                        SupportServiceResult.Failure(SupportServiceError.TicketClosed)
                }
        }
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
        val updated = repository.assign(ticketId = ticketId, agentUserId = agentUserId)
            ?: return SupportServiceResult.Failure(SupportServiceError.TicketNotFound)
        return SupportServiceResult.Success(updated)
    }

    override suspend fun setStatus(
        ticketId: Long,
        agentUserId: Long,
        status: TicketStatus,
    ): SupportServiceResult<Ticket> {
        val updated =
            repository.setStatus(
                ticketId = ticketId,
                agentUserId = agentUserId,
                status = status,
            )
            ?: return SupportServiceResult.Failure(SupportServiceError.TicketNotFound)
        return SupportServiceResult.Success(updated)
    }

    override suspend fun reply(
        ticketId: Long,
        agentUserId: Long,
        text: String,
        attachments: String?,
    ): SupportServiceResult<SupportReplyResult> {
        val result =
            repository.reply(
                ticketId = ticketId,
                agentUserId = agentUserId,
                text = text,
                attachments = attachments,
            ) ?: return SupportServiceResult.Failure(SupportServiceError.TicketNotFound)
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
        return when (
            val result =
                repository.setResolutionRating(
                    ticketId = ticketId,
                    userId = userId,
                    rating = rating,
                )
        ) {
            is SetResolutionRatingResult.Success -> SupportServiceResult.Success(result.ticket)
            is SetResolutionRatingResult.Failure ->
                when (result.reason) {
                    SetResolutionRatingFailure.NotFound ->
                        SupportServiceResult.Failure(SupportServiceError.TicketNotFound)
                    SetResolutionRatingFailure.Forbidden ->
                        SupportServiceResult.Failure(SupportServiceError.TicketForbidden)
                    SetResolutionRatingFailure.NotAllowed ->
                        SupportServiceResult.Failure(SupportServiceError.RatingNotAllowed)
                    SetResolutionRatingFailure.AlreadySet ->
                        SupportServiceResult.Failure(SupportServiceError.RatingAlreadySet)
                }
        }
    }

    override suspend fun getTicket(ticketId: Long): Ticket? =
        repository.findTicket(ticketId)
}
