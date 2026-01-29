package com.example.bot.gamification

import com.example.bot.audit.AuditLogger
import java.time.Duration
import java.time.Instant

class GamificationEngine(
    private val settingsRepository: GamificationSettingsRepository,
    private val badgeRepository: BadgeRepository,
    private val userBadgeRepository: UserBadgeRepository,
    private val rewardLadderRepository: RewardLadderRepository,
    private val prizeRepository: PrizeRepository,
    private val couponRepository: CouponRepository,
    private val visitMetricsRepository: VisitMetricsRepository,
    private val auditLogger: AuditLogger,
) {
    suspend fun onVisitCreated(
        visit: ClubVisit,
        now: Instant,
    ): GamificationDelta {
        val settings = settingsRepository.getByClubId(visit.clubId) ?: return GamificationDelta()
        val earnedBadges = mutableListOf<BadgeEarned>()
        val issuedCoupons = mutableListOf<CouponIssued>()
        val metricCounts = mutableMapOf<MetricKey, Long>()

        if (settings.badgesEnabled) {
            val enabledBadges = badgeRepository.listEnabled(visit.clubId)
            for (badge in enabledBadges) {
                val metricType = MetricType.fromBadgeCondition(badge.conditionType) ?: continue
                if (!metricType.isEnabled(settings)) continue
                val windowDays = badge.windowDays?.takeIf { it > 0 }
                val count = metricCounts.getOrPut(MetricKey(metricType, windowDays)) {
                    loadMetricCount(metricType, visit, windowDays, now)
                }
                if (count < badge.threshold) continue
                val fingerprint = badgeFingerprint(visit.clubId, visit.userId, badge.id)
                val result =
                    userBadgeRepository.tryEarn(
                        fingerprint = fingerprint,
                        clubId = visit.clubId,
                        userId = visit.userId,
                        badgeId = badge.id,
                        earnedAt = now,
                    )
                if (result.created) {
                    earnedBadges += BadgeEarned(badgeId = badge.id, earnedAt = result.badge.earnedAt)
                    auditLogger.badgeEarned(
                        clubId = visit.clubId,
                        userId = visit.userId,
                        badgeId = badge.id,
                        fingerprint = fingerprint,
                        conditionType = badge.conditionType,
                        threshold = badge.threshold,
                        windowDays = windowDays,
                        earnedAt = result.badge.earnedAt,
                    )
                }
            }
        }

        if (settings.prizesEnabled) {
            val levels = rewardLadderRepository.listEnabledLevels(visit.clubId)
            if (levels.isNotEmpty()) {
                val prizeIds = levels.mapTo(mutableSetOf()) { it.prizeId }
                val prizes = prizeRepository.listByIds(visit.clubId, prizeIds).associateBy { it.id }
                for (level in levels) {
                    val metricType = MetricType.fromMetricType(level.metricType) ?: continue
                    if (!metricType.isEnabled(settings)) continue
                    val prize = prizes[level.prizeId]?.takeIf { it.enabled } ?: continue
                    val windowDays = level.windowDays.takeIf { it > 0 }
                    val count = metricCounts.getOrPut(MetricKey(metricType, windowDays)) {
                        loadMetricCount(metricType, visit, windowDays, now)
                    }
                    if (count < level.threshold) continue
                    val fingerprint =
                        couponFingerprint(
                            clubId = visit.clubId,
                            userId = visit.userId,
                            metricType = metricType.dbValue,
                            threshold = level.threshold,
                            windowDays = windowDays,
                        )
                    val expiresAt =
                        prize.expiresInDays?.takeIf { it > 0 }?.let { days ->
                            now.plus(Duration.ofDays(days.toLong()))
                        }
                    val result =
                        couponRepository.tryIssue(
                            fingerprint = fingerprint,
                            clubId = visit.clubId,
                            userId = visit.userId,
                            prizeId = prize.id,
                            issuedAt = now,
                            expiresAt = expiresAt,
                        )
                    if (result.created) {
                        issuedCoupons +=
                            CouponIssued(
                                couponId = result.coupon.id,
                                prizeId = result.coupon.prizeId,
                                issuedAt = result.coupon.issuedAt,
                                expiresAt = result.coupon.expiresAt,
                            )
                        auditLogger.couponIssued(
                            clubId = visit.clubId,
                            userId = visit.userId,
                            couponId = result.coupon.id,
                            prizeId = prize.id,
                            fingerprint = fingerprint,
                            metricType = metricType.dbValue,
                            threshold = level.threshold,
                            windowDays = windowDays,
                            issuedAt = result.coupon.issuedAt,
                            expiresAt = result.coupon.expiresAt,
                        )
                    }
                }
            }
        }

        return GamificationDelta(
            earnedBadges = earnedBadges,
            issuedCoupons = issuedCoupons,
            nextRewardsPreview = null,
        )
    }

    private suspend fun loadMetricCount(
        metricType: MetricType,
        visit: ClubVisit,
        windowDays: Int?,
        now: Instant,
    ): Long {
        val sinceUtc = windowDays?.let { now.minus(Duration.ofDays(it.toLong())) }
        return when (metricType) {
            MetricType.VISITS ->
                visitMetricsRepository.countVisits(visit.userId, visit.clubId, sinceUtc)
            MetricType.EARLY_VISITS ->
                visitMetricsRepository.countEarlyVisits(visit.userId, visit.clubId, sinceUtc)
            MetricType.TABLE_NIGHTS ->
                visitMetricsRepository.countTableNights(visit.userId, visit.clubId, sinceUtc)
        }
    }

    private fun badgeFingerprint(
        clubId: Long,
        userId: Long,
        badgeId: Long,
    ): String = "club:$clubId:user:$userId:badge:$badgeId"

    private fun couponFingerprint(
        clubId: Long,
        userId: Long,
        metricType: String,
        threshold: Int,
        windowDays: Int?,
    ): String =
        "club:$clubId:user:$userId:metric:${metricType.uppercase()}:threshold:$threshold:window:${windowDays ?: "ALL"}"

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

        fun isEnabled(settings: ClubGamificationSettings): Boolean =
            when (this) {
                VISITS -> settings.stampsEnabled
                EARLY_VISITS -> settings.earlyEnabled
                TABLE_NIGHTS -> settings.tablesLoyaltyEnabled
            }

        companion object {
            fun fromBadgeCondition(conditionType: String): MetricType? {
                val normalized = conditionType.trim().uppercase()
                return entries.firstOrNull { normalized in it.aliases }
            }

            fun fromMetricType(metricType: String): MetricType? {
                val normalized = metricType.trim().uppercase()
                return entries.firstOrNull { normalized == it.dbValue || normalized in it.aliases }
            }
        }
    }
}
