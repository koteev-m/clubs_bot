package com.example.bot.data.client.assignments

import com.example.bot.data.client.onboarding.digest
import com.example.bot.data.client.onboarding.fail

/** Deployment-owned, immutable policy inputs; a command can reference a policy, never supply its contents. */
internal data class RolePolicy(
    val role: StaffRole,
    val version: Long,
    val permissions: Set<String>,
    val selectors: Set<SelectorKind> = setOf(SelectorKind.CLUB),
) {
    val reference: PolicyReference
        get() =
            PolicyReference(
                version,
                digest(
                    listOf(
                        "role-policy-v1",
                        role.name,
                        version.toString(),
                        permissions.sorted().joinToString(","),
                        selectors.map { it.name }.sorted().joinToString(","),
                    ).joinToString("\n"),
                ),
            )
}

internal data class DelegationPolicy(
    val permission: String,
    val version: Long,
) {
    val section: String get() = permission.removePrefix("client.club.").removeSuffix(".write")
    val supportingRead: String get() = "client.club.$section.read"
    val authority: String get() = "client.staff.delegate.$section"
    val permissions: Set<String> get() = setOf(permission, supportingRead)
    val reference: PolicyReference
        get() = PolicyReference(version, digest("delegation-policy-v1\n$permission\n$supportingRead\n$version"))
}

/** No production catalog is installed or injected by this inactive foundation. */
internal class AssignmentPolicyCatalog(
    roles: List<RolePolicy> = emptyList(),
    delegations: List<DelegationPolicy> = emptyList(),
) {
    private val roles =
        roles.map {
            it.copy(
                permissions = java.util.Collections.unmodifiableSet(it.permissions.toSet()),
                selectors = java.util.Collections.unmodifiableSet(it.selectors.toSet()),
            )
        }
    private val delegations = delegations.toList()

    init {
        require(
            this.roles
                .map { it.role to it.version }
                .distinct()
                .size == this.roles.size,
        )
        require(
            this.delegations
                .map { it.permission to it.version }
                .distinct()
                .size == this.delegations.size,
        )
        require(this.roles.all { it.version > 0 && it.permissions.isNotEmpty() && it.selectors.isNotEmpty() })
        require(this.roles.all { policy -> policy.permissions.all { CODE.matches(it) } })
        require(
            this.delegations.all {
                it.version > 0 &&
                    it.section in DELEGABLE_SECTIONS &&
                    it.permission == "client.club.${it.section}.write"
            },
        )
    }

    fun role(
        role: StaffRole,
        reference: PolicyReference,
    ): RolePolicy = roleOrNull(role, reference) ?: fail("invalid_transition")

    fun roleOrNull(
        role: StaffRole,
        reference: PolicyReference,
    ): RolePolicy? = roles.singleOrNull { it.role == role && it.reference == reference }

    fun delegation(
        permission: String,
        reference: PolicyReference,
    ): DelegationPolicy = delegationOrNull(permission, reference) ?: fail("invalid_transition")

    fun delegationOrNull(
        permission: String,
        reference: PolicyReference,
    ): DelegationPolicy? = delegations.singleOrNull { it.permission == permission && it.reference == reference }

    companion object {
        private val CODE = Regex("[a-z][a-z0-9_.]{1,127}")
        private val DELEGABLE_SECTIONS = setOf("profile", "layout", "calendar", "booking_rules")
    }
}
