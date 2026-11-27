package com.example.bot.data.client.onboarding

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

internal class ClubNightDeskView(
    private val session: OnboardingSession,
    private val clientId: Long,
    private val clubId: Long,
) {
    private val sql get() = session.sql

    fun build(
        nights: List<DbRow>,
        upcoming: List<DbRow>,
    ): JsonObject {
        val current = nights.singleOrNull()
        val event = current?.get("event_id")?.toLong()
        val night = event?.let { sql.verifiedLinkedCanonicalNight(clubId, it, current["timezone"]) }
        val state =
            if (night != null &&
                night.id == current.long("id")
            ) {
                ClubWorkingNight(session, clubId, event, night)
            } else {
                null
            }
        val future = current?.instant("start_at")?.isAfter(session.now) == true
        val allowed = session.clubPermissions(clientId, clubId)
        val permissions =
            if (future) {
                allowed -
                    setOf(SEAT_TABLE, RELEASE_TABLE, RECORD_TABLE_DEPOSIT, RECORD_TABLE_TOP_UP)
            } else {
                allowed
            }
        val bookingRows = state?.let { bookings(it.eventId) }.orEmpty()
        val byTable = bookingRows.groupBy { it["hall_table_id"] }
        return buildJsonObject {
            put("clubId", clubId.toString())
            put("clientId", clientId.toString())
            put("actorId", checkNotNull(session.userId).toString())
            put("night", current?.nightView() ?: JsonNull)
            put("upcomingNights", JsonArray(upcoming.map { it.nightView() }))
            put("future", future)
            if (state == null) {
                put(
                    "blocked",
                    when (nights.size) {
                        0 -> "no_current_night"
                        1 ->
                            if (sql.automaticWorkingClub(
                                    clientId,
                                    clubId,
                                )
                            ) {
                                "working_night_unprepared"
                            } else {
                                "missing_night_mapping"
                            }
                        else -> "ambiguous_night"
                    },
                )
            } else if (!hasPreparedTables(state)) {
                put("blocked", "working_tables_unprepared")
            }
            put("permissions", JsonArray(permissions.sorted().map(::JsonPrimitive)))
            put("categories", ClubDepositConfiguration(session, clubId).view())
            put(
                "halls",
                JsonArray(halls().map { hall -> hallView(hall, state, byTable, READ_TABLE_MONEY in permissions) }),
            )
            put("bookings", JsonArray(if (state == null) emptyList() else bookingRows.map { reservation(it, state) }))
        }
    }

    private fun hasPreparedTables(state: ClubWorkingNight): Boolean =
        sql.row(
            "SELECT 1 AS present FROM booking_cutover_fences f JOIN tables t ON t.id=f.booking_table_id " +
                "AND t.club_id=f.club_id JOIN hall_tables ht ON ht.booking_table_id=t.id " +
                "AND ht.booking_club_id=f.club_id " +
                "JOIN halls h ON h.id=ht.hall_id AND h.club_id=f.club_id " +
                "WHERE f.club_id=? AND f.canonical_night_id=? " +
                "AND t.active=TRUE AND ht.is_active=TRUE AND h.is_active=TRUE LIMIT 1",
            clubId,
            state.night.id,
        ) != null

    private fun DbRow.nightView(): JsonObject =
        buildJsonObject {
            put("id", this@nightView["id"])
            put("businessDate", this@nightView["business_date"])
            put("opensAt", this@nightView.instant("start_at").toString())
            put("closesAt", this@nightView.instant("end_at").toString())
        }

    private fun halls() = sql.rows("SELECT id,name,is_active FROM halls WHERE club_id=? ORDER BY id", clubId)

    private fun hallView(
        hall: DbRow,
        state: ClubWorkingNight?,
        byTable: Map<String?, List<DbRow>>,
        money: Boolean,
    ): JsonObject =
        buildJsonObject {
            put("id", hall["id"])
            put("name", hall["name"])
            val tables =
                sql.rows(
                    "SELECT ht.id,ht.label,ht.table_number,ht.capacity,ht.booking_club_id,ht.booking_table_id," +
                        "t.id AS verified_table_id,t.active AS booking_active FROM hall_tables ht " +
                        "LEFT JOIN tables t ON " +
                        "t.id=ht.booking_table_id AND t.club_id=ht.booking_club_id AND t.club_id=? " +
                        "AND t.table_number=ht.table_number AND t.capacity=ht.capacity WHERE " +
                        "ht.hall_id=? AND ht.is_active=TRUE " +
                        "ORDER BY ht.table_number,ht.id",
                    clubId,
                    hall.long("id"),
                )
            put(
                "tables",
                JsonArray(
                    tables.map { table ->
                        tableView(table, state.takeIf { hall.active() }, byTable[table["id"]].orEmpty(), money)
                    },
                ),
            )
        }

    private fun tableView(
        row: DbRow,
        state: ClubWorkingNight?,
        bookings: List<DbRow>,
        money: Boolean,
    ): JsonObject {
        val id = row["verified_table_id"]?.toLong()
        val linked = state != null && id != null && row.bookingTableIssue(clubId) == null
        val available = linked && state.isFenced(id)
        val last = if (linked) state.latest(id) else null
        val active = last?.takeIf { it["status"] == "OPEN" }
        val candidates = if (linked) state.activeBookings(id) else emptyList()
        val reservation =
            candidates
                .singleOrNull()
                ?.takeIf { it.long("event_id") == state?.eventId }
                ?.get("id")
                ?.let { found -> bookings.singleOrNull { it["id"] == found && it["exact_slot"] == "true" } }
        val stopped = linked && state.stopped(id)
        val status =
            when {
                !available -> "unavailable"
                active != null -> "seated"
                stopped -> "stopped"
                state.openHolds(id) || candidates.isNotEmpty() && reservation == null -> "unavailable"
                reservation != null -> "reserved"
                else -> "free"
            }
        val bookingId = (active?.get("staff_booking_id") ?: reservation?.get("id"))?.let(UUID::fromString)
        return buildJsonObject {
            put("id", row["id"])
            put("number", row.long("table_number"))
            put("label", row["label"])
            put("capacity", row.long("capacity"))
            put("status", status)
            put("salesStopped", stopped)
            put("sessionId", active?.get("id"))
            put("lastSessionId", last?.get("id"))
            put("bookingId", bookingId?.toString())
            val hasPaymentSubject = active != null || bookingId != null
            if (money && state != null && hasPaymentSubject) {
                put("payments", payments(state.ledger, active?.long("id"), bookingId))
            }
        }
    }

    private fun payments(
        ledger: ClubTableLedger,
        id: Long?,
        booking: UUID?,
    ): JsonArray {
        val operations =
            ledger.operations(id, booking).map { row ->
                buildJsonObject {
                    put("operationId", row["id"])
                    put("amountMinor", row["amount_minor"])
                    put("method", row["received_method"])
                    row["received_cash_minor"]?.let { put("cashMinor", it) }
                    row["received_card_minor"]?.let { put("cardMinor", it) }
                }
            }
        val captured =
            booking?.let(ledger::capturedBookingPayments).orEmpty().map { row ->
                buildJsonObject {
                    put("operationId", "provider:" + row["id"])
                    put("amountMinor", row["amount_minor"])
                }
            }
        return JsonArray(operations + captured)
    }

    private fun bookings(eventId: Long): List<DbRow> =
        sql.rows(
            """
            SELECT b.id,b.guest_user_id,COALESCE(b.guest_name,u.display_name) AS guest_name,
                u.username,b.phone_e164,b.guests_count,b.arrival_by,b.status,b.min_deposit,
                b.staff_note,b.staff_cancellation_reason,b.staff_wait_until,b.updated_at,b.staff_created,
                h.name AS hall_name,ht.id AS hall_table_id,CASE
                    WHEN b.slot_start=e.start_at AND b.slot_end=e.end_at THEN 'true' ELSE 'false' END AS exact_slot
            FROM bookings b JOIN events e ON e.id=b.event_id AND e.club_id=b.club_id
            LEFT JOIN users u ON u.id=b.guest_user_id
            LEFT JOIN hall_tables ht ON ht.booking_table_id=b.table_id AND ht.booking_club_id=b.club_id
            LEFT JOIN halls h ON h.id=ht.hall_id AND h.club_id=b.club_id
            WHERE b.club_id=? AND b.event_id=? ORDER BY b.created_at DESC,b.id
            """.trimIndent(),
            clubId,
            eventId,
        )

    private fun reservation(
        row: DbRow,
        state: ClubWorkingNight,
    ): JsonObject =
        buildJsonObject {
            put("id", row["id"])
            put("tableId", row["hall_table_id"])
            put("hallName", row["hall_name"])
            put("guestName", row["guest_name"])
            put("username", row["username"])
            put("phone", row["phone_e164"])
            put("guests", row.long("guests_count"))
            put("arrivalBy", row["arrival_by"]?.let { row.instant("arrival_by").toString() })
            put("status", row["status"])
            put("entryStatus", state.bookings.entry(row))
            put("minDeposit", row["min_deposit"])
            put("source", if (row.active("staff_created")) "CLUB" else null)
            put("waitUntil", state.bookings.deadline(row)?.toString())
            put("note", row["staff_note"])
            put("updatedAt", row.instant("updated_at").toString())
            put("cancellationReason", row["staff_cancellation_reason"])
        }
}
