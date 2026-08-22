package com.example.bot.data.support

import com.example.bot.support.GuestTicketThread
import com.example.bot.support.StaffSupportReadService
import com.example.bot.support.StaffTicketThread
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
import kotlin.coroutines.cancellation.CancellationException

class SupportServiceImpl(
    private val repository: SupportRepository,
) : SupportService,
    StaffSupportReadService {
    override suspend fun createTicket(
        clubId: Long,
        userId: Long,
        bookingId: UUID?,
        listEntryId: Long?,
        topic: TicketTopic,
        text: String,
        attachments: String?,
    ): SupportServiceResult<TicketWithMessage> =
        try {
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
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            SupportServiceResult.Failure(SupportServiceError.PersistenceFailure)
        }

    override suspend fun listMyTickets(userId: Long): List<TicketSummary> = repository.listTicketsByUser(userId)

    override suspend fun getMyTicket(
        ticketId: Long,
        userId: Long,
    ): SupportServiceResult<GuestTicketThread> =
        try {
            repository.findTicketThreadByUser(ticketId = ticketId, userId = userId)?.let {
                SupportServiceResult.Success(it)
            } ?: SupportServiceResult.Failure(SupportServiceError.TicketNotFound)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            SupportServiceResult.Failure(SupportServiceError.PersistenceFailure)
        }

    override suspend fun addGuestMessage(
        ticketId: Long,
        userId: Long,
        text: String,
        attachments: String?,
    ): SupportServiceResult<TicketMessage> =
        when (
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

    override suspend fun listTicketsForClub(
        clubId: Long,
        status: TicketStatus?,
    ): List<TicketSummary> = repository.listTicketsByClub(clubId = clubId, status = status)

    override suspend fun listStaffTicketsForClub(
        clubId: Long,
        status: TicketStatus?,
    ): SupportServiceResult<List<TicketSummary>> =
        readResult {
            repository.listTicketsByClub(clubId = clubId, status = status)
        }

    override suspend fun getStaffTicket(
        ticketId: Long,
        permittedClubIds: Set<Long>,
    ): SupportServiceResult<StaffTicketThread> =
        readResult {
            repository.findStaffTicketThread(
                ticketId = ticketId,
                permittedClubIds = permittedClubIds,
            ) ?: return@readResult null
        }.requireValueOrTicketNotFound()

    override suspend fun getStaffMutationTicket(
        ticketId: Long,
        permittedClubIds: Set<Long>,
    ): SupportServiceResult<Long> =
        if (permittedClubIds.isEmpty()) {
            SupportServiceResult.Failure(SupportServiceError.TicketForbidden)
        } else {
            getStaffMutationTicketInPermittedClubs(ticketId, permittedClubIds)
        }

    private suspend fun getStaffMutationTicketInPermittedClubs(
        ticketId: Long,
        permittedClubIds: Set<Long>,
    ): SupportServiceResult<Long> =
        try {
            repository
                .findTicketClubIdInClubs(
                    ticketId = ticketId,
                    permittedClubIds = permittedClubIds,
                )?.let { SupportServiceResult.Success(it) }
                ?: SupportServiceResult.Failure(SupportServiceError.TicketNotFound)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            SupportServiceResult.Failure(SupportServiceError.PersistenceFailure)
        }

    override suspend fun assign(
        ticketId: Long,
        agentUserId: Long,
    ): SupportServiceResult<Ticket> =
        staffMutationResult {
            repository.assign(ticketId = ticketId, agentUserId = agentUserId)
        }

    override suspend fun setStatus(
        ticketId: Long,
        agentUserId: Long,
        status: TicketStatus,
    ): SupportServiceResult<Ticket> =
        staffMutationResult {
            repository.setStatus(
                ticketId = ticketId,
                agentUserId = agentUserId,
                status = status,
            )
        }

    override suspend fun reply(
        ticketId: Long,
        agentUserId: Long,
        text: String,
        attachments: String?,
    ): SupportServiceResult<SupportReplyResult> =
        staffMutationResult {
            repository.reply(
                ticketId = ticketId,
                agentUserId = agentUserId,
                text = text,
                attachments = attachments,
            )
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

    override suspend fun getTicket(ticketId: Long): Ticket? = repository.findTicket(ticketId)

    private suspend fun <T> readResult(block: suspend () -> T): SupportServiceResult<T> =
        try {
            SupportServiceResult.Success(block())
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            SupportServiceResult.Failure(SupportServiceError.PersistenceFailure)
        }

    private suspend fun <T> staffMutationResult(block: suspend () -> StaffMutationResult<T>): SupportServiceResult<T> =
        try {
            when (val result = block()) {
                is StaffMutationResult.Success -> SupportServiceResult.Success(result.value)
                is StaffMutationResult.Failure -> SupportServiceResult.Failure(result.reason.toServiceError())
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            SupportServiceResult.Failure(SupportServiceError.PersistenceFailure)
        }

    private fun <T : Any> SupportServiceResult<T?>.requireValueOrTicketNotFound(): SupportServiceResult<T> =
        when (this) {
            is SupportServiceResult.Success ->
                value?.let { SupportServiceResult.Success(it) }
                    ?: SupportServiceResult.Failure(SupportServiceError.TicketNotFound)
            is SupportServiceResult.Failure -> this
        }

    private fun StaffMutationFailure.toServiceError(): SupportServiceError =
        when (this) {
            StaffMutationFailure.NotFound -> SupportServiceError.TicketNotFound
            StaffMutationFailure.Forbidden -> SupportServiceError.TicketForbidden
            StaffMutationFailure.InvalidState -> SupportServiceError.InvalidState
        }
}
