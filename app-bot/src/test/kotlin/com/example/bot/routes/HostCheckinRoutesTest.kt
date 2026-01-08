package com.example.bot.routes

import com.example.bot.checkin.CheckinResult
import com.example.bot.checkin.CheckinService
import com.example.bot.checkin.CheckinServiceError
import com.example.bot.checkin.CheckinServiceResult
import com.example.bot.club.CheckinResultStatus
import com.example.bot.club.CheckinSubjectType
import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.http.ErrorCodes
import com.example.bot.http.MINI_APP_VARY_HEADER
import com.example.bot.http.NO_STORE_CACHE_CONTROL
import com.example.bot.plugins.MiniAppUserKey
import com.example.bot.plugins.TelegramMiniUser
import com.example.bot.plugins.installMiniAppAuthStatusPage
import com.example.bot.plugins.overrideMiniAppValidatorForTesting
import com.example.bot.plugins.resetMiniAppValidator
import com.example.bot.security.auth.TelegramPrincipal
import com.example.bot.security.rbac.RbacPlugin
import com.example.bot.testing.createInitData
import com.example.bot.testing.withInitData
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
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HostCheckinRoutesTest {
    private val telegramId = 42L
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
    fun `rbac forbids non entry roles`() = withHostApp(roles = setOf(Role.PROMOTER)) { service ->
        val response =
            client.post("/api/host/checkin/scan") {
                withInitData(initData)
                contentType(ContentType.Application.Json)
                setBody("""{"payload":"token"}""")
            }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals(ErrorCodes.forbidden, response.errorCode())
        assertMiniAppNoStoreHeaders(response)
        assertServiceNotCalledScan(service)
    }

    @Test
    fun `missing initData returns unauthorized`() = withHostApp { service ->
        val response =
            client.post("/api/host/checkin/scan") {
                contentType(ContentType.Application.Json)
                setBody("""{"payload":"token"}""")
            }

        assertMiniAppUnauthorized(response, expectedError = "initData missing")
        assertServiceNotCalledScan(service)
    }

    @Test
    fun `initData in body is ignored`() = withHostApp { service ->
        val response =
            client.post("/api/host/checkin/scan") {
                contentType(ContentType.Application.Json)
                setBody("""{"initData":"body-token","payload":"token"}""")
            }

        assertMiniAppUnauthorized(response, expectedError = "initData missing")
        assertServiceNotCalledScan(service)
    }

    @Test
    fun `manual without initData returns unauthorized`() = withHostApp { service ->
        val response =
            client.post("/api/host/checkin/manual") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "subjectType": "guest_list_entry",
                      "subjectId": "123",
                      "status": "approved"
                    }
                    """.trimIndent()
                )
            }

        assertMiniAppUnauthorized(response, expectedError = "initData missing")
        assertServiceNotCalledManual(service)
    }

    @Test
    fun `scan returns success payload`() = withHostApp() { service ->
        val occurredAt = Instant.parse("2024-06-10T10:15:30Z")
        val result =
            CheckinResult.Success(
                subjectType = CheckinSubjectType.GUEST_LIST_ENTRY,
                subjectId = "123",
                resultStatus = CheckinResultStatus.ARRIVED,
                displayName = "Alice",
                occurredAt = occurredAt,
                checkedBy = 99L,
            )
        coEvery { service.scanQr(any(), any()) } returns CheckinServiceResult.Success(result)

        val response =
            client.post("/api/host/checkin/scan") {
                withInitData(initData)
                contentType(ContentType.Application.Json)
                setBody("""{"payload":"token"}""")
            }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsJson()
        assertEquals("SUCCESS", body["type"]!!.jsonPrimitive.content)
        assertEquals("GUEST_LIST_ENTRY", body["subjectType"]!!.jsonPrimitive.content)
        assertEquals("123", body["subjectId"]!!.jsonPrimitive.content)
        assertEquals("ARRIVED", body["resultStatus"]!!.jsonPrimitive.content)
        assertEquals("Alice", body["displayName"]!!.jsonPrimitive.content)
        assertEquals(99L, body["checkedBy"]!!.jsonPrimitive.long)
        assertEquals(occurredAt.toString(), body["occurredAt"]!!.jsonPrimitive.content)
        assertMiniAppNoStoreHeaders(response)
    }

    @Test
    fun `scan with wrong content type returns unsupported_media_type`() = withHostApp() { service ->
        val response =
            client.post("/api/host/checkin/scan") {
                withInitData(initData)
                contentType(ContentType.Text.Plain)
                setBody("payload")
            }

        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
        assertEquals(ErrorCodes.unsupported_media_type, response.errorCode())
        assertMiniAppNoStoreHeaders(response)
        assertServiceNotCalledScan(service)
    }

    @Test
    fun `manual denied without reason returns error`() = withHostApp() { service ->
        coEvery { service.manualCheckin(any(), any(), any(), any(), any()) } returns
            CheckinServiceResult.Failure(CheckinServiceError.CHECKIN_DENY_REASON_REQUIRED)

        val response =
            client.post("/api/host/checkin/manual") {
                withInitData(initData)
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "subjectType": "guest_list_entry",
                      "subjectId": "123",
                      "status": "denied",
                      "denyReason": ""
                    }
                    """.trimIndent()
                )
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(ErrorCodes.checkin_deny_reason_required, response.errorCode())
        assertMiniAppNoStoreHeaders(response)
    }

    private fun withHostApp(
        roles: Set<Role> = setOf(Role.ENTRY_MANAGER),
        block: suspend ApplicationTestBuilder.(CheckinService) -> Unit,
    ) {
        testApplication {
            val checkinService = mockk<CheckinService>(relaxed = true)
            application {
                install(ContentNegotiation) { json() }
                installMiniAppAuthStatusPage()
                install(RbacPlugin) {
                    userRepository = StubUserRepository()
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

                hostCheckinRoutes(checkinService = checkinService, botTokenProvider = { "test" })
            }
            block(this, checkinService)
        }
    }

    private class StubUserRepository : UserRepository {
        override suspend fun getByTelegramId(id: Long): User? = User(id = 1, telegramId = id, username = "tester")
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

    private suspend fun assertMiniAppUnauthorized(
        response: io.ktor.client.statement.HttpResponse,
        expectedError: String,
    ) {
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(expectedError, response.errorCode())
        assertMiniAppNoStoreHeaders(response)
    }

    private fun assertMiniAppNoStoreHeaders(response: io.ktor.client.statement.HttpResponse) {
        assertTrue(response.headers[HttpHeaders.CacheControl]?.contains(NO_STORE_CACHE_CONTROL, ignoreCase = true) == true)
        assertTrue(response.headers[HttpHeaders.Vary]?.contains(MINI_APP_VARY_HEADER, ignoreCase = true) == true)
    }

    private fun assertServiceNotCalledScan(service: CheckinService) {
        coVerify(exactly = 0) { service.scanQr(any(), any()) }
    }

    private fun assertServiceNotCalledManual(service: CheckinService) {
        coVerify(exactly = 0) { service.manualCheckin(any(), any(), any(), any(), any()) }
    }

    private fun JsonObject.errorCodeOrNull(): String? =
        this["code"]?.jsonPrimitive?.content
            ?: this["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content
            ?: this["error"]?.jsonPrimitive?.content
}
