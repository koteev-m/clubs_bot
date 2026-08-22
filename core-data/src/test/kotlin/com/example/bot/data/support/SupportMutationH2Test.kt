package com.example.bot.data.support

import com.example.bot.audit.StandardAuditAction
import com.example.bot.audit.StandardAuditEntityType
import com.example.bot.data.TestDatabase
import com.example.bot.data.audit.AuditLogRepositoryImpl
import com.example.bot.data.audit.AuditLogTable
import com.example.bot.data.security.PermissionCode
import com.example.bot.data.security.PermissionCodes
import com.example.bot.data.security.Role
import com.example.bot.support.SupportReplyResult
import com.example.bot.support.SupportServiceError
import com.example.bot.support.SupportServiceResult
import com.example.bot.support.TicketSenderType
import com.example.bot.support.TicketStatus
import com.example.bot.support.TicketTopic
import com.example.bot.support.TicketWithMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.coroutines.cancellation.CancellationException

class SupportMutationH2Test {
    private lateinit var fixture: SupportMutationH2Fixture

    @BeforeEach
    fun setUp() {
        fixture = SupportMutationH2Fixture()
    }

    @AfterEach
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `manager and club admin take NEW with one exact status audit and durable state`() =
        runBlocking {
            listOf(Role.MANAGER, Role.CLUB_ADMIN).forEach { role ->
                val clubId = fixture.insertClub("Take $role")
                val guestId = fixture.insertUser("take-guest-$role")
                val actorId = fixture.insertAuthorizedActor(role, clubId, PermissionCodes.SUPPORT_STATUS_MANAGE)
                val created = fixture.createTicket(clubId, guestId, "take-question-$role")

                val result = fixture.mutationService().assign(created.ticket.id, actorId)

                val ticket = result.successValue()
                assertEquals(TicketStatus.IN_PROGRESS, ticket.status)
                assertEquals(actorId, ticket.lastAgentId)
                assertTrue(ticket.updatedAt.isAfter(created.ticket.updatedAt))
                assertEquals(1L, fixture.messageCount(ticket.id))
                val audits = fixture.supportAudits(ticket.id)
                assertEquals(1, audits.size)
                fixture.assertStatusAudit(
                    audit = audits.single(),
                    clubId = clubId,
                    actorId = actorId,
                    actorRole = role,
                    oldStatus = TicketStatus.NEW,
                    newStatus = TicketStatus.IN_PROGRESS,
                )

                val freshService = fixture.freshService()
                assertEquals(ticket, freshService.getTicket(ticket.id))
                val freshAudits =
                    AuditLogRepositoryImpl(fixture.database)
                        .listForClub(clubId = clubId, limit = 10, offset = 0)
                        .filter { it.entityId == ticket.id }
                assertEquals(1, freshAudits.size)
                assertEquals(StandardAuditEntityType.SUPPORT_TICKET, freshAudits.single().entityType)
                assertEquals(StandardAuditAction.SUPPORT_STATUS_CHANGE, freshAudits.single().action)
            }
        }

    @Test
    fun `take rejects every non NEW and unsupported status without writes`() =
        runBlocking {
            val clubId = fixture.insertClub("Take state guard")
            val guestId = fixture.insertUser("take-state-guest")
            val actorId =
                fixture.insertAuthorizedActor(
                    Role.MANAGER,
                    clubId,
                    PermissionCodes.SUPPORT_STATUS_MANAGE,
                )
            listOf(
                TicketStatus.IN_PROGRESS,
                TicketStatus.OPENED,
                TicketStatus.ANSWERED,
                TicketStatus.CLOSED,
            ).forEach { status ->
                val ticketId = fixture.createTicket(clubId, guestId, "take-$status").ticket.id
                fixture.seedStatus(ticketId, status)
                val before = fixture.snapshot(ticketId)

                fixture.mutationService().assign(ticketId, actorId).assertFailure(SupportServiceError.InvalidState)

                assertEquals(before, fixture.snapshot(ticketId))
            }

            fixture.allowUnsupportedTicketStatus()
            val unsupportedId = fixture.createTicket(clubId, guestId, "take-unsupported").ticket.id
            fixture.seedRawStatus(unsupportedId, "unsupported")
            val unsupportedBefore = fixture.snapshot(unsupportedId)

            fixture.mutationService().assign(unsupportedId, actorId).assertFailure(SupportServiceError.InvalidState)

            assertEquals(unsupportedBefore, fixture.snapshot(unsupportedId))
        }

    @Test
    fun `first reply persists one AGENT message transitions NEW and writes exact safe audits`() =
        runBlocking {
            listOf(Role.MANAGER, Role.CLUB_ADMIN).forEach { role ->
                val clubId = fixture.insertClub("First reply $role")
                val guestId = fixture.insertUser("reply-guest-$role")
                val actorId = fixture.insertAuthorizedActor(role, clubId, PermissionCodes.SUPPORT_REPLY)
                val body = "private-reply-body-$role"
                val attachments = "private-reply-attachments-$role"
                val created = fixture.createTicket(clubId, guestId, "reply-question-$role")

                val result =
                    fixture.mutationService().reply(
                        ticketId = created.ticket.id,
                        agentUserId = actorId,
                        text = body,
                        attachments = attachments,
                    )

                val reply = result.successValue()
                fixture.assertPersistedReply(reply, actorId, body, attachments)
                assertTrue(reply.ticket.updatedAt.isAfter(created.ticket.updatedAt))
                val messages = fixture.messages(reply.ticket.id)
                assertEquals(2, messages.size)
                assertEquals(TicketSenderType.AGENT.wire, messages.last().senderType)
                assertEquals(body, messages.last().text)
                assertEquals(attachments, messages.last().attachments)

                val audits = fixture.supportAudits(reply.ticket.id)
                assertEquals(2, audits.size)
                val replyAudit = audits.single { it.action == StandardAuditAction.SUPPORT_REPLY.value }
                fixture.assertReplyAudit(replyAudit, clubId, actorId, role, reply.replyMessage.id)
                val statusAudit =
                    audits.single { it.action == StandardAuditAction.SUPPORT_STATUS_CHANGE.value }
                fixture.assertStatusAudit(
                    audit = statusAudit,
                    clubId = clubId,
                    actorId = actorId,
                    actorRole = role,
                    oldStatus = TicketStatus.NEW,
                    newStatus = TicketStatus.IN_PROGRESS,
                )
                val renderedAudits = audits.joinToString("\n") { it.metadataJson }
                assertFalse(renderedAudits.contains(body))
                assertFalse(renderedAudits.contains(attachments))

                val freshThread = fixture.freshService().getStaffTicket(reply.ticket.id, setOf(clubId)).successValue()
                assertEquals(
                    listOf(created.initialMessage.id, reply.replyMessage.id),
                    freshThread.messages.map { message -> message.id },
                )
                assertEquals(body, freshThread.messages.last().text)
                assertEquals(attachments, freshThread.messages.last().attachments)
            }
        }

    @Test
    fun `reply on IN PROGRESS persists once and writes no status audit`() =
        runBlocking {
            val clubId = fixture.insertClub("Subsequent reply")
            val guestId = fixture.insertUser("subsequent-guest")
            val actorId = fixture.insertAuthorizedActor(Role.MANAGER, clubId, PermissionCodes.SUPPORT_REPLY)
            val ticketId = fixture.createTicket(clubId, guestId, "subsequent-question").ticket.id
            fixture.seedStatus(ticketId, TicketStatus.IN_PROGRESS)

            val result = fixture.mutationService().reply(ticketId, actorId, "second answer", null)

            val reply = result.successValue()
            assertEquals(TicketStatus.IN_PROGRESS, reply.ticket.status)
            assertEquals(actorId, reply.ticket.lastAgentId)
            assertEquals(2L, fixture.messageCount(ticketId))
            val audits = fixture.supportAudits(ticketId)
            assertEquals(1, audits.size)
            assertEquals(StandardAuditAction.SUPPORT_REPLY.value, audits.single().action)
            fixture.assertReplyAudit(
                audit = audits.single(),
                clubId = clubId,
                actorId = actorId,
                actorRole = Role.MANAGER,
                messageId = reply.replyMessage.id,
            )
        }

    @Test
    fun `reply rejects legacy and unsupported statuses without writes`() =
        runBlocking {
            val clubId = fixture.insertClub("Reply state guard")
            val guestId = fixture.insertUser("reply-state-guest")
            val actorId = fixture.insertAuthorizedActor(Role.CLUB_ADMIN, clubId, PermissionCodes.SUPPORT_REPLY)
            listOf(TicketStatus.OPENED, TicketStatus.ANSWERED, TicketStatus.CLOSED).forEach { status ->
                val ticketId = fixture.createTicket(clubId, guestId, "reply-$status").ticket.id
                fixture.seedStatus(ticketId, status)
                val before = fixture.snapshot(ticketId)

                fixture.mutationService().reply(ticketId, actorId, "denied", "denied").assertFailure(
                    SupportServiceError.InvalidState,
                )

                assertEquals(before, fixture.snapshot(ticketId))
            }

            fixture.allowUnsupportedTicketStatus()
            val unsupportedId = fixture.createTicket(clubId, guestId, "reply-unsupported").ticket.id
            fixture.seedRawStatus(unsupportedId, "unsupported")
            val unsupportedBefore = fixture.snapshot(unsupportedId)

            fixture.mutationService().reply(unsupportedId, actorId, "denied", null).assertFailure(
                SupportServiceError.InvalidState,
            )

            assertEquals(unsupportedBefore, fixture.snapshot(unsupportedId))
        }

    @Test
    fun `mutation permissions stay independent and exact assignment scoped`() =
        runBlocking {
            val clubId = fixture.insertClub("Permission separation")
            val guestId = fixture.insertUser("permission-guest")
            val ticketId = fixture.createTicket(clubId, guestId, "permission-question").ticket.id

            val viewActor = fixture.insertAuthorizedActor(Role.MANAGER, clubId, PermissionCodes.SUPPORT_VIEW)
            fixture.assertAllMutationsForbidden(ticketId, viewActor)

            val replyActor = fixture.insertAuthorizedActor(Role.MANAGER, clubId, PermissionCodes.SUPPORT_REPLY)
            fixture.mutationService().assign(ticketId, replyActor).assertFailure(SupportServiceError.TicketForbidden)
            fixture.mutationService().setStatus(ticketId, replyActor, TicketStatus.CLOSED).assertFailure(
                SupportServiceError.TicketForbidden,
            )

            val statusActor =
                fixture.insertAuthorizedActor(
                    Role.CLUB_ADMIN,
                    clubId,
                    PermissionCodes.SUPPORT_STATUS_MANAGE,
                )
            fixture.mutationService().reply(ticketId, statusActor, "denied", null).assertFailure(
                SupportServiceError.TicketForbidden,
            )

            val splitActor = fixture.insertUser("split-actor")
            fixture.insertAssignment(splitActor, Role.MANAGER, "CLUB", clubId)
            val promoterAssignment = fixture.insertAssignment(splitActor, Role.PROMOTER, "CLUB", clubId)
            fixture.grant(promoterAssignment, PermissionCodes.SUPPORT_REPLY)
            fixture.grant(promoterAssignment, PermissionCodes.SUPPORT_STATUS_MANAGE)
            fixture.assertAllMutationsForbidden(ticketId, splitActor)

            val wrongUser = fixture.insertUser("wrong-grant-user")
            val wrongUserAssignment = fixture.insertAssignment(wrongUser, Role.MANAGER, "CLUB", clubId)
            fixture.grant(wrongUserAssignment, PermissionCodes.SUPPORT_REPLY)
            fixture.grant(wrongUserAssignment, PermissionCodes.SUPPORT_STATUS_MANAGE)
            val actorWithoutGrant = fixture.insertUser("actor-without-grant")
            fixture.insertAssignment(actorWithoutGrant, Role.MANAGER, "CLUB", clubId)
            fixture.assertAllMutationsForbidden(ticketId, actorWithoutGrant)

            val globalActor = fixture.insertUser("global-actor")
            val globalAssignment = fixture.insertAssignment(globalActor, Role.MANAGER, "GLOBAL", null)
            fixture.grant(globalAssignment, PermissionCodes.SUPPORT_REPLY)
            fixture.grant(globalAssignment, PermissionCodes.SUPPORT_STATUS_MANAGE)
            fixture.assertAllMutationsForbidden(ticketId, globalActor)

            assertEquals(TicketStatus.NEW.wire, fixture.snapshot(ticketId).status)
            assertEquals(0L, fixture.supportAuditCount(ticketId))
            assertEquals(1L, fixture.messageCount(ticketId))
        }

    @Test
    fun `unaccepted roles remain denied even with attached grants`() =
        runBlocking {
            val clubId = fixture.insertClub("Denied roles")
            val guestId = fixture.insertUser("denied-role-guest")
            val ticketId = fixture.createTicket(clubId, guestId, "denied-role-question").ticket.id
            listOf(
                Role.ENTRY_MANAGER,
                Role.OWNER,
                Role.GLOBAL_ADMIN,
                Role.HEAD_MANAGER,
                Role.PROMOTER,
                Role.GUEST,
            ).forEach { role ->
                val actorId =
                    fixture.insertAuthorizedActor(
                        role,
                        clubId,
                        PermissionCodes.SUPPORT_REPLY,
                        PermissionCodes.SUPPORT_STATUS_MANAGE,
                    )
                fixture.assertAllMutationsForbidden(ticketId, actorId)
            }
            assertEquals(TicketStatus.NEW.wire, fixture.snapshot(ticketId).status)
            assertEquals(0L, fixture.supportAuditCount(ticketId))
            assertEquals(1L, fixture.messageCount(ticketId))
        }

    @Test
    fun `foreign and missing tickets remain indistinguishable after exact permission check`() =
        runBlocking {
            val permittedClubId = fixture.insertClub("Permitted club")
            val foreignClubId = fixture.insertClub("Foreign club")
            val guestId = fixture.insertUser("foreign-guest")
            val actorId =
                fixture.insertAuthorizedActor(
                    Role.MANAGER,
                    permittedClubId,
                    PermissionCodes.SUPPORT_REPLY,
                    PermissionCodes.SUPPORT_STATUS_MANAGE,
                )
            val foreignTicketId = fixture.createTicket(foreignClubId, guestId, "foreign-private-body").ticket.id
            val foreignBefore = fixture.snapshot(foreignTicketId)
            val missingId = Long.MAX_VALUE

            assertEquals(
                fixture.mutationService().assign(missingId, actorId),
                fixture.mutationService().assign(foreignTicketId, actorId),
            )
            assertEquals(
                fixture.mutationService().reply(missingId, actorId, "reply", null),
                fixture.mutationService().reply(foreignTicketId, actorId, "reply", null),
            )
            assertEquals(
                fixture.mutationService().setStatus(missingId, actorId, TicketStatus.CLOSED),
                fixture.mutationService().setStatus(foreignTicketId, actorId, TicketStatus.CLOSED),
            )
            fixture.mutationService().assign(foreignTicketId, actorId).assertFailure(
                SupportServiceError.TicketNotFound,
            )
            assertEquals(foreignBefore, fixture.snapshot(foreignTicketId))
        }

    @Test
    fun `generic status is always invalid after authorization and never writes`() =
        runBlocking {
            val clubId = fixture.insertClub("Generic status")
            val guestId = fixture.insertUser("generic-status-guest")
            val actorId =
                fixture.insertAuthorizedActor(
                    Role.MANAGER,
                    clubId,
                    PermissionCodes.SUPPORT_STATUS_MANAGE,
                )
            val ticketId = fixture.createTicket(clubId, guestId, "generic-status-question").ticket.id
            val before = fixture.snapshot(ticketId)

            TicketStatus.entries.forEach { target ->
                fixture.mutationService().setStatus(ticketId, actorId, target).assertFailure(
                    SupportServiceError.InvalidState,
                )
            }

            assertEquals(before, fixture.snapshot(ticketId))
        }

    @Test
    fun `audit failure rolls back take and first reply with detail free persistence errors`() =
        runBlocking {
            val clubId = fixture.insertClub("Audit rollback")
            val guestId = fixture.insertUser("audit-rollback-guest")
            val takeActor =
                fixture.insertAuthorizedActor(
                    Role.MANAGER,
                    clubId,
                    PermissionCodes.SUPPORT_STATUS_MANAGE,
                )
            val replyActor = fixture.insertAuthorizedActor(Role.CLUB_ADMIN, clubId, PermissionCodes.SUPPORT_REPLY)
            val takeId = fixture.createTicket(clubId, guestId, "take-audit-failure").ticket.id
            val replyId = fixture.createTicket(clubId, guestId, "reply-audit-failure").ticket.id
            val takeBefore = fixture.snapshot(takeId)
            val replyBefore = fixture.snapshot(replyId)
            fixture.rejectStatusAuditInserts()

            val takeResult = fixture.mutationService().assign(takeId, takeActor)
            val replyResult =
                fixture.mutationService().reply(
                    ticketId = replyId,
                    agentUserId = replyActor,
                    text = "rollback-private-body",
                    attachments = "rollback-private-attachments",
                )

            takeResult.assertDetailFreePersistenceFailure()
            replyResult.assertDetailFreePersistenceFailure()
            assertEquals(takeBefore, fixture.snapshot(takeId))
            assertEquals(replyBefore, fixture.snapshot(replyId))
            assertEquals(0L, fixture.supportAuditCount(takeId))
            assertEquals(0L, fixture.supportAuditCount(replyId))
        }

    @Test
    fun `staff mutation rethrows cancellation and does not swallow JVM errors`() {
        val clubId = fixture.insertClub("Mutation cancellation")
        val guestId = fixture.insertUser("cancellation-guest")
        val actorId =
            fixture.insertAuthorizedActor(
                Role.MANAGER,
                clubId,
                PermissionCodes.SUPPORT_STATUS_MANAGE,
            )
        val cancellationTicket = runBlocking { fixture.createTicket(clubId, guestId, "cancel-take") }.ticket.id
        val errorTicket = runBlocking { fixture.createTicket(clubId, guestId, "error-take") }.ticket.id
        val cancellationBefore = fixture.snapshot(cancellationTicket)
        val errorBefore = fixture.snapshot(errorTicket)
        val cancellation = CancellationException("cancel-support-mutation")

        val thrownCancellation =
            assertThrows(CancellationException::class.java) {
                runBlocking {
                    fixture.serviceWithClock(ThrowingClock(cancellation)).assign(cancellationTicket, actorId)
                }
            }
        assertEquals(cancellation.message, thrownCancellation.message)

        val fatal = AssertionError("fatal-support-mutation")
        val thrownError =
            assertThrows(AssertionError::class.java) {
                runBlocking {
                    fixture.serviceWithClock(ThrowingClock(fatal)).assign(errorTicket, actorId)
                }
            }
        assertEquals(fatal.message, thrownError.message)
        assertEquals(cancellationBefore, fixture.snapshot(cancellationTicket))
        assertEquals(errorBefore, fixture.snapshot(errorTicket))
    }
}

private class SupportMutationH2Fixture : AutoCloseable {
    private val testDatabase = TestDatabase()
    val database: Database = testDatabase.database
    private var telegramUserId = 9_100_000L

    suspend fun createTicket(
        clubId: Long,
        userId: Long,
        text: String,
    ): TicketWithMessage =
        serviceAt(CREATED_AT)
            .createTicket(
                clubId = clubId,
                userId = userId,
                bookingId = null,
                listEntryId = null,
                topic = TicketTopic.OTHER,
                text = text,
                attachments = null,
            ).successValue()

    fun mutationService(): SupportServiceImpl = serviceAt(MUTATED_AT)

    fun freshService(): SupportServiceImpl = serviceAt(READ_AT)

    fun serviceWithClock(clock: Clock): SupportServiceImpl = SupportServiceImpl(SupportRepository(database, clock))

    fun insertUser(username: String): Long =
        transaction(database) {
            MutationTestUsersTable.insert {
                it[telegramUserId] = ++this@SupportMutationH2Fixture.telegramUserId
                it[MutationTestUsersTable.username] = username
                it[displayName] = username
                it[phoneE164] = null
            }[MutationTestUsersTable.id]
        }

    fun insertClub(name: String): Long =
        transaction(database) {
            MutationTestClubsTable.insert {
                it[MutationTestClubsTable.name] = name
                it[description] = null
                it[timezone] = "Europe/Moscow"
                it[adminChannelId] = null
                it[bookingsTopicId] = null
                it[checkinTopicId] = null
                it[qaTopicId] = null
            }[MutationTestClubsTable.id]
        }

    fun insertAuthorizedActor(
        role: Role,
        clubId: Long,
        permission: PermissionCode,
        additionalPermission: PermissionCode? = null,
    ): Long {
        val actorId = insertUser("actor-${role.name.lowercase()}-$telegramUserId")
        val assignmentId = insertAssignment(actorId, role, "CLUB", clubId)
        grant(assignmentId, permission)
        if (additionalPermission != null) {
            grant(assignmentId, additionalPermission)
        }
        return actorId
    }

    fun insertAssignment(
        userId: Long,
        role: Role,
        scopeType: String,
        clubId: Long?,
    ): Long =
        transaction(database) {
            MutationTestUserRolesTable.insert {
                it[MutationTestUserRolesTable.userId] = userId
                it[roleCode] = role.name
                it[MutationTestUserRolesTable.scopeType] = scopeType
                it[scopeClubId] = clubId
            }[MutationTestUserRolesTable.id]
        }

    fun grant(
        assignmentId: Long,
        permission: PermissionCode,
    ) {
        transaction(database) {
            MutationTestUserRolePermissionsTable.insert {
                it[userRoleId] = assignmentId
                it[permissionCode] = permission.value
            }
        }
    }

    fun seedStatus(
        ticketId: Long,
        status: TicketStatus,
    ) = seedRawStatus(ticketId, status.wire)

    fun seedRawStatus(
        ticketId: Long,
        status: String,
    ) {
        transaction(database) {
            assertEquals(
                1,
                TicketsTable.update({ TicketsTable.id eq ticketId }) {
                    it[TicketsTable.status] = status
                },
            )
        }
    }

    fun allowUnsupportedTicketStatus() {
        transaction(database) {
            exec("ALTER TABLE tickets DROP CONSTRAINT tickets_status_check")
        }
    }

    fun rejectStatusAuditInserts() {
        transaction(database) {
            exec(
                "ALTER TABLE audit_log ADD CONSTRAINT $AUDIT_FAILURE_CONSTRAINT " +
                    "CHECK (action <> '${StandardAuditAction.SUPPORT_STATUS_CHANGE.value}')",
            )
        }
    }

    fun snapshot(ticketId: Long): MutationSnapshot =
        transaction(database) {
            val ticket = TicketsTable.selectAll().where { TicketsTable.id eq ticketId }.single()
            MutationSnapshot(
                status = ticket[TicketsTable.status],
                lastAgentId = ticket[TicketsTable.lastAgentId],
                updatedAt = ticket[TicketsTable.updatedAt].toInstant(),
                messageCount =
                    TicketMessagesTable
                        .selectAll()
                        .where { TicketMessagesTable.ticketId eq ticketId }
                        .count(),
                auditCount = supportAuditCountInTransaction(ticketId),
            )
        }

    fun messageCount(ticketId: Long): Long =
        transaction(database) {
            TicketMessagesTable.selectAll().where { TicketMessagesTable.ticketId eq ticketId }.count()
        }

    fun messages(ticketId: Long): List<StoredMessage> =
        transaction(database) {
            TicketMessagesTable
                .selectAll()
                .where { TicketMessagesTable.ticketId eq ticketId }
                .orderBy(TicketMessagesTable.id to SortOrder.ASC)
                .map { row ->
                    StoredMessage(
                        id = row[TicketMessagesTable.id],
                        senderType = row[TicketMessagesTable.senderType],
                        text = row[TicketMessagesTable.text],
                        attachments = row[TicketMessagesTable.attachments],
                    )
                }
        }

    fun supportAudits(ticketId: Long): List<StoredAudit> =
        transaction(database) {
            AuditLogTable
                .selectAll()
                .where {
                    (AuditLogTable.entityType eq StandardAuditEntityType.SUPPORT_TICKET.value) and
                        (AuditLogTable.entityId eq ticketId)
                }.orderBy(AuditLogTable.id to SortOrder.ASC)
                .map { row ->
                    StoredAudit(
                        clubId = row[AuditLogTable.clubId],
                        actorUserId = row[AuditLogTable.actorUserId],
                        actorRole = row[AuditLogTable.actorRole],
                        entityType = row[AuditLogTable.entityType],
                        entityId = row[AuditLogTable.entityId],
                        action = row[AuditLogTable.action],
                        fingerprint = row[AuditLogTable.fingerprint],
                        metadataJson = row[AuditLogTable.metadataJson],
                    )
                }
        }

    fun supportAuditCount(ticketId: Long): Long = transaction(database) { supportAuditCountInTransaction(ticketId) }

    fun assertStatusAudit(
        audit: StoredAudit,
        clubId: Long,
        actorId: Long,
        actorRole: Role,
        oldStatus: TicketStatus,
        newStatus: TicketStatus,
    ) {
        assertAuditTopLevel(audit, clubId, actorId, actorRole, StandardAuditAction.SUPPORT_STATUS_CHANGE)
        val metadata = audit.metadata()
        assertEquals(setOf("old_status", "new_status"), metadata.keys)
        assertEquals(oldStatus.wire, metadata.getValue("old_status").jsonPrimitive.content)
        assertEquals(newStatus.wire, metadata.getValue("new_status").jsonPrimitive.content)
    }

    fun assertReplyAudit(
        audit: StoredAudit,
        clubId: Long,
        actorId: Long,
        actorRole: Role,
        messageId: Long,
    ) {
        assertAuditTopLevel(audit, clubId, actorId, actorRole, StandardAuditAction.SUPPORT_REPLY)
        val metadata = audit.metadata()
        assertEquals(setOf("message_id"), metadata.keys)
        assertEquals(
            messageId,
            metadata
                .getValue("message_id")
                .jsonPrimitive
                .content
                .toLong(),
        )
    }

    fun assertPersistedReply(
        reply: SupportReplyResult,
        actorId: Long,
        text: String,
        attachments: String?,
    ) {
        assertEquals(TicketStatus.IN_PROGRESS, reply.ticket.status)
        assertEquals(actorId, reply.ticket.lastAgentId)
        assertEquals(reply.ticket.id, reply.replyMessage.ticketId)
        assertEquals(TicketSenderType.AGENT, reply.replyMessage.senderType)
        assertEquals(text, reply.replyMessage.text)
        assertEquals(attachments, reply.replyMessage.attachments)
    }

    suspend fun assertAllMutationsForbidden(
        ticketId: Long,
        actorId: Long,
    ) {
        mutationService().assign(ticketId, actorId).assertFailure(SupportServiceError.TicketForbidden)
        mutationService().reply(ticketId, actorId, "denied", null).assertFailure(
            SupportServiceError.TicketForbidden,
        )
        mutationService().setStatus(ticketId, actorId, TicketStatus.CLOSED).assertFailure(
            SupportServiceError.TicketForbidden,
        )
    }

    override fun close() {
        testDatabase.close()
    }

    private fun serviceAt(instant: Instant): SupportServiceImpl =
        SupportServiceImpl(SupportRepository(database, Clock.fixed(instant, ZoneOffset.UTC)))

    private fun supportAuditCountInTransaction(ticketId: Long): Long =
        AuditLogTable
            .selectAll()
            .where {
                (AuditLogTable.entityType eq StandardAuditEntityType.SUPPORT_TICKET.value) and
                    (AuditLogTable.entityId eq ticketId)
            }.count()

    private fun assertAuditTopLevel(
        audit: StoredAudit,
        clubId: Long,
        actorId: Long,
        actorRole: Role,
        action: StandardAuditAction,
    ) {
        assertEquals(clubId, audit.clubId)
        assertEquals(actorId, audit.actorUserId)
        assertEquals(actorRole.name, audit.actorRole)
        assertEquals(StandardAuditEntityType.SUPPORT_TICKET.value, audit.entityType)
        assertNotNull(audit.entityId)
        assertEquals(action.value, audit.action)
        assertTrue(audit.fingerprint.isNotBlank())
    }

    private companion object {
        val CREATED_AT: Instant = Instant.parse("2024-06-01T10:00:00Z")
        val MUTATED_AT: Instant = Instant.parse("2024-06-01T11:00:00Z")
        val READ_AT: Instant = Instant.parse("2024-06-01T12:00:00Z")
        const val AUDIT_FAILURE_CONSTRAINT = "support_mutation_test_reject_status_audit"
    }
}

private data class MutationSnapshot(
    val status: String,
    val lastAgentId: Long?,
    val updatedAt: Instant,
    val messageCount: Long,
    val auditCount: Long,
)

private data class StoredMessage(
    val id: Long,
    val senderType: String,
    val text: String,
    val attachments: String?,
)

private data class StoredAudit(
    val clubId: Long?,
    val actorUserId: Long?,
    val actorRole: String?,
    val entityType: String,
    val entityId: Long?,
    val action: String,
    val fingerprint: String,
    val metadataJson: String,
) {
    fun metadata(): JsonObject = Json.parseToJsonElement(metadataJson).jsonObject
}

private class ThrowingClock(
    private val failure: Throwable,
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = throw failure
}

private fun <T> SupportServiceResult<T>.successValue(): T {
    assertTrue(this is SupportServiceResult.Success, toString())
    return (this as SupportServiceResult.Success).value
}

private fun SupportServiceResult<*>.assertFailure(expected: SupportServiceError) {
    assertTrue(this is SupportServiceResult.Failure, toString())
    assertEquals(expected, (this as SupportServiceResult.Failure).error)
}

private fun SupportServiceResult<*>.assertDetailFreePersistenceFailure() {
    assertFailure(SupportServiceError.PersistenceFailure)
    val rendered = toString()
    assertFalse(rendered.contains("support_mutation_test_reject_status_audit", ignoreCase = true))
    assertFalse(rendered.contains("audit_log", ignoreCase = true))
    assertFalse(rendered.contains("CHECK", ignoreCase = true))
    assertFalse(rendered.contains("private", ignoreCase = true))
}

private object MutationTestUsersTable : Table("users") {
    val id = long("id").autoIncrement()
    val telegramUserId = long("telegram_user_id")
    val username = text("username").nullable()
    val displayName = text("display_name").nullable()
    val phoneE164 = text("phone_e164").nullable()
    override val primaryKey = PrimaryKey(id)
}

private object MutationTestClubsTable : Table("clubs") {
    val id = long("id").autoIncrement()
    val name = text("name")
    val description = text("description").nullable()
    val timezone = text("timezone")
    val adminChannelId = long("admin_channel_id").nullable()
    val bookingsTopicId = integer("bookings_topic_id").nullable()
    val checkinTopicId = integer("checkin_topic_id").nullable()
    val qaTopicId = integer("qa_topic_id").nullable()
    override val primaryKey = PrimaryKey(id)
}

private object MutationTestUserRolesTable : Table("user_roles") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val roleCode = text("role_code")
    val scopeType = text("scope_type")
    val scopeClubId = long("scope_club_id").nullable()
    override val primaryKey = PrimaryKey(id)
}

private object MutationTestUserRolePermissionsTable : Table("user_role_permissions") {
    val userRoleId = long("user_role_id")
    val permissionCode = text("permission_code")
    override val primaryKey = PrimaryKey(userRoleId, permissionCode)
}
