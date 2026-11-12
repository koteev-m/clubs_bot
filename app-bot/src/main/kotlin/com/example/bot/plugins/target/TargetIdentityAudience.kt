package com.example.bot.plugins.target

import com.example.bot.data.client.CanonicalClientAudienceSnapshot
import com.example.bot.data.client.ClientAudience
import com.example.bot.plugins.TrustedBotConnection
import java.time.Instant

/** Server-checked resource context, not proof of membership, ownership or permission. */
internal data class TargetClubContext(
    val clientId: Long,
    val clubId: Long,
) {
    init {
        require(clientId > 0 && clubId > 0) { "Invalid TARGET context" }
    }
}

/** Immutable server snapshot. Rotation/rebinding must replace the connection or increment version. */
internal class TargetAudienceBinding(
    val connection: TrustedBotConnection,
    val audience: ClientAudience,
    val version: Long,
    val active: Boolean,
    val canonicalSnapshot: CanonicalClientAudienceSnapshot? = null,
) {
    fun permits(
        expected: TrustedBotConnection,
        context: TargetClubContext,
    ): Boolean {
        val client = audience as? ClientAudience.Client ?: return false
        val base = expected.audience
        val withinBase =
            base is ClientAudience.Platform ||
                (
                    base is ClientAudience.Client &&
                        base.clientId == client.clientId &&
                        base.allowedClubIds.containsAll(client.allowedClubIds)
                )
        return active &&
            version > 0 &&
            connection === expected &&
            withinBase &&
            client.clientId == context.clientId &&
            context.clubId in client.allowedClubIds
    }

    fun sameSnapshot(other: TargetAudienceBinding): Boolean {
        val client = audience as? ClientAudience.Client
        val otherClient = other.audience as? ClientAudience.Client
        val sameCanonical =
            canonicalSnapshot?.let { snapshot -> other.canonicalSnapshot?.let(snapshot::sameSnapshot) == true }
                ?: (other.canonicalSnapshot == null)
        return connection === other.connection &&
            version == other.version &&
            active == other.active &&
            client?.clientId == otherClient?.clientId &&
            client?.allowedClubIds == otherClient?.allowedClubIds &&
            sameCanonical
    }
}

/** Read-only trusted server metadata; no caller body/query/role may be used as an audience binding. */
internal fun interface TargetAudienceProvider {
    fun current(
        connection: TrustedBotConnection,
        context: TargetClubContext,
    ): TargetAudienceBinding?
}

/** No Principal/roles/permissions/ownership/admission interface and no serializable credential payload. */
internal sealed interface TargetVerifiedIdentity {
    val telegramUserId: Long
    val connectionKey: String
    val context: TargetClubContext
    val audienceVersion: Long
    val authenticatedAt: Instant
    val validUntil: Instant

    /** Structural binding only; future actions must reverify freshness and current server metadata. */
    fun matchesContext(
        connection: TrustedBotConnection,
        context: TargetClubContext,
    ): Boolean
}
