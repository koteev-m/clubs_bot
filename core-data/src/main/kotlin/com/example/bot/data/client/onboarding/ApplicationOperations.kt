package com.example.bot.data.client.onboarding

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.Normalizer
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

internal class ApplicationOperations(
    private val session: OnboardingSession,
) {
    private val sql get() = session.sql

    fun get(
        id: Long,
        platform: Boolean,
    ): JsonObject {
        if (platform) session.owner()
        val application = row(id)
        authorize(application, platform)
        return view(application, platform)
    }

    fun search(
        clientId: Long?,
        platform: Boolean,
        body: JsonObject,
    ): JsonObject {
        body.only(listOf("cursor", "limit", "clientId", "status"))
        val selectedClient =
            if (platform) {
                session.owner()
                body.string("clientId")?.let { body.id("clientId") }
            } else {
                checkNotNull(clientId).also { session.member(it) }
            }
        val status = body.string("status")
        if (status != null && status !in STATES) fail("validation_error")
        val limit = pageLimit(body)
        val parameters = mutableListOf<Any?>(cursor(body))
        val condition =
            buildString {
                append("id>?")
                if (platform) {
                    append(" AND submitted_at IS NOT NULL")
                } else {
                    append(" AND (author_user_id=? OR submitted_at IS NOT NULL)")
                    parameters += session.userId
                }
                if (selectedClient != null) {
                    append(" AND client_id=?")
                    parameters += selectedClient
                }
                if (status != null) {
                    append(" AND status=?")
                    parameters += status
                }
            }
        parameters += limit + 1
        return page(
            sql
                .rowsWithArguments(
                    "SELECT * FROM client_club_applications WHERE $condition ORDER BY id LIMIT ?",
                    parameters,
                ).map { view(it, platform) },
            limit,
        )
    }

    fun create(
        clientId: Long,
        body: JsonObject,
    ): MutationResult {
        session.client(clientId, lock = true)
        session.member(clientId)
        body.only(FIELDS + "previousApplicationId")
        val previousId = body.string("previousApplicationId")?.let { body.id("previousApplicationId") }
        if (previousId != null) {
            val previous = row(previousId)
            authorize(previous, platform = false)
            if (previous.long("client_id") != clientId || previous["status"] !in setOf("rejected", "cancelled")) {
                fail("validation_error")
            }
        }
        val fields = draftFields(body)
        val history = history("draft", null)
        val id =
            sql.insertWithArguments(
                "INSERT INTO client_club_applications(client_id,author_user_id,venue_name," +
                    "country_code,city,address,unit," +
                    "timezone,comment,previous_application_id,history_json,created_at," +
                    "updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                listOf(clientId, session.userId) + fields.values +
                    listOf(previousId, history.toString(), session.now, session.now),
            )
        session.audit("client.application.create", clientId, id, 1)
        return result(row(id), platform = false)
    }

    fun update(
        id: Long,
        body: JsonObject,
    ): MutationResult {
        body.only(FIELDS + "expectedVersion")
        val application = locked(id, platform = false)
        own(application)
        session.version(application, body)
        if (application["status"] != "draft") fail("invalid_transition")
        val merged = JsonObject(FIELDS.associateWith { JsonPrimitive(application.getValue(column(it))) } + body)
        val fields = draftFields(merged)
        sql.updateWithArguments(
            "UPDATE client_club_applications SET venue_name=?,country_code=?,city=?,address=?," +
                "unit=?,timezone=?,comment=?," +
                "updated_at=?,version=version+1 WHERE id=?",
            fields.values + listOf(session.now, id),
        )
        session.audit("client.application.update", application.long("client_id"), id, application.long("version") + 1)
        return result(row(id), platform = false)
    }

    fun transition(
        id: Long,
        operation: OnboardingMutation,
        body: JsonObject,
    ): MutationResult {
        body.only(
            listOf(
                "expectedVersion",
                "reason",
                "commercialReference",
                "commercialAgreedAt",
                "platformConfirmed",
                "internalNote",
            ),
        )
        val platform = operation in PLATFORM_ACTIONS
        val application = locked(id, platform)
        if (!platform) own(application)
        session.version(application, body)
        val state = checkNotNull(application["status"])
        return when (operation) {
            OnboardingMutation.APPLICATION_SUBMIT -> submit(application, body)
            OnboardingMutation.APPLICATION_REVIEW -> {
                if (state != "submitted") fail("invalid_transition")
                session.member(application.long("client_id"), application.long("author_user_id"))
                change(application, "reviewing", null, body, platform)
            }
            OnboardingMutation.APPLICATION_APPROVE -> {
                if (state != "reviewing") fail("invalid_transition")
                session.member(application.long("client_id"), application.long("author_user_id"))
                approve(application, body)
            }
            OnboardingMutation.APPLICATION_REJECT -> {
                if (state != "reviewing") fail("invalid_transition")
                change(application, "rejected", body.text("reason", COMMENT_LIMIT, required = true), body, platform)
            }
            OnboardingMutation.APPLICATION_CANCEL, OnboardingMutation.PLATFORM_APPLICATION_CANCEL -> {
                val allowed =
                    if (platform) {
                        setOf(
                            "submitted",
                            "reviewing",
                            "approved",
                        )
                    } else {
                        setOf("draft", "submitted", "reviewing")
                    }
                if (state !in allowed) fail("invalid_transition")
                change(
                    application,
                    "cancelled",
                    body.text("reason", COMMENT_LIMIT, required = platform).ifEmpty {
                        null
                    },
                    body,
                    platform,
                )
            }
            else -> fail("validation_error")
        }
    }

    private fun submit(
        application: DbRow,
        body: JsonObject,
    ): MutationResult {
        if (application["status"] != "draft") fail("invalid_transition")
        val fields =
            draftFields(
                JsonObject(FIELDS.associateWith { JsonPrimitive(application.getValue(column(it))) }),
                complete = true,
            )
        val snapshot = JsonObject(fields.mapValues { JsonPrimitive(it.value) })
        val fingerprint =
            digest(
                listOf(
                    "venueName",
                    "countryCode",
                    "city",
                    "address",
                    "unit",
                ).joinToString("|") { normalize(fields.getValue(it)) },
            )
        val duplicate =
            sql.row(
                "SELECT id FROM client_club_applications WHERE client_id=? AND reserved_fingerprint=?",
                application.long("client_id"),
                fingerprint,
            )
        if (duplicate != null) fail("duplicate_application")
        sql.update(
            "UPDATE client_club_applications SET status='submitted',submitted_at=?," +
                "submitted_snapshot=?,submitted_hash=?," +
                "submitted_version=?,venue_fingerprint=?,reserved_fingerprint=? WHERE id=?",
            session.now,
            snapshot.toString(),
            digest(canonical(snapshot).toString()),
            application.long("version") + 1,
            fingerprint,
            fingerprint,
            application.long("id"),
        )
        return change(application, "submitted", null, body, platform = false)
    }

    private fun approve(
        application: DbRow,
        body: JsonObject,
    ): MutationResult {
        val reference = body.text("commercialReference", ADDRESS_LIMIT, required = true)
        val agreed =
            try {
                LocalDate.parse(body.text("commercialAgreedAt", DATE_TEXT_LIMIT, required = true))
            } catch (_: java.time.DateTimeException) {
                fail("validation_error")
            }
        if (body["platformConfirmed"] != JsonPrimitive(true)) fail("validation_error")
        if (agreed.isAfter(session.now.atZone(java.time.ZoneOffset.UTC).toLocalDate())) fail("validation_error")
        sql.update(
            "UPDATE client_club_applications SET commercial_reference=?,commercial_agreed_at=?," +
                "platform_confirmed=TRUE," +
                "approved_snapshot_hash=submitted_hash,approved_snapshot_version=submitted_version WHERE id=?",
            reference,
            agreed,
            application.long("id"),
        )
        return change(
            application,
            "approved",
            body.text("reason", COMMENT_LIMIT).ifEmpty { null },
            body,
            platform = true,
        )
    }

    private fun change(
        application: DbRow,
        state: String,
        reason: String?,
        body: JsonObject,
        platform: Boolean,
    ): MutationResult {
        val history = onboardingJson.parseToJsonElement(checkNotNull(application["history_json"])) as JsonArray
        val nextHistory = JsonArray(history + history(state, reason))
        val decision = state in setOf("approved", "rejected", "cancelled")
        val internalNote =
            if (platform) {
                body.text("internalNote", COMMENT_LIMIT).ifEmpty {
                    null
                }
            } else {
                application["internal_note"]
            }
        // The reservation is retained for submitted/reviewing/approved, and freed only for terminal decisions.
        sql.update(
            "UPDATE client_club_applications SET status=?,decision_reason=?,internal_note=?,history_json=?," +
                "decision_actor_user_id=?,decided_at=?,reserved_fingerprint=" +
                if (state in setOf("rejected", "cancelled")) {
                    "NULL,updated_at=?,version=version+1 WHERE id=?"
                } else {
                    "reserved_fingerprint,updated_at=?,version=version+1 WHERE id=?"
                },
            state,
            reason,
            internalNote,
            nextHistory.toString(),
            if (decision) session.userId else null,
            if (decision) session.now else null,
            session.now,
            application.long("id"),
        )
        session.audit(
            "client.application.$state",
            application.long("client_id"),
            application.long("id"),
            application.long("version") + 1,
        )
        return result(row(application.long("id")), platform)
    }

    private fun draftFields(
        body: JsonObject,
        complete: Boolean = false,
    ): LinkedHashMap<String, String> {
        val values =
            linkedMapOf(
                "venueName" to body.text("venueName", VENUE_NAME_LIMIT, complete),
                "countryCode" to body.text("countryCode", 2, complete).uppercase(Locale.ROOT),
                "city" to body.text("city", CITY_LIMIT, complete),
                "address" to body.text("address", ADDRESS_LIMIT, complete),
                "unit" to body.text("unit", CITY_LIMIT),
                "timezone" to body.text("timezone", TIMEZONE_LIMIT, complete),
                "comment" to body.text("comment", COMMENT_LIMIT),
            )
        val country = values.getValue("countryCode")
        if (country.isNotEmpty() && !Regex("[A-Z]{2}").matches(country)) fail("validation_error")
        val timezone = values.getValue("timezone")
        if (timezone.isNotEmpty() && timezone !in ZoneId.getAvailableZoneIds()) fail("validation_error")
        return values
    }

    private fun row(id: Long): DbRow =
        sql.row("SELECT * FROM client_club_applications WHERE id=?", id) ?: fail("not_found")

    private fun locked(
        id: Long,
        platform: Boolean,
    ): DbRow {
        val before = row(id)
        session.client(before.long("client_id"), lock = true)
        val application =
            sql.row("SELECT * FROM client_club_applications WHERE id=? FOR UPDATE", id) ?: fail("not_found")
        authorize(application, platform)
        return application
    }

    private fun authorize(
        application: DbRow,
        platform: Boolean,
    ) {
        if (platform) {
            session.owner()
            if (application["submitted_at"] == null) fail("not_found")
        } else {
            session.member(application.long("client_id"))
            if (application["submitted_at"] == null &&
                application.long("author_user_id") != session.userId
            ) {
                fail("not_found")
            }
        }
    }

    private fun own(application: DbRow) {
        if (application.long("author_user_id") != session.userId) fail("not_found")
    }

    private fun history(
        state: String,
        reason: String?,
    ): JsonArray =
        JsonArray(
            listOf(
                buildJsonObject {
                    put("status", state)
                    put("at", session.now.toString())
                    put("reason", jsonNullable(reason))
                },
            ),
        )

    private fun result(
        application: DbRow,
        platform: Boolean,
    ) = MutationResult(
        "application",
        application.long("id"),
        application.long("client_id"),
        view(application, platform),
    )

    private fun view(
        application: DbRow,
        platform: Boolean,
    ): JsonObject =
        buildJsonObject {
            put("id", application.getValue("id"))
            put("clientId", application.getValue("client_id"))
            put("clientName", session.client(application.long("client_id")).getValue("display_name"))
            put("authorUserId", application.getValue("author_user_id"))
            put("status", application.getValue("status"))
            put("version", application.getValue("version"))
            FIELDS.forEach { put(it, application.getValue(column(it))) }
            put("previousApplicationId", jsonNullable(application["previous_application_id"]))
            put(
                "submittedAt",
                application["submitted_at"]?.let { JsonPrimitive(application.instant("submitted_at").toString()) }
                    ?: JsonNull,
            )
            put("decisionReason", jsonNullable(application["decision_reason"]))
            put("history", onboardingJson.parseToJsonElement(checkNotNull(application["history_json"])))
            put("availableActions", JsonArray(actions(application, platform).map(::JsonPrimitive)))
            if (platform) {
                val author = sql.row("SELECT * FROM users WHERE id=?", application.long("author_user_id"))
                put(
                    "author",
                    author?.let {
                        buildJsonObject {
                            put("userId", it.getValue("id"))
                            put("telegramUserId", it.getValue("telegram_user_id"))
                            put("displayName", jsonNullable(it["display_name"]))
                            put("username", jsonNullable(it["username"]))
                        }
                    } ?: JsonNull,
                )
                put("submittedHash", jsonNullable(application["submitted_hash"]))
                put("submittedVersion", jsonNullable(application["submitted_version"]))
                put("decisionActorUserId", jsonNullable(application["decision_actor_user_id"]))
                put(
                    "decidedAt",
                    application["decided_at"]?.let { JsonPrimitive(application.instant("decided_at").toString()) }
                        ?: JsonNull,
                )
                put("commercialReference", jsonNullable(application["commercial_reference"]))
                put("commercialAgreedAt", jsonNullable(application["commercial_agreed_at"]))
                put("internalNote", jsonNullable(application["internal_note"]))
            }
        }

    private fun actions(
        application: DbRow,
        platform: Boolean,
    ): List<String> =
        if (platform) {
            when (application["status"]) {
                "submitted" -> listOf("review", "cancel")
                "reviewing" -> listOf("approve", "reject", "cancel")
                "approved" -> listOf("cancel")
                else -> emptyList()
            }
        } else if (application.long("author_user_id") != session.userId) {
            emptyList()
        } else {
            when (application["status"]) {
                "draft" -> listOf("update", "submit", "cancel")
                "submitted", "reviewing" -> listOf("cancel")
                else -> emptyList()
            }
        }

    private companion object {
        const val COMMENT_LIMIT = 2000
        const val ADDRESS_LIMIT = 512
        const val VENUE_NAME_LIMIT = 255
        const val CITY_LIMIT = 128
        const val TIMEZONE_LIMIT = 64
        const val DATE_TEXT_LIMIT = 10
        val FIELDS = listOf("venueName", "countryCode", "city", "address", "unit", "timezone", "comment")
        val STATES = setOf("draft", "submitted", "reviewing", "approved", "rejected", "cancelled")
        val PLATFORM_ACTIONS =
            setOf(
                OnboardingMutation.APPLICATION_REVIEW,
                OnboardingMutation.APPLICATION_APPROVE,
                OnboardingMutation.APPLICATION_REJECT,
                OnboardingMutation.PLATFORM_APPLICATION_CANCEL,
            )

        fun column(field: String): String =
            when (field) {
                "venueName" -> "venue_name"
                "countryCode" -> "country_code"
                else -> field
            }

        fun normalize(value: String): String =
            Normalizer
                .normalize(value, Normalizer.Form.NFKC)
                .lowercase(Locale.ROOT)
                .replace(Regex("\\s+"), " ")
                .trim()
    }
}
