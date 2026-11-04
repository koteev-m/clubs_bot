package com.example.bot.data.client.onboarding

import com.example.bot.policy.BookingRulesNightPreview
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.ZoneId

/** A private owner read. The independent table identities make this projection read-only. */
internal class ClubBookingPreview(
    private val session: OnboardingSession,
) {
    private val sql get() = session.sql

    fun get(clubId: Long): JsonObject {
        val (clientId, club) = requireOwner(clubId, lock = true)

        val zone = ZoneId.of(checkNotNull(club["timezone"]))
        val views = ClubLayoutViews(sql)
        val halls =
            sql
                .rows("SELECT * FROM halls WHERE club_id=? ORDER BY id", clubId)
                .map { views.hall(it, preview = true) }
        val hasLinks =
            halls.any { hall ->
                hall.getValue("tables").jsonArray.any { it.jsonObject["bookingLinked"] == JsonPrimitive(true) }
            }
        val nights = ClubBookingRulesOperations(session).nightPreview(clubId, clientId, zone)
        return buildJsonObject {
            put("clubId", clubId.toString())
            put("name", club.getValue("name"))
            put("timezone", zone.id)
            put("bookingBlocker", if (hasLinks) "GUEST_BOOKING_DISABLED" else "TABLE_MAPPING_REQUIRED")
            put("halls", JsonArray(halls))
            put("nights", JsonArray(nights.map { it.previewNight(clubId, zone) }))
        }
    }

    fun requireOwner(
        clubId: Long,
        lock: Boolean,
    ): Pair<Long, DbRow> {
        val clientId = session.requireClubGrant(clubId, lockClient = lock)
        val club = session.calendarClub(clubId, lock = lock)
        if (club.active() || !isOwner(clubId, clientId)) fail("not_found")
        return clientId to club
    }

    private fun isOwner(
        clubId: Long,
        clientId: Long,
    ): Boolean {
        val profile =
            sql.row("SELECT client_id,owner_user_id FROM client_direct_club_profiles WHERE club_id=?", clubId)
                ?: sql.row("SELECT client_id,owner_user_id FROM client_club_onboarding WHERE club_id=?", clubId)
        return profile?.long("client_id") == clientId && profile.long("owner_user_id") == session.userId
    }

    private fun BookingRulesNightPreview.previewNight(
        clubId: Long,
        zone: ZoneId,
    ): JsonObject {
        val nightId = night.nightId
        val status = if (nightId == null) "NOT_MATERIALIZED" else eventStatus(clubId, nightId, zone.id)
        return JsonObject(json() + ("eventStatus" to JsonPrimitive(status)))
    }

    private fun eventStatus(
        clubId: Long,
        nightId: Long,
        timezone: String,
    ): String {
        val link =
            sql.row(
                "SELECT event_id FROM canonical_night_event_links WHERE club_id=? AND canonical_night_id=?",
                clubId,
                nightId,
            ) ?: return "UNLINKED"
        val verified = sql.verifiedLinkedCanonicalNight(clubId, link.long("event_id"), timezone)
        return if (verified?.id == nightId) "VERIFIED" else "INVALID_LINK"
    }
}
