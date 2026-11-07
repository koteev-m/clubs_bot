package com.example.bot.data.client.onboarding

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Canonical gamification storage, using the command's authority/idempotency/audit transaction. */
internal class ClubModuleSettingsOperations(
    private val session: OnboardingSession,
) {
    private val sql get() = session.sql

    fun get(
        clubId: Long,
        write: Boolean = false,
    ): JsonObject {
        val clientId = authorize(clubId, write)
        return view(clubId, clientId, stored(clubId))
    }

    fun update(
        clubId: Long,
        body: JsonObject,
    ): MutationResult {
        val clientId = authorize(clubId, write = true)
        body.only(setOf("expectedVersion", "earlyWindowMinutes"))
        val previous = stored(clubId)
        if (body.id("expectedVersion") != previous.version) fail("version_conflict")
        val minutes = body.earlyWindow()
        val nextVersion = previous.version + 1
        if (previous.present) {
            check(
                sql.update(
                    "UPDATE club_gamification_settings SET early_window_minutes=?,config_version=?,updated_at=? " +
                        "WHERE club_id=? AND config_version=?",
                    minutes,
                    nextVersion,
                    session.now,
                    clubId,
                    previous.version,
                ) == 1,
            )
        } else {
            // No stored row means the engine issues nothing. Explicit false preserves that behavior;
            // the legacy admin GET's synthetic stamps=true default is not a saved configuration.
            sql.update(
                "INSERT INTO club_gamification_settings " +
                    "(club_id,stamps_enabled,early_enabled,badges_enabled,prizes_enabled,contests_enabled," +
                    "tables_loyalty_enabled,early_window_minutes,config_version,updated_at) " +
                    "VALUES (?,FALSE,FALSE,FALSE,FALSE,FALSE,FALSE,?,?,?)",
                clubId,
                minutes,
                nextVersion,
                session.now,
            )
        }
        sql.audit(
            checkNotNull(session.userId),
            "client.club.modules.update",
            clientId,
            clubId,
            nextVersion,
            session.now,
            session.correlation,
            buildJsonObject {
                put("previousEarlyWindowMinutes", previous.earlyWindowMinutes.json())
                put("earlyWindowMinutes", minutes.json())
            },
        )
        return MutationResult("club_module_settings", clubId, clientId, view(clubId, clientId, stored(clubId)))
    }

    private fun authorize(
        clubId: Long,
        write: Boolean,
    ): Long {
        val clientId = session.requireClubGrant(clubId, READ_MODULES)
        if (write && !session.hasClubGrant(clientId, clubId, WRITE_MODULES)) fail("not_found")
        if (write && club(clubId).active()) fail("invalid_transition")
        return clientId
    }

    private fun club(clubId: Long): DbRow =
        sql.row("SELECT name,is_active FROM clubs WHERE id=?", clubId) ?: fail("not_found")

    private fun stored(clubId: Long): ModuleSettings {
        val row = sql.row("SELECT * FROM club_gamification_settings WHERE club_id=? FOR UPDATE", clubId)
        return ModuleSettings(
            present = row != null,
            version = row?.long("config_version") ?: INITIAL_VERSION,
            earlyWindowMinutes = row?.get("early_window_minutes")?.toInt(),
            flags = MODULE_FLAGS.associate { (field, column) -> field to (row?.active(column) == true) },
        )
    }

    private fun view(
        clubId: Long,
        clientId: Long,
        settings: ModuleSettings,
    ): JsonObject {
        val club = club(clubId)
        return buildJsonObject {
            put("clubId", clubId.toString())
            put("clientId", clientId.toString())
            put("name", club["name"])
            put("version", settings.version.toString())
            put("canWrite", !club.active() && session.hasClubGrant(clientId, clubId, WRITE_MODULES))
            put("settingsPresent", settings.present)
            put("earlyWindowMinutes", settings.earlyWindowMinutes.json())
            put("flags", buildJsonObject { settings.flags.forEach { (field, enabled) -> put(field, enabled) } })
            put("modules", JsonArray(moduleViews(settings)))
        }
    }
}

private data class ModuleSettings(
    val present: Boolean,
    val version: Long,
    val earlyWindowMinutes: Int?,
    val flags: Map<String, Boolean>,
)

private fun moduleViews(settings: ModuleSettings): List<JsonObject> =
    listOf(
        moduleView("stamps", settings.flagStatus("stampsEnabled"), "DEC-013", settings.flags["stampsEnabled"]),
        moduleView(
            "early_arrival",
            if (settings.earlyWindowMinutes == null) "requires_setup" else "configured",
            "DEC-013",
            settings.flags["earlyEnabled"],
        ),
        moduleView("badges", settings.flagStatus("badgesEnabled"), "DEC-013", settings.flags["badgesEnabled"]),
        moduleView("prizes", settings.flagStatus("prizesEnabled"), "DEC-013", settings.flags["prizesEnabled"]),
        moduleView("contests", "unavailable", "DEC-022", settings.flags["contestsEnabled"]),
        moduleView("table_loyalty", "unavailable", "DEC-013", settings.flags["tablesLoyaltyEnabled"]),
        moduleView("music", "unavailable", "DEC-014", null),
    )

private fun ModuleSettings.flagStatus(field: String): String =
    when {
        !present -> "requires_setup"
        flags[field] == false -> "disabled"
        else -> "unavailable"
    }

private fun moduleView(
    id: String,
    status: String,
    blockedBy: String,
    savedEnabled: Boolean?,
): JsonObject =
    buildJsonObject {
        put("id", id)
        put("status", status)
        put("blockedBy", blockedBy)
        put("savedEnabled", savedEnabled?.let(::JsonPrimitive) ?: JsonNull)
        put("activationAvailable", false)
    }

private fun JsonObject.earlyWindow(): Int? {
    val value = this["earlyWindowMinutes"] ?: fail("validation_error")
    if (value == JsonNull) return null
    return (value as? JsonPrimitive)
        ?.takeUnless { it.isString }
        ?.intOrNull
        ?.takeIf { it >= 0 } ?: fail("validation_error")
}

private fun Int?.json() = this?.let(::JsonPrimitive) ?: JsonNull

private const val INITIAL_VERSION = 1L
private val MODULE_FLAGS =
    listOf(
        "stampsEnabled" to "stamps_enabled",
        "earlyEnabled" to "early_enabled",
        "badgesEnabled" to "badges_enabled",
        "prizesEnabled" to "prizes_enabled",
        "contestsEnabled" to "contests_enabled",
        "tablesLoyaltyEnabled" to "tables_loyalty_enabled",
    )
