package com.example.bot.data.promoter

import com.example.bot.data.db.isUniqueViolation
import com.example.bot.data.db.withTxRetry
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

private object PromoterBookingAssignmentsTable : Table("promoter_booking_assignments") {
    val entryId = long("entry_id")
    val bookingId = long("booking_id")
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(entryId)
}

class PromoterBookingAssignmentsRepository(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
) {
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

    suspend fun assignIfAbsent(entryId: Long, bookingId: Long): Boolean {
        val now = now()
        return try {
            withTxRetry {
                newSuspendedTransaction(context = Dispatchers.IO, db = db) {
                    PromoterBookingAssignmentsTable.insert {
                        it[PromoterBookingAssignmentsTable.entryId] = entryId
                        it[PromoterBookingAssignmentsTable.bookingId] = bookingId
                        it[PromoterBookingAssignmentsTable.createdAt] = now
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
    }

    private fun now(): OffsetDateTime = Instant.now(clock).atOffset(ZoneOffset.UTC)
}
