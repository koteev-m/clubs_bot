package com.example.bot.plugins.target

import com.example.bot.data.client.CanonicalClientAudienceRepository
import com.example.bot.data.client.CanonicalClientAudienceSnapshot
import com.example.bot.data.client.ClientAudience
import com.example.bot.plugins.TrustedBotConnection
import com.example.bot.webapp.TEST_BOT_TOKEN
import java.time.Instant

internal class CanonicalTargetAudienceFixture {
    val identity = TargetIdentityFixture()
    val context = identity.context
    val connection = TrustedBotConnection("synthetic-canonical", ClientAudience.Platform) { TEST_BOT_TOKEN }
    val audience = ClientAudience.Client(101, setOf(1001, 1002))
    val pinned = TargetAudienceBinding(connection, audience, 4, true)
    var current: TargetAudienceBinding? = pinned
    var canonical: CanonicalClientAudienceSnapshot? = snapshot()
    var reads = 0
    var onRead: () -> Unit = {}
    var onBinding: () -> Unit = {}
    val repository =
        CanonicalClientAudienceRepository { requested ->
            check(requested === audience)
            reads++
            onRead()
            canonical
        }
    val bindings =
        CanonicalTargetBindingSource {
            onBinding()
            current
        }
    val provider = CanonicalTargetAudienceProvider(pinned, repository, bindings)
    val verifier = TargetIdentityVerifier(connection, provider, identity.clock)

    fun snapshot(
        client: Long = 101,
        lifecycle: Long = 1,
        clubs: Set<Long> = setOf(1001, 1002),
        createdAt: Instant = Instant.EPOCH,
    ): CanonicalClientAudienceSnapshot =
        CanonicalClientAudienceSnapshot(
            client,
            lifecycle,
            clubs.associateWith {
                createdAt
            },
        )

    fun read(): TargetAudienceBinding? = provider.current(connection, context)

    fun verify(): TargetVerifiedIdentity? = verifier.verify(identity.headers(), "", context)
}
