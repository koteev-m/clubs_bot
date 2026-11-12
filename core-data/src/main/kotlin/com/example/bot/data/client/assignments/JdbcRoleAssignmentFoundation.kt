package com.example.bot.data.client.assignments

import com.example.bot.data.client.onboarding.OnboardingSql
import com.example.bot.data.client.onboarding.active
import com.example.bot.data.client.onboarding.digest
import com.example.bot.data.client.onboarding.fail
import com.example.bot.data.client.onboarding.long
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import javax.sql.DataSource
import kotlin.coroutines.CoroutineContext

/**
 * Inactive internal foundation. No DI binding, route, automatic import, production policy or identity provider.
 * It never writes legacy grants/roles/staff. Existing authorization does not call this evaluator.
 */
internal class JdbcRoleAssignmentFoundation(
    private val dataSource: DataSource,
    private val policies: AssignmentPolicyCatalog = AssignmentPolicyCatalog(),
    private val identityVerifier: AssignmentIdentityVerifier = DenyAssignmentProviders.identity,
    private val ownership: AssignmentOwnershipProvider = DenyAssignmentProviders,
    private val admissionVerifier: TargetAdmissionVerifier = DenyAssignmentProviders.admission,
    private val configuredPlatformOwnerId: Long? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun effective(
        evidence: String,
        clientId: Long,
        clubId: Long,
    ): AssignmentView =
        withContext(dispatcher) {
            val callContext = currentCoroutineContext()
            transaction(callContext) { sql ->
                val identity = verified(evidence)
                val userId = sql.user(identity.telegramUserId)?.long("id") ?: fail("not_found")
                sql.lockActor(userId)
                requireContext(sql, userId, clientId, clubId)
                callContext.ensureActive()
                val current = verified(evidence).also { if (it != identity) fail("forbidden") }
                val view =
                    AssignmentSnapshot(
                        sql,
                        clientId,
                        policies,
                        clock.instant(),
                    ).view(StaffScope(clientId, clubId, userId))
                if (current.telegramUserId == configuredPlatformOwnerId) {
                    view.copy(contributions = emptyList(), validUntil = current.validUntil)
                } else {
                    view.fenced(current.validUntil)
                }
            }
        }

    suspend fun mutate(
        evidence: String,
        command: AssignmentCommand,
        idempotencyKey: String,
    ): AssignmentResult {
        val intent = command.frozen()
        return withContext(dispatcher) {
            validate(intent, idempotencyKey)
            val callContext = currentCoroutineContext()
            var attempt = 0
            while (true) {
                try {
                    return@withContext transaction(callContext) { sql ->
                        mutateLocked(sql, evidence, intent, idempotencyKey, callContext)
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
    }

    /** Unknown result resolution is strictly read-only, including when no receipt exists. */
    suspend fun resolve(
        evidence: String,
        command: AssignmentCommand,
        idempotencyKey: String,
    ): AssignmentResolution {
        val intent = command.frozen()
        return withContext(dispatcher) {
            validate(intent, idempotencyKey)
            val callContext = currentCoroutineContext()
            val result =
                transaction(callContext) { sql ->
                    mutateLocked(sql, evidence, intent, idempotencyKey, callContext, resolveOnly = true)
                }
            AssignmentResolution(result.replayed, result.current, result.actorFence)
        }
    }

    private fun mutateLocked(
        sql: OnboardingSql,
        evidence: String,
        command: AssignmentCommand,
        key: String,
        callContext: CoroutineContext,
        resolveOnly: Boolean = false,
    ): AssignmentResult {
        val identity = verified(evidence)
        if (identity.telegramUserId == configuredPlatformOwnerId) fail("forbidden")
        val actorId = sql.user(identity.telegramUserId)?.long("id") ?: fail("not_found")
        sql.lockActor(actorId)
        val scope = command.scope
        requireContext(sql, actorId, scope.clientId, scope.clubId)
        lockSources(sql, scope.clientId)
        callContext.ensureActive()
        val fresh = verified(evidence).also { if (it != identity) fail("forbidden") }
        val receipt =
            AssignmentReceipt(actorId, "af:" + digest(fresh.connectionKey).take(CONNECTION_HASH_LENGTH), digest(key))
        val existing =
            sql.row(
                "SELECT * FROM client_commands WHERE actor_user_id=? AND connection_key=? AND key_hash=?",
                actorId,
                receipt.connectionKey,
                receipt.keyHash,
            )
        val intentHash = command.intentHash()
        val matchingIntent =
            existing == null || existing["request_hash"] == intentHash && existing["result_type"] == "role_foundation"
        val matchingScope =
            existing == null ||
                existing.long("client_id") == scope.clientId &&
                existing.long("result_id") == scope.userId
        if (!matchingIntent || !matchingScope) {
            fail("idempotency_conflict")
        }
        val snapshot = AssignmentSnapshot(sql, scope.clientId, policies, clock.instant())
        val authority = AssignmentAuthority(snapshot, actorId, ownership, configuredPlatformOwnerId)
        val lifecycle = AssignmentLifecycle(snapshot, policies, authority, fresh, admissionVerifier)
        val authorityReference = lifecycle.authorize(command, replay = existing != null || resolveOnly)
        if (existing != null ||
            resolveOnly
        ) {
            return result(existing != null, snapshot, scope, actorId, authorityReference, fresh)
        }
        lifecycle.validate(command)
        val before = snapshot.view(scope)
        // Receipt is inserted first so new sources have an exact composite FK. Any later failure rolls it back.
        sql.update(
            "INSERT INTO client_commands(actor_user_id,connection_key,key_hash,request_hash," +
                "result_type,result_id,client_id,created_at) " +
                "VALUES (?,?,?,?,'role_foundation',?,?,?)",
            actorId,
            receipt.connectionKey,
            receipt.keyHash,
            intentHash,
            scope.userId,
            scope.clientId,
            snapshot.now,
        )
        val audit =
            AssignmentAudit(
                sql,
                actorId,
                receipt.keyHash,
                scope,
                command,
                authorityReference,
                snapshot.now,
                lifecycle.admissionProof?.invitationId,
            )
        val auditId = audit.begin(before)
        val writer = AssignmentSourceWriter(snapshot, receipt, auditId, authorityReference)
        apply(command, lifecycle, writer, actorId)
        val afterSnapshot = AssignmentSnapshot(sql, scope.clientId, policies, clock.instant())
        val after = afterSnapshot.view(scope)
        val finalAuthority = AssignmentAuthority(afterSnapshot, actorId, ownership, configuredPlatformOwnerId)
        AssignmentLifecycle(afterSnapshot, policies, finalAuthority, fresh, admissionVerifier)
            .authorize(command, replay = true)
        audit.complete(auditId, before, after)
        verified(evidence).also { if (it != fresh) fail("forbidden") }
        return result(false, afterSnapshot, scope, actorId, authorityReference, fresh)
    }

    private fun result(
        replayed: Boolean,
        snapshot: AssignmentSnapshot,
        scope: StaffScope,
        actorId: Long,
        authorityReference: String,
        identity: VerifiedAssignmentIdentity,
    ): AssignmentResult {
        val actorScope = scope.copy(userId = actorId)
        val actor = snapshot.view(actorScope).fenced(identity.validUntil)
        val fence =
            AssignmentActorFence(
                actorScope,
                checkNotNull(actor.membershipVersion),
                actor.admission,
                authorityReference,
                digest(identity.connectionKey),
                checkNotNull(actor.validUntil),
            )
        return AssignmentResult(replayed, snapshot.view(scope).fenced(fence.validUntil), fence)
    }

    private fun apply(
        command: AssignmentCommand,
        lifecycle: AssignmentLifecycle,
        writer: AssignmentSourceWriter,
        actorId: Long,
    ) {
        val snapshot = lifecycle.snapshot
        when (command) {
            is AssignmentCommand.Admit -> AssignmentAdmissionWriter(snapshot, actorId, writer).admit(command)
            is AssignmentCommand.IssueRole -> writer.issue(command)
            is AssignmentCommand.IssueDelegation -> writer.issue(command)
            is AssignmentCommand.RevokeRole ->
                writer.bump(
                    writer.revoke(listOf(lifecycle.role(command.scope, command.sourceId)), emptyList(), command.reason),
                )
            is AssignmentCommand.RevokeDelegation ->
                writer.bump(
                    writer.revoke(
                        emptyList(),
                        listOf(lifecycle.delegation(command.scope, command.sourceId)),
                        command.reason,
                    ),
                )
            is AssignmentCommand.Disable ->
                AssignmentAdmissionWriter(
                    snapshot,
                    actorId,
                    writer,
                ).disable(command, lifecycle.invitationWatermark)
        }
    }

    private fun requireContext(
        sql: OnboardingSql,
        actorId: Long,
        clientId: Long,
        clubId: Long,
    ) {
        val client = sql.row("SELECT * FROM clients WHERE id=? FOR UPDATE", clientId) ?: fail("not_found")
        if (!client.active() || client["archived_at"] != null) fail("not_found")
        sql.row("SELECT club_id FROM client_clubs WHERE client_id=? AND club_id=?", clientId, clubId)
            ?: fail("not_found")
        val member =
            sql.row("SELECT * FROM client_memberships WHERE client_id=? AND user_id=?", clientId, actorId)
                ?: fail("forbidden")
        if (!member.active()) fail("forbidden")
    }

    private fun lockSources(
        sql: OnboardingSql,
        clientId: Long,
    ) {
        // A single client lock prevents new phantom fences. Sorted child locks include the whole dependency tail.
        sql.rows(
            "SELECT user_id FROM client_club_access_state WHERE client_id=? ORDER BY club_id,user_id FOR UPDATE",
            clientId,
        )
        sql.rows("SELECT id FROM client_role_assignments WHERE client_id=? ORDER BY id FOR UPDATE", clientId)
        sql.rows("SELECT id FROM client_permission_delegations WHERE client_id=? ORDER BY id FOR UPDATE", clientId)
    }

    private fun verified(evidence: String): VerifiedAssignmentIdentity =
        (
            identityVerifier.verify(
                evidence,
                clock.instant(),
            ) ?: fail("forbidden")
        ).also { it.requireFresh(clock.instant()) }

    private fun validate(
        command: AssignmentCommand,
        key: String,
    ) {
        val validReason =
            command.reason.isNotBlank() && command.reason.length <= MAX_REASON && !command.reason.contains('\u0000')
        val validKey = key.isNotBlank() && key.length <= MAX_KEY_LENGTH && key.all { it.code in PRINTABLE_ASCII }
        if (!command.confirmed || !validReason || !validKey) {
            fail("validation_error")
        }
    }

    private fun <T> transaction(
        callContext: CoroutineContext,
        block: (OnboardingSql) -> T,
    ): T =
        dataSource.connection.use { connection ->
            connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
            connection.autoCommit = false
            try {
                callContext.ensureActive()
                val result = block(OnboardingSql(connection))
                callContext.ensureActive()
                connection.commit()
                result
            } catch (cancelled: CancellationException) {
                connection.rollback()
                throw cancelled
            } catch (failure: Throwable) {
                connection.rollback()
                throw failure
            }
        }

    companion object {
        private const val MAX_RETRIES = 2
        private const val RETRY_DELAY_MS = 10L
        private const val CONNECTION_HASH_LENGTH = 61
        private const val MAX_REASON = 500
        private const val MAX_KEY_LENGTH = 256
        private val PRINTABLE_ASCII = 33..126
    }
}

private fun AssignmentView.fenced(identityExpiry: Instant): AssignmentView =
    copy(
        validUntil =
            minOf(
                validUntil ?: identityExpiry,
                identityExpiry,
            ),
    )
