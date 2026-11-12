package com.example.bot.data.client.assignments

import com.example.bot.data.client.onboarding.canonical
import com.example.bot.data.client.onboarding.digest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Detach mutable caller collections before any database/dispatcher wait can change the accepted intent. */
internal fun AssignmentCommand.frozen(): AssignmentCommand =
    when (this) {
        is AssignmentCommand.IssueRole ->
            copy(
                selector = selector.copy(shifts = selector.shifts.toList()),
                regrantAcknowledgement = regrantAcknowledgement.toSet(),
            )
        is AssignmentCommand.IssueDelegation -> copy(regrantAcknowledgement = regrantAcknowledgement.toSet())
        else -> this
    }

/** Explicit canonical encoding: neither a toString() nor a saved historical authority response. */
internal fun AssignmentCommand.intentHash(): String =
    digest(
        canonical(
            buildJsonObject {
                put("protocol", "role-foundation-v1")
                put("client", scope.clientId)
                put("club", scope.clubId)
                put("user", scope.userId)
                put("expectedEpoch", expectedAdmission?.epoch)
                put("expectedRevision", expectedAdmission?.version)
                put("reason", reason)
                put("confirmed", confirmed)
                when (val command = this@intentHash) {
                    is AssignmentCommand.Admit -> {
                        put("operation", "admit")
                        put("role", command.intendedRole.name)
                        put("confirmation", command.confirmationReference)
                    }
                    is AssignmentCommand.IssueRole -> {
                        put("operation", "issue_role")
                        put("source", command.sourceId.toString())
                        put("role", command.role.name)
                        put("policyVersion", command.policy.version)
                        put("policyHash", command.policy.hash)
                        put("selector", command.selector.kind.name)
                        put("shifts", command.selector.encoded())
                        put("expires", command.expiresAt?.toString())
                        put("regrant", JsonArray(command.regrantAcknowledgement.sorted().map(::JsonPrimitive)))
                    }
                    is AssignmentCommand.RevokeRole -> {
                        put("operation", "revoke_role")
                        put("source", command.sourceId.toString())
                        put("sourceVersion", command.expectedSourceVersion)
                    }
                    is AssignmentCommand.IssueDelegation -> {
                        put("operation", "issue_delegation")
                        put("source", command.sourceId.toString())
                        put("recipient", command.recipientAssignmentId.toString())
                        put("recipientVersion", command.recipientVersion)
                        put("issuer", command.issuerAssignmentId.toString())
                        put("issuerVersion", command.issuerVersion)
                        put("issuerEpoch", command.expectedIssuerAdmission.epoch)
                        put("issuerRevision", command.expectedIssuerAdmission.version)
                        put("permission", command.permission)
                        put("policyVersion", command.policy.version)
                        put("policyHash", command.policy.hash)
                        put("expires", command.expiresAt.toString())
                        put("regrant", JsonArray(command.regrantAcknowledgement.sorted().map(::JsonPrimitive)))
                    }
                    is AssignmentCommand.RevokeDelegation -> {
                        put("operation", "revoke_delegation")
                        put("source", command.sourceId.toString())
                        put("sourceVersion", command.expectedSourceVersion)
                    }
                    is AssignmentCommand.Disable -> put("operation", "disable")
                }
            },
        ).toString(),
    )
