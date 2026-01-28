package com.example.bot.routes

import com.example.bot.clubs.Event
import com.example.bot.clubs.InMemoryEventsRepository
import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.http.ErrorCodes
import com.example.bot.plugins.TelegramMiniUser
import com.example.bot.plugins.overrideMiniAppValidatorForTesting
import com.example.bot.plugins.resetMiniAppValidator
import com.example.bot.promoter.invites.InMemoryPromoterInviteRepository
import com.example.bot.promoter.invites.PromoterInvite
import com.example.bot.promoter.invites.PromoterInviteStatus
import com.example.bot.promoter.rating.PromoterRatingService
import com.example.bot.security.auth.TelegramPrincipal
import com.example.bot.security.rbac.RbacPlugin
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class PromoterRatingRoutesTest {
    private val now = Instant.parse("2024-06-08T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val telegramId = 77L

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
    fun `scorecard returns metrics and headers`() = withPromoterApp { _ ->
        val response = client.get("/api/promoter/scorecard?period=week") {
            header("X-Telegram-Init-Data", "init")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsJson()
        assertEquals("week", body["period"]!!.jsonPrimitive.content)
        assertEquals(5, body["invited"]!!.jsonPrimitive.long)
        assertEquals(3, body["arrivals"]!!.jsonPrimitive.long)
        assertEquals(2, body["noShows"]!!.jsonPrimitive.long)
        response.assertNoStoreHeaders()
    }

    @Test
    fun `invalid period returns validation error`() = withPromoterApp { _ ->
        val response = client.get("/api/promoter/scorecard?period=bad") {
            header("X-Telegram-Init-Data", "init")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(ErrorCodes.validation_error, response.errorCode())
        response.assertNoStoreHeaders()
    }

    @Test
    fun `admin rating returns paginated list`() = withAdminApp { _ ->
        val response = client.get("/api/admin/promoters/rating?clubId=1&period=week&page=1&size=10") {
            header("X-Telegram-Init-Data", "init")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsJson()
        val items = body["items"]!!.jsonArray
        assertEquals(2, items.size)
        assertEquals(1L, body["clubId"]!!.jsonPrimitive.long)
        assertEquals(2L, items.first().jsonObject["promoterId"]!!.jsonPrimitive.long)
        response.assertNoStoreHeaders()
    }

    @Test
    fun `admin rating rejects invalid period`() = withAdminApp { _ ->
        val response = client.get("/api/admin/promoters/rating?clubId=1&period=bad&page=1&size=10") {
            header("X-Telegram-Init-Data", "init")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(ErrorCodes.validation_error, response.errorCode())
        response.assertNoStoreHeaders()
    }

    @Test
    fun `admin rating validates pagination`() = withAdminApp { _ ->
        listOf(
            "/api/admin/promoters/rating?clubId=1&period=week&page=0&size=10",
            "/api/admin/promoters/rating?clubId=1&period=week&page=1&size=0",
            "/api/admin/promoters/rating?clubId=1&period=week&page=1&size=101",
        ).forEach { path ->
            val response = client.get(path) {
                header("X-Telegram-Init-Data", "init")
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(ErrorCodes.validation_error, response.errorCode())
            response.assertNoStoreHeaders()
        }
    }

    private data class ServiceFixture(
        val inviteRepository: InMemoryPromoterInviteRepository,
        val service: PromoterRatingService,
    )

    private fun buildService(): ServiceFixture {
        val events = listOf(
            Event(
                id = 1,
                clubId = 1,
                startUtc = now.minus(Duration.ofDays(2)),
                endUtc = now.minus(Duration.ofDays(2)).plus(Duration.ofHours(3)),
                title = null,
                isSpecial = false,
            ),
            Event(
                id = 2,
                clubId = 1,
                startUtc = now.minus(Duration.ofHours(5)),
                endUtc = now.minus(Duration.ofHours(1)),
                title = null,
                isSpecial = false,
            ),
        )
        val eventsRepository = InMemoryEventsRepository(events, updatedAt = now)
        val invites = InMemoryPromoterInviteRepository()

        invites.save(
            PromoterInvite(
                id = invites.nextId(),
                promoterId = telegramId,
                clubId = 1,
                eventId = 1,
                guestName = "A",
                guestCount = 3,
                status = PromoterInviteStatus.ARRIVED,
                issuedAt = now.minus(Duration.ofDays(3)),
                openedAt = null,
                confirmedAt = null,
                arrivedAt = now.minus(Duration.ofDays(2)).plus(Duration.ofHours(1)),
                noShowAt = null,
                revokedAt = null,
            ),
        )
        invites.save(
            PromoterInvite(
                id = invites.nextId(),
                promoterId = telegramId,
                clubId = 1,
                eventId = 2,
                guestName = "B",
                guestCount = 2,
                status = PromoterInviteStatus.ISSUED,
                issuedAt = now.minus(Duration.ofDays(1)),
                openedAt = null,
                confirmedAt = null,
                arrivedAt = null,
                noShowAt = null,
                revokedAt = null,
            ),
        )

        invites.save(
            PromoterInvite(
                id = invites.nextId(),
                promoterId = 2,
                clubId = 1,
                eventId = 1,
                guestName = "C",
                guestCount = 4,
                status = PromoterInviteStatus.ARRIVED,
                issuedAt = now.minus(Duration.ofDays(3)),
                openedAt = null,
                confirmedAt = null,
                arrivedAt = now.minus(Duration.ofDays(2)).plus(Duration.ofHours(1)),
                noShowAt = null,
                revokedAt = null,
            ),
        )

        val service = PromoterRatingService(invites, eventsRepository, clock)
        return ServiceFixture(invites, service)
    }

    private fun withPromoterApp(block: suspend ApplicationTestBuilder.(ServiceFixture) -> Unit) {
        val fixture = buildService()
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                install(RbacPlugin) {
                    userRepository = StubUserRepository()
                    userRoleRepository = StubUserRoleRepository(emptySet())
                    auditLogRepository = relaxedAuditRepository()
                    principalExtractor = { TelegramPrincipal(telegramId, "tester") }
                }
                promoterRatingRoutes(fixture.service, botTokenProvider = { "test" })
            }
            block(this, fixture)
        }
    }

    private fun withAdminApp(block: suspend ApplicationTestBuilder.(ServiceFixture) -> Unit) {
        val fixture = buildService()
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                install(RbacPlugin) {
                    userRepository = StubUserRepository()
                    userRoleRepository = StubUserRoleRepository(setOf(Role.CLUB_ADMIN))
                    auditLogRepository = relaxedAuditRepository()
                    principalExtractor = { TelegramPrincipal(telegramId, "tester") }
                }
                promoterRatingRoutes(fixture.service, botTokenProvider = { "test" })
            }
            block(this, fixture)
        }
    }

    private fun relaxedAuditRepository() = io.mockk.mockk<com.example.bot.audit.AuditLogRepository>(relaxed = true)

    private class StubUserRepository : UserRepository {
        override suspend fun getByTelegramId(id: Long): User? = User(id = 1, telegramId = id, username = "tester")

        override suspend fun getById(id: Long): User? = User(id = id, telegramId = id, username = "tester")
    }

    private class StubUserRoleRepository(
        private val roles: Set<Role>,
    ) : UserRoleRepository {
        override suspend fun listRoles(userId: Long): Set<Role> = roles

        override suspend fun listClubIdsFor(userId: Long): Set<Long> = emptySet()
    }

    private suspend fun io.ktor.client.statement.HttpResponse.errorCode(): String =
        runCatching { Json.parseToJsonElement(bodyAsText()).jsonObject.errorCodeOrNull() }.getOrNull().orEmpty()

    private fun JsonObject.errorCodeOrNull(): String? =
        this["code"]?.jsonPrimitive?.content
            ?: this["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content

    private suspend fun io.ktor.client.statement.HttpResponse.bodyAsJson(): JsonObject =
        Json.parseToJsonElement(bodyAsText()).jsonObject

    private fun io.ktor.client.statement.HttpResponse.assertNoStoreHeaders() {
        assertEquals("no-store", headers[HttpHeaders.CacheControl])
        assertEquals("X-Telegram-Init-Data", headers[HttpHeaders.Vary])
    }
}
