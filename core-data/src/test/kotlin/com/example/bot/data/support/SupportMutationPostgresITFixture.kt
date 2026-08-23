package com.example.bot.data.support

import com.example.bot.audit.AuditLogRecord
import com.example.bot.audit.StandardAuditAction
import com.example.bot.audit.StandardAuditEntityType
import com.example.bot.data.security.PermissionCode
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
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
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors

open class SupportMutationPostgresITFixture {
    protected lateinit var database: Database
    protected lateinit var concurrencyDispatcher: ExecutorCoroutineDispatcher

    private lateinit var dataSource: HikariDataSource

    protected val fixedInstant: Instant = Instant.parse("2026-08-21T10:00:00Z")
    protected val fixedClock: Clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)

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

    protected suspend fun createTicket(
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

    protected fun insertUser(telegramUserId: Long): Long =
        transaction(database) {
            SupportMutationUsersTable.insert {
                it[SupportMutationUsersTable.telegramUserId] = telegramUserId
                it[SupportMutationUsersTable.username] = "support_mutation_$telegramUserId"
                it[SupportMutationUsersTable.displayName] = "Support mutation user"
                it[SupportMutationUsersTable.phoneE164] = null
            }[SupportMutationUsersTable.id]
        }

    protected fun insertClub(name: String): Long =
        transaction(database) {
            SupportMutationClubsTable.insert {
                it[SupportMutationClubsTable.name] = name
                it[SupportMutationClubsTable.timezone] = "Europe/Moscow"
            }[SupportMutationClubsTable.id]
        }

    protected fun insertAssignment(
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

    protected fun grantPermission(
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

    protected fun revokePermission(
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

    protected fun seedStatus(
        ticketId: Long,
        status: TicketStatus,
    ) {
        transaction(database) {
            assertEquals(
                1,
                TicketsTable.update({ TicketsTable.id eq ticketId }) {
                    it[TicketsTable.status] = status.wire
                },
            )
        }
    }

    protected fun installStatusAuditFailureConstraint() {
        transaction(database) {
            exec(
                "ALTER TABLE audit_log ADD CONSTRAINT $AUDIT_FAILURE_CONSTRAINT " +
                    "CHECK (action <> '${StandardAuditAction.SUPPORT_STATUS_CHANGE.value}')",
            )
        }
    }

    protected fun dropAuditFailureConstraint() {
        if (!::database.isInitialized) return
        transaction(database) {
            exec("ALTER TABLE audit_log DROP CONSTRAINT IF EXISTS $AUDIT_FAILURE_CONSTRAINT")
        }
    }

    protected suspend fun assertTicketUnchanged(
        repository: SupportRepository,
        expected: Ticket,
    ) {
        val actual = repository.findTicket(expected.id)
        assertNotNull(actual)
        assertEquals(TicketStatus.NEW, actual?.status)
        assertEquals(expected.updatedAt, actual?.updatedAt)
        assertNull(actual?.lastAgentId)
    }

    protected fun messageCount(ticketId: Long): Long =
        transaction(database) {
            TicketMessagesTable
                .selectAll()
                .where { TicketMessagesTable.ticketId eq ticketId }
                .count()
        }

    protected fun agentMessageBodies(ticketId: Long): Set<String> =
        transaction(database) {
            TicketMessagesTable
                .selectAll()
                .where {
                    (TicketMessagesTable.ticketId eq ticketId) and
                        (TicketMessagesTable.senderType eq TicketSenderType.AGENT.wire)
                }.mapTo(linkedSetOf()) { it[TicketMessagesTable.text] }
        }

    protected fun auditCount(ticketId: Long): Long =
        transaction(database) {
            com.example.bot.data.audit.AuditLogTable
                .selectAll()
                .where { com.example.bot.data.audit.AuditLogTable.entityId eq ticketId }
                .count()
        }

    protected fun assertAuditEnvelope(
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

    protected fun assertStatusAudit(
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

    protected fun assertCloseAudit(audit: AuditLogRecord) {
        assertEquals(StandardAuditAction.SUPPORT_CLOSE, audit.action)
        assertTrue(audit.metadata.jsonObject.isEmpty())
    }

    protected fun List<AuditLogRecord>.forTicket(ticketId: Long): List<AuditLogRecord> =
        filter { audit ->
            audit.entityType == StandardAuditEntityType.SUPPORT_TICKET && audit.entityId == ticketId
        }

    protected fun List<AuditLogRecord>.withAction(action: StandardAuditAction): List<AuditLogRecord> =
        filter { it.action == action }

    protected fun <T> success(result: SupportServiceResult<T>): T {
        assertTrue(result is SupportServiceResult.Success, result.toString())
        return (result as SupportServiceResult.Success).value
    }

    protected fun assertFailure(
        result: SupportServiceResult<*>,
        expected: SupportServiceError,
    ) {
        assertTrue(result is SupportServiceResult.Failure, result.toString())
        assertEquals(expected, (result as SupportServiceResult.Failure).error)
    }

    protected fun assertDetailFreePersistenceFailure(
        result: SupportServiceResult<*>,
        vararg forbiddenDetails: String,
    ) {
        assertFailure(result, SupportServiceError.PersistenceFailure)
        val rendered = result.toString()
        forbiddenDetails.forEach { detail ->
            assertFalse(rendered.contains(detail, ignoreCase = true), rendered)
        }
    }

    protected companion object {
        const val AUDIT_FAILURE_CONSTRAINT = "support_mutation_it_reject_status_audit"

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
