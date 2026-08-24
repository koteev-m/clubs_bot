package com.example.bot.data.support

import com.example.bot.audit.StandardAuditAction
import com.example.bot.audit.StandardAuditEntityType
import com.example.bot.data.audit.AuditLogTable
import com.example.bot.support.SupportReplyDeliveryFailureCode
import com.example.bot.support.SupportReplyDeliveryStatus
import com.example.bot.support.TicketSenderType
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.coroutines.CoroutineContext

data class ClaimedSupportReplyDelivery(
    val id: Long,
    val replyMessageId: Long,
    val ticketId: Long,
    val clubId: Long,
    val recipientUserId: Long,
    val recipientTelegramUserId: Long?,
    val actingStaffUserId: Long,
    val actingRole: String,
    val persistedReplyText: String,
)

sealed interface SupportReplyDeliveryClaimResult {
    data class Claimed(
        val delivery: ClaimedSupportReplyDelivery,
    ) : SupportReplyDeliveryClaimResult

    data object NotClaimed : SupportReplyDeliveryClaimResult
}

sealed interface SupportReplyDeliveryFinalizationResult {
    data object Finalized : SupportReplyDeliveryFinalizationResult

    data object NotFinalized : SupportReplyDeliveryFinalizationResult
}

data class SupportReplyDeliveryRecord(
    val id: Long,
    val replyMessageId: Long,
    val ticketId: Long,
    val recipientUserId: Long,
    val actingStaffUserId: Long,
    val actingRole: String,
    val status: SupportReplyDeliveryStatus,
    val failureCode: SupportReplyDeliveryFailureCode?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant?,
)

internal class SupportReplyDeliveryPersistence(
    private val db: Database,
    private val clock: Clock,
    private val transactionContext: CoroutineContext,
) {
    suspend fun claim(deliveryId: Long): SupportReplyDeliveryClaimResult =
        newSuspendedTransaction(
            context = transactionContext,
            db = db,
            transactionIsolation = Connection.TRANSACTION_READ_COMMITTED,
        ) {
            repetitionAttempts = 1
            val claimedAt = clock.instant().atOffset(ZoneOffset.UTC)
            val updated =
                SupportReplyDeliveriesTable.update({
                    (SupportReplyDeliveriesTable.id eq deliveryId) and
                        (SupportReplyDeliveriesTable.status eq SupportReplyDeliveryStatus.PENDING.wire)
                }) {
                    it[status] = SupportReplyDeliveryStatus.SENDING.wire
                    it[failureCode] = null
                    it[updatedAt] = claimedAt
                    it[completedAt] = null
                }
            if (updated != 1) {
                return@newSuspendedTransaction SupportReplyDeliveryClaimResult.NotClaimed
            }
            val deliveryRow =
                SupportReplyDeliveriesTable
                    .selectAll()
                    .where { SupportReplyDeliveriesTable.id eq deliveryId }
                    .single()
            val ticketId = deliveryRow[SupportReplyDeliveriesTable.ticketId]
            val replyMessageId = deliveryRow[SupportReplyDeliveriesTable.replyMessageId]
            val recipientUserId = deliveryRow[SupportReplyDeliveriesTable.recipientUserId]
            val ticketRow =
                TicketsTable
                    .selectAll()
                    .where { TicketsTable.id eq ticketId }
                    .single()
            check(ticketRow[TicketsTable.userId] == recipientUserId) {
                "Support reply delivery recipient does not own the ticket"
            }
            val messageRow =
                TicketMessagesTable
                    .selectAll()
                    .where {
                        (TicketMessagesTable.id eq replyMessageId) and
                            (TicketMessagesTable.ticketId eq ticketId)
                    }.single()
            check(messageRow[TicketMessagesTable.senderType] == TicketSenderType.AGENT.wire) {
                "Support reply delivery message is not an agent reply"
            }
            val recipientTelegramUserId =
                SupportDeliveryUsersTable
                    .selectAll()
                    .where { SupportDeliveryUsersTable.id eq recipientUserId }
                    .singleOrNull()
                    ?.get(SupportDeliveryUsersTable.telegramUserId)
            SupportReplyDeliveryClaimResult.Claimed(
                ClaimedSupportReplyDelivery(
                    id = deliveryId,
                    replyMessageId = replyMessageId,
                    ticketId = ticketId,
                    clubId = ticketRow[TicketsTable.clubId],
                    recipientUserId = recipientUserId,
                    recipientTelegramUserId = recipientTelegramUserId,
                    actingStaffUserId = deliveryRow[SupportReplyDeliveriesTable.actingStaffUserId],
                    actingRole = deliveryRow[SupportReplyDeliveriesTable.actingRole],
                    persistedReplyText = messageRow[TicketMessagesTable.text],
                ),
            )
        }

    suspend fun finalize(
        deliveryId: Long,
        resultStatus: SupportReplyDeliveryStatus,
        failureCode: SupportReplyDeliveryFailureCode?,
    ): SupportReplyDeliveryFinalizationResult {
        requireTerminalResult(resultStatus, failureCode)
        return newSuspendedTransaction(
            context = transactionContext,
            db = db,
            transactionIsolation = Connection.TRANSACTION_READ_COMMITTED,
        ) {
            repetitionAttempts = 1
            val deliveryRow =
                SupportReplyDeliveriesTable
                    .selectAll()
                    .where { SupportReplyDeliveriesTable.id eq deliveryId }
                    .forUpdate()
                    .singleOrNull()
                    ?: return@newSuspendedTransaction SupportReplyDeliveryFinalizationResult.NotFinalized
            if (deliveryRow[SupportReplyDeliveriesTable.status] != SupportReplyDeliveryStatus.SENDING.wire) {
                return@newSuspendedTransaction SupportReplyDeliveryFinalizationResult.NotFinalized
            }
            val completedAt = clock.instant().atOffset(ZoneOffset.UTC)
            val updated =
                SupportReplyDeliveriesTable.update({
                    (SupportReplyDeliveriesTable.id eq deliveryId) and
                        (SupportReplyDeliveriesTable.status eq SupportReplyDeliveryStatus.SENDING.wire)
                }) {
                    it[status] = resultStatus.wire
                    it[SupportReplyDeliveriesTable.failureCode] = failureCode?.wire
                    it[updatedAt] = completedAt
                    it[SupportReplyDeliveriesTable.completedAt] = completedAt
                }
            check(updated == 1) { "Support reply delivery finalization did not update exactly one row" }
            appendResultAudit(
                deliveryRow = deliveryRow,
                resultStatus = resultStatus,
                failureCode = failureCode,
                completedAt = completedAt,
            )
            SupportReplyDeliveryFinalizationResult.Finalized
        }
    }

    suspend fun find(deliveryId: Long): SupportReplyDeliveryRecord? =
        newSuspendedTransaction(context = transactionContext, db = db) {
            SupportReplyDeliveriesTable
                .selectAll()
                .where { SupportReplyDeliveriesTable.id eq deliveryId }
                .singleOrNull()
                ?.let(::toRecord)
        }

    private fun appendResultAudit(
        deliveryRow: ResultRow,
        resultStatus: SupportReplyDeliveryStatus,
        failureCode: SupportReplyDeliveryFailureCode?,
        completedAt: java.time.OffsetDateTime,
    ) {
        val deliveryId = deliveryRow[SupportReplyDeliveriesTable.id]
        val ticketId = deliveryRow[SupportReplyDeliveriesTable.ticketId]
        val deliveryClubId =
            TicketsTable
                .selectAll()
                .where { TicketsTable.id eq ticketId }
                .single()[TicketsTable.clubId]
        val metadata =
            buildJsonObject {
                put("result", resultStatus.wire)
                put("reply_message_id", deliveryRow[SupportReplyDeliveriesTable.replyMessageId])
                if (failureCode != null) {
                    put("failure_code", failureCode.wire)
                }
            }
        AuditLogTable.insert {
            it[createdAt] = completedAt
            it[clubId] = deliveryClubId
            it[nightId] = null
            it[actorUserId] = deliveryRow[SupportReplyDeliveriesTable.actingStaffUserId]
            it[actorRole] = deliveryRow[SupportReplyDeliveriesTable.actingRole]
            it[subjectUserId] = null
            it[entityType] = StandardAuditEntityType.SUPPORT_TICKET.value
            it[entityId] = ticketId
            it[action] = StandardAuditAction.SUPPORT_DELIVERY_RESULT.value
            it[fingerprint] = "SUPPORT_TICKET:SUPPORT_DELIVERY_RESULT:$deliveryId"
            it[metadataJson] = metadata.toString()
        }
    }

    private fun toRecord(row: ResultRow): SupportReplyDeliveryRecord {
        val statusWire = row[SupportReplyDeliveriesTable.status]
        val failureWire = row[SupportReplyDeliveriesTable.failureCode]
        return SupportReplyDeliveryRecord(
            id = row[SupportReplyDeliveriesTable.id],
            replyMessageId = row[SupportReplyDeliveriesTable.replyMessageId],
            ticketId = row[SupportReplyDeliveriesTable.ticketId],
            recipientUserId = row[SupportReplyDeliveriesTable.recipientUserId],
            actingStaffUserId = row[SupportReplyDeliveriesTable.actingStaffUserId],
            actingRole = row[SupportReplyDeliveriesTable.actingRole],
            status = requireNotNull(SupportReplyDeliveryStatus.fromWire(statusWire)),
            failureCode = failureWire?.let { requireNotNull(SupportReplyDeliveryFailureCode.fromWire(it)) },
            createdAt = row[SupportReplyDeliveriesTable.createdAt].toInstant(),
            updatedAt = row[SupportReplyDeliveriesTable.updatedAt].toInstant(),
            completedAt = row[SupportReplyDeliveriesTable.completedAt]?.toInstant(),
        )
    }

    private fun requireTerminalResult(
        resultStatus: SupportReplyDeliveryStatus,
        failureCode: SupportReplyDeliveryFailureCode?,
    ) {
        when (resultStatus) {
            SupportReplyDeliveryStatus.DELIVERED -> require(failureCode == null)
            SupportReplyDeliveryStatus.FAILED ->
                require(
                    failureCode == SupportReplyDeliveryFailureCode.RECIPIENT_UNAVAILABLE ||
                        failureCode == SupportReplyDeliveryFailureCode.CLIENT_UNAVAILABLE ||
                        failureCode == SupportReplyDeliveryFailureCode.TELEGRAM_REJECTED,
                )
            SupportReplyDeliveryStatus.UNCONFIRMED ->
                require(
                    failureCode == SupportReplyDeliveryFailureCode.TIMEOUT ||
                        failureCode == SupportReplyDeliveryFailureCode.TRANSPORT_ERROR ||
                        failureCode == SupportReplyDeliveryFailureCode.CANCELED,
                )
            SupportReplyDeliveryStatus.PENDING,
            SupportReplyDeliveryStatus.SENDING,
            -> error("Support reply delivery finalization requires a terminal result")
        }
    }
}

private object SupportDeliveryUsersTable : Table("users") {
    val id = long("id")
    val telegramUserId = long("telegram_user_id").nullable()

    override val primaryKey = PrimaryKey(id)
}
