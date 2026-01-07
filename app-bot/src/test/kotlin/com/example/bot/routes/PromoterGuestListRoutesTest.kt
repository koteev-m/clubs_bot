package com.example.bot.routes

import com.example.bot.club.GuestListOwnerType
import com.example.bot.club.GuestList
import com.example.bot.club.GuestListStatus
import com.example.bot.club.GuestListService
import com.example.bot.club.InvitationCard
import com.example.bot.club.InvitationChannel
import com.example.bot.club.InvitationResponse
import com.example.bot.club.InvitationService
import com.example.bot.club.InvitationServiceError
import com.example.bot.club.InvitationServiceResult
import com.example.bot.club.GuestListRepository
import com.example.bot.data.booking.core.AuditLogRepository
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
import com.example.bot.security.auth.TelegramPrincipal
import com.example.bot.security.auth.InitDataValidator
import com.example.bot.security.rbac.RbacPlugin
import com.example.bot.testing.createInitData
import com.example.bot.testing.withInitData
import com.example.bot.webapp.TEST_BOT_TOKEN
import io.ktor.client.request.header
import io.ktor.client.request.parameter
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
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installRbac(roles = emptySet())
                promoterGuestListRoutes(
                    guestListRepository = guestListRepository,
                    guestListService = guestListService,
                    guestListEntryRepository = guestListEntryRepository,
                    invitationService = invitationService,
                    botTokenProvider = { TEST_BOT_TOKEN },
                )
            }

            val initData = createInitData(userId = 100)
            val response =
                client.post("/api/promoter/guest-lists") {
                    parameter("initData", initData)
                    header("X-Telegram-Init-Data", initData)
                    header("X-Telegram-InitData", initData)
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
    fun `promoter cannot add entry to чужой list`() = runBlockingUnit {
        val guestListRepository = mockk<GuestListRepository>()
        val guestListService = mockk<GuestListService>(relaxed = true)
        val guestListEntryRepository = mockk<GuestListEntryDbRepository>(relaxed = true)
        val invitationService = mockk<InvitationService>(relaxed = true)
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
                    botTokenProvider = { TEST_BOT_TOKEN },
                )
            }

            val initData = createInitData(userId = 100)
            val response =
                client.post("/api/promoter/guest-lists/$listId/entries") {
                    parameter("initData", initData)
                    header("X-Telegram-Init-Data", initData)
                    header("X-Telegram-InitData", initData)
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
                    parameter("initData", initData)
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
                installRbac(roles = setOf(Role.PROMOTER))
                promoterGuestListRoutes(
                    guestListRepository = guestListRepository,
                    guestListService = guestListService,
                    guestListEntryRepository = guestListEntryRepository,
                    invitationService = invitationService,
                    botTokenProvider = { TEST_BOT_TOKEN },
                )
            }

            val initData = createInitData(userId = 100)
            val response =
                client.post("/api/promoter/guest-lists/12/entries/50/invitation") {
                    parameter("initData", initData)
                    header("X-Telegram-Init-Data", initData)
                    header("X-Telegram-InitData", initData)
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

private fun Application.installRbac(roles: Set<Role>) {
    installMiniAppAuthStatusPage()
    install(RbacPlugin) {
        userRepository = PromoterStubUserRepository()
        userRoleRepository = PromoterStubUserRoleRepository(roles)
        auditLogRepository = relaxedAuditRepository()
        principalExtractor = { call ->
            if (call.attributes.contains(MiniAppUserKey)) {
                val principal = call.attributes[MiniAppUserKey]
                TelegramPrincipal(principal.id, principal.username)
            } else {
                val initData =
                    call.request.header("X-Telegram-InitData")
                        ?: call.request.header("X-Telegram-Init-Data")
                        ?: call.request.queryParameters["initData"]
                val initUser = initData?.let { InitDataValidator.validate(it, TEST_BOT_TOKEN) }
                if (initUser != null) {
                    TelegramPrincipal(initUser.id, initUser.username)
                } else {
                    call.request.header("X-Telegram-Id")?.toLongOrNull()?.let { id ->
                        TelegramPrincipal(id, call.request.header("X-Telegram-Username"))
                    }
                }
            }
        }
    }
}

private class PromoterStubUserRepository : UserRepository {
    override suspend fun getByTelegramId(id: Long): User? = User(id = 1, telegramId = id, username = "tester")
}

private class PromoterStubUserRoleRepository(
    private val roles: Set<Role>,
) : UserRoleRepository {
    override suspend fun listRoles(userId: Long): Set<Role> = roles

    override suspend fun listClubIdsFor(userId: Long): Set<Long> = emptySet()
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

private suspend fun io.ktor.client.statement.HttpResponse.bodyAsJson(): JsonObject =
    Json.parseToJsonElement(bodyAsText()).jsonObject

private fun runBlockingUnit(block: suspend () -> Unit) = runBlocking { block() }
