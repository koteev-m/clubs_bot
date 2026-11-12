package com.example.bot.plugins.target

import com.example.bot.data.client.ClientAudience
import com.example.bot.data.client.bindings.ControlPublicationStatus
import com.example.bot.data.client.bindings.InactiveMaintenanceAction
import com.example.bot.data.client.bindings.MaintenanceControlHead
import java.util.Collections
import java.util.UUID

/** Protected configuration input; no implementation delivers or mutates real configuration. */
internal class MaintenanceProtectedContext(
    val authentication: MaintenanceAuthenticationBinding,
    val configuredOwnerId: Long,
    val contextId: UUID,
)

/** Finite single-root/incarnation manifest. Construction/digest alone confers no authority. */
internal class MaintenanceControlBundle(
    val head: MaintenanceControlHead,
    val context: MaintenanceProtectedContext,
    val selection: MaintenanceServerSelection,
    operations: Set<InactiveMaintenanceAction>,
) {
    val operations: Set<InactiveMaintenanceAction> = Collections.unmodifiableSet(HashSet(operations))
    val digest: String =
        maintenanceHash(
            listOf(
                "maintenance-control-bundle-v1",
                head.scope,
                head.version,
                head.generation,
                head.manifestId,
                context.contextId,
                context.configuredOwnerId,
                context.authentication.revision,
                selection.revision,
                selection.reference.rootId,
                selection.reference.telegramBotId,
                selection.reference.fence.bindingId,
                this.operations.map { it.name }.sorted(),
            ).joinToString(":"),
        )

    fun admissible(): Boolean {
        val reference = selection.reference
        val published = head.status == ControlPublicationStatus.ENABLED && head.manifestDigest == digest
        val authentication = context.authentication
        val live = authentication.enabled && authentication.revision > 0 && context.configuredOwnerId > 0
        val exact = selection.enabled && selection.revision > 0 && selection.authentication === authentication
        val platform =
            authentication.connection.audience is ClientAudience.Platform &&
                reference.audience is ClientAudience.Platform
        val root = reference.rootId == head.scope.rootId && reference.mappingEpoch == null
        return published && live && exact && platform && root && operations.isNotEmpty()
    }

    override fun toString(): String = "MaintenanceControlBundle(protected; not-authorized)"
}

/** Missing production protected configuration and intent source: default absent. */
internal fun interface MaintenanceControlBundleSource {
    fun current(): MaintenanceControlBundle?
}
