package com.example.bot.data.client.onboarding

import com.example.bot.data.booking.core.lockBookingAdmission
import com.example.bot.policy.CabinetBookingRules
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

internal const val READ_NIGHT_DESK = "client.club.operations.read"
internal const val WRITE_DESK_BOOKING = "client.club.operations.booking.write"
internal const val SEAT_TABLE = "client.club.operations.seat"
internal const val RELEASE_TABLE = "client.club.operations.release"
internal const val READ_TABLE_MONEY = "client.club.operations.table_money.read"
internal const val RECORD_TABLE_DEPOSIT = "client.club.operations.deposit.record"
internal const val RECORD_TABLE_TOP_UP = "client.club.operations.top_up.record"

internal class ClubNightDesk(
    private val session: OnboardingSession,
    private val clubId: Long,
) {
    private val sql get() = session.sql
    private val actor get() = checkNotNull(session.userId)

    fun read(body: JsonObject): JsonObject {
        val client = session.requireClubGrant(clubId, READ_NIGHT_DESK)
        val selected = body.string("nightId")?.let { body.id("nightId") }
        val nights =
            if (selected == null) {
                currentNights(client)
            } else {
                listOf(
                    readableNight(client, selected) ?: fail("not_found"),
                )
            }
        val upcoming = if (sql.automaticWorkingClub(client, clubId)) upcomingNights(client) else emptyList()
        val history = if (session.hasClubGrant(client, clubId, READ_TABLE_MONEY)) recentNights(client) else emptyList()
        return ClubNightDeskView(session, client, clubId).build(nights, upcoming, history)
    }

    fun mutate(body: JsonObject): MutationResult {
        val action = body.string("action") ?: fail("validation_error")
        val client = authorize(action)
        if (!sql.lockBookingAdmission(clubId)) fail("not_found")
        val club = session.calendarClub(clubId, lock = true)
        val id = body.id("nightId")
        val record = selectedNight(client, id) ?: fail("version_conflict")
        val eventId = record["event_id"]?.toLong() ?: fail("invalid_transition")
        val night =
            sql
                .lockLinkedCanonicalNight(clubId, eventId, club["timezone"])
                ?.takeIf { it.id == id } ?: fail("invalid_transition")
        val automatic = sql.ownedWorkingEvent(clubId, id) == eventId
        val now = session.now
        if (night.contains(now) && currentNights(client).singleOrNull()?.long("id") != id) {
            fail("version_conflict")
        }
        if (!night.contains(now)) {
            val future = automatic && night.legacyStart > now
            if (!future || action !in FUTURE_ACTIONS) fail("version_conflict")
        }
        val acquiresTable =
            action in BOOKING_ACQUISITION &&
                (action != "move_booking" || body.string("destinationTableId") != null)
        val deadline =
            if (automatic && acquiresTable) {
                val rules =
                    sql.row(
                        "SELECT cutoff_minutes FROM client_club_booking_rules WHERE client_id=? AND club_id=?",
                        client,
                        clubId,
                    )
                val cutoff = rules?.long("cutoff_minutes") ?: CabinetBookingRules().cutoffMinutes.toLong()
                night.end.minusSeconds(cutoff * 60)
            } else {
                night.end
            }
        if (now >= deadline) fail("booking_cutoff_passed")
        sql.workingNightDeadline = deadline
        val state = ClubWorkingNight(session, clubId, eventId, night)
        val table = state.table(body.id("tableId"))
        val result =
            when (action) {
                "seat" -> seat(client, state, table, body)
                "release" -> release(state, table, body)
                "top_up" -> topUp(client, state, table, body)
                else -> ClubDeskReservations(state).apply(action, table, body)
            }
        val receipt =
            sql.insert(
                "INSERT INTO client_table_operation_receipts(client_id,club_id,night_id," +
                    "hall_table_id,session_id,operation_id," +
                    "booking_id,action) VALUES (?,?,?,?,?,?,?,?)",
                client,
                clubId,
                night.id,
                table.physicalId,
                result.sessionId,
                result.operationId,
                result.bookingId,
                action,
            )
        session.audit("client.club.floor." + action, client, receipt, 1, result.auditDetails)
        return MutationResult("club_operations", receipt, client, receipt(client, receipt))
    }

    fun receipt(
        client: Long,
        id: Long,
    ): JsonObject {
        val row =
            sql.row(
                "SELECT * FROM client_table_operation_receipts WHERE client_id=? AND club_id=? AND id=?",
                client,
                clubId,
                id,
            )
                ?: fail("not_found")
        val action = checkNotNull(row["action"])
        if (authorize(action) != client) fail("not_found")
        val operation =
            row["operation_id"]?.toLong()?.let {
                val capability = if (action == "seat") RECORD_TABLE_DEPOSIT else RECORD_TABLE_TOP_UP
                if (!session.hasClubGrant(client, clubId, READ_TABLE_MONEY) ||
                    !session.hasClubGrant(client, clubId, capability)
                ) {
                    fail("not_found")
                }
                sql.row(
                    "SELECT id,amount_minor,received_method,received_cash_minor,received_card_minor " +
                        "FROM table_deposit_operations WHERE club_id=? AND id=?",
                    clubId,
                    it,
                )
                    ?: fail("not_found")
            }
        return buildJsonObject {
            put("action", action)
            put("nightId", row["night_id"])
            put("tableId", row["hall_table_id"])
            put("sessionId", row["session_id"])
            put("bookingId", row["booking_id"])
            operation?.let {
                put("operationId", it["id"])
                put("amountMinor", it["amount_minor"])
                put("method", it["received_method"])
                it["received_cash_minor"]?.let { cash -> put("cashMinor", cash) }
                it["received_card_minor"]?.let { card -> put("cardMinor", card) }
            }
        }
    }

    private fun authorize(action: String): Long {
        val capability =
            when (action) {
                "seat" -> SEAT_TABLE
                "release" -> RELEASE_TABLE
                "top_up" -> RECORD_TABLE_TOP_UP
                in ClubDeskReservations.actions -> WRITE_DESK_BOOKING
                else -> fail("validation_error")
            }
        val client = session.requireClubGrant(clubId, READ_NIGHT_DESK)
        if (!session.hasClubGrant(client, clubId, capability)) fail("not_found")
        return client
    }

    private fun currentNights(client: Long): List<DbRow> =
        sql.rows(
            NIGHT_SELECT + " AND n.start_at<=? AND n.end_at>? ORDER BY n.id LIMIT 2",
            client,
            clubId,
            session.now,
            session.now,
        )

    private fun selectedNight(
        client: Long,
        nightId: Long,
    ): DbRow? = sql.row(NIGHT_SELECT + " AND n.end_at>? AND n.id=?", client, clubId, session.now, nightId)

    private fun readableNight(
        client: Long,
        id: Long,
    ): DbRow? {
        val current = selectedNight(client, id)
        if (current != null && sql.ownedWorkingEvent(clubId, id) != null) return current
        return if (session.hasClubGrant(client, clubId, READ_TABLE_MONEY)) {
            sql.row(
                NIGHT_SELECT + " AND n.end_at<=? AND n.id=? AND l.event_id IS NOT NULL",
                client,
                clubId,
                session.now,
                id,
            )
        } else {
            null
        }
    }

    private fun recentNights(client: Long): List<DbRow> =
        sql.rows(
            NIGHT_SELECT + " AND n.end_at<=? AND l.event_id IS NOT NULL ORDER BY n.start_at DESC LIMIT 30",
            client,
            clubId,
            session.now,
        )

    private fun upcomingNights(client: Long): List<DbRow> =
        sql.rows(
            NIGHT_SELECT + " AND n.start_at>? AND EXISTS (SELECT 1 FROM client_working_night_events w " +
                "WHERE w.club_id=n.club_id AND w.night_id=n.id AND w.event_id=l.event_id) " +
                "ORDER BY n.start_at LIMIT 60",
            client,
            clubId,
            session.now,
        )

    private fun seat(
        client: Long,
        state: ClubWorkingNight,
        table: DeskTable,
        body: JsonObject,
    ): DeskResult {
        body.only(SEAT_FIELDS)
        if (!body.containsKey("expectedLastSessionId")) fail("validation_error")
        val latest = state.checkLastSession(table, body)
        if (latest?.get("status") == "OPEN") fail("version_conflict")
        val booking = state.bookings.forSeating(table, body.optionalUuid("bookingId"))
        state.requireAvailable(table, booking?.get("id"))
        val bookingId = booking?.get("id")?.let(UUID::fromString)
        val mode = body.string("mode")?.takeIf { it in setOf("DEPOSIT", "BILL", "CLUB") } ?: fail("validation_error")
        val payment = initialPayment(client, state, bookingId, mode, body)
        val id =
            sql.insert(
                "INSERT INTO table_sessions(club_id,night_start_utc,table_id,status," +
                    "open_marker,opened_at,opened_by,note," +
                    "staff_booking_id,seating_mode,seated_guests) VALUES (?,?,?,'OPEN',1,?,?,?,?,?,?)",
                clubId,
                state.night.legacyStart,
                table.bookingId,
                session.now,
                actor,
                body.text("note", DESK_NOTE_LENGTH).ifBlank { null },
                bookingId,
                mode,
                body.optionalCount("guests", table.capacity),
            )
        if (bookingId != null) {
            sql.update(
                "UPDATE bookings SET status='SEATED',updated_at=? WHERE club_id=? AND id=?",
                session.now,
                clubId,
                bookingId,
            )
        }
        val operation = payment?.let { state.ledger.record(table.bookingId, id, bookingId, it, topUp = false) }
        return DeskResult(id, operation, bookingId)
    }

    private fun initialPayment(
        client: Long,
        state: ClubWorkingNight,
        bookingId: UUID?,
        mode: String,
        body: JsonObject,
    ): RecordedPayment? {
        val hasNewPayment = PAYMENT_FIELDS.any(body::containsKey)
        if (mode != "DEPOSIT") {
            if (hasNewPayment) fail("validation_error")
            return null
        }
        if (!session.hasClubGrant(client, clubId, READ_TABLE_MONEY)) fail("not_found")
        val existing =
            bookingId?.let {
                state.ledger.operations(null, it).sumOf { row -> row.long("amount_minor") } > 0 ||
                    state.ledger.capturedBookingPayments(it).isNotEmpty()
            } == true
        return if (existing) {
            if (hasNewPayment) fail("version_conflict")
            null
        } else {
            newPayment(client, state, bookingId, body)
        }
    }

    private fun newPayment(
        client: Long,
        state: ClubWorkingNight,
        bookingId: UUID?,
        body: JsonObject,
    ): RecordedPayment {
        if (!session.hasClubGrant(client, clubId, RECORD_TABLE_DEPOSIT)) fail("not_found")
        state.ledger.lockOpenShift()
        if (bookingId != null &&
            sql.row(
                "SELECT id FROM payments WHERE booking_id=? AND status IN ('PENDING','INITIATED') LIMIT 1",
                bookingId,
            ) !=
            null
        ) {
            fail("invalid_transition")
        }
        return ClubDepositConfiguration(session, clubId).payment(body)
    }

    private fun release(
        state: ClubWorkingNight,
        table: DeskTable,
        body: JsonObject,
    ): DeskResult {
        body.only(SESSION_FIELDS)
        val active = state.requireOpenSession(table, body)
        val id = active.long("id")
        val bookingId = active["staff_booking_id"]?.let(UUID::fromString)
        if (sql.update(
                "UPDATE table_sessions SET status='CLOSED',open_marker=NULL,closed_at=?,closed_by=? " +
                    "WHERE club_id=? AND id=? AND status='OPEN'",
                session.now,
                actor,
                clubId,
                id,
            ) != 1
        ) {
            fail("version_conflict")
        }
        if (bookingId != null) {
            sql.update(
                "UPDATE bookings SET staff_closed_session_id=?,updated_at=? WHERE club_id=? AND id=? " +
                    "AND status='SEATED'",
                id,
                session.now,
                clubId,
                bookingId,
            )
        }
        return DeskResult(id, bookingId = bookingId)
    }

    private fun topUp(
        client: Long,
        state: ClubWorkingNight,
        table: DeskTable,
        body: JsonObject,
    ): DeskResult {
        body.only(SESSION_FIELDS + PAYMENT_FIELDS)
        if (!session.hasClubGrant(client, clubId, READ_TABLE_MONEY)) fail("not_found")
        val active = state.requireOpenSession(table, body)
        val sessionId = active.long("id")
        val bookingId = active["staff_booking_id"]?.let(UUID::fromString)
        state.ledger.lockOpenShift()
        val payment = ClubDepositConfiguration(session, clubId).payment(body)
        return DeskResult(
            sessionId,
            state.ledger.record(table.bookingId, sessionId, bookingId, payment, topUp = true),
            bookingId,
        )
    }

    private companion object {
        val BOOKING_ACQUISITION = setOf("create_booking", "move_booking")
        val FUTURE_ACTIONS =
            BOOKING_ACQUISITION + setOf("update_booking", "cancel_booking", "stop_sales", "resume_sales")
        const val NIGHT_SELECT =
            "SELECT n.id,n.business_date,n.start_at,n.end_at,n.timezone,l.event_id " +
                "FROM club_operational_nights n LEFT JOIN canonical_night_event_links l " +
                "ON l.club_id=n.club_id AND l.canonical_night_id=n.id " +
                "WHERE n.client_id=? AND n.club_id=? AND n.status='SCHEDULED'"
        val SESSION_FIELDS = setOf("action", "nightId", "tableId", "sessionId", "expectedLastSessionId")
        val PAYMENT_FIELDS = setOf("amountMinor", "method", "cashMinor", "categoryVersion", "extras")
        val SEAT_FIELDS = (SESSION_FIELDS - "sessionId") + setOf("bookingId", "mode", "guests", "note") + PAYMENT_FIELDS
    }
}

internal data class DeskResult(
    val sessionId: Long? = null,
    val operationId: Long? = null,
    val bookingId: UUID? = null,
    val auditDetails: JsonObject = JsonObject(emptyMap()),
)
