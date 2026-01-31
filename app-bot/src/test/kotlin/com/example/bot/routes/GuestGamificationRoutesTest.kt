package com.example.bot.routes

import com.example.bot.audit.AuditLogRepository
import com.example.bot.clubs.Club
import com.example.bot.clubs.InMemoryClubsRepository
import com.example.bot.data.gamification.Badge
import com.example.bot.data.gamification.CouponStatus
import com.example.bot.data.gamification.Prize
import com.example.bot.data.gamification.RewardCoupon
import com.example.bot.data.gamification.RewardLadderLevel
import com.example.bot.data.gamification.UserBadge
import com.example.bot.data.security.Role
import com.example.bot.data.security.User
import com.example.bot.data.security.UserRepository
import com.example.bot.data.security.UserRoleRepository
import com.example.bot.gamification.GamificationReadRepository
import com.example.bot.gamification.GuestGamificationService
import com.example.bot.gamification.VisitMetricsRepository
import com.example.bot.plugins.TelegramMiniUser
import com.example.bot.plugins.overrideMiniAppValidatorForTesting
import com.example.bot.plugins.resetMiniAppValidator
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GuestGamificationRoutesTest {
    private val now = Instant.parse("2026-01-29T12:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private val telegramId = 777L
    private val userId = 5001L
    private val clubId = 123L
    private val json = Json

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
    fun `401 without initData`() = withGamificationApp { _ ->
        val response = client.get("/api/me/clubs/$clubId/gamification")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `200 returns totals rewards badges coupons with headers`() = withGamificationApp { repo ->
        val response = client.get("/api/me/clubs/$clubId/gamification") { header("X-Telegram-Init-Data", "init") }

        assertEquals(HttpStatusCode.OK, response.status)
        response.assertNoStoreHeaders()
        assertEquals(2, repo.visitsSince.size)
        assertEquals(now.minus(Duration.ofDays(30)), repo.visitsSince.first { it != null })
        assertEquals(now.minus(Duration.ofDays(10)), repo.earlySince.first { it != null })
        assertEquals(now.minus(Duration.ofDays(14)), repo.tableSince.first { it != null })

        val body = json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(clubId, body["clubId"]!!.jsonPrimitive.long)
        assertEquals(now.toString(), body["nowUtc"]!!.jsonPrimitive.content)

        val totals = body["totals"]!!.jsonObject
        assertEquals(12, totals["visitsAllTime"]!!.jsonPrimitive.long)
        assertEquals(7, totals["visitsInWindow"]!!.jsonPrimitive.long)
        assertEquals(2, totals["earlyInWindow"]!!.jsonPrimitive.long)
        assertEquals(1, totals["tableNightsInWindow"]!!.jsonPrimitive.long)

        val rewards = body["nextRewards"]!!.jsonArray
        assertEquals(1, rewards.size)
        val reward = rewards.first().jsonObject
        assertEquals("VISITS", reward["metricType"]!!.jsonPrimitive.content)
        assertEquals(10, reward["threshold"]!!.jsonPrimitive.int)
        assertEquals(30, reward["windowDays"]!!.jsonPrimitive.int)
        assertEquals(7, reward["current"]!!.jsonPrimitive.long)
        assertEquals(3, reward["remaining"]!!.jsonPrimitive.long)
        val prize = reward["prize"]!!.jsonObject
        assertEquals(1, prize["id"]!!.jsonPrimitive.long)
        assertEquals("VISIT_10", prize["code"]!!.jsonPrimitive.content)
        assertEquals("10 визитов", prize["titleRu"]!!.jsonPrimitive.content)

        val badges = body["badges"]!!.jsonArray
        assertEquals(2, badges.size)
        val earlyBadge = badges.first { it.jsonObject["code"]!!.jsonPrimitive.content == "EARLY_3" }.jsonObject
        assertEquals("Ранний гость", earlyBadge["nameRu"]!!.jsonPrimitive.content)
        assertEquals("2026-01-10T10:00:00Z", earlyBadge["earnedAt"]!!.jsonPrimitive.content)
        val unearnedBadge = badges.first { it.jsonObject["code"]!!.jsonPrimitive.content == "VISIT_5" }.jsonObject
        assertTrue(unearnedBadge["earnedAt"] == null || unearnedBadge["earnedAt"] is JsonNull)

        val coupons = body["coupons"]!!.jsonArray
        assertEquals(1, coupons.size)
        val coupon = coupons.first().jsonObject
        assertEquals("AVAILABLE", coupon["status"]!!.jsonPrimitive.content)
        assertEquals("2026-01-15T12:00:00Z", coupon["issuedAt"]!!.jsonPrimitive.content)
        assertEquals("2026-02-14T12:00:00Z", coupon["expiresAt"]!!.jsonPrimitive.content)
        val couponPrize = coupon["prize"]!!.jsonObject
        assertEquals("FREE_BAR", couponPrize["code"]!!.jsonPrimitive.content)
    }

    private fun withGamificationApp(
        block: suspend ApplicationTestBuilder.(FakeVisitMetricsRepository) -> Unit,
    ) = testApplication {
        val readRepo = FakeGamificationReadRepository()
        val visitRepo = FakeVisitMetricsRepository(now)
        val clubsRepository = InMemoryClubsRepository(listOf(club()))
        val service =
            GuestGamificationService(
                readRepository = readRepo,
                visitMetricsRepository = visitRepo,
                clock = clock,
            )
        application {
            install(ContentNegotiation) { json() }
            install(RbacPlugin) {
                userRepository = StubUserRepository(userId = userId, telegramId = telegramId)
                userRoleRepository = StubUserRoleRepository(roles = setOf(Role.GUEST), clubIds = setOf(clubId))
                auditLogRepository = relaxedAuditRepository()
                principalExtractor = { TelegramPrincipal(telegramId, "tester") }
            }
            guestGamificationRoutes(
                clubsRepository = clubsRepository,
                gamificationService = service,
                botTokenProvider = { "test" },
            )
        }
        block(visitRepo)
    }

    private fun club(): Club =
        Club(
            id = clubId,
            city = "Moscow",
            name = "Club",
            genres = emptyList(),
            tags = emptyList(),
            logoUrl = null,
        )

    private class FakeGamificationReadRepository : GamificationReadRepository {
        override suspend fun listEnabledBadges(clubId: Long): List<Badge> =
            listOf(
                Badge(
                    id = 1,
                    clubId = clubId,
                    code = "EARLY_3",
                    nameRu = "Ранний гость",
                    icon = "early.png",
                    enabled = true,
                    conditionType = "EARLY",
                    threshold = 3,
                    windowDays = 30,
                    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                    updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
                ),
                Badge(
                    id = 2,
                    clubId = clubId,
                    code = "VISIT_5",
                    nameRu = "Первые визиты",
                    icon = null,
                    enabled = true,
                    conditionType = "VISITS",
                    threshold = 5,
                    windowDays = 30,
                    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                    updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
                ),
            )

        override suspend fun listUserBadges(clubId: Long, userId: Long): List<UserBadge> =
            listOf(
                UserBadge(
                    id = 10,
                    clubId = clubId,
                    userId = userId,
                    badgeId = 1,
                    earnedAt = Instant.parse("2026-01-10T10:00:00Z"),
                    fingerprint = "fp",
                    createdAt = Instant.parse("2026-01-10T10:00:00Z"),
                ),
            )

        override suspend fun listEnabledRewardLevels(clubId: Long): List<RewardLadderLevel> =
            listOf(
                RewardLadderLevel(
                    id = 100,
                    clubId = clubId,
                    metricType = "VISITS",
                    threshold = 5,
                    windowDays = 30,
                    prizeId = 1,
                    enabled = true,
                    orderIndex = 0,
                    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                    updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
                ),
                RewardLadderLevel(
                    id = 101,
                    clubId = clubId,
                    metricType = "VISITS",
                    threshold = 10,
                    windowDays = 30,
                    prizeId = 1,
                    enabled = true,
                    orderIndex = 1,
                    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                    updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
                ),
                RewardLadderLevel(
                    id = 200,
                    clubId = clubId,
                    metricType = "EARLY_VISITS",
                    threshold = 2,
                    windowDays = 10,
                    prizeId = 2,
                    enabled = true,
                    orderIndex = 0,
                    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                    updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
                ),
                RewardLadderLevel(
                    id = 300,
                    clubId = clubId,
                    metricType = "TABLE_NIGHTS",
                    threshold = 1,
                    windowDays = 14,
                    prizeId = 3,
                    enabled = true,
                    orderIndex = 0,
                    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                    updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
                ),
            )

        override suspend fun listPrizes(clubId: Long, prizeIds: Set<Long>): List<Prize> =
            listOf(
                Prize(
                    id = 1,
                    clubId = clubId,
                    code = "VISIT_10",
                    titleRu = "10 визитов",
                    description = "Подарок",
                    terms = "Только для гостей",
                    enabled = true,
                    limitTotal = null,
                    expiresInDays = null,
                    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                    updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
                ),
                Prize(
                    id = 2,
                    clubId = clubId,
                    code = "FREE_BAR",
                    titleRu = "Бар бесплатно",
                    description = "Один напиток",
                    terms = null,
                    enabled = true,
                    limitTotal = null,
                    expiresInDays = null,
                    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                    updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
                ),
                Prize(
                    id = 3,
                    clubId = clubId,
                    code = "TABLE",
                    titleRu = "Стол",
                    description = null,
                    terms = null,
                    enabled = true,
                    limitTotal = null,
                    expiresInDays = null,
                    createdAt = Instant.parse("2026-01-01T00:00:00Z"),
                    updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
                ),
            ).filter { it.id in prizeIds }

        override suspend fun listCoupons(
            clubId: Long,
            userId: Long,
            statuses: Set<CouponStatus>,
        ): List<RewardCoupon> {
            if (CouponStatus.AVAILABLE !in statuses) return emptyList()
            return listOf(
                RewardCoupon(
                    id = 500,
                    clubId = clubId,
                    userId = userId,
                    prizeId = 2,
                    status = CouponStatus.AVAILABLE,
                    reasonCode = null,
                    fingerprint = "coupon",
                    issuedAt = Instant.parse("2026-01-15T12:00:00Z"),
                    expiresAt = Instant.parse("2026-02-14T12:00:00Z"),
                    redeemedAt = null,
                    issuedBy = null,
                    redeemedBy = null,
                    createdAt = Instant.parse("2026-01-15T12:00:00Z"),
                    updatedAt = Instant.parse("2026-01-15T12:00:00Z"),
                ),
            )
        }
    }

    private class FakeVisitMetricsRepository(private val now: Instant) : VisitMetricsRepository {
        val visitsSince = mutableListOf<Instant?>()
        val earlySince = mutableListOf<Instant?>()
        val tableSince = mutableListOf<Instant?>()

        override suspend fun countVisits(userId: Long, clubId: Long, sinceUtc: Instant?): Long {
            visitsSince += sinceUtc
            return when (sinceUtc) {
                null -> 12
                now.minus(Duration.ofDays(30)) -> 7
                else -> 0
            }
        }

        override suspend fun countEarlyVisits(userId: Long, clubId: Long, sinceUtc: Instant?): Long {
            earlySince += sinceUtc
            return when (sinceUtc) {
                null -> 4
                now.minus(Duration.ofDays(10)) -> 2
                else -> 0
            }
        }

        override suspend fun countTableNights(userId: Long, clubId: Long, sinceUtc: Instant?): Long {
            tableSince += sinceUtc
            return when (sinceUtc) {
                null -> 1
                now.minus(Duration.ofDays(14)) -> 1
                else -> 0
            }
        }
    }

    private class StubUserRepository(
        private val userId: Long,
        private val telegramId: Long,
    ) : UserRepository {
        override suspend fun getByTelegramId(id: Long): User? =
            User(id = userId, telegramId = telegramId, username = "tester")

        override suspend fun getById(id: Long): User? =
            User(id = userId, telegramId = telegramId, username = "tester")
    }

    private class StubUserRoleRepository(
        private val roles: Set<Role>,
        private val clubIds: Set<Long>,
    ) : UserRoleRepository {
        override suspend fun listRoles(userId: Long): Set<Role> = roles

        override suspend fun listClubIdsFor(userId: Long): Set<Long> = clubIds
    }

    private fun io.ktor.client.statement.HttpResponse.assertNoStoreHeaders() {
        assertEquals("no-store", headers[HttpHeaders.CacheControl])
        assertEquals("X-Telegram-Init-Data", headers[HttpHeaders.Vary])
    }

    private fun relaxedAuditRepository(): AuditLogRepository = io.mockk.mockk(relaxed = true)
}
