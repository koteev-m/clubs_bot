package com.example.bot.data.client.onboarding

import com.example.bot.policy.BookingRulesNightPreview
import com.example.bot.policy.CabinetBookingRules
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.time.ZoneId

internal class ClubBookingRulesOperations(
    private val session: OnboardingSession,
) {
    private val sql get() = session.sql

    fun get(
        clubId: Long,
        write: Boolean = false,
    ): JsonObject {
        val clientId = session.requireClubGrant(clubId, WRITE_BOOKING_RULES.takeIf { write })
        return view(clubId, clientId, stored(clubId, clientId))
    }

    fun update(
        clubId: Long,
        body: JsonObject,
    ): MutationResult {
        val clientId = session.requireClubGrant(clubId, WRITE_BOOKING_RULES)
        val previous = stored(clubId, clientId)
        body.only(RULE_FIELDS + "expectedVersion")
        if (body.id("expectedVersion") != previous.version) fail("version_conflict")
        val next = StoredBookingRules(previous.version + 1, body.rules())
        save(clubId, clientId, next)
        sql.audit(
            checkNotNull(session.userId),
            "client.club.booking_rules.update",
            clientId,
            clubId,
            next.version,
            session.now,
            session.correlation,
            buildJsonObject {
                put("before", previous.rules.json())
                put("after", next.rules.json())
            },
        )
        return MutationResult("club_booking_rules", clubId, clientId, view(clubId, clientId, next))
    }

    fun nightPreview(
        clubId: Long,
        clientId: Long,
        zone: ZoneId,
    ): List<BookingRulesNightPreview> {
        val rules = stored(clubId, clientId).rules
        return ClubCalendarOperations(session).preview(clubId, clientId, zone).nights.map(rules::preview)
    }

    private fun stored(
        clubId: Long,
        clientId: Long,
    ): StoredBookingRules {
        val row = sql.row("SELECT * FROM client_club_booking_rules WHERE club_id=? AND client_id=?", clubId, clientId)
        return if (row == null) {
            StoredBookingRules(INITIAL_VERSION, CabinetBookingRules())
        } else {
            StoredBookingRules(
                row.long("version"),
                CabinetBookingRules(
                    cutoffMinutes = row.long("cutoff_minutes").toInt(),
                    arrivalMinutes = row["arrival_minutes"]?.toInt(),
                    holdMinutes = row["hold_minutes"]?.toInt(),
                    retentionMinutes = row.long("retention_minutes").toInt(),
                ),
            )
        }
    }

    private fun save(
        clubId: Long,
        clientId: Long,
        stored: StoredBookingRules,
    ) {
        val rules = stored.rules
        val args =
            listOf(
                stored.version,
                rules.cutoffMinutes,
                rules.arrivalMinutes,
                rules.holdMinutes,
                rules.retentionMinutes,
                clubId,
                clientId,
            )
        if (stored.version == INITIAL_VERSION + 1) {
            sql.updateWithArguments(
                "INSERT INTO client_club_booking_rules " +
                    "(version,cutoff_minutes,arrival_minutes,hold_minutes,retention_minutes,club_id,client_id) " +
                    "VALUES (?,?,?,?,?,?,?)",
                args,
            )
        } else {
            check(
                sql.updateWithArguments(
                    "UPDATE client_club_booking_rules SET version=?,cutoff_minutes=?,arrival_minutes=?," +
                        "hold_minutes=?,retention_minutes=? WHERE club_id=? AND client_id=?",
                    args,
                ) == 1,
            )
        }
    }

    private fun view(
        clubId: Long,
        clientId: Long,
        stored: StoredBookingRules,
    ): JsonObject {
        val club = sql.row("SELECT name,timezone FROM clubs WHERE id=?", clubId) ?: fail("not_found")
        val zone = ZoneId.of(checkNotNull(club["timezone"]))
        val calendar = ClubCalendarOperations(session).preview(clubId, clientId, zone)
        return buildJsonObject {
            put("clubId", clubId.toString())
            put("name", club["name"])
            put("version", stored.version.toString())
            put("timezone", zone.id)
            put("canWrite", session.hasClubGrant(clientId, clubId, WRITE_BOOKING_RULES))
            put("rules", stored.rules.json())
            put("nights", JsonArray(calendar.nights.map { stored.rules.preview(it).json() }))
            put("invalidDates", JsonArray(calendar.invalidDates.map { JsonPrimitive(it.toString()) }))
        }
    }
}

private data class StoredBookingRules(
    val version: Long,
    val rules: CabinetBookingRules,
)

private fun JsonObject.rules(): CabinetBookingRules =
    CabinetBookingRules(
        cutoffMinutes = minutes("cutoffMinutes", CabinetBookingRules.OFFSET_RANGE) ?: fail("validation_error"),
        arrivalMinutes = minutes("arrivalMinutes", CabinetBookingRules.OFFSET_RANGE),
        holdMinutes = minutes("holdMinutes", CabinetBookingRules.HOLD_RANGE),
        retentionMinutes = minutes("retentionMinutes", CabinetBookingRules.OFFSET_RANGE) ?: fail("validation_error"),
    )

private fun JsonObject.minutes(
    field: String,
    range: IntRange,
): Int? {
    val value = this[field] ?: fail("validation_error")
    if (value == JsonNull) return null
    return (value as? JsonPrimitive)
        ?.takeUnless { it.isString }
        ?.intOrNull
        ?.takeIf { it in range } ?: fail("validation_error")
}

private fun CabinetBookingRules.json(): JsonObject =
    buildJsonObject {
        put("cutoffMinutes", cutoffMinutes)
        put("arrivalMinutes", arrivalMinutes?.let(::JsonPrimitive) ?: JsonNull)
        put("holdMinutes", holdMinutes?.let(::JsonPrimitive) ?: JsonNull)
        put("retentionMinutes", retentionMinutes)
    }

internal fun BookingRulesNightPreview.json(): JsonObject =
    buildJsonObject {
        put("businessDate", night.businessDate.toString())
        put("opensAt", night.opensAt.toOffsetDateTime().toString())
        put("closesAt", night.closesAt.toOffsetDateTime().toString())
        put("cutoffAt", cutoffAt.toOffsetDateTime().toString())
        put("arrivalBy", arrivalBy?.toOffsetDateTime()?.toString())
        put("cutoffAtOrBeforeOpen", cutoffAtOrBeforeOpen)
        put("arrivalAtOrAfterClose", arrivalAtOrAfterClose)
        put("cutoffAfterArrival", cutoffAfterArrival)
    }

private const val INITIAL_VERSION = 1L
private val RULE_FIELDS = setOf("cutoffMinutes", "arrivalMinutes", "holdMinutes", "retentionMinutes")
