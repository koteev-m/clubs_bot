package com.example.bot.routes

import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.http.ErrorCodes
import com.example.bot.plugins.TelegramMiniUser
import com.example.bot.plugins.overrideMiniAppValidatorForTesting
import com.example.bot.plugins.resetMiniAppValidator
import com.example.bot.promoter.admin.PromoterAccessUpdateResult
import com.example.bot.promoter.admin.PromoterAdminProfile
import com.example.bot.promoter.admin.PromoterAdminRepository
import com.example.bot.promoter.admin.PromoterAdminService
import com.example.bot.promoter.quotas.PromoterQuota
import com.example.bot.promoter.quotas.PromoterQuotaRepository
import com.example.bot.promoter.quotas.PromoterQuotaService
import com.example.bot.security.auth.TelegramPrincipal
import com.example.bot.security.rbac.RbacPlugin
import io.ktor.client.request.get
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import io.mockk.mockk

class PromoterAdminRoutesTest {
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
    fun `list rejects non admins`() = withApp(roles = setOf(Role.PROMOTER)) { _ ->
        val response =
            client.get("/api/admin/promoters?clubId=1") { header("X-Telegram-Init-Data", "init") }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `list rejects club scope mismatch`() = withApp(clubIds = setOf(2)) { _ ->
        val response =
            client.get("/api/admin/promoters?clubId=1") { header("X-Telegram-Init-Data", "init") }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `list returns promoters with quotas`() = withApp { state ->
        state.repository.setPromoters(
            listOf(
                PromoterAdminProfile(
                    promoterId = 10,
                    telegramUserId = 10010,
                    username = "promo",
                    displayName = "Promo User",
                    accessEnabled = true,
                ),
            ),
        )
        state.quotaRepo.upsert(
            PromoterQuota(
                clubId = 1,
                promoterId = 10,
                tableId = 5,
                quota = 2,
                held = 1,
                expiresAt = now.plusSeconds(600),
            ),
        )

        val response =
            client.get("/api/admin/promoters?clubId=1") { header("X-Telegram-Init-Data", "init") }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsJson()
        val promoters = body["promoters"]!!.jsonArray
        assertEquals(1, promoters.size)
        val promoter = promoters.first().jsonObject
        assertEquals("Promo User", promoter["displayName"]!!.jsonPrimitive.content)
        val quotas = promoter["quotas"]!!.jsonArray
        assertEquals(1, quotas.size)
    }

    @Test
    fun `access update validates input`() = withApp { _ ->
        val response =
            client.post("/api/admin/promoters/0/access") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody("""{"clubId":0,"enabled":true}""")
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(ErrorCodes.validation_error, response.errorCode())
    }

    @Test
    fun `access update toggles promoter`() = withApp { state ->
        state.repository.allowPromoter(10)

        val response =
            client.post("/api/admin/promoters/10/access") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody("""{"clubId":1,"enabled":false}""")
            }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(false, state.repository.enabled[10])
    }

    private fun relaxedAuditRepository() = mockk<com.example.bot.data.booking.core.AuditLogRepository>(relaxed = true)

    private fun withApp(
        roles: Set<Role> = setOf(Role.CLUB_ADMIN),
        clubIds: Set<Long> = setOf(1),
        block: suspend ApplicationTestBuilder.(TestState) -> Unit,
    ) {
        val quotaRepo = InMemoryPromoterQuotaRepository()
        val quotaService = PromoterQuotaService(quotaRepo, clock)
        val repository = InMemoryPromoterAdminRepository()
        val service = PromoterAdminService(repository, quotaService, clock)
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                install(RbacPlugin) {
                    userRepository = StubUserRepository()
                    userRoleRepository = StubUserRoleRepository(roles, clubIds)
                    auditLogRepository = relaxedAuditRepository()
                    principalExtractor = { TelegramPrincipal(telegramId, "tester") }
                }
                promoterAdminRoutes(service, botTokenProvider = { "test" })
            }
            block(this, TestState(repository, quotaRepo))
        }
    }

    private data class TestState(
        val repository: InMemoryPromoterAdminRepository,
        val quotaRepo: InMemoryPromoterQuotaRepository,
    )

    private fun JsonObject.errorCodeOrNull(): String? =
        this["code"]?.jsonPrimitive?.content
            ?: this["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content

    private suspend fun io.ktor.client.statement.HttpResponse.errorCode(): String =
        runCatching { Json.parseToJsonElement(bodyAsText()).jsonObject.errorCodeOrNull() }.getOrNull().orEmpty()

    private suspend fun io.ktor.client.statement.HttpResponse.bodyAsJson(): JsonObject =
        Json.parseToJsonElement(bodyAsText()).jsonObject

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

    private class InMemoryPromoterQuotaRepository : PromoterQuotaRepository {
        private val storage = ConcurrentHashMap<Triple<Long, Long, Long>, PromoterQuota>()

        override fun upsert(quota: PromoterQuota): PromoterQuota {
            storage[Triple(quota.clubId, quota.promoterId, quota.tableId)] = quota
            return quota
        }

        override fun find(
            clubId: Long,
            promoterId: Long,
            tableId: Long,
        ): PromoterQuota? = storage[Triple(clubId, promoterId, tableId)]

        override fun listByClub(clubId: Long): List<PromoterQuota> =
            storage.values.filter { it.clubId == clubId }
    }

    private class InMemoryPromoterAdminRepository : PromoterAdminRepository {
        private val data = mutableListOf<PromoterAdminProfile>()
        val enabled = mutableMapOf<Long, Boolean>()

        override suspend fun listPromotersByClub(clubId: Long): List<PromoterAdminProfile> = data

        override suspend fun setPromoterAccess(
            clubId: Long,
            promoterId: Long,
            enabled: Boolean,
        ): PromoterAccessUpdateResult {
            this.enabled[promoterId] = enabled
            return if (data.any { it.promoterId == promoterId }) {
                PromoterAccessUpdateResult.Success(enabled)
            } else {
                PromoterAccessUpdateResult.NotFound
            }
        }

        fun setPromoters(promoters: List<PromoterAdminProfile>) {
            data.clear()
            data.addAll(promoters)
            promoters.forEach { enabled[it.promoterId] = it.accessEnabled }
        }

        fun allowPromoter(promoterId: Long) {
            data.add(
                PromoterAdminProfile(
                    promoterId = promoterId,
                    telegramUserId = 10000 + promoterId,
                    username = "promo-$promoterId",
                    displayName = "Promoter $promoterId",
                    accessEnabled = true,
                ),
            )
            enabled[promoterId] = true
        }
    }
}
