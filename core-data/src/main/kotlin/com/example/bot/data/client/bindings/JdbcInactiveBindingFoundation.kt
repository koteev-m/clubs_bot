package com.example.bot.data.client.bindings

import com.example.bot.data.client.onboarding.OnboardingSql
import com.example.bot.data.client.onboarding.fail
import com.example.bot.data.client.onboarding.long
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.util.UUID
import javax.sql.DataSource
import kotlin.coroutines.CoroutineContext

/** Inactive platform-only lifecycle. Default-deny evidence; no auth reader, credential resolver or DI wiring. */
internal class JdbcInactiveBindingFoundation(
    private val dataSource: DataSource,
    configuredPlatformOwnerId: Long? = null,
    providers: BindingEvidenceProvider = object : BindingEvidenceProvider {},
    private val clock: Clock = Clock.systemUTC(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val storageSchema: BindingStorageSchema? = null,
) {
    private val validation = BindingValidation(providers, configuredPlatformOwnerId)

    suspend fun mutate(
        evidence: String,
        command: BindingCommand,
        idempotencyKey: String,
    ): BindingResult = mutateAttempts(evidence, command, idempotencyKey)

    internal suspend fun mutateGuarded(
        command: BindingCommand,
        key: String,
        guards: () -> BindingCommitGuard,
    ): BindingResult = mutateAttempts("", command, key, guards)

    private suspend fun mutateAttempts(
        evidence: String,
        command: BindingCommand,
        key: String,
        guards: (() -> BindingCommitGuard)? = null,
    ): BindingResult =
        withContext(dispatcher) {
            validate(command, key)
            val context = currentCoroutineContext()
            var attempt = 0
            while (true) {
                try {
                    return@withContext if (guards == null) {
                        transaction(context) { sql ->
                            BindingControlCompatibility(storageSchema ?: fail("forbidden")).configure(sql.connection)
                            mutateLocked(sql, evidence, command, key, context)
                        }
                    } else {
                        guards().use { guard ->
                            transaction(context) { sql ->
                                guard.configure(sql.connection)
                                mutateLocked(sql, evidence, command, key, context, guard)
                            }
                        }
                    }
                } catch (failure: SQLException) {
                    if (failure.sqlState !in setOf("40001", "40P01") || attempt >= MAX_RETRIES) throw failure
                    attempt++
                    delay(RETRY_DELAY_MS * attempt)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("Unreachable transaction retry")
        }

    suspend fun read(
        evidence: String,
        rootId: UUID,
    ): BindingView? =
        withContext(dispatcher) {
            transaction(currentCoroutineContext()) { sql ->
                val identity = validation.identity(evidence, clock.instant())
                val result = BindingRows(sql).current(rootId, exclusive = false)
                requireSame(identity, evidence)
                result
            }
        }

    /** Unknown resolution never reserves a key or creates a root/receipt. Old results expose current metadata only. */
    suspend fun resolve(
        evidence: String,
        command: BindingCommand,
        idempotencyKey: String,
    ): BindingResolution =
        withContext(dispatcher) {
            validate(command, idempotencyKey)
            transaction(currentCoroutineContext()) { sql ->
                val identity = validation.identity(evidence, clock.instant())
                val actor = sql.user(identity.telegramUserId)?.bindingColumns()?.long("id") ?: fail("forbidden")
                val rows = BindingRows(sql)
                val current = rows.current(command.rootId, exclusive = false)
                val receipt = rows.receipt(actor, identity.connectionId, bindingDigest(idempotencyKey))
                checkReceipt(receipt?.get("request_hash"), command)
                val status =
                    when {
                        receipt == null -> BindingResolutionStatus.UNKNOWN
                        current != null && rows.matches(receipt, current) -> BindingResolutionStatus.COMPLETED_CURRENT
                        else -> BindingResolutionStatus.COMPLETED_STALE
                    }
                requireSame(identity, evidence)
                BindingResolution(status, current)
            }
        }

    private fun mutateLocked(
        sql: OnboardingSql,
        evidence: String,
        command: BindingCommand,
        key: String,
        context: CoroutineContext,
        guard: BindingCommitGuard? = null,
    ): BindingResult {
        val identity = currentIdentity(evidence, sql, guard)
        val actor = sql.user(identity.telegramUserId)?.bindingColumns()?.long("id") ?: fail("forbidden")
        sql.lockActor(actor)
        if (guard != null && sql.user(identity.telegramUserId)?.bindingColumns()?.long("id") != actor) {
            fail("forbidden")
        }
        val rows = BindingRows(sql)
        val before = rows.current(command.rootId, exclusive = true)
        if (guard == null) {
            BindingControlCompatibility(
                storageSchema ?: fail("forbidden"),
            ).requireUnmanaged(sql.connection, command.rootId)
        }
        context.ensureActive()
        requireSame(identity, evidence, sql, guard)
        val keyHash = bindingDigest(key)
        val receipt = rows.receipt(actor, identity.connectionId, keyHash)
        checkReceipt(receipt?.get("request_hash"), command, guard?.requestHash)
        guard?.before(sql.connection, before, receipt?.let { before != null && rows.matches(it, before) })
        if (receipt != null) {
            if (before == null || !rows.matches(receipt, before)) fail("version_conflict")
            if (guard != null) requireSame(identity, evidence, sql, guard)
            return BindingResult(true, before)
        }
        validation.command(command, before)
        val attestation = validation.attestation(command, before, clock.instant())
        val botId = before?.botId ?: (command as BindingCommand.Create).botId
        val audit = BindingAudit(sql, clock.instant())
        val auditId = audit.begin(actor, command, botId, before, identity)
        val writer = BindingWriter(sql, auditId, clock.instant())
        if (command is BindingCommand.Create) {
            writer.create(
                command,
                actor,
            )
        } else {
            writer.change(command, checkNotNull(before))
        }
        val after = rows.current(command.rootId, exclusive = true) ?: fail("invalid_transition")
        audit.complete(auditId, command, before, after, identity)
        sql.update(
            "INSERT INTO target_binding_commands(actor_user_id,authority_connection_id,key_hash," +
                "request_hash,root_id,binding_id,result_version,result_generation,audit_id,created_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?)",
            actor,
            identity.connectionId,
            keyHash,
            guard?.requestHash ?: command.intentHash(),
            command.rootId,
            after.fence.bindingId,
            after.fence.version,
            after.fence.generation,
            auditId,
            clock.instant(),
        )
        if (validation.attestation(command, before, clock.instant()) != attestation) fail("forbidden")
        guard?.after(sql.connection, after)
        requireSame(identity, evidence, sql, guard)
        return BindingResult(false, after)
    }

    private fun requireSame(
        identity: MaintenanceIdentity,
        evidence: String,
        sql: OnboardingSql? = null,
        guard: BindingCommitGuard? = null,
    ) {
        val current =
            if (guard == null) {
                validation.identity(evidence, clock.instant())
            } else {
                currentIdentity(evidence, checkNotNull(sql), guard)
            }
        if (current != identity) fail("forbidden")
    }

    private fun currentIdentity(
        evidence: String,
        sql: OnboardingSql,
        guard: BindingCommitGuard?,
    ): MaintenanceIdentity =
        if (guard == null) validation.identity(evidence, clock.instant()) else guard.identity(sql.connection)

    private fun checkReceipt(
        hash: String?,
        command: BindingCommand,
        guardedHash: String? = null,
    ) {
        if (hash != null && hash != (guardedHash ?: command.intentHash())) fail("idempotency_conflict")
    }

    private fun validate(
        command: BindingCommand,
        key: String,
    ) {
        command.validate()
        if (key.isEmpty() ||
            key.length > MAX_KEY_LENGTH ||
            key.any { it.code !in PRINTABLE_ASCII }
        ) {
            fail("validation_error")
        }
    }

    private fun <T> transaction(
        context: CoroutineContext,
        block: (OnboardingSql) -> T,
    ): T =
        dataSource.connection.use { connection ->
            connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
            connection.autoCommit = false
            var committed = false
            AutoCloseable { if (!committed) connection.rollback() }.use {
                context.ensureActive()
                val result = block(OnboardingSql(connection))
                context.ensureActive()
                connection.commit()
                committed = true
                result
            }
        }

    private companion object {
        const val MAX_RETRIES = 2
        const val RETRY_DELAY_MS = 10L
        const val MAX_KEY_LENGTH = 256
        val PRINTABLE_ASCII = 33..126
    }
}
