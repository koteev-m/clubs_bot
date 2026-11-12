package com.example.bot.data.client.assignments

import com.example.bot.data.client.onboarding.DbRow
import com.example.bot.data.client.onboarding.active
import com.example.bot.data.client.onboarding.fail
import com.example.bot.data.client.onboarding.long
import java.util.UUID

/** Pure validation over the current locked snapshot. No operation replaces a permissions array. */
internal class AssignmentLifecycle(
    val snapshot: AssignmentSnapshot,
    private val policies: AssignmentPolicyCatalog,
    private val authority: AssignmentAuthority,
    private val identity: VerifiedAssignmentIdentity,
    private val admissionVerifier: TargetAdmissionVerifier,
) {
    var admissionProof: TargetAdmissionConfirmation? = null
        private set
    var invitationWatermark: Long = 0
        private set

    fun authorize(
        command: AssignmentCommand,
        replay: Boolean = false,
    ): String {
        authority.protect(command.scope)
        return when (command) {
            is AssignmentCommand.Admit -> authority.manage(command.scope, command.intendedRole, "assign")
            is AssignmentCommand.IssueRole -> authority.manage(command.scope, command.role, "assign")
            is AssignmentCommand.RevokeRole ->
                authority.manage(
                    command.scope,
                    StaffRole.valueOf(checkNotNull(role(command.scope, command.sourceId)["role_code"])),
                    "revoke",
                )
            is AssignmentCommand.IssueDelegation -> {
                val issuer = roleById(command.issuerAssignmentId)
                authority.delegate(command.scope, issuer, policies.delegation(command.permission, command.policy))
                "ASSIGNMENT:${issuer["id"]}:${issuer["version"]}:${issuer["admission_epoch"]}"
            }
            is AssignmentCommand.RevokeDelegation -> {
                val source = delegation(command.scope, command.sourceId)
                authority.revokeDelegation(
                    command.scope,
                    source,
                    policies.delegation(checkNotNull(source["permission_code"]), source.policy()),
                )
            }
            is AssignmentCommand.Disable -> authority.disable(command.scope, replay)
        }
    }

    fun validate(command: AssignmentCommand) {
        val fence = snapshot.admission(command.scope)
        if (fence?.admissionVersion() != command.expectedAdmission) fail("version_conflict")
        if (fence != null && fence["mode"] != "SHADOW") fail("invalid_transition")
        when (command) {
            is AssignmentCommand.Admit -> validateAdmission(command, fence)
            is AssignmentCommand.IssueRole -> validateRole(command)
            is AssignmentCommand.RevokeRole ->
                sourceVersion(
                    role(command.scope, command.sourceId),
                    command.expectedSourceVersion,
                )
            is AssignmentCommand.IssueDelegation -> validateDelegation(command)
            is AssignmentCommand.RevokeDelegation ->
                sourceVersion(
                    delegation(command.scope, command.sourceId),
                    command.expectedSourceVersion,
                )
            is AssignmentCommand.Disable -> validateDisable(command, fence)
        }
    }

    fun role(
        scope: StaffScope,
        id: UUID,
    ): DbRow = roleById(id).takeIf { it.scope() == scope } ?: fail("not_found")

    fun delegation(
        scope: StaffScope,
        id: UUID,
    ): DbRow =
        snapshot.delegations.singleOrNull { it["id"] == id.toString() && it.scope() == scope } ?: fail("not_found")

    private fun roleById(id: UUID): DbRow =
        snapshot.roles.singleOrNull { it["id"] == id.toString() } ?: fail("not_found")

    private fun sourceVersion(
        source: DbRow,
        version: Long,
    ) {
        if (source.long("version") != version) fail("version_conflict")
        if (source["status"] != "ACTIVE") fail("invalid_transition")
    }

    private fun validateAdmission(
        command: AssignmentCommand.Admit,
        fence: DbRow?,
    ) {
        if (fence?.get("status") == "ACTIVE") fail("invalid_transition")
        if (snapshot.memberships[command.scope.userId]?.active() == false) fail("forbidden")
        val proof =
            admissionVerifier.verify(snapshot.sql.connection, command.confirmationReference, snapshot.now)
                ?: fail("forbidden")
        val exactConfirmation =
            proof.scope == command.scope &&
                proof.intendedRole == command.intendedRole &&
                proof.confirmerTelegramId == identity.telegramUserId
        val freshConfirmation =
            proof.expectedEpoch == (fence?.long("admission_epoch") ?: 0L) &&
                proof.validUntil > snapshot.now &&
                proof.invitationId > (fence?.long("revoked_through_invitation_id") ?: 0L)
        if (!exactConfirmation || !freshConfirmation) {
            fail("forbidden")
        }
        // A fresh admission must not silently resurrect surviving sources from a damaged prior fence.
        if ((snapshot.roles + snapshot.delegations).any { it.scope() == command.scope && it["status"] == "ACTIVE" }) {
            fail("invalid_transition")
        }
        admissionProof = proof
    }

    private fun validateRole(command: AssignmentCommand.IssueRole) {
        requireAdmitted(command.scope)
        unused(command.sourceId)
        val policy = policies.role(command.role, command.policy)
        if (!snapshot.registered(policy.permissions)) fail("invalid_transition")
        if (command.selector.kind !in policy.selectors ||
            command.expiresAt?.let { it <= snapshot.now } == true
        ) {
            fail("validation_error")
        }
        regrant(command.scope, policy.permissions, command.regrantAcknowledgement)
    }

    private fun validateDelegation(command: AssignmentCommand.IssueDelegation) {
        requireAdmitted(command.scope)
        unused(command.sourceId)
        val recipient = role(command.scope, command.recipientAssignmentId)
        sourceVersion(recipient, command.recipientVersion)
        if (snapshot.liveRole(recipient) == null ||
            recipient["role_code"] != StaffRole.FLOOR_MANAGER.name ||
            recipient.selector().kind != SelectorKind.CLUB
        ) {
            fail("forbidden")
        }
        val issuer = roleById(command.issuerAssignmentId)
        sourceVersion(issuer, command.issuerVersion)
        if (snapshot.admission(issuer.scope())?.admissionVersion() !=
            command.expectedIssuerAdmission
        ) {
            fail("version_conflict")
        }
        if (command.expiresAt <= snapshot.now) fail("validation_error")
        val policy = policies.delegation(command.permission, command.policy)
        if (!snapshot.registered(policy.permissions)) fail("invalid_transition")
        regrant(command.scope, policy.permissions, command.regrantAcknowledgement)
    }

    private fun validateDisable(
        command: AssignmentCommand.Disable,
        fence: DbRow?,
    ) {
        if (fence == null || fence["status"] == "DISABLED") fail("invalid_transition")
        invitationWatermark =
            admissionVerifier
                .latestIssuedInvitationId(
                    snapshot.sql.connection,
                    command.scope.clientId,
                    command.scope.clubId,
                )?.takeIf { it >= 0 } ?: fail("invalid_transition")
        // The inactive store cannot turn off permissions in the mounted COMPAT authorization path.
        // Such a mixed disable requires a reviewed writer/reader cutover, never a partial result.
        val scope = command.scope
        if (snapshot.sql.row(
                "SELECT user_id FROM client_club_grants " +
                    "WHERE client_id=? AND club_id=? AND user_id=? AND is_active=TRUE LIMIT 1",
                scope.clientId,
                scope.clubId,
                scope.userId,
            ) != null ||
            snapshot.sql.row(
                "SELECT user_id FROM user_roles WHERE user_id=? " +
                    "AND (scope_type='GLOBAL' OR (scope_type='CLUB' AND scope_club_id=?)) LIMIT 1",
                scope.userId,
                scope.clubId,
            ) != null
        ) {
            fail("invalid_transition")
        }
    }

    private fun requireAdmitted(scope: StaffScope) {
        if (!snapshot.admitted(scope)) fail("forbidden")
    }

    private fun unused(id: UUID) {
        // IDs are terminal across source kinds, not just unique within one table.
        if ((snapshot.roles + snapshot.delegations).any { it["id"] == id.toString() } ||
            snapshot.sql.row("SELECT id FROM client_role_assignments WHERE id=?", id) != null ||
            snapshot.sql.row("SELECT id FROM client_permission_delegations WHERE id=?", id) != null
        ) {
            fail("invalid_transition")
        }
    }

    private fun regrant(
        scope: StaffScope,
        permissions: Set<String>,
        acknowledgement: Set<String>,
    ) {
        val revoked = mutableSetOf<String>()
        snapshot.roles.filter { it.scope() == scope && it["status"] == "REVOKED" }.forEach {
            revoked += policies.role(StaffRole.valueOf(checkNotNull(it["role_code"])), it.policy()).permissions
        }
        snapshot.delegations.filter { it.scope() == scope && it["status"] == "REVOKED" }.forEach {
            revoked += policies.delegation(checkNotNull(it["permission_code"]), it.policy()).permissions
        }
        revoked +=
            snapshot.sql
                .rows(
                    "SELECT permission_code FROM client_club_grants " +
                        "WHERE client_id=? AND club_id=? AND user_id=? AND is_active=FALSE",
                    scope.clientId,
                    scope.clubId,
                    scope.userId,
                ).map { checkNotNull(it["permission_code"]) }
        if (acknowledgement != permissions.intersect(revoked)) fail("validation_error")
    }
}
