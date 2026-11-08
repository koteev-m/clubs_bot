package com.example.bot.data.client.onboarding

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** All reads and mutations share the client lock with platform revocation. */
internal class ClubTeamOperations(
    private val session: OnboardingSession,
) {
    private val sql get() = session.sql
    private val access = ClubStaffAccess(session)

    fun get(clubId: Long): JsonObject {
        val clientId = access.owner(clubId)
        val club = sql.row("SELECT name,team_version FROM clubs WHERE id=?", clubId) ?: fail("not_found")
        return buildJsonObject {
            put("clubId", clubId.toString())
            put("clientId", clientId.toString())
            put("name", club["name"])
            put("version", club["team_version"])
            put("canWrite", true)
            put(
                "roles",
                JsonArray(
                    staffPackages.map { (role, permissions) ->
                        buildJsonObject {
                            put("code", role.name)
                            put("permissions", strings(permissions))
                            put("assignable", permissions.all { session.hasClubGrant(clientId, clubId, it) })
                        }
                    },
                ),
            )
            put("members", members(clientId, clubId))
            put(
                "invitations",
                JsonArray(
                    sql
                        .rows(
                            "SELECT * FROM client_invitations WHERE client_id=? AND club_id=? AND " +
                                "purpose='staff' ORDER BY id DESC",
                            clientId,
                            clubId,
                        ).map { StaffInvitationOperations(session).view(it, owner = true) },
                ),
            )
        }
    }

    fun update(
        clubId: Long,
        body: JsonObject,
    ): MutationResult {
        val clientId = access.owner(clubId)
        val club = sql.row("SELECT team_version AS version FROM clubs WHERE id=?", clubId) ?: fail("not_found")
        session.version(club, body)
        return when (body.string("action")) {
            "invite" -> StaffInvitationOperations(session).issue(clientId, clubId, body)
            "confirm", "revoke_invitation" -> StaffInvitationOperations(session).ownerAction(clientId, clubId, body)
            "change_role", "disable" -> change(clientId, clubId, body)
            else -> fail("validation_error")
        }
    }

    fun receipt(invitationId: Long): JsonObject {
        val row =
            sql.row("SELECT * FROM client_invitations WHERE id=? AND purpose='staff'", invitationId)
                ?: fail("not_found")
        return JsonObject(
            get(row.long("club_id")) + buildJsonObject { put("createdInvitationId", invitationId.toString()) },
        )
    }

    private fun change(
        clientId: Long,
        clubId: Long,
        body: JsonObject,
    ): MutationResult {
        body.only(listOf("action", "expectedVersion", "userId", "expectedMemberVersion", "role", "reason", "confirmed"))
        if (body["confirmed"]?.toString() != "true") fail("validation_error")
        val reason = body.text("reason", REASON_LIMIT, required = true)
        val userId = body.id("userId")
        access.protectTarget(clientId, clubId, userId)
        val staff =
            sql.row(
                "SELECT * FROM client_club_staff WHERE client_id=? AND club_id=? AND user_id=?",
                clientId,
                clubId,
                userId,
            ) ?: fail("not_found")
        if (staff.long("version") != body.id("expectedMemberVersion")) fail("version_conflict")
        if (!staff.active()) fail("invalid_transition")
        val disabling = body.string("action") == "disable"
        val role = if (disabling) null else body.string("role") ?: fail("validation_error")
        if (!disabling) {
            session.member(clientId, userId, manage = false)
            access.packageAllowed(clientId, clubId, role)
            if (READ_CLUB !in access.permissions(clientId, clubId, userId)) fail("invalid_transition")
        }
        val before = access.permissions(clientId, clubId, userId)
        access.replace(clientId, clubId, userId, role)
        sql.update(
            "UPDATE client_club_staff SET " +
                "role_code=?,is_active=?,revoked_through_invitation_id=?,version=version+1,updated_at=? " +
                "WHERE client_id=? AND club_id=? AND user_id=?",
            role ?: staff["role_code"],
            !disabling,
            if (disabling) access.invitationFence(clubId) else staff.long("revoked_through_invitation_id"),
            session.now,
            clientId,
            clubId,
            userId,
        )
        access.bump(clubId)
        access.audit(
            if (disabling) "client.staff.disable" else "client.staff.role.change",
            clientId,
            clubId,
            userId,
            staff.long("version") + 1,
            buildJsonObject {
                put("userId", userId.toString())
                put("reason", reason)
                put("previousRole", staff["role_code"])
                put("role", role)
                put("before", strings(before))
                put("after", strings(access.permissions(clientId, clubId, userId)))
            },
        )
        return MutationResult("club_team", clubId, clientId, get(clubId))
    }

    private fun members(
        clientId: Long,
        clubId: Long,
    ): JsonArray {
        val rows =
            sql.rows(
                "SELECT u.id,u.display_name,u.username,m.is_active AS " +
                    "member_active,s.role_code,s.is_active AS staff_active," +
                    "s.version FROM client_memberships m JOIN users u ON u.id=m.user_id " +
                    "LEFT JOIN client_club_staff s ON s.client_id=m.client_id AND " +
                    "s.user_id=m.user_id AND s.club_id=? " +
                    "WHERE m.client_id=? AND (s.user_id IS NOT NULL OR EXISTS (SELECT 1 FROM client_club_grants g " +
                    "WHERE g.client_id=m.client_id AND g.user_id=m.user_id AND g.club_id=?)) ORDER BY u.id",
                clubId,
                clientId,
                clubId,
            )
        return JsonArray(
            rows.map { row ->
                val userId = row.long("id")
                val protected =
                    userId == access.creator(clubId) ||
                        sql.row(
                            "SELECT user_id FROM client_membership_grants WHERE client_id=? AND user_id=? " +
                                "AND permission_code=?",
                            clientId,
                            userId,
                            MANAGE,
                        ) != null
                val granted = access.permissions(clientId, clubId, userId)
                val active =
                    row.active("member_active") &&
                        READ_CLUB in granted &&
                        (row["role_code"] == null || row.active("staff_active"))
                buildJsonObject {
                    put("userId", userId.toString())
                    put("displayName", jsonNullable(row["display_name"]))
                    put("username", jsonNullable(row["username"]))
                    put("role", jsonNullable(row["role_code"]))
                    put("owner", protected)
                    put("version", jsonNullable(row["version"]))
                    put("active", active)
                    put("membershipActive", row.active("member_active"))
                    put("permissions", strings(if (active) granted else emptyList()))
                    put("canChange", !protected && row.active("staff_active") && active)
                    put("canDisable", !protected && row.active("staff_active"))
                }
            },
        )
    }

    private companion object {
        const val REASON_LIMIT = 500
    }
}
