package com.example.bot.data.repo

import com.example.bot.data.db.Clubs
import com.example.bot.data.db.withTxRetry
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Exposed-based implementation of [ClubRepository].
 */
class ExposedClubRepository(private val database: Database) : ClubRepository {
    override suspend fun listClubs(limit: Int): List<ClubDto> {
        return withTxRetry {
            transaction(database) {
                Clubs
                    .selectAll()
                    .orderBy(Clubs.name to SortOrder.ASC)
                    .limit(limit)
                    .map { it.toClubDto() }
            }
        }
    }

    private fun ResultRow.toClubDto(): ClubDto {
        return ClubDto(
            id = this[Clubs.id].value.toLong(),
            name = this[Clubs.name],
            shortDescription = this[Clubs.description],
        )
    }
}
