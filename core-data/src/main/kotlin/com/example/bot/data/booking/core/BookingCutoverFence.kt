package com.example.bot.data.booking.core

import com.example.bot.data.client.onboarding.OnboardingSql
import com.example.bot.data.client.onboarding.instant
import com.example.bot.data.client.onboarding.long
import com.example.bot.data.client.onboarding.updateLock
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.sql.Connection
import java.time.Instant
import java.util.UUID

/**
 * Legacy writers and activation lock the existing client mapping before child locks. Activation
 * also takes the protected club lock. Protected writers never request this admission lock.
 */
internal fun OnboardingSql.lockBookingAdmission(clubId: Long): Boolean =
    row("SELECT club_id FROM client_clubs WHERE club_id=?" + updateLock(), clubId) != null

internal suspend fun <T> legacyBookingTransaction(
    db: Database,
    block: suspend () -> T,
): T = canonicalBookingTransaction(db, name = "booking.legacyAdmission", block = block)

internal class LegacyBookingAdmission {
    private val sql = OnboardingSql(TransactionManager.current().connection.connection as Connection)

    fun lock(clubId: Long): Boolean = sql.lockBookingAdmission(clubId)

    fun fencesReservation(
        clubId: Long,
        tableId: Long,
        start: Instant,
        end: Instant,
        eventId: Long? = null,
    ): Boolean =
        sql.row(
            "SELECT 1 FROM booking_cutover_fences f " +
                "JOIN canonical_night_event_links l ON l.club_id=f.club_id " +
                "AND l.canonical_night_id=f.canonical_night_id " +
                "JOIN events e ON e.club_id=l.club_id AND e.id=l.event_id " +
                "WHERE f.club_id=? AND f.booking_table_id=? AND (l.event_id=? " +
                "OR (l.legacy_start_at<? AND l.legacy_end_at>?) OR (e.start_at<? AND e.end_at>?)) LIMIT 1",
            clubId,
            tableId,
            eventId,
            end,
            start,
            end,
            start,
        ) != null

    fun fencesBooking(bookingId: UUID): Boolean {
        val booking =
            sql.row(
                "SELECT t.club_id,b.table_id,b.event_id,b.slot_start,b.slot_end FROM bookings b " +
                    "JOIN tables t ON t.id=b.table_id WHERE b.id=?",
                bookingId,
            ) ?: return false
        val clubId = booking.long("club_id")
        return lock(clubId) &&
            fencesReservation(
                clubId,
                booking.long("table_id"),
                booking.instant("slot_start"),
                booking.instant("slot_end"),
                booking.long("event_id"),
            )
    }

    fun requireLegacySeating(
        clubId: Long,
        tableId: Long,
        nightStart: Instant,
    ) {
        if (fencesSeating(clubId, tableId, nightStart)) throw LegacyTableSessionFencedException()
    }

    private fun fencesSeating(
        clubId: Long,
        tableId: Long,
        nightStart: Instant,
    ): Boolean {
        val bookingClub = sql.row("SELECT club_id FROM tables WHERE id=?", tableId)?.get("club_id")?.toLong()
        // Old callers can supply a layout ID. Check both exact references and take locks in ID order.
        return setOfNotNull(clubId, bookingClub).sorted().filter(::lock).any { owner ->
            sql.row(
                "SELECT 1 FROM booking_cutover_fences f " +
                    "JOIN canonical_night_event_links l ON l.club_id=f.club_id " +
                    "AND l.canonical_night_id=f.canonical_night_id " +
                    "JOIN events e ON e.club_id=l.club_id AND e.id=l.event_id " +
                    "JOIN hall_tables ht ON ht.booking_club_id=f.club_id AND ht.booking_table_id=f.booking_table_id " +
                    "WHERE f.club_id=? AND (f.booking_table_id=? OR (f.club_id=? AND ht.id=?)) " +
                    "AND ((l.legacy_start_at<=? AND l.legacy_end_at>?) OR (e.start_at<=? AND e.end_at>?)) LIMIT 1",
                owner,
                tableId,
                clubId,
                tableId,
                nightStart,
                nightStart,
                nightStart,
                nightStart,
            ) != null
        }
    }
}

class LegacyTableSessionFencedException : IllegalStateException("Table session requires the protected booking path")
