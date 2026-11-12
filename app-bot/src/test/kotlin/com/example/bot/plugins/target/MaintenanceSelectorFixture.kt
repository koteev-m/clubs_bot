package com.example.bot.plugins.target

import com.example.bot.data.client.ClientAudience
import com.example.bot.data.client.bindings.BindingFence
import com.example.bot.data.client.bindings.BindingKind
import com.example.bot.data.client.bindings.BindingState
import com.example.bot.data.client.bindings.DurableBindingRead
import com.example.bot.data.client.bindings.DurableBindingSnapshot
import com.example.bot.data.client.bindings.DurableBindingSource
import com.example.bot.data.client.bindings.ServerSelectedBindingReference
import com.example.bot.plugins.TrustedBotConnection
import java.util.UUID

internal class MaintenanceSelectorFixture {
    val signing = TargetIdentityFixture()
    val connection = TrustedBotConnection("synthetic-shared-platform", ClientAudience.Platform) { signing.token }
    var authentication: MaintenanceAuthenticationBinding? = MaintenanceAuthenticationBinding(connection, 1)
    val authenticationSource = MaintenanceAuthenticationSource { authentication }
    val verifier = TargetIdentityVerifier(connection, clock = signing.clock, maintenance = authenticationSource)
    var owner: Long? = 7L
    var ownerReads = 0
    var onOwner: () -> Unit = {}
    var contextReads = 0
    var onContext: () -> Unit = {}
    var reads = 0
    var onRead: (Int) -> Unit = {}
    var business: BusinessMaintenanceReadAuthority? = null
    var businessReads = 0
    var reference =
        ServerSelectedBindingReference(
            UUID.randomUUID(),
            BindingFence(UUID.randomUUID(), 1, 1),
            780001,
            ClientAudience.Platform,
        )
    var selection: MaintenanceServerSelection? = MaintenanceServerSelection(checkNotNull(authentication), reference, 1)
    var snapshot =
        DurableBindingSnapshot(
            reference.rootId,
            reference.telegramBotId,
            BindingKind.PLATFORM,
            reference.fence,
            BindingState.PENDING,
            reference.audience,
            null,
            null,
            null,
        )
    var result: DurableBindingRead? = null
    val source =
        DurableBindingSource {
            reads++
            onRead(reads)
            result ?: DurableBindingRead.Inactive(snapshot)
        }
    val contexts =
        MaintenanceServerSelectionSource {
            contextReads++
            onContext()
            selection
        }
    val ownership =
        BusinessMaintenanceAuthorityProvider { _, _ ->
            businessReads++
            business
        }
    val ownerSource: () -> Long? = {
        ownerReads++
        onOwner()
        owner
    }

    fun selector(bindings: DurableBindingSource = source): TargetMaintenanceSelector =
        TargetMaintenanceSelector(
            connection,
            bindings,
            ownerSource,
            contexts,
            authenticationSource,
            ownership,
            signing.clock,
        )

    fun identity(): TargetMaintenanceIdentity = checkNotNull(verifier.verifyMaintenance(signing.headers(), ""))

    fun branded(clubs: Set<Long> = setOf(1001, 1002)) {
        reference = reference.copy(audience = ClientAudience.Client(101, clubs), mappingEpoch = 3)
        selection = MaintenanceServerSelection(checkNotNull(authentication), reference, 1)
        snapshot =
            snapshot.copy(
                kind = BindingKind.BRANDED,
                audience = reference.audience,
                mappingEpoch = 3,
                clientLifecycleVersion = 1,
            )
    }

    fun authority(
        client: Long = 101,
        clubs: Set<Long> = setOf(1001, 1002),
        actor: Long = 7,
        state: MaintenanceAuthorityState = MaintenanceAuthorityState.ACTIVE,
        version: Long = 1,
    ): BusinessMaintenanceReadAuthority =
        BusinessMaintenanceReadAuthority(UUID.randomUUID(), version, actor, client, clubs, state)
}
