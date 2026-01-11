package com.example.bot.routes

import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.http.ErrorCodes
import com.example.bot.plugins.TelegramMiniUser
import com.example.bot.plugins.overrideMiniAppValidatorForTesting
import com.example.bot.plugins.resetMiniAppValidator
import com.example.bot.promoter.quotas.InMemoryPromoterQuotaRepository
import com.example.bot.promoter.quotas.PromoterQuota
import com.example.bot.promoter.quotas.PromoterQuotaService
import com.example.bot.security.auth.TelegramPrincipal
import com.example.bot.security.rbac.RbacPlugin
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
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
import java.time.Clock
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
import io.mockk.mockk

class PromoterQuotasAdminRoutesTest {
    private val now = Instant.parse("2024-06-01T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val telegramId = 99L

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
    fun `get requires clubId`() = withApp { _, _ ->
        val response = client.get("/api/admin/quotas") { header("X-Telegram-Init-Data", "init") }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(ErrorCodes.validation_error, response.errorCode())
        response.assertNoStoreHeaders()
    }

    @Test
    fun `get returns quotas`() = withApp { repo, _ ->
        repo.upsert(
            PromoterQuota(
                clubId = 1,
                promoterId = 5,
                tableId = 10,
                quota = 2,
                held = 1,
                expiresAt = now.plusSeconds(1000),
            ),
        )

        val response =
            client.get("/api/admin/quotas?clubId=1") { header("X-Telegram-Init-Data", "init") }

        assertEquals(HttpStatusCode.OK, response.status)
        val quotas = response.bodyAsJson()["quotas"]!!.jsonArray
        assertEquals(1, quotas.size)
        val first = quotas.first().jsonObject
        assertEquals(1L, first["clubId"]!!.jsonPrimitive.long)
        assertEquals(5L, first["promoterId"]!!.jsonPrimitive.long)
        assertEquals(10L, first["tableId"]!!.jsonPrimitive.long)
        assertEquals(2, first["quota"]!!.jsonPrimitive.long)
        assertEquals(1, first["held"]!!.jsonPrimitive.long)
        response.assertNoStoreHeaders()
    }

    @Test
    fun `post validates input`() = withApp { _, _ ->
        val invalidJson = client.post("/api/admin/quotas") { header("X-Telegram-Init-Data", "init") }
        assertEquals(HttpStatusCode.BadRequest, invalidJson.status)
        assertEquals(ErrorCodes.invalid_json, invalidJson.errorCode())

        val invalidFields =
            client.post("/api/admin/quotas") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody("""{"clubId":0,"promoterId":1,"tableId":1,"quota":0,"expiresAt":"oops"}""")
            }
        assertEquals(HttpStatusCode.BadRequest, invalidFields.status)
        assertEquals(ErrorCodes.validation_error, invalidFields.errorCode())
    }

    @Test
    fun `post creates quota with held reset`() = withApp { repo, _ ->
        val response =
            client.post("/api/admin/quotas") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                    "clubId":1,
                    "promoterId":5,
                    "tableId":10,
                    "quota":3,
                    "expiresAt":"${now.plusSeconds(500)}"
                }""",
                )
            }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(0, repo.find(1, 5, 10)!!.held)
        assertEquals(3, repo.find(1, 5, 10)!!.quota)
        val body = response.bodyAsJson()["quota"]!!.jsonObject
        assertEquals(0, body["held"]!!.jsonPrimitive.long)
        response.assertNoStoreHeaders()
    }

    @Test
    fun `put returns not found when missing`() = withApp { _, _ ->
        val response =
            client.put("/api/admin/quotas") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                    "clubId":1,
                    "promoterId":5,
                    "tableId":10,
                    "quota":3,
                    "expiresAt":"${now.plusSeconds(500)}"
                }""",
                )
            }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(ErrorCodes.not_found, response.errorCode())
    }

    @Test
    fun `put updates quota preserving held`() = withApp { repo, _ ->
        repo.upsert(
            PromoterQuota(
                clubId = 1,
                promoterId = 5,
                tableId = 10,
                quota = 2,
                held = 2,
                expiresAt = now.plusSeconds(100),
            ),
        )

        val response =
            client.put("/api/admin/quotas") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                    "clubId":1,
                    "promoterId":5,
                    "tableId":10,
                    "quota":5,
                    "expiresAt":"${now.plusSeconds(900)}"
                }""",
                )
            }

        assertEquals(HttpStatusCode.OK, response.status)
        val persisted = repo.find(1, 5, 10)!!
        assertEquals(2, persisted.held)
        assertEquals(5, persisted.quota)
        val body = response.bodyAsJson()["quota"]!!.jsonObject
        assertEquals(2, body["held"]!!.jsonPrimitive.long)
        response.assertNoStoreHeaders()
    }

    private fun relaxedAuditRepository() = mockk<com.example.bot.data.booking.core.AuditLogRepository>(relaxed = true)

    private fun withApp(
        block: suspend ApplicationTestBuilder.(InMemoryPromoterQuotaRepository, PromoterQuotaService) -> Unit,
    ) {
        val repo = InMemoryPromoterQuotaRepository()
        val service = PromoterQuotaService(repo, clock)
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                install(RbacPlugin) {
                    userRepository = StubUserRepository()
                    userRoleRepository = StubUserRoleRepository(setOf(Role.CLUB_ADMIN))
                    auditLogRepository = relaxedAuditRepository()
                    principalExtractor = { TelegramPrincipal(telegramId, "tester") }
                }
                promoterQuotasAdminRoutes(service, botTokenProvider = { "test" }, clock = clock)
            }
            block(this, repo, service)
        }
    }

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
    ) : UserRoleRepository {
        override suspend fun listRoles(userId: Long): Set<Role> = roles

        override suspend fun listClubIdsFor(userId: Long): Set<Long> = emptySet()
    }

    companion object {
        private const val NO_STORE = "no-store"
    }
}
