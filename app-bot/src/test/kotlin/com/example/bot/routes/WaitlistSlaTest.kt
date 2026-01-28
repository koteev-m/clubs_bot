package com.example.bot.routes

import com.example.bot.club.WaitlistEntry
import com.example.bot.club.WaitlistRepository
import com.example.bot.club.WaitlistStatus
import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.notifications.NotificationService
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
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

class WaitlistSlaTest {
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
    fun `called entry exposes reserve and remaining seconds`() = withWaitlistApp() { repo, _ ->
        val waiting = repo.enqueue(clubId = 1, eventId = 100, userId = telegramId, partySize = 2)
        repo.enqueue(clubId = 1, eventId = 100, userId = telegramId + 1, partySize = 1)
        repo.callEntry(clubId = 1, id = waiting.id, reserveMinutes = 10)

        val response = client.get("/api/clubs/1/waitlist?eventId=100") { header("X-Telegram-Init-Data", "init") }

        assertEquals(HttpStatusCode.OK, response.status)
        response.assertNoStoreHeaders()

        val body = response.bodyAsJsonArray()
        val called = body.first { it.jsonObject["id"]!!.jsonPrimitive.long == waiting.id }.jsonObject
        assertEquals(waiting.id, called["id"]!!.jsonPrimitive.long)
        assertEquals("CALLED", called["status"]!!.jsonPrimitive.content)
        assertEquals(called["expiresAt"]!!.jsonPrimitive.content, called["reserveExpiresAt"]!!.jsonPrimitive.content)
        assertEquals(600, called["remainingSeconds"]!!.jsonPrimitive.int)

        val waitingEntry = body.first { it.jsonObject["status"]!!.jsonPrimitive.content == "WAITING" }.jsonObject
        assertTrue(waitingEntry["reserveExpiresAt"] == null || waitingEntry["reserveExpiresAt"] is JsonNull)
        assertTrue(waitingEntry["remainingSeconds"] == null || waitingEntry["remainingSeconds"] is JsonNull)
    }

    @Test
    fun `remaining seconds never negative`() = withWaitlistApp() { repo, _ ->
        repo.addEntry(
            WaitlistEntry(
                id = 99,
                clubId = 1,
                eventId = 100,
                userId = telegramId,
                partySize = 2,
                createdAt = now.minusSeconds(3600),
                calledAt = now.minusSeconds(120),
                expiresAt = now.minusSeconds(60),
                status = WaitlistStatus.CALLED,
            ),
        )

        val response = client.get("/api/clubs/1/waitlist?eventId=100") { header("X-Telegram-Init-Data", "init") }

        assertEquals(HttpStatusCode.OK, response.status)
        response.assertNoStoreHeaders()

        val body = response.bodyAsJsonArray()
        val expired = body.first { it.jsonObject["id"]!!.jsonPrimitive.long == 99L }.jsonObject
        assertEquals(0, expired["remainingSeconds"]!!.jsonPrimitive.int)
        assertNotNull(expired["reserveExpiresAt"]!!.jsonPrimitive.content)
    }

    @Test
    fun `notification triggered on enqueue`() = withWaitlistApp(roles = setOf(Role.GUEST, Role.ENTRY_MANAGER)) { _, notification ->
        val response =
            client.post("/api/clubs/1/waitlist") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody("""{"eventId":100,"partySize":3}""")
            }

        assertEquals(HttpStatusCode.OK, response.status)
        response.assertNoStoreHeaders()
        assertEquals(1, notification.callCount.get())
        val entry = notification.lastEntry.get()
        assertNotNull(entry)
        assertEquals(1L, entry!!.clubId)
        assertEquals(100L, entry.eventId)
        assertEquals(1L, entry.userId)
        assertEquals(3, entry.partySize)
    }

    @Test
    fun `validation keeps headers`() = withWaitlistApp(roles = setOf(Role.GUEST, Role.ENTRY_MANAGER)) { _, _ ->
        val response =
            client.post("/api/clubs/1/waitlist") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody("""{"eventId":100,"partySize":0}""")
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        response.assertNoStoreHeaders()
        assertEquals("invalid_party_size", response.errorCode())
    }

    @Test
    fun `get validates eventId and keeps headers`() = withWaitlistApp() { _, _ ->
        val response =
            client.get("/api/clubs/1/waitlist") { header("X-Telegram-Init-Data", "init") }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        response.assertNoStoreHeaders()
        assertEquals("invalid_event_id", response.errorCode())
    }

    private fun withWaitlistApp(
        roles: Set<Role> = setOf(Role.ENTRY_MANAGER),
        block: suspend ApplicationTestBuilder.(repo: StubWaitlistRepository, notification: FakeNotificationService) -> Unit,
    ) {
        testApplication {
            val repo = StubWaitlistRepository(clock)
            val notification = FakeNotificationService()

            application {
                install(ContentNegotiation) { json() }
                install(RbacPlugin) {
                    userRepository = StubUserRepository()
                    userRoleRepository = StubUserRoleRepository(roles)
                    auditLogRepository = relaxedAuditRepository()
                    principalExtractor = { TelegramPrincipal(telegramId, "tester") }
                }

                waitlistRoutes(
                    repository = repo,
                    notificationService = notification,
                    clock = clock,
                    botTokenProvider = { "test" },
                )
            }

            block(repo, notification)
        }
    }

    private class StubWaitlistRepository(private val clock: Clock) : WaitlistRepository {
        private val entries = mutableListOf<WaitlistEntry>()
        private var nextId = 1L

        override suspend fun enqueue(clubId: Long, eventId: Long, userId: Long, partySize: Int): WaitlistEntry {
            val entry =
                WaitlistEntry(
                    id = nextId++,
                    clubId = clubId,
                    eventId = eventId,
                    userId = userId,
                    partySize = partySize,
                    createdAt = Instant.now(clock),
                    calledAt = null,
                    expiresAt = null,
                    status = WaitlistStatus.WAITING,
                )
            entries += entry
            return entry
        }

        override suspend fun listQueue(clubId: Long, eventId: Long): List<WaitlistEntry> =
            entries.filter { it.clubId == clubId && it.eventId == eventId }

        override suspend fun callEntry(clubId: Long, id: Long, reserveMinutes: Int): WaitlistEntry? {
            val index = entries.indexOfFirst { it.id == id && it.clubId == clubId && it.status == WaitlistStatus.WAITING }
            if (index < 0) return null

            val calledAt = Instant.now(clock)
            val updated =
                entries[index].copy(
                    calledAt = calledAt,
                    expiresAt = calledAt.plusSeconds(reserveMinutes.toLong() * 60),
                    status = WaitlistStatus.CALLED,
                )
            entries[index] = updated
            return updated
        }

        override suspend fun expireEntry(clubId: Long, id: Long, close: Boolean): WaitlistEntry? {
            val index = entries.indexOfFirst { it.id == id && it.clubId == clubId }
            if (index < 0) return null

            val target = entries[index]
            val updated =
                if (close) {
                    target.copy(status = WaitlistStatus.EXPIRED)
                } else {
                    target.copy(status = WaitlistStatus.WAITING, calledAt = null, expiresAt = null)
                }
            entries[index] = updated
            return updated
        }

        override suspend fun get(id: Long): WaitlistEntry? = entries.firstOrNull { it.id == id }

        fun addEntry(entry: WaitlistEntry) {
            entries += entry
            nextId = maxOf(nextId, entry.id + 1)
        }
    }

    private class FakeNotificationService : NotificationService {
        val callCount = AtomicInteger(0)
        val lastEntry = AtomicReference<WaitlistEntry?>()

        override suspend fun notifyEnqueuedGuest(entry: WaitlistEntry) {
            callCount.incrementAndGet()
            lastEntry.set(entry)
        }
    }

    private class StubUserRepository : UserRepository {
        override suspend fun getByTelegramId(id: Long): User? = User(id = 1, telegramId = id, username = "tester")

        override suspend fun getById(id: Long): User? = User(id = id, telegramId = id, username = "tester")
    }

    private class StubUserRoleRepository(private val roles: Set<Role>) : UserRoleRepository {
        override suspend fun listRoles(userId: Long): Set<Role> = roles

        override suspend fun listClubIdsFor(userId: Long): Set<Long> = setOf(1)
    }

    private fun relaxedAuditRepository() = io.mockk.mockk<com.example.bot.audit.AuditLogRepository>(relaxed = true)

    private suspend fun io.ktor.client.statement.HttpResponse.errorCode(): String {
        val raw = bodyAsText()
        val parsed = runCatching { Json.parseToJsonElement(raw).jsonObject.errorCodeOrNull() }.getOrNull()
        val extracted = Regex("""\"error\"\s*:\s*\"([^\"]+)\"""").find(raw)?.groupValues?.getOrNull(1)
        return parsed ?: extracted ?: raw
    }

    private fun JsonObject.errorCodeOrNull(): String? =
        this["code"]?.jsonPrimitive?.content
            ?: this["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content
            ?: this["error"]?.jsonPrimitive?.content

    private suspend fun io.ktor.client.statement.HttpResponse.bodyAsJsonArray(): JsonArray =
        Json.parseToJsonElement(bodyAsText()).jsonArray

    private fun io.ktor.client.statement.HttpResponse.assertNoStoreHeaders() {
        assertEquals("no-store", headers[HttpHeaders.CacheControl])
        assertEquals("X-Telegram-Init-Data", headers[HttpHeaders.Vary])
    }
}
