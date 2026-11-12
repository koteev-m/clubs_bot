package com.example.bot.data.client.assignments

import com.example.bot.data.client.onboarding.DbRow
import com.example.bot.data.client.onboarding.OnboardingSql
import com.example.bot.data.client.onboarding.fail
import com.example.bot.data.client.onboarding.long

internal data class AssignmentReceipt(
    val actorId: Long,
    val connectionKey: String,
    val keyHash: String,
)

/** Invoked only inside the actor/client-serialized transaction, after complete validation. */
internal class AssignmentSourceWriter(
    private val snapshot: AssignmentSnapshot,
    private val receipt: AssignmentReceipt,
    private val auditId: Long,
    private val authorityReference: String,
) {
    private val sql: OnboardingSql get() = snapshot.sql

    fun issue(command: AssignmentCommand.IssueRole) {
        val scope = command.scope
        sql.update(
            "INSERT INTO client_role_assignments(id,client_id,club_id,user_id,role_code,policy_version,policy_hash," +
                "selector_kind,selector_json,admission_epoch,status,version,issuer_authority_ref,created_by," +
                "command_connection_key,command_key_hash,created_audit_id,last_audit_id," +
                "expires_at,created_at,updated_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,'ACTIVE',1,?,?,?,?,?,?,?,?,?)",
            command.sourceId,
            scope.clientId,
            scope.clubId,
            scope.userId,
            command.role.name,
            command.policy.version,
            command.policy.hash,
            command.selector.kind.name,
            command.selector.encoded(),
            command.expectedAdmission.epoch,
            authorityReference,
            receipt.actorId,
            receipt.connectionKey,
            receipt.keyHash,
            auditId,
            auditId,
            command.expiresAt,
            snapshot.now,
            snapshot.now,
        )
        bump(setOf(scope))
    }

    fun issue(command: AssignmentCommand.IssueDelegation) {
        val scope = command.scope
        sql.update(
            "INSERT INTO client_permission_delegations(id,client_id,club_id,user_id,permission_code," +
                "recipient_assignment_id,issuer_assignment_id,issuer_user_id,policy_version,policy_hash," +
                "admission_epoch,issuer_admission_epoch,status,version,issuer_authority_ref,created_by," +
                "command_connection_key,command_key_hash,created_audit_id,last_audit_id," +
                "expires_at,created_at,updated_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE',1,?,?,?,?,?,?,?,?,?)",
            command.sourceId,
            scope.clientId,
            scope.clubId,
            scope.userId,
            command.permission,
            command.recipientAssignmentId,
            command.issuerAssignmentId,
            receipt.actorId,
            command.policy.version,
            command.policy.hash,
            command.expectedAdmission.epoch,
            command.expectedIssuerAdmission.epoch,
            authorityReference,
            receipt.actorId,
            receipt.connectionKey,
            receipt.keyHash,
            auditId,
            auditId,
            command.expiresAt,
            snapshot.now,
            snapshot.now,
        )
        bump(setOf(scope))
    }

    /** Returns all affected subjects, including dependency recipients, before their revisions are bumped. */
    fun revoke(
        roles: List<DbRow>,
        delegations: List<DbRow>,
        reason: String,
    ): Set<StaffScope> {
        val roleIds = roles.map { it["id"] }.toSet()
        val dependent =
            snapshot.delegations.filter {
                it["status"] == "ACTIVE" &&
                    (it["issuer_assignment_id"] in roleIds || it["recipient_assignment_id"] in roleIds)
            }
        val allDelegations = (delegations + dependent).distinctBy { it["id"] }
        roles.forEach { revoke("client_role_assignments", it, reason) }
        allDelegations.forEach { revoke("client_permission_delegations", it, reason) }
        return (roles + allDelegations).map { it.scope() }.toSet()
    }

    fun bump(scopes: Set<StaffScope>) {
        scopes.sortedWith(compareBy(StaffScope::clubId, StaffScope::userId)).forEach { scope ->
            val version = snapshot.admission(scope)?.admissionVersion() ?: fail("invalid_transition")
            if (sql.update(
                    "UPDATE client_club_access_state SET version=version+1,updated_at=? " +
                        "WHERE client_id=? AND club_id=? AND user_id=? AND admission_epoch=? AND version=?",
                    snapshot.now,
                    scope.clientId,
                    scope.clubId,
                    scope.userId,
                    version.epoch,
                    version.version,
                ) != 1
            ) {
                fail("version_conflict")
            }
        }
    }

    private fun revoke(
        table: String,
        row: DbRow,
        reason: String,
    ) {
        // The table argument is a private constant chosen above, never caller-controlled SQL.
        if (sql.update(
                "UPDATE $table SET status='REVOKED',version=version+1,updated_at=?,revoked_at=?,revoked_by=?," +
                    "revoke_reason=?,last_audit_id=? WHERE id=? AND version=? AND admission_epoch=? " +
                    "AND status='ACTIVE'",
                snapshot.now,
                snapshot.now,
                receipt.actorId,
                reason,
                auditId,
                java.util.UUID.fromString(row["id"]),
                row.long("version"),
                row.long("admission_epoch"),
            ) != 1
        ) {
            fail("version_conflict")
        }
    }
}
