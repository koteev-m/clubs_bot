package com.example.bot.data.security.webhook

import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.math.min

enum class TelegramWebhookUpdateStatus {
    NEW,
    PROCESSING,
    DONE,
    FAILED,
}

sealed interface TelegramWebhookEnqueueResult {
    data class Enqueued(val rowId: Long) : TelegramWebhookEnqueueResult

    data object Duplicate : TelegramWebhookEnqueueResult
}

data class TelegramWebhookQueuedUpdate(
    val id: Long,
    val updateId: Long,
    val payloadJson: String,
    val receivedAt: Instant,
    val attempts: Int,
)

data class TelegramWebhookQueueStats(
    val depth: Long,
    val oldestReceivedAt: Instant?,
)

class TelegramWebhookIngressRepository(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun enqueue(
        updateId: Long,
        payloadJson: String,
    ): TelegramWebhookEnqueueResult {
        val now = Instant.now(clock).toOffsetDateTime()
        return newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            try {
                val id =
                    TelegramWebhookUpdatesTable.insert {
                        it[TelegramWebhookUpdatesTable.updateId] = updateId
                        it[receivedAt] = now
                        it[TelegramWebhookUpdatesTable.payloadJson] = payloadJson
                        it[status] = TelegramWebhookUpdateStatus.NEW.name
                        it[attempts] = 0
                        it[nextAttemptAt] = now
                        it[lastError] = null
                        it[processedAt] = null
                    }[TelegramWebhookUpdatesTable.id]
                TelegramWebhookEnqueueResult.Enqueued(rowId = id)
            } catch (ex: ExposedSQLException) {
                if (!ex.isDuplicateKeyErrorIngress()) {
                    throw ex
                }
                TelegramWebhookEnqueueResult.Duplicate
            }
        }
    }

    suspend fun claimBatch(limit: Int): List<TelegramWebhookQueuedUpdate> {
        if (limit <= 0) {
            return emptyList()
        }
        val now = Instant.now(clock).toOffsetDateTime()
        return newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            val candidates =
                TelegramWebhookUpdatesTable
                    .selectAll()
                    .where { TelegramWebhookUpdatesTable.status inList claimableStatuses }
                    .andWhere { TelegramWebhookUpdatesTable.nextAttemptAt lessEq now }
                    .orderBy(TelegramWebhookUpdatesTable.receivedAt to SortOrder.ASC)
                    .limit(limit * 3)
                    .map { it.toQueuedUpdate() }

            val claimed = mutableListOf<TelegramWebhookQueuedUpdate>()
            for (candidate in candidates) {
                if (claimed.size >= limit) {
                    break
                }
                val updated =
                    execClaim(candidate.id, candidate.attempts)
                if (updated == 1) {
                    claimed += candidate
                }
            }
            claimed
        }
    }

    suspend fun markDone(id: Long): Unit =
        newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            TelegramWebhookUpdatesTable.update({ TelegramWebhookUpdatesTable.id eq id }) {
                it[status] = TelegramWebhookUpdateStatus.DONE.name
                it[processedAt] = Instant.now(clock).toOffsetDateTime()
                it[lastError] = null
            }
        }

    suspend fun markFailed(
        id: Long,
        attempts: Int,
        error: String,
    ) {
        val now = Instant.now(clock)
        val delaySeconds = min(60L, 1L shl min(attempts, 6))
        val nextAttemptAt = now.plusSeconds(delaySeconds).toOffsetDateTime()
        newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            TelegramWebhookUpdatesTable.update({ TelegramWebhookUpdatesTable.id eq id }) {
                it[status] = TelegramWebhookUpdateStatus.FAILED.name
                it[lastError] = error.take(MAX_ERROR_LENGTH)
                it[TelegramWebhookUpdatesTable.nextAttemptAt] = nextAttemptAt
            }
        }
    }

    suspend fun queueStats(): TelegramWebhookQueueStats =
        newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            val depth =
                TelegramWebhookUpdatesTable
                    .selectAll()
                    .where { TelegramWebhookUpdatesTable.status inList queueStatuses }
                    .count()
            val oldest =
                TelegramWebhookUpdatesTable
                    .selectAll()
                    .where { TelegramWebhookUpdatesTable.status inList queueStatuses }
                    .orderBy(TelegramWebhookUpdatesTable.receivedAt to SortOrder.ASC)
                    .limit(1)
                    .firstOrNull()
                    ?.get(TelegramWebhookUpdatesTable.receivedAt)
                    ?.toInstant()
            TelegramWebhookQueueStats(depth = depth, oldestReceivedAt = oldest)
        }

    private fun org.jetbrains.exposed.sql.Transaction.execClaim(
        rowId: Long,
        expectedAttempts: Int,
    ): Int {
        val sql =
            """
            UPDATE telegram_webhook_updates
            SET status = '${TelegramWebhookUpdateStatus.PROCESSING.name}',
                attempts = attempts + 1,
                last_error = NULL
            WHERE id = $rowId
              AND status IN ('${TelegramWebhookUpdateStatus.NEW.name}', '${TelegramWebhookUpdateStatus.FAILED.name}')
              AND next_attempt_at <= CURRENT_TIMESTAMP
              AND attempts = $expectedAttempts
            """.trimIndent()
        return connection.prepareStatement(sql, false).executeUpdate()
    }

    suspend fun pendingCount(): Long =
        newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            TelegramWebhookUpdatesTable
                .selectAll()
                .where { TelegramWebhookUpdatesTable.status inList queueStatuses }
                .count()
        }

    companion object {
        private const val MAX_ERROR_LENGTH = 1024
        private val claimableStatuses =
            listOf(
                TelegramWebhookUpdateStatus.NEW.name,
                TelegramWebhookUpdateStatus.FAILED.name,
            )
        private val queueStatuses =
            listOf(
                TelegramWebhookUpdateStatus.NEW.name,
                TelegramWebhookUpdateStatus.FAILED.name,
                TelegramWebhookUpdateStatus.PROCESSING.name,
            )
    }
}

private fun ResultRow.toQueuedUpdate(): TelegramWebhookQueuedUpdate =
    TelegramWebhookQueuedUpdate(
        id = this[TelegramWebhookUpdatesTable.id],
        updateId = this[TelegramWebhookUpdatesTable.updateId],
        payloadJson = this[TelegramWebhookUpdatesTable.payloadJson],
        receivedAt = this[TelegramWebhookUpdatesTable.receivedAt].toInstant(),
        attempts = this[TelegramWebhookUpdatesTable.attempts],
    )

private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

private fun ExposedSQLException.isDuplicateKeyErrorIngress(): Boolean {
    val state = sqlState
    if (state == "23505") {
        return true
    }
    val message = message?.lowercase()
    return message?.contains("duplicate") == true || message?.contains("unique index or primary key violation") == true
}
