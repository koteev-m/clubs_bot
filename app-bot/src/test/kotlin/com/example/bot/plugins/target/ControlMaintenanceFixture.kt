package com.example.bot.plugins.target

import com.example.bot.data.client.bindings.BindingStorageSchema
import com.example.bot.data.client.bindings.ControlPublicationStatus
import com.example.bot.data.client.bindings.InactiveMaintenanceAction
import com.example.bot.data.client.bindings.MaintenanceControlHead
import com.example.bot.data.client.bindings.MaintenanceControlScope
import kotlinx.coroutines.runBlocking
import java.util.UUID

/** Explicit synthetic config delivery; no production bundle/owner/intent provider is installed. */
internal class ControlMaintenanceFixture(
    val f: MaintenanceDatabaseFixture,
    operations: Set<InactiveMaintenanceAction> = InactiveMaintenanceAction.entries.toSet(),
) {
    val schema = BindingStorageSchema.fromServerConfiguration(f.dataSource.connection.use { it.schema })
    private val scope = MaintenanceControlScope(UUID.randomUUID(), f.identity.reference.rootId, UUID.randomUUID())
    private var audit = 978001L
    var head = MaintenanceControlHead(scope, 1, 1, ControlPublicationStatus.DISABLED, null, null)
        private set
    var delivered: MaintenanceControlBundle? = null
    var checks = 0
    var onCurrent: () -> Unit = {}
    val source =
        DbControlMaintenanceLeaseSource(schema) {
            checks++
            onCurrent()
            delivered?.takeIf {
                f.identity.owner == it.context.configuredOwnerId &&
                    f.identity.authentication === it.context.authentication
            }
        }

    init {
        f.execute(
            "INSERT INTO target_maintenance_control_heads(id,root_id,authority_connection_id,provenance_id," +
                "version,generation,status,last_audit_id) VALUES ('${scope.id}','${scope.rootId}'," +
                "'${scope.authorityConnectionId}','${UUID.randomUUID()}',1,1,'DISABLED',978001)",
        )
        delivered = publish(operations)
    }

    fun publish(
        operations: Set<InactiveMaintenanceAction> = InactiveMaintenanceAction.entries.toSet(),
    ): MaintenanceControlBundle {
        if (head.status == ControlPublicationStatus.ENABLED) change(null, null)
        val next =
            head.copy(
                version = head.version + 1,
                generation = head.generation + 1,
                status = ControlPublicationStatus.ENABLED,
                manifestId = UUID.randomUUID(),
                manifestDigest = "0".repeat(64),
            )
        val context = MaintenanceProtectedContext(checkNotNull(f.identity.authentication), 7, UUID.randomUUID())
        val selection = checkNotNull(f.identity.selection)
        val draft = MaintenanceControlBundle(next, context, selection, operations)
        change(next.manifestId, draft.digest)
        return MaintenanceControlBundle(head, context, selection, operations)
    }

    private fun change(
        manifest: UUID?,
        digest: String?,
    ) {
        audit++
        val status = if (manifest == null) "DISABLED" else "ENABLED"
        val idSql = manifest?.let { "'$it'" } ?: "NULL"
        val digestSql = digest?.let { "'$it'" } ?: "NULL"
        // All literals are synthetic internal fixture values, never request content.
        f.execute(
            "INSERT INTO audit_log(id,created_at,actor_user_id,entity_type," +
                "entity_id,action,fingerprint,metadata_json) " +
                "VALUES($audit,CURRENT_TIMESTAMP,978001,'CLIENT_ONBOARDING',NULL,'synthetic.control'," +
                "'${UUID.randomUUID()}','{}')",
            "UPDATE target_maintenance_control_heads SET status='$status'," +
                "manifest_id=$idSql,manifest_digest=$digestSql," +
                "version=version+1,generation=generation+1,last_audit_id=$audit WHERE id='${scope.id}'",
        )
        head =
            MaintenanceControlHead(
                scope,
                head.version + 1,
                head.generation + 1,
                ControlPublicationStatus.valueOf(status),
                manifest,
                digest,
            )
    }

    fun pin(): MaintenanceSnapshotPin =
        runBlocking {
            (f.identity.selector(f.jdbc).select(f.identity.identity()) as MaintenanceSelection.Selected).pin
        }

    fun adapter(leases: MaintenanceMutationLeaseSource = source): TargetCommitTimeMaintenanceAdapter =
        TargetCommitTimeMaintenanceAdapter(
            f.dataSource,
            schema,
            f.identity.connection,
            f.identity.verifier,
            leases,
            f.identity.signing.clock,
        )
}
