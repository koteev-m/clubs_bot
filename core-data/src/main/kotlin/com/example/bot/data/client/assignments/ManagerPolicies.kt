package com.example.bot.data.client.assignments

import com.example.bot.data.client.onboarding.DbRow
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
import com.example.bot.data.client.onboarding.long

internal const val ASSIGN_CLUB_MANAGER = "client.staff.assign.club_manager"
internal const val REVOKE_CLUB_MANAGER = "client.staff.revoke.club_manager"
internal const val READ_REPORTS = "client.club.reports.read"
internal const val MANAGE_OPERATIONS_TEAM = "client.club.operations.team.manage"
internal const val CREATE_OPERATIONAL_NIGHT = "client.club.night.create"

internal val subordinateStaffRoles =
    setOf(StaffRole.FLOOR_MANAGER, StaffRole.ENTRANCE_MANAGER, StaffRole.FINANCE_MANAGER, StaffRole.PROMOTER)

internal val managerDelegationPolicies =
    setOf(WRITE_PROFILE, WRITE_LAYOUT, WRITE_CALENDAR, CREATE_OPERATIONAL_NIGHT, WRITE_BOOKING_RULES)
        .associateWith { DelegationPolicy(it, 1) }

private val floorOperations =
    setOf(
        "client.club.operations.read",
        "client.club.operations.booking.write",
        "client.club.operations.seat",
        "client.club.operations.release",
        "client.club.operations.table_money.read",
        "client.club.operations.deposit.record",
        "client.club.operations.top_up.record",
    )

private val managerReads = setOf(READ_CLUB, READ_FINANCE, READ_MODULES)
private val configurationWrites =
    setOf(WRITE_PROFILE, WRITE_LAYOUT, WRITE_CALENDAR, WRITE_BOOKING_RULES, WRITE_FINANCE, WRITE_MODULES)
private val networkV1 =
    RolePolicy(
        StaffRole.NETWORK_MANAGER,
        1,
        managerReads + setOf(READ_REPORTS, ASSIGN_CLUB_MANAGER, REVOKE_CLUB_MANAGER),
    )
private val networkV2 = networkV1.copy(version = 2, permissions = networkV1.permissions + configurationWrites)
private val clubManagerV1 = RolePolicy(StaffRole.CLUB_MANAGER, 1, managerReads + configurationWrites)
private val clubManagerOperations =
    floorOperations +
        setOf(
            MANAGE_OPERATIONS_TEAM,
            "client.club.operations.analytics.read",
            "client.club.operations.deposit_categories.write",
        ) + managerDelegationPolicies.values.flatMap { it.permissions }

internal val targetManagerProfilePermissions = clubManagerOperations + "client.club.operations.financial_reports.read"
private val clubManagerV2 =
    clubManagerV1.copy(
        version = 2,
        permissions =
            clubManagerV1.permissions + clubManagerOperations + managerDelegationPolicies.values.map { it.authority },
    )
private val subordinatePolicies =
    subordinateStaffRoles.associateWith { role ->
        RolePolicy(role, 1, if (role == StaffRole.FLOOR_MANAGER) floorOperations + READ_CLUB else setOf(READ_CLUB))
    }
private val managerPolicies = listOf(networkV1, networkV2, clubManagerV1, clubManagerV2) + subordinatePolicies.values
private val pinnedPolicies = AssignmentPolicyCatalog(managerPolicies)

internal fun OnboardingSession.managerPolicy(role: StaffRole): RolePolicy =
    when (role) {
        StaffRole.NETWORK_MANAGER -> networkV2
        StaffRole.CLUB_MANAGER -> if (sql.clubManagerOperationsAmendmentPresent) clubManagerV2 else clubManagerV1
        else ->
            subordinatePolicies[role]?.takeIf { sql.clubManagerOperationsAmendmentPresent }
                ?: fail("validation_error")
    }

internal fun pinnedManagerPolicy(row: DbRow): RolePolicy =
    pinnedPolicies.role(
        StaffRole.valueOf(checkNotNull(row["role_code"])),
        PolicyReference(row.long("policy_version"), checkNotNull(row["policy_hash"])),
    )

private fun RolePolicy.registeredSql(): String {
    val codes = permissions.joinToString(",") { "'$it'" }
    return "(SELECT COUNT(*) FROM permissions WHERE code IN ($codes))=${permissions.size}"
}

/**
 * Shared by discovery, the permission resolver and the invitation issuer fence. The generated
 * literals are from the immutable server policy. The only parameter here is the current instant.
 * A legacy/SHADOW assignment or a mere role label cannot expose even the club name.
 */
private val activeManagerFilterSql: String =
    run {
        val installedPolicies =
            managerPolicies.joinToString(" OR ") { policy ->
                "(a.role_code='${policy.role.name}' AND a.policy_version=${policy.version} " +
                    "AND a.policy_hash='${policy.reference.hash}' " +
                    "AND ${policy.registeredSql()})"
            }
        """
        FROM client_role_assignments a
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

private const val MANAGER_COLUMNS =
    "a.id,a.client_id,a.club_id,a.user_id,a.role_code,a.version,a.admission_epoch,a.policy_version,a.policy_hash"

internal val activeManagerSourcesSql = "SELECT $MANAGER_COLUMNS $activeManagerFilterSql"

private fun OnboardingSession.managerSourcesSql(): String {
    val network = if (sql.networkSettingsAmendmentPresent) ",a.network_settings_policy_hash" else ""
    val club = if (sql.clubManagerOperationsAmendmentPresent) ",a.club_manager_operations_policy_hash" else ""
    return "SELECT $MANAGER_COLUMNS$network$club $activeManagerFilterSql"
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

internal fun OnboardingSession.managerSources(
    clientId: Long,
    clubId: Long,
    user: Long? = userId,
): List<DbRow> =
    if (user == null || !sql.managerSchemaPresent) {
        emptyList()
    } else {
        if (user == userId) requireBusinessIdentity()
        loadManagerSources(clientId, clubId, user)
    }

private fun OnboardingSession.loadManagerSources(
    clientId: Long,
    clubId: Long,
    user: Long,
): List<DbRow> =
    sql.rows(
        "${managerSourcesSql()} AND a.client_id=? AND a.club_id=? AND a.user_id=? ORDER BY a.id",
        now,
        clientId,
        clubId,
        user,
    )

internal fun OnboardingSession.managerRoleCodes(
    clientId: Long,
    clubId: Long,
): Set<String> =
    if (targetManagerTuple(clientId, clubId)) {
        managerSources(clientId, clubId).map { checkNotNull(it["role_code"]) }.toSet()
    } else {
        emptySet()
    }

internal fun OnboardingSession.managerPermissions(
    clientId: Long,
    clubId: Long,
): Set<String> =
    if (targetManagerTuple(clientId, clubId)) {
        val sources = managerSources(clientId, clubId)
        buildSet {
            sources.forEach { addAll(managerSourcePermissions(it)) }
            val floor = sources.singleOrNull { it["role_code"] == StaffRole.FLOOR_MANAGER.name }
            if (floor != null) {
                liveManagerDelegations(floor).forEach { delegation ->
                    addAll(managerDelegationPolicies.getValue(checkNotNull(delegation["permission_code"])).permissions)
                }
            }
        }
    } else {
        emptySet()
    }

internal fun OnboardingSession.managerSourcePermissions(row: DbRow): Set<String> {
    val pinned = pinnedManagerPolicy(row)
    val amendments =
        when (pinned) {
            networkV1 -> networkV2.takeIf { it.reference.hash == row["network_settings_policy_hash"] }
            clubManagerV1 -> clubManagerV2.takeIf { it.reference.hash == row["club_manager_operations_policy_hash"] }
            else -> null
        }
    return if (amendments != null && sql.row("SELECT 1 AS present WHERE ${amendments.registeredSql()}") != null) {
        amendments.permissions
    } else {
        pinned.permissions
    }
}

internal fun OnboardingSession.liveManagerDelegations(floor: DbRow): List<DbRow> {
    if (!sql.clubManagerOperationsAmendmentPresent) return emptyList()
    val client = floor.long("client_id")
    val club = floor.long("club_id")
    return sql
        .rows(
            "SELECT id,client_id,club_id,user_id,permission_code,recipient_assignment_id,issuer_assignment_id," +
                "issuer_user_id,policy_version,policy_hash,admission_epoch,issuer_admission_epoch," +
                "status,version,expires_at " +
                "FROM client_permission_delegations WHERE client_id=? AND club_id=? AND user_id=? " +
                "AND recipient_assignment_id=? AND admission_epoch=? AND status='ACTIVE' " +
                "AND (expires_at IS NULL OR expires_at>?) ORDER BY id",
            client,
            club,
            floor.long("user_id"),
            java.util.UUID.fromString(floor["id"]),
            floor.long("admission_epoch"),
            now,
        ).filter { row ->
            val policy = managerDelegationPolicies[row["permission_code"]]
            val issuer =
                managerSources(client, club, row.long("issuer_user_id")).singleOrNull {
                    it["id"] == row["issuer_assignment_id"] &&
                        it["admission_epoch"] == row["issuer_admission_epoch"] &&
                        it["role_code"] == StaffRole.CLUB_MANAGER.name
                }
            policy != null &&
                policy.reference == row.policy() &&
                issuer != null &&
                managerSourcePermissions(issuer).containsAll(policy.permissions + policy.authority)
        }
}

internal fun DbRow.managerSourceRef(): String =
    "ASSIGNMENT:${getValue("id")}:${getValue("version")}:${getValue("admission_epoch")}"
