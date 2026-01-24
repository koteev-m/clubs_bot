package com.example.bot.routes

import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.http.ErrorCodes
import com.example.bot.opschat.ClubOpsChatConfig
import com.example.bot.opschat.ClubOpsChatConfigRepository
import com.example.bot.opschat.ClubOpsChatConfigUpsert
import com.example.bot.plugins.TelegramMiniUser
import com.example.bot.plugins.overrideMiniAppValidatorForTesting
import com.example.bot.plugins.resetMiniAppValidator
import com.example.bot.security.auth.TelegramPrincipal
import com.example.bot.security.rbac.RbacPlugin
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
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
import io.mockk.mockk
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AdminOpsChatsRoutesTest {
    private val now = Instant.parse("2024-06-01T12:00:00Z")
    private val telegramId = 99L

    @BeforeEach
    fun setUp() {
        System.setProperty("TELEGRAM_BOT_TOKEN", "test")
        overrideMiniAppValidatorForTesting { _, _ -> TelegramMiniUser(id = telegramId) }
    }

    @AfterEach
    fun tearDown() {
        resetMiniAppValidator()
    }

    @Test
    fun `get requires clubId`() = withApp { _ ->
        val response = client.get("/api/admin/ops-chats") { header("X-Telegram-Init-Data", "init") }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(ErrorCodes.validation_error, response.errorCode())
        response.assertNoStoreHeaders()
    }

    @Test
    fun `get returns null when config missing`() = withApp { _ ->
        val response = client.get("/api/admin/ops-chats?clubId=1") { header("X-Telegram-Init-Data", "init") }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsJson()
        assertEquals(JsonNull, body["config"])
        response.assertNoStoreHeaders()
    }

    @Test
    fun `put validates payload`() = withApp { _ ->
        val invalidJson = client.put("/api/admin/ops-chats") { header("X-Telegram-Init-Data", "init") }
        assertEquals(HttpStatusCode.BadRequest, invalidJson.status)
        assertEquals(ErrorCodes.invalid_json, invalidJson.errorCode())
        invalidJson.assertNoStoreHeaders()

        val invalidFields =
            client.put("/api/admin/ops-chats") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                        "clubId":1,
                        "chatId":0,
                        "bookingsThreadId":0,
                        "checkinThreadId":null,
                        "guestListsThreadId":null,
                        "supportThreadId":null,
                        "alertsThreadId":null
                    }""",
                )
            }
        assertEquals(HttpStatusCode.BadRequest, invalidFields.status)
        assertEquals(ErrorCodes.validation_error, invalidFields.errorCode())
        invalidFields.assertNoStoreHeaders()
    }

    @Test
    fun `get rejects non admins`() = withApp(roles = setOf(Role.PROMOTER)) { _ ->
        val response =
            client.get("/api/admin/ops-chats?clubId=1") { header("X-Telegram-Init-Data", "init") }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `get rejects club scope mismatch`() = withApp(clubIds = setOf(2)) { _ ->
        val response =
            client.get("/api/admin/ops-chats?clubId=1") { header("X-Telegram-Init-Data", "init") }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals(ErrorCodes.forbidden, response.errorCode())
        response.assertNoStoreHeaders()
    }

    @Test
    fun `put rejects club scope mismatch`() = withApp(clubIds = setOf(2)) { _ ->
        val response =
            client.put("/api/admin/ops-chats") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                        "clubId":1,
                        "chatId":-100,
                        "bookingsThreadId":10,
                        "checkinThreadId":11,
                        "guestListsThreadId":12,
                        "supportThreadId":null,
                        "alertsThreadId":null
                    }""",
                )
            }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals(ErrorCodes.forbidden, response.errorCode())
        response.assertNoStoreHeaders()
    }

    @Test
    fun `put and get return config`() = withApp { repo ->
        val response =
            client.put("/api/admin/ops-chats") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                        "clubId":1,
                        "chatId":-100,
                        "bookingsThreadId":10,
                        "checkinThreadId":11,
                        "guestListsThreadId":12,
                        "supportThreadId":null,
                        "alertsThreadId":null
                    }""",
                )
            }

        assertEquals(HttpStatusCode.OK, response.status)
        val persisted = repo.getByClubId(1)!!
        assertEquals(-100L, persisted.chatId)
        assertEquals(10, persisted.bookingsThreadId)
        assertEquals(11, persisted.checkinThreadId)
        assertEquals(12, persisted.guestListsThreadId)
        assertEquals(null, persisted.supportThreadId)
        assertEquals(null, persisted.alertsThreadId)
        response.assertNoStoreHeaders()

        val getResponse =
            client.get("/api/admin/ops-chats?clubId=1") { header("X-Telegram-Init-Data", "init") }
        assertEquals(HttpStatusCode.OK, getResponse.status)
        val config = getResponse.bodyAsJson()["config"]!!.jsonObject
        assertEquals(1L, config["clubId"]!!.jsonPrimitive.long)
        assertEquals(-100L, config["chatId"]!!.jsonPrimitive.long)
        assertEquals(10, config["bookingsThreadId"]!!.jsonPrimitive.long)
        assertEquals(11, config["checkinThreadId"]!!.jsonPrimitive.long)
        assertEquals(12, config["guestListsThreadId"]!!.jsonPrimitive.long)
        assertTrue(config["supportThreadId"] is JsonNull)
        assertTrue(config["alertsThreadId"] is JsonNull)
        getResponse.assertNoStoreHeaders()
    }

    private fun withApp(
        roles: Set<Role> = setOf(Role.CLUB_ADMIN),
        clubIds: Set<Long> = setOf(1),
        block: suspend ApplicationTestBuilder.(InMemoryOpsChatConfigRepository) -> Unit,
    ) {
        val repo = InMemoryOpsChatConfigRepository(now)
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                install(RbacPlugin) {
                    userRepository = StubUserRepository()
                    userRoleRepository = StubUserRoleRepository(roles, clubIds)
                    auditLogRepository = relaxedAuditRepository()
                    principalExtractor = { TelegramPrincipal(telegramId, "tester") }
                }
                adminOpsChatsRoutes(repository = repo, botTokenProvider = { "test" })
            }
            block(this, repo)
        }
    }

    private fun relaxedAuditRepository() = mockk<com.example.bot.data.booking.core.AuditLogRepository>(relaxed = true)

    private fun JsonObject.errorCodeOrNull(): String? =
        this["code"]?.jsonPrimitive?.content
            ?: this["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content

    private suspend fun io.ktor.client.statement.HttpResponse.errorCode(): String =
        runCatching { Json.parseToJsonElement(bodyAsText()).jsonObject.errorCodeOrNull() }.getOrNull().orEmpty()

    private suspend fun io.ktor.client.statement.HttpResponse.bodyAsJson(): JsonObject =
        Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun io.ktor.client.statement.HttpResponse.assertNoStoreHeaders() {
        assertEquals(NO_STORE, headers[HttpHeaders.CacheControl])
        assertEquals("X-Telegram-Init-Data", headers[HttpHeaders.Vary])
    }

    private class StubUserRepository : UserRepository {
        override suspend fun getByTelegramId(id: Long): User? = User(id = 1, telegramId = id, username = "tester")

        override suspend fun getById(id: Long): User? = User(id = id, telegramId = id, username = "tester")
    }

    private class StubUserRoleRepository(
        private val roles: Set<Role>,
        private val clubIds: Set<Long>,
    ) : UserRoleRepository {
        override suspend fun listRoles(userId: Long): Set<Role> = roles

        override suspend fun listClubIdsFor(userId: Long): Set<Long> = clubIds
    }

    private class InMemoryOpsChatConfigRepository(private val now: Instant) : ClubOpsChatConfigRepository {
        private val storage = mutableMapOf<Long, ClubOpsChatConfig>()

        override suspend fun getByClubId(clubId: Long): ClubOpsChatConfig? = storage[clubId]

        override suspend fun upsert(config: ClubOpsChatConfigUpsert): ClubOpsChatConfig {
            val saved =
                ClubOpsChatConfig(
                    clubId = config.clubId,
                    chatId = config.chatId,
                    bookingsThreadId = config.bookingsThreadId,
                    checkinThreadId = config.checkinThreadId,
                    guestListsThreadId = config.guestListsThreadId,
                    supportThreadId = config.supportThreadId,
                    alertsThreadId = config.alertsThreadId,
                    updatedAt = now,
                )
            storage[config.clubId] = saved
            return saved
        }
    }

    companion object {
        private const val NO_STORE = "no-store"
    }
}
