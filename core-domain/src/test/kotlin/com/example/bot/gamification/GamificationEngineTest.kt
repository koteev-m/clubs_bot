package com.example.bot.gamification

import com.example.bot.audit.AuditLogEvent
import com.example.bot.audit.AuditLogRecord
import com.example.bot.audit.AuditLogRepository
import com.example.bot.audit.AuditLogger
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GamificationEngineTest {
    private val now = Instant.parse("2024-07-01T10:00:00Z")

    @Test
    fun `repeat visit does not issue duplicate coupon`() = runBlocking {
        val settings =
            ClubGamificationSettings(
                clubId = 1,
                stampsEnabled = true,
                earlyEnabled = false,
                badgesEnabled = false,
                prizesEnabled = true,
                contestsEnabled = false,
                tablesLoyaltyEnabled = false,
                earlyWindowMinutes = null,
            )
        val rewardLevel =
            RewardLadderLevel(
                id = 1,
                clubId = 1,
                metricType = "VISITS",
                threshold = 1,
                windowDays = 0,
                prizeId = 10,
                enabled = true,
                orderIndex = 0,
            )
        val prize =
            Prize(
                id = 10,
                clubId = 1,
                code = "WELCOME",
                enabled = true,
                expiresInDays = null,
            )
        val engine =
            GamificationEngine(
                settingsRepository = FakeSettingsRepository(settings),
                badgeRepository = FakeBadgeRepository(emptyList()),
                userBadgeRepository = FakeUserBadgeRepository(),
                rewardLadderRepository = FakeRewardLadderRepository(listOf(rewardLevel)),
                prizeRepository = FakePrizeRepository(listOf(prize)),
                couponRepository = FakeCouponRepository(),
                visitMetricsRepository = FakeVisitMetricsRepository(visits = 1),
                auditLogger = AuditLogger(FakeAuditLogRepository()),
            )
        val visit =
            ClubVisit(
                id = 100,
                clubId = 1,
                nightStartUtc = now.minusSeconds(3600),
                eventId = 50,
                userId = 200,
                firstCheckinAt = now,
                isEarly = false,
                hasTable = false,
            )

        val first = engine.onVisitCreated(visit, now)
        val second = engine.onVisitCreated(visit, now)

        assertEquals(1, first.issuedCoupons.size)
        assertTrue(second.issuedCoupons.isEmpty())
    }

    @Test
    fun `early badge obeys toggle`() = runBlocking {
        val badge =
            Badge(
                id = 5,
                clubId = 1,
                code = "EARLY",
                conditionType = "EARLY_VISIT",
                threshold = 1,
                windowDays = null,
                enabled = true,
            )
        val visit =
            ClubVisit(
                id = 101,
                clubId = 1,
                nightStartUtc = now.minusSeconds(3600),
                eventId = 50,
                userId = 200,
                firstCheckinAt = now,
                isEarly = true,
                hasTable = false,
            )

        val disabledEngine =
            GamificationEngine(
                settingsRepository =
                    FakeSettingsRepository(
                        ClubGamificationSettings(
                            clubId = 1,
                            stampsEnabled = true,
                            earlyEnabled = false,
                            badgesEnabled = true,
                            prizesEnabled = false,
                            contestsEnabled = false,
                            tablesLoyaltyEnabled = false,
                            earlyWindowMinutes = null,
                        ),
                    ),
                badgeRepository = FakeBadgeRepository(listOf(badge)),
                userBadgeRepository = FakeUserBadgeRepository(),
                rewardLadderRepository = FakeRewardLadderRepository(emptyList()),
                prizeRepository = FakePrizeRepository(emptyList()),
                couponRepository = FakeCouponRepository(),
                visitMetricsRepository = FakeVisitMetricsRepository(earlyVisits = 1),
                auditLogger = AuditLogger(FakeAuditLogRepository()),
            )

        val disabledDelta = disabledEngine.onVisitCreated(visit, now)
        assertTrue(disabledDelta.earnedBadges.isEmpty())
    }

    @Test
    fun `badge earning is idempotent`() = runBlocking {
        val badge =
            Badge(
                id = 7,
                clubId = 1,
                code = "VISIT",
                conditionType = "VISITS",
                threshold = 1,
                windowDays = null,
                enabled = true,
            )
        val userBadgeRepo = FakeUserBadgeRepository()
        val engine =
            GamificationEngine(
                settingsRepository =
                    FakeSettingsRepository(
                        ClubGamificationSettings(
                            clubId = 1,
                            stampsEnabled = true,
                            earlyEnabled = false,
                            badgesEnabled = true,
                            prizesEnabled = false,
                            contestsEnabled = false,
                            tablesLoyaltyEnabled = false,
                            earlyWindowMinutes = null,
                        ),
                    ),
                badgeRepository = FakeBadgeRepository(listOf(badge)),
                userBadgeRepository = userBadgeRepo,
                rewardLadderRepository = FakeRewardLadderRepository(emptyList()),
                prizeRepository = FakePrizeRepository(emptyList()),
                couponRepository = FakeCouponRepository(),
                visitMetricsRepository = FakeVisitMetricsRepository(visits = 1),
                auditLogger = AuditLogger(FakeAuditLogRepository()),
            )
        val visit =
            ClubVisit(
                id = 102,
                clubId = 1,
                nightStartUtc = now.minusSeconds(3600),
                eventId = 50,
                userId = 200,
                firstCheckinAt = now,
                isEarly = false,
                hasTable = false,
            )

        val first = engine.onVisitCreated(visit, now)
        val second = engine.onVisitCreated(visit, now)

        assertEquals(1, first.earnedBadges.size)
        assertTrue(second.earnedBadges.isEmpty())
        assertEquals(1, userBadgeRepo.issued.size)
    }
}

private class FakeSettingsRepository(
    private val settings: ClubGamificationSettings?,
) : GamificationSettingsRepository {
    override suspend fun getByClubId(clubId: Long): ClubGamificationSettings? = settings
}

private class FakeBadgeRepository(
    private val badges: List<Badge>,
) : BadgeRepository {
    override suspend fun listEnabled(clubId: Long): List<Badge> = badges.filter { it.enabled }
}

private class FakeUserBadgeRepository : UserBadgeRepository {
    private var seq = 1L
    val issued = mutableMapOf<String, UserBadge>()

    override suspend fun tryEarn(
        fingerprint: String,
        clubId: Long,
        userId: Long,
        badgeId: Long,
        earnedAt: Instant,
    ): BadgeEarnResult {
        val existing = issued[fingerprint]
        if (existing != null) {
            return BadgeEarnResult(existing, created = false)
        }
        val badge =
            UserBadge(
                id = seq++,
                clubId = clubId,
                userId = userId,
                badgeId = badgeId,
                earnedAt = earnedAt,
                fingerprint = fingerprint,
            )
        issued[fingerprint] = badge
        return BadgeEarnResult(badge, created = true)
    }
}

private class FakeRewardLadderRepository(
    private val levels: List<RewardLadderLevel>,
) : RewardLadderRepository {
    override suspend fun listEnabledLevels(clubId: Long): List<RewardLadderLevel> =
        levels.filter { it.enabled }
}

private class FakePrizeRepository(
    private val prizes: List<Prize>,
) : PrizeRepository {
    override suspend fun listByIds(clubId: Long, prizeIds: Set<Long>): List<Prize> =
        prizes.filter { it.id in prizeIds && it.clubId == clubId }
}

private class FakeCouponRepository : CouponRepository {
    private var seq = 1L
    private val issued = mutableMapOf<String, RewardCoupon>()

    override suspend fun tryIssue(
        fingerprint: String,
        clubId: Long,
        userId: Long,
        prizeId: Long,
        issuedAt: Instant,
        expiresAt: Instant?,
        issuedBy: Long?,
    ): CouponIssueResult {
        val existing = issued[fingerprint]
        if (existing != null) {
            return CouponIssueResult(existing, created = false)
        }
        val coupon =
            RewardCoupon(
                id = seq++,
                clubId = clubId,
                userId = userId,
                prizeId = prizeId,
                fingerprint = fingerprint,
                issuedAt = issuedAt,
                expiresAt = expiresAt,
            )
        issued[fingerprint] = coupon
        return CouponIssueResult(coupon, created = true)
    }
}

private class FakeVisitMetricsRepository(
    private val visits: Long = 0,
    private val earlyVisits: Long = 0,
    private val tableNights: Long = 0,
) : VisitMetricsRepository {
    override suspend fun countVisits(userId: Long, clubId: Long, sinceUtc: Instant?): Long = visits

    override suspend fun countEarlyVisits(userId: Long, clubId: Long, sinceUtc: Instant?): Long = earlyVisits

    override suspend fun countTableNights(userId: Long, clubId: Long, sinceUtc: Instant?): Long = tableNights
}

private class FakeAuditLogRepository : AuditLogRepository {
    override suspend fun append(event: AuditLogEvent): Long = 1L

    override suspend fun listForClub(clubId: Long, limit: Int, offset: Int): List<AuditLogRecord> = emptyList()

    override suspend fun listForUser(userId: Long, limit: Int, offset: Int): List<AuditLogRecord> = emptyList()
}
