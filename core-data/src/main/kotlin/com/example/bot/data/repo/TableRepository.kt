package com.example.bot.data.repo

import com.example.bot.availability.Table

/**
 * Repository exposing table information.
 */
interface TableRepository {
    /**
     * Finds a table by its identifier within a club.
     */
    suspend fun findTable(
        clubId: Long,
        tableId: Long,
    ): Table?
}
