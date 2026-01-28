package com.example.bot.routes

import com.example.bot.admin.AdminHallsRepository
import com.example.bot.booking.a3.BookingState
import com.example.bot.club.GuestListOwnerType
import com.example.bot.club.GuestListRepository
import com.example.bot.club.GuestListService
import com.example.bot.club.InvitationService
import com.example.bot.audit.AuditLogRepository
import com.example.bot.data.club.GuestListDbRepository
import com.example.bot.data.club.GuestListEntryDbRepository
import com.example.bot.data.club.GuestListEntryRecord
import com.example.bot.data.club.GuestListRecord
import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.layout.AdminTablesRepository
import com.example.bot.plugins.MiniAppUserKey
import com.example.bot.plugins.TelegramMiniUser
import com.example.bot.plugins.installMiniAppAuthStatusPage
import com.example.bot.plugins.overrideMiniAppValidatorForTesting
import com.example.bot.plugins.resetMiniAppValidator
import com.example.bot.data.promoter.PromoterBookingAssignmentsRepository
import com.example.bot.security.auth.TelegramPrincipal
import com.example.bot.security.rbac.RbacPlugin
import com.example.bot.testing.createInitData
import com.example.bot.testing.withInitData
import com.example.bot.webapp.TEST_BOT_TOKEN
import com.example.bot.clubs.ClubsRepository
import com.example.bot.clubs.EventsRepository
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.request.header
import io.ktor.server.testing.testApplication
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PromoterStatsRoutesTest {
    @BeforeEach
    fun setUp() {
        overrideMiniAppValidatorForTesting { _, _ -> TelegramMiniUser(id = 100) }
    }

    @AfterEach
    fun tearDown() {
        resetMiniAppValidator()
    }

    @Test
    fun `stats endpoint requires promoter role`() = runBlockingUnit {
        val guestListRepository = mockk<GuestListRepository>(relaxed = true)
        val guestListService = mockk<GuestListService>(relaxed = true)
        val guestListEntryRepository = mockk<GuestListEntryDbRepository>(relaxed = true)
        val invitationService = mockk<InvitationService>(relaxed = true)
        val guestListDbRepository = mockk<GuestListDbRepository>(relaxed = true)
        val clubsRepository = mockk<ClubsRepository>(relaxed = true)
        val eventsRepository = mockk<EventsRepository>(relaxed = true)
        val adminHallsRepository = mockk<AdminHallsRepository>(relaxed = true)
        val adminTablesRepository = mockk<AdminTablesRepository>(relaxed = true)
        val bookingState = mockk<BookingState>(relaxed = true)
        val promoterAssignments = mockk<PromoterBookingAssignmentsRepository>(relaxed = true)

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installRbac(roles = emptySet())
                promoterGuestListRoutes(
                    guestListRepository = guestListRepository,
                    guestListService = guestListService,
                    guestListEntryRepository = guestListEntryRepository,
                    invitationService = invitationService,
                    guestListDbRepository = guestListDbRepository,
                    clubsRepository = clubsRepository,
                    eventsRepository = eventsRepository,
                    adminHallsRepository = adminHallsRepository,
                    adminTablesRepository = adminTablesRepository,
                    bookingState = bookingState,
                    promoterAssignments = promoterAssignments,
                    botTokenProvider = { TEST_BOT_TOKEN },
                )
            }

            val initData = createInitData(userId = 100)
            val response =
                client.get("/api/promoter/me/stats") {
                    withInitData(initData)
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
        }
    }

    @Test
    fun `stats endpoint returns totals`() = runBlockingUnit {
        val guestListRepository = mockk<GuestListRepository>(relaxed = true)
        val guestListService = mockk<GuestListService>(relaxed = true)
        val guestListEntryRepository = mockk<GuestListEntryDbRepository>(relaxed = true)
        val invitationService = mockk<InvitationService>(relaxed = true)
        val guestListDbRepository = mockk<GuestListDbRepository>(relaxed = true)
        val clubsRepository = mockk<ClubsRepository>(relaxed = true)
        val eventsRepository = mockk<EventsRepository>(relaxed = true)
        val adminHallsRepository = mockk<AdminHallsRepository>(relaxed = true)
        val adminTablesRepository = mockk<AdminTablesRepository>(relaxed = true)
        val bookingState = mockk<BookingState>(relaxed = true)
        val promoterAssignments = mockk<PromoterBookingAssignmentsRepository>(relaxed = true)

        val list =
            GuestListRecord(
                id = 10,
                clubId = 2,
                eventId = 5,
                promoterId = 1,
                ownerType = GuestListOwnerType.PROMOTER,
                ownerUserId = 1,
                title = "GL",
                capacity = 10,
                arrivalWindowStart = Instant.parse("2024-06-01T18:00:00Z"),
                arrivalWindowEnd = Instant.parse("2024-06-01T20:00:00Z"),
                status = com.example.bot.club.GuestListStatus.ACTIVE,
                createdAt = Instant.parse("2024-06-01T10:00:00Z"),
                updatedAt = Instant.parse("2024-06-01T10:00:00Z"),
            )
        val entries =
            listOf(
                GuestListEntryRecord(
                    id = 1,
                    guestListId = list.id,
                    displayName = "A",
                    fullName = "A",
                    telegramUserId = null,
                    status = com.example.bot.club.GuestListEntryStatus.ARRIVED,
                    createdAt = Instant.parse("2024-06-01T10:00:00Z"),
                    updatedAt = Instant.parse("2024-06-01T10:00:00Z"),
                ),
                GuestListEntryRecord(
                    id = 2,
                    guestListId = list.id,
                    displayName = "B",
                    fullName = "B",
                    telegramUserId = null,
                    status = com.example.bot.club.GuestListEntryStatus.ADDED,
                    createdAt = Instant.parse("2024-06-01T10:00:00Z"),
                    updatedAt = Instant.parse("2024-06-01T10:00:00Z"),
                ),
            )

        coEvery { guestListDbRepository.listByPromoter(1) } returns listOf(list)
        coEvery { guestListEntryRepository.listByGuestList(list.id) } returns entries

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installRbac(roles = setOf(Role.PROMOTER), clubIds = setOf(2))
                promoterGuestListRoutes(
                    guestListRepository = guestListRepository,
                    guestListService = guestListService,
                    guestListEntryRepository = guestListEntryRepository,
                    invitationService = invitationService,
                    guestListDbRepository = guestListDbRepository,
                    clubsRepository = clubsRepository,
                    eventsRepository = eventsRepository,
                    adminHallsRepository = adminHallsRepository,
                    adminTablesRepository = adminTablesRepository,
                    bookingState = bookingState,
                    promoterAssignments = promoterAssignments,
                    botTokenProvider = { TEST_BOT_TOKEN },
                )
            }

            val initData = createInitData(userId = 100)
            val response =
                client.get("/api/promoter/me/stats") {
                    withInitData(initData)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = response.bodyAsJson()
            assertEquals(2, payload["totalAdded"]!!.jsonPrimitive.int)
            assertEquals(1, payload["totalArrived"]!!.jsonPrimitive.int)
        }
    }
}

private fun Application.installRbac(
    roles: Set<Role>,
    clubIds: Set<Long> = emptySet(),
) {
    installMiniAppAuthStatusPage()
    install(RbacPlugin) {
        userRepository = PromoterStatsStubUserRepository()
        userRoleRepository = PromoterStatsStubUserRoleRepository(roles, clubIds)
        auditLogRepository = relaxedAuditRepository()
        principalExtractor = { call ->
            if (call.attributes.contains(MiniAppUserKey)) {
                val principal = call.attributes[MiniAppUserKey]
                TelegramPrincipal(principal.id, principal.username)
            } else {
                call.request.header("X-Telegram-Id")?.toLongOrNull()?.let { id ->
                    TelegramPrincipal(id, call.request.header("X-Telegram-Username"))
                }
            }
        }
    }
}

private class PromoterStatsStubUserRepository : UserRepository {
    override suspend fun getByTelegramId(id: Long): User? = User(id = 1, telegramId = id, username = "tester")

    override suspend fun getById(id: Long): User? = User(id = id, telegramId = id, username = "tester")
}

private class PromoterStatsStubUserRoleRepository(
    private val roles: Set<Role>,
    private val clubIds: Set<Long>,
) : UserRoleRepository {
    override suspend fun listRoles(userId: Long): Set<Role> = roles

    override suspend fun listClubIdsFor(userId: Long): Set<Long> = clubIds
}

private fun relaxedAuditRepository(): AuditLogRepository = mockk(relaxed = true)

private fun runBlockingUnit(block: suspend () -> Unit) = runBlocking { block() }

private suspend fun io.ktor.client.statement.HttpResponse.bodyAsJson(): JsonObject =
    Json.parseToJsonElement(bodyAsText()).jsonObject
