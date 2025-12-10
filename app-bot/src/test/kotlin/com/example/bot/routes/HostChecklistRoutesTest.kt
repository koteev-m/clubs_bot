package com.example.bot.routes

import com.example.bot.clubs.Event
import com.example.bot.clubs.InMemoryEventsRepository
import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.host.ShiftChecklistService
import com.example.bot.http.ErrorCodes
import com.example.bot.plugins.TelegramMiniUser
import com.example.bot.plugins.overrideMiniAppValidatorForTesting
import com.example.bot.plugins.resetMiniAppValidator
import com.example.bot.security.auth.TelegramPrincipal
import com.example.bot.security.rbac.RbacPlugin
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HostChecklistRoutesTest {
    private val now = Instant.parse("2024-06-08T18:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
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
    fun `get returns template with defaults`() = withHostApp() {
        val response = client.get("/api/host/checklist?clubId=1&eventId=100") {
            header("X-Telegram-Init-Data", "init")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        response.assertNoStoreHeaders()
        val body = response.bodyAsJson()
        assertEquals(1L, body["clubId"]!!.jsonPrimitive.long)
        assertEquals(100L, body["eventId"]!!.jsonPrimitive.long)
        val items = body["items"]!!.jsonArray
        assertTrue(items.isNotEmpty())
        items.forEach { item ->
            val obj = item.jsonObject
            assertEquals(false, obj["done"]!!.jsonPrimitive.booleanOrNull ?: false)

            val updatedAt = obj["updatedAt"]
            val actorId = obj["actorId"]

            assertTrue(updatedAt == null || updatedAt is JsonNull)
            assertTrue(actorId == null || actorId is JsonNull)
        }
    }

    @Test
    fun `post toggles done and records audit`() = withHostApp() {
        val initial = client.get("/api/host/checklist?clubId=1&eventId=100") {
            header("X-Telegram-Init-Data", "init")
        }.bodyAsJson()
        val firstItemId = initial["items"]!!.jsonArray.first().jsonObject["id"]!!.jsonPrimitive.content

        val response =
            client.post("/api/host/checklist?clubId=1&eventId=100") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody("""{"itemId":"$firstItemId","done":true}""")
            }

        assertEquals(HttpStatusCode.OK, response.status)
        response.assertNoStoreHeaders()
        val body = response.bodyAsJson()
        val updatedItem =
            body["items"]!!.jsonArray.first { it.jsonObject["id"]!!.jsonPrimitive.content == firstItemId }.jsonObject
        assertEquals(true, updatedItem["done"]!!.jsonPrimitive.booleanOrNull)
        assertNotNull(updatedItem["updatedAt"]!!.jsonPrimitive.contentOrNull)
        assertEquals(telegramId, updatedItem["actorId"]!!.jsonPrimitive.long)

        val persisted = client.get("/api/host/checklist?clubId=1&eventId=100") {
            header("X-Telegram-Init-Data", "init")
        }.bodyAsJson()
        val persistedItem =
            persisted["items"]!!.jsonArray.first { it.jsonObject["id"]!!.jsonPrimitive.content == firstItemId }.jsonObject
        assertEquals(true, persistedItem["done"]!!.jsonPrimitive.booleanOrNull)
        assertNotNull(persistedItem["updatedAt"]!!.jsonPrimitive.contentOrNull)
        assertEquals(telegramId, persistedItem["actorId"]!!.jsonPrimitive.long)
    }

    @Test
    fun `rbac forbids non entry roles`() = withHostApp(roles = setOf(Role.PROMOTER)) {
        val response = client.get("/api/host/checklist?clubId=1&eventId=100") {
            header("X-Telegram-Init-Data", "init")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals(ErrorCodes.forbidden, response.errorCode())
        response.assertNoStoreHeaders()
    }

    @Test
    fun `rbac forbids non entry roles on post`() = withHostApp(roles = setOf(Role.PROMOTER)) {
        val response =
            client.post("/api/host/checklist?clubId=1&eventId=100") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody("""{"itemId":"doors_open","done":true}""")
            }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals(ErrorCodes.forbidden, response.errorCode())
        response.assertNoStoreHeaders()
    }

    @Test
    fun `validates query parameters`() = withHostApp() {
        listOf(
            "/api/host/checklist",
            "/api/host/checklist?clubId=0&eventId=100",
            "/api/host/checklist?clubId=1&eventId=0",
        ).forEach { path ->
            val response = client.get(path) {
                header("X-Telegram-Init-Data", "init")
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(ErrorCodes.validation_error, response.errorCode())
            response.assertNoStoreHeaders()
        }
    }

    @Test
    fun `returns not found when event missing`() = withHostApp(events = emptyList()) {
        val response = client.get("/api/host/checklist?clubId=1&eventId=100") {
            header("X-Telegram-Init-Data", "init")
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(ErrorCodes.not_found, response.errorCode())
        response.assertNoStoreHeaders()
    }

    @Test
    fun `post rejects unknown itemId`() = withHostApp() {
        val response =
            client.post("/api/host/checklist?clubId=1&eventId=100") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody("""{"itemId":"unknown","done":true}""")
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(ErrorCodes.validation_error, response.errorCode())
        response.assertNoStoreHeaders()
    }

    @Test
    fun `post returns invalid_json on malformed body`() = withHostApp() {
        val response = client.post("/api/host/checklist?clubId=1&eventId=100") {
            header("X-Telegram-Init-Data", "init")
            contentType(ContentType.Application.Json)
            setBody("{ not-a-valid-json }")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(ErrorCodes.invalid_json, response.errorCode())
        response.assertNoStoreHeaders()
    }

    private fun withHostApp(
        events: List<Event> = listOf(defaultEvent()),
        roles: Set<Role> = setOf(Role.ENTRY_MANAGER),
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) {
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                install(RbacPlugin) {
                    userRepository = StubUserRepository()
                    userRoleRepository = StubUserRoleRepository(roles)
                    auditLogRepository = relaxedAuditRepository()
                    principalExtractor = { TelegramPrincipal(telegramId, "tester") }
                }

                val eventsRepository = InMemoryEventsRepository(events)
                val checklistService = ShiftChecklistService(clock = clock)

                hostChecklistRoutes(
                    checklistService = checklistService,
                    eventsRepository = eventsRepository,
                    clock = clock,
                    botTokenProvider = { "test" },
                )
            }
            block()
        }
    }

    private fun defaultEvent(): Event =
        Event(
            id = 100,
            clubId = 1,
            startUtc = now,
            endUtc = now.plusSeconds(3600),
            title = "Party",
            isSpecial = false,
        )

    private class StubUserRepository : UserRepository {
        override suspend fun getByTelegramId(id: Long): User? = User(id = 1, telegramId = id, username = "tester")
    }

    private class StubUserRoleRepository(private val roles: Set<Role>) : UserRoleRepository {
        override suspend fun listRoles(userId: Long): Set<Role> = roles

        override suspend fun listClubIdsFor(userId: Long): Set<Long> = setOf(1)
    }

    private fun relaxedAuditRepository() = io.mockk.mockk<com.example.bot.data.booking.core.AuditLogRepository>(relaxed = true)

    private suspend fun io.ktor.client.statement.HttpResponse.bodyAsJson(): JsonObject =
        Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun io.ktor.client.statement.HttpResponse.errorCode(): String {
        val raw = bodyAsText()
        val parsed = runCatching { Json.parseToJsonElement(raw).jsonObject.errorCodeOrNull() }.getOrNull()
        val extracted = Regex("\\\"error\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").find(raw)?.groupValues?.getOrNull(1)
        return parsed ?: extracted ?: raw
    }

    private fun JsonObject.errorCodeOrNull(): String? =
        this["code"]?.jsonPrimitive?.content
            ?: this["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content
            ?: this["error"]?.jsonPrimitive?.content

    private fun io.ktor.client.statement.HttpResponse.assertNoStoreHeaders() {
        assertEquals("no-store", headers[HttpHeaders.CacheControl])
        assertEquals("X-Telegram-Init-Data", headers[HttpHeaders.Vary])
    }
}
