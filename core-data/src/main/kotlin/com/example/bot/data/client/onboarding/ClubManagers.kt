package com.example.bot.data.client.onboarding

import com.example.bot.data.client.assignments.MANAGE_OPERATIONS_TEAM
import com.example.bot.data.client.assignments.StaffRole
import com.example.bot.data.client.assignments.managerDelegationPolicies
import com.example.bot.data.client.assignments.managerPolicy
import com.example.bot.data.client.assignments.managerSourcePermissions
import com.example.bot.data.client.assignments.managerSourceRef
import com.example.bot.data.client.assignments.managerSources
import com.example.bot.data.client.assignments.pinnedManagerPolicy
import com.example.bot.data.client.assignments.subordinateStaffRoles
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom
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
    private val scopes = ManagerInvitationScopes(session)

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

    private fun authorities(
        client: Long,
        club: Long,
    ): Map<StaffRole, String> {
        if (session.actor.telegramUserId == session.ownerId) fail("not_found")
        session.member(client, manage = false)
        val owner = session.businessOwnerRef(client, club)
        val assignments = session.managerSources(client, club)
        val network = assignments.singleOrNull { it["role_code"] == StaffRole.NETWORK_MANAGER.name }
        val clubManager =
            assignments.singleOrNull {
                it["role_code"] == StaffRole.CLUB_MANAGER.name &&
                    MANAGE_OPERATIONS_TEAM in session.managerSourcePermissions(it)
            }
        return buildMap {
            if (owner != null) put(StaffRole.NETWORK_MANAGER, owner)
            (owner ?: network?.managerSourceRef())?.let { put(StaffRole.CLUB_MANAGER, it) }
            if (clubManager != null) subordinateStaffRoles.forEach { put(it, clubManager.managerSourceRef()) }
        }.ifEmpty { fail("not_found") }
    }

    fun roster(club: Long): JsonObject {
        val client = context(club)
        val authorities = authorities(client, club)
        val roles = authorities.keys
        val current = clubRow(club)
        val allowed = roles.map { it.name }
        return buildJsonObject {
            put("clubId", club.toString())
            put("clientId", client.toString())
            put("name", current["name"])
            put("version", current["version"])
            put("canWrite", true)
            put("roles", strings(allowed))
            put(
                "rolePermissions",
                buildJsonObject {
                    roles.forEach { put(it.name, strings(session.managerPolicy(it).permissions.sorted())) }
                },
            )
            if (StaffRole.FLOOR_MANAGER in roles) {
                put("delegablePermissions", strings(managerDelegationPolicies.keys.toList()))
            }
            put("assignableClubs", scopes.choices(client, club, StaffRole.NETWORK_MANAGER in roles))
            val invitations =
                sql.rows(
                    "${sql.managerInvitationSelect} WHERE client_id=? AND club_id=? ORDER BY id DESC",
                    client,
                    club,
                )
            put(
                "invitations",
                JsonArray(
                    invitations.filter { it["role_code"] in allowed && it["status"] in PENDING }.map {
                        view(it, !canCancel(it, authorities), issuerView = true)
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
                            put("permissions", strings(pinnedManagerPolicy(row).permissions.sorted()))
                            if (row["role_code"] == StaffRole.FLOOR_MANAGER.name) {
                                put("delegations", ManagerDelegations(session).list(row))
                            }
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
        val authorities = authorities(client, club)
        val roles = authorities.keys
        session.version(clubRow(club), body)
        when (body.string("action")) {
            "invite" -> return issue(client, club, body, authorities)
            "revoke_assignment" -> sources.revoke(client, club, body, roles)
            "revoke_legacy_assignment" -> {
                if (session.businessOwnerRef(client, club) == null) fail("forbidden")
                legacy.revoke(client, club, body)
            }
            "revoke_invitation" -> {
                body.only(listOf("action", "expectedVersion", "invitationId", "expectedInvitationVersion", "confirmed"))
                val row = forAction(client, club, body, authorities, allowPredecessor = true)
                if (row["status"] !in PENDING) fail("invalid_transition")
                for (scope in scopes.rows(row)) {
                    sql.update(
                        "UPDATE client_manager_invitations SET status='revoked',verification_code=NULL," +
                            "version=version+1 WHERE id=?",
                        scope.long("id"),
                    )
                    if (scope.long("club_id") != club) bump(scope.long("club_id"))
                    session.audit(
                        "client.manager.invitation.revoke",
                        client,
                        scope.long("id"),
                        scope.long("version") + 1,
                    )
                }
            }
            "revoke_delegation" -> ManagerDelegations(session).revoke(client, club, body)
            else -> fail("validation_error")
        }
        bump(club)
        return MutationResult("manager_roster", club, client, roster(club))
    }

    private fun issue(
        client: Long,
        club: Long,
        body: JsonObject,
        authorities: Map<StaffRole, String>,
    ): MutationResult {
        body.only(listOf("action", "expectedVersion", "recipientLabel", "role", "clubIds"))
        val role = authorities.keys.singleOrNull { it.name == body.string("role") } ?: fail("forbidden")
        val issuer = authorities.getValue(role)
        val label = body.text("recipientLabel", MAX_LABEL, required = true)
        val clubs = scopes.selected(body, club, role)
        val token = randomToken()
        val expiresAt = session.now.plusSeconds(INVITATION_SECONDS)
        var rootId: Long? = null
        for (scope in clubs) {
            val ref = if (scope == club) issuer else session.businessOwnerRef(client, scope) ?: fail("forbidden")
            val policy = session.managerPolicy(role)
            val id =
                sql.insert(
                    "INSERT INTO client_manager_invitations(client_id,club_id,role_code,policy_version," +
                        "policy_hash,inviter_user_id,issuer_ref,recipient_label,token_hash,created_at,expires_at," +
                        "parent_invitation_id,acceptance_policy) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'recipient_acceptance')",
                    client,
                    scope,
                    role.name,
                    policy.version,
                    policy.reference.hash,
                    actor,
                    ref,
                    label,
                    digest(if (rootId == null) token else randomToken()),
                    session.now,
                    expiresAt,
                    rootId,
                )
            if (rootId == null) rootId = id
            bump(scope)
            session.audit("client.manager.invitation.issue", client, id, 1)
        }
        val id = checkNotNull(rootId)
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
        val authorities = authorities(row.long("client_id"), row.long("club_id"))
        if (!isOriginalIssuer(row, authorities)) fail("not_found")
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
        if (row.long("client_id") != client || row["parent_invitation_id"] != null) fail("not_found")
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
        for (scope in scopes.rows(row)) {
            session.userId?.let { sources.requireRecipient(scope, it) }
            if (scope["status"] in PENDING && !currentIssuer(scope)) fail("not_found")
        }
        if (row["status"] == "accepted" && !sources.active(row)) fail("not_found")
        return view(row, readOnly = false, issuerView = false)
    }

    fun claim(
        id: Long,
        body: JsonObject,
        requestHash: String,
    ): JsonObject {
        body.only(listOf("token", "expectedVersion"))
        val club = invitation(id).long("club_id")
        val client = context(club)
        val row = invitation(id)
        if (row["status"] == "issued" && row["token_hash"] != tokenHash(body)) fail("not_found")
        if (row["status"] != "issued" && row["claimant_user_id"] != actor.toString()) fail("not_found")
        recipient(id, preview = true)
        if (effective(row) !in setOf("issued", "claimed")) fail("invalid_transition")
        sql.invitationDeadline = row.instant("expires_at")
        session.version(row, body)
        if (row["acceptance_policy"] == "recipient_acceptance") {
            if (row["status"] != "issued") fail("invalid_transition")
            reserveReceipt("manager_claim", id, client, requestHash)
            for (scope in scopes.rows(row)) {
                if (effective(scope) != "issued") fail("invalid_transition")
                sources.accept(scope, checkNotNull(scope["issuer_ref"]), actor)
                bump(scope.long("club_id"))
            }
            return status(id)
        }
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
        reserveReceipt("manager_claim", id, client, requestHash)
        return status(id)
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
        val authorities = authorities(client, club)
        session.version(clubRow(club), body)
        val row = forAction(client, club, body, authorities)
        val issuer = checkNotNull(row["issuer_ref"])
        if (effective(row) != "claimed" ||
            !freshClaim(row) ||
            body.string("verificationCode") != row["verification_code"]
        ) {
            fail("invalid_transition")
        }
        sources.requireRecipient(row, row.long("claimant_user_id"))
        sql.invitationDeadline = minOf(row.instant("claim_valid_until"), row.instant("expires_at"))
        reserveReceipt("manager_roster", club, client, requestHash)
        sources.accept(row, issuer, row.long("claimant_user_id"))
        bump(club)
        return roster(club)
    }

    fun delegate(
        club: Long,
        body: JsonObject,
        requestHash: String,
    ): JsonObject {
        val client = context(club)
        session.version(clubRow(club), body)
        reserveReceipt("manager_roster", club, client, requestHash)
        ManagerDelegations(session).issue(client, club, body)
        bump(club)
        return roster(club)
    }

    // The assignment's FK points to the real command. All scopes, audits and the receipt commit together.
    private fun reserveReceipt(
        type: String,
        id: Long,
        client: Long,
        requestHash: String,
    ) {
        sql.update(
            "INSERT INTO client_commands(actor_user_id,connection_key,key_hash,request_hash," +
                "result_type,result_id,client_id,created_at) VALUES (?,?,?,?,?,?,?,?)",
            actor,
            session.actor.connectionKey,
            session.correlation,
            requestHash,
            type,
            id,
            client,
            session.now,
        )
    }

    private fun forAction(
        client: Long,
        club: Long,
        body: JsonObject,
        authorities: Map<StaffRole, String>,
        allowPredecessor: Boolean = false,
    ): DbRow {
        if (body["confirmed"] != JsonPrimitive(true)) fail("validation_error")
        val row = invitation(body.id("invitationId"))
        val sameClub = row.long("client_id") == client && row.long("club_id") == club
        val authorized = if (allowPredecessor) canCancel(row, authorities) else isOriginalIssuer(row, authorities)
        if (!sameClub || !authorized) fail("not_found")
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
            put("acceptanceFlow", if (row["acceptance_policy"] == "recipient_acceptance") "direct" else "confirmation")
            put(
                "clubs",
                scopes.names(row) { scope ->
                    when {
                        issuerView ->
                            scope["club_id"] == row["club_id"] ||
                                session.businessOwnerRef(scope.long("client_id"), scope.long("club_id")) != null
                        state == "accepted" -> sources.active(scope)
                        else -> true
                    }
                },
            )
            put("role", row["role_code"])
            put("permissions", strings(pinnedManagerPolicy(row).permissions.sorted()))
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
        if (sql.row("SELECT id FROM users WHERE id=? AND telegram_user_id=?", issuer, session.ownerId) !=
            null
        ) {
            return false
        }
        val ref = checkNotNull(row["issuer_ref"])
        val role = StaffRole.valueOf(checkNotNull(row["role_code"]))
        return when {
            role in subordinateStaffRoles ->
                session.managerSources(client, club, issuer).any {
                    it["role_code"] == StaffRole.CLUB_MANAGER.name &&
                        it.managerSourceRef() == ref &&
                        MANAGE_OPERATIONS_TEAM in session.managerSourcePermissions(it)
                }
            session.businessOwnerRef(client, club, issuer) == ref -> true
            role == StaffRole.CLUB_MANAGER ->
                session.managerSources(client, club, issuer).any {
                    it["role_code"] == StaffRole.NETWORK_MANAGER.name && it.managerSourceRef() == ref
                }
            else -> false
        }
    }

    private fun tokenHash(body: JsonObject) =
        digest(
            body.string("token")?.takeIf { TOKEN.matches(it) } ?: fail("not_found"),
        )

    private fun isOriginalIssuer(
        row: DbRow,
        authorities: Map<StaffRole, String>,
    ): Boolean {
        val issuer = authorities[StaffRole.valueOf(checkNotNull(row["role_code"]))]
        return issuer != null && row.long("inviter_user_id") == actor && row["issuer_ref"] == issuer
    }

    private fun canCancel(
        row: DbRow,
        authorities: Map<StaffRole, String>,
    ): Boolean {
        val role = StaffRole.valueOf(checkNotNull(row["role_code"]))
        return (role in subordinateStaffRoles && role in authorities) || isOriginalIssuer(row, authorities)
    }

    private fun effective(row: DbRow) =
        if (row["status"] in PENDING &&
            !session.now.isBefore(row.instant("expires_at"))
        ) {
            "expired"
        } else {
            checkNotNull(row["status"])
        }

    private fun invitation(id: Long) = sql.row("${sql.managerInvitationSelect} WHERE id=?", id) ?: fail("not_found")

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
        private const val INVITATION_SECONDS = 7L * 24 * 60 * 60
        private const val CODE_RANGE = 1_000_000
        private const val CODE_LENGTH = 6
        private val TOKEN = Regex("[A-Za-z0-9_-]{43}")
        private val PENDING = setOf("issued", "claimed")
    }
}
