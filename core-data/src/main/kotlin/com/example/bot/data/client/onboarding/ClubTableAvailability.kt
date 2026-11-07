package com.example.bot.data.client.onboarding

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

/** All authorization, identities and occupancy facts come from the caller's read-only snapshot. */
internal class ClubTableAvailability(
    private val session: OnboardingSession,
) {
    private val sql get() = session.sql

    fun get(
        clubId: Long,
        nightId: Long,
        hallId: Long,
        sampledAt: Instant,
    ): JsonObject {
        val (clientId, club) = ClubBookingPreview(session).requireOwner(clubId, lock = false)
        val hall =
            sql.row("SELECT is_active,layout_revision FROM halls WHERE club_id=? AND id=?", clubId, hallId)
                ?: fail("not_found")
        val night =
            sql.row(
                "SELECT n.business_date,n.status,l.event_id FROM club_operational_nights n " +
                    "LEFT JOIN canonical_night_event_links l ON l.club_id=n.club_id AND l.canonical_night_id=n.id " +
                    "WHERE n.client_id=? AND n.club_id=? AND n.id=?",
                clientId,
                clubId,
                nightId,
            ) ?: fail("not_found")
        val eventId = night["event_id"]?.toLong()
        val verified =
            eventId
                ?.let { sql.verifiedLinkedCanonicalNight(clubId, it, club["timezone"]) }
                ?.takeIf { it.id == nightId }
        val nightIssue =
            when {
                night["status"] != "SCHEDULED" -> "NIGHT_CANCELLED"
                eventId == null -> "EVENT_UNLINKED"
                verified == null -> "EVENT_MISMATCH"
                else -> null
            }
        val facts =
            if (verified != null && eventId != null) {
                occupancy(clubId, hallId, eventId, verified, sampledAt)
            } else {
                emptyMap()
            }
        val tables =
            sql.rows(
                "SELECT ht.id,ht.is_active,ht.booking_table_id,ht.booking_club_id," +
                    "bt.id AS verified_table_id,bt.active AS booking_active FROM hall_tables ht " +
                    "LEFT JOIN tables bt ON bt.id=ht.booking_table_id AND bt.club_id=ht.booking_club_id " +
                    "AND bt.table_number=ht.table_number AND bt.capacity=ht.capacity WHERE ht.hall_id=? ORDER BY ht.id",
                hallId,
            )
        return buildJsonObject {
            put("clubId", clubId.toString())
            put("hallId", hallId.toString())
            put("nightId", nightId.toString())
            put("businessDate", night.getValue("business_date"))
            put("layoutRevision", hall.getValue("layout_revision"))
            put("sampledAt", sampledAt.toString())
            put(
                "tables",
                JsonArray(
                    tables.map { table ->
                        val id = table.long("id")
                        val state = table.status(clubId, hall.active(), nightIssue, facts[id].orEmpty())
                        buildJsonObject {
                            put("id", id.toString())
                            put("status", state.status)
                            state.reason?.let { put("reason", it) }
                        }
                    },
                ),
            )
        }
    }

    private fun occupancy(
        clubId: Long,
        hallId: Long,
        eventId: Long,
        night: LockedCanonicalNight,
        sampledAt: Instant,
    ): Map<Long, List<String>> =
        sql
            .rows(
                """
                WITH linked_tables AS (
                    SELECT id,booking_table_id FROM hall_tables WHERE hall_id=? AND booking_club_id=?
                )
                SELECT t.id,CASE WHEN h.event_id=? AND h.slot_start=? AND h.slot_end=? THEN 'HOLD'
                    ELSE 'CONFLICT' END AS fact
                FROM linked_tables t JOIN booking_holds h ON h.table_id=t.booking_table_id
                WHERE h.expires_at>? AND (h.event_id=? OR (h.slot_start<? AND h.slot_end>?))
                UNION ALL
                SELECT t.id,CASE WHEN b.club_id=? AND b.event_id=? AND b.slot_start=? AND b.slot_end=? THEN 'BOOKED'
                    ELSE 'CONFLICT' END AS fact
                FROM linked_tables t JOIN bookings b ON b.table_id=t.booking_table_id
                WHERE b.status IN ('BOOKED','SEATED') AND (b.event_id=? OR (b.slot_start<? AND b.slot_end>?))
                UNION ALL
                SELECT t.id,CASE WHEN s.club_id=? THEN 'OCCUPIED' ELSE 'CONFLICT' END AS fact
                FROM linked_tables t JOIN table_sessions s ON s.table_id=t.booking_table_id
                WHERE s.night_start_utc=? AND s.status='OPEN'
                """.trimIndent(),
                hallId,
                clubId,
                eventId,
                night.legacyStart,
                night.end,
                sampledAt,
                eventId,
                night.end,
                night.legacyStart,
                clubId,
                eventId,
                night.legacyStart,
                night.end,
                eventId,
                night.end,
                night.legacyStart,
                clubId,
                night.legacyStart,
            ).groupBy({ it.long("id") }, { checkNotNull(it["fact"]) })

    private fun DbRow.status(
        clubId: Long,
        hallActive: Boolean,
        nightIssue: String?,
        facts: List<String>,
    ): Availability {
        if (!hallActive || !active()) return Availability("UNAVAILABLE", "LAYOUT_INACTIVE")
        val issue = bookingTableIssue(clubId)
        return when {
            issue == "BOOKING_TABLE_INACTIVE" -> Availability("UNAVAILABLE", issue)
            issue != null -> Availability("UNKNOWN", issue)
            nightIssue != null -> Availability("UNKNOWN", nightIssue)
            facts.size > 1 || "CONFLICT" in facts -> Availability("UNKNOWN", "OCCUPANCY_CONFLICT")
            else -> Availability(facts.singleOrNull() ?: "FREE")
        }
    }

    private data class Availability(
        val status: String,
        val reason: String? = null,
    )
}

internal fun DbRow.bookingTableIssue(clubId: Long): String? =
    when {
        this["booking_table_id"] == null -> "TABLE_UNLINKED"
        this["verified_table_id"] == null || this["booking_club_id"]?.toLongOrNull() != clubId -> "TABLE_LINK_MISMATCH"
        !active("booking_active") -> "BOOKING_TABLE_INACTIVE"
        else -> null
    }
