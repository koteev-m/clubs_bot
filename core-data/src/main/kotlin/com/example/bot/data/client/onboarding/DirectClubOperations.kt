package com.example.bot.data.client.onboarding

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.Normalizer
import java.time.ZoneId
import java.util.Locale

/** Direct creation has its own durable profile; the historical approval ledger remains immutable. */
internal class DirectClubOperations(
    private val session: OnboardingSession,
) {
    private val sql get() = session.sql

    fun create(
        clientId: Long,
        body: JsonObject,
    ): MutationResult {
        body.only(
            listOf("venueName", "countryCode", "city", "cityId", "region", "address", "timezone", "applicationId"),
        )
        session.client(clientId, lock = true)
        session.member(clientId)
        val application = savedApplication(clientId, body)
        val previous = application?.let { alreadyCreated(it.long("id")) }
        val fields = fields(body)
        val legacyFingerprint = venueFingerprint(fields)
        val fingerprint =
            digest(legacyFingerprint + ":" + fields.getValue("cityId") + ":" + fields.getValue("timezone"))
        val duplicate =
            sql.row(
                "SELECT club_id FROM client_direct_club_profiles WHERE client_id=? AND venue_fingerprint=?",
                clientId,
                fingerprint,
            )
        val existing = previous ?: duplicate?.long("club_id")
        if (existing != null) return result(existing, ClubOnboardingOperations(session).get(existing))
        val legacyDuplicate =
            sql.row(
                "SELECT o.club_id FROM client_club_onboarding o JOIN client_club_applications a " +
                    "ON a.id=o.application_id WHERE o.client_id=? AND a.venue_fingerprint=? AND a.timezone=?",
                clientId,
                legacyFingerprint,
                fields.getValue("timezone"),
            )
        if (legacyDuplicate != null) {
            // Historical profiles have no stable city identity. An unlinked name/address match
            // cannot silently adopt an existing club in a different same-named city or region.
            fail("duplicate_application")
        }
        ClientLimitOperations(session).requireSpace(clientId)
        val clubId =
            sql.insert(
                "INSERT INTO clubs(name,description,timezone,is_active) VALUES (?,'',?,FALSE)",
                fields.getValue("venueName"),
                fields.getValue("timezone"),
            )
        sql.update(
            "INSERT INTO client_clubs(client_id,club_id,created_at) VALUES (?,?,?)",
            clientId,
            clubId,
            session.now,
        )
        sql.update(
            "INSERT INTO client_direct_club_profiles(club_id,client_id,owner_user_id,application_id," +
                "venue_fingerprint,country_code,city,address,city_id,region,legacy_fingerprint," +
                "created_at,profile_saved_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
            clubId,
            clientId,
            session.userId,
            application?.long("id"),
            fingerprint,
            fields.getValue("countryCode"),
            fields.getValue("city"),
            fields.getValue("address"),
            fields.getValue("cityId"),
            fields.getValue("region"),
            legacyFingerprint,
            session.now,
            session.now,
        )
        listOf(READ_CLUB, WRITE_PROFILE).forEach { permission ->
            sql.update(
                "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active," +
                    "created_at,updated_at) VALUES (?,?,?,?,TRUE,?,?)",
                clientId,
                session.userId,
                clubId,
                permission,
                session.now,
                session.now,
            )
        }
        session.audit("client.club.create", clientId, clubId, 1)
        return result(clubId, get(clubId))
    }

    fun exists(clubId: Long): Boolean =
        sql.row("SELECT club_id FROM client_direct_club_profiles WHERE club_id=?", clubId) != null

    fun get(
        clubId: Long,
        write: Boolean = false,
    ): JsonObject {
        val profile = profile(clubId)
        authorize(profile, write)
        val club = sql.row("SELECT name,description,timezone FROM clubs WHERE id=?", clubId) ?: fail("not_found")
        val canWrite = hasGrant(profile, WRITE_PROFILE)
        return buildJsonObject {
            put("id", clubId.toString())
            put("clientId", profile.getValue("client_id"))
            put("clientName", session.client(profile.long("client_id")).getValue("display_name"))
            put("name", club.getValue("name"))
            put("description", club["description"].orEmpty())
            put("timezone", club.getValue("timezone"))
            put("countryCode", profile.getValue("country_code"))
            put("cityId", profile.getValue("city_id"))
            put("region", profile.getValue("region"))
            listOf("city", "address", "unit").forEach { put(it, profile.getValue(it)) }
            put("version", profile.getValue("profile_version"))
            put("savedAt", profile.instant("profile_saved_at").toString())
            put(
                "permissions",
                JsonArray(
                    (listOf(READ_CLUB) + if (canWrite) listOf(WRITE_PROFILE) else emptyList()).map(::JsonPrimitive),
                ),
            )
            put("availableActions", JsonArray((if (canWrite) listOf("update") else emptyList()).map(::JsonPrimitive)))
        }
    }

    fun update(
        clubId: Long,
        body: JsonObject,
    ): MutationResult {
        body.only(listOf("expectedVersion", "name", "description", "timezone"))
        session.client(profile(clubId).long("client_id"), lock = true)
        val profile = profile(clubId)
        authorize(profile, write = true)
        if (body.id("expectedVersion") != profile.long("profile_version")) fail("version_conflict")
        val club = get(clubId, write = true)
        val merged = JsonObject(club + body)
        val name = merged.text("name", NAME_LIMIT, required = true)
        val description = merged.text("description", DESCRIPTION_LIMIT)
        val timezone = merged.text("timezone", TIMEZONE_LIMIT, required = true)
        if (timezone !in ZoneId.getAvailableZoneIds()) fail("validation_error")
        sql.update("UPDATE clubs SET name=?,description=?,timezone=? WHERE id=?", name, description, timezone, clubId)
        sql.update(
            "UPDATE client_direct_club_profiles SET profile_version=profile_version+1," +
                "profile_saved_at=? WHERE club_id=?",
            session.now,
            clubId,
        )
        session.audit(
            "client.club.profile.update",
            profile.long("client_id"),
            clubId,
            profile.long("profile_version") + 1,
        )
        return MutationResult("club_profile", clubId, profile.long("client_id"), get(clubId, write = true))
    }

    private fun fields(body: JsonObject): Map<String, String> {
        val values =
            mapOf(
                "venueName" to body.text("venueName", NAME_LIMIT, required = true),
                "countryCode" to body.text("countryCode", COUNTRY_LIMIT, required = true),
                "city" to body.text("city", CITY_LIMIT, required = true),
                "cityId" to body.text("cityId", CITY_ID_LIMIT),
                "region" to body.text("region", NAME_LIMIT),
                "address" to body.text("address", ADDRESS_LIMIT, required = true),
                "timezone" to body.text("timezone", TIMEZONE_LIMIT, required = true),
                "unit" to "",
            )
        if (values["countryCode"] !in Locale.getISOCountries().toSet() ||
            values["timezone"] !in ZoneId.getAvailableZoneIds()
        ) {
            fail("validation_error")
        }
        return values
    }

    private fun savedApplication(
        clientId: Long,
        body: JsonObject,
    ): DbRow? {
        if (!body.containsKey("applicationId")) return null
        return sql.row(
            "SELECT * FROM client_club_applications WHERE id=? AND client_id=? AND author_user_id=?",
            body.id("applicationId"),
            clientId,
            session.userId,
        ) ?: fail("not_found")
    }

    private fun alreadyCreated(applicationId: Long): Long? =
        (
            sql.row("SELECT club_id FROM client_club_onboarding WHERE application_id=?", applicationId)
                ?: sql.row("SELECT club_id FROM client_direct_club_profiles WHERE application_id=?", applicationId)
        )?.long("club_id")

    private fun profile(clubId: Long): DbRow =
        sql.row("SELECT * FROM client_direct_club_profiles WHERE club_id=?", clubId) ?: fail("not_found")

    private fun authorize(
        profile: DbRow,
        write: Boolean,
    ) {
        session.member(profile.long("client_id"), manage = false)
        if (!hasGrant(profile, READ_CLUB) || (write && !hasGrant(profile, WRITE_PROFILE))) fail("not_found")
    }

    private fun hasGrant(
        profile: DbRow,
        permission: String,
    ): Boolean =
        sql.row(
            "SELECT g.club_id FROM client_club_grants g JOIN client_clubs m ON " +
                "m.client_id=g.client_id AND m.club_id=g.club_id WHERE g.client_id=? AND " +
                "g.club_id=? AND g.user_id=? AND g.permission_code=? AND g.is_active=TRUE",
            profile.long("client_id"),
            profile.long("club_id"),
            session.userId,
            permission,
        ) != null

    private fun result(
        clubId: Long,
        view: JsonObject,
    ) = MutationResult("club_create", clubId, view.id("clientId"), view)

    private companion object {
        const val NAME_LIMIT = 255
        const val COUNTRY_LIMIT = 2
        const val CITY_LIMIT = 128
        const val CITY_ID_LIMIT = 32
        const val ADDRESS_LIMIT = 512
        const val TIMEZONE_LIMIT = 64
        const val DESCRIPTION_LIMIT = 2000
    }
}

internal fun venueFingerprint(fields: Map<String, String>): String =
    digest(
        listOf("venueName", "countryCode", "city", "address", "unit").joinToString("|") {
            Normalizer
                .normalize(fields.getValue(it), Normalizer.Form.NFKC)
                .lowercase(Locale.ROOT)
                .replace(Regex("\\s+"), " ")
                .trim()
        },
    )
