package com.example.bot.data.client.onboarding

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Retire a confirmed staff package in an adopted club under its manager roster's client lock. */
internal class LegacyStaffAssignments(
    private val session: OnboardingSession,
) {
    private val sql get() = session.sql
    private val access = ClubStaffAccess(session)

    fun list(
        client: Long,
        club: Long,
    ): JsonArray =
        JsonArray(
            sources(client, club).map { staff ->
                buildJsonObject {
                    put("id", staff["invitation_id"])
                    put("role", staff["role_code"])
                    put("userId", staff["user_id"])
                    put("displayName", staff["display_name"] ?: staff["username"] ?: staff["user_id"])
                    put("version", staff["version"])
                    put(
                        "canRevoke",
                        !protected(client, staff) &&
                            !sql.blocksLegacyClubAuthorityWrite(client, club, staff.long("user_id")) &&
                            contributed(staff) != null,
                    )
                }
            },
        )

    fun revoke(
        client: Long,
        club: Long,
        body: JsonObject,
    ) {
        body.only(
            listOf("action", "expectedVersion", "assignmentId", "expectedAssignmentVersion", "reason", "confirmed"),
        )
        if (body["confirmed"] != JsonPrimitive(true)) fail("validation_error")
        val reason = body.text("reason", REASON_LIMIT, required = true)
        val id = body.id("assignmentId")
        val staff = sources(client, club).singleOrNull { it.long("invitation_id") == id } ?: fail("not_found")
        if (protected(client, staff)) fail("forbidden")
        if (staff.long("version") != body.id("expectedAssignmentVersion")) fail("version_conflict")
        session.requireLegacyClubAuthorityWrite(client, club, staff.long("user_id"))
        val permissions = contributed(staff) ?: fail("invalid_transition")
        val user = staff.long("user_id")
        val before = access.permissions(client, club, user)
        for (permission in permissions) {
            // All package rows are written before the staff row. A later independent edit is not ours.
            sql.update(
                "UPDATE client_club_grants SET is_active=FALSE,updated_at=? WHERE client_id=? AND club_id=? " +
                    "AND user_id=? AND permission_code=? AND is_active=TRUE AND updated_at<=?",
                session.now,
                client,
                club,
                user,
                permission,
                staff.instant("updated_at"),
            )
        }
        sql.update(
            "UPDATE client_club_staff SET is_active=FALSE,revoked_through_invitation_id=?,version=version+1," +
                "updated_at=? WHERE client_id=? AND club_id=? AND user_id=?",
            access.invitationFence(club),
            session.now,
            client,
            club,
            user,
        )
        access.bump(club)
        access.audit(
            "client.staff.assignment.revoke",
            client,
            club,
            id,
            staff.long("version") + 1,
            buildJsonObject {
                put("userId", user.toString())
                put("previousRole", staff["role_code"])
                put("reason", reason)
                put("before", strings(before))
                put("after", strings(access.permissions(client, club, user)))
            },
        )
    }

    private fun sources(
        client: Long,
        club: Long,
    ): List<DbRow> =
        sql.rows(
            "SELECT s.*,i.staff_role,i.inviter_user_id,u.telegram_user_id,u.display_name,u.username " +
                "FROM client_club_staff s JOIN client_invitations i ON i.client_id=s.client_id " +
                "AND i.club_id=s.club_id AND i.id=s.invitation_id AND i.claimant_user_id=s.user_id " +
                "JOIN users u ON u.id=s.user_id WHERE s.client_id=? AND s.club_id=? AND s.is_active=TRUE " +
                "AND i.purpose='staff' AND i.status='accepted' ORDER BY s.invitation_id",
            client,
            club,
        )

    private fun protected(
        client: Long,
        staff: DbRow,
    ): Boolean {
        val user = staff.long("user_id")
        val privileged = user == session.userId || staff["telegram_user_id"] == session.ownerId?.toString()
        return privileged || session.isBusinessOwner(client, user)
    }

    /** Replay exact staff revisions, including disable and rehire, against durable commands. */
    private fun contributed(staff: DbRow): Set<String>? {
        var state: LegacyPackage? = LegacyPackage()
        for (audit in history(staff)) {
            val event = parseMetadata(audit)
            if (state == null || event == null) {
                state = null
                break
            }
            if (sameScope(event, staff)) state = applyEvent(staff, state, audit, event)
        }
        return state?.takeIf { it.matches(staff) }?.permissions
    }

    private fun history(staff: DbRow): List<DbRow> =
        sql.rows(
            "SELECT a.id,a.entity_id,a.action,a.actor_user_id,a.metadata_json,a.created_at," +
                "i.id AS invitation_id,i.inviter_user_id,i.staff_role,i.version AS invitation_version,i.accepted_at," +
                "i.acceptance_policy FROM audit_log a LEFT JOIN client_invitations i ON a.action IN " +
                "('client.staff.invitation.confirm','client.staff.invitation.accept') " +
                "AND i.id=a.entity_id AND i.client_id=? AND i.club_id=? AND i.claimant_user_id=? " +
                "AND i.purpose='staff' AND i.status='accepted' WHERE a.entity_type='CLIENT_ONBOARDING' " +
                "AND (i.id IS NOT NULL OR (a.entity_id=? AND a.action IN " +
                "('client.staff.role.change','client.staff.disable','client.staff.platform_revoke'))) ORDER BY a.id",
            staff.long("client_id"),
            staff.long("club_id"),
            staff.long("user_id"),
            staff.long("user_id"),
        )

    private fun applyEvent(
        staff: DbRow,
        prior: LegacyPackage,
        audit: DbRow,
        event: JsonObject,
    ): LegacyPackage? {
        if (!hasReceipt(audit, event, staff)) return null
        val next = prior.version + 1
        val recorded = event.textValue("version")?.toLongOrNull()
        val accepted = audit["invitation_id"] != null
        return when {
            accepted -> {
                val validEvent =
                    recorded == audit.long("invitation_version") &&
                        event.textValue("candidateUserId") == staff["user_id"]
                val validActor =
                    if (audit["action"] == "client.staff.invitation.accept") {
                        audit["acceptance_policy"] == "recipient_acceptance" &&
                            audit["actor_user_id"] == staff["user_id"] &&
                            event.textValue("inviterUserId") == audit["inviter_user_id"]
                    } else {
                        audit["acceptance_policy"] == "owner_confirmation" &&
                            audit["actor_user_id"] == audit["inviter_user_id"]
                    }
                if (validEvent && validActor && audit.instant("accepted_at") <= audit.instant("created_at")) {
                    transition(event, audit["staff_role"], prior, audit.long("invitation_id"))
                } else {
                    null
                }
            }
            recorded != next || event.textValue("userId") != staff["user_id"] -> null
            audit["action"] == "client.staff.role.change" ->
                prior
                    .takeIf { it.role != null && it.role == event.textValue("previousRole") }
                    ?.let { transition(event, event.textValue("role"), it, it.invitation) }
            else -> prior.takeIf { it.role != null }?.let { LegacyPackage(version = next) }
        }?.copy(updatedThrough = audit.instant("created_at"))
    }

    private fun transition(
        event: JsonObject,
        role: String?,
        prior: LegacyPackage,
        invitation: Long?,
    ): LegacyPackage? {
        val policy = staffPackages.entries.singleOrNull { it.key.name == role } ?: return null
        val after = policy.value.toSet()
        val before = event.codes("before")
        // A role change retains attributable codes and adds only newly activated ones.
        return if (before != null && event.codes("after") == after && event.textValue("role") == role) {
            LegacyPackage(
                prior.version + 1,
                policy.key.name,
                invitation,
                after.intersect(prior.permissions) + (after - before),
            )
        } else {
            null
        }
    }

    private data class LegacyPackage(
        val version: Long = 0,
        val role: String? = null,
        val invitation: Long? = null,
        val permissions: Set<String> = emptySet(),
        val updatedThrough: java.time.Instant? = null,
    ) {
        fun matches(staff: DbRow): Boolean {
            val sourceMatches = invitation == staff.long("invitation_id") && role == staff["role_code"]
            val timeMatches = updatedThrough != null && staff.instant("updated_at") <= updatedThrough
            return version == staff.long("version") && sourceMatches && timeMatches
        }
    }

    private fun sameScope(
        event: JsonObject,
        staff: DbRow,
    ) = event.textValue("clientId") == staff["client_id"] && event.textValue("clubId") == staff["club_id"]

    private fun hasReceipt(
        audit: DbRow,
        event: JsonObject,
        staff: DbRow,
    ): Boolean {
        val (type, id) =
            when (audit["action"]) {
                "client.staff.platform_revoke" -> "member" to staff.long("user_id")
                "client.staff.invitation.accept" -> "invitation" to audit.long("invitation_id")
                else -> "club_team" to staff.long("club_id")
            }
        return sql.row(
            "SELECT actor_user_id FROM client_commands WHERE actor_user_id=? AND connection_key='platform' " +
                "AND key_hash=? AND client_id=? AND result_type=? AND result_id=?",
            audit["actor_user_id"]?.toLongOrNull(),
            event.textValue("commandCorrelation"),
            staff.long("client_id"),
            type,
            id,
        ) != null
    }

    private fun parseMetadata(row: DbRow): JsonObject? =
        try {
            onboardingJson.parseToJsonElement(row["metadata_json"].orEmpty()) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun JsonObject.textValue(key: String) =
        (this[key] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

    private fun JsonObject.codes(key: String): Set<String>? =
        (this[key] as? JsonArray)
            ?.takeIf { items ->
                items.all { it is JsonPrimitive && it.isString }
            }?.map { it.jsonPrimitive.content }
            ?.toSet()

    private companion object {
        const val REASON_LIMIT = 500
    }
}
