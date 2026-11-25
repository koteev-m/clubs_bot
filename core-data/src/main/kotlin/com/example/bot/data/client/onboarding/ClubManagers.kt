package com.example.bot.data.client.onboarding

import com.example.bot.data.client.assignments.StaffRole
import com.example.bot.data.client.assignments.activeManagerSourcesSql
import com.example.bot.data.client.assignments.managerPolicy
import com.example.bot.data.client.assignments.networkManagerSourceRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant

internal const val MANAGER_AUTH_SECONDS = 300L

internal fun OnboardingActor.requireFreshManagerIdentity(now: Instant) {
    val started = managerAuthenticatedAt ?: fail("unauthorized")
    if (connectionKey != "platform" ||
        started > now ||
        !now.isBefore(started.plusSeconds(MANAGER_AUTH_SECONDS))
    ) {
        fail("unauthorized")
    }
}

/** Canonical Telegram only. Issue, confirmation and scope revocation share the onboarding client lock. */
internal class ClubManagers(
    private val session: OnboardingSession,
) {
    private val sql get() = session.sql
    private val actor get() = session.userId ?: fail("not_found")
    private val sources = ManagerAssignmentWriter(session)
    private val legacy = LegacyStaffAssignments(session)

    private fun context(club: Long): Long {
        if (!sql.managerSchemaPresent) fail("not_found")
        val client =
            sql.row("SELECT client_id FROM client_clubs WHERE club_id=?", club)?.long("client_id") ?: fail("not_found")
        val row = session.client(client, lock = true)
        session.actor.requireFreshManagerIdentity(session.now)
        sql.usedTargetIdentity = true
        if (!row.active() ||
            row["archived_at"] != null ||
            !session.businessClub(client, club)
        ) {
            fail("not_found")
        }
        return client
    }

    private fun authority(
        client: Long,
        club: Long,
    ): Pair<String, Set<StaffRole>> {
        if (session.actor.telegramUserId == session.ownerId) fail("not_found")
        session.member(client, manage = false)
        val owner = session.businessOwnerRef(client, club)
        if (owner != null) return owner to setOf(StaffRole.CLUB_MANAGER, StaffRole.NETWORK_MANAGER)
        val network = session.networkManagerSourceRef(client, club) ?: fail("not_found")
        return network to setOf(StaffRole.CLUB_MANAGER)
    }

    fun roster(club: Long): JsonObject {
        val client = context(club)
        val (issuer, roles) = authority(client, club)
        val current = clubRow(club)
        val allowed = roles.map { it.name }
        return buildJsonObject {
            put("clubId", club.toString())
            put("clientId", client.toString())
            put("name", current["name"])
            put("version", current["version"])
            put("canWrite", true)
            put("roles", strings(allowed))
            val invitations =
                sql.rows(
                    "SELECT * FROM client_manager_invitations WHERE client_id=? AND club_id=? ORDER BY id DESC",
                    client,
                    club,
                )
            put(
                "invitations",
                JsonArray(
                    invitations.filter { it["role_code"] in allowed && it["status"] in PENDING }.map {
                        view(it, !isOriginalIssuer(it, issuer), issuerView = true)
                    },
                ),
            )
            put(
                "legacyAssignments",
                if (session.businessOwnerRef(client, club) !=
                    null
                ) {
                    legacy.list(client, club)
                } else {
                    JsonArray(emptyList())
                },
            )
            put(
                "assignments",
                JsonArray(
                    sources.list(client, club).filter { it["role_code"] in allowed }.map { row ->
                        buildJsonObject {
                            put("id", row["id"])
                            put("canRevoke", sources.canRevoke(client, row))
                            put("role", row["role_code"])
                            put("userId", row["user_id"])
                            put("displayName", row["display_name"] ?: row["username"] ?: row["user_id"])
                            put("version", row["version"])
                        }
                    },
                ),
            )
        }
    }

    fun update(
        club: Long,
        body: JsonObject,
    ): MutationResult {
        val client = context(club)
        val (issuer, roles) = authority(client, club)
        session.version(clubRow(club), body)
        when (body.string("action")) {
            "invite" -> return issue(client, club, body, issuer, roles)
            "revoke_assignment" -> sources.revoke(client, club, body, roles)
            "revoke_legacy_assignment" -> {
                if (session.businessOwnerRef(client, club) == null) fail("forbidden")
                legacy.revoke(client, club, body)
            }
            "revoke_invitation" -> {
                body.only(listOf("action", "expectedVersion", "invitationId", "expectedInvitationVersion", "confirmed"))
                val row = forAction(client, club, body, issuer, roles)
                if (row["status"] !in PENDING) fail("invalid_transition")
                sql.update(
                    "UPDATE client_manager_invitations SET status='revoked',verification_code=NULL," +
                        "version=version+1 WHERE id=?",
                    row.long("id"),
                )
                session.audit("client.manager.invitation.revoke", client, row.long("id"), row.long("version") + 1)
            }
            else -> fail("validation_error")
        }
        bump(club)
        return MutationResult("manager_roster", club, client, roster(club))
    }

    private fun issue(
        client: Long,
        club: Long,
        body: JsonObject,
        issuer: String,
        roles: Set<StaffRole>,
    ): MutationResult {
        body.only(listOf("action", "expectedVersion", "recipientLabel", "role"))
        val role = roles.singleOrNull { it.name == body.string("role") } ?: fail("forbidden")
        val policy = managerPolicy(role)
        val label = body.text("recipientLabel", MAX_LABEL, required = true)
        val token = randomToken()
        val id =
            sql.insert(
                "INSERT INTO client_manager_invitations(client_id,club_id,role_code,policy_version," +
                    "policy_hash,inviter_user_id,issuer_ref," +
                    "recipient_label,token_hash,created_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                client,
                club,
                role.name,
                policy.version,
                policy.reference.hash,
                actor,
                issuer,
                label,
                digest(token),
                session.now,
                session.now.plus(Duration.ofDays(INVITATION_DAYS)),
            )
        bump(club)
        session.audit("client.manager.invitation.issue", client, id, 1)
        return MutationResult(
            "manager_issue",
            id,
            client,
            JsonObject(
                issueReceipt(id) + ("token" to JsonPrimitive(token)),
            ),
        )
    }

    fun issueReceipt(id: Long): JsonObject {
        val row = invitation(id)
        val current = roster(row.long("club_id"))
        val (issuer, roles) = authority(row.long("client_id"), row.long("club_id"))
        if (!isOriginalIssuer(row, issuer) || roles.none { it.name == row["role_code"] }) fail("not_found")
        return JsonObject(current + ("createdInvitationId" to JsonPrimitive(id.toString())))
    }

    fun resolve(body: JsonObject): JsonObject {
        body.only(listOf("token"))
        val id =
            sql.row("SELECT id FROM client_manager_invitations WHERE token_hash=?", tokenHash(body))?.long("id")
                ?: fail("not_found")
        return recipient(id, preview = true)
    }

    fun status(id: Long): JsonObject = recipient(id, preview = false)

    private fun recipient(
        id: Long,
        preview: Boolean,
    ): JsonObject {
        val client = context(invitation(id).long("club_id"))
        val row = invitation(id)
        if (row.long("client_id") != client) fail("not_found")
        if (row["claimant_user_id"] != null) {
            if (row["claimant_user_id"] != session.userId?.toString()) fail("not_found")
        } else if (!preview) {
            fail("not_found")
        }
        if (session.actor.telegramUserId == session.ownerId ||
            row.long("inviter_user_id") == session.userId
        ) {
            fail("not_found")
        }
        session.userId?.let { sources.requireRecipient(row, it) }
        if (row["status"] in PENDING && !currentIssuer(row)) fail("not_found")
        if (row["status"] == "accepted" && !sources.active(row)) fail("not_found")
        return view(row, readOnly = false, issuerView = false)
    }

    fun claim(
        id: Long,
        body: JsonObject,
    ): MutationResult {
        body.only(listOf("token", "expectedVersion"))
        val club = invitation(id).long("club_id")
        val client = context(club)
        val row = invitation(id)
        if (row["status"] == "issued" && row["token_hash"] != tokenHash(body)) fail("not_found")
        if (row["status"] != "issued" && row["claimant_user_id"] != actor.toString()) fail("not_found")
        recipient(id, preview = true)
        if (effective(row) !in setOf("issued", "claimed")) fail("invalid_transition")
        sql.managerInvitationDeadline = row.instant("expires_at")
        session.version(row, body)
        if (row["status"] == "issued" || !freshClaim(row)) {
            val code = SecureRandom().nextInt(CODE_RANGE).toString().padStart(CODE_LENGTH, '0')
            sql.update(
                "UPDATE client_manager_invitations SET status='claimed',claimant_user_id=?," +
                    "verification_code=?,claim_valid_until=?,version=version+1 WHERE id=?",
                actor,
                code,
                checkNotNull(session.actor.managerAuthenticatedAt).plusSeconds(MANAGER_AUTH_SECONDS),
                id,
            )
            bump(club)
            session.audit("client.manager.invitation.claim", client, id, row.long("version") + 1)
        }
        return MutationResult("manager_claim", id, client, status(id))
    }

    fun confirm(
        club: Long,
        body: JsonObject,
        requestHash: String,
    ): JsonObject {
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
        val client = context(club)
        val (issuer, roles) = authority(client, club)
        session.version(clubRow(club), body)
        val row = forAction(client, club, body, issuer, roles)
        if (effective(row) != "claimed" ||
            !freshClaim(row) ||
            body.string("verificationCode") != row["verification_code"]
        ) {
            fail("invalid_transition")
        }
        sources.requireRecipient(row, row.long("claimant_user_id"))
        sql.managerInvitationDeadline = minOf(row.instant("claim_valid_until"), row.instant("expires_at"))
        // Reserve the real confirmation receipt before the V077 source's FK. All three records commit together.
        sql.update(
            "INSERT INTO client_commands(actor_user_id,connection_key,key_hash,request_hash," +
                "result_type,result_id,client_id,created_at) VALUES (?,?,?,?,'manager_roster',?,?,?)",
            actor,
            session.actor.connectionKey,
            session.correlation,
            requestHash,
            club,
            client,
            session.now,
        )
        sources.accept(row, issuer)
        bump(club)
        return roster(club)
    }

    private fun forAction(
        client: Long,
        club: Long,
        body: JsonObject,
        issuer: String,
        roles: Set<StaffRole>,
    ): DbRow {
        if (body["confirmed"] != JsonPrimitive(true)) fail("validation_error")
        val row = invitation(body.id("invitationId"))
        val sameClub = row.long("client_id") == client && row.long("club_id") == club
        if (!sameClub ||
            !isOriginalIssuer(row, issuer) ||
            roles.none { it.name == row["role_code"] }
        ) {
            fail("not_found")
        }
        if (row.long("version") != body.id("expectedInvitationVersion")) fail("version_conflict")
        return row
    }

    private fun view(
        row: DbRow,
        readOnly: Boolean,
        issuerView: Boolean,
    ): JsonObject {
        val state = effective(row)
        val claimed = state == "claimed"
        val codeAvailable = claimed && freshClaim(row)
        val claimable = state == "issued" || claimed && !codeAvailable
        val claimant = row["claimant_user_id"]?.toLong()?.let { sql.row("SELECT * FROM users WHERE id=?", it) }
        return buildJsonObject {
            put("id", row["id"])
            put("clubId", row["club_id"])
            put("clientId", row["client_id"])
            put("clientName", session.client(row.long("client_id"))["display_name"])
            put("clubName", clubRow(row.long("club_id"))["name"])
            put("acceptanceMode", "manager")
            put("role", row["role_code"])
            put("status", state)
            put("version", row["version"])
            put("expiresAt", row.instant("expires_at").toString())
            put("recipientLabel", if (issuerView) row["recipient_label"] else "")
            put(
                "candidate",
                claimant?.let {
                    buildJsonObject {
                        put("userId", it["id"])
                        put("telegramUserId", it["telegram_user_id"])
                        put("displayName", jsonNullable(it["display_name"]))
                        put("username", jsonNullable(it["username"]))
                    }
                } ?: JsonNull,
            )
            val code = if (!issuerView && codeAvailable) jsonNullable(row["verification_code"]) else JsonNull
            put("verificationCode", code)
            val actions =
                when {
                    readOnly -> emptyList()
                    issuerView && codeAvailable -> listOf("confirm", "revoke")
                    issuerView && (state == "issued" || claimed) -> listOf("revoke")
                    !issuerView && claimable -> listOf("claim")
                    else -> emptyList()
                }
            put("availableActions", strings(actions))
        }
    }

    private fun freshClaim(row: DbRow) =
        row["claim_valid_until"] != null && row.instant("claim_valid_until") > session.now

    private fun currentIssuer(row: DbRow): Boolean {
        val client = row.long("client_id")
        val club = row.long("club_id")
        val issuer = row.long("inviter_user_id")
        val ref = row["issuer_ref"] ?: return false
        return session.businessOwnerRef(client, club, issuer) == ref ||
            (row["role_code"] == StaffRole.CLUB_MANAGER.name && networkSourceCurrent(client, club, issuer, ref))
    }

    private fun networkSourceCurrent(
        client: Long,
        club: Long,
        issuer: Long,
        ref: String,
    ): Boolean =
        sql
            .rows(
                "$activeManagerSourcesSql AND a.client_id=? AND a.club_id=? AND a.user_id=? " +
                    "AND a.role_code='NETWORK_MANAGER'",
                session.now,
                client,
                club,
                issuer,
            ).any { "ASSIGNMENT:${it["id"]}:${it["version"]}:${it["admission_epoch"]}" == ref }

    private fun tokenHash(body: JsonObject) =
        digest(
            body.string("token")?.takeIf { TOKEN.matches(it) } ?: fail("not_found"),
        )

    private fun isOriginalIssuer(
        row: DbRow,
        issuer: String,
    ) = row.long("inviter_user_id") == actor && row["issuer_ref"] == issuer

    private fun effective(row: DbRow) =
        if (row["status"] in PENDING &&
            !session.now.isBefore(row.instant("expires_at"))
        ) {
            "expired"
        } else {
            checkNotNull(row["status"])
        }

    private fun invitation(id: Long) =
        sql.row("SELECT * FROM client_manager_invitations WHERE id=?", id) ?: fail("not_found")

    private fun clubRow(id: Long) =
        sql.row(
            "SELECT c.name,b.manager_roster_version AS version FROM client_business_clubs b " +
                "JOIN clubs c ON c.id=b.club_id WHERE b.club_id=?",
            id,
        ) ?: fail("not_found")

    private fun bump(id: Long) {
        sql.update(
            "UPDATE client_business_clubs SET manager_roster_version=manager_roster_version+1 WHERE club_id=?",
            id,
        )
    }

    companion object {
        private const val MAX_LABEL = 255
        private const val INVITATION_DAYS = 7L
        private const val CODE_RANGE = 1_000_000
        private const val CODE_LENGTH = 6
        private val TOKEN = Regex("[A-Za-z0-9_-]{43}")
        private val PENDING = setOf("issued", "claimed")
    }
}
