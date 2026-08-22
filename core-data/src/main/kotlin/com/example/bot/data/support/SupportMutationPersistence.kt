package com.example.bot.data.support

import com.example.bot.audit.StandardAuditAction
import com.example.bot.audit.StandardAuditEntityType
import com.example.bot.data.audit.AuditLogTable
import com.example.bot.data.security.PermissionCode
import com.example.bot.data.security.PermissionCodes
import com.example.bot.data.security.Role
import com.example.bot.data.security.UserRolePermissionsTable
import com.example.bot.data.security.UserRolesTable
import com.example.bot.support.SupportReplyResult
import com.example.bot.support.Ticket
import com.example.bot.support.TicketMessage
import com.example.bot.support.TicketSenderType
import com.example.bot.support.TicketStatus
import com.example.bot.support.TicketTopic
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.sql.Connection
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.coroutines.CoroutineContext

private const val CLUB_SCOPE_TYPE = "CLUB"
private const val MUTATION_TIMESTAMP_STEP_NANOS = 1_000L
private val operationalSupportRoleNames = setOf(Role.MANAGER.name, Role.CLUB_ADMIN.name)

internal class SupportMutationPersistence(
    private val db: Database,
    private val clock: Clock,
    auditFingerprintFactory: (String, Long) -> String,
    private val transactionContext: CoroutineContext,
) {
    private val auditWriter = SupportMutationAuditWriter(auditFingerprintFactory)

    suspend fun assign(
        ticketId: Long,
        agentUserId: Long,
    ): StaffMutationResult<Ticket> =
        newSuspendedTransaction(
            context = transactionContext,
            db = db,
            transactionIsolation = Connection.TRANSACTION_READ_COMMITTED,
        ) {
            repetitionAttempts = 1
            when (
                val authorization =
                    authorizeStaffMutation(
                        ticketId = ticketId,
                        agentUserId = agentUserId,
                        permission = PermissionCodes.SUPPORT_STATUS_MANAGE,
                    )
            ) {
                is StaffMutationAuthorization.Failure -> StaffMutationResult.Failure(authorization.reason)
                is StaffMutationAuthorization.Success -> takeInWork(authorization, agentUserId)
            }
        }

    suspend fun setStatus(
        ticketId: Long,
        agentUserId: Long,
        status: TicketStatus,
    ): StaffMutationResult<Ticket> =
        newSuspendedTransaction(
            context = transactionContext,
            db = db,
            transactionIsolation = Connection.TRANSACTION_READ_COMMITTED,
        ) {
            repetitionAttempts = 1
            when (
                val authorization =
                    authorizeStaffMutation(
                        ticketId = ticketId,
                        agentUserId = agentUserId,
                        permission = PermissionCodes.SUPPORT_STATUS_MANAGE,
                    )
            ) {
                is StaffMutationAuthorization.Failure -> StaffMutationResult.Failure(authorization.reason)
                is StaffMutationAuthorization.Success -> rejectGenericStatus(status)
            }
        }

    suspend fun reply(
        ticketId: Long,
        agentUserId: Long,
        text: String,
        attachments: String?,
    ): StaffMutationResult<SupportReplyResult> =
        newSuspendedTransaction(
            context = transactionContext,
            db = db,
            transactionIsolation = Connection.TRANSACTION_READ_COMMITTED,
        ) {
            repetitionAttempts = 1
            when (
                val authorization =
                    authorizeStaffMutation(
                        ticketId = ticketId,
                        agentUserId = agentUserId,
                        permission = PermissionCodes.SUPPORT_REPLY,
                    )
            ) {
                is StaffMutationAuthorization.Failure -> StaffMutationResult.Failure(authorization.reason)
                is StaffMutationAuthorization.Success ->
                    persistStaffReply(
                        authorization = authorization,
                        agentUserId = agentUserId,
                        text = text,
                        attachments = attachments,
                    )
            }
        }

    private fun rejectGenericStatus(status: TicketStatus): StaffMutationResult.Failure =
        when (status) {
            TicketStatus.NEW,
            TicketStatus.OPENED,
            TicketStatus.IN_PROGRESS,
            TicketStatus.ANSWERED,
            TicketStatus.CLOSED,
            -> StaffMutationResult.Failure(StaffMutationFailure.InvalidState)
        }

    private fun takeInWork(
        authorization: StaffMutationAuthorization.Success,
        agentUserId: Long,
    ): StaffMutationResult<Ticket> {
        val oldStatus = TicketStatus.fromWire(authorization.ticketRow[TicketsTable.status])
        return if (oldStatus == TicketStatus.NEW) {
            persistTakeInWork(authorization, agentUserId, oldStatus)
        } else {
            StaffMutationResult.Failure(StaffMutationFailure.InvalidState)
        }
    }

    private fun persistTakeInWork(
        authorization: StaffMutationAuthorization.Success,
        agentUserId: Long,
        oldStatus: TicketStatus,
    ): StaffMutationResult.Success<Ticket> {
        val occurredAt = nextMutationTimestamp(authorization.ticketRow)
        updateStaffTicket(
            ticketId = authorization.ticketId,
            agentUserId = agentUserId,
            status = TicketStatus.IN_PROGRESS,
            occurredAt = occurredAt,
        )
        auditWriter.appendStatusChange(
            authorization = authorization,
            agentUserId = agentUserId,
            oldStatus = oldStatus,
            newStatus = TicketStatus.IN_PROGRESS,
            occurredAt = occurredAt,
        )
        return StaffMutationResult.Success(loadTicket(authorization.ticketId))
    }

    private fun persistStaffReply(
        authorization: StaffMutationAuthorization.Success,
        agentUserId: Long,
        text: String,
        attachments: String?,
    ): StaffMutationResult<SupportReplyResult> =
        TicketStatus
            .fromWire(authorization.ticketRow[TicketsTable.status])
            ?.takeIf { it == TicketStatus.NEW || it == TicketStatus.IN_PROGRESS }
            ?.let { oldStatus ->
                persistAllowedStaffReply(
                    authorization = authorization,
                    agentUserId = agentUserId,
                    text = text,
                    attachments = attachments,
                    oldStatus = oldStatus,
                )
            } ?: StaffMutationResult.Failure(StaffMutationFailure.InvalidState)

    private fun persistAllowedStaffReply(
        authorization: StaffMutationAuthorization.Success,
        agentUserId: Long,
        text: String,
        attachments: String?,
        oldStatus: TicketStatus,
    ): StaffMutationResult.Success<SupportReplyResult> {
        val occurredAt = nextMutationTimestamp(authorization.ticketRow)
        updateStaffTicket(
            ticketId = authorization.ticketId,
            agentUserId = agentUserId,
            status = TicketStatus.IN_PROGRESS,
            occurredAt = occurredAt,
        )
        val messageId =
            TicketMessagesTable.insert {
                it[TicketMessagesTable.ticketId] = authorization.ticketId
                it[TicketMessagesTable.senderType] = TicketSenderType.AGENT.wire
                it[TicketMessagesTable.text] = text
                it[TicketMessagesTable.attachments] = attachments
                it[TicketMessagesTable.createdAt] = occurredAt
            }[TicketMessagesTable.id]
        auditWriter.appendReply(
            authorization = authorization,
            agentUserId = agentUserId,
            messageId = messageId,
            occurredAt = occurredAt,
        )
        if (oldStatus == TicketStatus.NEW) {
            auditWriter.appendStatusChange(
                authorization = authorization,
                agentUserId = agentUserId,
                oldStatus = oldStatus,
                newStatus = TicketStatus.IN_PROGRESS,
                occurredAt = occurredAt,
            )
        }
        val ticket = loadTicket(authorization.ticketId)
        val message =
            TicketMessage(
                id = messageId,
                ticketId = authorization.ticketId,
                senderType = TicketSenderType.AGENT,
                text = text,
                attachments = attachments,
                createdAt = occurredAt.toInstant(),
            )
        return StaffMutationResult.Success(SupportReplyResult(ticket = ticket, replyMessage = message))
    }

    private fun authorizeStaffMutation(
        ticketId: Long,
        agentUserId: Long,
        permission: PermissionCode,
    ): StaffMutationAuthorization {
        val assignments = loadAuthorizedAssignments(agentUserId, permission)
        return if (assignments.isEmpty()) {
            StaffMutationAuthorization.Failure(StaffMutationFailure.Forbidden)
        } else {
            authorizeScopedTicket(ticketId, assignments)
        }
    }

    private fun authorizeScopedTicket(
        ticketId: Long,
        assignments: List<AuthorizedSupportAssignment>,
    ): StaffMutationAuthorization {
        val permittedClubIds = assignments.map(AuthorizedSupportAssignment::clubId).distinct()
        return TicketsTable
            .selectAll()
            .where {
                (TicketsTable.id eq ticketId) and
                    (TicketsTable.clubId inList permittedClubIds)
            }.forUpdate()
            .singleOrNull()
            ?.let { ticketRow -> authorizedTicket(ticketId, ticketRow, assignments) }
            ?: StaffMutationAuthorization.Failure(StaffMutationFailure.NotFound)
    }

    private fun authorizedTicket(
        ticketId: Long,
        ticketRow: ResultRow,
        assignments: List<AuthorizedSupportAssignment>,
    ): StaffMutationAuthorization.Success {
        val clubId = ticketRow[TicketsTable.clubId]
        val assignment = assignments.first { it.clubId == clubId }
        return StaffMutationAuthorization.Success(
            ticketId = ticketId,
            clubId = clubId,
            actorRole = assignment.role,
            ticketRow = ticketRow,
        )
    }

    private fun loadAuthorizedAssignments(
        agentUserId: Long,
        permission: PermissionCode,
    ): List<AuthorizedSupportAssignment> =
        UserRolesTable
            .join(
                UserRolePermissionsTable,
                JoinType.INNER,
                additionalConstraint = {
                    UserRolesTable.id eq UserRolePermissionsTable.userRoleId
                },
            ).selectAll()
            .where {
                (UserRolesTable.userId eq agentUserId) and
                    (UserRolesTable.roleCode inList operationalSupportRoleNames) and
                    (UserRolesTable.scopeType eq CLUB_SCOPE_TYPE) and
                    UserRolesTable.scopeClubId.isNotNull() and
                    (UserRolePermissionsTable.permissionCode eq permission.value)
            }.orderBy(UserRolesTable.id to SortOrder.ASC)
            .forUpdate()
            .mapNotNull { row ->
                val clubId = row[UserRolesTable.scopeClubId] ?: return@mapNotNull null
                val role =
                    Role.entries.firstOrNull { it.name == row[UserRolesTable.roleCode] }
                        ?: return@mapNotNull null
                AuthorizedSupportAssignment(clubId = clubId, role = role)
            }

    private fun updateStaffTicket(
        ticketId: Long,
        agentUserId: Long,
        status: TicketStatus,
        occurredAt: OffsetDateTime,
    ) {
        val updated =
            TicketsTable.update({ TicketsTable.id eq ticketId }) {
                it[TicketsTable.status] = status.wire
                it[TicketsTable.lastAgentId] = agentUserId
                it[TicketsTable.updatedAt] = occurredAt
            }
        check(updated == 1) { "Support ticket mutation did not update exactly one row" }
    }

    private fun loadTicket(ticketId: Long): Ticket =
        TicketsTable
            .selectAll()
            .where { TicketsTable.id eq ticketId }
            .map(::toTicket)
            .single()

    private fun nextMutationTimestamp(ticketRow: ResultRow): OffsetDateTime {
        val now = clock.instant()
        val minimum = ticketRow[TicketsTable.updatedAt].toInstant().plusNanos(MUTATION_TIMESTAMP_STEP_NANOS)
        val instant = if (now.isAfter(minimum)) now else minimum
        return instant.atOffset(ZoneOffset.UTC)
    }

    private fun toTicket(row: ResultRow): Ticket =
        Ticket(
            id = row[TicketsTable.id],
            clubId = row[TicketsTable.clubId],
            userId = row[TicketsTable.userId],
            bookingId = row[TicketsTable.bookingId],
            listEntryId = row[TicketsTable.listEntryId],
            topic =
                requireNotNull(TicketTopic.fromWire(row[TicketsTable.topic])) {
                    "Unknown ticket topic: ${row[TicketsTable.topic]}"
                },
            status =
                requireNotNull(TicketStatus.fromWire(row[TicketsTable.status])) {
                    "Unknown ticket status: ${row[TicketsTable.status]}"
                },
            createdAt = row[TicketsTable.createdAt].toInstant(),
            updatedAt = row[TicketsTable.updatedAt].toInstant(),
            lastAgentId = row[TicketsTable.lastAgentId],
            resolutionRating = row[TicketsTable.resolutionRating]?.toInt(),
        )
}

private class SupportMutationAuditWriter(
    private val auditFingerprintFactory: (String, Long) -> String,
) {
    fun appendReply(
        authorization: StaffMutationAuthorization.Success,
        agentUserId: Long,
        messageId: Long,
        occurredAt: OffsetDateTime,
    ) {
        append(
            authorization = authorization,
            agentUserId = agentUserId,
            action = StandardAuditAction.SUPPORT_REPLY,
            occurredAt = occurredAt,
            metadataJson =
                buildJsonObject {
                    put("message_id", messageId)
                }.toString(),
        )
    }

    fun appendStatusChange(
        authorization: StaffMutationAuthorization.Success,
        agentUserId: Long,
        oldStatus: TicketStatus,
        newStatus: TicketStatus,
        occurredAt: OffsetDateTime,
    ) {
        append(
            authorization = authorization,
            agentUserId = agentUserId,
            action = StandardAuditAction.SUPPORT_STATUS_CHANGE,
            occurredAt = occurredAt,
            metadataJson =
                buildJsonObject {
                    put("old_status", oldStatus.wire)
                    put("new_status", newStatus.wire)
                }.toString(),
        )
    }

    private fun append(
        authorization: StaffMutationAuthorization.Success,
        agentUserId: Long,
        action: StandardAuditAction,
        occurredAt: OffsetDateTime,
        metadataJson: String,
    ) {
        AuditLogTable.insert {
            it[AuditLogTable.createdAt] = occurredAt
            it[AuditLogTable.clubId] = authorization.clubId
            it[AuditLogTable.nightId] = null
            it[AuditLogTable.actorUserId] = agentUserId
            it[AuditLogTable.actorRole] = authorization.actorRole.name
            it[AuditLogTable.subjectUserId] = null
            it[AuditLogTable.entityType] = StandardAuditEntityType.SUPPORT_TICKET.value
            it[AuditLogTable.entityId] = authorization.ticketId
            it[AuditLogTable.action] = action.value
            it[AuditLogTable.fingerprint] = auditFingerprintFactory(action.value, authorization.ticketId)
            it[AuditLogTable.metadataJson] = metadataJson
        }
    }
}

private data class AuthorizedSupportAssignment(
    val clubId: Long,
    val role: Role,
)

private sealed interface StaffMutationAuthorization {
    data class Success(
        val ticketId: Long,
        val clubId: Long,
        val actorRole: Role,
        val ticketRow: ResultRow,
    ) : StaffMutationAuthorization

    data class Failure(
        val reason: StaffMutationFailure,
    ) : StaffMutationAuthorization
}
