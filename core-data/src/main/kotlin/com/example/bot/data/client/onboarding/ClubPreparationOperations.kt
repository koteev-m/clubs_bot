package com.example.bot.data.client.onboarding

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A club task, never an authorization store. Every check shares the client revocation lock. */
internal class ClubPreparationOperations(
    private val session: OnboardingSession,
) {
    private val sql get() = session.sql
    private val access = ClubStaffAccess(session)

    fun get(clubId: Long): JsonObject {
        val clientId = session.requireClubGrant(clubId)
        val club = sql.row("SELECT name,is_active FROM clubs WHERE id=?", clubId) ?: fail("not_found")
        val row = state(clubId)
        val manage = manages(clientId, clubId)
        val submit = submits(clientId, clubId, row)
        val invitation =
            row?.get("invitation_id")?.toLong()?.let {
                sql.row("SELECT * FROM client_invitations WHERE id=? AND purpose='staff'", it)
            }
        return buildJsonObject {
            put("clubId", clubId.toString())
            put("clientId", clientId.toString())
            put("name", club["name"])
            put("published", club.active())
            put("version", row?.get("version") ?: "1")
            put("status", row?.get("status") ?: "unassigned")
            put("reason", jsonNullable(row?.get("reason")))
            put("configurationChanged", changed(clubId, row))
            put("canManage", manage)
            put("canSubmit", submit)
            put("canWrite", manage || submit)
            put("canInvite", manage && staffPackage("CLUB_ADMIN").all { session.hasClubGrant(clientId, clubId, it) })
            put("assignee", assignee(clientId, clubId, row))
            put(
                "invitation",
                invitation?.let {
                    buildJsonObject {
                        put("id", it["id"])
                        put("status", invitationStatus(it))
                        put("recipientLabel", it["recipient_label"])
                    }
                } ?: JsonNull,
            )
        }
    }

    fun update(
        clubId: Long,
        body: JsonObject,
    ): MutationResult {
        val clientId = session.requireClubGrant(clubId)
        val action = body.string("action") ?: fail("validation_error")
        if (action != "submit") access.owner(clubId)
        initialize(clientId, clubId)
        val row = checkNotNull(state(clubId))
        session.version(row, body)
        if (action == "submit" && !submits(clientId, clubId, row)) fail("not_found")
        val next = transition(clientId, clubId, row, action, body)
        save(clubId, row, next, action)
        val response = get(clubId)
        return MutationResult(
            if (action == "submit") {
                "prep_submit"
            } else if (action == "invite") {
                "prep_invite"
            } else {
                "club_preparation"
            },
            clubId,
            clientId,
            JsonObject(response + next.extra),
        )
    }

    fun receipt(
        clubId: Long,
        submission: Boolean,
    ): JsonObject {
        val clientId = session.requireClubGrant(clubId)
        if (submission) {
            if (!assignedActive(clientId, clubId, state(clubId)) ||
                state(clubId)?.get("assignee_user_id")?.toLong() != session.userId
            ) {
                fail("not_found")
            }
        } else {
            access.owner(clubId)
        }
        return get(clubId)
    }

    /** Called only after the unchanged staff confirmation has issued the entire package in this transaction. */
    fun confirmed(invitation: DbRow) {
        val clubId = invitation.long("club_id")
        val row = state(clubId) ?: return
        if (row["status"] != "invitation_pending" || row["invitation_id"] != invitation["id"]) return
        save(
            clubId,
            row,
            Next("in_progress", invitation.long("claimant_user_id"), invitation.long("id")),
            "invitation_confirmed",
        )
    }

    private fun transition(
        clientId: Long,
        clubId: Long,
        row: DbRow,
        action: String,
        body: JsonObject,
    ): Next =
        when (action) {
            "self" -> {
                body.only(COMMAND_FIELDS)
                session.requireClubGrant(clubId, WRITE_PROFILE)
                Next("in_progress", session.userId)
            }
            "later", "unassign" -> {
                body.only(COMMAND_FIELDS)
                Next("unassigned")
            }
            "assign" -> {
                body.only(COMMAND_FIELDS + "userId")
                val userId = body.id("userId")
                access.protectTarget(clientId, clubId, userId)
                session.member(clientId, userId, manage = false)
                val staff = staff(clientId, clubId, userId) ?: fail("not_found")
                if (!staff.active() ||
                    staff["role_code"] != "CLUB_ADMIN" ||
                    !staffPackage("CLUB_ADMIN").all { it in access.permissions(clientId, clubId, userId) }
                ) {
                    fail("not_found")
                }
                Next("in_progress", userId, staff.long("invitation_id"))
            }
            "invite" -> invite(clientId, clubId, body)
            "submit" -> {
                body.only(COMMAND_FIELDS)
                Next(
                    "submitted",
                    row.long("assignee_user_id"),
                    row["invitation_id"]?.toLong(),
                    hash = configuration(clubId),
                )
            }
            "accept" -> {
                body.only(COMMAND_FIELDS)
                if (row["status"] != "submitted" || !assignedActive(clientId, clubId, row)) fail("invalid_transition")
                if (changed(clubId, row)) fail("version_conflict")
                Next(
                    "accepted",
                    row.long("assignee_user_id"),
                    row["invitation_id"]?.toLong(),
                    hash = row["configuration_hash"],
                )
            }
            "return" -> {
                body.only(COMMAND_FIELDS + "reason")
                if (row["status"] != "submitted") fail("invalid_transition")
                Next(
                    "returned",
                    row.long("assignee_user_id"),
                    row["invitation_id"]?.toLong(),
                    body.text("reason", REASON_LIMIT, required = true),
                )
            }
            else -> fail("validation_error")
        }

    private fun invite(
        clientId: Long,
        clubId: Long,
        body: JsonObject,
    ): Next {
        body.only(COMMAND_FIELDS + listOf("expectedTeamVersion", "recipientLabel"))
        val team = sql.row("SELECT team_version AS version FROM clubs WHERE id=?", clubId) ?: fail("not_found")
        if (team.long("version") != body.id("expectedTeamVersion")) fail("version_conflict")
        val issued =
            StaffInvitationOperations(session)
                .issue(
                    clientId,
                    clubId,
                    buildJsonObject {
                        put("action", "invite")
                        put("expectedVersion", body.getValue("expectedTeamVersion"))
                        put("recipientLabel", body.text("recipientLabel", RECIPIENT_LIMIT, required = true))
                        put("role", "CLUB_ADMIN")
                    },
                ).response
        return Next(
            "invitation_pending",
            invitationId = issued.id("createdInvitationId"),
            extra =
                buildJsonObject {
                    put("createdInvitationId", issued.getValue("createdInvitationId"))
                    put("token", issued.getValue("token"))
                },
        )
    }

    private fun save(
        clubId: Long,
        row: DbRow,
        next: Next,
        action: String,
    ) {
        val changed =
            sql.update(
                "UPDATE client_club_preparation SET status=?,assignee_user_id=?,invitation_id=?,reason=?," +
                    "configuration_hash=?,version=version+1,updated_at=? WHERE club_id=? AND version=?",
                next.status,
                next.assigneeId,
                next.invitationId,
                next.reason,
                next.hash,
                session.now,
                clubId,
                row.long("version"),
            )
        if (changed != 1) fail("version_conflict")
        access.audit(
            "client.preparation.$action",
            row.long("client_id"),
            clubId,
            clubId,
            row.long("version") + 1,
            buildJsonObject {
                put("status", next.status)
                put("assigneeUserId", next.assigneeId?.toString())
                put("invitationId", next.invitationId?.toString())
                put("reason", next.reason)
            },
        )
    }

    private fun initialize(
        clientId: Long,
        clubId: Long,
    ) {
        if (state(clubId) == null) {
            sql.update(
                "INSERT INTO client_club_preparation(client_id,club_id,updated_at) VALUES (?,?,?)",
                clientId,
                clubId,
                session.now,
            )
        }
    }

    private fun state(clubId: Long): DbRow? = sql.row("SELECT * FROM client_club_preparation WHERE club_id=?", clubId)

    private fun manages(
        clientId: Long,
        clubId: Long,
    ): Boolean = access.owns(clubId) && session.hasClubGrant(clientId, clubId, MANAGE_TEAM)

    private fun submits(
        clientId: Long,
        clubId: Long,
        row: DbRow?,
    ): Boolean =
        row?.get("assignee_user_id")?.toLong() == session.userId &&
            assignedActive(clientId, clubId, row) &&
            (
                row?.get("status") in listOf("in_progress", "returned") ||
                    (row?.get("status") in listOf("submitted", "accepted") && changed(clubId, row))
            )

    private fun assignedActive(
        clientId: Long,
        clubId: Long,
        row: DbRow?,
    ): Boolean {
        val userId = row?.get("assignee_user_id")?.toLong() ?: return false
        val member =
            sql.row(
                "SELECT is_active FROM client_memberships WHERE client_id=? AND user_id=?",
                clientId,
                userId,
            )
        val granted = access.permissions(clientId, clubId, userId)
        val staff = staff(clientId, clubId, userId)
        return member?.active() == true &&
            if (userId == access.creator(clubId)) {
                READ_CLUB in granted && WRITE_PROFILE in granted && MANAGE_TEAM in granted
            } else {
                staff?.active() == true &&
                    staff["role_code"] == "CLUB_ADMIN" &&
                    staff["invitation_id"] == row["invitation_id"] &&
                    staffPackage("CLUB_ADMIN").all { it in granted }
            }
    }

    private fun staff(
        clientId: Long,
        clubId: Long,
        userId: Long,
    ): DbRow? =
        sql.row(
            "SELECT * FROM client_club_staff WHERE client_id=? AND club_id=? AND user_id=?",
            clientId,
            clubId,
            userId,
        )

    private fun assignee(
        clientId: Long,
        clubId: Long,
        row: DbRow?,
    ) = row?.get("assignee_user_id")?.toLong()?.let { userId ->
        val user = sql.row("SELECT display_name,username FROM users WHERE id=?", userId) ?: fail("not_found")
        buildJsonObject {
            put("userId", userId.toString())
            put("displayName", jsonNullable(user["display_name"]))
            put("username", jsonNullable(user["username"]))
            put("owner", userId == access.creator(clubId))
            put("active", assignedActive(clientId, clubId, row))
        }
    } ?: JsonNull

    private fun invitationStatus(row: DbRow): String =
        if (row["status"] in listOf("issued", "claimed") && !session.now.isBefore(row.instant("expires_at"))) {
            "expired"
        } else {
            checkNotNull(row["status"])
        }

    private fun changed(
        clubId: Long,
        row: DbRow?,
    ) = row?.get("configuration_hash")?.let { it != configuration(clubId) } ?: false

    private fun configuration(clubId: Long): String {
        val parts =
            listOf(
                sql.rows("SELECT name,description,timezone,calendar_version FROM clubs WHERE id=?", clubId),
                sql.rows("SELECT profile_version FROM client_direct_club_profiles WHERE club_id=?", clubId),
                sql.rows("SELECT profile_version FROM client_club_onboarding WHERE club_id=?", clubId),
                sql.rows("SELECT id,layout_revision FROM halls WHERE club_id=? ORDER BY id", clubId),
                sql.rows("SELECT version FROM client_club_booking_rules WHERE club_id=?", clubId),
                sql.rows("SELECT version FROM club_report_templates WHERE club_id=?", clubId),
                sql.rows("SELECT config_version FROM club_gamification_settings WHERE club_id=?", clubId),
            )
        return digest(parts.toString())
    }

    private data class Next(
        val status: String,
        val assigneeId: Long? = null,
        val invitationId: Long? = null,
        val reason: String? = null,
        val hash: String? = null,
        val extra: JsonObject = JsonObject(emptyMap()),
    )

    private companion object {
        val COMMAND_FIELDS = listOf("action", "expectedVersion")
        const val REASON_LIMIT = 500
        const val RECIPIENT_LIMIT = 255
    }
}
