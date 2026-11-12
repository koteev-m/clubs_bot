package com.example.bot.data.client.assignments

import com.example.bot.data.client.onboarding.DbRow
import com.example.bot.data.client.onboarding.OnboardingSql
import com.example.bot.data.client.onboarding.active
import com.example.bot.data.client.onboarding.instant
import com.example.bot.data.client.onboarding.long
import java.time.Instant
import java.util.UUID

/** Transaction-local only, loaded AFTER the shared client lock; never used as an authorization cache. */
internal class AssignmentSnapshot(
    val sql: OnboardingSql,
    val clientId: Long,
    private val policies: AssignmentPolicyCatalog,
    val now: Instant,
) {
    val memberships =
        sql.rows("SELECT * FROM client_memberships WHERE client_id=?", clientId).associateBy {
            it.long("user_id")
        }
    val admissions = sql.rows("SELECT * FROM client_club_access_state WHERE client_id=?", clientId)
    val roles = sql.rows("SELECT * FROM client_role_assignments WHERE client_id=? ORDER BY id", clientId)
    val delegations = sql.rows("SELECT * FROM client_permission_delegations WHERE client_id=? ORDER BY id", clientId)
    private val registeredPermissions = sql.rows("SELECT code FROM permissions").map { it["code"] }.toSet()

    fun registered(permissions: Set<String>): Boolean = registeredPermissions.containsAll(permissions)

    fun admission(scope: StaffScope): DbRow? = admissions.singleOrNull { it.scope() == scope }

    fun admitted(scope: StaffScope): Boolean {
        val admission = admission(scope)
        val member = memberships[scope.userId]
        return admission?.get("status") == "ACTIVE" &&
            member?.active() == true &&
            admission["membership_version"] == member["version"]
    }

    fun liveRole(row: DbRow): RolePolicy? {
        val role = StaffRole.valueOf(checkNotNull(row["role_code"]))
        val policy = if (currentSource(row)) policies.roleOrNull(role, row.policy()) else null
        val selector = row.selector()
        return policy?.takeIf { selector.kind in it.selectors && registered(it.permissions) }
    }

    fun liveDelegation(row: DbRow): DelegationPolicy? {
        val parties = delegationParties(row)
        val policy = policies.delegationOrNull(checkNotNull(row["permission_code"]), row.policy())
        return policy?.takeIf {
            currentSource(row) && parties != null && validDelegationParties(row, parties, it)
        }
    }

    private fun currentSource(row: DbRow): Boolean =
        row["status"] == "ACTIVE" &&
            admitted(row.scope()) &&
            !row.expired(now) &&
            admission(row.scope())?.get("admission_epoch") == row["admission_epoch"]

    private fun delegationParties(row: DbRow): Pair<DbRow, DbRow>? {
        val recipient = roles.singleOrNull { it["id"] == row["recipient_assignment_id"] }
        val issuer = roles.singleOrNull { it["id"] == row["issuer_assignment_id"] }
        return if (recipient != null && issuer != null) recipient to issuer else null
    }

    private fun validDelegationParties(
        row: DbRow,
        parties: Pair<DbRow, DbRow>,
        policy: DelegationPolicy,
    ): Boolean {
        val (recipient, issuer) = parties
        val scope = row.scope()
        val exactParent =
            recipient.scope() == scope &&
                issuer.long("client_id") == scope.clientId &&
                issuer.long("club_id") == scope.clubId &&
                issuer.long("user_id") == row.long("issuer_user_id")
        val correctRoles =
            recipient["role_code"] == StaffRole.FLOOR_MANAGER.name &&
                issuer["role_code"] == StaffRole.CLUB_MANAGER.name
        val selectors = recipient.selector().kind == SelectorKind.CLUB && issuer.selector().kind == SelectorKind.CLUB
        val epochs = issuer["admission_epoch"] == row["issuer_admission_epoch"]
        val issuerPolicy = liveRole(issuer)
        return exactParent &&
            correctRoles &&
            selectors &&
            epochs &&
            liveRole(recipient) != null &&
            registered(policy.permissions) &&
            issuerPolicy?.permissions?.containsAll(policy.permissions + policy.authority) == true
    }

    fun view(scope: StaffScope): AssignmentView {
        val ownRoles = roles.filter { it.scope() == scope }
        val ownDelegations = delegations.filter { it.scope() == scope }
        val rolePolicies = ownRoles.mapNotNull { row -> liveRole(row)?.let { row to it } }
        val delegationPolicies = ownDelegations.mapNotNull { row -> liveDelegation(row)?.let { row to it } }
        val roleContributions =
            rolePolicies.flatMap { (row, policy) ->
                policy.permissions.sorted().map { row.contribution(ContributionKind.ROLE, it, row.selector()) }
            }
        val delegationContributions =
            delegationPolicies.flatMap { (row, policy) ->
                policy.permissions.sorted().map {
                    row.contribution(
                        ContributionKind.DELEGATION,
                        it,
                        PermissionSelector(),
                    )
                }
            }
        val dependencyIds =
            delegationPolicies
                .flatMap { (row, _) ->
                    listOf(row["issuer_assignment_id"], row["recipient_assignment_id"])
                }.toSet()
        val expiringSources =
            rolePolicies.map { it.first } + delegationPolicies.map { it.first } +
                roles.filter { it["id"] in dependencyIds }
        val expiry = expiringSources.filter { it["expires_at"] != null }.map { it.instant("expires_at") }.minOrNull()
        return AssignmentView(
            scope,
            admission(scope)?.admissionVersion(),
            memberships[scope.userId]?.long("version"),
            roleContributions + delegationContributions,
            (ownRoles + ownDelegations).associate { UUID.fromString(it["id"]) to checkNotNull(it["status"]) },
            expiry,
        )
    }
}

internal fun DbRow.scope(): StaffScope = StaffScope(long("client_id"), long("club_id"), long("user_id"))

internal fun DbRow.admissionVersion(): AdmissionVersion = AdmissionVersion(long("admission_epoch"), long("version"))

internal fun DbRow.policy(): PolicyReference = PolicyReference(long("policy_version"), checkNotNull(get("policy_hash")))

internal fun DbRow.selector(): PermissionSelector =
    PermissionSelector.decode(checkNotNull(get("selector_kind")), checkNotNull(get("selector_json")))

internal fun DbRow.expired(now: Instant): Boolean = get("expires_at") != null && instant("expires_at") <= now

private fun DbRow.contribution(
    kind: ContributionKind,
    permission: String,
    selector: PermissionSelector,
): PermissionContribution =
    PermissionContribution(
        kind,
        UUID.fromString(get("id")),
        permission,
        selector,
        long("version"),
        long("admission_epoch"),
        policy(),
    )
