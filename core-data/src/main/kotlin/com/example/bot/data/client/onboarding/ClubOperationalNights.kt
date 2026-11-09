package com.example.bot.data.client.onboarding

import com.example.bot.time.CalendarDateResolution
import com.example.bot.time.CalendarNight
import com.example.bot.time.CalendarPreview
import com.example.bot.time.CalendarSchedule
import com.example.bot.time.NightSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Calendar commands hold both the client and club locks for this entire operation. */
internal class ClubOperationalNights(
    private val session: OnboardingSession,
    private val clientId: Long,
    private val clubId: Long,
    private val zone: ZoneId,
) {
    private val sql get() = session.sql

    fun preview(
        schedule: CalendarSchedule,
        now: Instant,
    ): CalendarPreview {
        val overrides = stored(now).associate { it.night.businessDate to it.resolution() }
        return schedule.preview(now, overrides)
    }

    fun cancellations(now: Instant): List<CalendarNight> =
        stored(now).filter { it.cancelled }.map { it.night }.take(MAX_CANCELLED_PREVIEW)

    /** Complete stored-date component of reconciliation, including cancelled nights beyond the preview. */
    fun reconciliationDates(now: Instant): List<LocalDate> = stored(now).map { it.night.businessDate }

    fun reconcile(
        previous: CalendarSchedule,
        schedule: CalendarSchedule,
        touchedDates: Set<LocalDate>,
        version: Long,
        now: Instant,
    ): Boolean {
        val existing = stored(now, touchedDates).associateBy { it.night.businessDate }
        val today = now.atZone(zone).toLocalDate()
        val first = today.minusDays(LOOKBACK_DAYS)
        val horizon = (0L until HORIZON_DAYS + LOOKBACK_DAYS).map { first.plusDays(it) }
        val datedRules = (previous.datedRules + schedule.datedRules).filter { it >= first }
        val dates = (horizon + datedRules + touchedDates + existing.keys).toSortedSet()
        val changes =
            dates.mapNotNull { date ->
                plan(
                    date,
                    existing[date],
                    schedule.resolve(date),
                    previous.resolve(date),
                    date in touchedDates,
                    now,
                )
            }
        checkOverlaps(existing, changes, now)
        changes.forEach { change -> apply(change, version, now) }
        return changes.isNotEmpty()
    }

    private fun plan(
        date: LocalDate,
        stored: StoredNight?,
        proposed: CalendarDateResolution,
        previous: CalendarDateResolution,
        explicitlyEdited: Boolean,
        now: Instant,
    ): NightChange? {
        val old = stored?.night
        val next = (proposed as? CalendarDateResolution.Open)?.night
        val former = (previous as? CalendarDateResolution.Open)?.night
        val unchanged =
            when {
                stored == null -> false
                stored.cancelled -> next == null
                else -> old?.window() == next?.window() && old?.source == next?.source
            }
        if (unchanged || frozen(date, old, next, explicitlyEdited, now)) return null
        if (old != null && proposed == CalendarDateResolution.Invalid) nightConflict("calendar_night_invalid", date)
        val prior = former?.takeIf { explicitlyEdited || it.closesAt.toInstant().isAfter(now) }
        val changesWindow = old?.window() != next?.window() || stored?.cancelled == true
        if (stored?.linked == true && changesWindow) nightConflict("calendar_night_dependencies", date)
        val previousWindowChanged = prior != null && previous != proposed
        if (changesWindow || explicitlyEdited || previousWindowChanged) {
            val windows = listOfNotNull(old, next, prior).map(CalendarNight::window).distinct()
            val candidates = windows.ifEmpty { if (explicitlyEdited) listOf(businessDateWindow(date)) else emptyList() }
            if (candidates.any { hasLegacyDependency(date, it) }) nightConflict("calendar_night_dependencies", date)
        }
        return if (old == null && next == null) null else NightChange(date, stored, next)
    }

    private fun frozen(
        date: LocalDate,
        old: CalendarNight?,
        next: CalendarNight?,
        explicitlyEdited: Boolean,
        now: Instant,
    ): Boolean {
        val oldStarted = old != null && !old.opensAt.toInstant().isAfter(now)
        val nextStarted = next != null && !next.opensAt.toInstant().isAfter(now)
        if (oldStarted) {
            val oldInProgress = old?.closesAt?.toInstant()?.isAfter(now) == true
            if (explicitlyEdited || oldInProgress) nightConflict("calendar_night_started", date)
            // Weekly edits do not rewrite past nights.
            return true
        }
        if (old != null && nextStarted) nightConflict("calendar_night_started", date)
        return nextStarted
    }

    private fun businessDateWindow(date: LocalDate): NightWindow =
        NightWindow(date.atStartOfDay(zone).toInstant(), date.plusDays(2).atStartOfDay(zone).toInstant(), zone)

    private fun stored(
        now: Instant,
        explicitDates: Set<LocalDate> = emptySet(),
    ): List<StoredNight> {
        val first = now.atZone(zone).toLocalDate().minusDays(LOOKBACK_DAYS)
        val dates = explicitDates.filter { it < first }
        val condition = if (dates.isEmpty()) "" else " OR business_date IN (" + dates.joinToString(",") { "?" } + ")"
        return sql
            .rowsWithArguments(
                "SELECT n.id,n.business_date,n.timezone,n.start_at,n.end_at,n.source,n.status,l.event_id " +
                    "FROM club_operational_nights n " +
                    "LEFT JOIN canonical_night_event_links l ON l.canonical_night_id=n.id " +
                    "WHERE n.club_id=? AND n.client_id=? AND (business_date>=?$condition) ORDER BY business_date",
                listOf(clubId, clientId, first) + dates,
            ).map(DbRow::storedNight)
    }

    private fun apply(
        change: NightChange,
        version: Long,
        now: Instant,
    ) {
        val before = change.before
        val next = change.next
        val status = if (next == null) "CANCELLED" else "SCHEDULED"
        val night = next ?: checkNotNull(before).night
        val window = night.window()
        val id =
            if (before == null) {
                sql.insert(
                    "INSERT INTO club_operational_nights(client_id,club_id,business_date,timezone,start_at,end_at," +
                        "source,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    clientId,
                    clubId,
                    change.date,
                    window.zone.id,
                    window.start,
                    window.end,
                    night.source.name,
                    status,
                    now,
                    now,
                )
            } else {
                val nightId = checkNotNull(before.night.nightId)
                check(
                    sql.update(
                        "UPDATE club_operational_nights SET timezone=?,start_at=?,end_at=?,source=?,status=?," +
                            "updated_at=? WHERE id=? AND club_id=? AND client_id=?",
                        window.zone.id,
                        window.start,
                        window.end,
                        night.source.name,
                        status,
                        now,
                        nightId,
                        clubId,
                        clientId,
                    ) == 1,
                )
                nightId
            }
        sql.audit(
            checkNotNull(session.userId),
            "client.club.calendar.night.reconcile",
            clientId,
            clubId,
            version,
            now,
            session.correlation,
            buildJsonObject {
                put("nightId", id.toString())
                put("businessDate", change.date.toString())
                before?.let { put("before", it.night.auditWindow(if (it.cancelled) "CANCELLED" else "SCHEDULED")) }
                put("after", night.auditWindow(status))
            },
        )
    }

    private fun hasLegacyDependency(
        businessDate: LocalDate,
        window: NightWindow,
    ): Boolean {
        // Events are reserved for the legacy runtime, regardless of whether they have visible obligations today.
        val event = "SELECT 1 FROM events WHERE club_id=? AND start_at<? AND (end_at>? OR start_at>=?)"
        val bookings = "SELECT 1 FROM bookings WHERE club_id=? AND slot_start<? AND slot_end>?"
        val holds =
            "SELECT 1 FROM booking_holds h JOIN events e ON h.event_id=e.id " +
                "WHERE e.club_id=? AND h.slot_start<? AND h.slot_end>?"
        val timestamped =
            NIGHT_KEYED_TABLES.map { (table, column) ->
                "SELECT 1 FROM $table WHERE club_id=? AND $column<? AND $column>=?"
            }
        // Legacy records can use a disjoint interval on the same business date.
        val dayStart = businessDate.atStartOfDay(window.zone).toInstant()
        val dayEnd = businessDate.plusDays(1).atStartOfDay(window.zone).toInstant()
        val start = minOf(dayStart, window.start)
        val args = listOf(clubId, maxOf(dayEnd, window.end), start)
        val intervalQueries = listOf(bookings, holds) + timestamped
        val allArgs = args + start + intervalQueries.flatMap { args }
        val select = (listOf(event) + intervalQueries).joinToString(" UNION ALL ")
        return sql.rowsWithArguments("SELECT 1 FROM ($select) dependencies LIMIT 1", allArgs).isNotEmpty()
    }
}

private fun checkOverlaps(
    existing: Map<LocalDate, StoredNight>,
    changes: List<NightChange>,
    now: Instant,
) {
    val changed = changes.associateBy { it.date }
    val windows =
        (
            existing.values.filter { it.night.businessDate !in changed && !it.cancelled }.map { it.night } +
                changes.mapNotNull { it.next }
        ).filter { it.closesAt.toInstant().isAfter(now) }.sortedBy { it.opensAt.toInstant() }
    windows.zipWithNext().forEach { (left, right) ->
        if (left.closesAt.isAfter(right.opensAt)) nightConflict("calendar_night_overlap", right.businessDate)
    }
}

private fun DbRow.storedNight(): StoredNight {
    val zone = ZoneId.of(checkNotNull(this["timezone"]))
    return StoredNight(
        CalendarNight(
            LocalDate.parse(this["business_date"]),
            instant("start_at").atZone(zone),
            instant("end_at").atZone(zone),
            NightSource.valueOf(checkNotNull(this["source"])),
            long("id"),
        ),
        this["status"] == "CANCELLED",
        this["event_id"] != null,
    )
}

private fun CalendarNight.window(): NightWindow = NightWindow(opensAt.toInstant(), closesAt.toInstant(), opensAt.zone)

private fun CalendarNight.auditWindow(status: String): JsonObject =
    buildJsonObject {
        put("status", status)
        put("timezone", opensAt.zone.id)
        put("opensAt", opensAt.toInstant().toString())
        put("closesAt", closesAt.toInstant().toString())
        put("source", source.name)
    }

private data class NightWindow(
    val start: Instant,
    val end: Instant,
    val zone: ZoneId,
)

private data class StoredNight(
    val night: CalendarNight,
    val cancelled: Boolean,
    val linked: Boolean,
) {
    fun resolution(): CalendarDateResolution =
        if (cancelled) CalendarDateResolution.Closed else CalendarDateResolution.Open(night)
}

private data class NightChange(
    val date: LocalDate,
    val before: StoredNight?,
    val next: CalendarNight?,
)

private const val HORIZON_DAYS = 56L
private const val LOOKBACK_DAYS = 3L
private const val MAX_CANCELLED_PREVIEW = 8

private val NIGHT_KEYED_TABLES =
    listOf(
        "club_visits" to "night_start_utc",
        "operational_night_overrides" to "night_start_utc",
        "table_sessions" to "night_start_utc",
        "table_deposits" to "night_start_utc",
        "table_deposit_operations" to "night_start_utc",
        "shift_reports" to "night_start_utc",
        "post_event_stories" to "night_start_utc",
        "analytics_snapshots" to "night_start_utc",
        "checkins" to "occurred_at",
    )

internal fun nightConflict(
    code: String,
    date: LocalDate,
): Nothing = throw OnboardingFailure(code, mapOf("businessDate" to date.toString()))
