package com.example.bot.data.support

import com.example.bot.support.SupportReplyResult
import com.example.bot.support.Ticket
import com.example.bot.support.TicketMessage
import com.example.bot.support.TicketSenderType
import com.example.bot.support.TicketStatus
import com.example.bot.support.TicketSummary
import com.example.bot.support.TicketTopic
import com.example.bot.support.TicketWithMessage
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

private const val PREVIEW_LIMIT = 140

class SupportRepository(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun createTicket(
        clubId: Long,
        userId: Long,
        bookingId: UUID?,
        listEntryId: Long?,
        topic: TicketTopic,
        text: String,
        attachments: String?,
    ): TicketWithMessage =
        newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            val now = clock.instant().atOffset(ZoneOffset.UTC)
            val ticketId =
                TicketsTable.insert {
                    it[TicketsTable.clubId] = clubId
                    it[TicketsTable.userId] = userId
                    it[TicketsTable.bookingId] = bookingId
                    it[TicketsTable.listEntryId] = listEntryId
                    it[TicketsTable.topic] = topic.wire
                    it[TicketsTable.status] = TicketStatus.OPENED.wire
                    it[TicketsTable.createdAt] = now
                    it[TicketsTable.updatedAt] = now
                    it[TicketsTable.lastAgentId] = null
                    it[TicketsTable.resolutionRating] = null
                }[TicketsTable.id]
            val messageId =
                TicketMessagesTable.insert {
                    it[TicketMessagesTable.ticketId] = ticketId
                    it[TicketMessagesTable.senderType] = TicketSenderType.GUEST.wire
                    it[TicketMessagesTable.text] = text
                    it[TicketMessagesTable.attachments] = attachments
                    it[TicketMessagesTable.createdAt] = now
                }[TicketMessagesTable.id]
            val ticket =
                Ticket(
                    id = ticketId,
                    clubId = clubId,
                    userId = userId,
                    bookingId = bookingId,
                    listEntryId = listEntryId,
                    topic = topic,
                    status = TicketStatus.OPENED,
                    createdAt = now.toInstant(),
                    updatedAt = now.toInstant(),
                    lastAgentId = null,
                    resolutionRating = null,
                )
            val message =
                TicketMessage(
                    id = messageId,
                    ticketId = ticketId,
                    senderType = TicketSenderType.GUEST,
                    text = text,
                    attachments = attachments,
                    createdAt = now.toInstant(),
                )
            TicketWithMessage(ticket = ticket, initialMessage = message)
        }

    suspend fun findTicket(id: Long): Ticket? =
        newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            TicketsTable
                .selectAll()
                .where { TicketsTable.id eq id }
                .map { toTicket(it) }
                .singleOrNull()
        }

    suspend fun listTicketsByUser(userId: Long): List<TicketSummary> =
        newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            val ticketRows =
                TicketsTable
                    .selectAll()
                    .where { TicketsTable.userId eq userId }
                    .orderBy(TicketsTable.updatedAt to SortOrder.DESC)
                    .toList()
            buildSummaries(ticketRows)
        }

    suspend fun listTicketsByClub(
        clubId: Long,
        status: TicketStatus?,
    ): List<TicketSummary> =
        newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            val baseQuery = TicketsTable.selectAll().where { TicketsTable.clubId eq clubId }
            val filtered =
                if (status == null) {
                    baseQuery
                } else {
                    baseQuery.andWhere { TicketsTable.status eq status.wire }
                }
            val ticketRows =
                filtered
                    .orderBy(TicketsTable.updatedAt to SortOrder.DESC)
                    .toList()
            buildSummaries(ticketRows)
        }

    suspend fun addGuestMessage(
        ticketId: Long,
        text: String,
        attachments: String?,
    ): TicketMessage =
        newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            val now = clock.instant().atOffset(ZoneOffset.UTC)
            val ticketRow =
                TicketsTable
                    .selectAll()
                    .where { TicketsTable.id eq ticketId }
                    .single()
            val currentStatus = toStatus(ticketRow)
            val nextStatus = if (currentStatus == TicketStatus.ANSWERED) TicketStatus.OPENED else currentStatus
            TicketsTable.update({ TicketsTable.id eq ticketId }) {
                it[TicketsTable.updatedAt] = now
                it[TicketsTable.status] = nextStatus.wire
            }
            val messageId =
                TicketMessagesTable.insert {
                    it[TicketMessagesTable.ticketId] = ticketId
                    it[TicketMessagesTable.senderType] = TicketSenderType.GUEST.wire
                    it[TicketMessagesTable.text] = text
                    it[TicketMessagesTable.attachments] = attachments
                    it[TicketMessagesTable.createdAt] = now
                }[TicketMessagesTable.id]
            TicketMessage(
                id = messageId,
                ticketId = ticketId,
                senderType = TicketSenderType.GUEST,
                text = text,
                attachments = attachments,
                createdAt = now.toInstant(),
            )
        }

    suspend fun assign(
        ticketId: Long,
        agentUserId: Long,
    ): Ticket =
        newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            val now = clock.instant().atOffset(ZoneOffset.UTC)
            TicketsTable.update({ TicketsTable.id eq ticketId }) {
                it[TicketsTable.status] = TicketStatus.IN_PROGRESS.wire
                it[TicketsTable.lastAgentId] = agentUserId
                it[TicketsTable.updatedAt] = now
            }
            TicketsTable
                .selectAll()
                .where { TicketsTable.id eq ticketId }
                .map { toTicket(it) }
                .single()
        }

    suspend fun setStatus(
        ticketId: Long,
        agentUserId: Long,
        status: TicketStatus,
    ): Ticket =
        newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            val now = clock.instant().atOffset(ZoneOffset.UTC)
            TicketsTable.update({ TicketsTable.id eq ticketId }) {
                it[TicketsTable.status] = status.wire
                it[TicketsTable.lastAgentId] = agentUserId
                it[TicketsTable.updatedAt] = now
            }
            TicketsTable
                .selectAll()
                .where { TicketsTable.id eq ticketId }
                .map { toTicket(it) }
                .single()
        }

    suspend fun reply(
        ticketId: Long,
        agentUserId: Long,
        text: String,
        attachments: String?,
    ): SupportReplyResult =
        newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            val now = clock.instant().atOffset(ZoneOffset.UTC)
            val messageId =
                TicketMessagesTable.insert {
                    it[TicketMessagesTable.ticketId] = ticketId
                    it[TicketMessagesTable.senderType] = TicketSenderType.AGENT.wire
                    it[TicketMessagesTable.text] = text
                    it[TicketMessagesTable.attachments] = attachments
                    it[TicketMessagesTable.createdAt] = now
                }[TicketMessagesTable.id]
            TicketsTable.update({ TicketsTable.id eq ticketId }) {
                it[TicketsTable.status] = TicketStatus.ANSWERED.wire
                it[TicketsTable.lastAgentId] = agentUserId
                it[TicketsTable.updatedAt] = now
            }
            val ticket =
                TicketsTable
                    .selectAll()
                    .where { TicketsTable.id eq ticketId }
                    .map { toTicket(it) }
                    .single()
            val replyMessage =
                TicketMessage(
                    id = messageId,
                    ticketId = ticketId,
                    senderType = TicketSenderType.AGENT,
                    text = text,
                    attachments = attachments,
                    createdAt = now.toInstant(),
                )
            SupportReplyResult(ticket = ticket, replyMessage = replyMessage)
        }

    suspend fun setResolutionRating(
        ticketId: Long,
        rating: Int,
    ): Boolean =
        newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            val now = clock.instant().atOffset(ZoneOffset.UTC)
            val updated =
                TicketsTable.update({
                    (TicketsTable.id eq ticketId) and TicketsTable.resolutionRating.isNull()
                }) {
                    it[TicketsTable.resolutionRating] = rating.toShort()
                    it[TicketsTable.updatedAt] = now
                }
            updated > 0
        }

    private fun buildSummaries(ticketRows: List<ResultRow>): List<TicketSummary> {
        if (ticketRows.isEmpty()) {
            return emptyList()
        }
        val ticketIds = ticketRows.map { it[TicketsTable.id] }
        val lastMessages = loadLastMessages(ticketIds)
        return ticketRows.map { row ->
            val ticketId = row[TicketsTable.id]
            val last = lastMessages[ticketId]
            TicketSummary(
                id = ticketId,
                clubId = row[TicketsTable.clubId],
                topic = toTopic(row),
                status = toStatus(row),
                updatedAt = row[TicketsTable.updatedAt].toInstant(),
                lastMessagePreview = last?.text?.take(PREVIEW_LIMIT),
                lastSenderType = last?.senderType,
            )
        }
    }

    private fun loadLastMessages(ticketIds: List<Long>): Map<Long, LastMessage> {
        val messages =
            TicketMessagesTable
                .selectAll()
                .where { TicketMessagesTable.ticketId inList ticketIds }
                .orderBy(
                    TicketMessagesTable.createdAt to SortOrder.DESC,
                    TicketMessagesTable.id to SortOrder.DESC,
                )
        val result = LinkedHashMap<Long, LastMessage>()
        for (row in messages) {
            val ticketId = row[TicketMessagesTable.ticketId]
            if (!result.containsKey(ticketId)) {
                result[ticketId] =
                    LastMessage(
                        text = row[TicketMessagesTable.text],
                        senderType = toSender(row),
                    )
            }
        }
        return result
    }

    private fun toTicket(row: ResultRow): Ticket =
        Ticket(
            id = row[TicketsTable.id],
            clubId = row[TicketsTable.clubId],
            userId = row[TicketsTable.userId],
            bookingId = row[TicketsTable.bookingId],
            listEntryId = row[TicketsTable.listEntryId],
            topic = toTopic(row),
            status = toStatus(row),
            createdAt = row[TicketsTable.createdAt].toInstant(),
            updatedAt = row[TicketsTable.updatedAt].toInstant(),
            lastAgentId = row[TicketsTable.lastAgentId],
            resolutionRating = row[TicketsTable.resolutionRating]?.toInt(),
        )

    private fun toTopic(row: ResultRow): TicketTopic =
        requireNotNull(TicketTopic.fromWire(row[TicketsTable.topic])) {
            "Unknown ticket topic: ${row[TicketsTable.topic]}"
        }

    private fun toStatus(row: ResultRow): TicketStatus =
        requireNotNull(TicketStatus.fromWire(row[TicketsTable.status])) {
            "Unknown ticket status: ${row[TicketsTable.status]}"
        }

    private fun toSender(row: ResultRow): TicketSenderType =
        requireNotNull(TicketSenderType.fromWire(row[TicketMessagesTable.senderType])) {
            "Unknown sender type: ${row[TicketMessagesTable.senderType]}"
        }

    private data class LastMessage(
        val text: String,
        val senderType: TicketSenderType,
    )
}
