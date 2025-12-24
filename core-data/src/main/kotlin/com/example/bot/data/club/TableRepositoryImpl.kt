package com.example.bot.data.club

import com.example.bot.club.Table
import com.example.bot.club.TableRepository
import com.example.bot.data.booking.TablesTable
import com.example.bot.data.db.withRetriedTx
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll

class TableRepositoryImpl(
    private val database: Database,
) : TableRepository {
    override suspend fun listByClub(clubId: Long): List<Table> =
        withRetriedTx(name = "tables.listByClub", readOnly = true, database = database) {
            TablesTable
                .selectAll()
                .where { TablesTable.clubId eq clubId }
                .orderBy(TablesTable.tableNumber, SortOrder.ASC)
                .map { it.toTable() }
        }

    override suspend fun get(id: Long): Table? =
        withRetriedTx(name = "tables.get", readOnly = true, database = database) {
            TablesTable
                .selectAll()
                .where { TablesTable.id eq id }
                .firstOrNull()
                ?.toTable()
        }

    private fun ResultRow.toTable(): Table =
        Table(
            id = this[TablesTable.id],
            clubId = this[TablesTable.clubId],
            zoneId = this[TablesTable.zoneId],
            number = this[TablesTable.tableNumber],
            capacity = this[TablesTable.capacity],
            minDeposit = this[TablesTable.minDeposit],
            active = this[TablesTable.active],
        )
}
