package com.example.bot.data.client.onboarding

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Cabinet-only compatibility command. Legacy events remain independently owned. */
internal class ClubNightEventLinks(
    private val session: OnboardingSession,
) {
    private val sql get() = session.sql

    fun classify(nightId: Long): JsonObject {
        val clubId = clubId(nightId)
        val clientId = session.requireClubGrant(clubId)
        val club = privateClub(clubId)
        val night = night(nightId, clubId, clientId)
        val match = sql.classifyNightEvent(night)
        val linked = linked(nightId)
        return buildJsonObject {
            put("nightId", nightId.toString())
            put("businessDate", night["business_date"])
            put("timezone", night["timezone"])
            put("version", club["calendar_version"])
            put("nightStatus", night["status"])
            put("classification", match.status.name)
            put("matchingEventId", match.eventId)
            put("linkedEventId", linked?.get("event_id"))
        }
    }

    fun link(
        nightId: Long,
        body: JsonObject,
    ): MutationResult {
        body.only(listOf("expectedVersion", "eventId"))
        val clubId = clubId(nightId)
        val clientId = session.requireClubGrant(clubId, WRITE_CALENDAR)
        val club = privateClub(clubId, lock = true)
        val version = club.long("calendar_version")
        if (body.id("expectedVersion") != version) fail("version_conflict")
        val eventId = body.id("eventId")
        val night = night(nightId, clubId, clientId)
        if (night["status"] != "SCHEDULED") conflict(night, "night_not_scheduled")
        if (night["timezone"] != club["timezone"]) conflict(night, "timezone_changed")

        // Acquire the same club lock as reconciliation before locking or reading the legacy event.
        sql.row("SELECT id FROM events WHERE club_id=? AND id=? FOR UPDATE", clubId, eventId) ?: fail("not_found")
        requireExactCandidate(night, eventId)

        val updatedVersion = version + 1
        val start = night.instant("start_at")
        val end = night.instant("end_at")
        sql.update(
            "INSERT INTO canonical_night_event_links " +
                "(canonical_night_id,event_id,club_id,legacy_start_at,legacy_end_at) " +
                "VALUES (?,?,?,?,?)",
            nightId,
            eventId,
            clubId,
            start,
            end,
        )
        sql.update("UPDATE clubs SET calendar_version=? WHERE id=?", updatedVersion, clubId)
        val receipt = checkNotNull(linked(nightId)).receipt(night)
        sql.audit(
            checkNotNull(session.userId),
            "client.club.calendar.night.link",
            clientId,
            clubId,
            updatedVersion,
            session.now,
            session.correlation,
            receipt,
        )
        return MutationResult("club_night_event_link", nightId, clientId, receipt)
    }

    fun receipt(nightId: Long): JsonObject {
        val clubId = clubId(nightId)
        val clientId = session.requireClubGrant(clubId, WRITE_CALENDAR)
        privateClub(clubId)
        val night = night(nightId, clubId, clientId)
        return (linked(nightId) ?: fail("not_found")).receipt(night)
    }

    private fun requireExactCandidate(
        night: DbRow,
        eventId: Long,
    ) {
        val eventLink = sql.row("SELECT 1 FROM canonical_night_event_links WHERE event_id=?", eventId)
        if (linked(night.long("id")) != null || eventLink != null) conflict(night, "already_linked")
        val match = sql.classifyNightEvent(night)
        if (match.status != CandidateStatus.EXACT_MATCH) conflict(night, match.status.name.lowercase())
        if (match.eventId != eventId.toString()) conflict(night, "event_mismatch")
    }

    private fun clubId(nightId: Long): Long =
        sql.row("SELECT club_id FROM club_operational_nights WHERE id=?", nightId)?.long("club_id") ?: fail("not_found")

    private fun privateClub(
        clubId: Long,
        lock: Boolean = false,
    ): DbRow = session.calendarClub(clubId, lock).also { if (it.active()) fail("calendar_night_cutover_required") }

    private fun night(
        nightId: Long,
        clubId: Long,
        clientId: Long,
    ): DbRow =
        sql.row(
            "SELECT id,club_id,business_date,timezone,start_at,end_at,status FROM club_operational_nights " +
                "WHERE id=? AND club_id=? AND client_id=?",
            nightId,
            clubId,
            clientId,
        ) ?: fail("not_found")

    private fun linked(nightId: Long): DbRow? =
        sql.row(
            "SELECT event_id,legacy_start_at,legacy_end_at FROM canonical_night_event_links WHERE canonical_night_id=?",
            nightId,
        )
}

private fun DbRow.receipt(night: DbRow): JsonObject =
    buildJsonObject {
        put("nightId", night["id"])
        put("businessDate", night["business_date"])
        put("eventId", this@receipt["event_id"])
        put("legacyStartsAt", instant("legacy_start_at").toString())
        put("legacyEndsAt", instant("legacy_end_at").toString())
    }

private fun conflict(
    night: DbRow,
    reason: String,
): Nothing =
    throw OnboardingFailure(
        "calendar_night_event_conflict",
        mapOf("businessDate" to checkNotNull(night["business_date"]), "reason" to reason),
    )

// A second event on the business date or overlapping the window prevents attribution.
internal const val NIGHT_EVENT_CANDIDATES =
    "SELECT id,start_at,end_at FROM events WHERE club_id=? AND " +
        "((start_at>=? AND start_at<?) OR (start_at<? AND (end_at>? OR start_at>=?))) ORDER BY id LIMIT 2"

internal fun DbRow.eventCandidateArgs(): List<Any> {
    val date = LocalDate.parse(getValue("business_date"))
    val zone = ZoneId.of(getValue("timezone"))
    val start = instant("start_at")
    return listOf(
        long("club_id"),
        date.atStartOfDay(zone).toInstant(),
        date.plusDays(1).atStartOfDay(zone).toInstant(),
        instant("end_at"),
        start,
        start,
    )
}

internal const val OTHER_MATCHING_NIGHT =
    "SELECT 1 FROM club_operational_nights WHERE club_id=? AND id<>? AND status='SCHEDULED' " +
        "AND start_at=? AND end_at=? LIMIT 1"

internal fun DbRow.otherMatchingNightArgs(): List<Any> =
    listOf(long("club_id"), long("id"), instant("start_at"), instant("end_at"))

internal fun OnboardingSql.classifyNightEvent(night: DbRow): Candidate {
    val events = rowsWithArguments(NIGHT_EVENT_CANDIDATES, night.eventCandidateArgs())
    val event = events.firstOrNull() ?: return Candidate(CandidateStatus.NO_EVENT)
    val exact = event.hasWindow(night.instant("start_at"), night.instant("end_at"))
    return when {
        events.size > 1 || (exact && anotherNightMatches(night)) -> Candidate(CandidateStatus.AMBIGUOUS)
        exact -> Candidate(CandidateStatus.EXACT_MATCH, event["id"])
        else -> Candidate(CandidateStatus.MISMATCH)
    }
}

private fun OnboardingSql.anotherNightMatches(night: DbRow): Boolean =
    rowsWithArguments(OTHER_MATCHING_NIGHT, night.otherMatchingNightArgs()).isNotEmpty()

internal data class Candidate(
    val status: CandidateStatus,
    val eventId: String? = null,
)

internal enum class CandidateStatus { NO_EVENT, EXACT_MATCH, MISMATCH, AMBIGUOUS }

internal data class LockedCanonicalNight(
    val id: Long,
    val legacyStart: Instant,
    val end: Instant,
) {
    fun contains(instant: Instant): Boolean = instant >= legacyStart && instant < end
}

/** The caller owns the club row before this function locks the linked event. */
internal fun OnboardingSql.lockLinkedCanonicalNight(
    clubId: Long,
    eventId: Long,
    timezone: String?,
): LockedCanonicalNight? = verifiedLinkedCanonicalNight(clubId, eventId, timezone, lockEvent = true)

internal fun OnboardingSql.verifiedLinkedCanonicalNight(
    clubId: Long,
    eventId: Long,
    timezone: String?,
    lockEvent: Boolean = false,
): LockedCanonicalNight? {
    val night =
        row(
            "SELECT n.id,n.club_id,n.business_date,n.timezone,n.start_at,n.end_at," +
                "l.legacy_start_at,l.legacy_end_at FROM club_operational_nights n " +
                "JOIN canonical_night_event_links l ON l.canonical_night_id=n.id AND l.club_id=n.club_id " +
                "WHERE n.club_id=? AND l.event_id=? AND n.status='SCHEDULED' AND n.timezone=?",
            clubId,
            eventId,
            timezone,
        )?.takeIf { it.sameLegacyWindow() } ?: return null
    val eventSql =
        "SELECT start_at,end_at FROM events WHERE club_id=? AND id=?" +
            if (lockEvent) updateLock() else ""
    return row(eventSql, clubId, eventId)
        ?.takeIf { it.hasWindow(night.instant("legacy_start_at"), night.instant("legacy_end_at")) }
        ?.takeIf {
            val match = classifyNightEvent(night)
            match.status == CandidateStatus.EXACT_MATCH && match.eventId == eventId.toString()
        }?.let {
            LockedCanonicalNight(
                night.long("id"),
                night.instant("legacy_start_at"),
                night.instant("legacy_end_at"),
            )
        }
}

// Serialize updates while allowing legacy FK checks to take KEY SHARE before releasing their slot lock.
internal fun OnboardingSql.updateLock(): String =
    if (connection.metaData.databaseProductName == "PostgreSQL") " FOR NO KEY UPDATE" else " FOR UPDATE"

private fun DbRow.sameLegacyWindow(): Boolean = hasWindow(instant("legacy_start_at"), instant("legacy_end_at"))

private fun DbRow.hasWindow(
    start: Instant,
    end: Instant,
): Boolean = instant("start_at") == start && instant("end_at") == end
