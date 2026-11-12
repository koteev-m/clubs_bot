package com.example.bot.data.client.bindings

import com.example.bot.data.client.onboarding.fail
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

enum class BindingState { PENDING, ACTIVE, DISABLED, REVOKED }

enum class BindingKind { PLATFORM, BRANDED }

data class BindingFence(
    val bindingId: UUID,
    val version: Long,
    val generation: Long,
)

/** Metadata only. Never a Principal, audience admission, permission contribution or secret handle. */
internal data class BindingView(
    val rootId: UUID,
    val botId: Long,
    val kind: BindingKind,
    val clientId: Long?,
    val fence: BindingFence,
    val status: BindingState,
    val credentialReference: UUID?,
    val clubs: List<Long>,
)

internal sealed interface BindingCommand {
    val rootId: UUID
    val expected: BindingFence?

    data class Create(
        override val rootId: UUID,
        val bindingId: UUID,
        val botId: Long,
        val provenanceId: UUID,
    ) : BindingCommand {
        override val expected: BindingFence? = null
    }

    data class State(
        override val rootId: UUID,
        override val expected: BindingFence,
        val state: BindingState,
    ) : BindingCommand

    data class Replace(
        override val rootId: UUID,
        override val expected: BindingFence,
        val bindingId: UUID,
        val provenanceId: UUID,
    ) : BindingCommand

    data class AttachReference(
        override val rootId: UUID,
        override val expected: BindingFence,
        val referenceId: UUID,
        val revision: Long,
        val materialId: UUID,
        val attestationId: UUID,
    ) : BindingCommand

    data class RetireMaterial(
        override val rootId: UUID,
        override val expected: BindingFence,
        val materialId: UUID,
    ) : BindingCommand
}

internal enum class BindingResolutionStatus { UNKNOWN, COMPLETED_CURRENT, COMPLETED_STALE }

internal data class BindingResolution(
    val status: BindingResolutionStatus,
    val current: BindingView?,
)

internal data class BindingResult(
    val replayed: Boolean,
    val current: BindingView,
)

internal fun BindingCommand.validate() {
    expected?.let { if (it.version <= 0 || it.generation <= 0) fail("validation_error") }
    if (this is BindingCommand.Create && botId <= 0) fail("validation_error")
    if (this is BindingCommand.AttachReference && revision <= 0) fail("validation_error")
}

/** All command fields are immutable UUIDs/enums/numbers; no raw evidence, key or token is serialized. */
internal fun BindingCommand.intentHash(): String = bindingDigest("binding-foundation-v1:" + toString())

internal fun bindingDigest(value: String): String =
    MessageDigest
        .getInstance(
            "SHA-256",
        ).digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/** Transaction-verified provider attestation; never obtained from a read-only pin. */
data class MaintenanceIdentity(
    val telegramUserId: Long,
    val connectionId: UUID,
    val authorityReference: UUID,
    val authenticatedAt: Instant,
    val validUntil: Instant,
)

internal data class BotIdentityEvidence(
    val rootId: UUID,
    val botId: Long,
    val provenanceId: UUID,
    val checkedAt: Instant,
    val validUntil: Instant,
)

internal data class CredentialReferenceEvidence(
    val rootId: UUID,
    val botId: Long,
    val referenceId: UUID,
    val revision: Long,
    val materialId: UUID,
    val attestationId: UUID,
    val checkedAt: Instant,
    val validUntil: Instant,
)

/** No production adapter. Caller-selected IDs, role names and AppConfig strings are not attestations. */
internal interface BindingEvidenceProvider {
    fun maintenance(
        evidence: String,
        now: Instant,
    ): MaintenanceIdentity? = null

    fun bot(
        command: BindingCommand.Create,
        now: Instant,
    ): BotIdentityEvidence? = null

    fun credential(
        command: BindingCommand.AttachReference,
        now: Instant,
    ): CredentialReferenceEvidence? = null
}

internal fun requireBindingFresh(
    at: Instant,
    until: Instant,
    now: Instant,
) {
    if (at > now || now >= until) fail("forbidden")
    if (until > at.plusSeconds(MAX_BINDING_AGE_SECONDS) || until <= at) fail("forbidden")
}

private const val MAX_BINDING_AGE_SECONDS = 300L
