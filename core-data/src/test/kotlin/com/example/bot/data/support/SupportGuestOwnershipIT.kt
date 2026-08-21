package com.example.bot.data.support

import com.example.bot.data.security.ExposedUserRolePermissionRepository
import com.example.bot.data.security.PermissionCode
import com.example.bot.data.security.PermissionCodes
import com.example.bot.data.security.Role
import com.example.bot.support.SupportServiceError
import com.example.bot.support.SupportServiceResult
import com.example.bot.support.TicketSenderType
import com.example.bot.support.TicketStatus
import com.example.bot.support.TicketTopic
import com.example.bot.support.TicketWithMessage
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

@RequiresDocker
@Tag("it")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SupportGuestOwnershipIT {
    private lateinit var database: Database

    @BeforeAll
    fun startContainer() {
        postgres.start()
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations(
                "classpath:db/migration/common",
                "classpath:db/migration/postgresql",
            ).baselineOnMigrate(true)
            .load()
            .migrate()
        database =
            Database.connect(
                url = postgres.jdbcUrl,
                driver = postgres.driverClassName,
                user = postgres.username,
                password = postgres.password,
            )
    }

    @BeforeEach
    fun cleanSupportData() {
        check(::database.isInitialized) { "database not initialised" }
        transaction(database) {
            exec(
                """
                TRUNCATE TABLE
                    ticket_messages,
                    tickets,
                    users,
                    clubs
                RESTART IDENTITY CASCADE
                """.trimIndent(),
            )
        }
    }

    @AfterAll
    fun stopContainer() {
        postgres.stop()
    }

    @Test
    fun `guest list and complete thread remain isolated by persisted owner`() =
        kotlinx.coroutines.runBlocking {
            val userA = insertUser(telegramUserId = 8_800_710L, username = "ownership_guest_a")
            val userB = insertUser(telegramUserId = 8_800_711L, username = "ownership_guest_b")
            val clubA = insertClub("Ownership Club A")
            val clubB = insertClub("Ownership Club B")

            val olderOwned =
                createTicket(
                    clock = clockAt(OLDER_TICKET_AT),
                    clubId = clubA,
                    userId = userA,
                    topic = TicketTopic.DRESSCODE,
                    text = OWN_OLDER_TEXT,
                    attachments = OWN_OLDER_ATTACHMENT,
                )
            val openedOwned =
                createTicket(
                    clock = clockAt(OWNED_TICKET_AT),
                    clubId = clubA,
                    userId = userA,
                    topic = TicketTopic.LOST_FOUND,
                    text = OWN_INITIAL_TEXT,
                    attachments = OWN_INITIAL_ATTACHMENT,
                )
            val foreign =
                createTicket(
                    clock = clockAt(FOREIGN_TICKET_AT),
                    clubId = clubB,
                    userId = userB,
                    topic = TicketTopic.COMPLAINT,
                    text = FOREIGN_INITIAL_TEXT,
                    attachments = FOREIGN_INITIAL_ATTACHMENT,
                )

            val ownGuestMessageId =
                insertMessage(
                    ticketId = openedOwned.ticket.id,
                    senderType = TicketSenderType.GUEST,
                    text = OWN_GUEST_REPLY_TEXT,
                    attachments = OWN_GUEST_ATTACHMENT,
                    createdAt = TIED_MESSAGE_AT,
                )
            val ownAgentMessageId =
                insertMessage(
                    ticketId = openedOwned.ticket.id,
                    senderType = TicketSenderType.AGENT,
                    text = OWN_AGENT_REPLY_TEXT,
                    attachments = OWN_AGENT_ATTACHMENT,
                    createdAt = TIED_MESSAGE_AT,
                )
            val foreignMessageId =
                insertMessage(
                    ticketId = foreign.ticket.id,
                    senderType = TicketSenderType.SYSTEM,
                    text = FOREIGN_THREAD_TEXT,
                    attachments = FOREIGN_THREAD_ATTACHMENT,
                    createdAt = TIED_MESSAGE_AT,
                )
            updateTicketTimestamp(openedOwned.ticket.id, TIED_MESSAGE_AT)
            updateTicketTimestamp(foreign.ticket.id, FOREIGN_UPDATED_AT)

            assertTrue(ownGuestMessageId < ownAgentMessageId)
            assertTrue(ownAgentMessageId < foreignMessageId)

            val service = SupportServiceImpl(SupportRepository(database, clockAt(READ_AT)))
            val summaries = service.listMyTickets(userA)

            assertEquals(listOf(openedOwned.ticket.id, olderOwned.ticket.id), summaries.map { it.id })
            assertTrue(summaries.all { it.clubId == clubA })
            assertTrue(summaries.none { it.id == foreign.ticket.id })
            assertTrue(summaries.none { it.topic == TicketTopic.COMPLAINT })
            assertFalse(summaries.toString().contains(FOREIGN_INITIAL_TEXT))
            assertFalse(summaries.toString().contains(FOREIGN_THREAD_TEXT))
            assertFalse(summaries.toString().contains(FOREIGN_INITIAL_ATTACHMENT))
            assertFalse(summaries.toString().contains(FOREIGN_THREAD_ATTACHMENT))

            val ownedResult = service.getMyTicket(openedOwned.ticket.id, userA)
            assertTrue(ownedResult is SupportServiceResult.Success)
            val ownedThread = (ownedResult as SupportServiceResult.Success).value

            assertEquals(openedOwned.ticket.id, ownedThread.ticket.id)
            assertEquals(clubA, ownedThread.ticket.clubId)
            assertEquals(TicketTopic.LOST_FOUND, ownedThread.ticket.topic)
            assertEquals(TicketStatus.NEW, ownedThread.ticket.status)
            assertEquals(OWNED_TICKET_AT, ownedThread.ticket.createdAt)
            assertEquals(TIED_MESSAGE_AT, ownedThread.ticket.updatedAt)
            assertEquals(
                listOf(openedOwned.initialMessage.id, ownGuestMessageId, ownAgentMessageId),
                ownedThread.messages.map { it.id },
            )
            assertEquals(
                listOf(OWN_INITIAL_TEXT, OWN_GUEST_REPLY_TEXT, OWN_AGENT_REPLY_TEXT),
                ownedThread.messages.map { it.text },
            )
            assertEquals(
                listOf(TicketSenderType.GUEST, TicketSenderType.GUEST, TicketSenderType.AGENT),
                ownedThread.messages.map { it.senderType },
            )
            assertEquals(
                listOf(OWN_INITIAL_ATTACHMENT, OWN_GUEST_ATTACHMENT, OWN_AGENT_ATTACHMENT),
                ownedThread.messages.map { it.attachments },
            )
            assertEquals(
                listOf(OWNED_TICKET_AT, TIED_MESSAGE_AT, TIED_MESSAGE_AT),
                ownedThread.messages.map { it.createdAt },
            )
            assertFalse(ownedThread.toString().contains(FOREIGN_INITIAL_TEXT))
            assertFalse(ownedThread.toString().contains(FOREIGN_THREAD_TEXT))
            assertFalse(ownedThread.toString().contains(FOREIGN_INITIAL_ATTACHMENT))
            assertFalse(ownedThread.toString().contains(FOREIGN_THREAD_ATTACHMENT))

            val foreignRead = service.getMyTicket(openedOwned.ticket.id, userB)
            val missingRead = service.getMyTicket(Long.MAX_VALUE, userB)
            val notFound = SupportServiceResult.Failure(SupportServiceError.TicketNotFound)
            assertEquals(notFound, foreignRead)
            assertEquals(notFound, missingRead)
            assertEquals(foreignRead, missingRead)

            val restartedService = SupportServiceImpl(SupportRepository(database, clockAt(READ_AT)))
            val restartedRead = restartedService.getMyTicket(openedOwned.ticket.id, userA)
            assertEquals(ownedResult, restartedRead)
        }

    @Test
    fun `persisted support permission scopes staff list and deterministic detail to one club`() =
        kotlinx.coroutines.runBlocking {
            val staffUserId = insertUser(telegramUserId = 8_800_720L, username = "staff_manager")
            val guestUserId = insertUser(telegramUserId = 8_800_721L, username = "staff_guest")
            val clubA = insertClub("Staff Permission Club A")
            val clubB = insertClub("Staff Permission Club B")
            val assignmentId = insertRoleAssignment(staffUserId, Role.MANAGER, clubA)
            grantPermission(assignmentId, PermissionCodes.SUPPORT_VIEW)

            val older =
                createTicket(
                    clock = clockAt(OLDER_TICKET_AT),
                    clubId = clubA,
                    userId = guestUserId,
                    topic = TicketTopic.ADDRESS,
                    text = "staff-club-a-older",
                    attachments = null,
                )
            val opened =
                createTicket(
                    clock = clockAt(OWNED_TICKET_AT),
                    clubId = clubA,
                    userId = guestUserId,
                    topic = TicketTopic.COMPLAINT,
                    text = "staff-club-a-initial",
                    attachments = "staff-club-a-attachment",
                )
            val foreign =
                createTicket(
                    clock = clockAt(FOREIGN_TICKET_AT),
                    clubId = clubB,
                    userId = guestUserId,
                    topic = TicketTopic.LOST_FOUND,
                    text = FOREIGN_INITIAL_TEXT,
                    attachments = FOREIGN_INITIAL_ATTACHMENT,
                )
            updateTicketTimestamp(older.ticket.id, TIED_MESSAGE_AT)
            updateTicketTimestamp(opened.ticket.id, TIED_MESSAGE_AT)
            updateTicketTimestamp(foreign.ticket.id, FOREIGN_UPDATED_AT)

            val tiedAgentMessageId =
                insertMessage(
                    ticketId = opened.ticket.id,
                    senderType = TicketSenderType.AGENT,
                    text = "staff-agent-tied",
                    attachments = "staff-agent-attachment",
                    createdAt = TIED_MESSAGE_AT,
                )
            val tiedGuestMessageId =
                insertMessage(
                    ticketId = opened.ticket.id,
                    senderType = TicketSenderType.GUEST,
                    text = "staff-guest-tied",
                    attachments = null,
                    createdAt = TIED_MESSAGE_AT,
                )
            insertMessage(
                ticketId = foreign.ticket.id,
                senderType = TicketSenderType.SYSTEM,
                text = FOREIGN_THREAD_TEXT,
                attachments = FOREIGN_THREAD_ATTACHMENT,
                createdAt = TIED_MESSAGE_AT,
            )

            val permissionRepository = ExposedUserRolePermissionRepository(database)
            assertTrue(
                permissionRepository.hasClubPermission(
                    userId = staffUserId,
                    clubId = clubA,
                    allowedRoles = setOf(Role.MANAGER, Role.CLUB_ADMIN),
                    permission = PermissionCodes.SUPPORT_VIEW,
                ),
            )
            assertFalse(
                permissionRepository.hasClubPermission(
                    userId = staffUserId,
                    clubId = clubB,
                    allowedRoles = setOf(Role.MANAGER, Role.CLUB_ADMIN),
                    permission = PermissionCodes.SUPPORT_VIEW,
                ),
            )
            val permittedClubIds =
                permissionRepository.listClubIdsForPermission(
                    userId = staffUserId,
                    allowedRoles = setOf(Role.MANAGER, Role.CLUB_ADMIN),
                    permission = PermissionCodes.SUPPORT_VIEW,
                )
            assertEquals(setOf(clubA), permittedClubIds)

            val service = SupportServiceImpl(SupportRepository(database, clockAt(READ_AT)))
            val listResult = service.listStaffTicketsForClub(clubA, status = null)
            assertTrue(listResult is SupportServiceResult.Success)
            val summaries = (listResult as SupportServiceResult.Success).value
            assertEquals(listOf(opened.ticket.id, older.ticket.id), summaries.map { it.id })
            assertTrue(summaries.all { it.clubId == clubA })
            assertFalse(summaries.toString().contains(FOREIGN_INITIAL_TEXT))
            assertFalse(summaries.toString().contains(FOREIGN_THREAD_TEXT))

            val detailResult = service.getStaffTicket(opened.ticket.id, permittedClubIds)
            assertTrue(detailResult is SupportServiceResult.Success)
            val detail = (detailResult as SupportServiceResult.Success).value
            assertEquals(clubA, detail.ticket.clubId)
            assertEquals(
                listOf(opened.initialMessage.id, tiedAgentMessageId, tiedGuestMessageId),
                detail.messages.map { it.id },
            )
            assertFalse(detail.toString().contains(FOREIGN_INITIAL_TEXT))
            assertFalse(detail.toString().contains(FOREIGN_THREAD_TEXT))
            assertFalse(detail.toString().contains(FOREIGN_INITIAL_ATTACHMENT))
            assertFalse(detail.toString().contains(FOREIGN_THREAD_ATTACHMENT))

            val foreignRead = service.getStaffTicket(foreign.ticket.id, permittedClubIds)
            val missingRead = service.getStaffTicket(Long.MAX_VALUE, permittedClubIds)
            assertEquals(SupportServiceResult.Failure(SupportServiceError.TicketNotFound), foreignRead)
            assertEquals(missingRead, foreignRead)

            val restartedPermissions = ExposedUserRolePermissionRepository(database)
            val restartedClubIds =
                restartedPermissions.listClubIdsForPermission(
                    userId = staffUserId,
                    allowedRoles = setOf(Role.MANAGER, Role.CLUB_ADMIN),
                    permission = PermissionCodes.SUPPORT_VIEW,
                )
            val restartedService = SupportServiceImpl(SupportRepository(database, clockAt(READ_AT)))
            assertEquals(permittedClubIds, restartedClubIds)
            assertEquals(detailResult, restartedService.getStaffTicket(opened.ticket.id, restartedClubIds))
        }

    private suspend fun createTicket(
        clock: Clock,
        clubId: Long,
        userId: Long,
        topic: TicketTopic,
        text: String,
        attachments: String?,
    ): TicketWithMessage {
        val result =
            SupportServiceImpl(SupportRepository(database, clock)).createTicket(
                clubId = clubId,
                userId = userId,
                bookingId = null,
                listEntryId = null,
                topic = topic,
                text = text,
                attachments = attachments,
            )
        assertTrue(result is SupportServiceResult.Success)
        return (result as SupportServiceResult.Success).value
    }

    private fun insertMessage(
        ticketId: Long,
        senderType: TicketSenderType,
        text: String,
        attachments: String?,
        createdAt: Instant,
    ): Long =
        transaction(database) {
            TicketMessagesTable.insert {
                it[TicketMessagesTable.ticketId] = ticketId
                it[TicketMessagesTable.senderType] = senderType.wire
                it[TicketMessagesTable.text] = text
                it[TicketMessagesTable.attachments] = attachments
                it[TicketMessagesTable.createdAt] = createdAt.atOffset(ZoneOffset.UTC)
            }[TicketMessagesTable.id]
        }

    private fun updateTicketTimestamp(
        ticketId: Long,
        updatedAt: Instant,
    ) {
        transaction(database) {
            val updated =
                TicketsTable.update({ TicketsTable.id eq ticketId }) {
                    it[TicketsTable.updatedAt] = updatedAt.atOffset(ZoneOffset.UTC)
                }
            assertEquals(1, updated)
        }
    }

    private fun insertUser(
        telegramUserId: Long,
        username: String,
    ): Long =
        transaction(database) {
            SupportGuestOwnershipUsersTable
                .insert {
                    it[SupportGuestOwnershipUsersTable.telegramUserId] = telegramUserId
                    it[SupportGuestOwnershipUsersTable.username] = username
                    it[SupportGuestOwnershipUsersTable.displayName] = username
                    it[SupportGuestOwnershipUsersTable.phoneE164] = null
                }[SupportGuestOwnershipUsersTable.id]
        }

    private fun insertClub(name: String): Long =
        transaction(database) {
            SupportGuestOwnershipClubsTable
                .insert {
                    it[SupportGuestOwnershipClubsTable.name] = name
                    it[SupportGuestOwnershipClubsTable.description] = null
                    it[SupportGuestOwnershipClubsTable.timezone] = "Europe/Moscow"
                    it[SupportGuestOwnershipClubsTable.adminChannelId] = null
                    it[SupportGuestOwnershipClubsTable.bookingsTopicId] = null
                    it[SupportGuestOwnershipClubsTable.checkinTopicId] = null
                    it[SupportGuestOwnershipClubsTable.qaTopicId] = null
                }[SupportGuestOwnershipClubsTable.id]
        }

    private fun insertRoleAssignment(
        userId: Long,
        role: Role,
        clubId: Long,
    ): Long =
        transaction(database) {
            SupportGuestOwnershipUserRolesTable.insert {
                it[SupportGuestOwnershipUserRolesTable.userId] = userId
                it[SupportGuestOwnershipUserRolesTable.roleCode] = role.name
                it[SupportGuestOwnershipUserRolesTable.scopeType] = "CLUB"
                it[SupportGuestOwnershipUserRolesTable.scopeClubId] = clubId
            }[SupportGuestOwnershipUserRolesTable.id]
        }

    private fun grantPermission(
        userRoleId: Long,
        permission: PermissionCode,
    ) {
        transaction(database) {
            SupportGuestOwnershipUserRolePermissionsTable.insert {
                it[SupportGuestOwnershipUserRolePermissionsTable.userRoleId] = userRoleId
                it[SupportGuestOwnershipUserRolePermissionsTable.permissionCode] = permission.value
            }
        }
    }

    private fun clockAt(instant: Instant): Clock = Clock.fixed(instant, ZoneOffset.UTC)

    companion object {
        private val OLDER_TICKET_AT = Instant.parse("2024-06-01T10:00:00Z")
        private val OWNED_TICKET_AT = Instant.parse("2024-06-01T11:00:00Z")
        private val FOREIGN_TICKET_AT = Instant.parse("2024-06-01T12:00:00Z")
        private val TIED_MESSAGE_AT = Instant.parse("2024-06-01T11:05:00Z")
        private val FOREIGN_UPDATED_AT = Instant.parse("2024-06-01T12:05:00Z")
        private val READ_AT = Instant.parse("2024-06-01T13:00:00Z")

        private const val OWN_OLDER_TEXT = "owner-a-older-ticket"
        private const val OWN_INITIAL_TEXT = "owner-a-initial-message"
        private const val OWN_GUEST_REPLY_TEXT = "owner-a-guest-follow-up"
        private const val OWN_AGENT_REPLY_TEXT = "owner-a-agent-reply"
        private const val OWN_OLDER_ATTACHMENT = "owner-a-older-attachment"
        private const val OWN_INITIAL_ATTACHMENT = "owner-a-initial-attachment"
        private const val OWN_GUEST_ATTACHMENT = "owner-a-guest-attachment"
        private const val OWN_AGENT_ATTACHMENT = "owner-a-agent-attachment"
        private const val FOREIGN_INITIAL_TEXT = "owner-b-private-initial-message"
        private const val FOREIGN_THREAD_TEXT = "owner-b-private-thread-message"
        private const val FOREIGN_INITIAL_ATTACHMENT = "owner-b-private-initial-attachment"
        private const val FOREIGN_THREAD_ATTACHMENT = "owner-b-private-thread-attachment"

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

private object SupportGuestOwnershipClubsTable : Table("clubs") {
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

private object SupportGuestOwnershipUsersTable : Table("users") {
    val id = long("id").autoIncrement()
    val telegramUserId = long("telegram_user_id").nullable()
    val username = text("username").nullable()
    val displayName = text("display_name").nullable()
    val phoneE164 = text("phone_e164").nullable()
    override val primaryKey = PrimaryKey(id)
}

private object SupportGuestOwnershipUserRolesTable : Table("user_roles") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val roleCode = text("role_code")
    val scopeType = text("scope_type")
    val scopeClubId = long("scope_club_id").nullable()
    override val primaryKey = PrimaryKey(id)
}

private object SupportGuestOwnershipUserRolePermissionsTable : Table("user_role_permissions") {
    val userRoleId = long("user_role_id")
    val permissionCode = text("permission_code")
    override val primaryKey = PrimaryKey(userRoleId, permissionCode)
}
