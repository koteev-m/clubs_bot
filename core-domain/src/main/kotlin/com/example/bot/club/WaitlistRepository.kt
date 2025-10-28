package com.example.bot.club

import java.time.Instant

enum class WaitlistStatus {
    WAITING,
    CALLED,
    EXPIRED,
    CANCELLED,
}

data class WaitlistEntry(
    val id: Long,
    val clubId: Long,
    val eventId: Long,
    val userId: Long,
    val partySize: Int,
    val createdAt: Instant,
    val calledAt: Instant?,
    val expiresAt: Instant?,
    val status: WaitlistStatus,
)

interface WaitlistRepository {
    suspend fun enqueue(
        clubId: Long,
        eventId: Long,
        userId: Long,
        partySize: Int,
    ): WaitlistEntry

    /** Текущая очередь по клубу и событию. */
    suspend fun listQueue(
        clubId: Long,
        eventId: Long,
    ): List<WaitlistEntry>

    /** Позвать гостя: статус -> CALLED, выставить calledAt и expiresAt(now + reserveMinutes). */
    suspend fun callEntry(
        clubId: Long,
        id: Long,
        reserveMinutes: Int,
    ): WaitlistEntry?

    /**
     * Истечь/вернуть: если close=true -> EXPIRED; иначе вернуть в WAITING (сбросить calledAt/expiresAt).
     */
    suspend fun expireEntry(
        clubId: Long,
        id: Long,
        close: Boolean,
    ): WaitlistEntry?

    suspend fun get(id: Long): WaitlistEntry?
}
