package com.example.bot.promoter.admin

import com.example.bot.promoter.quotas.PromoterQuota
import com.example.bot.promoter.quotas.PromoterQuotaService
import java.time.Clock
import java.time.Instant
import java.util.Locale

class PromoterAdminService(
    private val repository: PromoterAdminRepository,
    private val quotaService: PromoterQuotaService,
    private val clock: Clock,
) {
    suspend fun listPromoters(clubId: Long): List<PromoterAdminEntry> {
        val promoters = repository.listPromotersByClub(clubId)
        val quotas = quotaService.listByClub(clubId, Instant.now(clock))
        val quotasByPromoter = quotas.groupBy { it.promoterId }
        return promoters
            .sortedWith(
                compareBy<PromoterAdminProfile>(
                    { it.displayName?.lowercase(Locale.ROOT) ?: it.username?.lowercase(Locale.ROOT) ?: "" },
                    { it.promoterId },
                ),
            ).map { profile ->
                PromoterAdminEntry(
                    profile = profile,
                    quotas = quotasByPromoter[profile.promoterId]?.sortedBy(PromoterQuota::tableId).orEmpty(),
                )
            }
    }

    suspend fun setAccess(
        clubId: Long,
        promoterId: Long,
        enabled: Boolean,
    ): PromoterAccessUpdateResult = repository.setPromoterAccess(clubId, promoterId, enabled)
}
