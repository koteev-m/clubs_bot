package com.example.bot.data.client.onboarding

import com.example.bot.data.client.assignments.activeManagerSourcesSql
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val READ_OPERATIONAL_REPORTS = "client.club.operations.analytics.read"
internal const val READ_FINANCIAL_REPORTS = "client.club.operations.financial_reports.read"

internal class ClubReportAccess(
    private val session: OnboardingSession,
    private val clubId: Long,
) {
    private val sql get() = session.sql
    private val sources
        get() = "$activeManagerSourcesSql AND a.client_id=? AND a.club_id=? AND a.role_code='CLUB_MANAGER'"

    fun list(): JsonObject {
        val client = owner()
        return buildJsonObject {
            put("clubId", clubId.toString())
            put("clientId", client.toString())
            put(
                "assignments",
                JsonArray(
                    sql.rows(sources + " ORDER BY a.id", session.now, client, clubId).map { source ->
                        val id = java.util.UUID.fromString(source["id"])
                        val user = sql.row("SELECT display_name,username FROM users WHERE id=?", source.long("user_id"))
                        val grant =
                            sql.row(
                                "SELECT active,version FROM client_club_financial_report_access " +
                                    "WHERE client_id=? AND club_id=? AND assignment_id=?",
                                client,
                                clubId,
                                id,
                            )
                        buildJsonObject {
                            put("id", id.toString())
                            put("name", user?.get("display_name") ?: user?.get("username") ?: "Сотрудник")
                            put("active", grant?.active("active") == true)
                            put("version", grant?.get("version") ?: "1")
                        }
                    },
                ),
            )
        }
    }

    fun change(body: JsonObject): MutationResult {
        val client = owner()
        body.only(listOf("assignmentId", "active", "expectedVersion"))
        val id = body.optionalUuid("assignmentId") ?: fail("validation_error")
        if (sql.row(sources + " AND a.id=?", session.now, client, clubId, id) == null) fail("not_found")
        val active = body.boolean("active")
        val old =
            sql.row(
                "SELECT active,version FROM client_club_financial_report_access WHERE client_id=? " +
                    "AND club_id=? AND assignment_id=?",
                client,
                clubId,
                id,
            )
        val version = old?.long("version") ?: 1L
        if (body.id("expectedVersion") != version) fail("version_conflict")
        if (old == null) {
            sql.update(
                "INSERT INTO client_club_financial_report_access(client_id,club_id,assignment_id,granted_by," +
                    "updated_at,active,version) VALUES (?,?,?,?,?,?,?)",
                client,
                clubId,
                id,
                session.userId,
                session.now,
                active,
                version + 1,
            )
        } else {
            sql.update(
                "UPDATE client_club_financial_report_access SET " +
                    "active=?,updated_at=?,granted_by=?,version=? WHERE client_id=? AND club_id=? AND assignment_id=?",
                active,
                session.now,
                session.userId,
                version + 1,
                client,
                clubId,
                id,
            )
        }
        sql.audit(
            checkNotNull(session.userId),
            "client.club.financial_reports." + if (active) "grant" else "revoke",
            client,
            clubId,
            version + 1,
            session.now,
            session.correlation,
            buildJsonObject { put("assignmentId", id.toString()) },
        )
        return MutationResult("club_report_grant", clubId, client, list())
    }

    fun granted(client: Long): Boolean =
        sql.row(
            "SELECT 1 AS present FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA=CURRENT_SCHEMA " +
                "AND LOWER(TABLE_NAME)='client_club_financial_report_access'",
        ) != null &&
            sql.row(
                "SELECT 1 AS present FROM ($sources AND a.user_id=?) s " +
                    "JOIN client_club_financial_report_access g ON g.assignment_id=s.id AND " +
                    "g.client_id=s.client_id AND g.club_id=s.club_id " +
                    "WHERE g.active=TRUE AND EXISTS (SELECT 1 FROM permissions WHERE code=?) LIMIT 1",
                session.now,
                client,
                clubId,
                session.userId,
                READ_FINANCIAL_REPORTS,
            ) != null

    private fun owner(): Long {
        val client = session.requireClubGrant(clubId)
        if (session.businessOwnerRef(client, clubId) == null) fail("not_found")
        return client
    }

    private fun JsonObject.boolean(key: String): Boolean =
        when (this[key]) {
            JsonPrimitive(true) -> true
            JsonPrimitive(false) -> false
            else -> fail("validation_error")
        }
}
