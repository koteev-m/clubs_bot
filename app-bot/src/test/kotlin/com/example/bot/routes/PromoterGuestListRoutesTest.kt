package com.example.bot.routes

import com.example.bot.club.GuestListOwnerType
import com.example.bot.club.GuestListService
import com.example.bot.club.InvitationCard
import com.example.bot.club.InvitationChannel
import com.example.bot.club.InvitationResponse
import com.example.bot.club.InvitationService
import com.example.bot.club.InvitationServiceError
import com.example.bot.club.InvitationServiceResult
import com.example.bot.data.booking.core.AuditLogRepository
import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.plugins.MiniAppUserKey
import com.example.bot.plugins.TelegramMiniUser
import com.example.bot.plugins.overrideMiniAppValidatorForTesting
import com.example.bot.plugins.resetMiniAppValidator
import com.example.bot.security.auth.TelegramPrincipal
import com.example.bot.security.rbac.RbacPlugin
import io.ktor.client.request.header
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
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class PromoterGuestListRoutesTest {
    @Before
    fun setUp() {
        overrideMiniAppValidatorForTesting { _, _ -> TelegramMiniUser(id = 100) }
    }

    @After
    fun tearDown() {
        resetMiniAppValidator()
    }

    @Test
    fun `promoter guest lists require promoter role`() = runBlockingUnit {
        val guestListService = mockk<GuestListService>(relaxed = true)
        val invitationService = mockk<InvitationService>(relaxed = true)

        testApplication {
            application {
                install(ContentNegotiation) { json() }
                installRbac(roles = emptySet())
                promoterGuestListRoutes(
                    guestListService = guestListService,
                    invitationService = invitationService,
                    botTokenProvider = { "test" },
                )
            }

            val response =
                client.post("/api/promoter/guest-lists") {
                    header("X-Telegram-Init-Data", "init")
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
}

private fun Application.installRbac(roles: Set<Role>) {
    install(RbacPlugin) {
        userRepository = StubUserRepository()
        userRoleRepository = StubUserRoleRepository(roles)
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

private class StubUserRepository : UserRepository {
    override suspend fun getByTelegramId(id: Long): User? = User(id = 1, telegramId = id, username = "tester")
}

private class StubUserRoleRepository(
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
