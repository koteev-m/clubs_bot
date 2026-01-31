package com.example.bot.gamification

import com.example.bot.data.gamification.Badge as DataBadge
import com.example.bot.data.gamification.CouponStatus as DataCouponStatus
import com.example.bot.data.gamification.Prize as DataPrize
import com.example.bot.data.gamification.RewardCoupon as DataRewardCoupon
import com.example.bot.data.gamification.RewardLadderLevel as DataRewardLadderLevel
import com.example.bot.data.gamification.UserBadge as DataUserBadge
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlinx.serialization.Serializable

interface GamificationReadRepository {
    suspend fun listEnabledBadges(clubId: Long): List<DataBadge>

    suspend fun listUserBadges(
        clubId: Long,
        userId: Long,
    ): List<DataUserBadge>

    suspend fun listEnabledRewardLevels(clubId: Long): List<DataRewardLadderLevel>

    suspend fun listPrizes(
        clubId: Long,
        prizeIds: Set<Long>,
    ): List<DataPrize>

    suspend fun listCoupons(
        clubId: Long,
        userId: Long,
        statuses: Set<DataCouponStatus>,
    ): List<DataRewardCoupon>
}

class DefaultGamificationReadRepository(
    private val badgeRepository: com.example.bot.data.gamification.BadgeRepository,
    private val userBadgeRepository: com.example.bot.data.gamification.UserBadgeRepository,
    private val rewardLadderRepository: com.example.bot.data.gamification.RewardLadderRepository,
    private val prizeRepository: com.example.bot.data.gamification.PrizeRepository,
    private val couponRepository: com.example.bot.data.gamification.CouponRepository,
) : GamificationReadRepository {
    override suspend fun listEnabledBadges(clubId: Long): List<DataBadge> = badgeRepository.listEnabled(clubId)

    override suspend fun listUserBadges(clubId: Long, userId: Long): List<DataUserBadge> =
        userBadgeRepository.listForUser(clubId, userId)

    override suspend fun listEnabledRewardLevels(clubId: Long): List<DataRewardLadderLevel> =
        rewardLadderRepository.listEnabledLevels(clubId)

    override suspend fun listPrizes(clubId: Long, prizeIds: Set<Long>): List<DataPrize> =
        prizeRepository.listByIds(clubId, prizeIds)

    override suspend fun listCoupons(
        clubId: Long,
        userId: Long,
        statuses: Set<DataCouponStatus>,
    ): List<DataRewardCoupon> = couponRepository.listForUser(clubId, userId, statuses)
}

@Serializable
data class GuestGamificationResponse(
    val clubId: Long,
    val nowUtc: String,
    val totals: GuestGamificationTotals,
    val nextRewards: List<GuestGamificationReward>,
    val badges: List<GuestGamificationBadge>,
    val coupons: List<GuestGamificationCoupon>,
)

@Serializable
data class GuestGamificationTotals(
    val visitsAllTime: Long,
    val visitsInWindow: Long,
    val earlyInWindow: Long,
    val tableNightsInWindow: Long,
)

@Serializable
data class GuestGamificationReward(
    val metricType: String,
    val threshold: Int,
    val windowDays: Int,
    val current: Long,
    val remaining: Long,
    val prize: GuestGamificationPrize,
)

@Serializable
data class GuestGamificationBadge(
    val code: String,
    val nameRu: String,
    val icon: String? = null,
    val earnedAt: String? = null,
)

@Serializable
data class GuestGamificationCoupon(
    val id: Long,
    val status: String,
    val issuedAt: String,
    val expiresAt: String? = null,
    val prize: GuestGamificationPrize,
)

@Serializable
data class GuestGamificationPrize(
    val id: Long,
    val code: String,
    val titleRu: String,
    val description: String? = null,
    val terms: String? = null,
)

class GuestGamificationService(
    private val readRepository: GamificationReadRepository,
    private val visitMetricsRepository: VisitMetricsRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun load(
        clubId: Long,
        userId: Long,
        couponStatuses: Set<DataCouponStatus> = setOf(DataCouponStatus.AVAILABLE),
    ): GuestGamificationResponse {
        val now = Instant.now(clock)
        val badges = readRepository.listEnabledBadges(clubId)
        val userBadges = readRepository.listUserBadges(clubId, userId).associateBy { it.badgeId }
        val rewardLevels = readRepository.listEnabledRewardLevels(clubId)
        val coupons = readRepository.listCoupons(clubId, userId, couponStatuses)
        val prizeIds = (rewardLevels.map { it.prizeId } + coupons.map { it.prizeId }).toSet()
        val prizes =
            if (prizeIds.isEmpty()) {
                emptyMap()
            } else {
                readRepository.listPrizes(clubId, prizeIds).associateBy { it.id }
            }

        val counts = mutableMapOf<MetricKey, Long>()
        suspend fun metricCount(metricType: MetricType, windowDays: Int?): Long {
            val normalizedWindow = windowDays?.takeIf { it > 0 }
            return counts.getOrPut(MetricKey(metricType, normalizedWindow)) {
                val sinceUtc = normalizedWindow?.let { now.minus(Duration.ofDays(it.toLong())) }
                when (metricType) {
                    MetricType.VISITS -> visitMetricsRepository.countVisits(userId, clubId, sinceUtc)
                    MetricType.EARLY_VISITS -> visitMetricsRepository.countEarlyVisits(userId, clubId, sinceUtc)
                    MetricType.TABLE_NIGHTS -> visitMetricsRepository.countTableNights(userId, clubId, sinceUtc)
                }
            }
        }

        val totals =
            GuestGamificationTotals(
                visitsAllTime = metricCount(MetricType.VISITS, null),
                visitsInWindow = metricCount(MetricType.VISITS, maxWindowDays(rewardLevels, MetricType.VISITS)),
                earlyInWindow = metricCount(MetricType.EARLY_VISITS, maxWindowDays(rewardLevels, MetricType.EARLY_VISITS)),
                tableNightsInWindow = metricCount(MetricType.TABLE_NIGHTS, maxWindowDays(rewardLevels, MetricType.TABLE_NIGHTS)),
            )

        val nextRewards =
            MetricType.entries
                .mapNotNull { metricType ->
                    val level = nextLevelFor(metricType, rewardLevels, ::metricCount, prizes) ?: return@mapNotNull null
                    val current = metricCount(metricType, level.windowDays)
                    val prize = prizes[level.prizeId] ?: return@mapNotNull null
                    GuestGamificationReward(
                        metricType = metricType.dbValue,
                        threshold = level.threshold,
                        windowDays = level.windowDays,
                        current = current,
                        remaining = (level.threshold - current).coerceAtLeast(0),
                        prize = prize.toPrizeResponse(),
                    )
                }

        val badgePayload =
            badges.map { badge ->
                GuestGamificationBadge(
                    code = badge.code,
                    nameRu = badge.nameRu,
                    icon = badge.icon,
                    earnedAt = userBadges[badge.id]?.earnedAt?.toString(),
                )
            }

        val couponPayload =
            coupons.mapNotNull { coupon ->
                val prize = prizes[coupon.prizeId] ?: return@mapNotNull null
                GuestGamificationCoupon(
                    id = coupon.id,
                    status = coupon.status.name,
                    issuedAt = coupon.issuedAt.toString(),
                    expiresAt = coupon.expiresAt?.toString(),
                    prize = prize.toPrizeResponse(),
                )
            }

        return GuestGamificationResponse(
            clubId = clubId,
            nowUtc = now.toString(),
            totals = totals,
            nextRewards = nextRewards,
            badges = badgePayload,
            coupons = couponPayload,
        )
    }

    private fun maxWindowDays(levels: List<DataRewardLadderLevel>, metricType: MetricType): Int? =
        levels
            .asSequence()
            .filter { metricType.matches(it.metricType) }
            .map { it.windowDays }
            .maxOrNull()
            ?.takeIf { it > 0 }

    private suspend fun nextLevelFor(
        metricType: MetricType,
        levels: List<DataRewardLadderLevel>,
        metricCount: suspend (MetricType, Int?) -> Long,
        prizes: Map<Long, DataPrize>,
    ): DataRewardLadderLevel? {
        val sortedLevels =
            levels
                .asSequence()
                .filter { metricType.matches(it.metricType) }
                .sortedWith(compareBy<DataRewardLadderLevel> { it.orderIndex }.thenBy { it.threshold })
                .toList()
        for (level in sortedLevels) {
            if (!metricType.matches(level.metricType)) continue
            val prize = prizes[level.prizeId] ?: continue
            if (!prize.enabled) continue
            val current = metricCount(metricType, level.windowDays)
            if (current < level.threshold) return level
        }
        return null
    }

    private fun MetricType.matches(value: String): Boolean = MetricType.fromMetricType(value) == this

    private fun DataPrize.toPrizeResponse(): GuestGamificationPrize =
        GuestGamificationPrize(
            id = id,
            code = code,
            titleRu = titleRu,
            description = description,
            terms = terms,
        )

    private data class MetricKey(
        val metricType: MetricType,
        val windowDays: Int?,
    )

    private enum class MetricType(
        val dbValue: String,
        private val aliases: Set<String>,
    ) {
        VISITS("VISITS", setOf("VISITS", "VISIT", "STAMPS")),
        EARLY_VISITS("EARLY_VISITS", setOf("EARLY_VISITS", "EARLY_VISIT", "EARLY")),
        TABLE_NIGHTS("TABLE_NIGHTS", setOf("TABLE_NIGHTS", "TABLE_VISITS", "TABLE_VISIT", "TABLE")),
        ;

        companion object {
            fun fromMetricType(metricType: String): MetricType? {
                val normalized = metricType.trim().uppercase(Locale.ROOT)
                return entries.firstOrNull { normalized == it.dbValue || normalized in it.aliases }
            }
        }
    }
}
