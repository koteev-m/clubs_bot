package com.example.bot.data.support

import com.example.bot.support.GuestTicketDetails
import com.example.bot.support.GuestTicketMessage
import com.example.bot.support.GuestTicketThread
import com.example.bot.support.StaffTicketDetails
import com.example.bot.support.StaffTicketMessage
import com.example.bot.support.StaffTicketThread
import com.example.bot.support.SupportReplyDeliveryFailureCode
import com.example.bot.support.SupportReplyDeliveryStatus
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
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
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
    private val auditFingerprintFactory: (String, Long) -> String = { action, ticketId ->
        "SUPPORT_TICKET:$action:$ticketId:${UUID.randomUUID()}"
    },
) {
    private val staffMutations =
        SupportMutationPersistence(
            db = db,
            clock = clock,
            auditFingerprintFactory = auditFingerprintFactory,
            transactionContext = Dispatchers.IO,
        )
    internal val replyDeliveries =
        SupportReplyDeliveryPersistence(
            db = db,
            clock = clock,
            transactionContext = Dispatchers.IO,
        )

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
                    it[TicketsTable.status] = TicketStatus.NEW.wire
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
                    status = TicketStatus.NEW,
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
                    .orderBy(TicketsTable.updatedAt to SortOrder.DESC, TicketsTable.id to SortOrder.DESC)
                    .toList()
            buildSummaries(ticketRows)
        }

    suspend fun findTicketThreadByUser(
        ticketId: Long,
        userId: Long,
    ): GuestTicketThread? =
        newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            val ticketRow =
                TicketsTable
                    .selectAll()
                    .where {
                        (TicketsTable.id eq ticketId) and
                            (TicketsTable.userId eq userId)
                    }.singleOrNull()
                    ?: return@newSuspendedTransaction null
            val messages =
                TicketMessagesTable
                    .selectAll()
                    .where { TicketMessagesTable.ticketId eq ticketId }
                    .orderBy(
                        TicketMessagesTable.createdAt to SortOrder.ASC,
                        TicketMessagesTable.id to SortOrder.ASC,
                    ).map { toGuestTicketMessage(it) }
            if (messages.isEmpty()) {
                return@newSuspendedTransaction null
            }
            GuestTicketThread(
                ticket =
                    GuestTicketDetails(
                        id = ticketRow[TicketsTable.id],
                        clubId = ticketRow[TicketsTable.clubId],
                        topic = toTopic(ticketRow),
                        status = toStatus(ticketRow),
                        createdAt = ticketRow[TicketsTable.createdAt].toInstant(),
                        updatedAt = ticketRow[TicketsTable.updatedAt].toInstant(),
                    ),
                messages = messages,
            )
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
                    .orderBy(TicketsTable.updatedAt to SortOrder.DESC, TicketsTable.id to SortOrder.DESC)
                    .toList()
            buildSummaries(ticketRows)
        }

    suspend fun findStaffTicketThread(
        ticketId: Long,
        permittedClubIds: Set<Long>,
    ): StaffTicketThread? =
        newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            if (permittedClubIds.isEmpty()) {
                return@newSuspendedTransaction null
            }
            val ticketRow =
                TicketsTable
                    .selectAll()
                    .where {
                        (TicketsTable.id eq ticketId) and
                            (TicketsTable.clubId inList permittedClubIds)
                    }.singleOrNull()
                    ?: return@newSuspendedTransaction null
            val messageRows =
                TicketMessagesTable
                    .selectAll()
                    .where { TicketMessagesTable.ticketId eq ticketId }
                    .orderBy(
                        TicketMessagesTable.createdAt to SortOrder.ASC,
                        TicketMessagesTable.id to SortOrder.ASC,
                    ).toList()
            if (messageRows.isEmpty()) {
                return@newSuspendedTransaction null
            }
            val deliveryStatuses = loadDeliveryStatuses(ticketId, messageRows)
            val messages =
                messageRows.map { row ->
                    toStaffTicketMessage(
                        row = row,
                        deliveryStatus = deliveryStatuses[row[TicketMessagesTable.id]],
                    )
                }
            StaffTicketThread(
                ticket =
                    StaffTicketDetails(
                        id = ticketRow[TicketsTable.id],
                        clubId = ticketRow[TicketsTable.clubId],
                        topic = toTopic(ticketRow),
                        status = toStatus(ticketRow),
                        createdAt = ticketRow[TicketsTable.createdAt].toInstant(),
                        updatedAt = ticketRow[TicketsTable.updatedAt].toInstant(),
                    ),
                messages = messages,
            )
        }

    suspend fun findTicketClubIdInClubs(
        ticketId: Long,
        permittedClubIds: Set<Long>,
    ): Long? =
        newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            if (permittedClubIds.isEmpty()) {
                return@newSuspendedTransaction null
            }
            TicketsTable
                .select(TicketsTable.clubId)
                .where {
                    (TicketsTable.id eq ticketId) and
                        (TicketsTable.clubId inList permittedClubIds)
                }.singleOrNull()
                ?.get(TicketsTable.clubId)
        }

    suspend fun addGuestMessage(
        ticketId: Long,
        userId: Long,
        text: String,
        attachments: String?,
    ): AddGuestMessageResult = staffMutations.addGuestMessage(ticketId, userId, text, attachments)

    suspend fun assign(
        ticketId: Long,
        agentUserId: Long,
    ): StaffMutationResult<Ticket> = staffMutations.assign(ticketId, agentUserId)

    suspend fun resolve(
        ticketId: Long,
        agentUserId: Long,
    ): StaffMutationResult<Ticket> = staffMutations.resolve(ticketId, agentUserId)

    suspend fun close(
        ticketId: Long,
        agentUserId: Long,
    ): StaffMutationResult<Ticket> = staffMutations.close(ticketId, agentUserId)

    suspend fun setStatus(
        ticketId: Long,
        agentUserId: Long,
        status: TicketStatus,
    ): StaffMutationResult<Ticket> = staffMutations.setStatus(ticketId, agentUserId, status)

    suspend fun reply(
        ticketId: Long,
        agentUserId: Long,
        text: String,
        attachments: String?,
    ): StaffMutationResult<SupportReplyResult> = staffMutations.reply(ticketId, agentUserId, text, attachments)

    suspend fun setResolutionRating(
        ticketId: Long,
        userId: Long,
        rating: Int,
    ): SetResolutionRatingResult =
        newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            val now = clock.instant().atOffset(ZoneOffset.UTC)
            val updated =
                TicketsTable.update({
                    (TicketsTable.id eq ticketId) and
                        (TicketsTable.userId eq userId) and
                        TicketsTable.resolutionRating.isNull() and
                        (
                            TicketsTable.status inList
                                listOf(TicketStatus.ANSWERED.wire, TicketStatus.CLOSED.wire)
                        )
                }) {
                    it[TicketsTable.resolutionRating] = rating.toShort()
                    it[TicketsTable.updatedAt] = now
                }
            if (updated > 0) {
                val ticket =
                    TicketsTable
                        .selectAll()
                        .where { TicketsTable.id eq ticketId }
                        .map { toTicket(it) }
                        .singleOrNull()
                        ?: return@newSuspendedTransaction SetResolutionRatingResult.Failure(
                            SetResolutionRatingFailure.NotFound,
                        )
                return@newSuspendedTransaction SetResolutionRatingResult.Success(ticket)
            }
            val ticketRow =
                TicketsTable
                    .selectAll()
                    .where { TicketsTable.id eq ticketId }
                    .singleOrNull()
                    ?: return@newSuspendedTransaction SetResolutionRatingResult.Failure(
                        SetResolutionRatingFailure.NotFound,
                    )
            if (ticketRow[TicketsTable.userId] != userId) {
                return@newSuspendedTransaction SetResolutionRatingResult.Failure(SetResolutionRatingFailure.Forbidden)
            }
            if (ticketRow[TicketsTable.resolutionRating] != null) {
                return@newSuspendedTransaction SetResolutionRatingResult.Failure(SetResolutionRatingFailure.AlreadySet)
            }
            val status = toStatus(ticketRow)
            if (status != TicketStatus.ANSWERED && status != TicketStatus.CLOSED) {
                return@newSuspendedTransaction SetResolutionRatingResult.Failure(SetResolutionRatingFailure.NotAllowed)
            }
            SetResolutionRatingResult.Failure(SetResolutionRatingFailure.AlreadySet)
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
        if (ticketIds.isEmpty()) {
            return emptyMap()
        }
        val messages =
            TicketMessagesTable
                .selectAll()
                .where { TicketMessagesTable.ticketId inList ticketIds }
                .orderBy(
                    TicketMessagesTable.ticketId to SortOrder.ASC,
                    TicketMessagesTable.createdAt to SortOrder.DESC,
                    TicketMessagesTable.id to SortOrder.DESC,
                )
        val result = LinkedHashMap<Long, LastMessage>()
        for (row in messages) {
            val ticketId = row[TicketMessagesTable.ticketId]
            result.putIfAbsent(
                ticketId,
                LastMessage(
                    text = row[TicketMessagesTable.text],
                    senderType = toSender(row),
                ),
            )
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

    private fun toGuestTicketMessage(row: ResultRow): GuestTicketMessage =
        GuestTicketMessage(
            id = row[TicketMessagesTable.id],
            senderType = toSender(row),
            text = row[TicketMessagesTable.text],
            attachments = row[TicketMessagesTable.attachments],
            createdAt = row[TicketMessagesTable.createdAt].toInstant(),
        )

    private data class LastMessage(
        val text: String,
        val senderType: TicketSenderType,
    )
}

suspend fun SupportRepository.claimReplyDelivery(deliveryId: Long): SupportReplyDeliveryClaimResult =
    replyDeliveries.claim(deliveryId)

suspend fun SupportRepository.finalizeReplyDelivery(
    deliveryId: Long,
    resultStatus: SupportReplyDeliveryStatus,
    failureCode: SupportReplyDeliveryFailureCode?,
): SupportReplyDeliveryFinalizationResult =
    replyDeliveries.finalize(
        deliveryId = deliveryId,
        resultStatus = resultStatus,
        failureCode = failureCode,
    )

suspend fun SupportRepository.findReplyDelivery(deliveryId: Long): SupportReplyDeliveryRecord? =
    replyDeliveries.find(deliveryId)

private fun loadDeliveryStatuses(
    ticketId: Long,
    messageRows: List<ResultRow>,
): Map<Long, SupportReplyDeliveryStatus> {
    val agentMessageIds =
        messageRows
            .filter { row -> row[TicketMessagesTable.senderType] == TicketSenderType.AGENT.wire }
            .map { row -> row[TicketMessagesTable.id] }
    if (agentMessageIds.isEmpty()) {
        return emptyMap()
    }
    return SupportReplyDeliveriesTable
        .selectAll()
        .where {
            (SupportReplyDeliveriesTable.ticketId eq ticketId) and
                (SupportReplyDeliveriesTable.replyMessageId inList agentMessageIds)
        }.associate { row ->
            val statusWire = row[SupportReplyDeliveriesTable.status]
            row[SupportReplyDeliveriesTable.replyMessageId] to
                requireNotNull(SupportReplyDeliveryStatus.fromWire(statusWire)) {
                    "Unknown support reply delivery status"
                }
        }
}

private fun toStaffTicketMessage(
    row: ResultRow,
    deliveryStatus: SupportReplyDeliveryStatus?,
): StaffTicketMessage {
    val senderType =
        requireNotNull(TicketSenderType.fromWire(row[TicketMessagesTable.senderType])) {
            "Unknown support ticket message sender type"
        }
    return StaffTicketMessage(
        id = row[TicketMessagesTable.id],
        senderType = senderType,
        text = row[TicketMessagesTable.text],
        attachments = row[TicketMessagesTable.attachments],
        createdAt = row[TicketMessagesTable.createdAt].toInstant(),
        deliveryStatus = if (senderType == TicketSenderType.AGENT) deliveryStatus else null,
    )
}

sealed class AddGuestMessageResult {
    data class Success(
        val message: TicketMessage,
    ) : AddGuestMessageResult()

    data class Failure(
        val reason: AddGuestMessageFailure,
    ) : AddGuestMessageResult()
}

enum class AddGuestMessageFailure {
    NotFound,
    Forbidden,
    Closed,
    InvalidState,
}

sealed class StaffMutationResult<out T> {
    data class Success<T>(
        val value: T,
    ) : StaffMutationResult<T>()

    data class Failure(
        val reason: StaffMutationFailure,
    ) : StaffMutationResult<Nothing>()
}

enum class StaffMutationFailure {
    NotFound,
    Forbidden,
    InvalidState,
}

sealed class SetResolutionRatingResult {
    data class Success(
        val ticket: Ticket,
    ) : SetResolutionRatingResult()

    data class Failure(
        val reason: SetResolutionRatingFailure,
    ) : SetResolutionRatingResult()
}

enum class SetResolutionRatingFailure {
    NotFound,
    Forbidden,
    NotAllowed,
    AlreadySet,
}
