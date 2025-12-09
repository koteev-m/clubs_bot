package com.example.bot.promoter.quotas

import java.time.Clock
import java.time.Instant

sealed class HoldQuotaResult {
    data object Allowed : HoldQuotaResult()
    data object Exhausted : HoldQuotaResult()
    data object NoQuotaConfigured : HoldQuotaResult()
}

/**
 * Coordinates promoter HOLD quota checks and bookkeeping.
 *
 * Expired quotas immediately reset their [PromoterQuota.held] counter to 0 and
 * stop constraining HOLD attempts until reconfigured.
 */
class PromoterQuotaService(
    private val repository: PromoterQuotaRepository,
    private val clock: Clock,
) {
    /**
     * Attempts to reserve a HOLD slot for the given promoter/table pair.
     *
     * When the quota is missing or expired, HOLDs are allowed without
     * reservation. Active quotas increment [PromoterQuota.held] if capacity
     * remains; otherwise they return [HoldQuotaResult.Exhausted].
     */
    fun checkAndReserveHold(
        clubId: Long,
        promoterId: Long,
        tableId: Long,
        now: Instant = Instant.now(clock),
    ): HoldQuotaResult {
        val existing = repository.find(clubId, promoterId, tableId)
            ?: return HoldQuotaResult.NoQuotaConfigured

        if (!existing.expiresAt.isAfter(now)) {
            val reset = existing.copy(held = 0)
            repository.upsert(reset)
            return HoldQuotaResult.NoQuotaConfigured
        }

        if (existing.held < existing.quota) {
            repository.upsert(existing.copy(held = existing.held + 1))
            return HoldQuotaResult.Allowed
        }

        return HoldQuotaResult.Exhausted
    }

    /**
     * Releases a previously reserved slot when it is still tracked by an active
     * quota. Safe to call multiple times for the same booking lifecycle.
     */
    fun releaseHoldIfTracked(
        clubId: Long,
        promoterId: Long,
        tableId: Long,
        now: Instant = Instant.now(clock),
    ) {
        val existing = repository.find(clubId, promoterId, tableId) ?: return
        if (!existing.expiresAt.isAfter(now)) {
            return
        }
        if (existing.held <= 0) {
            return
        }

        repository.upsert(existing.copy(held = existing.held - 1))
    }

    /**
     * Lists quotas for a club, resetting expired entries (held → 0) so they no
     * longer block HOLD requests.
     */
    fun listByClub(clubId: Long, now: Instant = Instant.now(clock)): List<PromoterQuota> {
        return repository.listByClub(clubId).map { quota ->
            if (!quota.expiresAt.isAfter(now)) {
                val reset = quota.copy(held = 0)
                repository.upsert(reset)
                reset
            } else {
                quota
            }
        }
    }

    fun createOrReplace(quota: PromoterQuota): PromoterQuota {
        return repository.upsert(quota.copy(held = 0))
    }

    /**
     * Updates quota values while preserving the current [PromoterQuota.held]
     * counter. Returns null when the quota is missing.
     */
    fun updateExisting(quota: PromoterQuota): PromoterQuota? {
        val existing = repository.find(quota.clubId, quota.promoterId, quota.tableId) ?: return null
        return repository.upsert(quota.copy(held = existing.held))
    }
}
