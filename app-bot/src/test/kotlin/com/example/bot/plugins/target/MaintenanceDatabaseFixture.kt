package com.example.bot.plugins.target

import com.example.bot.data.client.ClientAudience
import com.example.bot.data.client.bindings.BindingFence
import com.example.bot.data.client.bindings.BindingStorageSchema
import com.example.bot.data.client.bindings.DurableBindingSource
import com.example.bot.data.client.bindings.JdbcDurableBindingSource
import java.util.UUID
import javax.sql.DataSource

/** Disposable synthetic metadata only; no tokens, memberships, grants or ACTIVE records are inserted. */
internal class MaintenanceDatabaseFixture(
    val dataSource: DataSource,
    branded: Boolean = false,
) {
    val identity = MaintenanceSelectorFixture()
    private val root get() = identity.reference.rootId
    private val binding get() = identity.reference.fence.bindingId
    val jdbc: DurableBindingSource

    init {
        if (branded) identity.branded()
        execute(
            "INSERT INTO users(id,telegram_user_id) VALUES (978001,7)",
            "INSERT INTO clients(id,display_name,is_active) VALUES (101,'Synthetic A',TRUE),(102,'Synthetic B',TRUE)",
            "INSERT INTO clubs(id,name,timezone,is_active) VALUES " +
                "(1001,'Private A','UTC',FALSE),(1002,'Private B','UTC',FALSE)," +
                "(1003,'Unassigned','UTC',FALSE),(2001,'Foreign','UTC',FALSE)",
            "INSERT INTO client_clubs(client_id,club_id,created_at) VALUES " +
                "(101,1001,'2026-11-11T00:00:00Z'),(101,1002,'2026-11-11T00:00:00Z')," +
                "(101,1003,'2026-11-11T00:00:00Z'),(102,2001,'2026-11-11T00:00:00Z')",
            "INSERT INTO audit_log(id,created_at,actor_user_id,entity_type,entity_id," +
                "action,fingerprint,metadata_json) " +
                "VALUES (978001,CURRENT_TIMESTAMP,978001,'CLIENT_ONBOARDING',780001," +
                "'synthetic.metadata','synthetic-maintenance','{}')",
        )
        val kind = if (branded) "BRANDED" else "PLATFORM"
        val client = if (branded) "101" else "NULL"
        execute(
            "INSERT INTO target_bot_connections(id,telegram_bot_id,kind,client_id,provenance_id," +
                "created_by,created_audit_id,last_audit_id) " +
                "VALUES ('$root',780001,'$kind',$client,'${UUID.randomUUID()}',978001,978001,978001)",
            "INSERT INTO target_bot_bindings(id,root_id,provenance_id,created_audit_id) " +
                "VALUES ('$binding','$root','${UUID.randomUUID()}',978001)",
            "UPDATE target_bot_connections SET current_binding_id='$binding' WHERE id='$root'",
        )
        if (branded) {
            execute(
                "INSERT INTO target_binding_clubs(root_id,binding_id,client_id,club_id) " +
                    "VALUES ('$root','$binding',101,1001),('$root','$binding',101,1002)",
            )
        }
        val schema = dataSource.connection.use { checkNotNull(it.schema) }
        jdbc = JdbcDurableBindingSource(dataSource, BindingStorageSchema.fromServerConfiguration(schema))
        refresh()
    }

    fun refresh() {
        val row = rows("SELECT version,generation FROM target_bot_connections WHERE id='$root'").single()
        val epoch =
            if (identity.reference.audience is ClientAudience.Client) {
                rows("SELECT target_mapping_epoch FROM clients WHERE id=101").single().single()?.toLong()
            } else {
                null
            }
        identity.reference =
            identity.reference.copy(
                fence = BindingFence(binding, checkNotNull(row[0]).toLong(), checkNotNull(row[1]).toLong()),
                mappingEpoch = epoch,
            )
        identity.selection =
            MaintenanceServerSelection(
                checkNotNull(identity.authentication),
                identity.reference,
                (identity.selection?.revision ?: 0) + 1,
            )
    }

    fun disable() = execute("UPDATE target_bot_bindings SET status='DISABLED' WHERE id='$binding'")

    fun revoke() =
        execute(
            "UPDATE target_bot_bindings SET status='REVOKED',revoked_at=CURRENT_TIMESTAMP," +
                "revoked_audit_id=978001 WHERE id='$binding'",
        )

    fun retireCredential() {
        val material = UUID.randomUUID()
        val revision = UUID.randomUUID()
        execute(
            "INSERT INTO target_credential_materials(id,root_id,created_audit_id) VALUES ('$material','$root',978001)",
            "INSERT INTO target_credential_revisions(id,root_id,material_id,revision,verified_bot_id," +
                "attestation_id,created_audit_id) VALUES ('$revision','$root','$material',1,780001,'${UUID.randomUUID()}',978001)",
            "UPDATE target_bot_bindings SET credential_ref='$revision' WHERE id='$binding'",
            "UPDATE target_credential_materials SET status='RETIRED',retired_at=CURRENT_TIMESTAMP," +
                "retired_audit_id=978001 WHERE id='$material'",
        )
        identity.reference = identity.reference.copy(credentialRevision = 1)
        refresh()
    }

    fun snapshot(): Map<String, List<List<String?>>> =
        listOf(
            "target_bot_connections",
            "target_bot_bindings",
            "target_binding_clubs",
            "target_credential_materials",
            "target_credential_revisions",
            "target_binding_commands",
            "target_client_mapping_epochs",
            "clients",
            "client_clubs",
            "audit_log",
            "client_memberships",
            "client_club_grants",
            "client_role_assignments",
            "client_permission_delegations",
            "client_club_access_state",
            "client_invitations",
            "user_roles",
        ).associateWith { rows("SELECT * FROM $it ORDER BY 1").sortedBy { row -> row.toString() } }

    fun execute(vararg sql: String) =
        dataSource.connection.use { c ->
            c.createStatement().use { s -> sql.forEach { s.execute(it) } }
        }

    private fun rows(sql: String): List<List<String?>> =
        dataSource.connection.use { c ->
            c.createStatement().use { s ->
                s.executeQuery(sql).use { r ->
                    buildList {
                        while (r.next()) add((1..r.metaData.columnCount).map { r.getString(it) })
                    }
                }
            }
        }
}
