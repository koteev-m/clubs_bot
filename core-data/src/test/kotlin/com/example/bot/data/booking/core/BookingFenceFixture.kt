package com.example.bot.data.booking.core

import com.example.bot.data.client.onboarding.OnboardingSql
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import java.sql.Connection
import java.time.Clock
import java.time.Instant

/** Activation is deliberately confined to disposable test fixtures. No runtime command writes V073. */
internal suspend fun enableBookingFence(
    db: Database,
    clock: Clock,
    scope: CanonicalBookingScope,
    tableId: Long,
    start: Instant,
    end: Instant,
): Boolean =
    canonicalBookingTransaction(db, name = "booking.testFence") {
        val sql = OnboardingSql(TransactionManager.current().connection.connection as Connection)
        val canonical = CanonicalBookingTransaction(scope, clock)
        if (!sql.lockBookingAdmission(scope.clubId) || canonical.lockClub(scope.clubId) == null) {
            return@canonicalBookingTransaction false
        }
        val night = canonical.matchingNight(start, end) ?: return@canonicalBookingTransaction false
        val now = clock.instant()
        val sameEvent =
            sql.row(
                "SELECT 1 FROM bookings WHERE table_id=? AND event_id=? AND status IN ('BOOKED','SEATED') " +
                    "UNION ALL SELECT 1 FROM booking_holds WHERE table_id=? AND event_id=? AND expires_at>? LIMIT 1",
                tableId,
                scope.eventId,
                tableId,
                scope.eventId,
                now,
            )
        if (!end.isAfter(now)) return@canonicalBookingTransaction false
        if (canonical.tableConflict(tableId, night) != null ||
            canonical.conflict(tableId, start, end, now) != null ||
            sameEvent != null
        ) {
            return@canonicalBookingTransaction false
        }
        sql.update(
            "INSERT INTO booking_cutover_fences(club_id,canonical_night_id,booking_table_id) VALUES (?,?,?)",
            scope.clubId,
            night.id,
            tableId,
        ) == 1
    }
