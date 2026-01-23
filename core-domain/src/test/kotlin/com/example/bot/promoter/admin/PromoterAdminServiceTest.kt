package com.example.bot.promoter.admin

import com.example.bot.promoter.quotas.PromoterQuota
import com.example.bot.promoter.quotas.PromoterQuotaRepository
import com.example.bot.promoter.quotas.PromoterQuotaService
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PromoterAdminServiceTest {
    private val now = Instant.parse("2024-06-01T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `listPromoters merges quotas and sorts by display name`() {
        val repo =
            StaticPromoterAdminRepository(
                listOf(
                    PromoterAdminProfile(
                        promoterId = 2,
                        telegramUserId = 1002,
                        username = "zed",
                        displayName = "Zed",
                        accessEnabled = true,
                    ),
                    PromoterAdminProfile(
                        promoterId = 1,
                        telegramUserId = 1001,
                        username = "alice",
                        displayName = "Alice",
                        accessEnabled = false,
                    ),
                ),
            )
        val quotaRepo = InMemoryPromoterQuotaRepository()
        quotaRepo.upsert(
            PromoterQuota(
                clubId = 10,
                promoterId = 1,
                tableId = 5,
                quota = 3,
                held = 1,
                expiresAt = now.plusSeconds(3600),
            ),
        )
        val quotaService = PromoterQuotaService(quotaRepo, clock)
        val service = PromoterAdminService(repo, quotaService, clock)

        val result = runBlocking { service.listPromoters(10) }

        assertEquals(2, result.size)
        assertEquals(1, result.first().profile.promoterId)
        assertEquals(1, result.first().quotas.size)
        assertEquals(5, result.first().quotas.first().tableId)
        assertEquals(2, result.last().profile.promoterId)
    }

    @Test
    fun `setAccess returns repository result`() {
        val repo = StaticPromoterAdminRepository(emptyList())
        val quotaService = PromoterQuotaService(InMemoryPromoterQuotaRepository(), clock)
        val service = PromoterAdminService(repo, quotaService, clock)

        val result = runBlocking { service.setAccess(1, 10, true) }

        assertTrue(result is PromoterAccessUpdateResult.Success)
    }

    private class StaticPromoterAdminRepository(
        private val promoters: List<PromoterAdminProfile>,
    ) : PromoterAdminRepository {
        override suspend fun listPromotersByClub(clubId: Long): List<PromoterAdminProfile> = promoters

        override suspend fun setPromoterAccess(
            clubId: Long,
            promoterId: Long,
            enabled: Boolean,
        ): PromoterAccessUpdateResult = PromoterAccessUpdateResult.Success(enabled)
    }

    private class InMemoryPromoterQuotaRepository : PromoterQuotaRepository {
        private val storage = ConcurrentHashMap<Triple<Long, Long, Long>, PromoterQuota>()

        override fun upsert(quota: PromoterQuota): PromoterQuota {
            storage[Triple(quota.clubId, quota.promoterId, quota.tableId)] = quota
            return quota
        }

        override fun find(
            clubId: Long,
            promoterId: Long,
            tableId: Long,
        ): PromoterQuota? = storage[Triple(clubId, promoterId, tableId)]

        override fun listByClub(clubId: Long): List<PromoterQuota> =
            storage.values.filter { it.clubId == clubId }
    }
}
