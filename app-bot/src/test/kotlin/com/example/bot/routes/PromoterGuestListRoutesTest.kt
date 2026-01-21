package com.example.bot.routes

import com.example.bot.club.GuestListOwnerType
import com.example.bot.club.GuestList
import com.example.bot.club.GuestListStatus
import com.example.bot.club.GuestListService
import com.example.bot.club.GuestListServiceResult
import com.example.bot.club.GuestListStats
import com.example.bot.club.GuestListInfo
import com.example.bot.club.InvitationCard
import com.example.bot.club.InvitationChannel
import com.example.bot.club.InvitationResponse
import com.example.bot.club.InvitationService
import com.example.bot.club.InvitationServiceError
import com.example.bot.club.InvitationServiceResult
import com.example.bot.club.GuestListRepository
import com.example.bot.data.booking.core.AuditLogRepository
import com.example.bot.data.club.GuestListDbRepository
import com.example.bot.data.club.GuestListEntryDbRepository
import com.example.bot.data.club.GuestListEntryRecord
import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
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
import com.example.bot.admin.AdminHallsRepository
import com.example.bot.admin.AdminHall
import com.example.bot.clubs.ClubsRepository
import com.example.bot.clubs.EventsRepository
import com.example.bot.layout.AdminTablesRepository
import com.example.bot.booking.a3.BookingState
import com.example.bot.booking.a3.Booking
import com.example.bot.booking.a3.BookingResponseSnapshot
import com.example.bot.booking.a3.BookingStatus
import com.example.bot.booking.a3.BookingView
import com.example.bot.booking.a3.ConfirmResult
import com.example.bot.booking.a3.HoldResult
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.header
import io.ktor.server.testing.testApplication
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PromoterGuestListRoutesTest {
    @BeforeEach
    fun setUp() {
        overrideMiniAppValidatorForTesting { _, _ -> TelegramMiniUser(id = 100) }
    }

    @AfterEach
    fun tearDown() {
        resetMiniAppValidator()
    }

    @Test
    fun `promoter guest lists require promoter role`() = runBlockingUnit {
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
                client.post("/api/promoter/guest-lists") {
                    withInitData(initData)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "clubId": 1,
                          "eventId": 10,
                          "arrivalWindowStart": "2024-06-01T12:00:00Z",
                          "arrivalWindowEnd": "2024-06-01T14:00:00Z",
                          "limit": 20,
                          "name": "VIP"
                        }
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            coVerify(exactly = 0) {
                guestListService.createGuestList(
                    promoterId = any(),
                    clubId = any(),
                    eventId = any(),
                    ownerType = GuestListOwnerType.PROMOTER,
                    ownerUserId = any(),
                    arrivalWindowStart = any(),
                    arrivalWindowEnd = any(),
                    limit = any(),
                    title = any(),
                )
            }
        }
    }

    @Test
    fun `promoter without club scope cannot create guest list`() = runBlockingUnit {
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
                installRbac(roles = setOf(Role.PROMOTER))
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
                client.post("/api/promoter/guest-lists") {
                    withInitData(initData)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "clubId": 1,
                          "eventId": 10,
                          "arrivalWindowStart": "2024-06-01T12:00:00Z",
                          "arrivalWindowEnd": "2024-06-01T14:00:00Z",
                          "limit": 20,
                          "name": "VIP"
                        }
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            coVerify(exactly = 0) { guestListService.createGuestList(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }
    }

    @Test
    fun `promoter with club scope can create guest list`() = runBlockingUnit {
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
        val created =
            GuestListInfo(
                id = 101,
                clubId = 1,
                eventId = 10,
                promoterId = 1L,
                ownerType = GuestListOwnerType.PROMOTER,
                ownerUserId = 1L,
                title = "VIP",
                capacity = 20,
                arrivalWindowStart = Instant.parse("2024-06-01T12:00:00Z"),
                arrivalWindowEnd = Instant.parse("2024-06-01T14:00:00Z"),
                status = GuestListStatus.ACTIVE,
                createdAt = Instant.parse("2024-06-01T10:00:00Z"),
                updatedAt = Instant.parse("2024-06-01T10:00:00Z"),
            )
        val stats =
            GuestListStats(
                added = 0,
                invited = 0,
                confirmed = 0,
                declined = 0,
                arrived = 0,
                noShow = 0,
            )

        coEvery {
            guestListService.createGuestList(
                promoterId = 1L,
                clubId = 1,
                eventId = 10,
                ownerType = GuestListOwnerType.PROMOTER,
                ownerUserId = 1L,
                arrivalWindowStart = any(),
                arrivalWindowEnd = any(),
                limit = 20,
                title = "VIP",
            )
        } returns GuestListServiceResult.Success(created)
        coEvery { guestListService.getStats(created.id, any()) } returns GuestListServiceResult.Success(stats)

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installRbac(roles = setOf(Role.PROMOTER), clubIds = setOf(1))
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
                client.post("/api/promoter/guest-lists") {
                    withInitData(initData)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "clubId": 1,
                          "eventId": 10,
                          "arrivalWindowStart": "2024-06-01T12:00:00Z",
                          "arrivalWindowEnd": "2024-06-01T14:00:00Z",
                          "limit": 20,
                          "name": "VIP"
                        }
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.Created, response.status)
            coVerify(exactly = 1) { guestListService.createGuestList(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        }
    }

    @Test
    fun `promoter cannot add entry to чужой list`() = runBlockingUnit {
        val guestListRepository = mockk<GuestListRepository>()
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
        val listId = 42L

        coEvery { guestListRepository.getList(listId) } returns
            GuestList(
                id = listId,
                clubId = 7,
                eventId = 9,
                ownerType = GuestListOwnerType.PROMOTER,
                ownerUserId = 999,
                title = " чужой ",
                capacity = 10,
                arrivalWindowStart = null,
                arrivalWindowEnd = null,
                status = GuestListStatus.ACTIVE,
                createdAt = Instant.parse("2024-06-01T12:00:00Z"),
            )

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installRbac(roles = setOf(Role.PROMOTER))
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
                client.post("/api/promoter/guest-lists/$listId/entries") {
                    withInitData(initData)
                    contentType(ContentType.Application.Json)
                    setBody("""{"displayName":"Guest"}""")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            val payload = response.bodyAsJson()
            assertEquals("forbidden", payload["code"]!!.jsonPrimitive.content)
            coVerify(exactly = 0) { guestListService.addEntrySingle(any(), any()) }
        }
    }

    @Test
    fun `promoter booking assignment is idempotent per entry`() = runBlockingUnit {
        val guestListRepository = mockk<GuestListRepository>()
        val guestListService = mockk<GuestListService>(relaxed = true)
        val guestListEntryRepository = mockk<GuestListEntryDbRepository>()
        val invitationService = mockk<InvitationService>(relaxed = true)
        val guestListDbRepository = mockk<GuestListDbRepository>(relaxed = true)
        val clubsRepository = mockk<ClubsRepository>(relaxed = true)
        val eventsRepository = mockk<EventsRepository>(relaxed = true)
        val adminHallsRepository = mockk<AdminHallsRepository>()
        val adminTablesRepository = mockk<AdminTablesRepository>()
        val bookingState = mockk<BookingState>()
        val promoterAssignments = mockk<PromoterBookingAssignmentsRepository>()
        val idempotencyKeySlot: CapturingSlot<String> = slot()

        val list =
            GuestList(
                id = 12,
                clubId = 1,
                eventId = 10,
                ownerType = GuestListOwnerType.PROMOTER,
                ownerUserId = 1,
                title = "VIP",
                capacity = 10,
                arrivalWindowStart = null,
                arrivalWindowEnd = null,
                status = GuestListStatus.ACTIVE,
                createdAt = Instant.parse("2024-06-01T12:00:00Z"),
            )
        val entry =
            GuestListEntryRecord(
                id = 50,
                guestListId = list.id,
                displayName = "Guest",
                fullName = "Guest",
                telegramUserId = null,
                status = com.example.bot.club.GuestListEntryStatus.ADDED,
                createdAt = Instant.parse("2024-06-01T12:00:00Z"),
                updatedAt = Instant.parse("2024-06-01T12:00:00Z"),
            )
        val hall =
            AdminHall(
                id = 7,
                clubId = 1,
                name = "Main",
                isActive = true,
                layoutRevision = 1,
                geometryFingerprint = "hash",
                createdAt = Instant.parse("2024-06-01T12:00:00Z"),
                updatedAt = Instant.parse("2024-06-01T12:00:00Z"),
            )
        val table =
            com.example.bot.layout.Table(
                id = 99,
                zoneId = "A",
                label = "A1",
                capacity = 2,
                minimumTier = "standard",
                status = com.example.bot.layout.TableStatus.FREE,
                minDeposit = 0,
                zone = "A",
                arrivalWindow = null,
                mysteryEligible = false,
                tableNumber = 1,
                x = 0.1,
                y = 0.2,
            )
        val bookingHold =
            Booking(
                id = 100,
                userId = 1,
                promoterId = 1,
                clubId = list.clubId,
                tableId = table.id,
                eventId = list.eventId,
                status = BookingStatus.HOLD,
                guestCount = 1,
                arrivalWindow = Instant.parse("2024-06-01T18:00:00Z") to Instant.parse("2024-06-01T19:00:00Z"),
                latePlusOneAllowedUntil = null,
                plusOneUsed = false,
                capacityAtHold = table.capacity,
                createdAt = Instant.parse("2024-06-01T12:00:00Z"),
                updatedAt = Instant.parse("2024-06-01T12:00:00Z"),
                holdExpiresAt = Instant.parse("2024-06-01T12:10:00Z"),
            )
        val bookingConfirmed = bookingHold.copy(status = BookingStatus.BOOKED)

        coEvery { guestListEntryRepository.findById(entry.id) } returns entry
        coEvery { guestListRepository.getList(entry.guestListId) } returns list
        coEvery { adminHallsRepository.getById(hall.id) } returns hall
        coEvery { adminTablesRepository.findByIdForHall(hall.id, table.id) } returns table
        coEvery { promoterAssignments.findBookingIdForEntry(entry.id) } returnsMany listOf(null, bookingConfirmed.id)
        coEvery { promoterAssignments.assignIfAbsent(entry.id, bookingConfirmed.id) } returns true
        coEvery {
            bookingState.hold(
                userId = 1,
                clubId = list.clubId,
                tableId = table.id,
                eventId = list.eventId,
                guestCount = 1,
                idempotencyKey = capture(idempotencyKeySlot),
                requestHash = any(),
                promoterId = 1,
            )
        } returns HoldResult.Success(bookingHold, bookingSnapshot(bookingHold), "{}", cached = false)
        coEvery {
            bookingState.confirm(
                userId = 1,
                clubId = list.clubId,
                bookingId = bookingHold.id,
                idempotencyKey = any(),
                requestHash = any(),
            )
        } returns ConfirmResult.Success(bookingConfirmed, bookingSnapshot(bookingConfirmed), "{}", cached = false)

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installRbac(roles = setOf(Role.PROMOTER), clubIds = setOf(1))
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
                client.post("/api/promoter/bookings/assign") {
                    withInitData(initData)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "guestListEntryId": 50,
                          "hallId": 7,
                          "tableId": 99,
                          "eventId": 10
                        }
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("promoter-assign:50", idempotencyKeySlot.captured)

            val secondResponse =
                client.post("/api/promoter/bookings/assign") {
                    withInitData(initData)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "guestListEntryId": 50,
                          "hallId": 7,
                          "tableId": 99,
                          "eventId": 10
                        }
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.Conflict, secondResponse.status)
            coVerify(exactly = 1) { bookingState.hold(any(), any(), any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 1) { bookingState.confirm(any(), any(), any(), any(), any()) }
            coVerify(exactly = 1) { promoterAssignments.assignIfAbsent(entry.id, bookingConfirmed.id) }
        }
    }

    @Test
    fun `resolve invitation returns card`() = runBlockingUnit {
        val service = StubInvitationService()

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                invitationRoutes(invitationService = service)
            }

            val response =
                client.post("/api/invitations/resolve") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"token":"token"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = response.bodyAsJson()
            assertEquals(99L, payload["invitationId"]!!.jsonPrimitive.long)
            assertEquals("Guest", payload["displayName"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `respond invitation without miniapp auth is forbidden`() = runBlockingUnit {
        val service = mockk<InvitationService>(relaxed = true)

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                invitationRoutes(invitationService = service, botTokenProvider = { "test" })
            }

            val response =
                client.post("/api/invitations/respond") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"token":"token","response":"CONFIRM"}""")
                }

            assertEquals(HttpStatusCode.Forbidden, response.status)
            val payload = response.bodyAsJson()
            assertEquals("invitation_forbidden", payload["code"]!!.jsonPrimitive.content)
            coVerify(exactly = 0) { service.respondToInvitation(any(), any(), any()) }
        }
    }

    @Test
    fun `respond invitation uses miniapp auth`() = runBlockingUnit {
        val service = mockk<InvitationService>()
        val card =
            InvitationCard(
                invitationId = 12,
                entryId = 3,
                guestListId = 2,
                clubId = 1,
                clubName = null,
                eventId = 4,
                arrivalWindowStart = Instant.parse("2024-06-01T12:00:00Z"),
                arrivalWindowEnd = Instant.parse("2024-06-01T14:00:00Z"),
                displayName = "Guest",
                entryStatus = com.example.bot.club.GuestListEntryStatus.INVITED,
                expiresAt = Instant.parse("2024-06-02T12:00:00Z"),
                revokedAt = null,
                usedAt = null,
            )
        coEvery { service.respondToInvitation("token", 100, InvitationResponse.CONFIRM) } returns
            InvitationServiceResult.Success(card)

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                invitationRoutes(invitationService = service, botTokenProvider = { TEST_BOT_TOKEN })
            }

            val initData = createInitData(userId = 100)
            val response =
                client.post("/api/invitations/respond") {
                    withInitData(initData)
                    contentType(ContentType.Application.Json)
                    setBody("""{"token":"token","response":"CONFIRM"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val payload = response.bodyAsJson()
            assertEquals(12L, payload["invitationId"]!!.jsonPrimitive.long)
            coVerify(exactly = 1) { service.respondToInvitation("token", 100, InvitationResponse.CONFIRM) }
        }
    }

    @Test
    fun `promoter invitation rejects entry list mismatch`() = runBlockingUnit {
        val guestListRepository = mockk<GuestListRepository>()
        val guestListService = mockk<GuestListService>(relaxed = true)
        val guestListEntryRepository = mockk<GuestListEntryDbRepository>()
        val invitationService = mockk<InvitationService>(relaxed = true)
        val guestListDbRepository = mockk<GuestListDbRepository>(relaxed = true)
        val clubsRepository = mockk<ClubsRepository>(relaxed = true)
        val eventsRepository = mockk<EventsRepository>(relaxed = true)
        val adminHallsRepository = mockk<AdminHallsRepository>(relaxed = true)
        val adminTablesRepository = mockk<AdminTablesRepository>(relaxed = true)
        val bookingState = mockk<BookingState>(relaxed = true)
        val promoterAssignments = mockk<PromoterBookingAssignmentsRepository>(relaxed = true)

        coEvery { guestListRepository.getList(12) } returns
            GuestList(
                id = 12,
                clubId = 1,
                eventId = 2,
                ownerType = GuestListOwnerType.PROMOTER,
                ownerUserId = 1,
                title = "VIP",
                capacity = 10,
                arrivalWindowStart = null,
                arrivalWindowEnd = null,
                status = GuestListStatus.ACTIVE,
                createdAt = Instant.parse("2024-06-01T12:00:00Z"),
            )
        coEvery { guestListEntryRepository.findById(50) } returns
            GuestListEntryRecord(
                id = 50,
                guestListId = 999,
                displayName = "Guest",
                fullName = "Guest",
                telegramUserId = null,
                status = com.example.bot.club.GuestListEntryStatus.ADDED,
                createdAt = Instant.parse("2024-06-01T12:00:00Z"),
                updatedAt = Instant.parse("2024-06-01T12:00:00Z"),
            )

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installRbac(roles = setOf(Role.PROMOTER), clubIds = setOf(1))
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
                client.post("/api/promoter/guest-lists/12/entries/50/invitation") {
                    withInitData(initData)
                    contentType(ContentType.Application.Json)
                    setBody("""{"channel":"TELEGRAM"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val payload = response.bodyAsJson()
            assertEquals("entry_list_mismatch", payload["code"]!!.jsonPrimitive.content)
            coVerify(exactly = 0) { invitationService.createInvitation(any(), any(), any()) }
        }
    }
}

private fun Application.installRbac(
    roles: Set<Role>,
    clubIds: Set<Long> = emptySet(),
) {
    installMiniAppAuthStatusPage()
    install(RbacPlugin) {
        userRepository = PromoterStubUserRepository()
        userRoleRepository = PromoterStubUserRoleRepository(roles, clubIds)
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

private class PromoterStubUserRepository : UserRepository {
    override suspend fun getByTelegramId(id: Long): User? = User(id = 1, telegramId = id, username = "tester")

    override suspend fun getById(id: Long): User? = User(id = id, telegramId = id, username = "tester")
}

private class PromoterStubUserRoleRepository(
    private val roles: Set<Role>,
    private val clubIds: Set<Long>,
) : UserRoleRepository {
    override suspend fun listRoles(userId: Long): Set<Role> = roles

    override suspend fun listClubIdsFor(userId: Long): Set<Long> = clubIds
}

private fun relaxedAuditRepository(): AuditLogRepository = mockk(relaxed = true)

private class StubInvitationService : InvitationService {
    override suspend fun createInvitation(
        entryId: Long,
        channel: InvitationChannel,
        createdBy: Long,
    ): InvitationServiceResult<com.example.bot.club.InvitationCreateResult> =
        InvitationServiceResult.Failure(InvitationServiceError.INVITATION_INVALID)

    override suspend fun resolveInvitation(rawToken: String): InvitationServiceResult<InvitationCard> =
        InvitationServiceResult.Success(
            InvitationCard(
                invitationId = 99,
                entryId = 7,
                guestListId = 5,
                clubId = 1,
                clubName = null,
                eventId = 10,
                arrivalWindowStart = Instant.parse("2024-06-01T12:00:00Z"),
                arrivalWindowEnd = Instant.parse("2024-06-01T14:00:00Z"),
                displayName = "Guest",
                entryStatus = com.example.bot.club.GuestListEntryStatus.INVITED,
                expiresAt = Instant.parse("2024-06-02T12:00:00Z"),
                revokedAt = null,
                usedAt = null,
            ),
        )

    override suspend fun respondToInvitation(
        rawToken: String,
        telegramUserId: Long,
        response: InvitationResponse,
    ): InvitationServiceResult<InvitationCard> =
        InvitationServiceResult.Failure(InvitationServiceError.INVITATION_INVALID)
}

private fun bookingSnapshot(booking: Booking): BookingResponseSnapshot {
    val arrival = listOf(booking.arrivalWindow.first.toString(), booking.arrivalWindow.second.toString())
    return BookingResponseSnapshot(
        booking =
            BookingView(
                id = booking.id,
                clubId = booking.clubId,
                tableId = booking.tableId,
                eventId = booking.eventId,
                status = booking.status.name,
                guestCount = booking.guestCount,
                arrivalWindow = arrival,
                latePlusOneAllowedUntil = booking.latePlusOneAllowedUntil?.toString(),
                plusOneUsed = booking.plusOneUsed,
                capacityAtHold = booking.capacityAtHold,
                createdAt = booking.createdAt.toString(),
                updatedAt = booking.updatedAt.toString(),
            ),
        latePlusOneAllowedUntil = booking.latePlusOneAllowedUntil?.toString(),
        arrivalWindow = arrival,
        userId = booking.userId,
        promoterId = booking.promoterId,
    )
}

private suspend fun io.ktor.client.statement.HttpResponse.bodyAsJson(): JsonObject =
    Json.parseToJsonElement(bodyAsText()).jsonObject

private fun runBlockingUnit(block: suspend () -> Unit) = runBlocking { block() }
