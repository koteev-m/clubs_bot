package com.example.bot.gamification

import java.time.Instant

data class ClubVisit(
    val id: Long,
    val clubId: Long,
    val nightStartUtc: Instant,
    val eventId: Long?,
    val userId: Long,
    val firstCheckinAt: Instant,
    val isEarly: Boolean,
    val hasTable: Boolean,
)

data class ClubGamificationSettings(
    val clubId: Long,
    val stampsEnabled: Boolean,
    val earlyEnabled: Boolean,
    val badgesEnabled: Boolean,
    val prizesEnabled: Boolean,
    val contestsEnabled: Boolean,
    val tablesLoyaltyEnabled: Boolean,
    val earlyWindowMinutes: Int?,
)

data class Badge(
    val id: Long,
    val clubId: Long,
    val code: String,
    val conditionType: String,
    val threshold: Int,
    val windowDays: Int?,
    val enabled: Boolean,
)

data class UserBadge(
    val id: Long,
    val clubId: Long,
    val userId: Long,
    val badgeId: Long,
    val earnedAt: Instant,
    val fingerprint: String,
)

data class RewardLadderLevel(
    val id: Long,
    val clubId: Long,
    val metricType: String,
    val threshold: Int,
    val windowDays: Int,
    val prizeId: Long,
    val enabled: Boolean,
    val orderIndex: Int,
)

data class Prize(
    val id: Long,
    val clubId: Long,
    val code: String,
    val enabled: Boolean,
    val expiresInDays: Int?,
)

data class RewardCoupon(
    val id: Long,
    val clubId: Long,
    val userId: Long,
    val prizeId: Long,
    val fingerprint: String,
    val issuedAt: Instant,
    val expiresAt: Instant?,
)

data class BadgeEarnResult(
    val badge: UserBadge,
    val created: Boolean,
)

data class CouponIssueResult(
    val coupon: RewardCoupon,
    val created: Boolean,
)

data class BadgeEarned(
    val badgeId: Long,
    val earnedAt: Instant,
)

data class CouponIssued(
    val couponId: Long,
    val prizeId: Long,
    val issuedAt: Instant,
    val expiresAt: Instant?,
)

data class RewardPreview(
    val metricType: String,
    val threshold: Int,
    val windowDays: Int?,
    val prizeId: Long?,
)

data class GamificationDelta(
    val earnedBadges: List<BadgeEarned> = emptyList(),
    val issuedCoupons: List<CouponIssued> = emptyList(),
    val nextRewardsPreview: List<RewardPreview>? = null,
)

interface GamificationSettingsRepository {
    suspend fun getByClubId(clubId: Long): ClubGamificationSettings?
}

interface BadgeRepository {
    suspend fun listEnabled(clubId: Long): List<Badge>
}

interface UserBadgeRepository {
    suspend fun tryEarn(
        fingerprint: String,
        clubId: Long,
        userId: Long,
        badgeId: Long,
        earnedAt: Instant,
    ): BadgeEarnResult
}

interface RewardLadderRepository {
    suspend fun listEnabledLevels(clubId: Long): List<RewardLadderLevel>
}

interface PrizeRepository {
    suspend fun listByIds(
        clubId: Long,
        prizeIds: Set<Long>,
    ): List<Prize>
}

interface CouponRepository {
    suspend fun tryIssue(
        fingerprint: String,
        clubId: Long,
        userId: Long,
        prizeId: Long,
        issuedAt: Instant,
        expiresAt: Instant? = null,
        issuedBy: Long? = null,
    ): CouponIssueResult
}

interface VisitMetricsRepository {
    suspend fun countVisits(
        userId: Long,
        clubId: Long,
        sinceUtc: Instant? = null,
    ): Long

    suspend fun countEarlyVisits(
        userId: Long,
        clubId: Long,
        sinceUtc: Instant? = null,
    ): Long

    suspend fun countTableNights(
        userId: Long,
        clubId: Long,
        sinceUtc: Instant? = null,
    ): Long
}
