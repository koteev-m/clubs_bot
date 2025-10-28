package com.example.bot.booking.dto

import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant
import java.util.UUID
import com.example.bot.booking.HoldRequest as DomainHoldRequest

@Serializable
data class HoldRequest(
    val clubId: Long? = null,
    val tableId: Long,
    val slotStart: String,
    val slotEnd: String,
    val guestsCount: Int,
    val ttlSeconds: Long,
) {
    fun toCommand(clubId: Long): DomainHoldRequest {
        require(ttlSeconds > 0) { "ttlSeconds must be positive" }
        val start = Instant.parse(slotStart)
        val end = Instant.parse(slotEnd)
        return DomainHoldRequest(
            clubId = clubId,
            tableId = tableId,
            slotStart = start,
            slotEnd = end,
            guestsCount = guestsCount,
            ttl = Duration.ofSeconds(ttlSeconds),
        )
    }
}

@Serializable
data class ConfirmRequest(
    val clubId: Long? = null,
    val holdId: String,
) {
    fun holdUuid(): UUID = UUID.fromString(holdId)
}
