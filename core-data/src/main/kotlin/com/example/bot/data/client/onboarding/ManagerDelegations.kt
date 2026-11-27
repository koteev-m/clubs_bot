package com.example.bot.data.client.onboarding

import com.example.bot.data.client.assignments.AssignmentCommand
import com.example.bot.data.client.assignments.AssignmentPolicyCatalog
import com.example.bot.data.client.assignments.AssignmentSnapshot
import com.example.bot.data.client.assignments.MANAGE_OPERATIONS_TEAM
import com.example.bot.data.client.assignments.StaffRole
import com.example.bot.data.client.assignments.admissionVersion
import com.example.bot.data.client.assignments.liveManagerDelegations
import com.example.bot.data.client.assignments.managerDelegationPolicies
import com.example.bot.data.client.assignments.managerSourcePermissions
import com.example.bot.data.client.assignments.managerSourceRef
import com.example.bot.data.client.assignments.managerSources
import com.example.bot.data.client.assignments.scope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

/** The caller holds the client lock and stores delegation commands under the roster's revision. */
internal class ManagerDelegations(
    private val session: OnboardingSession,
) {
    private val writer = ManagerAssignmentWriter(session)

    fun list(floor: DbRow): JsonArray =
        JsonArray(
            session.liveManagerDelegations(floor).map {
                buildJsonObject {
                    put("id", it["id"])
                    put("permission", it["permission_code"])
                    put("version", it["version"])
                }
            },
        )

    fun issue(
        client: Long,
        club: Long,
        body: JsonObject,
    ) {
        body.only(
            listOf("action", "expectedVersion", "assignmentId", "expectedAssignmentVersion", "permission", "confirmed"),
        )
        if (body["confirmed"] != JsonPrimitive(true)) fail("validation_error")
        val permission = body.string("permission") ?: fail("validation_error")
        val policy = managerDelegationPolicies[permission] ?: fail("validation_error")
        val issuer = issuer(client, club)
        if (!session
                .managerSourcePermissions(
                    issuer,
                ).containsAll(policy.permissions + policy.authority)
        ) {
            fail("forbidden")
        }
        val floor = recipient(client, club, body)
        if (session
                .liveManagerDelegations(
                    floor,
                ).any { it["permission_code"] == permission }
        ) {
            fail("invalid_transition")
        }
        val snapshot = AssignmentSnapshot(session.sql, client, AssignmentPolicyCatalog(), session.now)
        if (!snapshot.registered(policy.permissions)) fail("invalid_transition")
        val scope = floor.scope()
        val id = UUID.randomUUID()
        val reason = "Configuration section delegated"
        val audit = writer.auditId(scope, "client.manager.delegation.issue", id, reason)
        writer.writer(snapshot, audit, issuer.managerSourceRef()).issue(
            AssignmentCommand.IssueDelegation(
                scope,
                checkNotNull(snapshot.admission(scope)).admissionVersion(),
                id,
                UUID.fromString(floor["id"]),
                floor.long("version"),
                UUID.fromString(issuer["id"]),
                issuer.long("version"),
                checkNotNull(snapshot.admission(issuer.scope())).admissionVersion(),
                permission,
                policy.reference,
                expiresAt = OPEN_ENDED_DELEGATION,
                reason = reason,
                confirmed = true,
            ),
        )
    }

    fun revoke(
        client: Long,
        club: Long,
        body: JsonObject,
    ) {
        body.only(
            listOf(
                "action",
                "expectedVersion",
                "assignmentId",
                "expectedAssignmentVersion",
                "delegationId",
                "expectedDelegationVersion",
                "confirmed",
            ),
        )
        if (body["confirmed"] != JsonPrimitive(true)) fail("validation_error")
        val issuer = issuer(client, club)
        val floor = recipient(client, club, body)
        val delegation =
            session.liveManagerDelegations(floor).singleOrNull { it["id"] == body.string("delegationId") }
                ?: fail("not_found")
        if (delegation.long("version") != body.id("expectedDelegationVersion")) fail("version_conflict")
        val snapshot = AssignmentSnapshot(session.sql, client, AssignmentPolicyCatalog(), session.now)
        val reason = "Configuration delegation revoked"
        val audit =
            writer.auditId(
                floor.scope(),
                "client.manager.delegation.revoke",
                UUID.fromString(delegation["id"]),
                reason,
            )
        val mutation = writer.writer(snapshot, audit, issuer.managerSourceRef())
        mutation.bump(mutation.revoke(emptyList(), listOf(delegation), reason))
    }

    private fun issuer(
        client: Long,
        club: Long,
    ): DbRow =
        session.managerSources(client, club).singleOrNull {
            it["role_code"] == StaffRole.CLUB_MANAGER.name &&
                MANAGE_OPERATIONS_TEAM in session.managerSourcePermissions(it)
        } ?: fail("not_found")

    private fun recipient(
        client: Long,
        club: Long,
        body: JsonObject,
    ): DbRow {
        val assignmentId = body.string("assignmentId")
        val row =
            writer.list(client, club).singleOrNull {
                it["id"] == assignmentId && it["role_code"] == StaffRole.FLOOR_MANAGER.name
            } ?: fail("not_found")
        if (row.long("version") != body.id("expectedAssignmentVersion")) fail("version_conflict")
        if (!writer.canRevoke(client, row)) fail("forbidden")
        return row
    }
}

private val OPEN_ENDED_DELEGATION: Instant = Instant.parse("9999-12-31T00:00:00Z")
