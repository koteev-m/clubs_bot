package com.example.bot.data.support

import com.example.bot.audit.AuditLogRecord
import com.example.bot.audit.StandardAuditAction
import com.example.bot.audit.StandardAuditEntityType
import com.example.bot.data.audit.AuditLogRepositoryImpl
import com.example.bot.data.security.PermissionCode
import com.example.bot.data.security.PermissionCodes
import com.example.bot.data.security.Role
import com.example.bot.support.SupportServiceError
import com.example.bot.support.SupportServiceResult
import com.example.bot.support.Ticket
import com.example.bot.support.TicketSenderType
import com.example.bot.support.TicketStatus
import com.example.bot.support.TicketTopic
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import testing.RequiresDocker
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors

@RequiresDocker
@Tag("it")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SupportMutationIT {
    private lateinit var dataSource: HikariDataSource
    private lateinit var database: Database
    private lateinit var concurrencyDispatcher: ExecutorCoroutineDispatcher

    private val fixedInstant = Instant.parse("2026-08-21T10:00:00Z")
    private val fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC)

    @BeforeAll
    fun startContainer() {
        postgres.start()
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations(
                "classpath:db/migration/common",
                "classpath:db/migration/postgresql",
            ).cleanDisabled(false)
            .load()
            .also { flyway ->
                flyway.clean()
                flyway.migrate()
            }

        dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = postgres.jdbcUrl
                    driverClassName = postgres.driverClassName
                    username = postgres.username
                    password = postgres.password
                    maximumPoolSize = 8
                    isAutoCommit = false
                    transactionIsolation = "TRANSACTION_REPEATABLE_READ"
                },
            )
        database = Database.connect(dataSource)
        concurrencyDispatcher = Executors.newFixedThreadPool(2).asCoroutineDispatcher()
    }

    @BeforeEach
    fun cleanDatabase() {
        dropAuditFailureConstraint()
        transaction(database) {
            exec(
                """
                TRUNCATE TABLE
                    audit_log,
                    ticket_messages,
                    tickets,
                    user_role_permissions,
                    user_roles,
                    users,
                    clubs
                RESTART IDENTITY CASCADE
                """.trimIndent(),
            )
        }
    }

    @AfterEach
    fun removeAuditFailureConstraint() {
        dropAuditFailureConstraint()
    }

    @AfterAll
    fun stopContainer() {
        if (::concurrencyDispatcher.isInitialized) concurrencyDispatcher.close()
        if (::dataSource.isInitialized) dataSource.close()
        postgres.stop()
    }

    @Test
    fun `take and replies persist exact state messages audits and fresh repository readback`() =
        runBlocking {
            val ownerUserId = insertUser(8_800_820_001L)
            val actorUserId = insertUser(8_800_820_002L)
            val clubId = insertClub("Support mutation lifecycle")
            val assignmentId = insertAssignment(actorUserId, Role.CLUB_ADMIN, "CLUB", clubId)
            grantPermission(assignmentId, PermissionCodes.SUPPORT_STATUS_MANAGE)
            grantPermission(assignmentId, PermissionCodes.SUPPORT_REPLY)

            val repository = SupportRepository(database, fixedClock)
            val service = SupportServiceImpl(repository)
            val takeTicket = createTicket(repository, clubId, ownerUserId, "take initial", null)
            val replyTicket =
                createTicket(
                    repository,
                    clubId,
                    ownerUserId,
                    FIRST_REPLY_BODY,
                    FIRST_REPLY_ATTACHMENT,
                )

            val taken = success(service.assign(takeTicket.id, actorUserId))
            assertEquals(TicketStatus.IN_PROGRESS, taken.status)
            assertEquals(actorUserId, taken.lastAgentId)
            assertTrue(taken.updatedAt.isAfter(takeTicket.updatedAt))

            val firstReply =
                success(
                    service.reply(
                        ticketId = replyTicket.id,
                        agentUserId = actorUserId,
                        text = FIRST_REPLY_BODY,
                        attachments = FIRST_REPLY_ATTACHMENT,
                    ),
                )
            assertEquals(TicketStatus.IN_PROGRESS, firstReply.ticket.status)
            assertEquals(TicketSenderType.AGENT, firstReply.replyMessage.senderType)
            assertEquals(FIRST_REPLY_BODY, firstReply.replyMessage.text)
            assertEquals(FIRST_REPLY_ATTACHMENT, firstReply.replyMessage.attachments)

            val secondReply =
                success(
                    service.reply(
                        ticketId = replyTicket.id,
                        agentUserId = actorUserId,
                        text = SECOND_REPLY_BODY,
                        attachments = SECOND_REPLY_ATTACHMENT,
                    ),
                )
            assertEquals(TicketStatus.IN_PROGRESS, secondReply.ticket.status)
            assertEquals(actorUserId, secondReply.ticket.lastAgentId)
            assertTrue(secondReply.ticket.updatedAt.isAfter(firstReply.ticket.updatedAt))

            val auditRepository = AuditLogRepositoryImpl(database, fixedClock)
            val audits = auditRepository.listForClub(clubId, limit = 100, offset = 0)
            val takeAudits = audits.forTicket(takeTicket.id)
            val replyAudits = audits.forTicket(replyTicket.id)

            assertEquals(1, takeAudits.size)
            assertStatusAudit(takeAudits.single(), TicketStatus.NEW, TicketStatus.IN_PROGRESS)
            assertAuditEnvelope(takeAudits.single(), clubId, actorUserId, Role.CLUB_ADMIN, takeTicket.id)

            val replyActionAudits = replyAudits.withAction(StandardAuditAction.SUPPORT_REPLY)
            val replyStatusAudits = replyAudits.withAction(StandardAuditAction.SUPPORT_STATUS_CHANGE)
            assertEquals(2, replyActionAudits.size)
            assertEquals(1, replyStatusAudits.size)
            assertStatusAudit(replyStatusAudits.single(), TicketStatus.NEW, TicketStatus.IN_PROGRESS)
            assertEquals(
                setOf(firstReply.replyMessage.id, secondReply.replyMessage.id),
                replyActionAudits.mapTo(linkedSetOf()) { audit ->
                    assertEquals(setOf("message_id"), audit.metadata.jsonObject.keys)
                    audit.metadata.jsonObject
                        .getValue("message_id")
                        .jsonPrimitive
                        .long
                },
            )
            replyAudits.forEach { audit ->
                assertAuditEnvelope(audit, clubId, actorUserId, Role.CLUB_ADMIN, replyTicket.id)
            }
            val serializedAuditMetadata = replyAudits.joinToString(separator = "|") { it.metadata.toString() }
            listOf(
                FIRST_REPLY_BODY,
                FIRST_REPLY_ATTACHMENT,
                SECOND_REPLY_BODY,
                SECOND_REPLY_ATTACHMENT,
            ).forEach { privateValue ->
                assertFalse(serializedAuditMetadata.contains(privateValue), serializedAuditMetadata)
            }

            val freshRepository = SupportRepository(database, Clock.systemUTC())
            assertEquals(TicketStatus.IN_PROGRESS, freshRepository.findTicket(takeTicket.id)?.status)
            assertEquals(TicketStatus.IN_PROGRESS, freshRepository.findTicket(replyTicket.id)?.status)
            val freshThread = freshRepository.findStaffTicketThread(replyTicket.id, setOf(clubId))
            assertNotNull(freshThread)
            assertEquals(3, freshThread?.messages?.size)
            assertEquals(
                listOf(FIRST_REPLY_BODY, FIRST_REPLY_BODY, SECOND_REPLY_BODY),
                freshThread?.messages?.map { it.text },
            )
            assertEquals(
                listOf(FIRST_REPLY_ATTACHMENT, FIRST_REPLY_ATTACHMENT, SECOND_REPLY_ATTACHMENT),
                freshThread?.messages?.map { it.attachments },
            )
            val freshAudits = AuditLogRepositoryImpl(database).listForClub(clubId, limit = 100, offset = 0)
            assertEquals(4, freshAudits.size)
        }

    @Test
    fun `audit insertion failure rolls back take reply message status and partial audit`() =
        runBlocking {
            val ownerUserId = insertUser(8_800_820_011L)
            val actorUserId = insertUser(8_800_820_012L)
            val clubId = insertClub("Support mutation rollback")
            val assignmentId = insertAssignment(actorUserId, Role.MANAGER, "CLUB", clubId)
            grantPermission(assignmentId, PermissionCodes.SUPPORT_STATUS_MANAGE)
            grantPermission(assignmentId, PermissionCodes.SUPPORT_REPLY)

            val repository = SupportRepository(database, fixedClock)
            val service = SupportServiceImpl(repository)
            val takeTicket = createTicket(repository, clubId, ownerUserId, "rollback take", null)
            installStatusAuditFailureConstraint()

            assertFailure(
                service.assign(takeTicket.id, actorUserId),
                SupportServiceError.PersistenceFailure,
            )
            assertTicketUnchanged(repository, takeTicket)
            assertEquals(1L, messageCount(takeTicket.id))
            assertEquals(0L, auditCount(takeTicket.id))

            dropAuditFailureConstraint()
            val replyTicket = createTicket(repository, clubId, ownerUserId, "rollback reply", null)
            installStatusAuditFailureConstraint()

            assertFailure(
                service.reply(
                    ticketId = replyTicket.id,
                    agentUserId = actorUserId,
                    text = ROLLBACK_REPLY_BODY,
                    attachments = ROLLBACK_REPLY_ATTACHMENT,
                ),
                SupportServiceError.PersistenceFailure,
            )
            assertTicketUnchanged(repository, replyTicket)
            assertEquals(1L, messageCount(replyTicket.id))
            assertEquals(0L, auditCount(replyTicket.id))
        }

    @Test
    fun `two concurrent first replies persist both messages and one transition audit`() =
        runBlocking {
            val ownerUserId = insertUser(8_800_820_021L)
            val firstActorUserId = insertUser(8_800_820_022L)
            val secondActorUserId = insertUser(8_800_820_023L)
            val clubId = insertClub("Support concurrent replies")
            val firstAssignmentId = insertAssignment(firstActorUserId, Role.MANAGER, "CLUB", clubId)
            val secondAssignmentId = insertAssignment(secondActorUserId, Role.CLUB_ADMIN, "CLUB", clubId)
            grantPermission(firstAssignmentId, PermissionCodes.SUPPORT_REPLY)
            grantPermission(secondAssignmentId, PermissionCodes.SUPPORT_REPLY)

            val repository = SupportRepository(database, fixedClock)
            val ticket = createTicket(repository, clubId, ownerUserId, "concurrent initial", null)
            val firstService = SupportServiceImpl(SupportRepository(database, fixedClock))
            val secondService = SupportServiceImpl(SupportRepository(database, fixedClock))
            val barrier = CyclicBarrier(2)

            val results =
                coroutineScope {
                    listOf(
                        async(concurrencyDispatcher) {
                            barrier.await()
                            firstService.reply(ticket.id, firstActorUserId, CONCURRENT_REPLY_ONE, null)
                        },
                        async(concurrencyDispatcher) {
                            barrier.await()
                            secondService.reply(ticket.id, secondActorUserId, CONCURRENT_REPLY_TWO, null)
                        },
                    ).awaitAll()
                }

            results.forEach { result -> success(result) }
            assertEquals(TicketStatus.IN_PROGRESS, repository.findTicket(ticket.id)?.status)
            assertEquals(3L, messageCount(ticket.id))
            assertEquals(
                setOf(CONCURRENT_REPLY_ONE, CONCURRENT_REPLY_TWO),
                agentMessageBodies(ticket.id),
            )
            val audits = AuditLogRepositoryImpl(database).listForClub(clubId, 100, 0).forTicket(ticket.id)
            val replyAudits = audits.withAction(StandardAuditAction.SUPPORT_REPLY)
            assertEquals(2, replyAudits.size)
            assertEquals(
                setOf(firstActorUserId, secondActorUserId),
                replyAudits.mapTo(linkedSetOf()) { it.actorUserId },
            )
            assertEquals(
                setOf(Role.MANAGER.name, Role.CLUB_ADMIN.name),
                replyAudits.mapTo(linkedSetOf()) { it.actorRole },
            )
            assertEquals(1, audits.withAction(StandardAuditAction.SUPPORT_STATUS_CHANGE).size)
        }

    @Test
    fun `concurrent take and first reply produce one valid transition audit`() =
        runBlocking {
            val ownerUserId = insertUser(8_800_820_031L)
            val takeActorUserId = insertUser(8_800_820_032L)
            val replyActorUserId = insertUser(8_800_820_033L)
            val clubId = insertClub("Support concurrent take reply")
            val takeAssignmentId = insertAssignment(takeActorUserId, Role.MANAGER, "CLUB", clubId)
            val replyAssignmentId = insertAssignment(replyActorUserId, Role.CLUB_ADMIN, "CLUB", clubId)
            grantPermission(takeAssignmentId, PermissionCodes.SUPPORT_STATUS_MANAGE)
            grantPermission(replyAssignmentId, PermissionCodes.SUPPORT_REPLY)

            val repository = SupportRepository(database, fixedClock)
            val ticket = createTicket(repository, clubId, ownerUserId, "take reply initial", null)
            val takeService = SupportServiceImpl(SupportRepository(database, fixedClock))
            val replyService = SupportServiceImpl(SupportRepository(database, fixedClock))
            val barrier = CyclicBarrier(2)

            val (takeResult, replyResult) =
                coroutineScope {
                    val take =
                        async(concurrencyDispatcher) {
                            barrier.await()
                            takeService.assign(ticket.id, takeActorUserId)
                        }
                    val reply =
                        async(concurrencyDispatcher) {
                            barrier.await()
                            replyService.reply(ticket.id, replyActorUserId, TAKE_REPLY_BODY, null)
                        }
                    take.await() to reply.await()
                }

            success(replyResult)
            when (takeResult) {
                is SupportServiceResult.Success -> assertEquals(TicketStatus.IN_PROGRESS, takeResult.value.status)
                is SupportServiceResult.Failure -> assertEquals(SupportServiceError.InvalidState, takeResult.error)
            }
            assertEquals(TicketStatus.IN_PROGRESS, repository.findTicket(ticket.id)?.status)
            assertEquals(2L, messageCount(ticket.id))
            val audits = AuditLogRepositoryImpl(database).listForClub(clubId, 100, 0).forTicket(ticket.id)
            val replyAudit = audits.withAction(StandardAuditAction.SUPPORT_REPLY).single()
            assertEquals(replyActorUserId, replyAudit.actorUserId)
            assertEquals(Role.CLUB_ADMIN.name, replyAudit.actorRole)
            val statusAudit = audits.withAction(StandardAuditAction.SUPPORT_STATUS_CHANGE).single()
            when (takeResult) {
                is SupportServiceResult.Success -> {
                    assertEquals(takeActorUserId, statusAudit.actorUserId)
                    assertEquals(Role.MANAGER.name, statusAudit.actorRole)
                }

                is SupportServiceResult.Failure -> {
                    assertEquals(replyActorUserId, statusAudit.actorUserId)
                    assertEquals(Role.CLUB_ADMIN.name, statusAudit.actorRole)
                }
            }
        }

    @Test
    fun `permission is attached to one exact assignment and revocation prevents mutation`() =
        runBlocking {
            val ownerUserId = insertUser(8_800_820_041L)
            val actorUserId = insertUser(8_800_820_042L)
            val targetClubId = insertClub("Support exact assignment target")
            val foreignClubId = insertClub("Support exact assignment foreign")
            val targetManager = insertAssignment(actorUserId, Role.MANAGER, "CLUB", targetClubId)
            val targetEntryManager = insertAssignment(actorUserId, Role.ENTRY_MANAGER, "CLUB", targetClubId)
            val foreignManager = insertAssignment(actorUserId, Role.MANAGER, "CLUB", foreignClubId)
            grantPermission(targetEntryManager, PermissionCodes.SUPPORT_REPLY)
            grantPermission(foreignManager, PermissionCodes.SUPPORT_REPLY)

            val repository = SupportRepository(database, fixedClock)
            val service = SupportServiceImpl(repository)
            val targetTicket = createTicket(repository, targetClubId, ownerUserId, "exact assignment", null)

            assertFailure(
                service.reply(targetTicket.id, actorUserId, "must not combine", null),
                SupportServiceError.TicketNotFound,
            )
            assertTicketUnchanged(repository, targetTicket)
            assertEquals(1L, messageCount(targetTicket.id))
            assertEquals(0L, auditCount(targetTicket.id))

            grantPermission(targetManager, PermissionCodes.SUPPORT_REPLY)
            success(service.reply(targetTicket.id, actorUserId, "exact manager reply", null))
            val targetAudit =
                AuditLogRepositoryImpl(database)
                    .listForClub(targetClubId, 100, 0)
                    .forTicket(targetTicket.id)
                    .withAction(StandardAuditAction.SUPPORT_REPLY)
                    .single()
            assertEquals(Role.MANAGER.name, targetAudit.actorRole)

            val revokedTicket = createTicket(repository, targetClubId, ownerUserId, "revoked permission", null)
            revokePermission(targetManager, PermissionCodes.SUPPORT_REPLY)
            assertFailure(
                service.reply(revokedTicket.id, actorUserId, "must remain absent", null),
                SupportServiceError.TicketNotFound,
            )
            assertTicketUnchanged(repository, revokedTicket)
            assertEquals(1L, messageCount(revokedTicket.id))
            assertEquals(0L, auditCount(revokedTicket.id))
        }

    private suspend fun createTicket(
        repository: SupportRepository,
        clubId: Long,
        ownerUserId: Long,
        text: String,
        attachments: String?,
    ): Ticket =
        repository
            .createTicket(
                clubId = clubId,
                userId = ownerUserId,
                bookingId = null,
                listEntryId = null,
                topic = TicketTopic.OTHER,
                text = text,
                attachments = attachments,
            ).ticket

    private fun insertUser(telegramUserId: Long): Long =
        transaction(database) {
            SupportMutationUsersTable.insert {
                it[SupportMutationUsersTable.telegramUserId] = telegramUserId
                it[SupportMutationUsersTable.username] = "support_mutation_$telegramUserId"
                it[SupportMutationUsersTable.displayName] = "Support mutation user"
                it[SupportMutationUsersTable.phoneE164] = null
            }[SupportMutationUsersTable.id]
        }

    private fun insertClub(name: String): Long =
        transaction(database) {
            SupportMutationClubsTable.insert {
                it[SupportMutationClubsTable.name] = name
                it[SupportMutationClubsTable.timezone] = "Europe/Moscow"
            }[SupportMutationClubsTable.id]
        }

    private fun insertAssignment(
        userId: Long,
        role: Role,
        scopeType: String,
        clubId: Long?,
    ): Long =
        transaction(database) {
            SupportMutationUserRolesTable.insert {
                it[SupportMutationUserRolesTable.userId] = userId
                it[SupportMutationUserRolesTable.roleCode] = role.name
                it[SupportMutationUserRolesTable.scopeType] = scopeType
                it[SupportMutationUserRolesTable.scopeClubId] = clubId
            }[SupportMutationUserRolesTable.id]
        }

    private fun grantPermission(
        assignmentId: Long,
        permission: PermissionCode,
    ) {
        transaction(database) {
            SupportMutationUserRolePermissionsTable.insert {
                it[userRoleId] = assignmentId
                it[permissionCode] = permission.value
            }
        }
    }

    private fun revokePermission(
        assignmentId: Long,
        permission: PermissionCode,
    ) {
        transaction(database) {
            assertEquals(
                1,
                SupportMutationUserRolePermissionsTable.deleteWhere {
                    (userRoleId eq assignmentId) and (permissionCode eq permission.value)
                },
            )
        }
    }

    private fun installStatusAuditFailureConstraint() {
        transaction(database) {
            exec(
                "ALTER TABLE audit_log ADD CONSTRAINT $AUDIT_FAILURE_CONSTRAINT " +
                    "CHECK (action <> '${StandardAuditAction.SUPPORT_STATUS_CHANGE.value}')",
            )
        }
    }

    private fun dropAuditFailureConstraint() {
        if (!::database.isInitialized) return
        transaction(database) {
            exec("ALTER TABLE audit_log DROP CONSTRAINT IF EXISTS $AUDIT_FAILURE_CONSTRAINT")
        }
    }

    private suspend fun assertTicketUnchanged(
        repository: SupportRepository,
        expected: Ticket,
    ) {
        val actual = repository.findTicket(expected.id)
        assertNotNull(actual)
        assertEquals(TicketStatus.NEW, actual?.status)
        assertEquals(expected.updatedAt, actual?.updatedAt)
        assertNull(actual?.lastAgentId)
    }

    private fun messageCount(ticketId: Long): Long =
        transaction(database) {
            TicketMessagesTable
                .selectAll()
                .where { TicketMessagesTable.ticketId eq ticketId }
                .count()
        }

    private fun agentMessageBodies(ticketId: Long): Set<String> =
        transaction(database) {
            TicketMessagesTable
                .selectAll()
                .where {
                    (TicketMessagesTable.ticketId eq ticketId) and
                        (TicketMessagesTable.senderType eq TicketSenderType.AGENT.wire)
                }.mapTo(linkedSetOf()) { it[TicketMessagesTable.text] }
        }

    private fun auditCount(ticketId: Long): Long =
        transaction(database) {
            com.example.bot.data.audit.AuditLogTable
                .selectAll()
                .where { com.example.bot.data.audit.AuditLogTable.entityId eq ticketId }
                .count()
        }

    private fun assertAuditEnvelope(
        audit: AuditLogRecord,
        clubId: Long,
        actorUserId: Long,
        role: Role,
        ticketId: Long,
    ) {
        assertEquals(clubId, audit.clubId)
        assertNull(audit.nightId)
        assertEquals(actorUserId, audit.actorUserId)
        assertEquals(role.name, audit.actorRole)
        assertNull(audit.subjectUserId)
        assertEquals(StandardAuditEntityType.SUPPORT_TICKET, audit.entityType)
        assertEquals(ticketId, audit.entityId)
        assertTrue(audit.fingerprint.isNotBlank())
    }

    private fun assertStatusAudit(
        audit: AuditLogRecord,
        oldStatus: TicketStatus,
        newStatus: TicketStatus,
    ) {
        assertEquals(StandardAuditAction.SUPPORT_STATUS_CHANGE, audit.action)
        assertEquals(setOf("old_status", "new_status"), audit.metadata.jsonObject.keys)
        assertEquals(
            oldStatus.wire,
            audit.metadata.jsonObject
                .getValue("old_status")
                .jsonPrimitive
                .content,
        )
        assertEquals(
            newStatus.wire,
            audit.metadata.jsonObject
                .getValue("new_status")
                .jsonPrimitive
                .content,
        )
    }

    private fun List<AuditLogRecord>.forTicket(ticketId: Long): List<AuditLogRecord> =
        filter { audit ->
            audit.entityType == StandardAuditEntityType.SUPPORT_TICKET && audit.entityId == ticketId
        }

    private fun List<AuditLogRecord>.withAction(action: StandardAuditAction): List<AuditLogRecord> =
        filter { it.action == action }

    private fun <T> success(result: SupportServiceResult<T>): T {
        assertTrue(result is SupportServiceResult.Success, result.toString())
        return (result as SupportServiceResult.Success).value
    }

    private fun assertFailure(
        result: SupportServiceResult<*>,
        expected: SupportServiceError,
    ) {
        assertTrue(result is SupportServiceResult.Failure, result.toString())
        assertEquals(expected, (result as SupportServiceResult.Failure).error)
    }

    companion object {
        private const val AUDIT_FAILURE_CONSTRAINT = "support_mutation_it_reject_status_audit"
        private const val FIRST_REPLY_BODY = "private reply body one"
        private const val FIRST_REPLY_ATTACHMENT = "private-attachment-one"
        private const val SECOND_REPLY_BODY = "private reply body two"
        private const val SECOND_REPLY_ATTACHMENT = "private-attachment-two"
        private const val ROLLBACK_REPLY_BODY = "rollback private reply"
        private const val ROLLBACK_REPLY_ATTACHMENT = "rollback-private-attachment"
        private const val CONCURRENT_REPLY_ONE = "concurrent reply one"
        private const val CONCURRENT_REPLY_TWO = "concurrent reply two"
        private const val TAKE_REPLY_BODY = "concurrent take reply body"

        @JvmStatic
        @BeforeAll
        fun assumeDocker() {
            val dockerAvailable =
                try {
                    DockerClientFactory.instance().client()
                    true
                } catch (_: Throwable) {
                    false
                }
            assumeTrue(dockerAvailable, "Docker is not available on this host; skipping IT.")
        }

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }
}

private object SupportMutationClubsTable : Table("clubs") {
    val id = long("id").autoIncrement()
    val name = text("name")
    val timezone = text("timezone")
    override val primaryKey = PrimaryKey(id)
}

private object SupportMutationUsersTable : Table("users") {
    val id = long("id").autoIncrement()
    val telegramUserId = long("telegram_user_id").nullable()
    val username = text("username").nullable()
    val displayName = text("display_name").nullable()
    val phoneE164 = text("phone_e164").nullable()
    override val primaryKey = PrimaryKey(id)
}

private object SupportMutationUserRolesTable : Table("user_roles") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val roleCode = text("role_code")
    val scopeType = text("scope_type")
    val scopeClubId = long("scope_club_id").nullable()
    override val primaryKey = PrimaryKey(id)
}

private object SupportMutationUserRolePermissionsTable : Table("user_role_permissions") {
    val userRoleId = long("user_role_id")
    val permissionCode = text("permission_code")
    override val primaryKey = PrimaryKey(userRoleId, permissionCode)
}
