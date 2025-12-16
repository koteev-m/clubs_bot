package com.example.bot.layout

import java.time.LocalTime

enum class TableStatus { FREE, HOLD, BOOKED }

data class Zone(
    val id: String,
    val name: String,
    val tags: List<String>,
    val order: Int,
)

data class ArrivalWindow(val from: LocalTime, val to: LocalTime)

data class Table(
    val id: Long,
    /**
     * Идентификатор зоны из layout (zone.id). Используется как источник правды для принадлежности столика к зоне.
     */
    val zoneId: String,
    val label: String,
    val capacity: Int,
    val minimumTier: String,
    val status: TableStatus,
    val minDeposit: Long = 0,
    /** Дублирует идентификатор зоны для обратной совместимости с контрактами layout/Admin API. */
    val zone: String? = null,
    val arrivalWindow: ArrivalWindow? = null,
    val mysteryEligible: Boolean = false,
)

data class ClubLayout(
    val clubId: Long,
    val eventId: Long?,
    val zones: List<Zone>,
    val tables: List<Table>,
    val assets: LayoutAssets,
)

data class LayoutAssets(
    val geometryUrl: String,
    val fingerprint: String,
)
