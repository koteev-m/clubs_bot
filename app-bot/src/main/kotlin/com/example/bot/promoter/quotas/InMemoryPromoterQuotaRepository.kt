package com.example.bot.promoter.quotas

import java.util.concurrent.ConcurrentHashMap

/** In-memory quota repository for tests and local runs. */
class InMemoryPromoterQuotaRepository : PromoterQuotaRepository {
    private val storage = ConcurrentHashMap<Triple<Long, Long, Long>, PromoterQuota>()

    override fun upsert(quota: PromoterQuota): PromoterQuota {
        val copy = quota.copy()
        storage[Triple(quota.clubId, quota.promoterId, quota.tableId)] = copy
        return copy.copy()
    }

    override fun find(clubId: Long, promoterId: Long, tableId: Long): PromoterQuota? =
        storage[Triple(clubId, promoterId, tableId)]?.copy()

    override fun listByClub(clubId: Long): List<PromoterQuota> =
        storage.values.filter { it.clubId == clubId }.map { it.copy() }
}
