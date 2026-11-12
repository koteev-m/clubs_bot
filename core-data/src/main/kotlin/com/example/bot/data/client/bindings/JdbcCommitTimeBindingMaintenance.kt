package com.example.bot.data.client.bindings

import com.example.bot.data.client.ClientAudience
import com.example.bot.data.client.onboarding.OnboardingSql
import com.example.bot.data.client.onboarding.fail
import java.sql.Connection
import java.time.Clock
import javax.sql.DataSource

/** Unwired adapter into the existing single lifecycle writer/audit/receipt transaction. */
class JdbcCommitTimeBindingMaintenance(
    dataSource: DataSource,
    private val schema: BindingStorageSchema,
    private val authorization: BindingMaintenanceAuthorization = BindingMaintenanceAuthorization { null },
    private val clock: Clock = Clock.systemUTC(),
) {
    private val foundation = JdbcInactiveBindingFoundation(dataSource, clock = clock)
    private val reader = JdbcDurableBindingSource(dataSource, schema)

    suspend fun execute(
        intent: InactiveMaintenanceIntent,
        key: String,
    ): MaintenanceMutationOutcome {
        requireIntent(intent)
        val command = command(intent)
        return try {
            val result =
                foundation.mutateGuarded(command, key) {
                    val lease = authorization.acquire(intent) ?: fail("forbidden")
                    CommitGuard(intent, lease, schema, reader, clock)
                }
            val status =
                if (result.replayed) MaintenanceMutationStatus.REPLAY_CURRENT else MaintenanceMutationStatus.APPLIED
            MaintenanceMutationOutcome(status, result.current.fence)
        } catch (stale: StaleMaintenanceReceipt) {
            MaintenanceMutationOutcome(MaintenanceMutationStatus.REPLAY_STALE, stale.fence)
        }
    }

    private fun requireIntent(intent: InactiveMaintenanceIntent) {
        val before = intent.expected
        val platform = before.kind == BindingKind.PLATFORM && before.audience is ClientAudience.Platform
        val unbound = before.mappingEpoch == null && before.clientLifecycleVersion == null
        if (!platform || !unbound) fail("forbidden")
        if (before.status !in setOf(BindingState.PENDING, BindingState.DISABLED)) fail("invalid_transition")
        if (intent.action == InactiveMaintenanceAction.RETIRE_SELECTED_MATERIAL) {
            val credential = before.credential ?: fail("invalid_transition")
            if (credential.materialId != intent.materialId || credential.state != CredentialMaterialState.AVAILABLE) {
                fail("invalid_transition")
            }
        } else if (intent.materialId != null) {
            fail("validation_error")
        }
    }

    private fun command(intent: InactiveMaintenanceIntent): BindingCommand =
        when (intent.action) {
            InactiveMaintenanceAction.DISABLE ->
                BindingCommand.State(
                    intent.expected.rootId,
                    intent.expected.fence,
                    BindingState.DISABLED,
                )
            InactiveMaintenanceAction.REVOKE ->
                BindingCommand.State(
                    intent.expected.rootId,
                    intent.expected.fence,
                    BindingState.REVOKED,
                )
            InactiveMaintenanceAction.RETIRE_SELECTED_MATERIAL ->
                BindingCommand.RetireMaterial(
                    intent.expected.rootId,
                    intent.expected.fence,
                    checkNotNull(intent.materialId),
                )
        }
}

private class CommitGuard(
    private val intent: InactiveMaintenanceIntent,
    private val lease: BindingMaintenanceLease,
    private val schema: BindingStorageSchema,
    private val reader: JdbcDurableBindingSource,
    private val clock: Clock,
) : BindingCommitGuard {
    private val controlHead = lease.controlHead
    private val intentBinding = lease.intentBinding
    override val requestHash =
        bindingDigest(
            listOf(
                intentBinding?.let { "commit-maintenance-control-v1:$it" } ?: "commit-maintenance-v1",
                intent.action,
                intent.materialId,
                intent.expected.rootId,
                intent.expected.telegramBotId,
                intent.expected.kind,
                intent.expected.fence,
                intent.expected.status,
                intent.expected.credential?.referenceId,
                intent.expected.credential?.revision,
                intent.expected.credential?.materialId,
                intent.expected.credential?.state,
                intent.expected.mappingEpoch,
                intent.expected.clientLifecycleVersion,
            ).joinToString(":"),
        )

    override fun configure(connection: Connection) {
        val sql =
            if (connection.metaData.databaseProductName == "PostgreSQL") {
                "SET LOCAL search_path = pg_catalog, ${schema.quoted}, pg_temp"
            } else {
                "SET SCHEMA ${schema.quoted}"
            }
        connection.createStatement().use { it.execute(sql) }
        // Bind every canonical relation before any unqualified writer/receipt lookup.
        // A missing trusted schema/table must never fall through to a TEMP replica.
        val tables =
            listOf(
                "users",
                "audit_log",
                "target_bot_connections",
                "target_bot_bindings",
                "target_credential_materials",
                "target_credential_revisions",
                "target_binding_clubs",
                "target_binding_commands",
                "clients",
                "client_clubs",
                "clubs",
                "target_client_mapping_epochs",
            )
        val relations = tables.mapIndexed { index, name -> "${schema.quoted}.$name r$index" }.joinToString(",")
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT 1 FROM $relations WHERE 1=0").close()
        }
    }

    override fun identity(connection: Connection): MaintenanceIdentity {
        if (lease.controlHead !== controlHead || lease.intentBinding != intentBinding) fail("forbidden")
        controlHead?.let { control ->
            if (control.snapshot.scope.rootId != intent.expected.rootId ||
                lease.intentBinding == null
            ) {
                fail("forbidden")
            }
            control.verify(connection)
        }
        return lease.identity(connection).also {
            requireBindingFresh(it.authenticatedAt, it.validUntil, clock.instant())
            if (it.telegramUserId <= 0) fail("forbidden")
            controlHead?.snapshot?.let { head ->
                if (it.connectionId != head.scope.authorityConnectionId || it.authorityReference != head.manifestId) {
                    fail("forbidden")
                }
            }
        }
    }

    override fun before(
        connection: Connection,
        current: BindingView?,
        receiptCurrent: Boolean?,
    ) {
        if (controlHead == null) {
            BindingControlCompatibility(schema).requireUnmanaged(connection, intent.expected.rootId)
        }
        identity(connection)
        val row = current ?: fail("not_found")
        val exact = row.rootId == intent.expected.rootId && row.botId == intent.expected.telegramBotId
        val platform = row.kind == BindingKind.PLATFORM && row.clientId == null && row.clubs.isEmpty()
        if (!exact || !platform) fail("forbidden")
        if (receiptCurrent == false) throw StaleMaintenanceReceipt(row.fence)
        if (receiptCurrent == null) {
            val actual = read(connection, intent.expected.fence, intent.expected.credential?.revision)
            if (actual != intent.expected) fail("version_conflict")
        }
    }

    override fun after(
        connection: Connection,
        current: BindingView,
    ) {
        val revision =
            current.credentialReference?.let { ref ->
                OnboardingSql(connection)
                    .row("SELECT revision FROM target_credential_revisions WHERE id=?", ref)
                    ?.bindingColumns()
                    ?.get("revision")
                    ?.toLong() ?: fail("invalid_transition")
            }
        val actual = read(connection, current.fence, revision)
        val expectedStatus =
            if (intent.action ==
                InactiveMaintenanceAction.REVOKE
            ) {
                BindingState.REVOKED
            } else {
                BindingState.DISABLED
            }
        if (intent.action == InactiveMaintenanceAction.REVOKE &&
            actual.credential?.state == CredentialMaterialState.AVAILABLE
        ) {
            fail("invalid_transition")
        }
        if (intent.action == InactiveMaintenanceAction.RETIRE_SELECTED_MATERIAL && actual.credential != null) {
            fail("invalid_transition")
        }
        if (actual.status != expectedStatus ||
            actual.fence.version <= intent.expected.fence.version ||
            actual.fence.generation <= intent.expected.fence.generation
        ) {
            fail("invalid_transition")
        }
    }

    private fun read(
        connection: Connection,
        fence: BindingFence,
        revision: Long?,
    ): DurableBindingSnapshot {
        val reference =
            ServerSelectedBindingReference(
                intent.expected.rootId,
                fence,
                intent.expected.telegramBotId,
                ClientAudience.Platform,
                credentialRevision = revision,
            )
        return when (val result = reader.readInTransaction(connection, reference)) {
            is DurableBindingRead.Inactive -> result.snapshot
            is DurableBindingRead.Revoked -> result.snapshot
            else -> fail("version_conflict")
        }
    }

    override fun close() = lease.close()
}
