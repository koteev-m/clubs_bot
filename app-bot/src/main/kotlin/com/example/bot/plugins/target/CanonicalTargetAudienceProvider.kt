package com.example.bot.plugins.target

import com.example.bot.data.client.CanonicalClientAudienceRepository
import com.example.bot.data.client.ClientAudience
import com.example.bot.plugins.TrustedBotConnection
import kotlinx.coroutines.CancellationException

/** Current explicit canonical server configuration. No registry or lifecycle writer is supplied here. */
internal fun interface CanonicalTargetBindingSource {
    fun current(): TargetAudienceBinding?
}

/**
 * Unwired blocking provider: run on an IO dispatcher. A pinned server binding is necessary,
 * but is never inferred from canonical DB resources or from a user's membership.
 */
internal class CanonicalTargetAudienceProvider(
    private val pinned: TargetAudienceBinding,
    private val mappings: CanonicalClientAudienceRepository,
    private val bindings: CanonicalTargetBindingSource = CanonicalTargetBindingSource { null },
) : TargetAudienceProvider {
    override fun current(
        connection: TrustedBotConnection,
        context: TargetClubContext,
    ): TargetAudienceBinding? =
        try {
            readCurrent(connection, context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // DB/configuration failure cannot become an audience fallback or leak credentials.
            null
        }

    private fun readCurrent(
        connection: TrustedBotConnection,
        context: TargetClubContext,
    ): TargetAudienceBinding? {
        // Branded connections require a future durable binding contract, not this canonical adapter.
        require(connection === pinned.connection && connection.audience is ClientAudience.Platform)
        require(pinned.canonicalSnapshot == null && pinned.permits(connection, context))
        val before = requireNotNull(bindings.current())
        require(pinned.sameSnapshot(before) && before.permits(connection, context))
        val audience = before.audience as ClientAudience.Client
        val snapshot = requireNotNull(mappings.read(audience))
        require(snapshot.clientId == audience.clientId && snapshot.mappingCreatedAt.keys == audience.allowedClubIds)
        val after = requireNotNull(bindings.current())
        require(pinned.sameSnapshot(after) && after.permits(connection, context))
        return TargetAudienceBinding(connection, audience, before.version, true, snapshot)
    }
}
