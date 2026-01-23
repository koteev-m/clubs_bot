package com.example.bot.promoter.admin

import com.example.bot.promoter.quotas.PromoterQuota

data class PromoterAdminProfile(
    val promoterId: Long,
    val telegramUserId: Long,
    val username: String?,
    val displayName: String?,
    val accessEnabled: Boolean,
)

data class PromoterAdminEntry(
    val profile: PromoterAdminProfile,
    val quotas: List<PromoterQuota>,
)

sealed interface PromoterAccessUpdateResult {
    data class Success(
        val enabled: Boolean,
    ) : PromoterAccessUpdateResult

    data object NotFound : PromoterAccessUpdateResult
}

interface PromoterAdminRepository {
    suspend fun listPromotersByClub(clubId: Long): List<PromoterAdminProfile>

    suspend fun setPromoterAccess(
        clubId: Long,
        promoterId: Long,
        enabled: Boolean,
    ): PromoterAccessUpdateResult
}
