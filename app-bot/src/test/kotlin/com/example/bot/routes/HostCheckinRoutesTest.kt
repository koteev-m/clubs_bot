package com.example.bot.routes

import com.example.bot.checkin.CheckinResult
import com.example.bot.checkin.CheckinService
import com.example.bot.checkin.CheckinServiceResult
import com.example.bot.club.CheckinResultStatus
import com.example.bot.club.CheckinSubjectType
import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.http.ErrorCodes
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import io.mockk.coEvery
import io.mockk.mockk

class HostCheckinRoutesTest {
    private val telegramId = 42L

    @Before
    fun setUp() {
        System.setProperty("TELEGRAM_BOT_TOKEN", "test")
        overrideMiniAppValidatorForTesting { _, _ -> TelegramMiniUser(id = telegramId) }
    }

    @After
    fun tearDown() {
        resetMiniAppValidator()
    }

    @Test
    fun `rbac forbids non entry roles`() = withHostApp(roles = setOf(Role.PROMOTER)) {
        val response =
            client.post("/api/host/checkin/scan") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody("""{"payload":"token"}""")
            }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals(ErrorCodes.forbidden, response.errorCode())
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
                header("X-Telegram-Init-Data", "init")
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
    }

    private fun withHostApp(
        roles: Set<Role> = setOf(Role.ENTRY_MANAGER),
        block: suspend ApplicationTestBuilder.(CheckinService) -> Unit,
    ) {
        testApplication {
            val checkinService = mockk<CheckinService>(relaxed = true)
            application {
                install(ContentNegotiation) { json() }
                install(RbacPlugin) {
                    userRepository = StubUserRepository()
                    userRoleRepository = StubUserRoleRepository(roles)
                    auditLogRepository = relaxedAuditRepository()
                    principalExtractor = { TelegramPrincipal(telegramId, "tester") }
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

    private fun JsonObject.errorCodeOrNull(): String? =
        this["code"]?.jsonPrimitive?.content
            ?: this["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content
            ?: this["error"]?.jsonPrimitive?.content
}
