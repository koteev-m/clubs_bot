package com.example.bot.data.client.onboarding

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

internal class ClubOperationalReports(
    private val session: OnboardingSession,
    private val clubId: Long,
) {
    private val sql get() = session.sql

    fun operations(body: JsonObject): JsonObject {
        val client = session.requireClubGrant(clubId, READ_OPERATIONAL_REPORTS)
        body.only(listOf("period", "from", "to"))
        val zone = ZoneId.of(checkNotNull(session.calendarClub(clubId)["timezone"]))
        val today = session.now.atZone(zone).toLocalDate()
        val nights =
            when (body.string("period")) {
                "current" ->
                    sql
                        .rows(
                            NIGHT_QUERY + " AND n.start_at<=? AND n.end_at>? ORDER BY n.start_at DESC LIMIT 2",
                            client,
                            clubId,
                            session.now,
                            session.now,
                        ).let { if (it.size == 1) it else emptyList() }
                "previous" ->
                    sql.rows(
                        NIGHT_QUERY + " AND n.end_at<=? ORDER BY n.end_at DESC LIMIT 1",
                        client,
                        clubId,
                        session.now,
                    )
                "week" -> range(client, today.minusDays(WEEK_LOOKBACK_DAYS), today)
                "month" -> range(client, today.withDayOfMonth(1), today)
                "range" -> range(client, date(body, "from"), date(body, "to"))
                else -> fail("validation_error")
            }
        val capacity =
            sql.row(
                "SELECT COUNT(ht.id) AS tables, SUM(ht.capacity) AS places FROM halls h " +
                    "JOIN hall_tables ht ON ht.hall_id=h.id WHERE h.club_id=? AND h.is_active=TRUE " +
                    "AND ht.is_active=TRUE",
                clubId,
            )
        return buildJsonObject {
            put("clubId", clubId.toString())
            put("clientId", client.toString())
            put("timezone", zone.id)
            put("configuredTables", capacity?.long("tables"))
            put("configuredPlaces", capacity?.get("places")?.toLong() ?: 0)
            put("nights", JsonArray(nights.map { nightView(it, zone.id) }))
        }
    }

    fun financial(): JsonObject {
        val client = session.requireClubGrant(clubId)
        if (!ClubReportAccess(session, clubId).granted(client)) fail("not_found")
        return buildJsonObject {
            put("clubId", clubId.toString())
            put("reports", BusinessOverview(session).closedReports(clubId))
        }
    }

    private fun nightView(
        row: DbRow,
        timezone: String,
    ): JsonObject {
        val event = row["event_id"]?.toLong()
        val night =
            event
                ?.let { sql.verifiedLinkedCanonicalNight(clubId, it, timezone) }
                ?.takeIf { it.id == row.long("id") }
        return buildJsonObject {
            put("businessDate", row["business_date"])
            put("preliminary", row.instant("end_at") > session.now)
            put("metrics", night?.let { metrics(it, checkNotNull(event)) } ?: JsonNull)
        }
    }

    private fun metrics(
        night: LockedCanonicalNight,
        event: Long,
    ): JsonObject {
        val bookings =
            sql
                .rows(
                    "SELECT status,COUNT(*) AS total FROM bookings WHERE club_id=? AND event_id=? " +
                        "AND slot_start=? AND slot_end=? GROUP BY status",
                    clubId,
                    event,
                    night.legacyStart,
                    night.end,
                ).associate { it["status"] to it.long("total") }
        val entries =
            sql
                .rows(
                    "SELECT result_status,COUNT(*) AS total FROM checkins WHERE club_id=? AND " +
                        "event_id=? GROUP BY result_status",
                    clubId,
                    event,
                ).associate { it["result_status"] to it.long("total") }
        val occupancy =
            checkNotNull(
                sql.row(
                    """
                    SELECT COUNT(*) AS sessions, COUNT(DISTINCT table_id) AS used_tables,
                        COUNT(seated_guests) AS known_counts,
                        SUM(seated_guests) AS seated_guests, COUNT(CASE WHEN status='OPEN' THEN 1 END) AS occupied
                    FROM table_sessions WHERE club_id=? AND night_start_utc=?
                    """.trimIndent(),
                    clubId,
                    night.legacyStart,
                ),
            )
        return buildJsonObject {
            put("bookings", bookings.values.sum())
            put("seatedBookings", bookings["SEATED"] ?: 0)
            put("cancelledBookings", bookings["CANCELLED"] ?: 0)
            put("noShowBookings", bookings["NO_SHOW"] ?: 0)
            put("entrants", (entries["ARRIVED"] ?: 0) + (entries["LATE"] ?: 0))
            put("denials", entries["DENIED"] ?: 0)
            put("usedTables", occupancy.long("used_tables"))
            put("occupiedTables", occupancy.long("occupied"))
            put("sessions", occupancy.long("sessions"))
            put("sessionsWithKnownCount", occupancy.long("known_counts"))
            put("knownSeatedGuests", occupancy["seated_guests"]?.toLong())
        }
    }

    private fun range(
        client: Long,
        from: LocalDate,
        to: LocalDate,
    ): List<DbRow> {
        if (ChronoUnit.DAYS.between(from, to) !in 0..MAX_RANGE_DAYS) fail("validation_error")
        return sql.rows(
            NIGHT_QUERY + " AND n.business_date BETWEEN ? AND ? ORDER BY n.business_date DESC",
            client,
            clubId,
            from,
            to,
        )
    }

    private fun date(
        body: JsonObject,
        key: String,
    ): LocalDate =
        try {
            LocalDate.parse(body.string(key) ?: fail("validation_error"))
        } catch (_: DateTimeException) {
            fail("validation_error")
        }

    private companion object {
        const val WEEK_LOOKBACK_DAYS = 6L
        const val MAX_RANGE_DAYS = 92L
        const val NIGHT_QUERY =
            "SELECT n.id,n.business_date,n.end_at,l.event_id FROM club_operational_nights n " +
                "LEFT JOIN canonical_night_event_links l ON l.club_id=n.club_id AND l.canonical_night_id=n.id " +
                "WHERE n.client_id=? AND n.club_id=? AND n.status='SCHEDULED'"
    }
}
