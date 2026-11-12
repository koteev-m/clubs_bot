package com.example.bot.plugins.target

import com.example.bot.data.client.ClientAudience
import com.example.bot.data.client.bindings.DurableBindingSnapshot
import com.example.bot.data.client.bindings.ServerSelectedBindingReference
import java.time.Instant
import java.util.Collections
import java.util.UUID

/** Immutable server intent, never constructed from HTTP/body/query hints. No production provider. */
internal class MaintenanceServerSelection(
    val authentication: MaintenanceAuthenticationBinding,
    val reference: ServerSelectedBindingReference,
    val revision: Long,
    val enabled: Boolean = true,
) {
    fun sameSelection(other: MaintenanceServerSelection): Boolean =
        authentication === other.authentication &&
            revision == other.revision &&
            enabled == other.enabled &&
            reference.rootId == other.reference.rootId &&
            reference.fence == other.reference.fence &&
            reference.telegramBotId == other.reference.telegramBotId &&
            reference.mappingEpoch == other.reference.mappingEpoch &&
            reference.credentialRevision == other.reference.credentialRevision &&
            sameMaintenanceScope(reference.audience, other.reference.audience)
}

internal fun interface MaintenanceServerSelectionSource {
    fun current(): MaintenanceServerSelection?
}

internal enum class MaintenanceAuthorityState { ACTIVE, INACTIVE, REVOKED }

/** A future independent ownership provider must attest this exact actor/client/finite set. */
internal class BusinessMaintenanceReadAuthority(
    val sourceId: UUID,
    val version: Long,
    val telegramUserId: Long,
    val clientId: Long,
    clubs: Set<Long>,
    val state: MaintenanceAuthorityState = MaintenanceAuthorityState.ACTIVE,
) {
    val clubs: Set<Long> = Collections.unmodifiableSet(HashSet(clubs))
}

/** Default absent; membership, immutable club creator and legacy OWNER/GLOBAL are insufficient. */
internal fun interface BusinessMaintenanceAuthorityProvider {
    fun current(
        telegramUserId: Long,
        clientId: Long,
    ): BusinessMaintenanceReadAuthority?
}

/** Diagnostic pin only. No implementation of identity, Principal, permission or writer proof. */
internal class MaintenanceSnapshotPin(
    val binding: DurableBindingSnapshot,
    val actorTelegramId: Long,
    val authenticatedAt: Instant,
    val validUntil: Instant,
    val selectedAt: Instant,
    val contextRevision: Long,
    val authoritySourceId: UUID?,
    val authorityVersion: Long?,
) {
    override fun toString(): String = "MaintenanceSnapshotPin(read-only; not-mutation-authority)"
}

internal sealed interface MaintenanceSelection {
    data class Selected(
        val pin: MaintenanceSnapshotPin,
    ) : MaintenanceSelection

    data object Denied : MaintenanceSelection

    data object Stale : MaintenanceSelection

    data object Revoked : MaintenanceSelection

    data object Unavailable : MaintenanceSelection

    data object Error : MaintenanceSelection
}

internal fun sameMaintenanceScope(
    left: ClientAudience,
    right: ClientAudience,
): Boolean =
    when {
        left is ClientAudience.Platform -> right is ClientAudience.Platform
        left is ClientAudience.Client && right is ClientAudience.Client ->
            left.clientId == right.clientId && left.allowedClubIds == right.allowedClubIds
        else -> false
    }
