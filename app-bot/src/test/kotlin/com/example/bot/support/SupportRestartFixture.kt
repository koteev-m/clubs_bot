package com.example.bot.support

import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Types
import java.time.Duration
import java.time.OffsetDateTime

internal data class RestartDatabaseConfig(
    val jdbcUrl: String,
    val username: String,
    val password: String,
) {
    fun connect(): Connection = DriverManager.getConnection(jdbcUrl, username, password)
}

internal data class DatabaseIdentity(
    val databaseName: String,
    val databaseOid: Long,
    val postmasterStartedAt: String,
)

internal data class ApplicationUserRow(
    val id: Long,
    val telegramUserId: Long,
    val username: String?,
    val displayName: String?,
)

internal data class ClubRow(
    val id: Long,
    val name: String,
)

internal data class StaffAssignmentRow(
    val id: Long,
    val userId: Long,
    val roleCode: String,
    val scopeType: String,
    val scopeClubId: Long,
    val permissions: List<String>,
)

internal data class InstalledStaffFixture(
    val assignment: StaffAssignmentRow,
    val club: ClubRow,
    val foreignClub: ClubRow,
)

internal data class DurableTicketRow(
    val id: Long,
    val clubId: Long,
    val userId: Long,
    val topic: String,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
    val lastAgentId: Long?,
)

internal data class DurableMessageRow(
    val id: Long,
    val ticketId: Long,
    val senderType: String,
    val text: String,
    val attachments: String?,
    val createdAt: String,
)

internal data class DurableDeliveryRow(
    val id: Long,
    val replyMessageId: Long,
    val ticketId: Long,
    val recipientUserId: Long,
    val actingStaffUserId: Long,
    val actingRole: String,
    val status: String,
    val failureCode: String?,
    val createdAt: String,
    val updatedAt: String,
    val completedAt: String?,
)

internal data class DurableAuditRow(
    val id: Long,
    val createdAt: String,
    val clubId: Long?,
    val actorUserId: Long?,
    val actorRole: String?,
    val entityType: String,
    val entityId: Long?,
    val action: String,
    val fingerprint: String,
    val metadataJson: String,
)

internal data class DurableSupportState(
    val databaseIdentity: DatabaseIdentity,
    val guestUser: ApplicationUserRow,
    val staffUser: ApplicationUserRow,
    val guestUserRowCount: Long,
    val staffUserRowCount: Long,
    val assignment: StaffAssignmentRow,
    val ticket: DurableTicketRow,
    val messages: List<DurableMessageRow>,
    val delivery: DurableDeliveryRow,
    val audits: List<DurableAuditRow>,
    val allTicketCount: Long,
    val scopedTicketCount: Long,
    val scopedMessageCount: Long,
    val scopedDeliveryCount: Long,
)

internal object SupportRestartDatabase {
    fun awaitProvisionedUsers(
        config: RestartDatabaseConfig,
        guestTelegramId: Long,
        staffTelegramId: Long,
        updateIds: Collection<Long>,
    ): Pair<ApplicationUserRow, ApplicationUserRow> {
        awaitCondition(
            description = "durable /start identity provisioning",
            timeout = Duration.ofSeconds(20),
        ) {
            config.connect().use { connection ->
                findUser(connection, guestTelegramId) != null &&
                    findUser(connection, staffTelegramId) != null &&
                    updateIds.all { updateId -> webhookStatus(connection, updateId) == "DONE" }
            }
        }
        return config.connect().use { connection ->
            requireNotNull(findUser(connection, guestTelegramId)) to
                requireNotNull(findUser(connection, staffTelegramId))
        }
    }

    fun awaitRepeatedStart(
        config: RestartDatabaseConfig,
        telegramUserId: Long,
        updateId: Long,
        expectedUserId: Long,
    ): ApplicationUserRow {
        awaitCondition(
            description = "repeated /start durable completion",
            timeout = Duration.ofSeconds(20),
        ) {
            config.connect().use { connection ->
                val user = findUser(connection, telegramUserId)
                user?.id == expectedUserId &&
                    userCount(connection, telegramUserId) == 1L &&
                    webhookStatus(connection, updateId) == "DONE"
            }
        }
        return config.connect().use { connection ->
            requireNotNull(findUser(connection, telegramUserId))
        }
    }

    fun installExactStaffFixture(
        config: RestartDatabaseConfig,
        staffUserId: Long,
    ): InstalledStaffFixture =
        config.connect().use { connection ->
            connection.autoCommit = false
            try {
                val club = findSeededClub(connection, "Aurora")
                val foreignClub = findSeededClub(connection, "Nebula")
                val assignmentId = insertStaffAssignment(connection, staffUserId, club.id)
                insertStaffPermissions(connection, assignmentId)
                connection.commit()
                InstalledStaffFixture(
                    assignment = loadAssignment(connection, assignmentId),
                    club = club,
                    foreignClub = foreignClub,
                )
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            } finally {
                connection.autoCommit = true
            }
        }

    private fun insertStaffAssignment(
        connection: Connection,
        staffUserId: Long,
        clubId: Long,
    ): Long =
        connection
            .prepareStatement(
                """
                INSERT INTO user_roles(user_id, role_code, scope_type, scope_club_id)
                VALUES (?, 'MANAGER', 'CLUB', ?)
                RETURNING id
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, staffUserId)
                statement.setLong(2, clubId)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "staff assignment insert returned no id" }
                    rows.getLong(1)
                }
            }

    private fun insertStaffPermissions(
        connection: Connection,
        assignmentId: Long,
    ) {
        listOf("support.view", "support.reply").forEach { permission ->
            connection
                .prepareStatement(
                    "INSERT INTO user_role_permissions(user_role_id, permission_code) VALUES (?, ?)",
                ).use { statement ->
                    statement.setLong(1, assignmentId)
                    statement.setString(2, permission)
                    check(statement.executeUpdate() == 1) { "permission fixture insert failed" }
                }
        }
    }

    fun databaseIdentity(config: RestartDatabaseConfig): DatabaseIdentity = config.connect().use(::readDatabaseIdentity)

    fun userByTelegramId(
        config: RestartDatabaseConfig,
        telegramUserId: Long,
    ): ApplicationUserRow? = config.connect().use { findUser(it, telegramUserId) }

    fun inspectTicketAndMessages(
        config: RestartDatabaseConfig,
        ticketId: Long,
    ): Pair<DurableTicketRow, List<DurableMessageRow>> =
        config.connect().use { connection ->
            loadTicket(connection, ticketId) to loadMessages(connection, ticketId)
        }

    fun inspectDurableState(
        config: RestartDatabaseConfig,
        ticketId: Long,
        guestTelegramId: Long,
        staffTelegramId: Long,
        assignmentId: Long,
    ): DurableSupportState =
        config.connect().use { connection ->
            DurableSupportState(
                databaseIdentity = readDatabaseIdentity(connection),
                guestUser = requireNotNull(findUser(connection, guestTelegramId)),
                staffUser = requireNotNull(findUser(connection, staffTelegramId)),
                guestUserRowCount = userCount(connection, guestTelegramId),
                staffUserRowCount = userCount(connection, staffTelegramId),
                assignment = loadAssignment(connection, assignmentId),
                ticket = loadTicket(connection, ticketId),
                messages = loadMessages(connection, ticketId),
                delivery = loadDelivery(connection, ticketId),
                audits = loadAudits(connection, ticketId),
                allTicketCount = count(connection, "SELECT COUNT(*) FROM tickets"),
                scopedTicketCount = count(connection, "SELECT COUNT(*) FROM tickets WHERE id = ?", ticketId),
                scopedMessageCount =
                    count(connection, "SELECT COUNT(*) FROM ticket_messages WHERE ticket_id = ?", ticketId),
                scopedDeliveryCount =
                    count(connection, "SELECT COUNT(*) FROM support_reply_deliveries WHERE ticket_id = ?", ticketId),
            )
        }

    private fun readDatabaseIdentity(connection: Connection): DatabaseIdentity =
        connection
            .prepareStatement(
                """
                SELECT current_database() AS database_name,
                       (SELECT oid FROM pg_database WHERE datname = current_database()) AS database_oid,
                       pg_postmaster_start_time() AS postmaster_started_at
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "database identity query returned no row" }
                    DatabaseIdentity(
                        databaseName = rows.getString("database_name"),
                        databaseOid = rows.getLong("database_oid"),
                        postmasterStartedAt = rows.requiredInstantString("postmaster_started_at"),
                    )
                }
            }

    private fun findSeededClub(
        connection: Connection,
        name: String,
    ): ClubRow =
        connection.prepareStatement("SELECT id, name FROM clubs WHERE name = ? ORDER BY id LIMIT 1").use { statement ->
            statement.setString(1, name)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "seeded club was not found: $name" }
                ClubRow(id = rows.getLong("id"), name = rows.getString("name"))
            }
        }

    private fun findUser(
        connection: Connection,
        telegramUserId: Long,
    ): ApplicationUserRow? =
        connection
            .prepareStatement(
                """
                SELECT id, telegram_user_id, username, display_name
                FROM users
                WHERE telegram_user_id = ?
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, telegramUserId)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) {
                        null
                    } else {
                        ApplicationUserRow(
                            id = rows.getLong("id"),
                            telegramUserId = rows.getLong("telegram_user_id"),
                            username = rows.getString("username"),
                            displayName = rows.getString("display_name"),
                        )
                    }
                }
            }

    private fun userCount(
        connection: Connection,
        telegramUserId: Long,
    ): Long = count(connection, "SELECT COUNT(*) FROM users WHERE telegram_user_id = ?", telegramUserId)

    private fun webhookStatus(
        connection: Connection,
        updateId: Long,
    ): String? =
        connection
            .prepareStatement("SELECT status FROM telegram_webhook_updates WHERE update_id = ?")
            .use { statement ->
                statement.setLong(1, updateId)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }

    private fun loadAssignment(
        connection: Connection,
        assignmentId: Long,
    ): StaffAssignmentRow {
        val assignment =
            connection
                .prepareStatement(
                    """
                    SELECT id, user_id, role_code, scope_type, scope_club_id
                    FROM user_roles
                    WHERE id = ?
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, assignmentId)
                    statement.executeQuery().use { rows ->
                        check(rows.next()) { "staff assignment $assignmentId was not found" }
                        StaffAssignmentRow(
                            id = rows.getLong("id"),
                            userId = rows.getLong("user_id"),
                            roleCode = rows.getString("role_code"),
                            scopeType = rows.getString("scope_type"),
                            scopeClubId = rows.getLong("scope_club_id"),
                            permissions = emptyList(),
                        )
                    }
                }
        val permissions =
            connection
                .prepareStatement(
                    """
                    SELECT permission_code
                    FROM user_role_permissions
                    WHERE user_role_id = ?
                    ORDER BY permission_code
                    """.trimIndent(),
                ).use { statement ->
                    statement.setLong(1, assignmentId)
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                add(rows.getString("permission_code"))
                            }
                        }
                    }
                }
        return assignment.copy(permissions = permissions)
    }

    private fun loadTicket(
        connection: Connection,
        ticketId: Long,
    ): DurableTicketRow =
        connection
            .prepareStatement(
                """
                SELECT id, club_id, user_id, topic, status, created_at, updated_at, last_agent_id
                FROM tickets
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, ticketId)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "ticket $ticketId was not found" }
                    DurableTicketRow(
                        id = rows.getLong("id"),
                        clubId = rows.getLong("club_id"),
                        userId = rows.getLong("user_id"),
                        topic = rows.getString("topic"),
                        status = rows.getString("status"),
                        createdAt = rows.requiredInstantString("created_at"),
                        updatedAt = rows.requiredInstantString("updated_at"),
                        lastAgentId = rows.nullableLong("last_agent_id"),
                    )
                }
            }

    private fun loadMessages(
        connection: Connection,
        ticketId: Long,
    ): List<DurableMessageRow> =
        connection
            .prepareStatement(
                """
                SELECT id, ticket_id, sender_type, text, attachments, created_at
                FROM ticket_messages
                WHERE ticket_id = ?
                ORDER BY created_at ASC, id ASC
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, ticketId)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                DurableMessageRow(
                                    id = rows.getLong("id"),
                                    ticketId = rows.getLong("ticket_id"),
                                    senderType = rows.getString("sender_type"),
                                    text = rows.getString("text"),
                                    attachments = rows.getString("attachments"),
                                    createdAt = rows.requiredInstantString("created_at"),
                                ),
                            )
                        }
                    }
                }
            }

    private fun loadDelivery(
        connection: Connection,
        ticketId: Long,
    ): DurableDeliveryRow =
        connection
            .prepareStatement(
                """
                SELECT id, reply_message_id, ticket_id, recipient_user_id, acting_staff_user_id,
                       acting_role, status, failure_code, created_at, updated_at, completed_at
                FROM support_reply_deliveries
                WHERE ticket_id = ?
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, ticketId)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "delivery for ticket $ticketId was not found" }
                    val delivery =
                        DurableDeliveryRow(
                            id = rows.getLong("id"),
                            replyMessageId = rows.getLong("reply_message_id"),
                            ticketId = rows.getLong("ticket_id"),
                            recipientUserId = rows.getLong("recipient_user_id"),
                            actingStaffUserId = rows.getLong("acting_staff_user_id"),
                            actingRole = rows.getString("acting_role"),
                            status = rows.getString("status"),
                            failureCode = rows.getString("failure_code"),
                            createdAt = rows.requiredInstantString("created_at"),
                            updatedAt = rows.requiredInstantString("updated_at"),
                            completedAt = rows.optionalInstantString("completed_at"),
                        )
                    check(!rows.next()) { "more than one delivery exists for ticket $ticketId" }
                    delivery
                }
            }

    private fun loadAudits(
        connection: Connection,
        ticketId: Long,
    ): List<DurableAuditRow> =
        connection
            .prepareStatement(
                """
                SELECT id, created_at, club_id, actor_user_id, actor_role, entity_type, entity_id,
                       action, fingerprint, metadata_json
                FROM audit_log
                WHERE entity_type = 'SUPPORT_TICKET' AND entity_id = ?
                ORDER BY id
                """.trimIndent(),
            ).use { statement ->
                statement.setLong(1, ticketId)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            add(
                                DurableAuditRow(
                                    id = rows.getLong("id"),
                                    createdAt = rows.requiredInstantString("created_at"),
                                    clubId = rows.nullableLong("club_id"),
                                    actorUserId = rows.nullableLong("actor_user_id"),
                                    actorRole = rows.getString("actor_role"),
                                    entityType = rows.getString("entity_type"),
                                    entityId = rows.nullableLong("entity_id"),
                                    action = rows.getString("action"),
                                    fingerprint = rows.getString("fingerprint"),
                                    metadataJson = rows.getString("metadata_json"),
                                ),
                            )
                        }
                    }
                }
            }

    private fun count(
        connection: Connection,
        sql: String,
        parameter: Long? = null,
    ): Long =
        connection.prepareStatement(sql).use { statement ->
            if (parameter != null) {
                statement.setLong(1, parameter)
            } else if (statement.parameterMetaData.parameterCount > 0) {
                statement.setNull(1, Types.BIGINT)
            }
            statement.executeQuery().use { rows ->
                check(rows.next()) { "count query returned no row" }
                rows.getLong(1)
            }
        }
}

private fun ResultSet.requiredInstantString(column: String): String =
    requireNotNull(getObject(column, OffsetDateTime::class.java)).toInstant().toString()

private fun ResultSet.optionalInstantString(column: String): String? =
    getObject(column, OffsetDateTime::class.java)?.toInstant()?.toString()

private fun ResultSet.nullableLong(column: String): Long? {
    val value = getLong(column)
    return if (wasNull()) null else value
}
