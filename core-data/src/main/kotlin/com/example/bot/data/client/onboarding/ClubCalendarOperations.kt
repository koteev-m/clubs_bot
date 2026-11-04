package com.example.bot.data.client.onboarding

import com.example.bot.time.CalendarNight
import com.example.bot.time.CalendarPreview
import com.example.bot.time.CalendarRules
import com.example.bot.time.CalendarSchedule
import com.example.bot.time.ClubException
import com.example.bot.time.ClubHoliday
import com.example.bot.time.ClubHour
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

internal class ClubCalendarOperations(
    private val session: OnboardingSession,
) {
    private val sql get() = session.sql

    fun get(
        clubId: Long,
        write: Boolean = false,
    ): JsonObject {
        val clientId = session.requireClubGrant(clubId, WRITE_CALENDAR.takeIf { write })
        return view(clubId, clientId, session.calendarClub(clubId))
    }

    fun update(
        clubId: Long,
        body: JsonObject,
    ): MutationResult {
        val clientId = session.requireClubGrant(clubId, WRITE_CALENDAR)
        val club = session.calendarClub(clubId, lock = true)
        val version = club.long("calendar_version")
        if (body.id("expectedVersion") != version || body.string("timezone") != club["timezone"]) {
            fail("version_conflict")
        }
        if (club.active()) fail("calendar_night_cutover_required")
        val changes = body.calendarChanges()
        val zone = ZoneId.of(checkNotNull(club["timezone"]))
        val previous = rules(clubId).schedule(zone)
        val now = session.now
        changes.forEach { apply(clubId, it) }
        val updatedVersion = version + 1
        val reconciled =
            ClubOperationalNights(session, clientId, clubId, zone).reconcile(
                previous,
                rules(clubId).schedule(zone),
                changes.mapNotNull { it.key as? LocalDate }.toSet(),
                updatedVersion,
                now,
            )
        if (changes.isEmpty() && !reconciled) {
            return MutationResult("club_calendar", clubId, clientId, view(clubId, clientId, club))
        }
        sql.update("UPDATE clubs SET calendar_version=? WHERE id=?", updatedVersion, clubId)
        sql.audit(
            checkNotNull(session.userId),
            "client.club.calendar.update",
            clientId,
            clubId,
            updatedVersion,
            session.now,
            session.correlation,
            buildJsonObject {
                put("timezone", club["timezone"])
                put(
                    "changedRules",
                    JsonArray(changes.map(CalendarChange::auditJson)),
                )
            },
        )
        return MutationResult("club_calendar", clubId, clientId, view(clubId, clientId, session.calendarClub(clubId)))
    }

    private fun view(
        clubId: Long,
        clientId: Long,
        club: DbRow,
    ): JsonObject {
        val rules = rules(clubId)
        val zone = ZoneId.of(checkNotNull(club["timezone"]))
        val now = session.now
        val nights = ClubOperationalNights(session, clientId, clubId, zone)
        val preview = nights.preview(rules.schedule(zone), now)
        return buildJsonObject {
            put("clubId", clubId.toString())
            put("name", club["name"])
            put("timezone", zone.id)
            put("version", club["calendar_version"])
            put("canWrite", session.hasClubGrant(clientId, clubId, WRITE_CALENDAR))
            put("weekly", JsonArray(rules.weekly.map { it.ruleView(CalendarRuleTable.WEEKLY) }))
            put("holidays", JsonArray(rules.holidays.map { it.ruleView(CalendarRuleTable.HOLIDAYS) }))
            put("exceptions", JsonArray(rules.exceptions.map { it.ruleView(CalendarRuleTable.EXCEPTIONS) }))
            put("nights", JsonArray(preview.nights.map(CalendarNight::json)))
            put("cancelledNights", JsonArray(nights.cancellations(now).map(CalendarNight::json)))
            put("invalidDates", JsonArray(preview.invalidDates.map { JsonPrimitive(it.toString()) }))
        }
    }

    // Caller holds the client lock and has checked current access to the club.
    fun preview(
        clubId: Long,
        clientId: Long,
        zone: ZoneId,
    ): CalendarPreview =
        ClubOperationalNights(session, clientId, clubId, zone).preview(rules(clubId).schedule(zone), session.now)

    private fun StoredCalendarRules.schedule(zone: ZoneId): CalendarSchedule =
        CalendarSchedule(
            CalendarRules(weekly.map(DbRow::clubHour), holidays.map(DbRow::holiday), exceptions.map(DbRow::exception)),
            zone,
        )

    private fun rules(clubId: Long): StoredCalendarRules =
        StoredCalendarRules(
            rows(clubId, CalendarRuleTable.WEEKLY),
            rows(clubId, CalendarRuleTable.HOLIDAYS),
            rows(clubId, CalendarRuleTable.EXCEPTIONS),
        )

    private fun rows(
        clubId: Long,
        table: CalendarRuleTable,
    ): List<DbRow> = sql.rows("SELECT * FROM ${table.table} WHERE club_id=? ORDER BY ${table.key}", clubId)

    private fun apply(
        clubId: Long,
        change: CalendarChange,
    ) {
        val table = change.table
        val where = "WHERE club_id=? AND ${table.key}=?"
        if (change.mode == CalendarChangeMode.REMOVE) {
            sql.update("DELETE FROM ${table.table} $where", clubId, change.key)
            return
        }
        val columns = listOf(table.open, table.close, "close_next_day", "start_next_day").toMutableList()
        val values =
            mutableListOf<Any?>(
                change.window?.open,
                change.window?.close,
                change.window?.nextDay,
                change.window?.startNextDay ?: false,
            )
        if (table != CalendarRuleTable.WEEKLY) {
            columns += "is_open"
            values += change.mode == CalendarChangeMode.OPEN
        }
        val assignments = columns.joinToString(",") { "$it=?" }
        val args = values + listOf(clubId, change.key)
        // Preserve legacy row identity and any stored holiday name / exception reason.
        if (sql.updateWithArguments("UPDATE ${table.table} SET $assignments $where", args) == 0) {
            val placeholders = args.joinToString(",") { "?" }
            sql.updateWithArguments(
                "INSERT INTO ${table.table}(${columns.joinToString(",")},club_id,${table.key}) VALUES ($placeholders)",
                args,
            )
        }
    }
}

internal fun OnboardingSession.calendarClub(
    clubId: Long,
    lock: Boolean = false,
): DbRow =
    sql.row(
        "SELECT name,timezone,calendar_version,is_active FROM clubs WHERE id=?" + if (lock) " FOR UPDATE" else "",
        clubId,
    ) ?: fail("not_found")

private data class StoredCalendarRules(
    val weekly: List<DbRow>,
    val holidays: List<DbRow>,
    val exceptions: List<DbRow>,
)

private fun DbRow.localTime(key: String): LocalTime? = this[key]?.let(LocalTime::parse)

private fun DbRow.nextDay(): Boolean? = this["close_next_day"]?.let { active("close_next_day") }

private fun DbRow.clubHour(): ClubHour =
    ClubHour(
        DayOfWeek.of(long("dow").toInt()),
        checkNotNull(localTime("open_local")),
        checkNotNull(localTime("close_local")),
        nextDay(),
        active("start_next_day"),
    )

private fun DbRow.holiday(): ClubHoliday =
    ClubHoliday(
        LocalDate.parse(getValue("date")),
        active("is_open"),
        localTime("override_open_local"),
        localTime("override_close_local"),
        nextDay(),
        active("start_next_day").takeIf { localTime("override_open_local") != null || it },
    )

private fun DbRow.exception(): ClubException =
    ClubException(
        LocalDate.parse(getValue("date")),
        active("is_open"),
        localTime("override_open_local"),
        localTime("override_close_local"),
        nextDay(),
        active("start_next_day").takeIf { localTime("override_open_local") != null || it },
    )

private fun DbRow.ruleView(table: CalendarRuleTable): JsonObject =
    buildJsonObject {
        if (table == CalendarRuleTable.WEEKLY) put("day", long("dow").toInt()) else put("date", this@ruleView["date"])
        put("mode", if (table == CalendarRuleTable.WEEKLY || active("is_open")) "open" else "closed")
        val open = localTime(table.open)
        val close = localTime(table.close)
        put("open", open?.toString())
        put("close", close?.toString())
        put("startNextDay", active("start_next_day"))
        put("nextDay", nextDay() ?: (open != null && close != null && close < open))
    }

private fun CalendarNight.json(): JsonObject =
    buildJsonObject {
        put("businessDate", businessDate.toString())
        put("nightId", nightId?.toString())
        put("timezone", opensAt.zone.id)
        put("opensAt", opensAt.toOffsetDateTime().toString())
        put("closesAt", closesAt.toOffsetDateTime().toString())
        put("source", source.name)
    }

private fun CalendarChange.auditJson(): JsonObject =
    buildJsonObject {
        put("rule", table.name)
        put("key", key.toString())
        put("mode", mode.name)
        window?.let {
            put("open", it.open.toString())
            put("close", it.close.toString())
            put("nextDay", it.nextDay)
            put("startNextDay", it.startNextDay)
        }
    }
