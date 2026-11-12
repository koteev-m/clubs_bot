package com.example.bot.data.client.assignments

import com.example.bot.data.client.onboarding.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.util.UUID

/** Target staff roles only. Ownership, platform authority and guest SELF are separate providers. */
internal enum class StaffRole {
    NETWORK_MANAGER,
    CLUB_MANAGER,
    FLOOR_MANAGER,
    ENTRANCE_MANAGER,
    FINANCE_MANAGER,
    PROMOTER,
}

internal fun StaffRole.managementSuffix(): String =
    when (this) {
        StaffRole.FLOOR_MANAGER -> "manager"
        StaffRole.ENTRANCE_MANAGER -> "entry_manager"
        else -> name.lowercase()
    }

internal data class StaffScope(
    val clientId: Long,
    val clubId: Long,
    val userId: Long,
) {
    init {
        require(clientId > 0 && clubId > 0 && userId > 0)
    }
}

internal data class AdmissionVersion(
    val epoch: Long,
    val version: Long,
)

internal enum class SelectorKind { CLUB, SHIFT_SET, ALL_SHIFTS_OF_THIS_CLUB }

internal data class PermissionSelector(
    val kind: SelectorKind = SelectorKind.CLUB,
    val shifts: List<Long> = emptyList(),
) {
    init {
        require(shifts.size <= MAX_SHIFTS && shifts == shifts.distinct().sorted() && shifts.all { it > 0 })
        require(if (kind == SelectorKind.SHIFT_SET) shifts.isNotEmpty() else shifts.isEmpty())
    }

    fun includes(shiftId: Long?): Boolean =
        when (kind) {
            SelectorKind.CLUB -> shiftId == null
            SelectorKind.ALL_SHIFTS_OF_THIS_CLUB -> shiftId == null || shiftId > 0
            SelectorKind.SHIFT_SET -> shiftId != null && shiftId in shifts
        }

    fun encoded(): String = shifts.joinToString(",", "[", "]")

    companion object {
        private const val MAX_SHIFTS = 256

        fun decode(
            kind: String,
            encoded: String,
        ): PermissionSelector {
            val values = Json.parseToJsonElement(encoded) as? JsonArray ?: fail("invalid_transition")
            val shifts = values.map { (it as? JsonPrimitive)?.longOrNull ?: fail("invalid_transition") }
            val selector = PermissionSelector(SelectorKind.valueOf(kind), shifts)
            if (selector.encoded() != encoded) fail("invalid_transition")
            return selector
        }
    }
}

internal data class PolicyReference(
    val version: Long,
    val hash: String,
)

internal enum class ContributionKind { ROLE, DELEGATION }

internal data class PermissionContribution(
    val kind: ContributionKind,
    val sourceId: UUID,
    val permission: String,
    val selector: PermissionSelector,
    val sourceVersion: Long,
    val admissionEpoch: Long,
    val policy: PolicyReference,
)

/** A revision fences observed late responses; expiry also requires a fresh decision. */
internal data class AssignmentView(
    val scope: StaffScope,
    val admission: AdmissionVersion?,
    val membershipVersion: Long?,
    val contributions: List<PermissionContribution>,
    val sources: Map<UUID, String>,
    val validUntil: Instant?,
) {
    fun permits(
        permission: String,
        shiftId: Long? = null,
    ): Boolean = contributions.any { it.permission == permission && it.selector.includes(shiftId) }
}

internal data class AssignmentActorFence(
    val scope: StaffScope,
    val membershipVersion: Long,
    val admission: AdmissionVersion?,
    val authorityReference: String,
    val connectionKeyHash: String,
    val validUntil: Instant,
)

internal data class AssignmentResult(
    val replayed: Boolean,
    val current: AssignmentView,
    val actorFence: AssignmentActorFence,
)

internal data class AssignmentResolution(
    val recorded: Boolean,
    val current: AssignmentView,
    val actorFence: AssignmentActorFence,
)

internal sealed interface AssignmentCommand {
    val scope: StaffScope
    val expectedAdmission: AdmissionVersion?
    val reason: String
    val confirmed: Boolean

    data class Admit(
        override val scope: StaffScope,
        override val expectedAdmission: AdmissionVersion?,
        val intendedRole: StaffRole,
        val confirmationReference: String,
        override val reason: String,
        override val confirmed: Boolean = false,
    ) : AssignmentCommand

    data class IssueRole(
        override val scope: StaffScope,
        override val expectedAdmission: AdmissionVersion,
        val sourceId: UUID,
        val role: StaffRole,
        val policy: PolicyReference,
        val selector: PermissionSelector = PermissionSelector(),
        val expiresAt: Instant? = null,
        val regrantAcknowledgement: Set<String> = emptySet(),
        override val reason: String,
        override val confirmed: Boolean = false,
    ) : AssignmentCommand

    data class RevokeRole(
        override val scope: StaffScope,
        override val expectedAdmission: AdmissionVersion,
        val sourceId: UUID,
        val expectedSourceVersion: Long,
        override val reason: String,
        override val confirmed: Boolean = false,
    ) : AssignmentCommand

    data class IssueDelegation(
        override val scope: StaffScope,
        override val expectedAdmission: AdmissionVersion,
        val sourceId: UUID,
        val recipientAssignmentId: UUID,
        val recipientVersion: Long,
        val issuerAssignmentId: UUID,
        val issuerVersion: Long,
        val expectedIssuerAdmission: AdmissionVersion,
        val permission: String,
        val policy: PolicyReference,
        val expiresAt: Instant,
        val regrantAcknowledgement: Set<String> = emptySet(),
        override val reason: String,
        override val confirmed: Boolean = false,
    ) : AssignmentCommand

    data class RevokeDelegation(
        override val scope: StaffScope,
        override val expectedAdmission: AdmissionVersion,
        val sourceId: UUID,
        val expectedSourceVersion: Long,
        override val reason: String,
        override val confirmed: Boolean = false,
    ) : AssignmentCommand

    data class Disable(
        override val scope: StaffScope,
        override val expectedAdmission: AdmissionVersion,
        override val reason: String,
        override val confirmed: Boolean = false,
    ) : AssignmentCommand
}
