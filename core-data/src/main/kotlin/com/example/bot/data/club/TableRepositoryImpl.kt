package com.example.bot.data.club

import com.example.bot.club.Table
import com.example.bot.club.TableRepository
import com.example.bot.data.booking.TablesTable
import com.example.bot.data.db.withTxRetry
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

class TableRepositoryImpl(private val database: Database) : TableRepository {
    override suspend fun listByClub(clubId: Long): List<Table> {
        return withTxRetry {
            transaction(database) {
                TablesTable
                    .selectAll()
                    .where { TablesTable.clubId eq clubId }
                    .orderBy(TablesTable.tableNumber, SortOrder.ASC)
                    .map { it.toTable() }
            }
        }
    }

    override suspend fun get(id: Long): Table? {
        return withTxRetry {
            transaction(database) {
                TablesTable
                    .selectAll()
                    .where { TablesTable.id eq id }
                    .firstOrNull()
                    ?.toTable()
            }
        }
    }

    private fun ResultRow.toTable(): Table {
        return Table(
            id = this[TablesTable.id],
            clubId = this[TablesTable.clubId],
            zoneId = this[TablesTable.zoneId],
            number = this[TablesTable.tableNumber],
            capacity = this[TablesTable.capacity],
            minDeposit = this[TablesTable.minDeposit],
            active = this[TablesTable.active],
        )
    }
}
