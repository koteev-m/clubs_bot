package com.example.bot.routes

import com.example.bot.checkin.CheckinService
import com.example.bot.checkin.CheckinServiceError
import com.example.bot.checkin.CheckinServiceResult
import com.example.bot.checkin.HostCheckinOutcome
import com.example.bot.checkin.HostCheckinSubject
import com.example.bot.checkin.HostCheckinSubjectKind
import com.example.bot.club.CheckinResultStatus
import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.host.HostSearchItem
import com.example.bot.host.HostSearchKind
import com.example.bot.host.HostSearchService
import com.example.bot.http.ErrorCodes
import com.example.bot.http.MINI_APP_VARY_HEADER
import com.example.bot.http.NO_STORE_CACHE_CONTROL
import com.example.bot.plugins.DEFAULT_CHECKIN_MAX_BYTES
import com.example.bot.plugins.MiniAppUserKey
import com.example.bot.plugins.TelegramMiniUser
import com.example.bot.plugins.installJsonErrorPages
import com.example.bot.plugins.overrideMiniAppValidatorForTesting
import com.example.bot.plugins.resetMiniAppValidator
import com.example.bot.security.auth.TelegramPrincipal
import com.example.bot.security.rbac.RbacPlugin
import com.example.bot.testing.createInitData
import com.example.bot.testing.withInitData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HostCheckinRoutesTest {
    private val telegramId = 42L
    private val userId = 1L
    private val initData = createInitData(userId = telegramId)

    @BeforeEach
    fun setUp() {
        overrideMiniAppValidatorForTesting { _, _ -> TelegramMiniUser(id = telegramId) }
    }

    @AfterEach
    fun tearDown() {
        resetMiniAppValidator()
    }

    @Test
    fun `rbac forbids non entry roles`() = withHostApp(roles = setOf(Role.PROMOTER)) { checkinService, _ ->
        val checkinResponse =
            client.post("/api/host/checkin") {
                withInitData(initData)
                contentType(ContentType.Application.Json)
                setBody("""{"clubId":1,"eventId":2,"guestListEntryId":3}""")
            }

        assertEquals(HttpStatusCode.Forbidden, checkinResponse.status)
        assertEquals(ErrorCodes.forbidden, checkinResponse.errorCode())
        assertMiniAppNoStoreHeaders(checkinResponse)
        coVerify(exactly = 0) { checkinService.hostCheckin(any(), any()) }

        val scanResponse =
            client.post("/api/host/checkin/scan") {
                withInitData(initData)
                contentType(ContentType.Application.Json)
                setBody("""{"clubId":1,"eventId":2,"qrPayload":"inv:token"}""")
            }

        assertEquals(HttpStatusCode.Forbidden, scanResponse.status)
        assertEquals(ErrorCodes.forbidden, scanResponse.errorCode())
        assertMiniAppNoStoreHeaders(scanResponse)
        coVerify(exactly = 0) { checkinService.hostScan(any(), any(), any(), any()) }

        val searchResponse =
            client.get("/api/host/checkin/search?clubId=1&eventId=2&query=Al") {
                withInitData(initData)
            }

        assertEquals(HttpStatusCode.Forbidden, searchResponse.status)
        assertEquals(ErrorCodes.forbidden, searchResponse.errorCode())
        assertMiniAppNoStoreHeaders(searchResponse)
    }

    @Test
    fun `checkin returns outcome payload`() = withHostApp() { checkinService, _ ->
        val occurredAt = Instant.parse("2024-06-10T10:15:30Z")
        val result =
            HostCheckinOutcome(
                outcomeStatus = CheckinResultStatus.ARRIVED,
                subject = HostCheckinSubject(kind = HostCheckinSubjectKind.GUEST_LIST_ENTRY, guestListEntryId = 123),
                entryStatus = com.example.bot.club.GuestListEntryStatus.ARRIVED,
                occurredAt = occurredAt,
            )
        coEvery { checkinService.hostCheckin(any(), any()) } returns CheckinServiceResult.Success(result)

        val response =
            client.post("/api/host/checkin") {
                withInitData(initData)
                contentType(ContentType.Application.Json)
                setBody("""{"clubId":1,"eventId":2,"guestListEntryId":123}""")
            }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsJson()
        assertEquals("ARRIVED", body["outcomeStatus"]!!.jsonPrimitive.content)
        val subject = body["subject"]!!.jsonObject
        assertEquals("GUEST_LIST_ENTRY", subject["kind"]!!.jsonPrimitive.content)
        assertEquals(123L, subject["guestListEntryId"]!!.jsonPrimitive.long)
        assertEquals("ARRIVED", body["entryStatus"]!!.jsonPrimitive.content)
        assertEquals(occurredAt.toString(), body["occurredAt"]!!.jsonPrimitive.content)
        assertMiniAppNoStoreHeaders(response)
    }

    @Test
    fun `scan returns outcome payload`() = withHostApp() { checkinService, _ ->
        val result =
            HostCheckinOutcome(
                outcomeStatus = CheckinResultStatus.LATE,
                subject = HostCheckinSubject(kind = HostCheckinSubjectKind.INVITATION, invitationId = 9, guestListEntryId = 10),
                entryStatus = com.example.bot.club.GuestListEntryStatus.LATE,
            )
        coEvery { checkinService.hostScan(any(), any(), any(), any()) } returns CheckinServiceResult.Success(result)

        val response =
            client.post("/api/host/checkin/scan") {
                withInitData(initData)
                contentType(ContentType.Application.Json)
                setBody("""{"clubId":1,"eventId":2,"qrPayload":"inv:token"}""")
            }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsJson()
        assertEquals("LATE", body["outcomeStatus"]!!.jsonPrimitive.content)
        val subject = body["subject"]!!.jsonObject
        assertEquals("INVITATION", subject["kind"]!!.jsonPrimitive.content)
        assertEquals(9L, subject["invitationId"]!!.jsonPrimitive.long)
        assertEquals(10L, subject["guestListEntryId"]!!.jsonPrimitive.long)
        assertMiniAppNoStoreHeaders(response)
    }

    @Test
    fun `search returns items`() = withHostApp() { _, hostSearchService ->
        val results =
            listOf(
                HostSearchItem(
                    kind = HostSearchKind.GUEST_LIST_ENTRY,
                    displayName = "Alice",
                    guestListEntryId = 10,
                    status = "ARRIVED",
                    guestCount = 2,
                    arrived = true,
                ),
            )
        coEvery { hostSearchService.search(1, 2, "Al", any()) } returns results

        val response =
            client.get("/api/host/checkin/search?clubId=1&eventId=2&query=Al") {
                withInitData(initData)
            }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("Alice"))
        assertMiniAppNoStoreHeaders(response)
    }

    @Test
    fun `host routes forbid club scope mismatch`() = withHostApp() { checkinService, hostSearchService ->
        val checkinResponse =
            client.post("/api/host/checkin") {
                withInitData(initData)
                contentType(ContentType.Application.Json)
                setBody("""{"clubId":2,"eventId":2,"guestListEntryId":3}""")
            }

        assertEquals(HttpStatusCode.Forbidden, checkinResponse.status)
        assertEquals(ErrorCodes.forbidden, checkinResponse.errorCode())
        coVerify(exactly = 0) { checkinService.hostCheckin(any(), any()) }

        val scanResponse =
            client.post("/api/host/checkin/scan") {
                withInitData(initData)
                contentType(ContentType.Application.Json)
                setBody("""{"clubId":2,"eventId":2,"qrPayload":"inv:token"}""")
            }

        assertEquals(HttpStatusCode.Forbidden, scanResponse.status)
        assertEquals(ErrorCodes.forbidden, scanResponse.errorCode())
        coVerify(exactly = 0) { checkinService.hostScan(any(), any(), any(), any()) }

        val searchResponse =
            client.get("/api/host/checkin/search?clubId=2&eventId=2&query=Al") {
                withInitData(initData)
            }

        assertEquals(HttpStatusCode.Forbidden, searchResponse.status)
        assertEquals(ErrorCodes.forbidden, searchResponse.errorCode())
        coVerify(exactly = 0) { hostSearchService.search(any(), any(), any(), any()) }
    }

    private fun withHostApp(
        roles: Set<Role> = setOf(Role.ENTRY_MANAGER),
        maxBodyBytes: Long = DEFAULT_CHECKIN_MAX_BYTES,
        block: suspend ApplicationTestBuilder.(CheckinService, HostSearchService) -> Unit,
    ) {
        testApplication {
            val checkinService = mockk<CheckinService>(relaxed = true)
            val hostSearchService = mockk<HostSearchService>(relaxed = true)
            application {
                install(ContentNegotiation) { json() }
                installJsonErrorPages()
                install(RbacPlugin) {
                    userRepository = StubUserRepository(userId)
                    userRoleRepository = StubUserRoleRepository(roles)
                    auditLogRepository = relaxedAuditRepository()
                    principalExtractor = { call ->
                        if (call.attributes.contains(MiniAppUserKey)) {
                            val principal = call.attributes[MiniAppUserKey]
                            TelegramPrincipal(principal.id, principal.username)
                        } else {
                            null
                        }
                    }
                }

                hostCheckinRoutes(
                    checkinService = checkinService,
                    hostSearchService = hostSearchService,
                    botTokenProvider = { "test" },
                    maxBodyBytes = maxBodyBytes,
                )
            }
            block(this, checkinService, hostSearchService)
        }
    }

    private class StubUserRepository(private val userId: Long) : UserRepository {
        override suspend fun getByTelegramId(id: Long): User? = User(id = userId, telegramId = id, username = "tester")

        override suspend fun getById(id: Long): User? = User(id = userId, telegramId = id, username = "tester")
    }

    private class StubUserRoleRepository(private val roles: Set<Role>) : UserRoleRepository {
        override suspend fun listRoles(userId: Long): Set<Role> = roles

        override suspend fun listClubIdsFor(userId: Long): Set<Long> = setOf(1)
    }

    private fun relaxedAuditRepository() = io.mockk.mockk<com.example.bot.data.booking.core.AuditLogRepository>(
        relaxed = true,
    )

    private suspend fun io.ktor.client.statement.HttpResponse.bodyAsJson(): JsonObject =
        Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun io.ktor.client.statement.HttpResponse.errorCode(): String {
        val raw = bodyAsText()
        val parsed = runCatching { Json.parseToJsonElement(raw).jsonObject.errorCodeOrNull() }.getOrNull()
        val extracted = Regex("\\\"error\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
            .find(raw)
            ?.groupValues
            ?.getOrNull(1)
        return parsed ?: extracted ?: raw
    }

    private fun JsonObject.errorCodeOrNull(): String? =
        this["code"]?.jsonPrimitive?.content

    private fun assertMiniAppNoStoreHeaders(response: io.ktor.client.statement.HttpResponse) {
        assertEquals(NO_STORE_CACHE_CONTROL, response.headers[HttpHeaders.CacheControl])
        assertEquals(MINI_APP_VARY_HEADER, response.headers[HttpHeaders.Vary])
    }
}
