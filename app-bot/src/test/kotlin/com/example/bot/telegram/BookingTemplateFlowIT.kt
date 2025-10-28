package com.example.bot.telegram

import com.example.bot.booking.BookingCmdResult
import com.example.bot.booking.BookingService
import com.example.bot.data.booking.BookingsTable
import com.example.bot.data.booking.EventsTable
import com.example.bot.data.booking.TablesTable
import com.example.bot.data.booking.core.BookingOutboxTable
import com.example.bot.data.db.Clubs
import com.example.bot.data.notifications.NotificationsOutboxRepository
import com.example.bot.data.notifications.NotificationsOutboxTable
import com.example.bot.data.promo.BookingTemplateRepositoryImpl
import com.example.bot.data.security.ExposedUserRepository
import com.example.bot.data.security.ExposedUserRoleRepository
import com.example.bot.data.security.Role
import com.example.bot.promo.BookingTemplateService
import com.example.bot.promo.TemplateAccessException
import com.example.bot.promo.TemplateActor
import com.example.bot.promo.TemplateBookingRequest
import com.example.bot.promo.TemplateCreateRequest
import com.example.bot.promo.TemplateUpdateRequest
import com.example.bot.testing.PostgresAppTest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

private object TestUsersTable : org.jetbrains.exposed.sql.Table("users") {
    val id = long("id").autoIncrement()
    val telegramUserId = long("telegram_user_id").nullable()
    val username = text("username").nullable()
    val displayName = text("display_name").nullable()
    val phone = text("phone_e164").nullable()
    override val primaryKey = PrimaryKey(id)
}

private object TestUserRolesTable : org.jetbrains.exposed.sql.Table("user_roles") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val roleCode = text("role_code")
    val scopeType = text("scope_type")
    val scopeClubId = long("scope_club_id").nullable()
    override val primaryKey = PrimaryKey(id)
}

class BookingTemplateFlowIT : PostgresAppTest() {
    private val fixedInstant: Instant = Instant.parse("2025-04-10T18:00:00Z")
    private val clock: Clock = Clock.fixed(fixedInstant, ZoneOffset.UTC)

    private lateinit var bookingService: BookingService
    private lateinit var templateService: BookingTemplateService
    private lateinit var userRepository: ExposedUserRepository
    private lateinit var userRoleRepository: ExposedUserRoleRepository

    @BeforeEach
    fun setUpServices() {
        val bookingRepository = com.example.bot.data.booking.core.BookingRepository(database, clock)
        val holdRepository = com.example.bot.data.booking.core.BookingHoldRepository(database, clock)
        val outboxRepository = com.example.bot.data.booking.core.OutboxRepository(database, clock)
        val auditRepository = com.example.bot.data.booking.core.AuditLogRepository(database, clock)
        bookingService = BookingService(bookingRepository, holdRepository, outboxRepository, auditRepository)
        val templateRepository = BookingTemplateRepositoryImpl(database, clock)
        val notificationsOutbox = NotificationsOutboxRepository(database)
        userRepository = ExposedUserRepository(database)
        userRoleRepository = ExposedUserRoleRepository(database)
        templateService =
            BookingTemplateService(
                templateRepository,
                bookingService,
                userRepository,
                userRoleRepository,
                notificationsOutbox,
            )
    }

    @Test
    fun `rbac rules enforced for template lifecycle`() =
        runBlocking {
            val clubOne = insertClub("Orion")
            val clubTwo = insertClub("Andromeda")

            val promoterOneId = insertUser(telegramId = 101L, username = "promoter1")
            val promoterTwoId = insertUser(telegramId = 102L, username = "promoter2")
            val managerId = insertUser(telegramId = 201L, username = "manager")
            val ownerId = insertUser(telegramId = 301L, username = "owner")

            assignRole(promoterOneId, Role.PROMOTER, clubOne)
            assignRole(promoterTwoId, Role.PROMOTER, clubTwo)
            assignRole(managerId, Role.MANAGER, clubOne)
            assignRole(ownerId, Role.OWNER, null)

            val promoterOne = actor(101L)
            val promoterTwo = actor(102L)
            val manager = actor(201L)
            val owner = actor(301L)

            val templateOne =
                templateService.createTemplate(
                    promoterOne,
                    TemplateCreateRequest(
                        promoterUserId = promoterOne.userId,
                        clubId = clubOne,
                        tableCapacityMin = 4,
                        notes = "VIP",
                    ),
                )
            val templateTwo =
                templateService.createTemplate(
                    promoterTwo,
                    TemplateCreateRequest(
                        promoterUserId = promoterTwo.userId,
                        clubId = clubTwo,
                        tableCapacityMin = 2,
                        notes = "BAR",
                    ),
                )

            val ownTemplates = templateService.listTemplates(promoterOne)
            assertEquals(listOf(templateOne.id), ownTemplates.map { it.id })

            try {
                templateService.updateTemplate(
                    promoterOne,
                    TemplateUpdateRequest(
                        id = templateTwo.id,
                        tableCapacityMin = 3,
                        notes = "updated",
                        isActive = true,
                    ),
                )
                fail("expected TemplateAccessException")
            } catch (_: TemplateAccessException) {
                // expected
            }

            val managerTemplates = templateService.listTemplates(manager, clubId = clubOne, onlyActive = false)
            assertEquals(listOf(templateOne.id), managerTemplates.map { it.id })
            try {
                templateService.listTemplates(manager, clubId = clubTwo, onlyActive = false)
                fail("expected TemplateAccessException")
            } catch (_: TemplateAccessException) {
                // expected
            }

            val updated =
                templateService.updateTemplate(
                    manager,
                    TemplateUpdateRequest(
                        id = templateOne.id,
                        tableCapacityMin = 5,
                        notes = "updated",
                        isActive = true,
                    ),
                )
            assertEquals(5, updated.tableCapacityMin)

            val ownerView = templateService.listTemplates(owner, clubId = clubTwo, onlyActive = false)
            assertEquals(listOf(templateTwo.id), ownerView.map { it.id })
        }

    @Test
    fun `apply template books table and enqueues notification`() =
        runBlocking {
            val clubId = insertClub("Nova")
            val promoterId = insertUser(telegramId = 501L, username = "nova-promoter")
            val managerId = insertUser(telegramId = 502L, username = "nova-manager")
            assignRole(promoterId, Role.PROMOTER, clubId)
            assignRole(managerId, Role.MANAGER, clubId)

            val start = fixedInstant.plusSeconds(3_600)
            val end = start.plusSeconds(10_800)
            insertEvent(clubId, start, end)
            val tableId = insertTable(clubId, tableNumber = 10, capacity = 6, deposit = BigDecimal("150.00"))

            val promoter = actor(501L)
            val template =
                templateService.createTemplate(
                    promoter,
                    TemplateCreateRequest(
                        promoterUserId = promoter.userId,
                        clubId = clubId,
                        tableCapacityMin = 4,
                        notes = null,
                    ),
                )

            val result =
                templateService.applyTemplate(
                    promoter,
                    template.id,
                    TemplateBookingRequest(
                        clubId = clubId,
                        tableId = tableId,
                        slotStart = start,
                        slotEnd = end,
                    ),
                )
            assertTrue(result is BookingCmdResult.Booked)

            transaction(database) {
                val bookings =
                    BookingsTable
                        .selectAll()
                        .andWhere { BookingsTable.tableId eq tableId }
                        .toList()
                assertEquals(1, bookings.size)
                val record = bookings.first()
                assertEquals(start, record[BookingsTable.slotStart].toInstant())
                assertEquals(end, record[BookingsTable.slotEnd].toInstant())
            }

            transaction(database) {
                val outboxCount =
                    BookingOutboxTable
                        .selectAll()
                        .andWhere { BookingOutboxTable.topic eq "booking.confirmed" }
                        .count()
                assertEquals(1, outboxCount)
            }

            transaction(database) {
                val rows =
                    NotificationsOutboxTable
                        .selectAll()
                        .andWhere { NotificationsOutboxTable.kind eq "promo.template.booked" }
                        .toList()
                assertEquals(1, rows.size)
                val payload = rows.first()[NotificationsOutboxTable.payload]
                val obj = payload.jsonObject
                assertEquals(template.id, obj["templateId"]?.jsonPrimitive?.long)
                assertEquals(clubId, obj["clubId"]?.jsonPrimitive?.long)
                assertEquals(tableId, obj["tableId"]?.jsonPrimitive?.long)
            }

            val secondResult =
                templateService.applyTemplate(
                    promoter,
                    template.id,
                    TemplateBookingRequest(
                        clubId = clubId,
                        tableId = tableId,
                        slotStart = start,
                        slotEnd = end,
                    ),
                )
            assertTrue(secondResult is BookingCmdResult.DuplicateActiveBooking)

            transaction(database) {
                val outboxRows =
                    NotificationsOutboxTable
                        .selectAll()
                        .andWhere { NotificationsOutboxTable.kind eq "promo.template.booked" }
                        .toList()
                assertEquals(1, outboxRows.size)
            }
        }

    private suspend fun actor(telegramId: Long): TemplateActor =
        templateService.resolveActor(telegramId) ?: error("actor $telegramId not found")

    private fun insertClub(name: String): Long {
        val id =
            transaction(database) {
                Clubs.insert { table ->
                    table[Clubs.name] = name
                    table[Clubs.description] = "$name club"
                    table[Clubs.adminChatId] = null
                    table[Clubs.timezone] = "UTC"
                    table[Clubs.generalTopicId] = null
                    table[Clubs.bookingsTopicId] = null
                    table[Clubs.listsTopicId] = null
                    table[Clubs.qaTopicId] = null
                    table[Clubs.mediaTopicId] = null
                    table[Clubs.systemTopicId] = null
                } get Clubs.id
            }
        return id.value.toLong()
    }

    private fun insertEvent(
        clubId: Long,
        start: Instant,
        end: Instant,
    ): Long {
        return transaction(database) {
            EventsTable.insert { row ->
                row[EventsTable.clubId] = clubId
                row[EventsTable.title] = "Show"
                row[EventsTable.startAt] = OffsetDateTime.ofInstant(start, ZoneOffset.UTC)
                row[EventsTable.endAt] = OffsetDateTime.ofInstant(end, ZoneOffset.UTC)
                row[EventsTable.isSpecial] = false
                row[EventsTable.posterUrl] = null
            } get EventsTable.id
        }
    }

    private fun insertTable(
        clubId: Long,
        tableNumber: Int,
        capacity: Int,
        deposit: BigDecimal,
    ): Long {
        return transaction(database) {
            TablesTable.insert { row ->
                row[TablesTable.clubId] = clubId
                row[TablesTable.zoneId] = null
                row[TablesTable.tableNumber] = tableNumber
                row[TablesTable.capacity] = capacity
                row[TablesTable.minDeposit] = deposit
                row[TablesTable.active] = true
            } get TablesTable.id
        }
    }

    private fun insertUser(
        telegramId: Long,
        username: String,
    ): Long {
        return transaction(database) {
            TestUsersTable.insert { row ->
                row[TestUsersTable.telegramUserId] = telegramId
                row[TestUsersTable.username] = username
                row[TestUsersTable.displayName] = username
                row[TestUsersTable.phone] = null
            } get TestUsersTable.id
        }
    }

    private fun assignRole(
        userId: Long,
        role: Role,
        clubId: Long?,
    ) {
        transaction(database) {
            TestUserRolesTable.insert { row ->
                row[TestUserRolesTable.userId] = userId
                row[TestUserRolesTable.roleCode] = role.name
                if (clubId != null) {
                    row[TestUserRolesTable.scopeType] = "CLUB"
                    row[TestUserRolesTable.scopeClubId] = clubId
                } else {
                    row[TestUserRolesTable.scopeType] = "GLOBAL"
                    row[TestUserRolesTable.scopeClubId] = null
                }
            }
        }
    }
}
