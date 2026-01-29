package com.example.bot.data.gamification

import com.example.bot.gamification.Badge
import com.example.bot.gamification.BadgeEarnResult
import com.example.bot.gamification.BadgeRepository
import com.example.bot.gamification.ClubGamificationSettings
import com.example.bot.gamification.ClubVisit
import com.example.bot.gamification.CouponIssueResult
import com.example.bot.gamification.CouponRepository
import com.example.bot.gamification.GamificationSettingsRepository as DomainGamificationSettingsRepository
import com.example.bot.gamification.Prize
import com.example.bot.gamification.PrizeRepository
import com.example.bot.gamification.RewardCoupon
import com.example.bot.gamification.RewardLadderLevel
import com.example.bot.gamification.RewardLadderRepository
import com.example.bot.gamification.UserBadge
import com.example.bot.gamification.UserBadgeRepository
import com.example.bot.gamification.VisitMetricsRepository
import com.example.bot.data.visits.VisitRepository

class GamificationSettingsRepositoryAdapter(
    private val repository: com.example.bot.data.gamification.GamificationSettingsRepository,
) : DomainGamificationSettingsRepository {
    override suspend fun getByClubId(clubId: Long): ClubGamificationSettings? =
        repository.getByClubId(clubId)?.toDomain()
}

class BadgeRepositoryAdapter(
    private val repository: com.example.bot.data.gamification.BadgeRepository,
) : BadgeRepository {
    override suspend fun listEnabled(clubId: Long): List<Badge> =
        repository.listEnabled(clubId).map { it.toDomain() }
}

class UserBadgeRepositoryAdapter(
    private val repository: com.example.bot.data.gamification.UserBadgeRepository,
) : UserBadgeRepository {
    override suspend fun tryEarn(
        fingerprint: String,
        clubId: Long,
        userId: Long,
        badgeId: Long,
        earnedAt: java.time.Instant,
    ): BadgeEarnResult {
        val result = repository.tryEarn(fingerprint, clubId, userId, badgeId, earnedAt)
        return BadgeEarnResult(badge = result.badge.toDomain(), created = result.created)
    }
}

class RewardLadderRepositoryAdapter(
    private val repository: com.example.bot.data.gamification.RewardLadderRepository,
) : RewardLadderRepository {
    override suspend fun listEnabledLevels(clubId: Long): List<RewardLadderLevel> =
        repository.listEnabledLevels(clubId).map { it.toDomain() }
}

class PrizeRepositoryAdapter(
    private val repository: com.example.bot.data.gamification.PrizeRepository,
) : PrizeRepository {
    override suspend fun listByIds(
        clubId: Long,
        prizeIds: Set<Long>,
    ): List<Prize> =
        repository.listByIds(clubId, prizeIds).map { it.toDomain() }
}

class CouponRepositoryAdapter(
    private val repository: com.example.bot.data.gamification.CouponRepository,
) : CouponRepository {
    override suspend fun tryIssue(
        fingerprint: String,
        clubId: Long,
        userId: Long,
        prizeId: Long,
        issuedAt: java.time.Instant,
        expiresAt: java.time.Instant?,
        issuedBy: Long?,
    ): CouponIssueResult {
        val result = repository.tryIssue(fingerprint, clubId, userId, prizeId, issuedAt, expiresAt, issuedBy)
        return CouponIssueResult(coupon = result.coupon.toDomain(), created = result.created)
    }
}

class VisitMetricsRepositoryAdapter(
    private val repository: VisitRepository,
) : VisitMetricsRepository {
    override suspend fun countVisits(userId: Long, clubId: Long, sinceUtc: java.time.Instant?): Long =
        repository.countVisits(userId, clubId, sinceUtc)

    override suspend fun countEarlyVisits(userId: Long, clubId: Long, sinceUtc: java.time.Instant?): Long =
        repository.countEarlyVisits(userId, clubId, sinceUtc)

    override suspend fun countTableNights(userId: Long, clubId: Long, sinceUtc: java.time.Instant?): Long =
        repository.countTableNights(userId, clubId, sinceUtc)
}

fun com.example.bot.data.gamification.ClubGamificationSettings.toDomain(): ClubGamificationSettings =
    ClubGamificationSettings(
        clubId = clubId,
        stampsEnabled = stampsEnabled,
        earlyEnabled = earlyEnabled,
        badgesEnabled = badgesEnabled,
        prizesEnabled = prizesEnabled,
        contestsEnabled = contestsEnabled,
        tablesLoyaltyEnabled = tablesLoyaltyEnabled,
        earlyWindowMinutes = earlyWindowMinutes,
    )

fun com.example.bot.data.gamification.Badge.toDomain(): Badge =
    Badge(
        id = id,
        clubId = clubId,
        code = code,
        conditionType = conditionType,
        threshold = threshold,
        windowDays = windowDays,
        enabled = enabled,
    )

fun com.example.bot.data.gamification.UserBadge.toDomain(): UserBadge =
    UserBadge(
        id = id,
        clubId = clubId,
        userId = userId,
        badgeId = badgeId,
        earnedAt = earnedAt,
        fingerprint = fingerprint,
    )

fun com.example.bot.data.gamification.RewardLadderLevel.toDomain(): RewardLadderLevel =
    RewardLadderLevel(
        id = id,
        clubId = clubId,
        metricType = metricType,
        threshold = threshold,
        windowDays = windowDays,
        prizeId = prizeId,
        enabled = enabled,
        orderIndex = orderIndex,
    )

fun com.example.bot.data.gamification.Prize.toDomain(): Prize =
    Prize(
        id = id,
        clubId = clubId,
        code = code,
        enabled = enabled,
        expiresInDays = expiresInDays,
    )

fun com.example.bot.data.gamification.RewardCoupon.toDomain(): RewardCoupon =
    RewardCoupon(
        id = id,
        clubId = clubId,
        userId = userId,
        prizeId = prizeId,
        fingerprint = fingerprint,
        issuedAt = issuedAt,
        expiresAt = expiresAt,
    )

fun com.example.bot.data.visits.ClubVisit.toDomain(): ClubVisit =
    ClubVisit(
        id = id,
        clubId = clubId,
        nightStartUtc = nightStartUtc,
        eventId = eventId,
        userId = userId,
        firstCheckinAt = firstCheckinAt,
        isEarly = isEarly,
        hasTable = hasTable,
    )
