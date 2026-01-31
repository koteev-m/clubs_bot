package com.example.bot.data.promoter

import com.example.bot.data.db.isUniqueViolation
import com.example.bot.data.db.withTxRetry
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

private object PromoterBookingAssignmentsTable : Table("promoter_booking_assignments") {
    val entryId = long("entry_id")
    val bookingId = long("booking_id")
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(entryId)
}

sealed class AcquireLockResult {
    data object Acquired : AcquireLockResult()

    data class AlreadyAssigned(val bookingId: Long) : AcquireLockResult()

    data object InProgress : AcquireLockResult()
}

class PromoterBookingAssignmentsRepository(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
) {
    private data class AssignmentRow(
        val bookingId: Long,
        val createdAt: OffsetDateTime,
    )

    suspend fun acquireLock(
        entryId: Long,
        now: OffsetDateTime = now(),
        staleAfter: Duration = DEFAULT_LOCK_STALE_AFTER,
    ): AcquireLockResult {
        if (tryInsert(entryId, IN_PROGRESS_BOOKING_ID, now)) {
            return AcquireLockResult.Acquired
        }
        val current = getAssignment(entryId)
            ?: return if (tryInsert(entryId, IN_PROGRESS_BOOKING_ID, now)) {
                AcquireLockResult.Acquired
            } else {
                val refreshed = getAssignment(entryId)
                if (refreshed != null && refreshed.bookingId != IN_PROGRESS_BOOKING_ID) {
                    AcquireLockResult.AlreadyAssigned(refreshed.bookingId)
                } else {
                    AcquireLockResult.InProgress
                }
            }
        if (current.bookingId != IN_PROGRESS_BOOKING_ID) {
            return AcquireLockResult.AlreadyAssigned(current.bookingId)
        }
        val cutoff = now.minus(staleAfter)
        if (current.createdAt.isBefore(cutoff)) {
            deleteStaleLock(entryId, cutoff)
            if (tryInsert(entryId, IN_PROGRESS_BOOKING_ID, now)) {
                return AcquireLockResult.Acquired
            }
            val refreshed = getAssignment(entryId)
            if (refreshed != null && refreshed.bookingId != IN_PROGRESS_BOOKING_ID) {
                return AcquireLockResult.AlreadyAssigned(refreshed.bookingId)
            }
        }
        return AcquireLockResult.InProgress
    }

    suspend fun findBookingIdForEntry(entryId: Long): Long? =
        withTxRetry {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                PromoterBookingAssignmentsTable
                    .slice(PromoterBookingAssignmentsTable.bookingId)
                    .select { PromoterBookingAssignmentsTable.entryId eq entryId }
                    .limit(1)
                    .firstOrNull()
                    ?.get(PromoterBookingAssignmentsTable.bookingId)
            }
        }

    suspend fun findEntryIdForBooking(bookingId: Long): Long? =
        withTxRetry {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                PromoterBookingAssignmentsTable
                    .slice(PromoterBookingAssignmentsTable.entryId)
                    .select { PromoterBookingAssignmentsTable.bookingId eq bookingId }
                    .limit(1)
                    .firstOrNull()
                    ?.get(PromoterBookingAssignmentsTable.entryId)
            }
        }

    suspend fun finalizeAssignment(entryId: Long, bookingId: Long): Boolean =
        withTxRetry {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                PromoterBookingAssignmentsTable.update(
                    where = {
                        (PromoterBookingAssignmentsTable.entryId eq entryId) and
                            (PromoterBookingAssignmentsTable.bookingId eq IN_PROGRESS_BOOKING_ID)
                    },
                ) {
                    it[PromoterBookingAssignmentsTable.bookingId] = bookingId
                } > 0
            }
        }

    suspend fun releaseLock(entryId: Long): Boolean =
        withTxRetry {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                PromoterBookingAssignmentsTable.deleteWhere {
                    (PromoterBookingAssignmentsTable.entryId eq entryId) and
                        (PromoterBookingAssignmentsTable.bookingId eq IN_PROGRESS_BOOKING_ID)
                } > 0
            }
        }

    private suspend fun tryInsert(entryId: Long, bookingId: Long, createdAt: OffsetDateTime): Boolean =
        try {
            withTxRetry {
                newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                    PromoterBookingAssignmentsTable.insert {
                        it[PromoterBookingAssignmentsTable.entryId] = entryId
                        it[PromoterBookingAssignmentsTable.bookingId] = bookingId
                        it[PromoterBookingAssignmentsTable.createdAt] = createdAt
                    }
                }
            }
            true
        } catch (ex: Throwable) {
            if (ex.isUniqueViolation()) {
                false
            } else {
                throw ex
            }
        }

    private suspend fun getAssignment(entryId: Long): AssignmentRow? =
        withTxRetry {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                PromoterBookingAssignmentsTable
                    .slice(
                        PromoterBookingAssignmentsTable.bookingId,
                        PromoterBookingAssignmentsTable.createdAt,
                    )
                    .select { PromoterBookingAssignmentsTable.entryId eq entryId }
                    .limit(1)
                    .firstOrNull()
                    ?.let {
                        AssignmentRow(
                            bookingId = it[PromoterBookingAssignmentsTable.bookingId],
                            createdAt = it[PromoterBookingAssignmentsTable.createdAt],
                        )
                    }
            }
        }

    private suspend fun deleteStaleLock(entryId: Long, cutoff: OffsetDateTime): Boolean =
        withTxRetry {
            newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                PromoterBookingAssignmentsTable.deleteWhere {
                    (PromoterBookingAssignmentsTable.entryId eq entryId) and
                        (PromoterBookingAssignmentsTable.bookingId eq IN_PROGRESS_BOOKING_ID) and
                        (PromoterBookingAssignmentsTable.createdAt less cutoff)
                } > 0
            }
        }

    private fun now(): OffsetDateTime = Instant.now(clock).atOffset(ZoneOffset.UTC)

    companion object {
        const val IN_PROGRESS_BOOKING_ID = 0L
        private val DEFAULT_LOCK_STALE_AFTER = Duration.ofMinutes(20)

        fun toAssignmentBookingId(bookingId: Long): Long? = bookingId.takeUnless { it == IN_PROGRESS_BOOKING_ID }

        fun toAssignmentBookingId(bookingId: UUID): Long? = toAssignmentBookingId(bookingId.leastSignificantBits)

        fun isFinalizedBookingId(bookingId: Long): Boolean = bookingId != IN_PROGRESS_BOOKING_ID
    }
}
