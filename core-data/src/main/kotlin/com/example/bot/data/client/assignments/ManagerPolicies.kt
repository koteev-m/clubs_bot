package com.example.bot.data.client.assignments

import com.example.bot.data.client.onboarding.OnboardingSession
import com.example.bot.data.client.onboarding.READ_CLUB
import com.example.bot.data.client.onboarding.READ_FINANCE
import com.example.bot.data.client.onboarding.READ_MODULES
import com.example.bot.data.client.onboarding.WRITE_BOOKING_RULES
import com.example.bot.data.client.onboarding.WRITE_CALENDAR
import com.example.bot.data.client.onboarding.WRITE_FINANCE
import com.example.bot.data.client.onboarding.WRITE_LAYOUT
import com.example.bot.data.client.onboarding.WRITE_MODULES
import com.example.bot.data.client.onboarding.WRITE_PROFILE
import com.example.bot.data.client.onboarding.fail

internal const val ASSIGN_CLUB_MANAGER = "client.staff.assign.club_manager"
internal const val REVOKE_CLUB_MANAGER = "client.staff.revoke.club_manager"
internal const val READ_REPORTS = "client.club.reports.read"

private val managerReads = setOf(READ_CLUB, READ_FINANCE, READ_MODULES)
private val managerPolicies =
    listOf(
        RolePolicy(
            StaffRole.NETWORK_MANAGER,
            1,
            managerReads + setOf(READ_REPORTS, ASSIGN_CLUB_MANAGER, REVOKE_CLUB_MANAGER),
        ),
        RolePolicy(
            StaffRole.CLUB_MANAGER,
            1,
            managerReads +
                setOf(WRITE_PROFILE, WRITE_LAYOUT, WRITE_CALENDAR, WRITE_BOOKING_RULES, WRITE_FINANCE, WRITE_MODULES),
        ),
    ).associateBy { it.role }

internal fun managerPolicy(role: StaffRole): RolePolicy = managerPolicies[role] ?: fail("validation_error")

/**
 * Shared by discovery, the permission resolver and the invitation issuer fence. The generated
 * literals are from the immutable server policy. The only parameter here is the current instant.
 * A legacy/SHADOW assignment or a mere role label cannot expose even the club name.
 */
internal val activeManagerSourcesSql: String =
    run {
        val installedPolicies =
            managerPolicies.values.joinToString(" OR ") { policy ->
                val codes = policy.permissions.joinToString(",") { "'$it'" }
                "(a.role_code='${policy.role.name}' AND a.policy_version=${policy.version} " +
                    "AND a.policy_hash='${policy.reference.hash}' " +
                    "AND (SELECT COUNT(*) FROM permissions WHERE code IN ($codes))=${policy.permissions.size})"
            }
        """
        SELECT a.id,a.client_id,a.club_id,a.user_id,a.role_code,a.version,a.admission_epoch FROM client_role_assignments a
        JOIN client_club_access_state s ON s.client_id=a.client_id AND s.club_id=a.club_id AND s.user_id=a.user_id
        JOIN client_memberships m ON m.client_id=s.client_id AND m.user_id=s.user_id
        JOIN clients c ON c.id=m.client_id
        JOIN client_manager_invitations i ON i.source_id=a.id AND i.client_id=a.client_id AND i.club_id=a.club_id
            AND i.claimant_user_id=a.user_id AND i.role_code=a.role_code AND i.policy_version=a.policy_version AND i.policy_hash=a.policy_hash
        WHERE i.status='accepted' AND m.is_active=TRUE AND c.is_active=TRUE AND c.archived_at IS NULL
            AND s.mode='TARGET' AND s.status='ACTIVE' AND s.membership_version=m.version
            AND a.selector_kind='CLUB' AND a.selector_json='[]' AND a.admission_epoch=s.admission_epoch
            AND a.status='ACTIVE' AND (a.expires_at IS NULL OR a.expires_at>?) AND ($installedPolicies)
        """.trimIndent()
    }

internal fun OnboardingSession.targetManagerTuple(
    clientId: Long,
    clubId: Long,
): Boolean =
    sql.managerSchemaPresent &&
        sql.row(
            "SELECT user_id FROM client_club_access_state WHERE client_id=? AND club_id=? " +
                "AND user_id=? AND mode='TARGET'",
            clientId,
            clubId,
            userId,
        ) != null

private fun OnboardingSession.managerSources(
    clientId: Long,
    clubId: Long,
) = if (userId == null || !targetManagerTuple(clientId, clubId)) {
    emptyList()
} else {
    requireBusinessIdentity()
    sql.rows(
        "$activeManagerSourcesSql AND a.client_id=? AND a.club_id=? AND a.user_id=? ORDER BY a.id",
        now,
        clientId,
        clubId,
        userId,
    )
}

internal fun OnboardingSession.managerRoleCodes(
    clientId: Long,
    clubId: Long,
): Set<String> = managerSources(clientId, clubId).map { checkNotNull(it["role_code"]) }.toSet()

internal fun OnboardingSession.managerPermissions(
    clientId: Long,
    clubId: Long,
): Set<String> = managerRoleCodes(clientId, clubId).flatMap { managerPolicy(StaffRole.valueOf(it)).permissions }.toSet()

internal fun OnboardingSession.networkManagerSourceRef(
    clientId: Long,
    clubId: Long,
): String? =
    managerSources(clientId, clubId).firstOrNull { it["role_code"] == StaffRole.NETWORK_MANAGER.name }?.let {
        "ASSIGNMENT:${it.getValue("id")}:${it.getValue("version")}:${it.getValue("admission_epoch")}"
    }
