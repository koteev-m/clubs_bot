package com.example.bot.admin

import java.time.Instant

data class AdminGamificationSettings(
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

data class AdminGamificationSettingsUpdate(
    val clubId: Long,
    val stampsEnabled: Boolean,
    val earlyEnabled: Boolean,
    val badgesEnabled: Boolean,
    val prizesEnabled: Boolean,
    val contestsEnabled: Boolean,
    val tablesLoyaltyEnabled: Boolean,
    val earlyWindowMinutes: Int?,
)

data class AdminBadge(
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

data class AdminBadgeCreate(
    val code: String,
    val nameRu: String,
    val icon: String?,
    val enabled: Boolean,
    val conditionType: String,
    val threshold: Int,
    val windowDays: Int?,
)

data class AdminBadgeUpdate(
    val id: Long,
    val code: String,
    val nameRu: String,
    val icon: String?,
    val enabled: Boolean,
    val conditionType: String,
    val threshold: Int,
    val windowDays: Int?,
)

data class AdminPrize(
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

data class AdminPrizeCreate(
    val code: String,
    val titleRu: String,
    val description: String?,
    val terms: String?,
    val enabled: Boolean,
    val limitTotal: Int?,
    val expiresInDays: Int?,
)

data class AdminPrizeUpdate(
    val id: Long,
    val code: String,
    val titleRu: String,
    val description: String?,
    val terms: String?,
    val enabled: Boolean,
    val limitTotal: Int?,
    val expiresInDays: Int?,
)

data class AdminRewardLadderLevel(
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

data class AdminRewardLadderLevelCreate(
    val metricType: String,
    val threshold: Int,
    val windowDays: Int,
    val prizeId: Long,
    val enabled: Boolean,
    val orderIndex: Int,
)

data class AdminRewardLadderLevelUpdate(
    val id: Long,
    val metricType: String,
    val threshold: Int,
    val windowDays: Int,
    val prizeId: Long,
    val enabled: Boolean,
    val orderIndex: Int,
)

data class AdminNightOverride(
    val clubId: Long,
    val nightStartUtc: Instant,
    val earlyCutoffAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

interface AdminGamificationSettingsRepository {
    suspend fun getByClubId(clubId: Long): AdminGamificationSettings?

    suspend fun upsert(settings: AdminGamificationSettingsUpdate): AdminGamificationSettings
}

interface AdminBadgeRepository {
    suspend fun listForClub(clubId: Long): List<AdminBadge>

    suspend fun create(clubId: Long, request: AdminBadgeCreate): AdminBadge

    suspend fun update(clubId: Long, request: AdminBadgeUpdate): AdminBadge?

    suspend fun delete(clubId: Long, id: Long): Boolean
}

interface AdminPrizeRepository {
    suspend fun listForClub(clubId: Long): List<AdminPrize>

    suspend fun create(clubId: Long, request: AdminPrizeCreate): AdminPrize

    suspend fun update(clubId: Long, request: AdminPrizeUpdate): AdminPrize?

    suspend fun delete(clubId: Long, id: Long): Boolean
}

interface AdminRewardLadderRepository {
    suspend fun listForClub(clubId: Long): List<AdminRewardLadderLevel>

    suspend fun create(clubId: Long, request: AdminRewardLadderLevelCreate): AdminRewardLadderLevel

    suspend fun update(clubId: Long, request: AdminRewardLadderLevelUpdate): AdminRewardLadderLevel?

    suspend fun delete(clubId: Long, id: Long): Boolean
}

interface AdminNightOverrideRepository {
    suspend fun getOverride(clubId: Long, nightStartUtc: Instant): AdminNightOverride?

    suspend fun upsertOverride(
        clubId: Long,
        nightStartUtc: Instant,
        earlyCutoffAt: Instant?,
    ): AdminNightOverride
}
