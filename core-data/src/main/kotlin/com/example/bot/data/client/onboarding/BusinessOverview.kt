package com.example.bot.data.client.onboarding

import com.example.bot.data.client.assignments.READ_REPORTS
import com.example.bot.data.client.assignments.managerPermissions
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate

/** Read only persisted facts. An unlinked night has no verified event/booking population. */
internal class BusinessOverview(
    private val session: OnboardingSession,
) {
    fun get(clubId: Long): JsonObject {
        val clientId = session.requireClubGrant(clubId)
        session.requireBusinessIdentity()
        if (session.businessOwnerRef(clientId, clubId) == null &&
            READ_REPORTS !in session.managerPermissions(clientId, clubId)
        ) {
            fail("not_found")
        }
        val nights =
            session.sql.rows(
                """
                SELECT n.id,n.club_id,n.business_date,n.timezone,n.start_at,n.end_at
                FROM club_operational_nights n
                WHERE n.client_id=? AND n.club_id=? AND n.status='SCHEDULED' AND n.start_at<=?
                ORDER BY n.business_date DESC LIMIT 30
                """.trimIndent(),
                clientId,
                clubId,
                session.now,
            )
        val reports =
            session.sql.rows(
                """
                SELECT r.night_start_utc,r.closed_at,r.people_women,r.people_men,
                    (SELECT COALESCE(SUM(e.amount_minor),0) FROM shift_report_revenue_entries e
                        WHERE e.report_id=r.id AND e.include_in_total=TRUE) AS total_revenue
                FROM shift_reports r WHERE r.club_id=? AND r.status='CLOSED' AND r.closed_at IS NOT NULL
                ORDER BY r.night_start_utc DESC LIMIT 30
                """.trimIndent(),
                clubId,
            )
        return buildJsonObject {
            put("clubId", clubId.toString())
            put(
                "nights",
                JsonArray(
                    nights.map { night ->
                        val counters = verifiedCounters(night, clientId)
                        buildJsonObject {
                            put("businessDate", night.getValue("business_date"))
                            put("bookings", counters?.long("bookings"))
                            put("checkins", counters?.long("checkins"))
                        }
                    },
                ),
            )
            put(
                "reports",
                JsonArray(
                    reports.map {
                        buildJsonObject {
                            put("nightStart", it.instant("night_start_utc").toString())
                            put("closedAt", it.instant("closed_at").toString())
                            put("people", it.long("people_women") + it.long("people_men"))
                            put("totalRevenueMinor", it.getValue("total_revenue"))
                        }
                    },
                ),
            )
        }
    }

    // Candidate identity and counters must describe one statement snapshot even if a legacy
    // writer changes events concurrently. Recheck the row used to derive the local day bounds.
    private fun verifiedCounters(
        night: DbRow,
        clientId: Long,
    ): DbRow? =
        session.sql
            .rowsWithArguments(
                """
                SELECT
                    (SELECT COUNT(*) FROM bookings b WHERE b.club_id=n.club_id AND b.event_id=e.id
                        AND b.status IN ('BOOKED','SEATED')) AS bookings,
                    (SELECT COUNT(*) FROM checkins i WHERE i.club_id=n.club_id AND i.event_id=e.id
                        AND i.result_status IN ('ARRIVED','LATE')) AS checkins
                FROM club_operational_nights n JOIN clubs c ON c.id=n.club_id AND c.timezone=n.timezone
                JOIN canonical_night_event_links l ON l.canonical_night_id=n.id AND l.club_id=n.club_id
                    AND l.legacy_start_at=n.start_at AND l.legacy_end_at=n.end_at
                JOIN events e ON e.club_id=n.club_id AND e.id=l.event_id AND e.start_at=n.start_at AND e.end_at=n.end_at
                WHERE n.id=? AND n.club_id=? AND n.client_id=? AND n.status='SCHEDULED'
                    AND n.business_date=? AND n.timezone=? AND n.start_at=? AND n.end_at=?
                    AND e.id=(SELECT MIN(id) FROM ($NIGHT_EVENT_CANDIDATES) candidates HAVING COUNT(*)=1)
                    AND NOT EXISTS ($OTHER_MATCHING_NIGHT)
                """.trimIndent(),
                listOf(
                    night.long("id"),
                    night.long("club_id"),
                    clientId,
                    LocalDate.parse(night.getValue("business_date")),
                    night.getValue("timezone"),
                    night.instant("start_at"),
                    night.instant("end_at"),
                ) + night.eventCandidateArgs() + night.otherMatchingNightArgs(),
            ).singleOrNull()
}
