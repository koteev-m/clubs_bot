package com.example.bot.data.client.bindings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

/** Unwired, one SELECT / READ COMMITTED statement snapshot. Never resolves credentials or authority. */
class JdbcDurableBindingSource(
    private val dataSource: DataSource,
    private val schema: BindingStorageSchema,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DurableBindingSource {
    private val query = durableBindingQuery(schema.quoted)

    override suspend fun read(reference: ServerSelectedBindingReference?): DurableBindingRead =
        withContext(dispatcher) {
            currentCoroutineContext().ensureActive()
            if (reference == null) return@withContext DurableBindingRead.MissingSelection
            try {
                readSnapshot(reference)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: SQLException) {
                // Deliberately do not expose driver errors, SQL, credential metadata or connection configuration.
                DurableBindingRead.ReadFailed
            } catch (_: InvalidBindingMetadata) {
                DurableBindingRead.Inconsistent
            }
        }

    private suspend fun readSnapshot(reference: ServerSelectedBindingReference): DurableBindingRead =
        dataSource.connection.use { connection ->
            connection.isReadOnly = true
            connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
            connection.autoCommit = false
            AutoCloseable { connection.rollback() }.use {
                configureResolution(connection)
                currentCoroutineContext().ensureActive()
                val result = query(connection, reference)
                currentCoroutineContext().ensureActive()
                result
            }
        }

    private fun configureResolution(connection: Connection) {
        if (connection.metaData.databaseProductName == "PostgreSQL") {
            // Qualified relations alone do not protect operators from a hostile session path.
            connection.createStatement().use { statement ->
                statement.execute("SET LOCAL search_path = pg_catalog, pg_temp")
            }
        }
    }

    /** Caller owns the transaction and locks; no new connection, commit, rollback or permission is created. */
    fun readInTransaction(
        connection: Connection,
        reference: ServerSelectedBindingReference,
    ): DurableBindingRead {
        require(!connection.autoCommit) { "Binding transaction required" }
        if (connection.metaData.databaseProductName == "PostgreSQL") {
            connection.createStatement().use {
                it.execute("SET LOCAL search_path = pg_catalog, ${schema.quoted}, pg_temp")
            }
        }
        return query(connection, reference)
    }

    private fun query(
        connection: Connection,
        reference: ServerSelectedBindingReference,
    ): DurableBindingRead =
        connection.prepareStatement(query).use { statement ->
            statement.setObject(1, reference.fence.bindingId)
            statement.setObject(2, reference.rootId)
            statement.executeQuery().use { rows -> DurableBindingDecoder(reference).decode(rows) }
        }
}

private fun durableBindingQuery(schema: String): String =
    """
    SELECT r.id AS root_id, r.telegram_bot_id, r.kind, r.client_id,
      r.current_binding_id, r.version, r.generation,
      b.id AS binding_id, b.root_id AS binding_root, b.status AS binding_status,
      b.credential_ref, b.revoked_at, b.revoked_audit_id,
      q.id AS reference_id, q.root_id AS reference_root, q.revision,
      q.verified_bot_id, q.material_id, m.root_id AS material_root,
      m.status AS material_status, m.retired_at, m.retired_audit_id,
      c.id AS canonical_client, c.is_active AS client_active, c.archived_at,
      c.lifecycle_version, c.target_mapping_epoch, h.epoch AS epoch_head,
      s.root_id AS scope_root, s.client_id AS scope_client, s.club_id,
      cm.club_id AS mapped_club, k.id AS canonical_club
    FROM $schema.target_bot_connections r
    LEFT JOIN $schema.target_bot_bindings b ON b.id = ?
    LEFT JOIN $schema.target_credential_revisions q ON q.id = b.credential_ref
    LEFT JOIN $schema.target_credential_materials m ON m.id = q.material_id
    LEFT JOIN $schema.clients c ON c.id = r.client_id
    LEFT JOIN $schema.target_client_mapping_epochs h ON h.client_id = r.client_id
    LEFT JOIN $schema.target_binding_clubs s ON s.binding_id = b.id
    LEFT JOIN $schema.client_clubs cm ON cm.client_id = s.client_id AND cm.club_id = s.club_id
    LEFT JOIN $schema.clubs k ON k.id = s.club_id
    WHERE r.id = ?
    ORDER BY s.club_id
    """.trimIndent()
