package com.example.bot.data.client.onboarding

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.util.UUID

internal class ClubWorkingNight(
    val session: OnboardingSession,
    val clubId: Long,
    val eventId: Long,
    val night: LockedCanonicalNight,
) {
    val sql get() = session.sql
    val ledger get() = ClubTableLedger(session, clubId, night.legacyStart)
    val bookings get() = ClubDeskReservations(this)

    fun table(physicalId: Long): DeskTable {
        val row =
            sql.row(
                """
                SELECT ht.id,ht.capacity,ht.table_number,h.is_active AS hall_active,ht.is_active,
                    t.active AS booking_active,t.id AS verified_table_id,
                    ht.booking_table_id,ht.booking_club_id,t.min_deposit
                FROM halls h JOIN hall_tables ht ON ht.hall_id=h.id
                LEFT JOIN tables t ON t.id=ht.booking_table_id AND t.club_id=h.club_id AND t.club_id=ht.booking_club_id
                    AND t.table_number=ht.table_number AND t.capacity=ht.capacity
                WHERE h.club_id=? AND ht.id=?
                """.trimIndent(),
                clubId,
                physicalId,
            ) ?: fail("not_found")
        if (!row.active("hall_active") || !row.active() || row.bookingTableIssue(clubId) != null) {
            fail("invalid_transition")
        }
        val bookingId = row.long("verified_table_id")
        if (!isFenced(bookingId)) fail("operations_table_not_enabled")
        return DeskTable(
            physicalId,
            bookingId,
            row.long("table_number"),
            row.long("capacity").toInt(),
            checkNotNull(row["min_deposit"]),
        )
    }

    fun isFenced(bookingTableId: Long): Boolean =
        sql.row(
            "SELECT 1 AS present FROM booking_cutover_fences WHERE club_id=? AND " +
                "canonical_night_id=? AND booking_table_id=?",
            clubId,
            night.id,
            bookingTableId,
        ) != null

    fun latest(
        bookingTableId: Long,
        lock: Boolean = false,
    ): DbRow? =
        sql.row(
            "SELECT id,status,staff_booking_id FROM table_sessions WHERE club_id=? AND " +
                "night_start_utc=? AND table_id=? " +
                "ORDER BY id DESC LIMIT 1" + if (lock) sql.updateLock() else "",
            clubId,
            night.legacyStart,
            bookingTableId,
        )

    fun checkLastSession(
        table: DeskTable,
        body: JsonObject,
    ): DbRow? {
        val row = latest(table.bookingId, lock = true)
        if (row?.get("id") != body.string("expectedLastSessionId")) fail("version_conflict")
        return row
    }

    fun requireOpenSession(
        table: DeskTable,
        body: JsonObject,
    ): DbRow =
        checkLastSession(table, body)
            ?.takeIf { it.long("id") == body.id("sessionId") && it["status"] == "OPEN" }
            ?: fail("version_conflict")

    fun stopped(bookingTableId: Long): Boolean =
        sql.row(
            "SELECT 1 AS present FROM client_night_table_sales_stops WHERE club_id=? AND night_id=? " +
                "AND booking_table_id=?",
            clubId,
            night.id,
            bookingTableId,
        ) != null

    fun openHolds(bookingTableId: Long): Boolean =
        sql.row(
            "SELECT 1 AS present FROM booking_holds WHERE table_id=? AND expires_at>? AND " +
                "(event_id=? OR (slot_start<? AND slot_end>?)) LIMIT 1",
            bookingTableId,
            session.now,
            eventId,
            night.end,
            night.legacyStart,
        ) != null

    fun activeBookings(bookingTableId: Long): List<DbRow> =
        sql.rows(
            "SELECT id,event_id,status FROM bookings WHERE club_id=? AND table_id=? AND " +
                "staff_closed_session_id IS NULL " +
                "AND status IN ('BOOKED','SEATED') AND (event_id=? OR (slot_start<? AND slot_end>?)) " +
                "ORDER BY id LIMIT 2",
            clubId,
            bookingTableId,
            eventId,
            night.end,
            night.legacyStart,
        )

    fun requireAvailable(
        table: DeskTable,
        ownBooking: String? = null,
    ) {
        val reserved = activeBookings(table.bookingId).any { it["id"] != ownBooking }
        val open = latest(table.bookingId)?.get("status") == "OPEN"
        val available = !stopped(table.bookingId) && !openHolds(table.bookingId)
        if (!available || open || reserved) fail("version_conflict")
    }
}

internal data class DeskTable(
    val physicalId: Long,
    val bookingId: Long,
    val number: Long,
    val capacity: Int,
    val minDeposit: String,
)

internal fun JsonObject.optionalUuid(key: String): UUID? =
    string(key)?.let { raw ->
        if (!raw.matches(Regex("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}"))) fail("validation_error")
        UUID.fromString(raw)
    }

internal fun JsonObject.optionalCount(
    key: String,
    maximum: Int,
): Int? =
    this[key]?.let { input ->
        (input as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it in 1..maximum }
            ?: fail("validation_error")
    }
