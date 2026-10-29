package com.example.bot.data.client.onboarding

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** The client row lock is shared by every creation and limit update. Counts include private clubs. */
internal class ClientLimitOperations(
    private val session: OnboardingSession,
) {
    fun view(clientId: Long): JsonObject {
        val client = session.client(clientId)
        val allowed = client["allowed_clubs"]?.toInt()
        val created = used(clientId)
        return buildJsonObject {
            put("allowedClubs", allowed?.let(::JsonPrimitive) ?: JsonNull)
            put("createdClubs", created)
            put("availableClubs", allowed?.let { (it - created).coerceAtLeast(0) } ?: 0)
            put("limitVersion", client.getValue("limit_version"))
        }
    }

    fun requireSpace(clientId: Long) {
        val client = session.client(clientId, lock = true)
        val allowed = client["allowed_clubs"]?.toInt() ?: fail("club_limit_unset")
        if (used(clientId) >= allowed) fail("club_limit_reached")
    }

    fun update(
        clientId: Long,
        body: JsonObject,
    ): MutationResult {
        session.owner()
        body.only(listOf("allowedClubs", "expectedVersion"))
        val client = session.client(clientId, lock = true)
        if (body.id("expectedVersion") != client.long("limit_version")) fail("version_conflict")
        val allowed = parseAllowedClubs(body)
        if (allowed < used(clientId)) fail("club_limit_below_usage")
        session.sql.update(
            "UPDATE clients SET allowed_clubs=?,limit_version=limit_version+1,updated_at=? WHERE id=?",
            allowed,
            session.now,
            clientId,
        )
        session.sql.audit(
            checkNotNull(session.userId),
            "client.limit.update",
            clientId,
            clientId,
            client.long("limit_version") + 1,
            session.now,
            session.correlation,
            buildJsonObject {
                put("previousAllowedClubs", client["allowed_clubs"]?.toInt()?.let(::JsonPrimitive) ?: JsonNull)
                put("allowedClubs", allowed)
            },
        )
        return MutationResult("client_limit", clientId, clientId, ClientAccessOperations(session).get(clientId))
    }

    private fun used(clientId: Long): Int =
        checkNotNull(session.sql.row("SELECT COUNT(*) AS total FROM client_clubs WHERE client_id=?", clientId))
            .long("total")
            .toInt()
}

internal fun parseAllowedClubs(body: JsonObject): Int =
    (body["allowedClubs"] as? JsonPrimitive)
        ?.takeUnless(JsonPrimitive::isString)
        ?.intOrNull
        ?.takeIf { it >= 0 } ?: fail("validation_error")
