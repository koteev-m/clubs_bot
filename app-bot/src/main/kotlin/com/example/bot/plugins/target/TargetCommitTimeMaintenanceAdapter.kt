package com.example.bot.plugins.target

import com.example.bot.data.client.ClientAudience
import com.example.bot.data.client.bindings.BindingMaintenanceAuthorization
import com.example.bot.data.client.bindings.BindingMaintenanceLease
import com.example.bot.data.client.bindings.BindingStorageSchema
import com.example.bot.data.client.bindings.InactiveMaintenanceAction
import com.example.bot.data.client.bindings.InactiveMaintenanceIntent
import com.example.bot.data.client.bindings.JdbcCommitTimeBindingMaintenance
import com.example.bot.data.client.bindings.MaintenanceIdentity
import com.example.bot.data.client.bindings.MaintenanceMutationOutcome
import com.example.bot.data.client.onboarding.OnboardingFailure
import com.example.bot.plugins.TrustedBotConnection
import io.ktor.http.Headers
import java.sql.Connection
import java.time.Clock
import java.util.UUID
import javax.sql.DataSource

/** Authoritative current server state, not a request DTO or a registry/provisioning implementation. */
internal class MaintenanceMutationState(
    val authentication: MaintenanceAuthenticationBinding,
    val selection: MaintenanceServerSelection,
    val ownerTelegramId: Long?,
    val authorityConnectionId: UUID,
    val authorityReference: UUID,
) {
    fun sameState(other: MaintenanceMutationState): Boolean =
        authentication === other.authentication &&
            selection.sameSelection(other.selection) &&
            ownerTelegramId == other.ownerTelegramId &&
            authorityConnectionId == other.authorityConnectionId &&
            authorityReference == other.authorityReference
}

/** All relevant auth/config/selection writers must take the corresponding exclusive fence before changing state. */
internal interface MaintenanceMutationStateLease : AutoCloseable {
    val controlHead: com.example.bot.data.client.bindings.JdbcControlHeadLease? get() = null

    val intentBinding: String? get() = null

    fun current(connection: Connection): MaintenanceMutationState?
}

/** Missing in production. A free-running current() callback is NOT a valid implementation of this lease. */
internal fun interface MaintenanceMutationLeaseSource {
    fun acquire(request: MaintenanceMutationRequest): MaintenanceMutationStateLease?
}

/** Immutable expected command data; only a trusted current provider can admit it. Not a bearer credential. */
internal data class MaintenanceMutationRequest(
    val intent: InactiveMaintenanceIntent,
    val keyHash: String,
    val actorTelegramId: Long,
    val contextRevision: Long,
) {
    override fun toString(): String = "MaintenanceMutationRequest(expected-data; not-authorized)"
}

/** Local opt-in only. Neither the read pin nor successful read selection is a write credential. */
internal class TargetCommitTimeMaintenanceAdapter(
    private val dataSource: DataSource,
    private val schema: BindingStorageSchema,
    private val connection: TrustedBotConnection,
    private val verifier: TargetIdentityVerifier,
    private val leases: MaintenanceMutationLeaseSource = MaintenanceMutationLeaseSource { null },
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun execute(
        pin: MaintenanceSnapshotPin,
        action: InactiveMaintenanceAction,
        key: String,
        headers: Headers,
        rawQuery: String = "",
        materialId: UUID? = null,
    ): MaintenanceMutationOutcome {
        val intent = InactiveMaintenanceIntent(pin.binding, action, materialId)
        val authorization =
            BindingMaintenanceAuthorization { normalized ->
                val request =
                    MaintenanceMutationRequest(
                        normalized,
                        maintenanceHash(key),
                        pin.actorTelegramId,
                        pin.contextRevision,
                    )
                val stateLease = leases.acquire(request) ?: forbiddenMaintenance()
                SignedMutationLease(pin, stateLease, connection, verifier, headers, rawQuery)
            }
        return JdbcCommitTimeBindingMaintenance(dataSource, schema, authorization, clock).execute(intent, key)
    }
}

private class SignedMutationLease(
    private val pin: MaintenanceSnapshotPin,
    private val lease: MaintenanceMutationStateLease,
    private val connection: TrustedBotConnection,
    private val verifier: TargetIdentityVerifier,
    private val headers: Headers,
    private val query: String,
) : BindingMaintenanceLease {
    private var initial: MaintenanceMutationState? = null
    override val controlHead: com.example.bot.data.client.bindings.JdbcControlHeadLease? get() = lease.controlHead
    override val intentBinding: String? get() = lease.intentBinding

    override fun identity(connection: Connection): MaintenanceIdentity {
        if (connection.autoCommit) forbiddenMaintenance()
        val state = lease.current(connection) ?: forbiddenMaintenance()
        val actor = verifier.verifyMaintenance(headers, query) ?: forbiddenMaintenance()
        val trusted =
            state.authentication.connection === this.connection &&
                actor.belongsTo(this.connection) &&
                actor.matchesAuthentication(state.authentication)
        val exactOwner =
            state.ownerTelegramId != null &&
                state.ownerTelegramId > 0 &&
                actor.telegramUserId == state.ownerTelegramId &&
                actor.telegramUserId == pin.actorTelegramId
        if (!trusted || !exactOwner) forbiddenMaintenance()
        verifySelection(state)
        initial?.let { if (!it.sameState(state)) forbiddenMaintenance() }
        initial = state
        return MaintenanceIdentity(
            actor.telegramUserId,
            state.authorityConnectionId,
            state.authorityReference,
            actor.authenticatedAt,
            actor.validUntil,
        )
    }

    private fun verifySelection(state: MaintenanceMutationState) {
        val selection = state.selection
        val ref = selection.reference
        val expected = pin.binding
        val revisionCurrent = selection.revision == pin.contextRevision && selection.revision > 0
        val live = selection.enabled && revisionCurrent && selection.authentication === state.authentication
        val exact =
            ref.rootId == expected.rootId &&
                ref.telegramBotId == expected.telegramBotId &&
                ref.fence == expected.fence
        val platform = ref.audience is ClientAudience.Platform && expected.audience is ClientAudience.Platform
        val unbound = ref.mappingEpoch == null && expected.mappingEpoch == null
        val revision = ref.credentialRevision == expected.credential?.revision
        val scope = platform && unbound && revision
        if (!live || !exact || !scope) forbiddenMaintenance()
    }

    override fun close() = lease.close()
}

private fun forbiddenMaintenance(): Nothing = throw OnboardingFailure("forbidden")

internal fun maintenanceHash(value: String): String =
    java.security.MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
