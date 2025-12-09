package com.example.bot.promoter.quotas

interface PromoterQuotaRepository {
    fun upsert(quota: PromoterQuota): PromoterQuota
    fun find(clubId: Long, promoterId: Long, tableId: Long): PromoterQuota?
    fun listByClub(clubId: Long): List<PromoterQuota>
}
