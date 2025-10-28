package com.example.bot.club

import java.math.BigDecimal

/**
 * Repository exposing club tables for read models.
 */
interface TableRepository {
    /** Lists tables belonging to [clubId]. */
    suspend fun listByClub(clubId: Long): List<Table>

    /** Loads a table by its identifier. */
    suspend fun get(id: Long): Table?
}

/** Representation of a club table. */
data class Table(
    val id: Long,
    val clubId: Long,
    val zoneId: Long?,
    val number: Int,
    val capacity: Int,
    val minDeposit: BigDecimal,
    val active: Boolean,
)
