package com.example.bot.promoter.quotas

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PromoterQuotaServiceTest {
    private val now = Instant.parse("2024-06-01T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `no quota configured`() {
        val repo = InMemoryRepo()
        val service = PromoterQuotaService(repo, clock)

        val result = service.checkAndReserveHold(clubId = 1, promoterId = 10, tableId = 5)

        assertEquals(HoldQuotaResult.NoQuotaConfigured, result)
        assertNull(repo.find(1, 10, 5))
    }

    @Test
    fun `active quota reserves until exhausted`() {
        val repo = InMemoryRepo()
        val quota = PromoterQuota(clubId = 1, promoterId = 10, tableId = 5, quota = 2, held = 0, expiresAt = now.plusSeconds(60))
        repo.upsert(quota)
        val service = PromoterQuotaService(repo, clock)

        assertEquals(HoldQuotaResult.Allowed, service.checkAndReserveHold(1, 10, 5))
        assertEquals(1, repo.find(1, 10, 5)!!.held)
        assertEquals(HoldQuotaResult.Allowed, service.checkAndReserveHold(1, 10, 5))
        assertEquals(2, repo.find(1, 10, 5)!!.held)
        assertEquals(HoldQuotaResult.Exhausted, service.checkAndReserveHold(1, 10, 5))
        assertEquals(2, repo.find(1, 10, 5)!!.held)
    }

    @Test
    fun `release hold decrements but not below zero`() {
        val repo = InMemoryRepo()
        val quota = PromoterQuota(clubId = 1, promoterId = 10, tableId = 5, quota = 3, held = 2, expiresAt = now.plusSeconds(60))
        repo.upsert(quota)
        val service = PromoterQuotaService(repo, clock)

        service.releaseHoldIfTracked(1, 10, 5)
        assertEquals(1, repo.find(1, 10, 5)!!.held)
        service.releaseHoldIfTracked(1, 10, 5)
        assertEquals(0, repo.find(1, 10, 5)!!.held)
        service.releaseHoldIfTracked(1, 10, 5)
        assertEquals(0, repo.find(1, 10, 5)!!.held)
    }

    @Test
    fun `expired quota resets on reserve`() {
        val repo = InMemoryRepo()
        val expired = PromoterQuota(clubId = 1, promoterId = 10, tableId = 5, quota = 2, held = 2, expiresAt = now)
        repo.upsert(expired)
        val service = PromoterQuotaService(repo, clock)

        val result = service.checkAndReserveHold(1, 10, 5, now.plusSeconds(1))

        assertEquals(HoldQuotaResult.NoQuotaConfigured, result)
        assertEquals(0, repo.find(1, 10, 5)!!.held)
    }

    @Test
    fun `release ignores expired quota`() {
        val repo = InMemoryRepo()
        val expired = PromoterQuota(clubId = 1, promoterId = 10, tableId = 5, quota = 2, held = 2, expiresAt = now)
        repo.upsert(expired)
        val service = PromoterQuotaService(repo, clock)

        service.releaseHoldIfTracked(1, 10, 5, now.plusSeconds(1))

        assertEquals(2, repo.find(1, 10, 5)!!.held)
    }

    @Test
    fun `createOrReplace resets held to zero`() {
        val repo = InMemoryRepo()
        val service = PromoterQuotaService(repo, clock)

        val saved =
            service.createOrReplace(
                PromoterQuota(
                    clubId = 1,
                    promoterId = 10,
                    tableId = 5,
                    quota = 3,
                    held = 7,
                    expiresAt = now.plusSeconds(100),
                ),
            )

        assertEquals(0, saved.held)
        assertEquals(0, repo.find(1, 10, 5)!!.held)
    }

    @Test
    fun `updateExisting keeps held value`() {
        val repo = InMemoryRepo()
        repo.upsert(PromoterQuota(clubId = 1, promoterId = 10, tableId = 5, quota = 2, held = 1, expiresAt = now.plusSeconds(50)))
        val service = PromoterQuotaService(repo, clock)

        val updated =
            service.updateExisting(
                PromoterQuota(
                    clubId = 1,
                    promoterId = 10,
                    tableId = 5,
                    quota = 5,
                    held = 99,
                    expiresAt = now.plusSeconds(150),
                ),
            )

        assertEquals(5, updated!!.quota)
        assertEquals(1, updated.held)
        assertEquals(1, repo.find(1, 10, 5)!!.held)
    }

    private class InMemoryRepo : PromoterQuotaRepository {
        private val storage = ConcurrentHashMap<Triple<Long, Long, Long>, PromoterQuota>()

        override fun upsert(quota: PromoterQuota): PromoterQuota {
            storage[Triple(quota.clubId, quota.promoterId, quota.tableId)] = quota
            return quota
        }

        override fun find(clubId: Long, promoterId: Long, tableId: Long): PromoterQuota? =
            storage[Triple(clubId, promoterId, tableId)]

        override fun listByClub(clubId: Long): List<PromoterQuota> = storage.values.filter { it.clubId == clubId }
    }
}
