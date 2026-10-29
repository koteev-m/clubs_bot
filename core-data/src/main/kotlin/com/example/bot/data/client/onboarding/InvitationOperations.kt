package com.example.bot.data.client.onboarding

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.SecureRandom
import java.time.Duration

internal data class MutationResult(
    val type: String,
    val id: Long,
    val clientId: Long,
    val response: JsonObject,
)

internal class InvitationOperations(
    private val session: OnboardingSession,
) {
    private val sql get() = session.sql

    fun resolve(body: JsonObject): JsonObject {
        body.only(listOf("token"))
        val row = byToken(body)
        authorizeRecipient(row, preview = true)
        return view(row, platform = false)
    }

    fun get(
        id: Long,
        platform: Boolean,
    ): JsonObject {
        if (platform) session.owner()
        val row = row(id)
        if (!platform) authorizeRecipient(row, preview = false)
        return view(row, platform)
    }

    fun issue(body: JsonObject): MutationResult {
        session.owner()
        body.only(listOf("clientName", "recipientLabel", "allowedClubs"))
        val name = body.text("clientName", LABEL_LIMIT, required = true)
        val label = body.text("recipientLabel", LABEL_LIMIT, required = true)
        val allowed = if (body.containsKey("allowedClubs")) parseAllowedClubs(body) else 1
        val clientId =
            sql.insert(
                "INSERT INTO clients(display_name,is_active,allowed_clubs,created_at,updated_at) " +
                    "VALUES (?,FALSE,?,?,?)",
                name,
                allowed,
                session.now,
                session.now,
            )
        return create(clientId, label, null, "recipient_acceptance")
    }

    fun claim(
        id: Long,
        body: JsonObject,
    ): MutationResult {
        body.only(listOf("token", "expectedVersion"))
        val invitation = locked(id)
        if (invitation["token_hash"] != digest(token(body))) fail("not_found")
        val claimant = invitation["claimant_user_id"]?.toLong()
        if (claimant != null && claimant != session.userId) fail("not_found")
        if (effective(invitation) !in setOf("issued", "claimed", "accepted")) fail("not_found")
        if (invitation["status"] in setOf("claimed", "accepted")) {
            if (invitation["status"] == "accepted") session.member(invitation.long("client_id"), manage = false)
            return result(invitation, platform = false)
        }
        session.version(invitation, body)
        return if (invitation["acceptance_policy"] == "recipient_acceptance") {
            acceptRecipient(invitation)
        } else {
            claimForConfirmation(invitation)
        }
    }

    private fun claimForConfirmation(invitation: DbRow): MutationResult {
        val id = invitation.long("id")
        val code = (CODE_MINIMUM + SecureRandom().nextInt(CODE_RANGE)).toString()
        sql.update(
            "UPDATE client_invitations SET status='claimed',claimant_user_id=?,verification_code=?," +
                "claimed_at=?,updated_at=?,version=version+1 WHERE id=?",
            session.userId,
            code,
            session.now,
            session.now,
            id,
        )
        session.audit("client.invitation.claim", invitation.long("client_id"), id, invitation.long("version") + 1)
        return result(row(id), platform = false)
    }

    fun action(
        id: Long,
        operation: OnboardingMutation,
        body: JsonObject,
    ): MutationResult {
        session.owner()
        body.only(listOf("expectedVersion"))
        val invitation = locked(id)
        session.version(invitation, body)
        return when (operation) {
            OnboardingMutation.INVITATION_CONFIRM -> confirm(invitation)
            OnboardingMutation.INVITATION_REVOKE -> {
                if (invitation["status"] !in setOf("issued", "claimed")) fail("invalid_transition")
                revoke(invitation)
                result(row(id), platform = true)
            }
            OnboardingMutation.INVITATION_REISSUE -> {
                if (invitation["status"] == "accepted") fail("invalid_transition")
                if (invitation["status"] != "revoked") revoke(invitation)
                if (sql.row("SELECT id FROM client_invitations WHERE replaces_invitation_id=?", id) != null) {
                    fail("invalid_transition")
                }
                create(
                    invitation.long("client_id"),
                    checkNotNull(invitation.getValue("recipient_label")),
                    id,
                    checkNotNull(invitation["acceptance_policy"]),
                )
            }
            else -> fail("validation_error")
        }
    }

    fun forClient(clientId: Long): JsonArray =
        JsonArray(
            sql
                .rows("SELECT * FROM client_invitations WHERE client_id=? ORDER BY id DESC", clientId)
                .map { view(it, platform = true) },
        )

    private fun confirm(invitation: DbRow): MutationResult {
        if (effective(invitation) != "claimed") fail("invalid_transition")
        val clientId = invitation.long("client_id")
        val userId = invitation.long("claimant_user_id")
        if (sql.row("SELECT * FROM client_memberships WHERE client_id=? AND user_id=?", clientId, userId) != null) {
            // An accepted invitation never silently restores a deactivated membership.
            fail("invalid_transition")
        }
        sql.update("UPDATE clients SET is_active=TRUE,updated_at=? WHERE id=?", session.now, clientId)
        sql.update(
            "INSERT INTO client_memberships(client_id,user_id,is_active,created_at,updated_at) VALUES (?,?,TRUE,?,?)",
            clientId,
            userId,
            session.now,
            session.now,
        )
        sql.update(
            "INSERT INTO client_membership_grants(client_id,user_id,permission_code,is_active,created_at,updated_at) " +
                "VALUES (?,?,?,TRUE,?,?)",
            clientId,
            userId,
            MANAGE,
            session.now,
            session.now,
        )
        sql.update(
            "UPDATE client_invitations SET status='accepted',open_marker=NULL,verification_code=NULL," +
                "accepted_at=?,updated_at=?,version=version+1 WHERE id=?",
            session.now,
            session.now,
            invitation.long("id"),
        )
        session.audit("client.invitation.confirm", clientId, invitation.long("id"), invitation.long("version") + 1)
        return result(row(invitation.long("id")), platform = true)
    }

    private fun acceptRecipient(invitation: DbRow): MutationResult {
        val clientId = invitation.long("client_id")
        // Direct acceptance is exclusively for a newly issued empty cabinet, never an account transfer.
        if (session.client(clientId).active()) fail("invalid_transition")
        if (sql.row("SELECT client_id FROM client_memberships WHERE client_id=? LIMIT 1", clientId) != null ||
            sql.row("SELECT client_id FROM client_clubs WHERE client_id=? LIMIT 1", clientId) != null ||
            sql.row("SELECT client_id FROM client_club_applications WHERE client_id=? LIMIT 1", clientId) != null
        ) {
            fail("invalid_transition")
        }
        sql.update("UPDATE clients SET is_active=TRUE,updated_at=? WHERE id=?", session.now, clientId)
        sql.update(
            "INSERT INTO client_memberships(client_id,user_id,is_active,created_at,updated_at) VALUES (?,?,TRUE,?,?)",
            clientId,
            session.userId,
            session.now,
            session.now,
        )
        sql.update(
            "INSERT INTO client_membership_grants(client_id,user_id,permission_code,is_active,created_at,updated_at) " +
                "VALUES (?,?,?,TRUE,?,?)",
            clientId,
            session.userId,
            MANAGE,
            session.now,
            session.now,
        )
        sql.update(
            "UPDATE client_invitations SET status='accepted',open_marker=NULL,claimant_user_id=?," +
                "claimed_at=?,accepted_at=?,updated_at=?,version=version+1 WHERE id=?",
            session.userId,
            session.now,
            session.now,
            session.now,
            invitation.long("id"),
        )
        session.audit("client.invitation.accept", clientId, invitation.long("id"), invitation.long("version") + 1)
        return result(row(invitation.long("id")), platform = false)
    }

    private fun create(
        clientId: Long,
        label: String,
        previousId: Long?,
        acceptancePolicy: String,
    ): MutationResult {
        val secret = randomToken()
        val id =
            sql.insert(
                "INSERT INTO client_invitations(client_id,recipient_label,token_hash,status,open_marker,expires_at," +
                    "replaces_invitation_id,created_at,updated_at,acceptance_policy) " +
                    "VALUES (?,?,?,'issued',1,?,?,?,?,?)",
                clientId,
                label,
                digest(secret),
                session.now.plus(Duration.ofDays(INVITATION_DAYS)),
                previousId,
                session.now,
                session.now,
                acceptancePolicy,
            )
        session.audit("client.invitation.issue", clientId, id, 1)
        val response = JsonObject(view(row(id), platform = true) + ("token" to JsonPrimitive(secret)))
        return MutationResult("invitation", id, clientId, response)
    }

    private fun revoke(invitation: DbRow) {
        sql.update(
            "UPDATE client_invitations SET status='revoked',open_marker=NULL,verification_code=NULL," +
                "revoked_at=?,updated_at=?,version=version+1 WHERE id=?",
            session.now,
            session.now,
            invitation.long("id"),
        )
        session.audit(
            "client.invitation.revoke",
            invitation.long("client_id"),
            invitation.long("id"),
            invitation.long("version") + 1,
        )
    }

    private fun row(id: Long): DbRow = sql.row("SELECT * FROM client_invitations WHERE id=?", id) ?: fail("not_found")

    private fun locked(id: Long): DbRow {
        val before = row(id)
        session.client(before.long("client_id"), lock = true)
        return sql.row("SELECT * FROM client_invitations WHERE id=? FOR UPDATE", id) ?: fail("not_found")
    }

    private fun token(body: JsonObject): String =
        body
            .string("token")
            ?.takeIf { Regex("[A-Za-z0-9_-]{43}").matches(it) } ?: fail("not_found")

    private fun byToken(body: JsonObject): DbRow =
        sql.row("SELECT * FROM client_invitations WHERE token_hash=?", digest(token(body))) ?: fail("not_found")

    private fun authorizeRecipient(
        invitation: DbRow,
        preview: Boolean,
    ) {
        val claimant = invitation["claimant_user_id"]?.toLong()
        if (claimant != null && claimant != session.userId) fail("not_found")
        if (!preview && claimant == null) fail("not_found")
        if (invitation["status"] == "accepted") session.member(invitation.long("client_id"), manage = false)
    }

    private fun effective(invitation: DbRow): String =
        if (invitation["status"] in setOf("issued", "claimed") &&
            !session.now.isBefore(invitation.instant("expires_at"))
        ) {
            "expired"
        } else {
            checkNotNull(invitation.getValue("status"))
        }

    private fun result(
        invitation: DbRow,
        platform: Boolean,
    ): MutationResult =
        MutationResult(
            "invitation",
            invitation.long("id"),
            invitation.long("client_id"),
            view(invitation, platform),
        )

    private fun view(
        invitation: DbRow,
        platform: Boolean,
    ): JsonObject {
        val state = effective(invitation)
        val candidate =
            invitation["claimant_user_id"]?.toLong()?.let { id ->
                sql.row("SELECT * FROM users WHERE id=?", id)
            }
        val actions =
            if (platform) {
                when (state) {
                    "issued" -> listOf("revoke", "reissue")
                    "claimed" -> listOf("confirm", "revoke", "reissue")
                    "expired", "revoked" -> listOf("reissue")
                    else -> emptyList()
                }
            } else if (state == "issued") {
                listOf("claim")
            } else {
                emptyList()
            }
        return buildJsonObject {
            put("id", invitation.getValue("id"))
            put("clientId", invitation.getValue("client_id"))
            put("clientName", session.client(invitation.long("client_id")).getValue("display_name"))
            put("recipientLabel", if (platform) invitation.getValue("recipient_label") else "")
            put("status", state)
            put("acceptanceMode", if (invitation["acceptance_policy"] == "recipient_acceptance") "direct" else "legacy")
            put("version", invitation.getValue("version"))
            put("expiresAt", invitation.instant("expires_at").toString())
            put(
                "candidate",
                candidate?.let {
                    buildJsonObject {
                        put("userId", it.getValue("id"))
                        put("telegramUserId", it.getValue("telegram_user_id"))
                        put("displayName", jsonNullable(it["display_name"]))
                        put("username", jsonNullable(it["username"]))
                    }
                } ?: JsonNull,
            )
            put("verificationCode", if (state == "claimed") jsonNullable(invitation["verification_code"]) else JsonNull)
            put("availableActions", JsonArray(actions.map(::JsonPrimitive)))
        }
    }

    private companion object {
        const val LABEL_LIMIT = 255
        const val CODE_MINIMUM = 100000
        const val CODE_RANGE = 900000
        const val INVITATION_DAYS = 7L
    }
}
