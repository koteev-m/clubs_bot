package com.example.bot.data.client.bindings

import com.example.bot.data.client.onboarding.fail
import java.time.Instant

internal class BindingValidation(
    private val providers: BindingEvidenceProvider,
    private val ownerTelegramId: Long?,
) {
    fun identity(
        evidence: String,
        now: Instant,
    ): MaintenanceIdentity {
        val proof = providers.maintenance(evidence, now) ?: fail("forbidden")
        if (ownerTelegramId == null ||
            ownerTelegramId <= 0 ||
            proof.telegramUserId != ownerTelegramId
        ) {
            fail("forbidden")
        }
        requireBindingFresh(proof.authenticatedAt, proof.validUntil, now)
        return proof
    }

    fun command(
        command: BindingCommand,
        before: BindingView?,
    ) {
        if (command is BindingCommand.Create) {
            if (before != null) fail("invalid_transition")
            return
        }
        val current = before ?: fail("not_found")
        if (current.kind != BindingKind.PLATFORM ||
            current.clientId != null ||
            current.clubs.isNotEmpty()
        ) {
            fail("forbidden")
        }
        if (command.expected != current.fence) fail("version_conflict")
        if (current.fence.version == Long.MAX_VALUE ||
            current.fence.generation == Long.MAX_VALUE
        ) {
            fail("version_conflict")
        }
        if (command is BindingCommand.Replace) {
            if (current.status != BindingState.REVOKED ||
                command.bindingId == current.fence.bindingId
            ) {
                fail("invalid_transition")
            }
        } else {
            if (current.status == BindingState.REVOKED) fail("invalid_transition")
            when (command) {
                is BindingCommand.State ->
                    if (command.state !in setOf(BindingState.DISABLED, BindingState.REVOKED) ||
                        command.state == current.status
                    ) {
                        fail("invalid_transition")
                    }
                is BindingCommand.AttachReference -> if (current.credentialReference != null) fail("invalid_transition")
                else -> Unit
            }
        }
    }

    fun attestation(
        command: BindingCommand,
        before: BindingView?,
        now: Instant,
    ): Any? =
        when (command) {
            is BindingCommand.Create -> bot(command, now)
            is BindingCommand.AttachReference -> credential(command, checkNotNull(before), now)
            else -> null
        }

    private fun bot(
        command: BindingCommand.Create,
        now: Instant,
    ): BotIdentityEvidence {
        val proof = providers.bot(command, now) ?: fail("forbidden")
        if (proof.rootId != command.rootId ||
            proof.botId != command.botId ||
            proof.provenanceId != command.provenanceId
        ) {
            fail("forbidden")
        }
        requireBindingFresh(proof.checkedAt, proof.validUntil, now)
        return proof
    }

    private fun credential(
        command: BindingCommand.AttachReference,
        before: BindingView,
        now: Instant,
    ): CredentialReferenceEvidence {
        val proof = providers.credential(command, now) ?: fail("forbidden")
        val expected =
            CredentialReferenceEvidence(
                command.rootId,
                before.botId,
                command.referenceId,
                command.revision,
                command.materialId,
                command.attestationId,
                proof.checkedAt,
                proof.validUntil,
            )
        if (proof != expected) fail("forbidden")
        requireBindingFresh(proof.checkedAt, proof.validUntil, now)
        return proof
    }
}
