package com.example.bot.coredata

import com.example.bot.layout.ArrivalWindow
import java.time.Instant

/**
 * Стартовые данные core-data для миграции из статических конфигов.
 * Используются только при пустой базе, чтобы сохранить стабильные идентификаторы.
 */
data class CoreDataSeed(
    val clubs: List<ClubSeed>,
    val events: List<EventSeed>,
    val halls: List<HallSeed>,
)

data class ClubSeed(
    val id: Long,
    val city: String,
    val name: String,
    val genres: List<String>,
    val tags: List<String>,
    val logoUrl: String?,
    val isActive: Boolean = true,
)

data class EventSeed(
    val id: Long,
    val clubId: Long,
    val startUtc: Instant,
    val endUtc: Instant,
    val title: String?,
    val isSpecial: Boolean,
)

data class HallSeed(
    val id: Long,
    val clubId: Long,
    val name: String,
    val isActive: Boolean = true,
    val layoutRevision: Long = 1,
    val geometryJson: String,
    val zones: List<HallZoneSeed>,
    val tables: List<HallTableSeed>,
)

data class HallZoneSeed(
    val id: String,
    val name: String,
    val tags: List<String>,
    val order: Int,
)

data class HallTableSeed(
    val id: Long,
    val tableNumber: Int,
    val zoneId: String,
    val label: String,
    val capacity: Int,
    val minimumTier: String,
    val minDeposit: Long,
    val zone: String?,
    val arrivalWindow: ArrivalWindow?,
    val mysteryEligible: Boolean,
    val x: Double,
    val y: Double,
    val isActive: Boolean = true,
)
