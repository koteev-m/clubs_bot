package com.example.bot.data.client.bindings

import com.example.bot.data.client.onboarding.OnboardingSql
import com.example.bot.data.client.onboarding.fail
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.util.UUID
import javax.sql.DataSource

internal data class SyntheticControlPublisherIdentity(
    val userId: Long,
    val provenance: UUID,
)

/** Test/internal authority only, absent by default. Not a protected configuration publisher. */
internal fun interface SyntheticControlPublisherAuthority {
    fun current(connection: Connection): SyntheticControlPublisherIdentity?
}

/** Unregistered local publisher. Never delivers configuration or touches credentials/bindings/grants. */
internal class SyntheticControlHeadPublisher(
    private val source: DataSource,
    private val schema: BindingStorageSchema,
    private val authority: SyntheticControlPublisherAuthority = SyntheticControlPublisherAuthority { null },
    private val clock: Clock = Clock.systemUTC(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun create(scope: MaintenanceControlScope): MaintenanceControlHead =
        transaction { connection, identity ->
            val audit = audit(connection, scope, identity, "create")
            connection
                .prepareStatement(
                    "INSERT INTO ${schema.quoted}.target_maintenance_control_heads " +
                        "(id,root_id,authority_connection_id,provenance_id,version,generation,status,last_audit_id) " +
                        "VALUES (?,?,?,?,1,1,'DISABLED',?)",
                ).use { insert ->
                    listOf(scope.id, scope.rootId, scope.authorityConnectionId, identity.provenance, audit)
                        .forEachIndexed { index, value -> insert.setObject(index + 1, value) }
                    if (insert.executeUpdate() != 1) fail("version_conflict")
                }
            val created = readControlHead(connection, schema, scope.id, "FOR UPDATE") ?: fail("forbidden")
            val expected = MaintenanceControlHead(scope, 1, 1, ControlPublicationStatus.DISABLED, null, null)
            if (created != expected) fail("version_conflict")
            created
        }

    suspend fun fence(expected: MaintenanceControlHead): MaintenanceControlHead = update(expected, null, null)

    suspend fun publish(
        expected: MaintenanceControlHead,
        manifestId: UUID,
        digest: String,
    ): MaintenanceControlHead {
        require(digest.matches(Regex("[0-9a-f]{64}")))
        if (expected.status != ControlPublicationStatus.DISABLED) fail("invalid_transition")
        return update(expected, manifestId, digest)
    }

    private suspend fun update(
        expected: MaintenanceControlHead,
        manifestId: UUID?,
        digest: String?,
    ): MaintenanceControlHead =
        transaction { connection, identity ->
            val current = readControlHead(connection, schema, expected.scope.id, "FOR UPDATE")
            if (current != expected) fail("version_conflict")
            if (authority.current(connection) != identity) fail("forbidden")
            configureControlTransaction(connection, schema)
            val enabled = manifestId != null
            val audit = audit(connection, expected.scope, identity, if (enabled) "publish" else "fence")
            val sql =
                "UPDATE ${schema.quoted}.target_maintenance_control_heads SET version=version+1," +
                    "generation=generation+1,status=?,manifest_id=?,manifest_digest=?,last_audit_id=?,updated_at=? " +
                    "WHERE id=? AND version=? AND generation=?"
            val next =
                expected.copy(
                    version = Math.addExact(expected.version, 1),
                    generation = Math.addExact(expected.generation, 1),
                    status = if (enabled) ControlPublicationStatus.ENABLED else ControlPublicationStatus.DISABLED,
                    manifestId = manifestId,
                    manifestDigest = digest,
                )
            val postgres = connection.metaData.databaseProductName == "PostgreSQL"
            val returning = if (postgres) " RETURNING id,version,generation" else ""
            connection.prepareStatement(sql + returning).use { change ->
                listOf(
                    if (enabled) "ENABLED" else "DISABLED",
                    manifestId,
                    digest,
                    audit,
                    clock.instant().atOffset(java.time.ZoneOffset.UTC),
                    expected.scope.id,
                    expected.version,
                    expected.generation,
                ).forEachIndexed { index, value -> change.setObject(index + 1, value) }
                if (postgres) {
                    change.executeQuery().use { result ->
                        if (!result.next()) fail("version_conflict")
                        if (result.getObject("id", UUID::class.java) != next.scope.id ||
                            result.getLong("version") != next.version ||
                            result.getLong("generation") != next.generation
                        ) {
                            fail("version_conflict")
                        }
                        if (result.next()) fail("version_conflict")
                    }
                } else if (change.executeUpdate() != 1) {
                    fail("version_conflict")
                }
            }
            val persisted = readControlHead(connection, schema, expected.scope.id, "FOR UPDATE") ?: fail("forbidden")
            if (persisted != next) fail("version_conflict")
            persisted
        }

    private fun audit(
        connection: Connection,
        scope: MaintenanceControlScope,
        identity: SyntheticControlPublisherIdentity,
        operation: String,
    ): Long =
        OnboardingSql(connection).insert(
            "INSERT INTO ${schema.quoted}.audit_log " +
                "(created_at,actor_user_id,entity_type,entity_id,action,fingerprint,metadata_json) " +
                "VALUES (?,?,'CLIENT_ONBOARDING',NULL,?,?,?)",
            clock.instant(),
            identity.userId,
            "target.control.$operation",
            "control-foundation-" + UUID.randomUUID(),
            buildJsonObject {
                put("mode", "SYNTHETIC_CONTROL_PUBLISHER")
                put("controlId", scope.id.toString())
                put("rootId", scope.rootId.toString())
                put("provenance", identity.provenance.toString())
            }.toString(),
        )

    private suspend fun <T> transaction(block: (Connection, SyntheticControlPublisherIdentity) -> T): T =
        withContext(dispatcher) {
            val context = currentCoroutineContext()
            var retries = 0
            while (true) {
                try {
                    return@withContext source.connection.use { connection ->
                        connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
                        connection.autoCommit = false
                        configureControlTransaction(connection, schema)
                        val started = System.nanoTime()
                        var committed = false
                        AutoCloseable { if (!committed) connection.rollback() }.use {
                            context.ensureActive()
                            val identity = authority.current(connection) ?: fail("forbidden")
                            if (identity.userId <= 0) fail("forbidden")
                            val result = block(connection, identity)
                            context.ensureActive()
                            if (System.nanoTime() - started >= CONTROL_ATTEMPT_NANOS) fail("forbidden")
                            if (authority.current(connection) != identity) fail("forbidden")
                            connection.commit()
                            committed = true
                            result
                        }
                    }
                } catch (failure: SQLException) {
                    if (failure.sqlState !in setOf("40001", "40P01") || retries >= 2) throw failure
                    retries++
                    delay(10L * retries)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("Unreachable publisher retry")
        }
}
