package com.example.bot.data.client.assignments

import com.example.bot.data.client.onboarding.fail
import java.sql.Connection
import java.time.Instant

/** Produced only by a trusted signed-Telegram/audience verifier, called again after database lock waits. */
internal data class VerifiedAssignmentIdentity(
    val telegramUserId: Long,
    val connectionKey: String,
    val authenticatedAt: Instant,
    val validUntil: Instant,
) {
    fun requireFresh(now: Instant) {
        val validIdentity =
            telegramUserId > 0 && connectionKey.isNotBlank() && connectionKey.length <= MAX_CONNECTION_LENGTH
        val validWindow =
            authenticatedAt <= now && validUntil > now && validUntil <= authenticatedAt.plusSeconds(MAX_AGE_SECONDS)
        if (!validIdentity || !validWindow) {
            fail("forbidden")
        }
    }

    companion object {
        private const val MAX_AGE_SECONDS = 300L
        private const val MAX_CONNECTION_LENGTH = 64
    }
}

/** No default crypto shortcut or production identity adapter is supplied. Evidence must never be logged. */
internal fun interface AssignmentIdentityVerifier {
    fun verify(
        evidence: String,
        now: Instant,
    ): VerifiedAssignmentIdentity?
}

/** The provider must read current ownership and prove an exact club; legacy creator/role names are insufficient. */
internal interface AssignmentOwnershipProvider {
    fun ownerReference(
        connection: Connection,
        actorId: Long,
        clientId: Long,
        clubId: Long,
    ): String?

    /** null means unknown and MUST deny a mutation of the subject. */
    fun protectedSubject(
        connection: Connection,
        scope: StaffScope,
    ): Boolean?

    fun canDisable(
        connection: Connection,
        actorId: Long,
        scope: StaffScope,
    ): Boolean = false

    fun canRevokeDelegation(
        connection: Connection,
        actorId: Long,
        scope: StaffScope,
    ): Boolean = false
}

internal data class TargetAdmissionConfirmation(
    val invitationId: Long,
    val scope: StaffScope,
    val intendedRole: StaffRole,
    val expectedEpoch: Long,
    val confirmerTelegramId: Long,
    val validUntil: Instant,
)

/** A future TARGET protocol must verify signed recipient claim, confirmation and current invitation state. */
internal fun interface TargetAdmissionVerifier {
    fun verify(
        connection: Connection,
        reference: String,
        now: Instant,
    ): TargetAdmissionConfirmation?

    /** Includes unclaimed TARGET invitations; absence of an authoritative watermark blocks full disable. */
    fun latestIssuedInvitationId(
        connection: Connection,
        clientId: Long,
        clubId: Long,
    ): Long? = null
}

internal object DenyAssignmentProviders : AssignmentOwnershipProvider {
    val identity = AssignmentIdentityVerifier { _, _ -> null }
    val admission = TargetAdmissionVerifier { _, _, _ -> null }

    override fun ownerReference(
        connection: Connection,
        actorId: Long,
        clientId: Long,
        clubId: Long,
    ): String? = null

    override fun protectedSubject(
        connection: Connection,
        scope: StaffScope,
    ): Boolean? = null
}
