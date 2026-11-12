package com.example.bot.data.client.onboarding

import com.example.bot.data.client.assignments.PlatformMembershipSourceRevocation
import com.example.bot.data.security.Role
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val MANAGE_TEAM = "client.club.team.manage"
internal val staffPackages =
    mapOf(
        Role.ENTRY_MANAGER to listOf(READ_CLUB),
        Role.MANAGER to listOf(READ_CLUB, WRITE_PROFILE, WRITE_LAYOUT, WRITE_CALENDAR, WRITE_BOOKING_RULES),
        Role.CLUB_ADMIN to
            listOf(
                READ_CLUB,
                WRITE_PROFILE,
                WRITE_LAYOUT,
                WRITE_CALENDAR,
                WRITE_BOOKING_RULES,
                READ_FINANCE,
                WRITE_FINANCE,
                READ_MODULES,
                WRITE_MODULES,
            ),
    )

internal fun staffPackage(role: String?): List<String> =
    staffPackages.entries.singleOrNull { it.key.name == role }?.value ?: fail("validation_error")

/** Assignment metadata never authorizes a request; the exact current grants do. */
internal class ClubStaffAccess(
    val session: OnboardingSession,
) {
    private val sql get() = session.sql

    fun creator(clubId: Long): Long? =
        sql
            .rows(
                "SELECT owner_user_id FROM client_direct_club_profiles WHERE club_id=? UNION " +
                    "SELECT owner_user_id FROM client_club_onboarding WHERE club_id=?",
                clubId,
                clubId,
            ).singleOrNull()
            ?.long("owner_user_id")

    fun owns(clubId: Long): Boolean = creator(clubId) == session.userId

    fun owner(clubId: Long): Long {
        val clientId = session.requireClubGrant(clubId, MANAGE_TEAM)
        if (!owns(clubId)) fail("not_found")
        return clientId
    }

    fun packageAllowed(
        clientId: Long,
        clubId: Long,
        role: String?,
    ) {
        if (!staffPackage(role).all { session.hasClubGrant(clientId, clubId, it) }) fail("forbidden")
    }

    fun protectTarget(
        clientId: Long,
        clubId: Long,
        userId: Long,
    ) {
        if (creator(clubId) == userId ||
            sql.row(
                "SELECT user_id FROM client_membership_grants WHERE client_id=? AND user_id=? AND permission_code=?",
                clientId,
                userId,
                MANAGE,
            ) != null ||
            sql.row(
                "SELECT id FROM client_invitations WHERE client_id=? AND purpose='client' AND " +
                    "status='accepted' AND claimant_user_id=? LIMIT 1",
                clientId,
                userId,
            ) != null
        ) {
            fail("forbidden")
        }
    }

    fun invitationFence(clubId: Long): Long =
        checkNotNull(
            sql.row(
                "SELECT COALESCE(MAX(id),0) AS fence FROM client_invitations WHERE club_id=? AND purpose='staff'",
                clubId,
            ),
        ).long("fence")

    fun bump(clubId: Long) {
        if (sql.update("UPDATE clubs SET team_version=team_version+1 WHERE id=?", clubId) != 1) fail("not_found")
    }

    fun permissions(
        clientId: Long,
        clubId: Long,
        userId: Long,
    ): List<String> =
        sql
            .rows(
                "SELECT permission_code FROM client_club_grants WHERE client_id=? AND club_id=? AND user_id=? " +
                    "AND is_active=TRUE ORDER BY permission_code",
                clientId,
                clubId,
                userId,
            ).map { checkNotNull(it["permission_code"]) }

    fun replace(
        clientId: Long,
        clubId: Long,
        userId: Long,
        role: String?,
    ) {
        session.requireLegacyClubAuthorityWrite(clientId, clubId, userId)
        sql.update(
            "UPDATE client_club_grants SET is_active=FALSE,updated_at=? WHERE client_id=? AND club_id=? AND user_id=?",
            session.now,
            clientId,
            clubId,
            userId,
        )
        if (role == null) return
        staffPackage(role).forEach { permission ->
            val changed =
                sql.update(
                    "UPDATE client_club_grants SET is_active=TRUE,updated_at=? WHERE client_id=? AND club_id=? " +
                        "AND user_id=? AND permission_code=?",
                    session.now,
                    clientId,
                    clubId,
                    userId,
                    permission,
                )
            if (changed == 0) {
                sql.update(
                    "INSERT INTO " +
                        "client_club_grants(client_id,club_id,user_id,permission_code,is_active," +
                        "created_at,updated_at) " +
                        "VALUES (?,?,?,?,TRUE,?,?)",
                    clientId,
                    clubId,
                    userId,
                    permission,
                    session.now,
                    session.now,
                )
            }
        }
    }

    fun audit(
        action: String,
        clientId: Long,
        clubId: Long,
        resourceId: Long,
        version: Long,
        metadata: JsonObject,
    ) {
        sql.audit(
            checkNotNull(session.userId),
            action,
            clientId,
            resourceId,
            version,
            session.now,
            session.correlation,
            JsonObject(metadata + ("clubId" to JsonPrimitive(clubId.toString()))),
        )
    }

    fun platformRevoke(
        clientId: Long,
        userId: Long,
        revocation: PlatformMembershipSourceRevocation? = null,
    ) {
        if (revocation == null) {
            session.requireLegacyClientAuthorityWrite(clientId, userId)
        } else {
            revocation.consume(session, clientId, userId)
        }
        val affected =
            sql.rows(
                "SELECT club_id FROM client_club_grants WHERE client_id=? AND user_id=? UNION " +
                    "SELECT club_id FROM client_club_staff WHERE client_id=? AND user_id=?",
                clientId,
                userId,
                clientId,
                userId,
            )
        sql
            .rows(
                "SELECT * FROM client_club_staff WHERE client_id=? AND user_id=? AND is_active=TRUE",
                clientId,
                userId,
            ).forEach { staff ->
                sql.update(
                    "UPDATE client_club_staff SET " +
                        "is_active=FALSE,revoked_through_invitation_id=?,version=version+1,updated_at=? " +
                        "WHERE client_id=? AND club_id=? AND user_id=?",
                    invitationFence(staff.long("club_id")),
                    session.now,
                    clientId,
                    staff.long("club_id"),
                    userId,
                )
                audit(
                    "client.staff.platform_revoke",
                    clientId,
                    staff.long("club_id"),
                    userId,
                    staff.long("version") + 1,
                    buildJsonObject {
                        put("role", staff["role_code"])
                        put("userId", userId.toString())
                    },
                )
            }
        affected.forEach { bump(it.long("club_id")) }
    }
}

internal fun strings(values: List<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))
