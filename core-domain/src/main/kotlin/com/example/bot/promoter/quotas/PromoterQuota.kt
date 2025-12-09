package com.example.bot.promoter.quotas

import java.time.Instant

/**
 * Micro-quota for promoter HOLDs on a specific table within a club.
 *
 * Quotas cap simultaneous HOLD reservations for a (clubId, promoterId, tableId)
 * tuple. When [expiresAt] is in the past the quota no longer constrains HOLDs
 * and its [held] counter is reset by the service layer.
 */
data class PromoterQuota(
    val clubId: Long,
    val promoterId: Long,
    val tableId: Long,
    val quota: Int,
    val held: Int,
    val expiresAt: Instant,
)
