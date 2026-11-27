package com.example.bot.data.client.onboarding

import com.example.bot.policy.CabinetBookingRules
import kotlinx.serialization.json.JsonObject
import java.time.DateTimeException
import java.time.Instant
import java.util.UUID

internal class ClubDeskReservations(
    private val state: ClubWorkingNight,
) {
    private val sql get() = state.sql
    private val session get() = state.session
    private val now get() = session.now
    private val night get() = state.night
    private val club get() = state.clubId

    fun forSeating(
        table: DeskTable,
        bookingId: UUID?,
    ): DbRow? {
        if (bookingId == null) return null
        val row = requireBooking(table, bookingId, setOf("BOOKED", "SEATED"))
        if (sql.row("SELECT id FROM table_sessions WHERE club_id=? AND staff_booking_id=? LIMIT 1", club, bookingId) !=
            null
        ) {
            fail("version_conflict")
        }
        if (entry(row) == "DENIED") fail("entry_denied")
        return row
    }

    fun entry(booking: DbRow): String? =
        sql
            .row(
                """
                SELECT c.result_status FROM checkins c WHERE c.club_id=? AND c.event_id=?
                AND ((c.subject_type='BOOKING' AND (c.subject_id=? OR EXISTS (
                    SELECT 1 FROM bookings b WHERE b.club_id=c.club_id AND b.event_id=c.event_id
                    AND CAST(b.id AS VARCHAR)=c.subject_id AND b.guest_user_id=?)))
                OR (c.subject_type='GUEST_LIST_ENTRY' AND EXISTS (
                    SELECT 1 FROM guest_list_entries e JOIN users u ON u.telegram_user_id=e.telegram_user_id
                    WHERE CAST(e.id AS VARCHAR)=c.subject_id AND u.id=?)))
                ORDER BY CASE WHEN c.result_status='DENIED' THEN 0 ELSE 1 END, c.occurred_at DESC, c.id DESC LIMIT 1
                """.trimIndent(),
                club,
                state.eventId,
                booking["id"],
                booking["guest_user_id"]?.toLong(),
                booking["guest_user_id"]?.toLong(),
            )?.get("result_status")

    fun apply(
        action: String,
        table: DeskTable,
        body: JsonObject,
    ): DeskResult =
        when (action) {
            "stop_sales", "resume_sales" -> {
                body.only(BASE)
                sales(action, table)
                DeskResult()
            }
            "create_booking" -> {
                body.only(BASE + setOf("guestName", "phone", "guests", "arrivalBy", "note"))
                DeskResult(bookingId = create(table, body))
            }
            else -> changeBooking(action, table, body)
        }

    private fun changeBooking(
        action: String,
        table: DeskTable,
        body: JsonObject,
    ): DeskResult {
        val id = body.optionalUuid("bookingId") ?: fail("validation_error")
        val booking = requireBooking(table, id, setOf("BOOKED"))
        if (body.string("expectedUpdatedAt") != booking.instant("updated_at").toString()) fail("version_conflict")
        val fields = BASE + setOf("bookingId", "expectedUpdatedAt")
        when (action) {
            "update_booking" -> {
                body.only(fields + setOf("arrivalBy", "guests"))
                sql.update(
                    "UPDATE bookings SET arrival_by=?,guests_count=?,updated_at=? WHERE id=? AND club_id=?",
                    arrival(body),
                    body.optionalCount("guests", minOf(MAX_BOOKING_GUESTS, table.capacity)) ?: fail("validation_error"),
                    now,
                    id,
                    club,
                )
            }
            "move_booking" -> {
                body.only(fields + "destinationTableId")
                val destination = state.table(body.id("destinationTableId"))
                if (destination.capacity < booking.long("guests_count") ||
                    destination == table
                ) {
                    fail("validation_error")
                }
                state.requireAvailable(destination)
                sql.update(
                    "UPDATE bookings SET table_id=?,table_number=?,updated_at=? WHERE id=? AND club_id=?",
                    destination.bookingId,
                    destination.number,
                    now,
                    id,
                    club,
                )
            }
            "cancel_booking" -> {
                body.only(fields + "reason")
                status(id, "CANCELLED", body.text("reason", DESK_NOTE_LENGTH, required = true))
            }
            "no_show" -> {
                body.only(fields)
                val deadline = deadline(booking) ?: fail("invalid_transition")
                if (now < deadline || entry(booking) in setOf("ARRIVED", "LATE")) fail("invalid_transition")
                status(id, "NO_SHOW", null)
            }
            "extend_wait" -> {
                body.only(fields + "waitUntil")
                val until = instant(body, "waitUntil") ?: fail("validation_error")
                if (until <= now || until <= (deadline(booking) ?: now) || until > night.end) fail("validation_error")
                sql.update(
                    "UPDATE bookings SET staff_wait_until=?,updated_at=? WHERE id=? AND club_id=?",
                    until,
                    now,
                    id,
                    club,
                )
            }
            else -> fail("validation_error")
        }
        return DeskResult(bookingId = id)
    }

    fun deadline(booking: DbRow): Instant? {
        if (booking["staff_wait_until"] != null) return booking.instant("staff_wait_until")
        return booking["arrival_by"]?.let {
            val config = sql.row("SELECT retention_minutes FROM client_club_booking_rules WHERE club_id=?", club)
            val minutes = config?.long("retention_minutes") ?: CabinetBookingRules().retentionMinutes.toLong()
            minOf(night.end, booking.instant("arrival_by").plusSeconds(minutes * 60))
        }
    }

    private fun create(
        table: DeskTable,
        body: JsonObject,
    ): UUID {
        state.requireAvailable(table)
        val name = body.text("guestName", MAX_GUEST_NAME, required = true)
        val phone = body.text("phone", MAX_PHONE).ifBlank { null }
        if (phone != null && !phone.matches(Regex("\\+[1-9][0-9]{6,14}"))) fail("validation_error")
        val count = body.optionalCount("guests", minOf(MAX_BOOKING_GUESTS, table.capacity)) ?: fail("validation_error")
        val id = UUID.randomUUID()
        sql.update(
            "INSERT INTO bookings(id,event_id,club_id,table_id,table_number,guest_name,phone_e164,guests_count," +
                "min_deposit,total_deposit,arrival_by,status,qr_secret,idempotency_key," +
                "created_at,updated_at,slot_start,slot_end," +
                "staff_note,staff_created) VALUES (?,?,?,?,?,?,?,?,?,0,?,'BOOKED',?,?,?,?,?,?,?,TRUE)",
            id,
            state.eventId,
            club,
            table.bookingId,
            table.number,
            name,
            phone,
            count,
            table.minDeposit.toBigDecimal(),
            arrival(body),
            UUID.randomUUID().toString().replace("-", ""),
            "staff-booking-$id",
            now,
            now,
            night.legacyStart,
            night.end,
            body.text("note", DESK_NOTE_LENGTH).ifBlank { null },
        )
        return id
    }

    private fun requireBooking(
        table: DeskTable,
        id: UUID,
        statuses: Set<String>,
    ): DbRow =
        sql
            .row(
                "SELECT id,guest_user_id,status,arrival_by,updated_at,guests_count,staff_wait_until " +
                    "FROM bookings WHERE " +
                    "id=? AND club_id=? AND table_id=? AND event_id=? AND slot_start=? AND slot_end=? " +
                    "AND staff_closed_session_id IS NULL FOR UPDATE",
                id,
                club,
                table.bookingId,
                state.eventId,
                night.legacyStart,
                night.end,
            )?.takeIf { it["status"] in statuses } ?: fail("version_conflict")

    private fun sales(
        action: String,
        table: DeskTable,
    ) {
        val stopped = state.stopped(table.bookingId)
        if (action == "stop_sales" && !stopped) {
            sql.update(
                "INSERT INTO client_night_table_sales_stops(club_id,night_id,booking_table_id) VALUES (?,?,?)",
                club,
                night.id,
                table.bookingId,
            )
        }
        if (action == "resume_sales" && stopped) {
            sql.update(
                "DELETE FROM client_night_table_sales_stops WHERE club_id=? AND night_id=? AND booking_table_id=?",
                club,
                night.id,
                table.bookingId,
            )
        }
    }

    private fun status(
        id: UUID,
        status: String,
        reason: String?,
    ) {
        sql.update(
            "UPDATE bookings SET status=?,staff_cancellation_reason=?,updated_at=? WHERE id=? AND club_id=?",
            status,
            reason,
            now,
            id,
            club,
        )
    }

    private fun arrival(body: JsonObject): Instant? =
        instant(body, "arrivalBy")?.takeIf { night.contains(it) }
            ?: if (body.string("arrivalBy") == null) null else fail("validation_error")

    private fun instant(
        body: JsonObject,
        key: String,
    ): Instant? =
        body.string(key)?.let {
            try {
                Instant.parse(it)
            } catch (_: DateTimeException) {
                fail("validation_error")
            }
        }

    companion object {
        private val BASE = setOf("action", "nightId", "tableId")
        val actions =
            setOf(
                "create_booking",
                "update_booking",
                "move_booking",
                "cancel_booking",
                "extend_wait",
                "no_show",
                "stop_sales",
                "resume_sales",
            )
    }
}

internal const val DESK_NOTE_LENGTH = 500
private const val MAX_BOOKING_GUESTS = 50
private const val MAX_GUEST_NAME = 160
private const val MAX_PHONE = 16
