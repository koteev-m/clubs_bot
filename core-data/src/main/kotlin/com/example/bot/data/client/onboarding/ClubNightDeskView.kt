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
        history: List<DbRow>,
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
        val historical = current?.instant("end_at")?.let { it <= session.now } == true
        val allowed = session.clubPermissions(clientId, clubId)
        val permissions =
            if (historical) {
                allowed -
                    setOf(WRITE_DESK_BOOKING, SEAT_TABLE, RELEASE_TABLE, RECORD_TABLE_DEPOSIT, RECORD_TABLE_TOP_UP)
            } else if (future) {
                allowed -
                    setOf(SEAT_TABLE, RELEASE_TABLE, RECORD_TABLE_DEPOSIT, RECORD_TABLE_TOP_UP)
            } else {
                allowed
            }
        val bookingRows = state?.takeUnless { historical }?.let { bookings(it.eventId) }.orEmpty()
        val byTable = bookingRows.groupBy { it["hall_table_id"] }
        val blocked = blockedReason(nights.size, state)
        val hasMoneyView = READ_TABLE_MONEY in permissions && (historical || blocked == null)
        return buildJsonObject {
            put("clubId", clubId.toString())
            put("clientId", clientId.toString())
            put("actorId", checkNotNull(session.userId).toString())
            put("night", current?.nightView() ?: JsonNull)
            put("upcomingNights", JsonArray(upcoming.map { it.nightView() }))
            put("future", future)
            put("historical", historical)
            put("recentNights", JsonArray(history.map { it.nightView() }))
            blocked?.let { put("blocked", it) }
            put("permissions", JsonArray(permissions.sorted().map(::JsonPrimitive)))
            put("categories", ClubDepositConfiguration(session, clubId).view())
            put(
                "halls",
                JsonArray(
                    halls().map { hall ->
                        hallView(
                            hall,
                            state.takeUnless { historical },
                            byTable,
                            READ_TABLE_MONEY in permissions,
                        )
                    },
                ),
            )
            put("bookings", JsonArray(if (state == null) emptyList() else bookingRows.map { reservation(it, state) }))
            if (state != null && !future && hasMoneyView) {
                put("tableMoney", nightMoney(state))
            }
        }
    }

    private fun blockedReason(
        nightCount: Int,
        state: ClubWorkingNight?,
    ): String? =
        when {
            nightCount == 0 -> "no_current_night"
            nightCount > 1 -> "ambiguous_night"
            state != null && hasPreparedTables(state) -> null
            !sql.automaticWorkingClub(clientId, clubId) ->
                PreexistingClientWorkingClub(sql, clientId, clubId).blocker(session.now) ?: "working_club_preparing"
            state == null -> "working_night_unprepared"
            else -> "working_tables_unprepared"
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

    private fun halls() =
        sql.rows(
            "SELECT h.id,h.name,h.is_active,h.layout_revision,p.sha256 AS plan_sha256 FROM halls h " +
                "LEFT JOIN hall_plans p ON p.hall_id=h.id WHERE h.club_id=? ORDER BY h.id",
            clubId,
        )

    private fun hallView(
        hall: DbRow,
        state: ClubWorkingNight?,
        byTable: Map<String?, List<DbRow>>,
        money: Boolean,
    ): JsonObject =
        buildJsonObject {
            put("id", hall["id"])
            put("name", hall["name"])
            if (hall.active()) {
                hall["plan_sha256"]?.let { hash ->
                    put(
                        "plan",
                        buildJsonObject {
                            put("sha256", hash)
                            put("layoutRevision", hall["layout_revision"])
                        },
                    )
                }
            }
            val tables =
                sql.rows(
                    "SELECT ht.id,ht.label,ht.table_number,ht.capacity,ht.booking_club_id,ht.booking_table_id," +
                        "ht.x,ht.y,ht.is_placed,t.id AS verified_table_id,t.active AS booking_active,t.min_deposit " +
                        "FROM hall_tables ht " +
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
            put("minDeposit", row["min_deposit"])
            if (row.placed()) {
                put("x", checkNotNull(row["x"]).toDouble())
                put("y", checkNotNull(row["y"]).toDouble())
            }
            put("status", status)
            put("salesStopped", stopped)
            put("sessionId", active?.get("id"))
            put("lastSessionId", last?.get("id"))
            put("bookingId", bookingId?.toString())
            active?.get("seated_guests")?.toInt()?.let { put("seatedGuests", it) }
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

    private fun nightMoney(state: ClubWorkingNight): JsonArray {
        val allocations =
            sql
                .rows(
                    """
                    SELECT a.operation_id,a.category_code,a.category_name_snapshot,a.amount_minor
                    FROM table_deposit_operation_allocations a JOIN table_deposit_operations o ON o.id=a.operation_id
                    WHERE o.club_id=? AND o.night_start_utc=? AND o.type IN ('INITIAL','TOPUP') ORDER BY a.id
                    """.trimIndent(),
                    clubId,
                    state.night.legacyStart,
                ).groupBy { it["operation_id"] }
        val rows =
            sql.rows(
                """
                SELECT o.*,t.table_number,t.id AS physical_table_id FROM table_deposit_operations o
                JOIN table_sessions s ON s.id=o.session_id AND s.club_id=o.club_id
                JOIN tables t ON t.id=s.table_id AND t.club_id=s.club_id
                WHERE o.club_id=? AND o.night_start_utc=? AND o.type IN ('INITIAL','TOPUP') AND o.amount_minor>0
                ORDER BY o.created_at DESC,o.id DESC
                """.trimIndent(),
                clubId,
                state.night.legacyStart,
            )
        return JsonArray(rows.map { row -> nightPayment(row, allocations[row["id"]].orEmpty()) })
    }

    private fun nightPayment(
        row: DbRow,
        allocations: List<DbRow>,
    ): JsonObject =
        buildJsonObject {
            put("operationId", row["id"])
            put("tableId", row["physical_table_id"])
            put("tableNumber", row.long("table_number"))
            put("recordedAt", row.instant("created_at").toString())
            put("kind", row["type"])
            put("amountMinor", row["amount_minor"])
            put("method", row["received_method"])
            put("cashMinor", row["received_cash_minor"])
            put("cardMinor", row["received_card_minor"])
            put(
                "allocations",
                JsonArray(
                    allocations.map { allocation ->
                        buildJsonObject {
                            put("name", allocation["category_name_snapshot"] ?: allocation["category_code"])
                            put("amountMinor", allocation["amount_minor"])
                        }
                    },
                ),
            )
        }

    private fun bookings(eventId: Long): List<DbRow> =
        sql.rows(
            """
            SELECT b.id,b.guest_user_id,COALESCE(b.guest_name,u.display_name) AS guest_name,
                u.username,b.phone_e164,b.guests_count,b.arrival_by,b.status,b.min_deposit,
                b.staff_booking_deposit_minor,b.staff_note,b.staff_cancellation_reason,b.staff_wait_until,b.updated_at,b.staff_created,
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
            put("bookingDepositMinor", row["staff_booking_deposit_minor"])
            put("source", if (row.active("staff_created")) "CLUB" else null)
            put("waitUntil", state.bookings.deadline(row)?.toString())
            put("note", row["staff_note"])
            put("updatedAt", row.instant("updated_at").toString())
            put("cancellationReason", row["staff_cancellation_reason"])
        }
}
