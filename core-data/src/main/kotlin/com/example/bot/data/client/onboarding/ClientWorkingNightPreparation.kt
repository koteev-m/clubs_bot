package com.example.bot.data.client.onboarding

import java.time.Instant

/** The caller holds client, booking admission and club locks, in that order. */
internal class ClientWorkingNightPreparation(
    private val sql: OnboardingSql,
    private val clientId: Long,
    private val clubId: Long,
) {
    fun prepare(now: Instant) {
        if (!sql.automaticWorkingClub(clientId, clubId)) return
        val tables = tables()
        if (tables.isEmpty()) return
        val nights =
            sql.rows(
                "SELECT n.*,l.event_id FROM club_operational_nights n LEFT JOIN canonical_night_event_links l " +
                    "ON l.club_id=n.club_id AND l.canonical_night_id=n.id " +
                    "WHERE n.client_id=? AND n.club_id=? AND n.status='SCHEDULED' AND n.end_at>? ORDER BY n.start_at",
                clientId,
                clubId,
                now,
            )
        nights.forEach { night -> prepareNight(night, tables) }
    }

    private fun tables(): List<Long> =
        sql
            .rows(
                """
                SELECT t.id FROM client_working_tables w JOIN tables t ON t.id=w.table_id AND t.club_id=w.club_id
                JOIN hall_tables ht ON ht.booking_table_id=t.id AND ht.booking_club_id=w.club_id
                    AND ht.table_number=t.table_number AND ht.capacity=t.capacity
                JOIN halls h ON h.id=ht.hall_id AND h.club_id=w.club_id
                WHERE w.club_id=? AND ht.is_active=TRUE AND h.is_active=TRUE ORDER BY t.id
                """.trimIndent(),
                clubId,
            ).map { it.long("id") }

    private fun prepareNight(
        night: DbRow,
        tables: List<Long>,
    ) {
        val nightId = night.long("id")
        val owned = sql.ownedWorkingEvent(clubId, nightId)
        val ready = if (owned == null) canCreate(night) else canExtend(night, owned)
        if (!ready) return
        val eventId = owned ?: createEvent(night)
        val verified = checkNotNull(sql.lockLinkedCanonicalNight(clubId, eventId, night["timezone"]))
        check(verified.id == nightId)
        tables.forEach { table -> protectTable(nightId, table, verified) }
    }

    private fun canCreate(night: DbRow): Boolean =
        night["event_id"] == null &&
            !sql.hasWorkingEventConflict(night) &&
            !sql.hasNightObligations(clubId, night.long("id"), night.instant("start_at"), null)

    private fun canExtend(
        night: DbRow,
        eventId: Long,
    ): Boolean =
        night["event_id"]?.toLong() == eventId &&
            sql.verifiedLinkedCanonicalNight(clubId, eventId, night["timezone"])?.id == night.long("id")

    private fun createEvent(night: DbRow): Long {
        val start = night.instant("start_at")
        val end = night.instant("end_at")
        val nightId = night.long("id")
        val eventId = sql.insert("INSERT INTO events(club_id,start_at,end_at) VALUES (?,?,?)", clubId, start, end)
        sql.update(
            "INSERT INTO canonical_night_event_links(club_id,canonical_night_id,event_id," +
                "legacy_start_at,legacy_end_at) " +
                "VALUES (?,?,?,?,?)",
            clubId,
            nightId,
            eventId,
            start,
            end,
        )
        sql.update(
            "INSERT INTO client_working_night_events(club_id,night_id,event_id) VALUES (?,?,?)",
            clubId,
            nightId,
            eventId,
        )
        return eventId
    }

    private fun protectTable(
        nightId: Long,
        tableId: Long,
        night: LockedCanonicalNight,
    ) {
        if (sql.row(
                "SELECT 1 AS present FROM booking_cutover_fences WHERE club_id=? " +
                    "AND canonical_night_id=? AND booking_table_id=?",
                clubId,
                nightId,
                tableId,
            ) != null
        ) {
            return
        }
        if (sql.hasTableObligations(clubId, tableId, night)) return
        sql.update(
            "INSERT INTO booking_cutover_fences(club_id,canonical_night_id,booking_table_id) VALUES (?,?,?)",
            clubId,
            nightId,
            tableId,
        )
        sql.update("UPDATE tables SET active=TRUE WHERE id=? AND club_id=? AND active=FALSE", tableId, clubId)
    }

    fun detachEmptyFuture(
        nightId: Long,
        now: Instant,
    ): Boolean {
        val eventId = sql.ownedWorkingEvent(clubId, nightId) ?: return false
        val start =
            sql
                .row("SELECT start_at FROM events WHERE club_id=? AND id=? FOR UPDATE", clubId, eventId)
                ?.instant("start_at")
        val canDetach = start != null && start > now && !sql.hasNightObligations(clubId, nightId, start, eventId)
        if (canDetach) {
            sql.update("DELETE FROM booking_cutover_fences WHERE club_id=? AND canonical_night_id=?", clubId, nightId)
            sql.update("DELETE FROM client_working_night_events WHERE club_id=? AND night_id=?", clubId, nightId)
            sql.update(
                "DELETE FROM canonical_night_event_links WHERE club_id=? AND canonical_night_id=?",
                clubId,
                nightId,
            )
            check(sql.update("DELETE FROM events WHERE id=? AND club_id=?", eventId, clubId) == 1)
        }
        return canDetach
    }
}

internal fun OnboardingSql.automaticWorkingClub(
    clientId: Long,
    clubId: Long,
): Boolean =
    workingOperationsSchema &&
        row(
            "SELECT 1 AS present FROM client_working_clubs w JOIN clubs c ON c.id=w.club_id " +
                "WHERE w.client_id=? AND w.club_id=? AND c.is_active=FALSE",
            clientId,
            clubId,
        ) != null

internal fun OnboardingSql.ownedWorkingEvent(
    clubId: Long,
    nightId: Long,
): Long? =
    if (!workingOperationsSchema) {
        null
    } else {
        row("SELECT event_id FROM client_working_night_events WHERE club_id=? AND night_id=?", clubId, nightId)
            ?.long("event_id")
    }

/** Exact overlap for generated events, plus the existing business-date conflict rule for external events. */
internal fun OnboardingSql.hasWorkingEventConflict(
    night: DbRow,
    except: Long? = null,
): Boolean =
    row(
        """
        SELECT 1 AS present FROM events e LEFT JOIN client_working_night_events owned
            ON owned.club_id=e.club_id AND owned.event_id=e.id
        WHERE e.club_id=? AND (e.id<>? OR ?=0) AND (
            (e.start_at<? AND e.end_at>?) OR (owned.night_id IS NULL AND e.start_at>=? AND e.start_at<?)
        ) LIMIT 1
        """.trimIndent(),
        night.long("club_id"),
        except ?: 0L,
        except ?: 0L,
        night.instant("end_at"),
        night.instant("start_at"),
        night.eventCandidateArgs()[1],
        night.eventCandidateArgs()[2],
    ) != null

private fun OnboardingSql.hasNightObligations(
    club: Long,
    night: Long,
    start: Instant,
    event: Long?,
): Boolean {
    val nightTables =
        listOf(
            "shift_reports",
            "table_sessions",
            "club_visits",
            "operational_night_overrides",
            "post_event_stories",
            "analytics_snapshots",
        )
    val eventTables = listOf("bookings", "booking_holds", "checkins", "guest_lists", "club_visits")
    return nightTables.any {
        row("SELECT 1 AS present FROM $it WHERE club_id=? AND night_start_utc=? LIMIT 1", club, start) !=
            null
    } ||
        (
            event != null &&
                eventTables.any { row("SELECT 1 AS present FROM $it WHERE event_id=? LIMIT 1", event) != null }
        ) ||
        hasNightHistory(club, night)
}

private fun OnboardingSql.hasNightHistory(
    club: Long,
    night: Long,
): Boolean =
    listOf("client_table_operation_receipts", "client_night_table_sales_stops").any {
        row("SELECT 1 AS present FROM $it WHERE club_id=? AND night_id=? LIMIT 1", club, night) != null
    } ||
        row(
            "SELECT 1 AS present FROM canonical_booking_commands WHERE club_id=? AND canonical_night_id=? LIMIT 1",
            club,
            night,
        ) != null

private fun OnboardingSql.hasTableObligations(
    club: Long,
    table: Long,
    night: LockedCanonicalNight,
): Boolean =
    row(
        """
        SELECT 1 AS present FROM bookings WHERE table_id=? AND slot_start<? AND slot_end>?
        UNION ALL SELECT 1 FROM booking_holds WHERE table_id=? AND slot_start<? AND slot_end>?
        UNION ALL SELECT 1 FROM table_sessions WHERE club_id=? AND table_id=? AND night_start_utc=?
        LIMIT 1
        """.trimIndent(),
        table,
        night.end,
        night.legacyStart,
        table,
        night.end,
        night.legacyStart,
        club,
        table,
        night.legacyStart,
    ) != null
