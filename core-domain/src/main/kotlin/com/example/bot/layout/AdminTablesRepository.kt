package com.example.bot.layout

import java.time.Instant

data class AdminTableCreate(
    val clubId: Long,
    val label: String,
    val minDeposit: Long,
    val capacity: Int,
    val zone: String?,
    val arrivalWindow: ArrivalWindow?,
    val mysteryEligible: Boolean,
)

data class AdminTableUpdate(
    val id: Long,
    val clubId: Long,
    val label: String?,
    val minDeposit: Long?,
    val capacity: Int?,
    val zone: String?,
    val arrivalWindow: ArrivalWindow?,
    val mysteryEligible: Boolean?,
)

interface AdminTablesRepository {
    suspend fun listForClub(clubId: Long): List<Table>

    suspend fun listZonesForClub(clubId: Long): List<Zone>

    suspend fun create(request: AdminTableCreate): Table

    suspend fun update(request: AdminTableUpdate): Table?

    /**
     * Watermark for admin table modifications for the given club.
     * Should advance whenever tables are created or updated so layout caches can react to changes.
     */
    suspend fun lastUpdatedAt(clubId: Long): Instant?
}
