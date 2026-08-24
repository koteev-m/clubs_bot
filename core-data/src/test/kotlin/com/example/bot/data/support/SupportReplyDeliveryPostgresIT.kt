package com.example.bot.data.support

import com.example.bot.audit.StandardAuditAction
import com.example.bot.data.audit.AuditLogRepositoryImpl
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.junit.jupiter.Testcontainers
import testing.RequiresDocker
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Types
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CyclicBarrier

@RequiresDocker
@Tag("it")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SupportReplyDeliveryPostgresIT : SupportMutationPostgresITFixture() {
    private var telegramUserSequence = 8_800_860_000L

    @AfterEach
    fun dropDeliveryTestConstraints() {
        transaction(database) {
            exec(
                "ALTER TABLE support_reply_deliveries DROP CONSTRAINT IF EXISTS " +
                    INTENT_FAILURE_CONSTRAINT,
            )
            exec(
                "ALTER TABLE audit_log DROP CONSTRAINT IF EXISTS " +
                    RESULT_AUDIT_FAILURE_CONSTRAINT,
            )
        }
    }

    @Test
    fun `V060 follows V059 preserves legacy rows and installs exact private schema`() {
        val databaseName = "support_v060_${UUID.randomUUID().toString().replace("-", "")}"
        postgres.createConnection("").use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE DATABASE $databaseName")
            }
        }
        val jdbcUrl = "jdbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/$databaseName"
        val locations = arrayOf("classpath:db/migration/common", "classpath:db/migration/postgresql")
        val v059Flyway =
            Flyway
                .configure()
                .dataSource(jdbcUrl, postgres.username, postgres.password)
                .locations(*locations)
                .target(PRE_DELIVERY_VERSION)
                .load()
        v059Flyway.migrate()
        assertEquals(
            EXPECTED_PRE_DELIVERY_VERSION,
            v059Flyway
                .info()
                .current()
                ?.version
                ?.toString(),
        )

        lateinit var legacy: MigrationLegacyFixture
        lateinit var before: MigrationPreservedRows
        migrationConnection(jdbcUrl).use { connection ->
            legacy = insertMigrationLegacyFixture(connection)
            before = readPreservedRows(connection, legacy.ticketId)
        }

        val v060Flyway =
            Flyway
                .configure()
                .dataSource(jdbcUrl, postgres.username, postgres.password)
                .locations(*locations)
                .target(DELIVERY_VERSION)
                .load()
        assertEquals(1, v060Flyway.migrate().migrationsExecuted)
        assertEquals(
            EXPECTED_DELIVERY_VERSION,
            v060Flyway
                .info()
                .current()
                ?.version
                ?.toString(),
        )

        migrationConnection(jdbcUrl).use { connection ->
            assertEquals(before, readPreservedRows(connection, legacy.ticketId))
            assertEquals(0L, deliveryRowCount(connection))
            assertDeliveryColumns(connection)
            assertDeliveryConstraints(connection)
            assertDeliveryForeignKeys(connection)
            assertDeliveryIndexes(connection)

            val allowed =
                listOf(
                    RawDeliveryCase("pending", null, completed = false),
                    RawDeliveryCase("sending", null, completed = false),
                    RawDeliveryCase("delivered", null, completed = true),
                    RawDeliveryCase("failed", "client_unavailable", completed = true),
                    RawDeliveryCase("unconfirmed", "transport_error", completed = true),
                )
            val allowedMessageIds =
                allowed.mapIndexed { index, case ->
                    val messageId =
                        insertMigrationMessage(
                            connection,
                            legacy.ticketId,
                            TicketSenderType.AGENT.wire,
                            "allowed-$index",
                            null,
                        )
                    insertRawDelivery(connection, legacy, messageId, case)
                    messageId
                }
            assertEquals(allowed.size.toLong(), deliveryRowCount(connection))

            assertThrows(SQLException::class.java) {
                insertRawDelivery(connection, legacy, allowedMessageIds.first(), allowed.first())
            }
            assertRejectedRawDelivery(
                connection,
                legacy,
                RawDeliveryCase("unsupported", null, completed = false),
            )
            assertRejectedRawDelivery(
                connection,
                legacy,
                RawDeliveryCase("failed", null, completed = true),
            )
            assertRejectedRawDelivery(
                connection,
                legacy,
                RawDeliveryCase("unconfirmed", null, completed = true),
            )
            assertEquals(allowed.size.toLong(), deliveryRowCount(connection))
        }
    }

    @Test
    fun `reply and pending intent commit atomically while rejected intent rolls everything back`() =
        runBlocking {
            val successful = createPending(Role.CLUB_ADMIN, PRIVATE_REPLY, PRIVATE_ATTACHMENT)
            val repository = repository()
            val delivery = requireNotNull(repository.findReplyDelivery(successful.reply.deliveryId))
            assertEquals(successful.reply.replyMessage.id, delivery.replyMessageId)
            assertEquals(successful.ticketId, delivery.ticketId)
            assertEquals(successful.ownerUserId, delivery.recipientUserId)
            assertEquals(successful.actorUserId, delivery.actingStaffUserId)
            assertEquals(Role.CLUB_ADMIN.name, delivery.actingRole)
            assertEquals(SupportReplyDeliveryStatus.PENDING, delivery.status)
            assertNull(delivery.failureCode)
            assertNull(delivery.completedAt)
            assertEquals(2L, auditCount(successful.ticketId))

            val clubId = insertClub("Rejected delivery intent")
            val ownerUserId = insertUser(nextTelegramUserId())
            val actorUserId = insertUser(nextTelegramUserId())
            val assignmentId = insertAssignment(actorUserId, Role.MANAGER, "CLUB", clubId)
            grantPermission(assignmentId, PermissionCodes.SUPPORT_REPLY)
            val rollbackRepository = repository()
            val rollbackTicket =
                createTicket(
                    rollbackRepository,
                    clubId,
                    ownerUserId,
                    "rollback question",
                    null,
                )
            val beforeTicket = requireNotNull(rollbackRepository.findTicket(rollbackTicket.id))
            val beforeMessageCount = messageCount(rollbackTicket.id)
            val beforeAuditCount = auditCount(rollbackTicket.id)
            transaction(database) {
                exec(
                    "ALTER TABLE support_reply_deliveries ADD CONSTRAINT $INTENT_FAILURE_CONSTRAINT " +
                        "CHECK (ticket_id <> ${rollbackTicket.id} OR status <> 'pending')",
                )
            }

            val result =
                SupportServiceImpl(rollbackRepository).reply(
                    ticketId = rollbackTicket.id,
                    agentUserId = actorUserId,
                    text = ROLLBACK_PRIVATE_REPLY,
                    attachments = ROLLBACK_PRIVATE_ATTACHMENT,
                )

            assertDetailFreePersistenceFailure(
                result,
                INTENT_FAILURE_CONSTRAINT,
                ROLLBACK_PRIVATE_REPLY,
                ROLLBACK_PRIVATE_ATTACHMENT,
            )
            assertEquals(beforeTicket, rollbackRepository.findTicket(rollbackTicket.id))
            assertEquals(beforeMessageCount, messageCount(rollbackTicket.id))
            assertEquals(beforeAuditCount, auditCount(rollbackTicket.id))
            assertEquals(0L, deliveryCount(rollbackTicket.id))
        }

    @Test
    fun `concurrent conditional claim grants exactly one sender`() =
        runBlocking {
            val scenario = createPending(Role.MANAGER, "claim persisted text", null)
            val firstRepository = repository()
            val secondRepository = repository()
            val barrier = CyclicBarrier(2)

            val claims =
                coroutineScope {
                    listOf(firstRepository, secondRepository)
                        .map { candidate ->
                            async(concurrencyDispatcher) {
                                barrier.await()
                                candidate.claimReplyDelivery(scenario.reply.deliveryId)
                            }
                        }.awaitAll()
                }

            assertEquals(1, claims.count { it is SupportReplyDeliveryClaimResult.Claimed })
            assertEquals(1, claims.count { it === SupportReplyDeliveryClaimResult.NotClaimed })
            val claimed = claims.filterIsInstance<SupportReplyDeliveryClaimResult.Claimed>().single().delivery
            assertEquals(scenario.reply.replyMessage.id, claimed.replyMessageId)
            assertEquals(scenario.ticketId, claimed.ticketId)
            assertEquals(scenario.clubId, claimed.clubId)
            assertEquals(scenario.ownerUserId, claimed.recipientUserId)
            assertEquals(scenario.ownerTelegramUserId, claimed.recipientTelegramUserId)
            assertEquals(scenario.actorUserId, claimed.actingStaffUserId)
            assertEquals(Role.MANAGER.name, claimed.actingRole)
            assertEquals("claim persisted text", claimed.persistedReplyText)
            assertEquals(
                SupportReplyDeliveryStatus.SENDING,
                repository().findReplyDelivery(scenario.reply.deliveryId)?.status,
            )
            assertEquals(
                0,
                resultAudits(scenario.clubId, scenario.ticketId).size,
            )
        }

    @Test
    fun `all terminal outcomes commit one allowlisted private audit and duplicate finalization is inert`() =
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
                        SupportReplyDeliveryFailureCode.TIMEOUT,
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
                        deliveryId = scenario.reply.deliveryId,
                        resultStatus = case.status,
                        failureCode = case.failureCode,
                    ),
                )
                val committed = requireNotNull(repository.findReplyDelivery(scenario.reply.deliveryId))
                assertEquals(case.status, committed.status)
                assertEquals(case.failureCode, committed.failureCode)
                assertNotNull(committed.completedAt)

                val audits = resultAudits(scenario.clubId, scenario.ticketId)
                assertEquals(1, audits.size)
                val audit = audits.single()
                assertAuditEnvelope(
                    audit,
                    scenario.clubId,
                    scenario.actorUserId,
                    Role.CLUB_ADMIN,
                    scenario.ticketId,
                )
                assertEquals(
                    "SUPPORT_TICKET:SUPPORT_DELIVERY_RESULT:${scenario.reply.deliveryId}",
                    audit.fingerprint,
                )
                val metadata = audit.metadata.jsonObject
                val expectedKeys =
                    if (case.failureCode == null) {
                        setOf("result", "reply_message_id")
                    } else {
                        setOf("result", "reply_message_id", "failure_code")
                    }
                assertEquals(expectedKeys, metadata.keys)
                assertEquals(case.status.wire, metadata.getValue("result").jsonPrimitive.content)
                assertEquals(
                    scenario.reply.replyMessage.id,
                    metadata.getValue("reply_message_id").jsonPrimitive.long,
                )
                case.failureCode?.let { code ->
                    assertEquals(code.wire, metadata.getValue("failure_code").jsonPrimitive.content)
                }
                val renderedMetadata = audit.metadata.toString()
                listOf(
                    body,
                    attachment,
                    scenario.ownerTelegramUserId.toString(),
                    "exception",
                    "stack trace",
                ).forEach { forbidden ->
                    assertFalse(renderedMetadata.contains(forbidden, ignoreCase = true), renderedMetadata)
                }

                assertEquals(
                    SupportReplyDeliveryFinalizationResult.NotFinalized,
                    repository.finalizeReplyDelivery(
                        deliveryId = scenario.reply.deliveryId,
                        resultStatus = case.status,
                        failureCode = case.failureCode,
                    ),
                )
                assertEquals(1, resultAudits(scenario.clubId, scenario.ticketId).size)
                assertEquals(
                    committed,
                    SupportRepository(database, Clock.systemUTC())
                        .findReplyDelivery(scenario.reply.deliveryId),
                )
                assertEquals(
                    SupportReplyDeliveryClaimResult.NotClaimed,
                    repository.claimReplyDelivery(scenario.reply.deliveryId),
                )
            }
        }

    @Test
    fun `result audit failure rolls terminal update back to sending`() =
        runBlocking {
            val scenario = createPending(Role.MANAGER, PRIVATE_REPLY, PRIVATE_ATTACHMENT)
            val repository = repository()
            assertTrue(
                repository.claimReplyDelivery(scenario.reply.deliveryId) is
                    SupportReplyDeliveryClaimResult.Claimed,
            )
            transaction(database) {
                exec(
                    "ALTER TABLE audit_log ADD CONSTRAINT $RESULT_AUDIT_FAILURE_CONSTRAINT " +
                        "CHECK (action <> '${StandardAuditAction.SUPPORT_DELIVERY_RESULT.value}')",
                )
            }

            val failure =
                try {
                    repository.finalizeReplyDelivery(
                        deliveryId = scenario.reply.deliveryId,
                        resultStatus = SupportReplyDeliveryStatus.DELIVERED,
                        failureCode = null,
                    )
                    error("result audit insert must fail")
                } catch (actual: Exception) {
                    actual
                }

            assertFalse(failure.toString().contains(PRIVATE_REPLY))
            val retained = requireNotNull(repository.findReplyDelivery(scenario.reply.deliveryId))
            assertEquals(SupportReplyDeliveryStatus.SENDING, retained.status)
            assertNull(retained.failureCode)
            assertNull(retained.completedAt)
            assertEquals(0, resultAudits(scenario.clubId, scenario.ticketId).size)
        }

    @Test
    fun `fresh staff thread reads only exact message and ticket delivery statuses`() =
        runBlocking {
            val clubId = insertClub("No cross delivery mixing")
            val ownerUserId = insertUser(nextTelegramUserId())
            val actorUserId = insertUser(nextTelegramUserId())
            val assignmentId = insertAssignment(actorUserId, Role.MANAGER, "CLUB", clubId)
            grantPermission(assignmentId, PermissionCodes.SUPPORT_REPLY)
            val repository = repository()
            val service = SupportServiceImpl(repository)
            val deliveredTicket = createTicket(repository, clubId, ownerUserId, "delivered guest", null)
            val failedTicket = createTicket(repository, clubId, ownerUserId, "failed guest", null)
            val legacyTicket = createTicket(repository, clubId, ownerUserId, "legacy guest", null)
            val deliveredReply =
                success(service.reply(deliveredTicket.id, actorUserId, "delivered agent", null))
            val failedReply = success(service.reply(failedTicket.id, actorUserId, "failed agent", null))
            val systemMessageId = insertMessage(deliveredTicket.id, TicketSenderType.SYSTEM, "system")
            val legacyAgentMessageId = insertMessage(legacyTicket.id, TicketSenderType.AGENT, "legacy agent")

            assertTrue(
                repository.claimReplyDelivery(deliveredReply.deliveryId) is
                    SupportReplyDeliveryClaimResult.Claimed,
            )
            assertEquals(
                SupportReplyDeliveryFinalizationResult.Finalized,
                repository.finalizeReplyDelivery(
                    deliveredReply.deliveryId,
                    SupportReplyDeliveryStatus.DELIVERED,
                    null,
                ),
            )
            assertTrue(
                repository.claimReplyDelivery(failedReply.deliveryId) is
                    SupportReplyDeliveryClaimResult.Claimed,
            )
            assertEquals(
                SupportReplyDeliveryFinalizationResult.Finalized,
                repository.finalizeReplyDelivery(
                    failedReply.deliveryId,
                    SupportReplyDeliveryStatus.FAILED,
                    SupportReplyDeliveryFailureCode.RECIPIENT_UNAVAILABLE,
                ),
            )
            insertCrossTicketDelivery(
                replyMessageId = legacyAgentMessageId,
                ticketId = deliveredTicket.id,
                recipientUserId = ownerUserId,
                actingStaffUserId = actorUserId,
            )

            val freshService = SupportServiceImpl(SupportRepository(database, Clock.systemUTC()))
            val deliveredThread = success(freshService.getStaffTicket(deliveredTicket.id, setOf(clubId)))
            val failedThread = success(freshService.getStaffTicket(failedTicket.id, setOf(clubId)))
            val legacyThread = success(freshService.getStaffTicket(legacyTicket.id, setOf(clubId)))

            assertNull(deliveredThread.messages.single { it.senderType == TicketSenderType.GUEST }.deliveryStatus)
            assertEquals(
                SupportReplyDeliveryStatus.DELIVERED,
                deliveredThread.messages.single { it.id == deliveredReply.replyMessage.id }.deliveryStatus,
            )
            assertNull(deliveredThread.messages.single { it.id == systemMessageId }.deliveryStatus)
            assertFalse(deliveredThread.messages.any { it.id == failedReply.replyMessage.id })
            assertFalse(deliveredThread.messages.any { it.id == legacyAgentMessageId })

            assertEquals(
                SupportReplyDeliveryStatus.FAILED,
                failedThread.messages.single { it.id == failedReply.replyMessage.id }.deliveryStatus,
            )
            assertFalse(failedThread.messages.any { it.id == deliveredReply.replyMessage.id })
            assertNull(legacyThread.messages.single { it.id == legacyAgentMessageId }.deliveryStatus)
            assertTrue(legacyThread.messages.all { it.deliveryStatus == null })
            assertFalse(legacyThread.messages.any { it.text == "delivered agent" || it.text == "failed agent" })
        }

    private suspend fun createPending(
        role: Role,
        body: String,
        attachment: String?,
    ): DeliveryScenario {
        val clubId = insertClub("Delivery $role ${UUID.randomUUID()}")
        val ownerTelegramUserId = nextTelegramUserId()
        val ownerUserId = insertUser(ownerTelegramUserId)
        val actorUserId = insertUser(nextTelegramUserId())
        val assignmentId = insertAssignment(actorUserId, role, "CLUB", clubId)
        grantPermission(assignmentId, PermissionCodes.SUPPORT_REPLY)
        val repository = repository()
        val ticket = createTicket(repository, clubId, ownerUserId, "question", null)
        val reply =
            success(
                SupportServiceImpl(repository).reply(
                    ticketId = ticket.id,
                    agentUserId = actorUserId,
                    text = body,
                    attachments = attachment,
                ),
            )
        assertEquals(SupportReplyDeliveryStatus.PENDING, reply.deliveryStatus)
        return DeliveryScenario(
            clubId = clubId,
            ticketId = ticket.id,
            ownerUserId = ownerUserId,
            ownerTelegramUserId = ownerTelegramUserId,
            actorUserId = actorUserId,
            reply = reply,
        )
    }

    private fun repository(): SupportRepository = SupportRepository(database, fixedClock)

    private fun nextTelegramUserId(): Long = ++telegramUserSequence

    private fun deliveryCount(ticketId: Long): Long =
        transaction(database) {
            SupportReplyDeliveriesTable
                .selectAll()
                .where { SupportReplyDeliveriesTable.ticketId eq ticketId }
                .count()
        }

    private suspend fun resultAudits(
        clubId: Long,
        ticketId: Long,
    ) = AuditLogRepositoryImpl(database, fixedClock)
        .listForClub(clubId, limit = 100, offset = 0)
        .forTicket(ticketId)
        .withAction(StandardAuditAction.SUPPORT_DELIVERY_RESULT)

    private fun insertMessage(
        ticketId: Long,
        senderType: TicketSenderType,
        text: String,
    ): Long =
        transaction(database) {
            TicketMessagesTable.insert {
                it[TicketMessagesTable.ticketId] = ticketId
                it[TicketMessagesTable.senderType] = senderType.wire
                it[TicketMessagesTable.text] = text
                it[TicketMessagesTable.attachments] = null
                it[TicketMessagesTable.createdAt] = fixedInstant.atOffset(ZoneOffset.UTC)
            }[TicketMessagesTable.id]
        }

    private fun insertCrossTicketDelivery(
        replyMessageId: Long,
        ticketId: Long,
        recipientUserId: Long,
        actingStaffUserId: Long,
    ) {
        transaction(database) {
            SupportReplyDeliveriesTable.insert {
                it[SupportReplyDeliveriesTable.replyMessageId] = replyMessageId
                it[SupportReplyDeliveriesTable.ticketId] = ticketId
                it[SupportReplyDeliveriesTable.recipientUserId] = recipientUserId
                it[SupportReplyDeliveriesTable.actingStaffUserId] = actingStaffUserId
                it[SupportReplyDeliveriesTable.actingRole] = Role.MANAGER.name
                it[SupportReplyDeliveriesTable.status] = SupportReplyDeliveryStatus.PENDING.wire
                it[SupportReplyDeliveriesTable.failureCode] = null
                it[SupportReplyDeliveriesTable.createdAt] = fixedInstant.atOffset(ZoneOffset.UTC)
                it[SupportReplyDeliveriesTable.updatedAt] = fixedInstant.atOffset(ZoneOffset.UTC)
                it[SupportReplyDeliveriesTable.completedAt] = null
            }
        }
    }

    private fun migrationConnection(jdbcUrl: String): Connection =
        java.sql.DriverManager.getConnection(jdbcUrl, postgres.username, postgres.password)

    private data class DeliveryScenario(
        val clubId: Long,
        val ticketId: Long,
        val ownerUserId: Long,
        val ownerTelegramUserId: Long,
        val actorUserId: Long,
        val reply: SupportReplyResult,
    )

    private data class TerminalCase(
        val status: SupportReplyDeliveryStatus,
        val failureCode: SupportReplyDeliveryFailureCode?,
    )

    private companion object {
        const val PRE_DELIVERY_VERSION = "59"
        const val EXPECTED_PRE_DELIVERY_VERSION = "059"
        const val DELIVERY_VERSION = "60"
        const val EXPECTED_DELIVERY_VERSION = "060"
        const val PRIVATE_REPLY = "POSTGRES_PRIVATE_REPLY_SENTINEL"
        const val PRIVATE_ATTACHMENT = "POSTGRES_PRIVATE_ATTACHMENT_SENTINEL"
        const val ROLLBACK_PRIVATE_REPLY = "POSTGRES_ROLLBACK_PRIVATE_REPLY"
        const val ROLLBACK_PRIVATE_ATTACHMENT = "POSTGRES_ROLLBACK_PRIVATE_ATTACHMENT"
        const val INTENT_FAILURE_CONSTRAINT = "support_delivery_pg_reject_intent"
        const val RESULT_AUDIT_FAILURE_CONSTRAINT = "support_delivery_pg_reject_result_audit"
    }
}

private data class MigrationLegacyFixture(
    val clubId: Long,
    val ownerUserId: Long,
    val actorUserId: Long,
    val ticketId: Long,
)

private data class MigrationPreservedRows(
    val tickets: List<List<String?>>,
    val messages: List<List<String?>>,
    val audits: List<List<String?>>,
)

private data class RawDeliveryCase(
    val status: String,
    val failureCode: String?,
    val completed: Boolean,
)

private data class DeliveryForeignKey(
    val column: String,
    val referencedTable: String,
    val referencedColumn: String,
    val deleteRule: String,
)

private fun insertMigrationLegacyFixture(connection: Connection): MigrationLegacyFixture {
    val clubId =
        returningId(
            connection,
            """
            INSERT INTO clubs (name, description, timezone, admin_channel_id)
            VALUES ('V060 preservation club', NULL, 'Europe/Moscow', NULL)
            RETURNING id
            """.trimIndent(),
        )
    val ownerUserId = insertMigrationUser(connection, 8_800_860_901L, "v060-owner")
    val actorUserId = insertMigrationUser(connection, 8_800_860_902L, "v060-actor")
    val ticketId =
        returningId(
            connection,
            """
            INSERT INTO tickets (
                club_id, user_id, booking_id, list_entry_id, topic, status,
                created_at, updated_at, last_agent_id, resolution_rating
            ) VALUES (?, ?, NULL, NULL, 'other', 'new', ?, ?, NULL, NULL)
            RETURNING id
            """.trimIndent(),
        ) { statement ->
            statement.setLong(1, clubId)
            statement.setLong(2, ownerUserId)
            statement.setObject(3, MIGRATION_TIME)
            statement.setObject(4, MIGRATION_TIME.plusMinutes(1))
        }
    insertMigrationMessage(connection, ticketId, TicketSenderType.GUEST.wire, "legacy guest", "guest-attachment")
    val legacyAgentMessageId =
        insertMigrationMessage(
            connection,
            ticketId,
            TicketSenderType.AGENT.wire,
            "legacy agent must not backfill",
            "agent-attachment",
        )
    connection
        .prepareStatement(
            """
            INSERT INTO audit_log (
                created_at, club_id, night_id, actor_user_id, actor_role, subject_user_id,
                entity_type, entity_id, action, fingerprint, metadata_json
            ) VALUES (?, ?, NULL, ?, 'MANAGER', NULL, 'SUPPORT_TICKET', ?, 'SUPPORT_REPLY', ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, MIGRATION_TIME.plusMinutes(2))
            statement.setLong(2, clubId)
            statement.setLong(3, actorUserId)
            statement.setLong(4, ticketId)
            statement.setString(5, "v060-preserved-audit")
            statement.setString(6, "{\"message_id\":$legacyAgentMessageId}")
            assertEquals(1, statement.executeUpdate())
        }
    return MigrationLegacyFixture(clubId, ownerUserId, actorUserId, ticketId)
}

private fun insertMigrationUser(
    connection: Connection,
    telegramUserId: Long,
    username: String,
): Long =
    returningId(
        connection,
        """
        INSERT INTO users (telegram_user_id, username, display_name, phone_e164)
        VALUES (?, ?, ?, NULL)
        RETURNING id
        """.trimIndent(),
    ) { statement ->
        statement.setLong(1, telegramUserId)
        statement.setString(2, username)
        statement.setString(3, username)
    }

private fun insertMigrationMessage(
    connection: Connection,
    ticketId: Long,
    senderType: String,
    text: String,
    attachments: String?,
): Long =
    returningId(
        connection,
        """
        INSERT INTO ticket_messages (ticket_id, sender_type, text, attachments, created_at)
        VALUES (?, ?, ?, ?, ?)
        RETURNING id
        """.trimIndent(),
    ) { statement ->
        statement.setLong(1, ticketId)
        statement.setString(2, senderType)
        statement.setString(3, text)
        if (attachments == null) {
            statement.setNull(4, Types.VARCHAR)
        } else {
            statement.setString(4, attachments)
        }
        statement.setObject(5, MIGRATION_TIME.plusMinutes(3))
    }

private fun returningId(
    connection: Connection,
    sql: String,
    bind: (PreparedStatement) -> Unit = {},
): Long =
    connection.prepareStatement(sql).use { statement ->
        bind(statement)
        statement.executeQuery().use { result ->
            assertTrue(result.next())
            result.getLong(1)
        }
    }

private fun readPreservedRows(
    connection: Connection,
    ticketId: Long,
): MigrationPreservedRows =
    MigrationPreservedRows(
        tickets =
            readRows(
                connection,
                """
                SELECT id, club_id, user_id, topic, status, created_at, updated_at, last_agent_id
                FROM tickets WHERE id = $ticketId
                """.trimIndent(),
            ),
        messages =
            readRows(
                connection,
                """
                SELECT id, ticket_id, sender_type, text, attachments, created_at
                FROM ticket_messages WHERE ticket_id = $ticketId ORDER BY id
                """.trimIndent(),
            ),
        audits =
            readRows(
                connection,
                """
                SELECT club_id, actor_user_id, actor_role, entity_type, entity_id,
                       action, fingerprint, metadata_json
                FROM audit_log WHERE entity_id = $ticketId ORDER BY id
                """.trimIndent(),
            ),
    )

private fun readRows(
    connection: Connection,
    sql: String,
): List<List<String?>> =
    connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { result ->
            buildList {
                val columnCount = result.metaData.columnCount
                while (result.next()) {
                    add((1..columnCount).map { index -> result.getObject(index)?.toString() })
                }
            }
        }
    }

private fun deliveryRowCount(connection: Connection): Long =
    connection.createStatement().use { statement ->
        statement.executeQuery("SELECT COUNT(*) FROM support_reply_deliveries").use { result ->
            assertTrue(result.next())
            result.getLong(1)
        }
    }

private fun assertDeliveryColumns(connection: Connection) {
    val columns =
        connection
            .prepareStatement(
                """
                SELECT column_name, data_type, is_nullable
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'support_reply_deliveries'
                ORDER BY ordinal_position
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(Triple(result.getString(1), result.getString(2), result.getString(3)))
                        }
                    }
                }
            }
    assertEquals(
        listOf(
            Triple("id", "bigint", "NO"),
            Triple("reply_message_id", "bigint", "NO"),
            Triple("ticket_id", "bigint", "NO"),
            Triple("recipient_user_id", "bigint", "NO"),
            Triple("acting_staff_user_id", "bigint", "NO"),
            Triple("acting_role", "text", "NO"),
            Triple("status", "text", "NO"),
            Triple("failure_code", "text", "YES"),
            Triple("created_at", "timestamp with time zone", "NO"),
            Triple("updated_at", "timestamp with time zone", "NO"),
            Triple("completed_at", "timestamp with time zone", "YES"),
        ),
        columns,
    )
    val names = columns.mapTo(linkedSetOf()) { it.first }
    assertFalse(names.any { it.contains("text") || it.contains("attachment") || it.contains("telegram") })
}

private fun assertDeliveryConstraints(connection: Connection) {
    val constraints =
        connection
            .prepareStatement(
                """
                SELECT constraint_name, constraint_type
                FROM information_schema.table_constraints
                WHERE table_schema = 'public' AND table_name = 'support_reply_deliveries'
                  AND constraint_name !~ '_not_null$'
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { result ->
                    buildMap {
                        while (result.next()) {
                            put(result.getString(1), result.getString(2))
                        }
                    }
                }
            }
    assertEquals(
        mapOf(
            "support_reply_deliveries_pkey" to "PRIMARY KEY",
            "uq_support_reply_deliveries_reply_message" to "UNIQUE",
            "support_reply_deliveries_reply_message_id_fkey" to "FOREIGN KEY",
            "support_reply_deliveries_ticket_id_fkey" to "FOREIGN KEY",
            "support_reply_deliveries_recipient_user_id_fkey" to "FOREIGN KEY",
            "support_reply_deliveries_acting_staff_user_id_fkey" to "FOREIGN KEY",
            "support_reply_deliveries_acting_role_check" to "CHECK",
            "support_reply_deliveries_status_check" to "CHECK",
            "support_reply_deliveries_result_check" to "CHECK",
            "support_reply_deliveries_completion_check" to "CHECK",
        ),
        constraints,
    )
}

private fun assertDeliveryForeignKeys(connection: Connection) {
    val foreignKeys =
        connection
            .prepareStatement(
                """
                SELECT kcu.column_name, ccu.table_name, ccu.column_name, rc.delete_rule
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                  ON tc.constraint_schema = kcu.constraint_schema
                 AND tc.constraint_name = kcu.constraint_name
                JOIN information_schema.constraint_column_usage ccu
                  ON tc.constraint_schema = ccu.constraint_schema
                 AND tc.constraint_name = ccu.constraint_name
                JOIN information_schema.referential_constraints rc
                  ON tc.constraint_schema = rc.constraint_schema
                 AND tc.constraint_name = rc.constraint_name
                WHERE tc.table_schema = 'public'
                  AND tc.table_name = 'support_reply_deliveries'
                  AND tc.constraint_type = 'FOREIGN KEY'
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { result ->
                    buildSet {
                        while (result.next()) {
                            add(
                                DeliveryForeignKey(
                                    column = result.getString(1),
                                    referencedTable = result.getString(2),
                                    referencedColumn = result.getString(3),
                                    deleteRule = result.getString(4),
                                ),
                            )
                        }
                    }
                }
            }
    assertEquals(
        setOf(
            DeliveryForeignKey("reply_message_id", "ticket_messages", "id", "CASCADE"),
            DeliveryForeignKey("ticket_id", "tickets", "id", "CASCADE"),
            DeliveryForeignKey("recipient_user_id", "users", "id", "NO ACTION"),
            DeliveryForeignKey("acting_staff_user_id", "users", "id", "NO ACTION"),
        ),
        foreignKeys,
    )
}

private fun assertDeliveryIndexes(connection: Connection) {
    val indexes =
        connection
            .prepareStatement(
                """
                SELECT indexname, indexdef
                FROM pg_indexes
                WHERE schemaname = 'public' AND tablename = 'support_reply_deliveries'
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { result ->
                    buildMap {
                        while (result.next()) {
                            put(result.getString(1), result.getString(2))
                        }
                    }
                }
            }
    assertEquals(
        setOf(
            "support_reply_deliveries_pkey",
            "uq_support_reply_deliveries_reply_message",
            "idx_support_reply_deliveries_ticket_id",
        ),
        indexes.keys,
    )
    assertTrue(indexes.getValue("uq_support_reply_deliveries_reply_message").contains("(reply_message_id)"))
    assertTrue(indexes.getValue("idx_support_reply_deliveries_ticket_id").contains("(ticket_id)"))
}

private fun insertRawDelivery(
    connection: Connection,
    legacy: MigrationLegacyFixture,
    replyMessageId: Long,
    case: RawDeliveryCase,
) {
    connection
        .prepareStatement(
            """
            INSERT INTO support_reply_deliveries (
                reply_message_id, ticket_id, recipient_user_id, acting_staff_user_id,
                acting_role, status, failure_code, created_at, updated_at, completed_at
            ) VALUES (?, ?, ?, ?, 'MANAGER', ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setLong(1, replyMessageId)
            statement.setLong(2, legacy.ticketId)
            statement.setLong(3, legacy.ownerUserId)
            statement.setLong(4, legacy.actorUserId)
            statement.setString(5, case.status)
            if (case.failureCode == null) {
                statement.setNull(6, Types.VARCHAR)
            } else {
                statement.setString(6, case.failureCode)
            }
            statement.setObject(7, MIGRATION_TIME.plusMinutes(4))
            statement.setObject(8, MIGRATION_TIME.plusMinutes(4))
            if (case.completed) {
                statement.setObject(9, MIGRATION_TIME.plusMinutes(5))
            } else {
                statement.setNull(9, Types.TIMESTAMP_WITH_TIMEZONE)
            }
            assertEquals(1, statement.executeUpdate())
        }
}

private fun assertRejectedRawDelivery(
    connection: Connection,
    legacy: MigrationLegacyFixture,
    case: RawDeliveryCase,
) {
    val messageId =
        insertMigrationMessage(
            connection,
            legacy.ticketId,
            TicketSenderType.AGENT.wire,
            "rejected-${case.status}-${UUID.randomUUID()}",
            null,
        )
    assertThrows(SQLException::class.java) {
        insertRawDelivery(connection, legacy, messageId, case)
    }
}

private val MIGRATION_TIME: OffsetDateTime =
    Instant.parse("2026-08-23T12:00:00Z").atOffset(ZoneOffset.UTC)
