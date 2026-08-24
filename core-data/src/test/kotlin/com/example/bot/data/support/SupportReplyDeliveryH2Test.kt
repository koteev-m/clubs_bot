package com.example.bot.data.support

import com.example.bot.audit.StandardAuditAction
import com.example.bot.audit.StandardAuditEntityType
import com.example.bot.data.security.PermissionCodes
import com.example.bot.data.security.Role
import com.example.bot.support.SupportReplyDeliveryFailureCode
import com.example.bot.support.SupportReplyDeliveryStatus
import com.example.bot.support.SupportReplyResult
import com.example.bot.support.TicketSenderType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SupportReplyDeliveryH2Test {
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
    fun `reply transaction persists exact pending intent without private payload`() =
        runBlocking {
            val scenario = createPending(Role.CLUB_ADMIN, PRIVATE_REPLY, PRIVATE_ATTACHMENT)
            val repository = repository()

            val delivery = requireNotNull(repository.findReplyDelivery(scenario.reply.deliveryId))
            assertEquals(scenario.reply.replyMessage.id, delivery.replyMessageId)
            assertEquals(scenario.ticketId, delivery.ticketId)
            assertEquals(scenario.ownerUserId, delivery.recipientUserId)
            assertEquals(scenario.actorUserId, delivery.actingStaffUserId)
            assertEquals(Role.CLUB_ADMIN.name, delivery.actingRole)
            assertEquals(SupportReplyDeliveryStatus.PENDING, delivery.status)
            assertNull(delivery.failureCode)
            assertNull(delivery.completedAt)
            assertEquals(scenario.reply.replyMessage.createdAt, delivery.createdAt)
            assertEquals(delivery.createdAt, delivery.updatedAt)
            assertEquals(SupportReplyDeliveryStatus.PENDING, scenario.reply.deliveryStatus)

            val columns = deliveryColumns()
            assertEquals(
                setOf(
                    "id",
                    "reply_message_id",
                    "ticket_id",
                    "recipient_user_id",
                    "acting_staff_user_id",
                    "acting_role",
                    "status",
                    "failure_code",
                    "created_at",
                    "updated_at",
                    "completed_at",
                ),
                columns,
            )
            assertFalse(columns.any { it.contains("text") || it.contains("attachment") || it.contains("telegram") })

            val claimed = repository.claimReplyDelivery(delivery.id)
            assertTrue(claimed is SupportReplyDeliveryClaimResult.Claimed)
            val payload = (claimed as SupportReplyDeliveryClaimResult.Claimed).delivery
            assertEquals(PRIVATE_REPLY, payload.persistedReplyText)
            assertEquals(scenario.ticketId, payload.ticketId)
            assertEquals(scenario.clubId, payload.clubId)
            assertEquals(scenario.ownerUserId, payload.recipientUserId)
            assertEquals(telegramUserId(scenario.ownerUserId), payload.recipientTelegramUserId)
            assertEquals(scenario.actorUserId, payload.actingStaffUserId)
            assertEquals(Role.CLUB_ADMIN.name, payload.actingRole)
        }

    @Test
    fun `delivery intent failure rolls back ticket reply and both existing audits detail free`() =
        runBlocking {
            val clubId = fixture.insertClub("Intent rollback")
            val ownerUserId = fixture.insertUser("intent-owner")
            val actorUserId =
                fixture.insertAuthorizedActor(Role.MANAGER, clubId, PermissionCodes.SUPPORT_REPLY)
            val ticketId = fixture.createTicket(clubId, ownerUserId, "intent-question").ticket.id
            val before = fixture.snapshot(ticketId)
            transaction(fixture.database) {
                exec(
                    "ALTER TABLE support_reply_deliveries ADD CONSTRAINT " +
                        "$INTENT_FAILURE_CONSTRAINT CHECK (status <> 'pending')",
                )
            }

            val result =
                fixture.mutationService().reply(
                    ticketId = ticketId,
                    agentUserId = actorUserId,
                    text = PRIVATE_REPLY,
                    attachments = PRIVATE_ATTACHMENT,
                )

            result.assertDetailFreePersistenceFailure()
            assertFalse(result.toString().contains(INTENT_FAILURE_CONSTRAINT))
            assertFalse(result.toString().contains(PRIVATE_REPLY))
            assertEquals(before, fixture.snapshot(ticketId))
            assertEquals(0L, deliveryCount(ticketId))
        }

    @Test
    fun `conditional claim grants exactly one concurrent sender and terminal rows never reclaim`() =
        runBlocking {
            val scenario = createPending(Role.MANAGER, "claim body", null)
            val firstRepository = repository()
            val secondRepository = repository()

            val claims =
                coroutineScope {
                    listOf(firstRepository, secondRepository)
                        .map { candidate ->
                            async {
                                candidate.claimReplyDelivery(scenario.reply.deliveryId)
                            }
                        }.awaitAll()
                }

            assertEquals(1, claims.count { it is SupportReplyDeliveryClaimResult.Claimed })
            assertEquals(1, claims.count { it === SupportReplyDeliveryClaimResult.NotClaimed })
            assertEquals(
                SupportReplyDeliveryStatus.SENDING,
                repository().findReplyDelivery(scenario.reply.deliveryId)?.status,
            )
            assertEquals(
                SupportReplyDeliveryFinalizationResult.Finalized,
                repository().finalizeReplyDelivery(
                    deliveryId = scenario.reply.deliveryId,
                    resultStatus = SupportReplyDeliveryStatus.DELIVERED,
                    failureCode = null,
                ),
            )
            assertEquals(
                SupportReplyDeliveryClaimResult.NotClaimed,
                repository().claimReplyDelivery(scenario.reply.deliveryId),
            )
        }

    @Test
    fun `terminal results persist one exact private audit and duplicate finalization is inert`() =
        runBlocking {
            val cases =
                listOf(
                    TerminalCase(SupportReplyDeliveryStatus.DELIVERED, null),
                    TerminalCase(
                        SupportReplyDeliveryStatus.FAILED,
                        SupportReplyDeliveryFailureCode.TELEGRAM_REJECTED,
                    ),
                    TerminalCase(
                        SupportReplyDeliveryStatus.UNCONFIRMED,
                        SupportReplyDeliveryFailureCode.TRANSPORT_ERROR,
                    ),
                )

            cases.forEachIndexed { index, case ->
                val body = "$PRIVATE_REPLY-$index"
                val attachment = "$PRIVATE_ATTACHMENT-$index"
                val scenario = createPending(Role.CLUB_ADMIN, body, attachment)
                val repository = repository()
                assertTrue(
                    repository.claimReplyDelivery(scenario.reply.deliveryId) is
                        SupportReplyDeliveryClaimResult.Claimed,
                )

                assertEquals(
                    SupportReplyDeliveryFinalizationResult.Finalized,
                    repository.finalizeReplyDelivery(
                        scenario.reply.deliveryId,
                        case.status,
                        case.failureCode,
                    ),
                )
                val committed = requireNotNull(repository.findReplyDelivery(scenario.reply.deliveryId))
                assertEquals(case.status, committed.status)
                assertEquals(case.failureCode, committed.failureCode)
                assertNotNull(committed.completedAt)

                val resultAudits =
                    fixture
                        .supportAudits(scenario.ticketId)
                        .filter { it.action == StandardAuditAction.SUPPORT_DELIVERY_RESULT.value }
                assertEquals(1, resultAudits.size)
                val audit = resultAudits.single()
                assertEquals(scenario.clubId, audit.clubId)
                assertEquals(scenario.actorUserId, audit.actorUserId)
                assertEquals(Role.CLUB_ADMIN.name, audit.actorRole)
                assertEquals(StandardAuditEntityType.SUPPORT_TICKET.value, audit.entityType)
                assertEquals(scenario.ticketId, audit.entityId)
                assertTrue(audit.fingerprint.isNotBlank())
                val metadata = audit.metadata()
                val expectedKeys =
                    if (case.failureCode == null) {
                        setOf("result", "reply_message_id")
                    } else {
                        setOf("result", "reply_message_id", "failure_code")
                    }
                assertEquals(expectedKeys, metadata.keys)
                assertEquals(case.status.wire, metadata.getValue("result").jsonPrimitive.content)
                assertEquals(
                    scenario.reply.replyMessage.id
                        .toString(),
                    metadata.getValue("reply_message_id").jsonPrimitive.content,
                )
                case.failureCode?.let { failureCode ->
                    assertEquals(
                        failureCode.wire,
                        metadata.getValue("failure_code").jsonPrimitive.content,
                    )
                }
                val renderedAudit = audit.metadataJson
                assertFalse(renderedAudit.contains(body))
                assertFalse(renderedAudit.contains(attachment))
                assertFalse(renderedAudit.contains(telegramUserId(scenario.ownerUserId).toString()))
                assertFalse(renderedAudit.contains("exception", ignoreCase = true))

                assertEquals(
                    SupportReplyDeliveryFinalizationResult.NotFinalized,
                    repository.finalizeReplyDelivery(
                        scenario.reply.deliveryId,
                        case.status,
                        case.failureCode,
                    ),
                )
                assertEquals(
                    1,
                    fixture
                        .supportAudits(scenario.ticketId)
                        .count { it.action == StandardAuditAction.SUPPORT_DELIVERY_RESULT.value },
                )
                assertEquals(
                    committed,
                    SupportRepository(fixture.database, Clock.systemUTC())
                        .findReplyDelivery(scenario.reply.deliveryId),
                )
            }
        }

    @Test
    fun `audit insert failure rolls back terminal update and exposes no false terminal result`() =
        runBlocking {
            val scenario = createPending(Role.MANAGER, PRIVATE_REPLY, PRIVATE_ATTACHMENT)
            val repository = repository()
            assertTrue(
                repository.claimReplyDelivery(scenario.reply.deliveryId) is
                    SupportReplyDeliveryClaimResult.Claimed,
            )
            transaction(fixture.database) {
                exec(
                    "ALTER TABLE audit_log ADD CONSTRAINT $RESULT_AUDIT_FAILURE_CONSTRAINT " +
                        "CHECK (action <> '${StandardAuditAction.SUPPORT_DELIVERY_RESULT.value}')",
                )
            }

            val failure =
                try {
                    repository.finalizeReplyDelivery(
                        scenario.reply.deliveryId,
                        SupportReplyDeliveryStatus.DELIVERED,
                        null,
                    )
                    error("Expected delivery result audit failure")
                } catch (failure: Exception) {
                    failure
                }
            assertFalse(failure.toString().contains(PRIVATE_REPLY))
            val delivery = requireNotNull(repository.findReplyDelivery(scenario.reply.deliveryId))
            assertEquals(SupportReplyDeliveryStatus.SENDING, delivery.status)
            assertNull(delivery.failureCode)
            assertNull(delivery.completedAt)
            assertEquals(
                0,
                fixture
                    .supportAudits(scenario.ticketId)
                    .count { it.action == StandardAuditAction.SUPPORT_DELIVERY_RESULT.value },
            )
        }

    @Test
    fun `legacy agent guest and system messages expose null while exact reply owns its status`() =
        runBlocking {
            val clubId = fixture.insertClub("Legacy delivery status")
            val ownerUserId = fixture.insertUser("legacy-owner")
            val legacyTicket = fixture.createTicket(clubId, ownerUserId, "guest body")
            val legacyAgentId = insertMessage(legacyTicket.ticket.id, TicketSenderType.AGENT, "legacy agent")
            val systemId = insertMessage(legacyTicket.ticket.id, TicketSenderType.SYSTEM, "system body")
            val actorUserId =
                fixture.insertAuthorizedActor(Role.MANAGER, clubId, PermissionCodes.SUPPORT_REPLY)
            val deliveredTicket = fixture.createTicket(clubId, ownerUserId, "second guest")
            val deliveredReply =
                fixture
                    .mutationService()
                    .reply(deliveredTicket.ticket.id, actorUserId, "tracked agent", null)
                    .successValue()
            val repository = repository()
            assertTrue(
                repository.claimReplyDelivery(deliveredReply.deliveryId) is
                    SupportReplyDeliveryClaimResult.Claimed,
            )
            assertEquals(
                SupportReplyDeliveryFinalizationResult.Finalized,
                repository.finalizeReplyDelivery(
                    deliveredReply.deliveryId,
                    SupportReplyDeliveryStatus.FAILED,
                    SupportReplyDeliveryFailureCode.RECIPIENT_UNAVAILABLE,
                ),
            )

            val legacyThread =
                fixture.freshService().getStaffTicket(legacyTicket.ticket.id, setOf(clubId)).successValue()
            assertTrue(legacyThread.messages.all { it.deliveryStatus == null })
            assertNull(legacyThread.messages.single { it.id == legacyTicket.initialMessage.id }.deliveryStatus)
            assertNull(legacyThread.messages.single { it.id == legacyAgentId }.deliveryStatus)
            assertNull(legacyThread.messages.single { it.id == systemId }.deliveryStatus)

            val trackedThread =
                fixture.freshService().getStaffTicket(deliveredTicket.ticket.id, setOf(clubId)).successValue()
            assertNull(trackedThread.messages.first().deliveryStatus)
            assertEquals(SupportReplyDeliveryStatus.FAILED, trackedThread.messages.last().deliveryStatus)
            assertFalse(trackedThread.messages.any { it.text == "legacy agent" })
        }

    @Test
    fun `migration constraints reject duplicate unsupported and null coded non success rows`() =
        runBlocking {
            val duplicateScenario = createPending(Role.MANAGER, "duplicate", null)
            assertThrows<Exception> {
                transaction(fixture.database) {
                    SupportReplyDeliveriesTable.insert {
                        it[replyMessageId] = duplicateScenario.reply.replyMessage.id
                        it[ticketId] = duplicateScenario.ticketId
                        it[recipientUserId] = duplicateScenario.ownerUserId
                        it[actingStaffUserId] = duplicateScenario.actorUserId
                        it[actingRole] = Role.MANAGER.name
                        it[status] = SupportReplyDeliveryStatus.PENDING.wire
                        it[failureCode] = null
                        it[createdAt] = TEST_TIME.atOffset(ZoneOffset.UTC)
                        it[updatedAt] = TEST_TIME.atOffset(ZoneOffset.UTC)
                        it[completedAt] = null
                    }
                }
            }

            val unsupported = createPending(Role.MANAGER, "unsupported", null)
            assertRejectedUpdate(unsupported.reply.deliveryId, "unsupported", null, null)

            val failedWithoutCode = createPending(Role.MANAGER, "failed null", null)
            assertRejectedUpdate(
                failedWithoutCode.reply.deliveryId,
                SupportReplyDeliveryStatus.FAILED.wire,
                null,
                TEST_TIME,
            )

            val unconfirmedWithoutCode = createPending(Role.MANAGER, "unconfirmed null", null)
            assertRejectedUpdate(
                unconfirmedWithoutCode.reply.deliveryId,
                SupportReplyDeliveryStatus.UNCONFIRMED.wire,
                null,
                TEST_TIME,
            )

            val allowedCases =
                listOf(
                    Triple(SupportReplyDeliveryStatus.SENDING, null, null),
                    Triple(SupportReplyDeliveryStatus.DELIVERED, null, TEST_TIME),
                    Triple(
                        SupportReplyDeliveryStatus.FAILED,
                        SupportReplyDeliveryFailureCode.CLIENT_UNAVAILABLE,
                        TEST_TIME,
                    ),
                    Triple(
                        SupportReplyDeliveryStatus.UNCONFIRMED,
                        SupportReplyDeliveryFailureCode.TIMEOUT,
                        TEST_TIME,
                    ),
                )
            allowedCases.forEachIndexed { index, (status, code, completedAt) ->
                val scenario = createPending(Role.CLUB_ADMIN, "allowed-$index", null)
                transaction(fixture.database) {
                    assertEquals(
                        1,
                        SupportReplyDeliveriesTable.update({
                            SupportReplyDeliveriesTable.id eq scenario.reply.deliveryId
                        }) {
                            it[SupportReplyDeliveriesTable.status] = status.wire
                            it[failureCode] = code?.wire
                            it[SupportReplyDeliveriesTable.completedAt] = completedAt?.atOffset(ZoneOffset.UTC)
                        },
                    )
                }
            }
        }

    private suspend fun createPending(
        role: Role,
        body: String,
        attachment: String?,
    ): DeliveryScenario {
        val clubId = fixture.insertClub("Delivery $role $body")
        val ownerUserId = fixture.insertUser("delivery-owner-$body")
        val actorUserId = fixture.insertAuthorizedActor(role, clubId, PermissionCodes.SUPPORT_REPLY)
        val ticketId = fixture.createTicket(clubId, ownerUserId, "question-$body").ticket.id
        val reply =
            fixture
                .mutationService()
                .reply(ticketId, actorUserId, body, attachment)
                .successValue()
        return DeliveryScenario(clubId, ticketId, ownerUserId, actorUserId, reply)
    }

    private fun repository(): SupportRepository =
        SupportRepository(fixture.database, Clock.fixed(TEST_TIME, ZoneOffset.UTC))

    private fun deliveryCount(ticketId: Long): Long =
        transaction(fixture.database) {
            SupportReplyDeliveriesTable
                .selectAll()
                .where { SupportReplyDeliveriesTable.ticketId eq ticketId }
                .count()
        }

    private fun deliveryColumns(): Set<String> =
        transaction(fixture.database) {
            exec(
                "SELECT \"COLUMN_NAME\" FROM \"INFORMATION_SCHEMA\".\"COLUMNS\" " +
                    "WHERE \"TABLE_NAME\" = 'support_reply_deliveries'",
            ) { result ->
                buildSet {
                    while (result.next()) {
                        add(result.getString(1))
                    }
                }
            }.orEmpty()
        }

    private fun telegramUserId(userId: Long): Long =
        transaction(fixture.database) {
            DeliveryTestUsersTable
                .selectAll()
                .where { DeliveryTestUsersTable.id eq userId }
                .single()[DeliveryTestUsersTable.telegramUserId]
        }

    private fun insertMessage(
        ticketId: Long,
        senderType: TicketSenderType,
        text: String,
    ): Long =
        transaction(fixture.database) {
            TicketMessagesTable.insert {
                it[TicketMessagesTable.ticketId] = ticketId
                it[TicketMessagesTable.senderType] = senderType.wire
                it[TicketMessagesTable.text] = text
                it[TicketMessagesTable.attachments] = null
                it[TicketMessagesTable.createdAt] = TEST_TIME.atOffset(ZoneOffset.UTC)
            }[TicketMessagesTable.id]
        }

    private suspend fun assertRejectedUpdate(
        deliveryId: Long,
        status: String,
        failureCode: SupportReplyDeliveryFailureCode?,
        completedAt: Instant?,
    ) {
        assertThrows<Exception> {
            transaction(fixture.database) {
                SupportReplyDeliveriesTable.update({ SupportReplyDeliveriesTable.id eq deliveryId }) {
                    it[SupportReplyDeliveriesTable.status] = status
                    it[SupportReplyDeliveriesTable.failureCode] = failureCode?.wire
                    it[SupportReplyDeliveriesTable.completedAt] = completedAt?.atOffset(ZoneOffset.UTC)
                }
            }
        }
        assertEquals(SupportReplyDeliveryStatus.PENDING, repository().findReplyDelivery(deliveryId)?.status)
    }

    private data class DeliveryScenario(
        val clubId: Long,
        val ticketId: Long,
        val ownerUserId: Long,
        val actorUserId: Long,
        val reply: SupportReplyResult,
    )

    private data class TerminalCase(
        val status: SupportReplyDeliveryStatus,
        val failureCode: SupportReplyDeliveryFailureCode?,
    )

    private companion object {
        val TEST_TIME: Instant = Instant.parse("2026-08-23T10:00:00Z")
        const val PRIVATE_REPLY = "PRIVATE_DELIVERY_REPLY_SENTINEL"
        const val PRIVATE_ATTACHMENT = "PRIVATE_DELIVERY_ATTACHMENT_SENTINEL"
        const val INTENT_FAILURE_CONSTRAINT = "support_delivery_test_reject_intent"
        const val RESULT_AUDIT_FAILURE_CONSTRAINT = "support_delivery_test_reject_result_audit"
    }
}

private object DeliveryTestUsersTable : Table("users") {
    val id = long("id")
    val telegramUserId = long("telegram_user_id")

    override val primaryKey = PrimaryKey(id)
}
