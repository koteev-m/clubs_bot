package com.example.bot.data.booking

import com.example.bot.data.db.isUniqueViolation
import com.example.bot.data.db.toOffsetDateTimeUtc
import com.example.bot.data.db.withRetriedTx
import com.example.bot.data.finance.ShiftReportStatus
import com.example.bot.data.finance.ShiftReportsTable
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.sum
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.Locale
import java.util.UUID

class ShiftClosedForDepositMutationException(
    val clubId: Long,
    val nightStartUtc: Instant,
    operation: String,
) : IllegalStateException("Shift report is closed for clubId=$clubId nightStartUtc=$nightStartUtc operation=$operation")

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

private data class TableDepositOperationSnapshot(
    val amountMinor: Long,
    val allocations: List<TableDepositAllocation>,
    val updatedAt: Instant,
    val updatedBy: Long,
    val reason: String?,
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

    suspend fun findActiveSession(
        clubId: Long,
        nightStartUtc: Instant,
        tableId: Long,
    ): TableSession? {
        val nightStart = nightStartUtc.toOffsetDateTimeUtc()
        return withRetriedTx(name = "tableSession.findActive", readOnly = true, database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                findOpenSession(clubId, nightStart, tableId)
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
        return try {
            withRetriedTx(name = "tableDeposit.create", database = db) {
                newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                    ensureShiftOpenForDeposits(clubId = clubId, nightStartUtc = nightStartUtc, operation = "create")
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
                    appendOperation(
                        depositId = depositId,
                        sessionId = sessionId,
                        clubId = clubId,
                        nightStartUtc = nightStartUtc,
                        type = TableDepositOperationType.INITIAL,
                        amountMinor = amountMinor,
                        allocations = normalizedAllocations,
                        actorId = actorId,
                        reason = null,
                        paymentId = paymentId,
                        createdAt = now,
                    )
                    val row =
                        TableDepositsTable
                            .selectAll()
                            .where { TableDepositsTable.id eq depositId }
                            .limit(1)
                            .firstOrNull()
                            ?: error("Failed to load inserted table deposit for clubId=$clubId sessionId=$sessionId")
                    row.toTableDeposit(snapshotForDeposit(row))
                }
            }
        } catch (ex: Throwable) {
            if (ex.isShiftClosedViolation()) {
                throw ShiftClosedForDepositMutationException(clubId, nightStartUtc, "create")
            }
            throw ex
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
        var existingNightStartUtc: Instant? = null
        return try {
            withRetriedTx(name = "tableDeposit.update", database = db) {
                newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                    val existing =
                        TableDepositsTable
                            .selectAll()
                            .where {
                                (TableDepositsTable.id eq depositId) and
                                    (TableDepositsTable.clubId eq clubId)
                            }.forUpdate()
                            .limit(1)
                            .firstOrNull()
                            ?: error("Table deposit not found for clubId=$clubId depositId=$depositId")
                    existingNightStartUtc = existing[TableDepositsTable.nightStartUtc].toInstant()
                    ensureShiftOpenForDeposits(clubId = clubId, nightStartUtc = existingNightStartUtc, operation = "update")
                    val currentSnapshot = snapshotForDeposit(existing)
                    val adjustmentAmount = amountMinor - currentSnapshot.amountMinor
                    val adjustmentAllocations =
                        allocationDeltas(
                            depositId = depositId,
                            current = currentSnapshot.allocations,
                            target = normalizedAllocations,
                        )
                    appendOperation(
                        depositId = depositId,
                        sessionId = existing[TableDepositsTable.tableSessionId],
                        clubId = clubId,
                        nightStartUtc = existingNightStartUtc!!,
                        type = TableDepositOperationType.ADJUSTMENT,
                        amountMinor = adjustmentAmount,
                        allocations = adjustmentAllocations,
                        actorId = actorId,
                        reason = reason,
                        paymentId = null,
                        createdAt = now,
                    )
                    val updatedSnapshot = snapshotForDeposit(existing)
                    existing.toTableDeposit(updatedSnapshot)
                }
            }
        } catch (ex: Throwable) {
            if (ex.isShiftClosedViolation()) {
                throw ShiftClosedForDepositMutationException(clubId, existingNightStartUtc ?: Instant.EPOCH, "update")
            }
            throw ex
        }
    }

    suspend fun sumDepositsForNight(
        clubId: Long,
        nightStartUtc: Instant,
    ): Long {
        val nightStart = nightStartUtc.toOffsetDateTimeUtc()
        val amountSum = TableDepositOperationsTable.amountMinor.sum()
        return withRetriedTx(name = "tableDeposit.sumNight", readOnly = true, database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                TableDepositOperationsTable
                    .slice(amountSum)
                    .selectAll()
                    .where {
                        (TableDepositOperationsTable.clubId eq clubId) and
                            (TableDepositOperationsTable.nightStartUtc eq nightStart)
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
        val sumAmount = TableDepositOperationAllocationsTable.amountMinor.sum()
        return withRetriedTx(name = "tableDeposit.summaryNight", readOnly = true, database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                TableDepositOperationAllocationsTable
                    .join(
                        TableDepositOperationsTable,
                        JoinType.INNER,
                        additionalConstraint = {
                            TableDepositOperationAllocationsTable.operationId eq TableDepositOperationsTable.id
                        },
                    ).slice(TableDepositOperationAllocationsTable.categoryCode, sumAmount)
                    .selectAll()
                    .where {
                        (TableDepositOperationsTable.clubId eq clubId) and
                            (TableDepositOperationsTable.nightStartUtc eq nightStart)
                    }.groupBy(TableDepositOperationAllocationsTable.categoryCode)
                    .associate {
                        it[TableDepositOperationAllocationsTable.categoryCode] to (it[sumAmount] ?: 0L)
                    }.filterValues { it != 0L }
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
                rows.map { row -> row.toTableDeposit(snapshotForDeposit(row)) }
            }
        }

    suspend fun findById(
        clubId: Long,
        depositId: Long,
    ): TableDeposit? =
        withRetriedTx(name = "tableDeposit.findById", readOnly = true, database = db) {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                val row =
                    TableDepositsTable
                        .selectAll()
                        .where {
                            (TableDepositsTable.id eq depositId) and
                                (TableDepositsTable.clubId eq clubId)
                        }.limit(1)
                        .firstOrNull()
                        ?: return@newSuspendedTransaction null
                row.toTableDeposit(snapshotForDeposit(row))
            }
        }

    private fun appendOperation(
        depositId: Long,
        sessionId: Long,
        clubId: Long,
        nightStartUtc: Instant,
        type: TableDepositOperationType,
        amountMinor: Long,
        allocations: List<AllocationInput>,
        actorId: Long,
        reason: String?,
        paymentId: UUID?,
        createdAt: Instant,
    ) {
        val operationId =
            TableDepositOperationsTable.insert {
                it[TableDepositOperationsTable.depositId] = depositId
                it[TableDepositOperationsTable.sessionId] = sessionId
                it[TableDepositOperationsTable.clubId] = clubId
                it[TableDepositOperationsTable.nightStartUtc] = nightStartUtc.toOffsetDateTimeUtc()
                it[TableDepositOperationsTable.type] = type.name
                it[TableDepositOperationsTable.amountMinor] = amountMinor
                it[TableDepositOperationsTable.createdAt] = createdAt.toOffsetDateTimeUtc()
                it[TableDepositOperationsTable.actorId] = actorId
                it[TableDepositOperationsTable.reason] = reason?.trim()?.ifBlank { null }
                it[TableDepositOperationsTable.paymentId] = paymentId
            }[TableDepositOperationsTable.id]
        allocations.forEach { allocation ->
            TableDepositOperationAllocationsTable.insert {
                it[TableDepositOperationAllocationsTable.operationId] = operationId
                it[TableDepositOperationAllocationsTable.categoryCode] = allocation.categoryCode
                it[TableDepositOperationAllocationsTable.amountMinor] = allocation.amountMinor
            }
        }
    }

    private fun snapshotForDeposit(depositRow: ResultRow): TableDepositOperationSnapshot {
        val depositId = depositRow[TableDepositsTable.id]
        val amountSum = TableDepositOperationsTable.amountMinor.sum()
        val amountMinor =
            TableDepositOperationsTable
                .slice(amountSum)
                .selectAll()
                .where { TableDepositOperationsTable.depositId eq depositId }
                .firstOrNull()
                ?.get(amountSum)
                ?: 0L
        val allocations = allocationSnapshotForDeposit(depositId)
        val lastOperation =
            TableDepositOperationsTable
                .selectAll()
                .where { TableDepositOperationsTable.depositId eq depositId }
                .orderBy(TableDepositOperationsTable.id to SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?: error("Table deposit ledger is empty for depositId=$depositId")
        val snapshotMetadata = resolveSnapshotMetadata(depositRow = depositRow, lastOperation = lastOperation)
        return TableDepositOperationSnapshot(
            amountMinor = amountMinor,
            allocations = allocations,
            updatedAt = snapshotMetadata.updatedAt,
            updatedBy = snapshotMetadata.updatedBy,
            reason = snapshotMetadata.reason,
        )
    }

    private fun resolveSnapshotMetadata(
        depositRow: ResultRow,
        lastOperation: ResultRow,
    ): TableDepositOperationSnapshot {
        val lastOperationUpdatedAt = lastOperation[TableDepositOperationsTable.createdAt].toInstant()
        val legacyUpdatedAt = depositRow[TableDepositsTable.updatedAt].toInstant()
        val useLegacyMetadata =
            lastOperation[TableDepositOperationsTable.type] == TableDepositOperationType.INITIAL.name &&
                legacyUpdatedAt.isAfter(lastOperationUpdatedAt)
        return if (useLegacyMetadata) {
            TableDepositOperationSnapshot(
                amountMinor = 0,
                allocations = emptyList(),
                updatedAt = legacyUpdatedAt,
                updatedBy = depositRow[TableDepositsTable.updatedBy],
                reason = depositRow[TableDepositsTable.updateReason],
            )
        } else {
            TableDepositOperationSnapshot(
                amountMinor = 0,
                allocations = emptyList(),
                updatedAt = lastOperationUpdatedAt,
                updatedBy = lastOperation[TableDepositOperationsTable.actorId],
                reason = lastOperation[TableDepositOperationsTable.reason],
            )
        }
    }

    private fun allocationSnapshotForDeposit(depositId: Long): List<TableDepositAllocation> {
        val sumAmount = TableDepositOperationAllocationsTable.amountMinor.sum()
        val rows =
            TableDepositOperationAllocationsTable
                .join(
                    TableDepositOperationsTable,
                    JoinType.INNER,
                    additionalConstraint = {
                        TableDepositOperationAllocationsTable.operationId eq TableDepositOperationsTable.id
                    },
                ).slice(TableDepositOperationAllocationsTable.categoryCode, sumAmount)
                .selectAll()
                .where { TableDepositOperationsTable.depositId eq depositId }
                .groupBy(TableDepositOperationAllocationsTable.categoryCode)
                .toList()
        return rows
            .map {
                TableDepositAllocation(
                    depositId = depositId,
                    categoryCode = it[TableDepositOperationAllocationsTable.categoryCode],
                    amountMinor = it[sumAmount] ?: 0L,
                )
            }.filter { it.amountMinor != 0L }
            .sortedBy { it.categoryCode }
    }

    private fun allocationDeltas(
        depositId: Long,
        current: List<TableDepositAllocation>,
        target: List<AllocationInput>,
    ): List<AllocationInput> {
        val currentByCode = current.associate { it.categoryCode to it.amountMinor }
        val targetByCode = target.associate { it.categoryCode to it.amountMinor }
        val allCodes = (currentByCode.keys + targetByCode.keys).sorted()
        return allCodes.mapNotNull { code ->
            val delta = (targetByCode[code] ?: 0L) - (currentByCode[code] ?: 0L)
            if (delta == 0L) {
                null
            } else {
                AllocationInput(categoryCode = code, amountMinor = delta)
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

    private fun ensureShiftOpenForDeposits(
        clubId: Long,
        nightStartUtc: Instant,
        operation: String,
    ) {
        val closedShiftExists =
            ShiftReportsTable
                .selectAll()
                .where {
                    (ShiftReportsTable.clubId eq clubId) and
                        (ShiftReportsTable.nightStartUtc eq nightStartUtc.toOffsetDateTimeUtc()) and
                        (ShiftReportsTable.status eq ShiftReportStatus.CLOSED.name)
                }.limit(1)
                .any()
        if (closedShiftExists) {
            throw ShiftClosedForDepositMutationException(
                clubId = clubId,
                nightStartUtc = nightStartUtc,
                operation = operation,
            )
        }
    }
}

private fun Throwable.isShiftClosedViolation(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is ExposedSQLException && current.message?.contains("shift_report_closed", ignoreCase = true) == true) {
            return true
        }
        if (current.message?.contains("shift_report_closed", ignoreCase = true) == true) {
            return true
        }
        current = current.cause
    }
    return false
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

private fun ResultRow.toTableDeposit(snapshot: TableDepositOperationSnapshot): TableDeposit =
    TableDeposit(
        id = this[TableDepositsTable.id],
        clubId = this[TableDepositsTable.clubId],
        nightStartUtc = this[TableDepositsTable.nightStartUtc].toInstant(),
        tableId = this[TableDepositsTable.tableId],
        tableSessionId = this[TableDepositsTable.tableSessionId],
        paymentId = this[TableDepositsTable.paymentId],
        bookingId = this[TableDepositsTable.bookingId],
        guestUserId = this[TableDepositsTable.guestUserId],
        amountMinor = snapshot.amountMinor,
        createdAt = this[TableDepositsTable.createdAt].toInstant(),
        createdBy = this[TableDepositsTable.createdBy],
        updatedAt = snapshot.updatedAt,
        updatedBy = snapshot.updatedBy,
        updateReason = snapshot.reason,
        allocations = snapshot.allocations,
    )
