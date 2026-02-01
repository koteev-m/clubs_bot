package com.example.bot.data.booking

import com.example.bot.data.db.isUniqueViolation
import com.example.bot.data.db.toOffsetDateTimeUtc
import com.example.bot.data.db.withRetriedTx
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.sum
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.Locale
import java.util.UUID

enum class TableSessionStatus {
    OPEN,
    CLOSED,
}

data class TableSession(
    val id: Long,
    val clubId: Long,
    val nightStartUtc: Instant,
    val tableId: Long,
    val status: TableSessionStatus,
    val openedAt: Instant,
    val closedAt: Instant?,
    val openedBy: Long,
    val closedBy: Long?,
    val note: String?,
)

data class TableDeposit(
    val id: Long,
    val clubId: Long,
    val nightStartUtc: Instant,
    val tableId: Long,
    val tableSessionId: Long,
    val paymentId: UUID?,
    val bookingId: UUID?,
    val guestUserId: Long?,
    val amountMinor: Long,
    val createdAt: Instant,
    val createdBy: Long,
    val updatedAt: Instant,
    val updatedBy: Long,
    val updateReason: String?,
    val allocations: List<TableDepositAllocation>,
)

data class TableDepositAllocation(
    val depositId: Long,
    val categoryCode: String,
    val amountMinor: Long,
)

data class AllocationInput(
    val categoryCode: String,
    val amountMinor: Long,
)

class TableSessionRepository(
    private val db: Database,
) {
    suspend fun openSession(
        clubId: Long,
        nightStartUtc: Instant,
        tableId: Long,
        actorId: Long,
        now: Instant,
        note: String?,
    ): TableSession {
        val nightStart = nightStartUtc.toOffsetDateTimeUtc()
        val openedAt = now.toOffsetDateTimeUtc()
        return withRetriedTx(name = "tableSession.open", database = db) {
            var lastConflict: Throwable? = null
            repeat(2) {
                try {
                    return@withRetriedTx newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                        val sessionId =
                            TableSessionsTable.insert {
                                it[TableSessionsTable.clubId] = clubId
                                it[TableSessionsTable.nightStartUtc] = nightStart
                                it[TableSessionsTable.tableId] = tableId
                                it[TableSessionsTable.status] = TableSessionStatus.OPEN.name
                                it[TableSessionsTable.openMarker] = 1.toShort()
                                it[TableSessionsTable.openedAt] = openedAt
                                it[TableSessionsTable.openedBy] = actorId
                                it[TableSessionsTable.note] = note
                            }[TableSessionsTable.id]
                        val row =
                            TableSessionsTable
                                .selectAll()
                                .where { TableSessionsTable.id eq sessionId }
                                .limit(1)
                                .firstOrNull()
                                ?: error("Failed to load inserted table session for clubId=$clubId tableId=$tableId")
                        row.toTableSession()
                    }
                } catch (ex: Throwable) {
                    if (!ex.isUniqueViolation()) throw ex
                    val existing =
                        newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                            findOpenSession(clubId, nightStart, tableId)
                        }
                    if (existing != null) return@withRetriedTx existing
                    lastConflict = ex
                }
            }
            throw lastConflict ?: error("Failed to open table session for clubId=$clubId tableId=$tableId")
        }
    }

    suspend fun closeSession(
        sessionId: Long,
        clubId: Long,
        actorId: Long,
        now: Instant,
    ): Boolean =
        withRetriedTx(name = "tableSession.close", database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                TableSessionsTable.update({
                    (TableSessionsTable.id eq sessionId) and
                        (TableSessionsTable.clubId eq clubId) and
                        (TableSessionsTable.status eq TableSessionStatus.OPEN.name)
                }) {
                    it[status] = TableSessionStatus.CLOSED.name
                    it[openMarker] = null
                    it[closedAt] = now.toOffsetDateTimeUtc()
                    it[closedBy] = actorId
                } > 0
            }
        }

    suspend fun listActive(
        clubId: Long,
        nightStartUtc: Instant,
    ): List<TableSession> {
        val nightStart = nightStartUtc.toOffsetDateTimeUtc()
        return withRetriedTx(name = "tableSession.listActive", readOnly = true, database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                TableSessionsTable
                    .selectAll()
                    .where {
                        (TableSessionsTable.clubId eq clubId) and
                            (TableSessionsTable.nightStartUtc eq nightStart) and
                            (TableSessionsTable.status eq TableSessionStatus.OPEN.name)
                    }.map { it.toTableSession() }
            }
        }
    }

    private fun findOpenSession(
        clubId: Long,
        nightStart: java.time.OffsetDateTime,
        tableId: Long,
    ): TableSession? =
        TableSessionsTable
            .selectAll()
            .where {
                (TableSessionsTable.clubId eq clubId) and
                    (TableSessionsTable.nightStartUtc eq nightStart) and
                    (TableSessionsTable.tableId eq tableId) and
                    (TableSessionsTable.status eq TableSessionStatus.OPEN.name)
            }.limit(1)
            .firstOrNull()
            ?.toTableSession()
}

class TableDepositRepository(
    private val db: Database,
) {
    suspend fun createDeposit(
        clubId: Long,
        nightStartUtc: Instant,
        tableId: Long,
        sessionId: Long,
        guestUserId: Long?,
        bookingId: UUID?,
        paymentId: UUID?,
        amountMinor: Long,
        allocations: List<AllocationInput>,
        actorId: Long,
        now: Instant,
    ): TableDeposit {
        val normalizedAllocations = normalizeAllocations(allocations)
        validateAllocations(amountMinor, normalizedAllocations)
        val nightStart = nightStartUtc.toOffsetDateTimeUtc()
        val nowUtc = now.toOffsetDateTimeUtc()
        return withRetriedTx(name = "tableDeposit.create", database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                val depositId =
                    TableDepositsTable.insert {
                        it[TableDepositsTable.clubId] = clubId
                        it[TableDepositsTable.nightStartUtc] = nightStart
                        it[TableDepositsTable.tableId] = tableId
                        it[TableDepositsTable.tableSessionId] = sessionId
                        it[TableDepositsTable.paymentId] = paymentId
                        it[TableDepositsTable.bookingId] = bookingId
                        it[TableDepositsTable.guestUserId] = guestUserId
                        it[TableDepositsTable.amountMinor] = amountMinor
                        it[TableDepositsTable.createdAt] = nowUtc
                        it[TableDepositsTable.createdBy] = actorId
                        it[TableDepositsTable.updatedAt] = nowUtc
                        it[TableDepositsTable.updatedBy] = actorId
                        it[TableDepositsTable.updateReason] = null
                    }[TableDepositsTable.id]
                val savedAllocations = insertAllocations(depositId, normalizedAllocations)
                val row =
                    TableDepositsTable
                        .selectAll()
                        .where { TableDepositsTable.id eq depositId }
                        .limit(1)
                        .firstOrNull()
                        ?: error("Failed to load inserted table deposit for clubId=$clubId sessionId=$sessionId")
                row.toTableDeposit(savedAllocations)
            }
        }
    }

    suspend fun updateDeposit(
        clubId: Long,
        depositId: Long,
        amountMinor: Long,
        allocations: List<AllocationInput>,
        reason: String,
        actorId: Long,
        now: Instant,
    ): TableDeposit {
        require(reason.trim().isNotEmpty()) { "Update reason must be provided" }
        val normalizedAllocations = normalizeAllocations(allocations)
        validateAllocations(amountMinor, normalizedAllocations)
        val nowUtc = now.toOffsetDateTimeUtc()
        return withRetriedTx(name = "tableDeposit.update", database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                val updated =
                    TableDepositsTable.update({
                        (TableDepositsTable.id eq depositId) and
                            (TableDepositsTable.clubId eq clubId)
                    }) {
                        it[TableDepositsTable.amountMinor] = amountMinor
                        it[TableDepositsTable.updatedAt] = nowUtc
                        it[TableDepositsTable.updatedBy] = actorId
                        it[TableDepositsTable.updateReason] = reason
                    }
                if (updated == 0) {
                    error("Table deposit not found for clubId=$clubId depositId=$depositId")
                }
                TableDepositAllocationsTable.deleteWhere { TableDepositAllocationsTable.depositId eq depositId }
                val savedAllocations = insertAllocations(depositId, normalizedAllocations)
                val row =
                    TableDepositsTable
                        .selectAll()
                        .where {
                            (TableDepositsTable.id eq depositId) and
                                (TableDepositsTable.clubId eq clubId)
                        }.limit(1)
                        .firstOrNull()
                        ?: error("Failed to load updated table deposit for clubId=$clubId depositId=$depositId")
                row.toTableDeposit(savedAllocations)
            }
        }
    }

    suspend fun sumDepositsForNight(
        clubId: Long,
        nightStartUtc: Instant,
    ): Long {
        val nightStart = nightStartUtc.toOffsetDateTimeUtc()
        val amountSum = TableDepositsTable.amountMinor.sum()
        return withRetriedTx(name = "tableDeposit.sumNight", readOnly = true, database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                TableDepositsTable
                    .slice(amountSum)
                    .selectAll()
                    .where {
                        (TableDepositsTable.clubId eq clubId) and
                            (TableDepositsTable.nightStartUtc eq nightStart)
                    }.firstOrNull()
                    ?.get(amountSum)
                    ?: 0L
            }
        }
    }

    suspend fun allocationSummaryForNight(
        clubId: Long,
        nightStartUtc: Instant,
    ): Map<String, Long> {
        val nightStart = nightStartUtc.toOffsetDateTimeUtc()
        val sumAmount = TableDepositAllocationsTable.amountMinor.sum()
        return withRetriedTx(name = "tableDeposit.summaryNight", readOnly = true, database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                TableDepositAllocationsTable
                    .join(
                        TableDepositsTable,
                        JoinType.INNER,
                        additionalConstraint = { TableDepositAllocationsTable.depositId eq TableDepositsTable.id },
                    ).slice(TableDepositAllocationsTable.categoryCode, sumAmount)
                    .selectAll()
                    .where {
                        (TableDepositsTable.clubId eq clubId) and
                            (TableDepositsTable.nightStartUtc eq nightStart)
                    }.groupBy(TableDepositAllocationsTable.categoryCode)
                    .associate {
                        it[TableDepositAllocationsTable.categoryCode] to (it[sumAmount] ?: 0L)
                    }
            }
        }
    }

    suspend fun listDepositsForSession(
        clubId: Long,
        sessionId: Long,
    ): List<TableDeposit> =
        withRetriedTx(name = "tableDeposit.listForSession", readOnly = true, database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                val rows =
                    TableDepositsTable
                        .selectAll()
                        .where {
                            (TableDepositsTable.clubId eq clubId) and
                                (TableDepositsTable.tableSessionId eq sessionId)
                        }.toList()
                if (rows.isEmpty()) return@newSuspendedTransaction emptyList()
                val allocations = loadAllocations(rows.map { it[TableDepositsTable.id] })
                rows.map { row ->
                    row.toTableDeposit(allocations[row[TableDepositsTable.id]].orEmpty())
                }
            }
        }

    private fun validateAllocations(
        amountMinor: Long,
        allocations: List<AllocationInput>,
    ) {
        require(amountMinor >= 0) { "Deposit amount must be non-negative" }
        allocations.forEach { require(it.amountMinor >= 0) { "Allocation amounts must be non-negative" } }
        val total = allocations.sumOf { it.amountMinor }
        if (amountMinor == 0L) {
            require(total == 0L) { "Allocations total must be 0 when amount is 0" }
        } else {
            require(total == amountMinor) { "Allocations total must match deposit amount" }
        }
    }

    private fun normalizeAllocations(
        allocations: List<AllocationInput>,
    ): List<AllocationInput> {
        val normalized =
            allocations.map { allocation ->
                val normalizedCode = allocation.categoryCode.trim().uppercase(Locale.ROOT)
                require(normalizedCode.isNotBlank()) { "Allocation categoryCode must not be blank" }
                allocation.copy(categoryCode = normalizedCode)
            }
        val duplicates =
            normalized.groupBy { it.categoryCode }
                .filterValues { it.size > 1 }
                .keys
        require(duplicates.isEmpty()) {
            "Duplicate allocation categoryCode: ${duplicates.sorted().joinToString()}"
        }
        return normalized
    }

    private fun insertAllocations(
        depositId: Long,
        allocations: List<AllocationInput>,
    ): List<TableDepositAllocation> {
        if (allocations.isEmpty()) return emptyList()
        allocations.forEach { allocation ->
            TableDepositAllocationsTable.insert {
                it[TableDepositAllocationsTable.depositId] = depositId
                it[TableDepositAllocationsTable.categoryCode] = allocation.categoryCode
                it[TableDepositAllocationsTable.amountMinor] = allocation.amountMinor
            }
        }
        return allocations.map {
            TableDepositAllocation(
                depositId = depositId,
                categoryCode = it.categoryCode,
                amountMinor = it.amountMinor,
            )
        }
    }

    private fun loadAllocations(depositIds: List<Long>): Map<Long, List<TableDepositAllocation>> {
        if (depositIds.isEmpty()) return emptyMap()
        val rows =
            TableDepositAllocationsTable
                .selectAll()
                .where { TableDepositAllocationsTable.depositId inList depositIds }
        val result = LinkedHashMap<Long, MutableList<TableDepositAllocation>>()
        for (row in rows) {
            val depositId = row[TableDepositAllocationsTable.depositId]
            val list = result.getOrPut(depositId) { mutableListOf() }
            list += row.toAllocation()
        }
        return result
    }
}

private fun ResultRow.toTableSession(): TableSession =
    TableSession(
        id = this[TableSessionsTable.id],
        clubId = this[TableSessionsTable.clubId],
        nightStartUtc = this[TableSessionsTable.nightStartUtc].toInstant(),
        tableId = this[TableSessionsTable.tableId],
        status = TableSessionStatus.valueOf(this[TableSessionsTable.status]),
        openedAt = this[TableSessionsTable.openedAt].toInstant(),
        closedAt = this[TableSessionsTable.closedAt]?.toInstant(),
        openedBy = this[TableSessionsTable.openedBy],
        closedBy = this[TableSessionsTable.closedBy],
        note = this[TableSessionsTable.note],
    )

private fun ResultRow.toTableDeposit(allocations: List<TableDepositAllocation>): TableDeposit =
    TableDeposit(
        id = this[TableDepositsTable.id],
        clubId = this[TableDepositsTable.clubId],
        nightStartUtc = this[TableDepositsTable.nightStartUtc].toInstant(),
        tableId = this[TableDepositsTable.tableId],
        tableSessionId = this[TableDepositsTable.tableSessionId],
        paymentId = this[TableDepositsTable.paymentId],
        bookingId = this[TableDepositsTable.bookingId],
        guestUserId = this[TableDepositsTable.guestUserId],
        amountMinor = this[TableDepositsTable.amountMinor],
        createdAt = this[TableDepositsTable.createdAt].toInstant(),
        createdBy = this[TableDepositsTable.createdBy],
        updatedAt = this[TableDepositsTable.updatedAt].toInstant(),
        updatedBy = this[TableDepositsTable.updatedBy],
        updateReason = this[TableDepositsTable.updateReason],
        allocations = allocations,
    )

private fun ResultRow.toAllocation(): TableDepositAllocation =
    TableDepositAllocation(
        depositId = this[TableDepositAllocationsTable.depositId],
        categoryCode = this[TableDepositAllocationsTable.categoryCode],
        amountMinor = this[TableDepositAllocationsTable.amountMinor],
    )
