package com.example.bot.plugins.target

import com.example.bot.data.client.ClientAudience
import com.example.bot.data.client.bindings.BindingStorageSchema
import com.example.bot.data.client.bindings.JdbcControlHeadLease
import com.example.bot.data.client.onboarding.OnboardingFailure
import java.sql.Connection

/** Local opt-in composition only. No runtime registration or protected bundle publisher. */
internal class DbControlMaintenanceLeaseSource(
    private val schema: BindingStorageSchema,
    private val bundles: MaintenanceControlBundleSource = MaintenanceControlBundleSource { null },
) : MaintenanceMutationLeaseSource {
    override fun acquire(request: MaintenanceMutationRequest): MaintenanceMutationStateLease? {
        val bundle = bundles.current() ?: return null
        return if (bundle.admissible() &&
            admitted(bundle, request)
        ) {
            ControlStateLease(schema, bundle, bundles, request)
        } else {
            null
        }
    }

    private fun admitted(
        bundle: MaintenanceControlBundle,
        request: MaintenanceMutationRequest,
    ): Boolean {
        val expected = request.intent.expected
        val reference = bundle.selection.reference
        val actor = request.actorTelegramId == bundle.context.configuredOwnerId
        val operation = request.intent.action in bundle.operations
        val context = request.contextRevision == bundle.selection.revision
        val scope = expected.audience is ClientAudience.Platform && expected.mappingEpoch == null
        val root = expected.rootId == reference.rootId && expected.telegramBotId == reference.telegramBotId
        val incarnation = expected.fence.bindingId == reference.fence.bindingId
        return actor &&
            operation &&
            context &&
            scope &&
            root &&
            incarnation &&
            request.keyHash.matches(Regex("[0-9a-f]{64}"))
    }
}

private class ControlStateLease(
    schema: BindingStorageSchema,
    private val bundle: MaintenanceControlBundle,
    private val bundles: MaintenanceControlBundleSource,
    request: MaintenanceMutationRequest,
) : MaintenanceMutationStateLease {
    override val controlHead = JdbcControlHeadLease(schema, bundle.head)
    override val intentBinding =
        maintenanceHash(
            "control-intent-v1:${bundle.head.scope}:${bundle.head.generation}:${bundle.digest}:${request.keyHash}",
        )
    private val selection =
        MaintenanceServerSelection(
            bundle.context.authentication,
            bundle.selection.reference.copy(
                fence = request.intent.expected.fence,
                credentialRevision =
                    request.intent.expected.credential
                        ?.revision,
            ),
            bundle.selection.revision,
        )

    override fun current(connection: Connection): MaintenanceMutationState {
        if (bundles.current() !== bundle || !bundle.admissible()) throw OnboardingFailure("forbidden")
        val current = controlHead.verify(connection)
        return MaintenanceMutationState(
            bundle.context.authentication,
            selection,
            bundle.context.configuredOwnerId,
            current.scope.authorityConnectionId,
            checkNotNull(current.manifestId),
        )
    }

    override fun close() = controlHead.close()
}
