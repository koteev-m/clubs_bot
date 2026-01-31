package com.example.bot.routes

import com.example.bot.admin.AdminBadge
import com.example.bot.admin.AdminBadgeCreate
import com.example.bot.admin.AdminBadgeRepository
import com.example.bot.admin.AdminBadgeUpdate
import com.example.bot.admin.AdminGamificationSettings
import com.example.bot.admin.AdminGamificationSettingsRepository
import com.example.bot.admin.AdminGamificationSettingsUpdate
import com.example.bot.admin.AdminNightOverride
import com.example.bot.admin.AdminNightOverrideRepository
import com.example.bot.admin.AdminPrize
import com.example.bot.admin.AdminPrizeCreate
import com.example.bot.admin.AdminPrizeRepository
import com.example.bot.admin.AdminPrizeUpdate
import com.example.bot.admin.AdminRewardLadderLevel
import com.example.bot.admin.AdminRewardLadderLevelCreate
import com.example.bot.admin.AdminRewardLadderLevelUpdate
import com.example.bot.admin.AdminRewardLadderRepository
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
import io.mockk.mockk
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AdminGamificationRoutesTest {
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
    fun `forbidden without admin role`() = withApp(roles = emptySet()) { _ ->
        val response = client.get("/api/admin/clubs/1/gamification/badges") { header("X-Telegram-Init-Data", "init") }

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `forbidden for чужого клуба`() = withApp(clubIds = setOf(2)) { _ ->
        val response = client.get("/api/admin/clubs/1/gamification/badges") { header("X-Telegram-Init-Data", "init") }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals(ErrorCodes.forbidden, response.errorCode())
        response.assertNoStoreHeaders()
    }

    @Test
    fun `happy path create badge prize ladder`() = withApp { _ ->
        val badgeResponse =
            client.post("/api/admin/clubs/1/gamification/badges") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                        "code":"first_visit",
                        "nameRu":"Первый визит",
                        "icon":"star",
                        "enabled":true,
                        "conditionType":"VISITS",
                        "threshold":1
                    }""",
                )
            }
        assertEquals(HttpStatusCode.Created, badgeResponse.status)
        val badgeId = badgeResponse.bodyAsJson()["id"]!!.jsonPrimitive.long

        val prizeResponse =
            client.post("/api/admin/clubs/1/gamification/prizes") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                        "code":"free_entry",
                        "titleRu":"Бесплатный вход",
                        "description":"Разовый вход",
                        "enabled":true,
                        "limitTotal":10
                    }""",
                )
            }
        assertEquals(HttpStatusCode.Created, prizeResponse.status)
        val prizeId = prizeResponse.bodyAsJson()["id"]!!.jsonPrimitive.long

        val ladderResponse =
            client.post("/api/admin/clubs/1/gamification/ladder-levels") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                        "metricType":"VISITS",
                        "threshold":5,
                        "windowDays":30,
                        "prizeId":$prizeId,
                        "enabled":true,
                        "orderIndex":1
                    }""",
                )
            }
        assertEquals(HttpStatusCode.Created, ladderResponse.status)
        val ladderId = ladderResponse.bodyAsJson()["id"]!!.jsonPrimitive.long

        assertEquals(1L, badgeId)
        assertEquals(1L, prizeId)
        assertEquals(1L, ladderId)
    }

    @Test
    fun `night override set and clear`() = withApp { repos ->
        repos.settings.upsert(
            AdminGamificationSettingsUpdate(
                clubId = 1,
                stampsEnabled = true,
                earlyEnabled = true,
                badgesEnabled = true,
                prizesEnabled = true,
                contestsEnabled = false,
                tablesLoyaltyEnabled = false,
                earlyWindowMinutes = 60,
            ),
        )
        val nightStart = "2024-06-01T20:00:00Z"
        val overrideResponse =
            client.put("/api/admin/clubs/1/nights/$nightStart/gamification") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody("""{"earlyCutoffAt":"2024-06-01T21:00:00Z"}""")
            }
        assertEquals(HttpStatusCode.OK, overrideResponse.status)
        val overrideBody = overrideResponse.bodyAsJson()
        assertEquals("2024-06-01T21:00:00Z", overrideBody["earlyCutoffAt"]!!.jsonPrimitive.content)
        assertEquals("2024-06-01T21:00:00Z", overrideBody["effectiveEarlyCutoffAt"]!!.jsonPrimitive.content)

        val clearResponse =
            client.put("/api/admin/clubs/1/nights/$nightStart/gamification") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody("""{"earlyCutoffAt":null}""")
            }
        assertEquals(HttpStatusCode.OK, clearResponse.status)
        val clearBody = clearResponse.bodyAsJson()
        assertEquals("2024-06-01T21:00:00Z", clearBody["effectiveEarlyCutoffAt"]!!.jsonPrimitive.content)
        assertEquals("2024-06-01T20:00:00Z", clearBody["nightStartUtc"]!!.jsonPrimitive.content)
    }

    @Test
    fun `prize expiresInDays must be at least 1`() = withApp { _ ->
        val response =
            client.post("/api/admin/clubs/1/gamification/prizes") {
                header("X-Telegram-Init-Data", "init")
                contentType(ContentType.Application.Json)
                setBody(
                    """{
                        "code":"trial",
                        "titleRu":"Пробный",
                        "enabled":true,
                        "expiresInDays":0
                    }""",
                )
            }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(ErrorCodes.validation_error, response.errorCode())
        val details = response.bodyAsJson()["details"]!!.jsonObject
        assertEquals("must_be_at_least_1", details["expiresInDays"]!!.jsonPrimitive.content)
    }

    private fun withApp(
        roles: Set<Role> = setOf(Role.CLUB_ADMIN),
        clubIds: Set<Long> = setOf(1),
        block: suspend ApplicationTestBuilder.(Repos) -> Unit,
    ) {
        val repos = Repos(now)
        testApplication {
            application {
                install(ContentNegotiation) { json() }
                install(RbacPlugin) {
                    userRepository = StubUserRepository()
                    userRoleRepository = StubUserRoleRepository(roles, clubIds)
                    auditLogRepository = mockk(relaxed = true)
                    principalExtractor = { TelegramPrincipal(telegramId, "tester") }
                }
                adminGamificationRoutes(
                    settingsRepository = repos.settings,
                    nightOverrideRepository = repos.nightOverrides,
                    badgeRepository = repos.badges,
                    prizeRepository = repos.prizes,
                    rewardLadderRepository = repos.ladders,
                    botTokenProvider = { "test" },
                )
            }
            block(this, repos)
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
        private val clubIds: Set<Long>,
    ) : UserRoleRepository {
        override suspend fun listRoles(userId: Long): Set<Role> = roles

        override suspend fun listClubIdsFor(userId: Long): Set<Long> = clubIds
    }

    private class Repos(now: Instant) {
        val settings = InMemorySettingsRepository(now)
        val nightOverrides = InMemoryNightOverrideRepository(now)
        val badges = InMemoryBadgeRepository(now)
        val prizes = InMemoryPrizeRepository(now)
        val ladders = InMemoryRewardLadderRepository(now)
    }

    private class InMemorySettingsRepository(private val now: Instant) : AdminGamificationSettingsRepository {
        private val storage = mutableMapOf<Long, AdminGamificationSettings>()

        override suspend fun getByClubId(clubId: Long): AdminGamificationSettings? = storage[clubId]

        override suspend fun upsert(settings: AdminGamificationSettingsUpdate): AdminGamificationSettings {
            val saved =
                AdminGamificationSettings(
                    clubId = settings.clubId,
                    stampsEnabled = settings.stampsEnabled,
                    earlyEnabled = settings.earlyEnabled,
                    badgesEnabled = settings.badgesEnabled,
                    prizesEnabled = settings.prizesEnabled,
                    contestsEnabled = settings.contestsEnabled,
                    tablesLoyaltyEnabled = settings.tablesLoyaltyEnabled,
                    earlyWindowMinutes = settings.earlyWindowMinutes,
                    updatedAt = now,
                )
            storage[settings.clubId] = saved
            return saved
        }
    }

    private class InMemoryNightOverrideRepository(private val now: Instant) : AdminNightOverrideRepository {
        private val storage = mutableMapOf<Pair<Long, Instant>, AdminNightOverride>()

        override suspend fun getOverride(clubId: Long, nightStartUtc: Instant): AdminNightOverride? =
            storage[clubId to nightStartUtc]

        override suspend fun upsertOverride(
            clubId: Long,
            nightStartUtc: Instant,
            earlyCutoffAt: Instant?,
        ): AdminNightOverride {
            val saved =
                AdminNightOverride(
                    clubId = clubId,
                    nightStartUtc = nightStartUtc,
                    earlyCutoffAt = earlyCutoffAt,
                    createdAt = now,
                    updatedAt = now,
                )
            storage[clubId to nightStartUtc] = saved
            return saved
        }
    }

    private class InMemoryBadgeRepository(private val now: Instant) : AdminBadgeRepository {
        private val ids = AtomicLong(0)
        private val storage = mutableMapOf<Long, AdminBadge>()

        override suspend fun listForClub(clubId: Long): List<AdminBadge> =
            storage.values.filter { it.clubId == clubId }.sortedBy { it.id }

        override suspend fun create(clubId: Long, request: AdminBadgeCreate): AdminBadge {
            val id = ids.incrementAndGet()
            val badge =
                AdminBadge(
                    id = id,
                    clubId = clubId,
                    code = request.code,
                    nameRu = request.nameRu,
                    icon = request.icon,
                    enabled = request.enabled,
                    conditionType = request.conditionType,
                    threshold = request.threshold,
                    windowDays = request.windowDays,
                    createdAt = now,
                    updatedAt = now,
                )
            storage[id] = badge
            return badge
        }

        override suspend fun update(clubId: Long, request: AdminBadgeUpdate): AdminBadge? {
            val existing = storage[request.id] ?: return null
            if (existing.clubId != clubId) return null
            val updated =
                existing.copy(
                    code = request.code,
                    nameRu = request.nameRu,
                    icon = request.icon,
                    enabled = request.enabled,
                    conditionType = request.conditionType,
                    threshold = request.threshold,
                    windowDays = request.windowDays,
                    updatedAt = now,
                )
            storage[request.id] = updated
            return updated
        }

        override suspend fun delete(clubId: Long, id: Long): Boolean =
            storage[id]?.takeIf { it.clubId == clubId }?.let { storage.remove(id); true } ?: false
    }

    private class InMemoryPrizeRepository(private val now: Instant) : AdminPrizeRepository {
        private val ids = AtomicLong(0)
        private val storage = mutableMapOf<Long, AdminPrize>()

        override suspend fun listForClub(clubId: Long): List<AdminPrize> =
            storage.values.filter { it.clubId == clubId }.sortedBy { it.id }

        override suspend fun create(clubId: Long, request: AdminPrizeCreate): AdminPrize {
            val id = ids.incrementAndGet()
            val prize =
                AdminPrize(
                    id = id,
                    clubId = clubId,
                    code = request.code,
                    titleRu = request.titleRu,
                    description = request.description,
                    terms = request.terms,
                    enabled = request.enabled,
                    limitTotal = request.limitTotal,
                    expiresInDays = request.expiresInDays,
                    createdAt = now,
                    updatedAt = now,
                )
            storage[id] = prize
            return prize
        }

        override suspend fun update(clubId: Long, request: AdminPrizeUpdate): AdminPrize? {
            val existing = storage[request.id] ?: return null
            if (existing.clubId != clubId) return null
            val updated =
                existing.copy(
                    code = request.code,
                    titleRu = request.titleRu,
                    description = request.description,
                    terms = request.terms,
                    enabled = request.enabled,
                    limitTotal = request.limitTotal,
                    expiresInDays = request.expiresInDays,
                    updatedAt = now,
                )
            storage[request.id] = updated
            return updated
        }

        override suspend fun delete(clubId: Long, id: Long): Boolean =
            storage[id]?.takeIf { it.clubId == clubId }?.let { storage.remove(id); true } ?: false
    }

    private class InMemoryRewardLadderRepository(private val now: Instant) : AdminRewardLadderRepository {
        private val ids = AtomicLong(0)
        private val storage = mutableMapOf<Long, AdminRewardLadderLevel>()

        override suspend fun listForClub(clubId: Long): List<AdminRewardLadderLevel> =
            storage.values.filter { it.clubId == clubId }.sortedBy { it.orderIndex }

        override suspend fun create(
            clubId: Long,
            request: AdminRewardLadderLevelCreate,
        ): AdminRewardLadderLevel {
            val id = ids.incrementAndGet()
            val level =
                AdminRewardLadderLevel(
                    id = id,
                    clubId = clubId,
                    metricType = request.metricType,
                    threshold = request.threshold,
                    windowDays = request.windowDays,
                    prizeId = request.prizeId,
                    enabled = request.enabled,
                    orderIndex = request.orderIndex,
                    createdAt = now,
                    updatedAt = now,
                )
            storage[id] = level
            return level
        }

        override suspend fun update(
            clubId: Long,
            request: AdminRewardLadderLevelUpdate,
        ): AdminRewardLadderLevel? {
            val existing = storage[request.id] ?: return null
            if (existing.clubId != clubId) return null
            val updated =
                existing.copy(
                    metricType = request.metricType,
                    threshold = request.threshold,
                    windowDays = request.windowDays,
                    prizeId = request.prizeId,
                    enabled = request.enabled,
                    orderIndex = request.orderIndex,
                    updatedAt = now,
                )
            storage[request.id] = updated
            return updated
        }

        override suspend fun delete(clubId: Long, id: Long): Boolean =
            storage[id]?.takeIf { it.clubId == clubId }?.let { storage.remove(id); true } ?: false
    }

    companion object {
        private const val NO_STORE = "no-store"
    }
}
