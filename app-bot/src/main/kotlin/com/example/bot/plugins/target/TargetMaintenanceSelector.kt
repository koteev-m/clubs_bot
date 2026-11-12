package com.example.bot.plugins.target

import com.example.bot.data.client.ClientAudience
import com.example.bot.data.client.bindings.BindingKind
import com.example.bot.data.client.bindings.BindingReadRejection
import com.example.bot.data.client.bindings.BindingState
import com.example.bot.data.client.bindings.DurableBindingRead
import com.example.bot.data.client.bindings.DurableBindingSnapshot
import com.example.bot.data.client.bindings.DurableBindingSource
import com.example.bot.plugins.TrustedBotConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Unwired read-only selection. A selected pin is never accepted by a mutation writer. */
internal class TargetMaintenanceSelector(
    private val authenticationConnection: TrustedBotConnection,
    private val bindings: DurableBindingSource,
    private val configuredPlatformOwnerId: () -> Long? = { null },
    private val selections: MaintenanceServerSelectionSource = MaintenanceServerSelectionSource { null },
    private val authentication: MaintenanceAuthenticationSource = MaintenanceAuthenticationSource { null },
    private val business: BusinessMaintenanceAuthorityProvider = BusinessMaintenanceAuthorityProvider { _, _ -> null },
    private val clock: Clock = Clock.systemUTC(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun select(identity: TargetMaintenanceIdentity?): MaintenanceSelection =
        withContext(dispatcher) {
            try {
                currentCoroutineContext().ensureActive()
                val actor = identity ?: stop(MaintenanceSelection.Denied)
                selectCurrent(actor)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: SelectionStopped) {
                failure.result
            } catch (_: Exception) {
                // No credential, provider error or private binding details escape on a failed selection.
                MaintenanceSelection.Error
            }
        }

    private suspend fun selectCurrent(identity: TargetMaintenanceIdentity): MaintenanceSelection {
        val started = clock.instant()
        fresh(identity, started, started)
        val selection = selections.current() ?: stop(MaintenanceSelection.Unavailable)
        trusted(selection, identity)
        val authority = authority(identity, selection)
        val first = read(selection)
        revalidate(identity, selection, authority, started)
        val last = read(selection)
        if (!sameSnapshot(first, last)) stop(MaintenanceSelection.Stale)
        revalidate(identity, selection, authority, started)
        currentCoroutineContext().ensureActive()
        val finished = clock.instant()
        fresh(identity, finished, started)
        return MaintenanceSelection.Selected(
            MaintenanceSnapshotPin(
                last,
                identity.telegramUserId,
                identity.authenticatedAt,
                identity.validUntil,
                finished,
                selection.revision,
                authority.source,
                authority.version,
            ),
        )
    }

    private fun fresh(
        identity: TargetMaintenanceIdentity,
        now: Instant,
        started: Instant,
    ) {
        val connectionValid =
            identity.belongsTo(authenticationConnection) &&
                authenticationConnection.audience is ClientAudience.Platform &&
                identity.telegramUserId > 0
        val fresh = identity.authenticatedAt <= now && now < identity.validUntil && now >= started
        val windowValid =
            identity.validUntil <= identity.authenticatedAt.plusSeconds(TargetInitDataCredentials.MAX_AGE_SECONDS)
        if (!connectionValid || !fresh || !windowValid) stop(MaintenanceSelection.Denied)
    }

    private fun trusted(
        selection: MaintenanceServerSelection,
        identity: TargetMaintenanceIdentity,
    ) {
        if (!selection.enabled || selection.revision <= 0) stop(MaintenanceSelection.Unavailable)
        val current = authentication.current() ?: stop(MaintenanceSelection.Unavailable)
        val live = current.enabled && current.revision > 0 && current.connection === authenticationConnection
        if (!live || selection.authentication !== current || !identity.matchesAuthentication(current)) {
            stop(MaintenanceSelection.Denied)
        }
    }

    private fun authority(
        identity: TargetMaintenanceIdentity,
        selection: MaintenanceServerSelection,
    ): AuthorityStamp =
        when (val scope = selection.reference.audience) {
            ClientAudience.Platform -> {
                val owner = configuredPlatformOwnerId()?.takeIf { it > 0 } ?: stop(MaintenanceSelection.Unavailable)
                if (owner != identity.telegramUserId) stop(MaintenanceSelection.Denied)
                AuthorityStamp(null, null, owner, null, emptySet())
            }
            is ClientAudience.Client -> businessAuthority(identity.telegramUserId, scope)
        }

    private fun businessAuthority(
        actor: Long,
        scope: ClientAudience.Client,
    ): AuthorityStamp {
        val proof = business.current(actor, scope.clientId) ?: stop(MaintenanceSelection.Unavailable)
        val exact = proof.telegramUserId == actor && proof.clientId == scope.clientId && proof.version > 0
        val finite =
            scope.allowedClubIds.isNotEmpty() &&
                proof.clubs.all { it > 0 } &&
                proof.clubs.containsAll(scope.allowedClubIds)
        if (!exact || !finite || proof.state != MaintenanceAuthorityState.ACTIVE) stop(MaintenanceSelection.Denied)
        return AuthorityStamp(proof.sourceId, proof.version, actor, proof.clientId, proof.clubs)
    }

    private suspend fun read(selection: MaintenanceServerSelection): DurableBindingSnapshot =
        when (val result = bindings.read(selection.reference)) {
            is DurableBindingRead.Inactive -> result.snapshot.also { validate(it, selection) }
            is DurableBindingRead.Revoked -> stop(MaintenanceSelection.Revoked)
            is DurableBindingRead.Rejected -> stop(rejected(result.reason))
            DurableBindingRead.Inconsistent, DurableBindingRead.ReadFailed -> stop(MaintenanceSelection.Error)
            DurableBindingRead.MissingSelection, DurableBindingRead.NotFound -> stop(MaintenanceSelection.Unavailable)
        }

    private fun validate(
        snapshot: DurableBindingSnapshot,
        selection: MaintenanceServerSelection,
    ) {
        val ref = selection.reference
        val identityMatches = snapshot.rootId == ref.rootId && snapshot.telegramBotId == ref.telegramBotId
        val pinsMatch =
            snapshot.fence == ref.fence &&
                snapshot.credential?.revision == ref.credentialRevision &&
                snapshot.mappingEpoch == ref.mappingEpoch
        val scopeMatches = sameMaintenanceScope(snapshot.audience, ref.audience)
        if (!identityMatches || !pinsMatch || !scopeMatches) stop(MaintenanceSelection.Stale)
        val positive = snapshot.telegramBotId > 0 && snapshot.fence.version > 0 && snapshot.fence.generation > 0
        if (!positive || (snapshot.credential?.revision ?: 1) <= 0) stop(MaintenanceSelection.Error)
        val inactive = snapshot.status in setOf(BindingState.PENDING, BindingState.DISABLED)
        val audience = snapshot.audience
        val shape =
            when (snapshot.kind) {
                BindingKind.PLATFORM -> audience is ClientAudience.Platform && snapshot.mappingEpoch == null
                BindingKind.BRANDED ->
                    audience is ClientAudience.Client &&
                        audience.allowedClubIds.isNotEmpty() &&
                        (snapshot.mappingEpoch ?: 0) > 0 &&
                        (snapshot.clientLifecycleVersion ?: 0) > 0
            }
        if (!inactive || !shape) stop(MaintenanceSelection.Error)
    }

    private fun revalidate(
        identity: TargetMaintenanceIdentity,
        selection: MaintenanceServerSelection,
        before: AuthorityStamp,
        started: Instant,
    ) {
        fresh(identity, clock.instant(), started)
        val current = selections.current() ?: stop(MaintenanceSelection.Unavailable)
        trusted(current, identity)
        if (!selection.sameSelection(current) ||
            authority(identity, current) != before
        ) {
            stop(MaintenanceSelection.Stale)
        }
    }

    private fun sameSnapshot(
        left: DurableBindingSnapshot,
        right: DurableBindingSnapshot,
    ): Boolean = left.copy(audience = right.audience) == right && sameMaintenanceScope(left.audience, right.audience)

    private fun rejected(reason: BindingReadRejection): MaintenanceSelection =
        when (reason) {
            BindingReadRejection.FENCE, BindingReadRejection.MAPPING_EPOCH,
            BindingReadRejection.CREDENTIAL_REVISION,
            -> MaintenanceSelection.Stale
            else -> MaintenanceSelection.Denied
        }

    private data class AuthorityStamp(
        val source: UUID?,
        val version: Long?,
        val actor: Long?,
        val client: Long?,
        val clubs: Set<Long>,
    )
}

private class SelectionStopped(
    val result: MaintenanceSelection,
) : RuntimeException()

private fun stop(result: MaintenanceSelection): Nothing = throw SelectionStopped(result)
