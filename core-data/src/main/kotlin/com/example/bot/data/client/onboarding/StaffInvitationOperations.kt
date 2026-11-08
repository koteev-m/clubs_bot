package com.example.bot.data.client.onboarding

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.time.Duration

/** Staff invitations share durable commands and lifecycle storage, never client-owner acceptance. */
internal class StaffInvitationOperations(
    private val session: OnboardingSession,
) {
    private val sql get() = session.sql
    private val access = ClubStaffAccess(session)

    fun issue(
        clientId: Long,
        clubId: Long,
        body: JsonObject,
    ): MutationResult {
        body.only(listOf("action", "expectedVersion", "recipientLabel", "role"))
        val role = body.string("role") ?: fail("validation_error")
        access.packageAllowed(clientId, clubId, role)
        val label = body.text("recipientLabel", LABEL_LIMIT, required = true)
        val token = randomToken()
        val hash = digest(token)
        val id =
            sql.insert(
                "INSERT INTO client_invitations(client_id,recipient_label,token_hash,status,open_marker,expires_at," +
                    "acceptance_policy,purpose,club_id,staff_role,inviter_user_id," +
                    "invitation_scope,created_at,updated_at) " +
                    "VALUES (?,?,?,'issued',1,?,'owner_confirmation','staff',?,?,?,?,?,?)",
                clientId,
                label,
                hash,
                session.now.plus(Duration.ofDays(INVITATION_DAYS)),
                clubId,
                role,
                session.userId,
                hash,
                session.now,
                session.now,
            )
        access.bump(clubId)
        access.audit(
            "client.staff.invitation.issue",
            clientId,
            clubId,
            id,
            1,
            buildJsonObject { put("role", role) },
        )
        val receipt = ClubTeamOperations(session).receipt(id)
        return MutationResult("staff_invitation", id, clientId, JsonObject(receipt + ("token" to JsonPrimitive(token))))
    }

    fun recipient(
        row: DbRow,
        preview: Boolean,
    ): JsonObject {
        activeClient(row.long("client_id"))
        val claimant = row["claimant_user_id"]?.toLong()
        if (claimant != null && claimant != session.userId) fail("not_found")
        if (!preview && claimant == null) fail("not_found")
        if (row["status"] == "accepted") {
            session.requireClubGrant(row.long("club_id"))
            val staff = assignment(row)
            if (!staff.active() || staff.long("invitation_id") != row.long("id")) fail("not_found")
            return JsonObject(
                view(row, owner = false) +
                    buildJsonObject {
                        put("role", staff["role_code"])
                        put(
                            "permissions",
                            strings(
                                access.permissions(
                                    row.long("client_id"),
                                    row.long("club_id"),
                                    checkNotNull(session.userId),
                                ),
                            ),
                        )
                    },
            )
        }
        return view(row, owner = false)
    }

    fun claim(
        row: DbRow,
        body: JsonObject,
    ): MutationResult {
        body.only(listOf("token", "expectedVersion"))
        val token = body.string("token")?.takeIf { Regex("[A-Za-z0-9_-]{43}").matches(it) } ?: fail("not_found")
        if (digest(token) != row["token_hash"]) fail("not_found")
        recipient(row, preview = true)
        if (effective(row) !in setOf("issued", "claimed", "accepted")) fail("not_found")
        val existing =
            sql.row(
                "SELECT * FROM client_club_staff WHERE client_id=? AND club_id=? AND user_id=?",
                row.long("client_id"),
                row.long("club_id"),
                session.userId,
            )
        if (existing != null && row.long("id") <= existing.long("revoked_through_invitation_id")) fail("not_found")
        if (row["status"] != "issued") return result(row)
        session.version(row, body)
        val userId = checkNotNull(session.userId)
        access.protectTarget(row.long("client_id"), row.long("club_id"), userId)
        val code = (CODE_MINIMUM + SecureRandom().nextInt(CODE_RANGE)).toString()
        sql.update(
            "UPDATE client_invitations SET claimant_user_id=?,status='claimed',verification_code=?," +
                "claimed_at=?,updated_at=?,version=version+1 WHERE id=?",
            userId,
            code,
            session.now,
            session.now,
            row.long("id"),
        )
        access.bump(row.long("club_id"))
        access.audit(
            "client.staff.invitation.claim",
            row.long("client_id"),
            row.long("club_id"),
            row.long("id"),
            row.long("version") + 1,
            buildJsonObject { put("candidateUserId", userId.toString()) },
        )
        return result(invitation(row.long("id")))
    }

    fun ownerAction(
        clientId: Long,
        clubId: Long,
        body: JsonObject,
    ): MutationResult {
        body.only(
            listOf(
                "action",
                "expectedVersion",
                "invitationId",
                "expectedInvitationVersion",
                "verificationCode",
                "confirmed",
            ),
        )
        val row = invitation(body.id("invitationId"))
        if (row.long("club_id") != clubId || row.long("client_id") != clientId) fail("not_found")
        if (row.long("version") != body.id("expectedInvitationVersion")) fail("version_conflict")
        if (body.string("action") == "confirm") {
            if (body["confirmed"]?.toString() != "true") fail("validation_error")
            if (effective(row) != "claimed") fail("invalid_transition")
            if (body.string("verificationCode") != row["verification_code"]) fail("validation_error")
            confirm(row)
        } else {
            if (row["status"] !in setOf("issued", "claimed")) fail("invalid_transition")
            sql.update(
                "UPDATE client_invitations SET status='revoked',open_marker=NULL,verification_code=NULL," +
                    "revoked_at=?,updated_at=?,version=version+1 WHERE id=?",
                session.now,
                session.now,
                row.long("id"),
            )
            access.audit(
                "client.staff.invitation.revoke",
                clientId,
                clubId,
                row.long("id"),
                row.long("version") + 1,
                buildJsonObject { put("role", row["staff_role"]) },
            )
        }
        access.bump(clubId)
        return MutationResult("club_team", clubId, clientId, ClubTeamOperations(session).get(clubId))
    }

    private fun confirm(row: DbRow) {
        val clientId = row.long("client_id")
        val clubId = row.long("club_id")
        val userId = row.long("claimant_user_id")
        access.protectTarget(clientId, clubId, userId)
        access.packageAllowed(clientId, clubId, row["staff_role"])
        val member = sql.row("SELECT * FROM client_memberships WHERE client_id=? AND user_id=?", clientId, userId)
        if (member != null && !member.active()) fail("invalid_transition")
        val previous =
            sql.row(
                "SELECT * FROM client_club_staff WHERE client_id=? AND club_id=? AND user_id=?",
                clientId,
                clubId,
                userId,
            )
        if (previous != null &&
            row.long("id") <= previous.long("revoked_through_invitation_id")
        ) {
            fail("invalid_transition")
        }
        if (previous?.active() == true &&
            READ_CLUB in access.permissions(clientId, clubId, userId)
        ) {
            fail("invalid_transition")
        }
        if (member == null) {
            sql.update(
                "INSERT INTO " +
                    "client_memberships(client_id,user_id,is_active,created_at,updated_at) VALUES (?,?,TRUE,?,?)",
                clientId,
                userId,
                session.now,
                session.now,
            )
        }
        val before = access.permissions(clientId, clubId, userId)
        access.replace(clientId, clubId, userId, row["staff_role"])
        if (previous == null) {
            sql.update(
                "INSERT INTO " +
                    "client_club_staff(client_id,club_id,user_id,role_code,is_active," +
                    "invitation_id,created_at,updated_at) " +
                    "VALUES (?,?,?,?,TRUE,?,?,?)",
                clientId,
                clubId,
                userId,
                row["staff_role"],
                row.long("id"),
                session.now,
                session.now,
            )
        } else {
            sql.update(
                "UPDATE client_club_staff SET " +
                    "role_code=?,is_active=TRUE,invitation_id=?,version=version+1,updated_at=? " +
                    "WHERE client_id=? AND club_id=? AND user_id=?",
                row["staff_role"],
                row.long("id"),
                session.now,
                clientId,
                clubId,
                userId,
            )
        }
        sql.update(
            "UPDATE client_invitations SET status='accepted',open_marker=NULL,verification_code=NULL," +
                "accepted_at=?,updated_at=?,version=version+1 WHERE id=?",
            session.now,
            session.now,
            row.long("id"),
        )
        access.audit(
            "client.staff.invitation.confirm",
            clientId,
            clubId,
            row.long("id"),
            row.long("version") + 1,
            buildJsonObject {
                put("candidateUserId", userId.toString())
                put("role", row["staff_role"])
                put("before", strings(before))
                put("after", strings(access.permissions(clientId, clubId, userId)))
            },
        )
    }

    fun view(
        row: DbRow,
        owner: Boolean,
    ): JsonObject {
        val state = effective(row)
        val candidate = row["claimant_user_id"]?.toLong()?.let { sql.row("SELECT * FROM users WHERE id=?", it) }
        return buildJsonObject {
            put("id", row["id"])
            put("clientId", row["client_id"])
            put("clubId", row["club_id"])
            put("clientName", session.client(row.long("client_id"))["display_name"])
            put("clubName", sql.row("SELECT name FROM clubs WHERE id=?", row.long("club_id"))?.get("name"))
            put("recipientLabel", if (owner) row["recipient_label"] else "")
            put("acceptanceMode", "staff")
            put("role", row["staff_role"])
            put("permissions", strings(staffPackage(row["staff_role"])))
            put("status", state)
            put("version", row["version"])
            put("expiresAt", row.instant("expires_at").toString())
            put(
                "candidate",
                candidate?.let {
                    buildJsonObject {
                        put("userId", it["id"])
                        put("telegramUserId", it["telegram_user_id"])
                        put("displayName", jsonNullable(it["display_name"]))
                        put("username", jsonNullable(it["username"]))
                    }
                } ?: JsonNull,
            )
            put("verificationCode", if (state == "claimed") jsonNullable(row["verification_code"]) else JsonNull)
            put(
                "availableActions",
                strings(
                    when {
                        owner && state == "claimed" -> listOf("confirm", "revoke_invitation")
                        owner && row["status"] in setOf("issued", "claimed") -> listOf("revoke_invitation")
                        !owner && state == "issued" -> listOf("claim")
                        else -> emptyList()
                    },
                ),
            )
        }
    }

    private fun result(row: DbRow) =
        MutationResult("invitation", row.long("id"), row.long("client_id"), recipient(row, preview = false))

    private fun invitation(id: Long): DbRow =
        sql.row("SELECT * FROM client_invitations WHERE id=? AND purpose='staff'", id) ?: fail("not_found")

    private fun assignment(row: DbRow): DbRow =
        sql.row(
            "SELECT * FROM client_club_staff WHERE client_id=? AND club_id=? AND user_id=?",
            row.long("client_id"),
            row.long("club_id"),
            session.userId,
        ) ?: fail("not_found")

    private fun activeClient(id: Long) {
        val client = session.client(id, lock = true)
        if (!client.active() || client["archived_at"] != null) fail("not_found")
    }

    private fun effective(row: DbRow): String =
        if (row["status"] in setOf("issued", "claimed") && !session.now.isBefore(row.instant("expires_at"))) {
            "expired"
        } else {
            checkNotNull(row["status"])
        }

    private companion object {
        const val LABEL_LIMIT = 255
        const val INVITATION_DAYS = 7L
        const val CODE_MINIMUM = 100000
        const val CODE_RANGE = 900000
    }
}
