package com.example.bot.data.client.onboarding

import java.time.Instant

private const val VERIFIED_DIRECT_CLUBS =
    "FROM client_direct_club_profiles p JOIN client_business_clubs b ON b.client_id=p.client_id " +
        "AND b.club_id=p.club_id AND b.user_id=p.owner_user_id " +
        "JOIN client_business_owners o ON o.client_id=b.client_id AND o.user_id=b.user_id " +
        "JOIN client_memberships m ON m.client_id=o.client_id AND m.user_id=o.user_id " +
        "JOIN clients c ON c.id=p.client_id AND c.is_active=TRUE AND c.archived_at IS NULL " +
        "JOIN clubs venue ON venue.id=p.club_id AND venue.is_active=FALSE " +
        "WHERE b.evidence IN ('OWNER_CREATE','VERIFIED_CREATION') AND m.is_active=TRUE AND m.version=o.member_version"

internal fun OnboardingSql.workingClubCandidates(): List<DbRow> =
    rows(
        "SELECT w.client_id,w.club_id FROM client_working_clubs w JOIN clubs c ON c.id=w.club_id " +
            "WHERE c.is_active=FALSE UNION SELECT p.client_id,p.club_id " + VERIFIED_DIRECT_CLUBS + " ORDER BY club_id",
    )

internal fun OnboardingSql.verifiedDirectWorkingCandidate(
    clientId: Long,
    clubId: Long,
): Boolean =
    workingOperationsSchema &&
        row("SELECT p.club_id " + VERIFIED_DIRECT_CLUBS + " AND p.client_id=? AND p.club_id=?", clientId, clubId) !=
        null

/** Adopts only inactive, verified client creations with no operating history or ambiguous mappings.
 *  The maintenance transaction holds the client, booking admission and club locks before this check.
 */
internal class PreexistingClientWorkingClub(
    private val sql: OnboardingSql,
    private val clientId: Long,
    private val clubId: Long,
) {
    fun blocker(now: Instant): String? =
        when {
            !sql.verifiedDirectWorkingCandidate(clientId, clubId) -> "working_club_review_required"
            hasOperatingHistory() -> "working_club_review_required"
            !consistentCalendar(now) -> "working_calendar_review_required"
            !safeLayout() -> "working_layout_review_required"
            else -> null
        }

    fun adopt(now: Instant) {
        if (blocker(now) != null) return
        sql.update("INSERT INTO client_working_clubs(club_id,client_id) VALUES (?,?)", clubId, clientId)
        val tables = layout()
        for (table in tables) registerTable(table)
        for (hall in tables.map { it.long("hall_id") }.toSet()) {
            sql.update("UPDATE halls SET layout_revision=layout_revision+1,updated_at=? WHERE id=?", now, hall)
        }
        ClientWorkingNightPreparation(sql, clientId, clubId).prepare(now)
        sql.update(
            "UPDATE clubs SET calendar_version=calendar_version+1 WHERE id=? " +
                "AND EXISTS (SELECT 1 FROM client_working_night_events WHERE club_id=?)",
            clubId,
            clubId,
        )
    }

    private fun registerTable(table: DbRow) {
        val bookingTable =
            table["booking_table_id"]?.toLong() ?: sql
                .insert(
                    "INSERT INTO tables(club_id,table_number,capacity,min_deposit,active) VALUES (?,?,?,?,FALSE)",
                    clubId,
                    table.long("table_number"),
                    table.long("capacity"),
                    0,
                ).also { id ->
                    check(
                        sql.update(
                            "UPDATE hall_tables SET booking_table_id=?,booking_club_id=? " +
                                "WHERE id=? AND booking_table_id IS NULL",
                            id,
                            clubId,
                            table.long("id"),
                        ) == 1,
                    )
                }
        sql.update("INSERT INTO client_working_tables(table_id,club_id) VALUES (?,?)", bookingTable, clubId)
    }

    private fun layout(): List<DbRow> =
        sql.rows(
            "SELECT ht.id,ht.hall_id,ht.booking_table_id,ht.table_number,ht.capacity,ht.min_deposit " +
                "FROM hall_tables ht JOIN halls h ON h.id=ht.hall_id " +
                "WHERE h.club_id=? AND ht.is_active=TRUE ORDER BY ht.id",
            clubId,
        )

    private fun safeLayout(): Boolean {
        val layout = layout()
        val numbers = layout.map { it.long("table_number") }
        val hasInvalidTable =
            layout.any {
                it.long("capacity") !in 1L..MAX_BOOKING_CAPACITY.toLong() ||
                    it.long("table_number") <= 0 ||
                    it.long("min_deposit") != 0L
            }
        if (numbers.size != numbers.toSet().size || hasInvalidTable) return false
        // V072's composite FKs prove the club, number and capacity of every non-null mapping.
        val expected = layout.mapNotNull { it["booking_table_id"]?.toLong() }.toSet()
        val tables = sql.rows("SELECT id,active FROM tables WHERE club_id=?", clubId)
        return tables.none { it.active("active") } && tables.map { it.long("id") }.toSet() == expected
    }

    private fun consistentCalendar(now: Instant): Boolean {
        val nights =
            sql.rows(
                "SELECT n.start_at,n.end_at,n.timezone,c.timezone AS club_timezone " +
                    "FROM club_operational_nights n JOIN clubs c ON c.id=n.club_id " +
                    "WHERE n.client_id=? AND n.club_id=? AND n.status='SCHEDULED' AND n.end_at>? ORDER BY n.start_at",
                clientId,
                clubId,
                now,
            )
        return nights.all { it["timezone"] == it["club_timezone"] } &&
            nights.zipWithNext().none { (older, newer) -> older.instant("end_at") > newer.instant("start_at") }
    }

    private fun hasOperatingHistory(): Boolean =
        listOf(
            "events",
            "bookings",
            "table_sessions",
            "shift_reports",
            "club_visits",
            "operational_night_overrides",
            "post_event_stories",
            "analytics_snapshots",
            "canonical_booking_commands",
            "client_table_operation_receipts",
            "client_night_table_sales_stops",
            "booking_cutover_fences",
        ).any { sql.row("SELECT 1 FROM $it WHERE club_id=? LIMIT 1", clubId) != null } ||
            sql.row(
                "SELECT 1 FROM booking_holds b JOIN tables t ON t.id=b.table_id WHERE t.club_id=? " +
                    "UNION ALL SELECT 1 FROM bookings b JOIN tables t ON t.id=b.table_id WHERE t.club_id=? LIMIT 1",
                clubId,
                clubId,
            ) != null
}
