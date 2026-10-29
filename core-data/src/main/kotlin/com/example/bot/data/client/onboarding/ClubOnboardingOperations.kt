package com.example.bot.data.client.onboarding

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.ZoneId

/** Client lock is shared with application transitions and membership/grant revocation. */
internal class ClubOnboardingOperations(
    private val session: OnboardingSession,
) {
    private val sql get() = session.sql

    fun create(
        applicationId: Long,
        body: JsonObject,
    ): MutationResult {
        body.only(listOf("expectedVersion"))
        val application = lockedApplication(applicationId)
        val replay = existingCreation(application, body)
        if (replay != null) return replay
        session.version(application, body)
        if (application["status"] != "approved") fail("invalid_transition")
        val snapshot = approvedSnapshot(application)
        val clientId = application.long("client_id")
        return createApproved(application, snapshot, clientId)
    }

    private fun existingCreation(
        application: DbRow,
        body: JsonObject,
    ): MutationResult? {
        val applicationId = application.long("id")
        val existing = sql.row("SELECT * FROM client_club_onboarding WHERE application_id=?", applicationId)
        if (existing != null) {
            val expected = body.id("expectedVersion")
            if (expected != application.long("version") && expected != existing.long("create_application_version")) {
                fail("version_conflict")
            }
            return result(existing.long("club_id"), "club_create", get(existing.long("club_id")))
        }
        val direct = sql.row("SELECT club_id FROM client_direct_club_profiles WHERE application_id=?", applicationId)
        return direct?.let { result(it.long("club_id"), "club_create", get(it.long("club_id"))) }
    }

    private fun createApproved(
        application: DbRow,
        snapshot: JsonObject,
        clientId: Long,
    ): MutationResult {
        val applicationId = application.long("id")
        val legacyFingerprint = application.getValue("venue_fingerprint")
        val directProfiles =
            sql.rows(
                "SELECT venue_fingerprint,city_id FROM client_direct_club_profiles " +
                    "WHERE client_id=? AND legacy_fingerprint=?",
                clientId,
                legacyFingerprint,
            )
        // Compare the immutable creation identity, independent of later profile edits.
        if (directProfiles.any {
                it.getValue("venue_fingerprint") ==
                    digest(legacyFingerprint + ":" + it.getValue("city_id") + ":" + snapshot.string("timezone"))
            }
        ) {
            fail("duplicate_application")
        }
        ClientLimitOperations(session).requireSpace(clientId)
        val clubId =
            sql.insert(
                "INSERT INTO clubs(name,description,timezone,is_active) VALUES (?,?,?,FALSE)",
                snapshot.text("venueName", NAME_LIMIT, required = true),
                "",
                snapshot.text("timezone", TIMEZONE_LIMIT, required = true),
            )
        sql.update(
            "INSERT INTO client_clubs(client_id,club_id,created_at) VALUES (?,?,?)",
            clientId,
            clubId,
            session.now,
        )
        sql.update(
            "INSERT INTO client_club_onboarding(application_id,client_id,owner_user_id,club_id," +
                "approved_snapshot_hash,approved_snapshot_version,create_application_version,created_at," +
                "profile_saved_at) VALUES (?,?,?,?,?,?,?,?,?)",
            applicationId,
            clientId,
            application.long("author_user_id"),
            clubId,
            application.getValue("approved_snapshot_hash"),
            application.long("approved_snapshot_version"),
            application.long("version"),
            session.now,
            session.now,
        )
        listOf(READ_CLUB, WRITE_PROFILE).forEach { permission ->
            sql.update(
                "INSERT INTO client_club_grants(client_id,user_id,club_id,permission_code,is_active," +
                    "created_at,updated_at) VALUES (?,?,?,?,TRUE,?,?)",
                clientId,
                application.long("author_user_id"),
                clubId,
                permission,
                session.now,
                session.now,
            )
        }
        sql.update(
            "UPDATE client_club_applications SET version=version+1,updated_at=? WHERE id=?",
            session.now,
            applicationId,
        )
        session.audit("client.club.create", clientId, clubId, 1)
        return result(clubId, "club_create", get(clubId))
    }

    fun get(
        clubId: Long,
        write: Boolean = false,
    ): JsonObject {
        val direct = DirectClubOperations(session)
        if (direct.exists(clubId)) return direct.get(clubId, write)
        val association = association(clubId)
        authorize(association, write)
        // No club or submitted profile data is loaded before current exact authority is checked.
        val club = sql.row("SELECT name,description,timezone FROM clubs WHERE id=?", clubId) ?: fail("not_found")
        val application =
            sql.row("SELECT * FROM client_club_applications WHERE id=?", association.long("application_id"))
                ?: fail("not_found")
        val snapshot = approvedSnapshot(application)
        val canWrite = hasGrant(association, WRITE_PROFILE)
        return buildJsonObject {
            put("id", clubId.toString())
            put("clientId", association.getValue("client_id"))
            put("clientName", session.client(association.long("client_id")).getValue("display_name"))
            put("name", club.getValue("name"))
            put("description", club["description"].orEmpty())
            put("timezone", club.getValue("timezone"))
            listOf("countryCode", "city", "address", "unit").forEach { put(it, snapshot.string(it).orEmpty()) }
            put("version", association.getValue("profile_version"))
            put("savedAt", association.instant("profile_saved_at").toString())
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
        val direct = DirectClubOperations(session)
        if (direct.exists(clubId)) return direct.update(clubId, body)
        body.only(listOf("expectedVersion", "name", "description", "timezone"))
        val before = association(clubId)
        session.client(before.long("client_id"), lock = true)
        val association =
            sql.row("SELECT * FROM client_club_onboarding WHERE club_id=? FOR UPDATE", clubId) ?: fail("not_found")
        authorize(association, write = true)
        if (body.id("expectedVersion") != association.long("profile_version")) fail("version_conflict")
        val club = sql.row("SELECT name,description,timezone FROM clubs WHERE id=?", clubId) ?: fail("not_found")
        val merged =
            JsonObject(
                mapOf(
                    "name" to JsonPrimitive(club.getValue("name")),
                    "description" to JsonPrimitive(club["description"].orEmpty()),
                    "timezone" to JsonPrimitive(club.getValue("timezone")),
                ) + body,
            )
        val name = merged.text("name", NAME_LIMIT, required = true)
        val description = merged.text("description", DESCRIPTION_LIMIT)
        val timezone = merged.text("timezone", TIMEZONE_LIMIT, required = true)
        if (timezone !in ZoneId.getAvailableZoneIds()) fail("validation_error")
        sql.update("UPDATE clubs SET name=?,description=?,timezone=? WHERE id=?", name, description, timezone, clubId)
        sql.update(
            "UPDATE client_club_onboarding SET profile_version=profile_version+1,profile_saved_at=? WHERE club_id=?",
            session.now,
            clubId,
        )
        session.audit(
            "client.club.profile.update",
            association.long("client_id"),
            clubId,
            association.long("profile_version") + 1,
        )
        return result(clubId, "club_profile", get(clubId, write = true))
    }

    fun replayCreate(clubId: Long): JsonObject {
        val direct = DirectClubOperations(session)
        if (direct.exists(clubId)) {
            val view = direct.get(clubId)
            session.member(view.id("clientId"))
            return view
        }
        val association = association(clubId)
        lockedApplication(association.long("application_id"))
        return get(clubId)
    }

    fun accessibleClub(applicationId: Long): Long? {
        val association =
            sql.row("SELECT * FROM client_club_onboarding WHERE application_id=?", applicationId)
                ?: sql.row("SELECT * FROM client_direct_club_profiles WHERE application_id=?", applicationId)
                ?: return null
        return if (hasGrant(association, READ_CLUB)) association.long("club_id") else null
    }

    private fun lockedApplication(applicationId: Long): DbRow {
        val before =
            sql.row("SELECT client_id FROM client_club_applications WHERE id=?", applicationId) ?: fail("not_found")
        session.client(before.long("client_id"), lock = true)
        session.member(before.long("client_id"))
        val application =
            sql.row("SELECT * FROM client_club_applications WHERE id=? FOR UPDATE", applicationId) ?: fail("not_found")
        if (application.long("author_user_id") != session.userId) fail("not_found")
        return application
    }

    private fun association(clubId: Long): DbRow =
        sql.row("SELECT * FROM client_club_onboarding WHERE club_id=?", clubId) ?: fail("not_found")

    private fun authorize(
        association: DbRow,
        write: Boolean,
    ) {
        session.member(association.long("client_id"), manage = false)
        if (!hasGrant(association, READ_CLUB) || (write && !hasGrant(association, WRITE_PROFILE))) fail("not_found")
    }

    private fun hasGrant(
        association: DbRow,
        permission: String,
    ): Boolean =
        sql.row(
            "SELECT g.club_id FROM client_club_grants g JOIN client_clubs m ON " +
                "m.client_id=g.client_id AND m.club_id=g.club_id WHERE g.client_id=? AND " +
                "g.club_id=? AND g.user_id=? AND g.permission_code=? AND g.is_active=TRUE",
            association.long("client_id"),
            association.long("club_id"),
            session.userId,
            permission,
        ) != null

    private fun approvedSnapshot(application: DbRow): JsonObject {
        if (application["status"] != "approved") fail("invalid_transition")
        val snapshot =
            onboardingJson.parseToJsonElement(checkNotNull(application["submitted_snapshot"])) as? JsonObject
                ?: error("Invalid stored approval snapshot")
        check(digest(canonical(snapshot).toString()) == application["submitted_hash"])
        check(application["submitted_hash"] == application["approved_snapshot_hash"])
        check(application["submitted_version"] == application["approved_snapshot_version"])
        check(snapshot.string("timezone") in ZoneId.getAvailableZoneIds())
        return snapshot
    }

    private fun result(
        clubId: Long,
        type: String,
        view: JsonObject,
    ) = MutationResult(type, clubId, view.id("clientId"), view)

    private companion object {
        const val NAME_LIMIT = 255
        const val DESCRIPTION_LIMIT = 2000
        const val TIMEZONE_LIMIT = 64
    }
}
