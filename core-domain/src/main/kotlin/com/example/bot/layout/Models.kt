package com.example.bot.layout

enum class TableStatus { FREE, HOLD, BOOKED }

data class Zone(
    val id: String,
    val name: String,
    val tags: List<String>,
    val order: Int,
)

data class Table(
    val id: Long,
    val zoneId: String,
    val label: String,
    val capacity: Int,
    val minimumTier: String,
    val status: TableStatus,
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
