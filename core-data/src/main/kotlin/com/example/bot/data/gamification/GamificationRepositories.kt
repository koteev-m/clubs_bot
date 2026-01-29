package com.example.bot.data.gamification

import com.example.bot.data.db.toOffsetDateTimeUtc
import com.example.bot.data.db.withRetriedTx
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.time.Clock
import java.time.Instant

data class ClubGamificationSettings(
    val clubId: Long,
    val stampsEnabled: Boolean,
    val earlyEnabled: Boolean,
    val badgesEnabled: Boolean,
    val prizesEnabled: Boolean,
    val contestsEnabled: Boolean,
    val tablesLoyaltyEnabled: Boolean,
    val earlyWindowMinutes: Int?,
    val updatedAt: Instant,
)

class GamificationSettingsRepository(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun getByClubId(clubId: Long): ClubGamificationSettings? =
        withRetriedTx(name = "gamification.settings.get", readOnly = true, database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                ClubGamificationSettingsTable
                    .selectAll()
                    .where { ClubGamificationSettingsTable.clubId eq clubId }
                    .limit(1)
                    .firstOrNull()
                    ?.toSettings()
            }
        }

    suspend fun upsert(settings: ClubGamificationSettings): ClubGamificationSettings {
        val now = Instant.now(clock).toOffsetDateTimeUtc()
        return withRetriedTx(name = "gamification.settings.upsert", database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                val updated =
                    ClubGamificationSettingsTable.update({
                        ClubGamificationSettingsTable.clubId eq settings.clubId
                    }) {
                        it[stampsEnabled] = settings.stampsEnabled
                        it[earlyEnabled] = settings.earlyEnabled
                        it[badgesEnabled] = settings.badgesEnabled
                        it[prizesEnabled] = settings.prizesEnabled
                        it[contestsEnabled] = settings.contestsEnabled
                        it[tablesLoyaltyEnabled] = settings.tablesLoyaltyEnabled
                        it[earlyWindowMinutes] = settings.earlyWindowMinutes
                        it[updatedAt] = now
                    }
                if (updated == 0) {
                    val inserted =
                        ClubGamificationSettingsTable.insertIgnore {
                            it[clubId] = settings.clubId
                            it[stampsEnabled] = settings.stampsEnabled
                            it[earlyEnabled] = settings.earlyEnabled
                            it[badgesEnabled] = settings.badgesEnabled
                            it[prizesEnabled] = settings.prizesEnabled
                            it[contestsEnabled] = settings.contestsEnabled
                            it[tablesLoyaltyEnabled] = settings.tablesLoyaltyEnabled
                            it[earlyWindowMinutes] = settings.earlyWindowMinutes
                            it[updatedAt] = now
                        }
                    if (inserted.insertedCount == 0) {
                        ClubGamificationSettingsTable.update({
                            ClubGamificationSettingsTable.clubId eq settings.clubId
                        }) {
                            it[stampsEnabled] = settings.stampsEnabled
                            it[earlyEnabled] = settings.earlyEnabled
                            it[badgesEnabled] = settings.badgesEnabled
                            it[prizesEnabled] = settings.prizesEnabled
                            it[contestsEnabled] = settings.contestsEnabled
                            it[tablesLoyaltyEnabled] = settings.tablesLoyaltyEnabled
                            it[earlyWindowMinutes] = settings.earlyWindowMinutes
                            it[updatedAt] = now
                        }
                    }
                }
                ClubGamificationSettingsTable
                    .selectAll()
                    .where { ClubGamificationSettingsTable.clubId eq settings.clubId }
                    .limit(1)
                    .firstOrNull()
                    ?.toSettings()
                    ?: error("Failed to load gamification settings for clubId=${settings.clubId}")
            }
        }
    }
}

data class Badge(
    val id: Long,
    val clubId: Long,
    val code: String,
    val nameRu: String,
    val icon: String?,
    val enabled: Boolean,
    val conditionType: String,
    val threshold: Int,
    val windowDays: Int?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

class BadgeRepository(
    private val db: Database,
) {
    suspend fun listEnabled(clubId: Long): List<Badge> =
        withRetriedTx(name = "badge.listEnabled", readOnly = true, database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                BadgesTable
                    .selectAll()
                    .where { (BadgesTable.clubId eq clubId) and (BadgesTable.enabled eq true) }
                    .orderBy(BadgesTable.id, SortOrder.ASC)
                    .map { it.toBadge() }
            }
        }
}

data class UserBadge(
    val id: Long,
    val clubId: Long,
    val userId: Long,
    val badgeId: Long,
    val earnedAt: Instant,
    val fingerprint: String,
    val createdAt: Instant,
)

data class UserBadgeIssueResult(
    val badge: UserBadge,
    val created: Boolean,
)

class UserBadgeRepository(
    private val db: Database,
) {
    suspend fun tryEarn(
        fingerprint: String,
        clubId: Long,
        userId: Long,
        badgeId: Long,
        earnedAt: Instant,
    ): UserBadgeIssueResult =
        withRetriedTx(name = "badge.tryEarn", database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                val inserted =
                    UserBadgesTable.insertIgnore {
                    it[UserBadgesTable.clubId] = clubId
                    it[UserBadgesTable.userId] = userId
                    it[UserBadgesTable.badgeId] = badgeId
                    it[UserBadgesTable.earnedAt] = earnedAt.toOffsetDateTimeUtc()
                    it[UserBadgesTable.fingerprint] = fingerprint
                }
                val badge =
                    findUserBadgeByFingerprint(fingerprint)
                        ?: findUserBadgeByKey(clubId, userId, badgeId)
                        ?: error("Failed to load user badge after insert")
                UserBadgeIssueResult(badge = badge, created = inserted.insertedCount > 0)
            }
        }

    suspend fun listForUser(
        clubId: Long,
        userId: Long,
    ): List<UserBadge> =
        withRetriedTx(name = "badge.listForUser", readOnly = true, database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                UserBadgesTable
                    .selectAll()
                    .where { (UserBadgesTable.clubId eq clubId) and (UserBadgesTable.userId eq userId) }
                    .orderBy(UserBadgesTable.earnedAt, SortOrder.DESC)
                    .map { it.toUserBadge() }
            }
        }

    private fun findUserBadgeByFingerprint(fingerprint: String): UserBadge? =
        UserBadgesTable
            .selectAll()
            .where { UserBadgesTable.fingerprint eq fingerprint }
            .limit(1)
            .firstOrNull()
            ?.toUserBadge()

    private fun findUserBadgeByKey(
        clubId: Long,
        userId: Long,
        badgeId: Long,
    ): UserBadge? =
        UserBadgesTable
            .selectAll()
            .where {
                (UserBadgesTable.clubId eq clubId) and
                    (UserBadgesTable.userId eq userId) and
                    (UserBadgesTable.badgeId eq badgeId)
            }.limit(1)
            .firstOrNull()
            ?.toUserBadge()
}

data class Prize(
    val id: Long,
    val clubId: Long,
    val code: String,
    val titleRu: String,
    val description: String?,
    val terms: String?,
    val enabled: Boolean,
    val limitTotal: Int?,
    val expiresInDays: Int?,
    val createdAt: Instant,
    val updatedAt: Instant,
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
    val createdAt: Instant,
    val updatedAt: Instant,
)

class RewardLadderRepository(
    private val db: Database,
) {
    suspend fun listEnabledLevels(
        clubId: Long,
    ): List<RewardLadderLevel> =
        withRetriedTx(name = "reward.ladder.listEnabled", readOnly = true, database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                RewardLadderLevelsTable
                    .selectAll()
                    .where {
                        (RewardLadderLevelsTable.clubId eq clubId) and (RewardLadderLevelsTable.enabled eq true)
                    }.orderBy(RewardLadderLevelsTable.orderIndex, SortOrder.ASC)
                    .map { it.toRewardLadderLevel() }
            }
        }

    suspend fun listEnabledLevels(
        clubId: Long,
        metricType: String,
    ): List<RewardLadderLevel> =
        listEnabledLevels(clubId).filter { it.metricType == metricType }
}

class PrizeRepository(
    private val db: Database,
) {
    suspend fun listByIds(
        clubId: Long,
        prizeIds: Set<Long>,
    ): List<Prize> {
        if (prizeIds.isEmpty()) return emptyList()
        return withRetriedTx(name = "prize.listByIds", readOnly = true, database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                PrizesTable
                    .selectAll()
                    .where { (PrizesTable.clubId eq clubId) and (PrizesTable.id inList prizeIds.toList()) }
                    .orderBy(PrizesTable.id, SortOrder.ASC)
                    .map { it.toPrize() }
            }
        }
    }
}

enum class CouponStatus {
    AVAILABLE,
    REDEEMED,
    EXPIRED,
    CANCELLED,
}

data class RewardCoupon(
    val id: Long,
    val clubId: Long,
    val userId: Long,
    val prizeId: Long,
    val status: CouponStatus,
    val reasonCode: String?,
    val fingerprint: String,
    val issuedAt: Instant,
    val expiresAt: Instant?,
    val redeemedAt: Instant?,
    val issuedBy: Long?,
    val redeemedBy: Long?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class CouponIssueResult(
    val coupon: RewardCoupon,
    val created: Boolean,
)

class CouponRepository(
    private val db: Database,
) {
    suspend fun tryIssue(
        fingerprint: String,
        clubId: Long,
        userId: Long,
        prizeId: Long,
        issuedAt: Instant,
        expiresAt: Instant? = null,
        issuedBy: Long? = null,
    ): CouponIssueResult =
        withRetriedTx(name = "coupon.tryIssue", database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                val inserted =
                    RewardCouponsTable.insertIgnore {
                    it[RewardCouponsTable.clubId] = clubId
                    it[RewardCouponsTable.userId] = userId
                    it[RewardCouponsTable.prizeId] = prizeId
                    it[RewardCouponsTable.status] = CouponStatus.AVAILABLE.name
                    it[RewardCouponsTable.reasonCode] = null
                    it[RewardCouponsTable.fingerprint] = fingerprint
                    it[RewardCouponsTable.issuedAt] = issuedAt.toOffsetDateTimeUtc()
                    it[RewardCouponsTable.expiresAt] = expiresAt?.toOffsetDateTimeUtc()
                    it[RewardCouponsTable.issuedBy] = issuedBy
                    it[RewardCouponsTable.updatedAt] = issuedAt.toOffsetDateTimeUtc()
                }
                val coupon =
                    findCouponByFingerprint(fingerprint)
                        ?: error("Failed to load reward coupon after insert")
                CouponIssueResult(coupon = coupon, created = inserted.insertedCount > 0)
            }
        }

    suspend fun listForUser(
        clubId: Long,
        userId: Long,
        statuses: Set<CouponStatus>? = null,
    ): List<RewardCoupon> =
        withRetriedTx(name = "coupon.listForUser", readOnly = true, database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                val baseQuery =
                    RewardCouponsTable
                        .selectAll()
                        .where {
                            (RewardCouponsTable.clubId eq clubId) and (RewardCouponsTable.userId eq userId)
                        }
                val filtered =
                    statuses?.takeIf { it.isNotEmpty() }?.let { statusesSet ->
                        baseQuery.andWhere { RewardCouponsTable.status inList statusesSet.map { it.name } }
                    } ?: baseQuery
                filtered.orderBy(RewardCouponsTable.issuedAt, SortOrder.DESC).map { it.toRewardCoupon() }
            }
        }

    suspend fun redeem(
        couponId: Long,
        managerId: Long,
        now: Instant,
    ): Boolean =
        withRetriedTx(name = "coupon.redeem", database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                RewardCouponsTable.update({
                    (RewardCouponsTable.id eq couponId) and
                        (RewardCouponsTable.status eq CouponStatus.AVAILABLE.name)
                }) {
                    it[RewardCouponsTable.status] = CouponStatus.REDEEMED.name
                    it[RewardCouponsTable.redeemedAt] = now.toOffsetDateTimeUtc()
                    it[RewardCouponsTable.redeemedBy] = managerId
                    it[RewardCouponsTable.updatedAt] = now.toOffsetDateTimeUtc()
                } > 0
            }
        }

    private fun findCouponByFingerprint(fingerprint: String): RewardCoupon? =
        RewardCouponsTable
            .selectAll()
            .where { RewardCouponsTable.fingerprint eq fingerprint }
            .limit(1)
            .firstOrNull()
            ?.toRewardCoupon()
}

private fun ResultRow.toSettings(): ClubGamificationSettings =
    ClubGamificationSettings(
        clubId = this[ClubGamificationSettingsTable.clubId],
        stampsEnabled = this[ClubGamificationSettingsTable.stampsEnabled],
        earlyEnabled = this[ClubGamificationSettingsTable.earlyEnabled],
        badgesEnabled = this[ClubGamificationSettingsTable.badgesEnabled],
        prizesEnabled = this[ClubGamificationSettingsTable.prizesEnabled],
        contestsEnabled = this[ClubGamificationSettingsTable.contestsEnabled],
        tablesLoyaltyEnabled = this[ClubGamificationSettingsTable.tablesLoyaltyEnabled],
        earlyWindowMinutes = this[ClubGamificationSettingsTable.earlyWindowMinutes],
        updatedAt = this[ClubGamificationSettingsTable.updatedAt].toInstant(),
    )

private fun ResultRow.toBadge(): Badge =
    Badge(
        id = this[BadgesTable.id],
        clubId = this[BadgesTable.clubId],
        code = this[BadgesTable.code],
        nameRu = this[BadgesTable.nameRu],
        icon = this[BadgesTable.icon],
        enabled = this[BadgesTable.enabled],
        conditionType = this[BadgesTable.conditionType],
        threshold = this[BadgesTable.threshold],
        windowDays = this[BadgesTable.windowDays],
        createdAt = this[BadgesTable.createdAt].toInstant(),
        updatedAt = this[BadgesTable.updatedAt].toInstant(),
    )

private fun ResultRow.toUserBadge(): UserBadge =
    UserBadge(
        id = this[UserBadgesTable.id],
        clubId = this[UserBadgesTable.clubId],
        userId = this[UserBadgesTable.userId],
        badgeId = this[UserBadgesTable.badgeId],
        earnedAt = this[UserBadgesTable.earnedAt].toInstant(),
        fingerprint = this[UserBadgesTable.fingerprint],
        createdAt = this[UserBadgesTable.createdAt].toInstant(),
    )

private fun ResultRow.toRewardLadderLevel(): RewardLadderLevel =
    RewardLadderLevel(
        id = this[RewardLadderLevelsTable.id],
        clubId = this[RewardLadderLevelsTable.clubId],
        metricType = this[RewardLadderLevelsTable.metricType],
        threshold = this[RewardLadderLevelsTable.threshold],
        windowDays = this[RewardLadderLevelsTable.windowDays],
        prizeId = this[RewardLadderLevelsTable.prizeId],
        enabled = this[RewardLadderLevelsTable.enabled],
        orderIndex = this[RewardLadderLevelsTable.orderIndex],
        createdAt = this[RewardLadderLevelsTable.createdAt].toInstant(),
        updatedAt = this[RewardLadderLevelsTable.updatedAt].toInstant(),
    )

private fun ResultRow.toRewardCoupon(): RewardCoupon =
    RewardCoupon(
        id = this[RewardCouponsTable.id],
        clubId = this[RewardCouponsTable.clubId],
        userId = this[RewardCouponsTable.userId],
        prizeId = this[RewardCouponsTable.prizeId],
        status = CouponStatus.valueOf(this[RewardCouponsTable.status]),
        reasonCode = this[RewardCouponsTable.reasonCode],
        fingerprint = this[RewardCouponsTable.fingerprint],
        issuedAt = this[RewardCouponsTable.issuedAt].toInstant(),
        expiresAt = this[RewardCouponsTable.expiresAt]?.toInstant(),
        redeemedAt = this[RewardCouponsTable.redeemedAt]?.toInstant(),
        issuedBy = this[RewardCouponsTable.issuedBy],
        redeemedBy = this[RewardCouponsTable.redeemedBy],
        createdAt = this[RewardCouponsTable.createdAt].toInstant(),
        updatedAt = this[RewardCouponsTable.updatedAt].toInstant(),
    )

private fun ResultRow.toPrize(): Prize =
    Prize(
        id = this[PrizesTable.id],
        clubId = this[PrizesTable.clubId],
        code = this[PrizesTable.code],
        titleRu = this[PrizesTable.titleRu],
        description = this[PrizesTable.description],
        terms = this[PrizesTable.terms],
        enabled = this[PrizesTable.enabled],
        limitTotal = this[PrizesTable.limitTotal],
        expiresInDays = this[PrizesTable.expiresInDays],
        createdAt = this[PrizesTable.createdAt].toInstant(),
        updatedAt = this[PrizesTable.updatedAt].toInstant(),
    )
