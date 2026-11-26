package com.example.bot.data.client.onboarding

import com.example.bot.data.client.assignments.StaffRole
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Each selected club keeps its own V081 admission, source and revocation fence. Only the root has a usable link. */
internal class ManagerInvitationScopes(
    private val session: OnboardingSession,
) {
    private val sql get() = session.sql

    fun choices(
        client: Long,
        club: Long,
        owner: Boolean,
    ): JsonArray {
        val clubs =
            if (owner) {
                sql.rows(
                    "SELECT c.id,c.name FROM client_business_clubs b JOIN clubs c ON c.id=b.club_id " +
                        "WHERE b.client_id=? AND b.user_id=? ORDER BY c.id",
                    client,
                    session.userId,
                )
            } else {
                sql.rows("SELECT id,name FROM clubs WHERE id=?", club)
            }
        return JsonArray(clubs.map { item(it["id"], it["name"]) })
    }

    fun selected(
        body: JsonObject,
        club: Long,
        role: StaffRole,
    ): List<Long> {
        val raw = body["clubIds"] ?: return listOf(club)
        val ids =
            (raw as? JsonArray ?: fail("validation_error")).map {
                if (it !is JsonPrimitive || !it.isString) fail("validation_error")
                it.content.toLongOrNull()?.takeIf { id -> id > 0 } ?: fail("validation_error")
            }
        if (club !in ids || ids.distinct().size != ids.size) fail("validation_error")
        if (ids.size > 1 && role != StaffRole.NETWORK_MANAGER) fail("forbidden")
        return listOf(club) + (ids - club).sorted()
    }

    fun rows(row: DbRow): List<DbRow> {
        if (row["acceptance_policy"] != "recipient_acceptance") return listOf(row)
        val root = row["parent_invitation_id"]?.toLong() ?: row.long("id")
        return sql.rows(
            "${sql.managerInvitationSelect} WHERE client_id=? " +
                "AND (id=? OR parent_invitation_id=?) ORDER BY id",
            row.long("client_id"),
            root,
            root,
        )
    }

    fun names(
        row: DbRow,
        visible: (DbRow) -> Boolean,
    ): JsonArray =
        JsonArray(
            rows(row).filter(visible).map {
                val club = sql.row("SELECT name FROM clubs WHERE id=?", it.long("club_id")) ?: fail("not_found")
                item(it["club_id"], club["name"])
            },
        )

    private fun item(
        id: String?,
        name: String?,
    ) = buildJsonObject {
        put("id", id)
        put("name", name)
    }
}
