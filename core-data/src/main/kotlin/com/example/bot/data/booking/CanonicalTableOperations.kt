package com.example.bot.data.booking

import com.example.bot.audit.StandardAuditEntityType
import com.example.bot.data.audit.AuditLogTable
import com.example.bot.data.client.onboarding.LockedCanonicalNight
import com.example.bot.data.client.onboarding.OnboardingSql
import com.example.bot.data.client.onboarding.active
import com.example.bot.data.client.onboarding.lockLinkedCanonicalNight
import com.example.bot.data.client.onboarding.long
import com.example.bot.data.db.toOffsetDateTimeUtc
import com.example.bot.data.finance.ShiftReportStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.sql.Connection
import java.time.Clock
import java.time.Instant

internal data class PhysicalTableKey(
    val clubId: Long,
    val hallId: Long,
    val hallTableId: Long,
)

internal data class TableOperationsScope(
    val clientId: Long,
    val table: PhysicalTableKey,
    val canonicalNightId: Long,
    val eventId: Long,
    val legacyNightStart: Instant,
)

internal data class TableDepositAmount(
    val amountMinor: Long,
    val allocations: List<AllocationInput>,
)

internal data class TableOccupancy(
    val latestSessionId: Long?,
    val activeSession: TableSession?,
    val deposits: List<TableDeposit>,
    val hasBookingOrHold: Boolean,
)

internal data class TableSeating(
    val session: TableSession,
    val deposit: TableDeposit?,
)

internal sealed interface TableOperationResult<out T> {
    data class Success<T>(
        val value: T,
    ) : TableOperationResult<T>

    enum class Rejected : TableOperationResult<Nothing> {
        NIGHT_UNAVAILABLE,
        TABLE_UNAVAILABLE,
        TABLE_LINK_REQUIRED,
        TABLE_INACTIVE,
        SHIFT_NOT_OPEN,
        OCCUPIED,
        STALE_SESSION,
        DEPOSIT_UNAVAILABLE,
    }
}

/**
 * Disconnected persistence boundary. Runtime wiring must add scoped actor authorization
 * inside the transaction, before acquiring the club lock.
 */
internal class CanonicalTableOperations(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val sessions = TableSessionRepository(db)
    private val deposits = TableDepositRepository(db)

    suspend fun occupancy(scope: TableOperationsScope): TableOperationResult<TableOccupancy> =
        locked(scope, write = false) {
            val latest = latestSession()
            val active = activeSession(latest)
            val activeDeposits = active?.let { depositRows(it.id).map(deposits::depositSnapshot) }.orEmpty()
            TableOperationResult.Success(TableOccupancy(latest?.id, active, activeDeposits, hasBookingOrHold()))
        }

    /**
     * Use the last session ID observed by occupancy, including a closed session. Replaying an old
     * seating after the table has been freed cannot open a second session or post another deposit.
     */
    suspend fun seat(
        scope: TableOperationsScope,
        actorId: Long,
        expectedLastSessionId: Long?,
        initialDeposit: TableDepositAmount? = null,
    ): TableOperationResult<TableSeating> =
        locked(scope, write = true) {
            val latest = latestSession()
            when {
                !night.contains(now) -> TableOperationResult.Rejected.NIGHT_UNAVAILABLE
                activeSession(latest) != null || hasBookingOrHold() -> TableOperationResult.Rejected.OCCUPIED
                latest?.id != expectedLastSessionId -> TableOperationResult.Rejected.STALE_SESSION
                else -> {
                    val session = sessions.insertSession(clubId, night.legacyStart, bookingTableId, actorId, now, null)
                    val deposit =
                        initialDeposit?.let {
                            deposits.createDepositInTransaction(session, it.amountMinor, it.allocations, actorId, now)
                        }
                    audit(StandardAuditEntityType.TABLE_SESSION, session.id, "CREATE", actorId, session.id)
                    deposit?.let {
                        audit(StandardAuditEntityType.TABLE_DEPOSIT, it.id, "CREATE", actorId, session.id)
                    }
                    TableOperationResult.Success(TableSeating(session, deposit))
                }
            }
        }

    suspend fun free(
        scope: TableOperationsScope,
        actorId: Long,
        sessionId: Long,
    ): TableOperationResult<Unit> =
        locked(scope, write = true) {
            if (activeSession()?.id != sessionId ||
                !sessions.closeSessionInTransaction(sessionId, clubId, actorId, now)
            ) {
                return@locked TableOperationResult.Rejected.STALE_SESSION
            }
            audit(StandardAuditEntityType.TABLE_SESSION, sessionId, "CLOSE", actorId, sessionId)
            TableOperationResult.Success(Unit)
        }

    suspend fun updateDeposit(
        scope: TableOperationsScope,
        actorId: Long,
        sessionId: Long,
        depositId: Long,
        target: TableDepositAmount,
        reason: String,
    ): TableOperationResult<TableDeposit> =
        locked(scope, write = true) {
            val row =
                depositRows(sessionId, depositId).singleOrNull()
                    ?: return@locked TableOperationResult.Rejected.DEPOSIT_UNAVAILABLE
            val (deposit, operationId) =
                deposits.updateDepositInTransaction(
                    row,
                    target.amountMinor,
                    target.allocations,
                    reason,
                    actorId,
                    now,
                )
            audit(StandardAuditEntityType.TABLE_DEPOSIT, depositId, "UPDATE", actorId, sessionId, operationId)
            TableOperationResult.Success(deposit)
        }

    private suspend fun <T> locked(
        scope: TableOperationsScope,
        write: Boolean,
        operation: LockedTable.() -> TableOperationResult<T>,
    ): TableOperationResult<T> =
        newSuspendedTransaction(dispatcher, db, transactionIsolation = Connection.TRANSACTION_READ_COMMITTED) {
            // A lost commit response must not silently retry a financial adjustment.
            repetitionAttempts = 1
            val sql = OnboardingSql(connection.connection as Connection)
            val key = scope.table
            val club =
                sql.row(
                    "SELECT timezone FROM clubs WHERE id=? AND EXISTS (" +
                        "SELECT 1 FROM client_clubs WHERE club_id=clubs.id AND client_id=?) FOR UPDATE",
                    key.clubId,
                    scope.clientId,
                )
            val night =
                club
                    ?.let { sql.lockLinkedCanonicalNight(key.clubId, scope.eventId, it["timezone"]) }
                    ?.takeIf { it.id == scope.canonicalNightId && it.legacyStart == scope.legacyNightStart }
                    ?: return@newSuspendedTransaction TableOperationResult.Rejected.NIGHT_UNAVAILABLE
            when (val identity = sql.resolveTable(key)) {
                is TableOperationResult.Rejected -> identity
                is TableOperationResult.Success -> {
                    if (write && !sql.lockOpenShift(key.clubId, night.legacyStart)) {
                        TableOperationResult.Rejected.SHIFT_NOT_OPEN
                    } else {
                        LockedTable(sql, scope, identity.value, night, clock.instant()).operation()
                    }
                }
            }
        }
}

/** Resolve the explicit V072 link while holding the common club lock. IDs and numbers are never inferred. */
private fun OnboardingSql.resolveTable(key: PhysicalTableKey): TableOperationResult<Long> {
    val hallTable =
        row(
            "SELECT h.is_active AS hall_active,ht.is_active,ht.booking_table_id,ht.booking_club_id," +
                "ht.table_number,ht.capacity FROM halls h JOIN hall_tables ht ON ht.hall_id=h.id " +
                "WHERE h.club_id=? AND h.id=? AND ht.id=? FOR UPDATE",
            key.clubId,
            key.hallId,
            key.hallTableId,
        ) ?: return TableOperationResult.Rejected.TABLE_UNAVAILABLE
    val bookingId = hallTable["booking_table_id"]?.toLongOrNull()
    return when {
        bookingId == null -> TableOperationResult.Rejected.TABLE_LINK_REQUIRED
        hallTable["booking_club_id"]?.toLongOrNull() != key.clubId -> TableOperationResult.Rejected.TABLE_UNAVAILABLE
        !hallTable.active() || !hallTable.active("hall_active") -> TableOperationResult.Rejected.TABLE_INACTIVE
        else -> {
            val booking =
                row(
                    "SELECT active FROM tables WHERE id=? AND club_id=? AND table_number=? AND capacity=? FOR UPDATE",
                    bookingId,
                    key.clubId,
                    hallTable.long("table_number"),
                    hallTable.long("capacity"),
                )
            when {
                booking == null -> TableOperationResult.Rejected.TABLE_UNAVAILABLE
                !booking.active("active") -> TableOperationResult.Rejected.TABLE_INACTIVE
                else -> TableOperationResult.Success(bookingId)
            }
        }
    }
}

private fun OnboardingSql.lockOpenShift(
    clubId: Long,
    legacyStart: Instant,
): Boolean =
    row(
        "SELECT status FROM shift_reports WHERE club_id=? AND night_start_utc=? FOR UPDATE",
        clubId,
        legacyStart,
    )?.get("status") == ShiftReportStatus.DRAFT.name

private class LockedTable(
    private val sql: OnboardingSql,
    private val scope: TableOperationsScope,
    val bookingTableId: Long,
    val night: LockedCanonicalNight,
    val now: Instant,
) {
    val clubId get() = scope.table.clubId
    private val nightKey = night.legacyStart.toOffsetDateTimeUtc()

    fun latestSession(): TableSession? = findSession(openOnly = false)

    fun activeSession(latest: TableSession? = null): TableSession? =
        latest?.takeIf { it.status == TableSessionStatus.OPEN } ?: findSession(openOnly = true)

    private fun findSession(openOnly: Boolean): TableSession? =
        TableSessionsTable
            .selectAll()
            .where {
                (TableSessionsTable.clubId eq clubId) and
                    (TableSessionsTable.nightStartUtc eq nightKey) and
                    (TableSessionsTable.tableId eq bookingTableId) and
                    if (openOnly) TableSessionsTable.status eq TableSessionStatus.OPEN.name else Op.TRUE
            }.orderBy(TableSessionsTable.id to SortOrder.DESC)
            .forUpdate()
            .limit(1)
            .firstOrNull()
            ?.toTableSession()

    fun depositRows(
        sessionId: Long,
        depositId: Long? = null,
    ): List<ResultRow> =
        TableDepositsTable
            .selectAll()
            .where {
                (TableDepositsTable.clubId eq clubId) and
                    (TableDepositsTable.nightStartUtc eq nightKey) and
                    (TableDepositsTable.tableId eq bookingTableId) and
                    (TableDepositsTable.tableSessionId eq sessionId) and
                    if (depositId != null) TableDepositsTable.id eq depositId else Op.TRUE
            }.orderBy(TableDepositsTable.id to SortOrder.ASC)
            .forUpdate()
            .toList()

    fun hasBookingOrHold(): Boolean =
        sql.row(
            "SELECT 1 FROM bookings WHERE club_id=? AND table_id=? AND slot_start<? AND slot_end>? " +
                "AND status IN ('BOOKED','SEATED') LIMIT 1",
            clubId,
            bookingTableId,
            night.end,
            night.legacyStart,
        ) != null ||
            sql.row(
                "SELECT 1 FROM booking_holds bh JOIN events e ON e.id=bh.event_id " +
                    "WHERE e.club_id=? AND bh.table_id=? AND bh.slot_start<? AND bh.slot_end>? " +
                    "AND bh.expires_at>? LIMIT 1",
                clubId,
                bookingTableId,
                night.end,
                night.legacyStart,
                now,
            ) != null

    fun audit(
        type: StandardAuditEntityType,
        id: Long,
        action: String,
        actorId: Long,
        sessionId: Long,
        operationId: Long? = null,
    ) {
        AuditLogTable.insert {
            it[createdAt] = now.toOffsetDateTimeUtc()
            it[AuditLogTable.clubId] = this@LockedTable.clubId
            it[nightId] = scope.eventId
            it[actorUserId] = actorId
            it[entityType] = type.value
            it[entityId] = id
            it[AuditLogTable.action] = action
            it[fingerprint] = "canonical-table:${type.value}:$action:$id:${operationId ?: 0}"
            it[metadataJson] =
                buildJsonObject {
                    put("canonicalNightId", night.id)
                    put("nightStartUtc", night.legacyStart.toString())
                    put("hallId", scope.table.hallId)
                    put("hallTableId", scope.table.hallTableId)
                    put("tableId", bookingTableId)
                    put("sessionId", sessionId)
                    operationId?.let { put("depositOperationId", it) }
                }.toString()
        }
    }
}
