package com.example.bot.availability

import com.example.bot.time.NightSlot
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDateTime

/**
 * Public DTO representing available night.
 */
@Serializable
data class NightDto(
    val eventStartUtc: @Contextual Instant,
    val eventEndUtc: @Contextual Instant,
    val isSpecial: Boolean,
    val arrivalByUtc: @Contextual Instant,
    val openLocal: @Contextual LocalDateTime,
    val closeLocal: @Contextual LocalDateTime,
    val timezone: String,
)

/**
 * Status of a table relative to an event.
 */
@Serializable
enum class TableStatus { FREE, HELD, BOOKED }

/**
 * DTO describing availability of a single table.
 */
@Serializable
data class TableAvailabilityDto(
    val tableId: Long,
    val tableNumber: String,
    val zone: String,
    val capacity: Int,
    val minDeposit: Int,
    val status: TableStatus,
)

fun TableAvailabilityDto.minDepositMinor(): Long = minDeposit.toLong() * 100L

/**
 * Internal representation of a table entity.
 */
data class Table(
    val id: Long,
    val number: String,
    val zone: String,
    val capacity: Int,
    val minDeposit: Int,
    val active: Boolean,
)

/**
 * Helper to convert domain slot to DTO.
 */
fun NightSlot.toDto(arrivalBy: Instant): NightDto =
    NightDto(
        eventStartUtc = eventStartUtc,
        eventEndUtc = eventEndUtc,
        isSpecial = isSpecial,
        arrivalByUtc = arrivalBy,
        openLocal = openLocal,
        closeLocal = closeLocal,
        timezone = zone.id,
    )
