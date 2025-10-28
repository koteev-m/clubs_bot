package com.example.bot.data.security.webhook

import com.example.bot.config.BotLimits
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

enum class SuspiciousIpReason {
    SECRET_MISMATCH,
    INVALID_CONTENT_TYPE,
    PAYLOAD_TOO_LARGE,
    EMPTY_BODY,
    MALFORMED_JSON,
    DUPLICATE_UPDATE,
    INVALID_METHOD,
}

data class SuspiciousIpRecord(
    val id: Long,
    val ip: String,
    val userAgent: String?,
    val reason: SuspiciousIpReason,
    val details: String?,
    val createdAt: Instant,
)

class SuspiciousIpRepository(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun record(
        ip: String,
        userAgent: String?,
        reason: SuspiciousIpReason,
        details: String? = null,
    ): Long {
        val now = Instant.now(clock).toOffsetDateTime()
        return newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            SuspiciousIpTable.insert {
                it[SuspiciousIpTable.ip] = ip
                it[SuspiciousIpTable.userAgent] = userAgent
                it[SuspiciousIpTable.reason] = reason.name
                it[SuspiciousIpTable.details] = details
                it[SuspiciousIpTable.createdAt] = now
            }[SuspiciousIpTable.id]
        }
    }

    suspend fun listRecent(limit: Int = 50): List<SuspiciousIpRecord> {
        return newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            SuspiciousIpTable
                .selectAll()
                .orderBy(SuspiciousIpTable.createdAt to SortOrder.DESC)
                .limit(limit)
                .map { it.toRecord() }
        }
    }

    private fun ResultRow.toRecord(): SuspiciousIpRecord {
        return SuspiciousIpRecord(
            id = this[SuspiciousIpTable.id],
            ip = this[SuspiciousIpTable.ip],
            userAgent = this[SuspiciousIpTable.userAgent],
            reason = SuspiciousIpReason.valueOf(this[SuspiciousIpTable.reason]),
            details = this[SuspiciousIpTable.details],
            createdAt = this[SuspiciousIpTable.createdAt].toInstant(),
        )
    }
}

sealed interface DedupResult {
    data class FirstSeen(val updateId: Long) : DedupResult

    data class Duplicate(
        val updateId: Long,
        val firstSeenAt: Instant,
        val lastSeenAt: Instant,
        val duplicateCount: Int,
    ) : DedupResult
}

data class WebhookUpdateRecord(
    val updateId: Long,
    val firstSeenAt: Instant,
    val lastSeenAt: Instant,
    val duplicateCount: Int,
)

class WebhookUpdateDedupRepository(
    private val db: Database,
    private val ttl: Duration = BotLimits.Webhook.dedupTtl,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun mark(updateId: Long): DedupResult {
        val now = Instant.now(clock)
        val nowOffset = now.toOffsetDateTime()
        val expireBefore = now.minus(ttl).toOffsetDateTime()
        return newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            cleanupExpired(expireBefore)
            try {
                WebhookUpdateDedupTable.insert {
                    it[WebhookUpdateDedupTable.updateId] = updateId
                    it[WebhookUpdateDedupTable.firstSeenAt] = nowOffset
                    it[WebhookUpdateDedupTable.lastSeenAt] = nowOffset
                    it[WebhookUpdateDedupTable.duplicateCount] = 0
                }
                DedupResult.FirstSeen(updateId)
            } catch (ex: ExposedSQLException) {
                if (!ex.isDuplicateKeyError()) {
                    throw ex
                }
                val row =
                    WebhookUpdateDedupTable
                        .selectAll()
                        .where { WebhookUpdateDedupTable.updateId eq updateId }
                        .first()
                val duplicates = row[WebhookUpdateDedupTable.duplicateCount] + 1
                WebhookUpdateDedupTable.update({ WebhookUpdateDedupTable.updateId eq updateId }) {
                    it[lastSeenAt] = nowOffset
                    it[duplicateCount] = duplicates
                }
                DedupResult.Duplicate(
                    updateId = updateId,
                    firstSeenAt = row[WebhookUpdateDedupTable.firstSeenAt].toInstant(),
                    lastSeenAt = now,
                    duplicateCount = duplicates,
                )
            }
        }
    }

    suspend fun find(updateId: Long): WebhookUpdateRecord? {
        val expireBefore = Instant.now(clock).minus(ttl).toOffsetDateTime()
        return newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            cleanupExpired(expireBefore)
            WebhookUpdateDedupTable
                .selectAll()
                .where { WebhookUpdateDedupTable.updateId eq updateId }
                .firstOrNull()
                ?.toRecord()
        }
    }

    private fun cleanupExpired(expireBefore: OffsetDateTime) {
        WebhookUpdateDedupTable.deleteWhere { WebhookUpdateDedupTable.firstSeenAt less expireBefore }
    }

    private fun ResultRow.toRecord(): WebhookUpdateRecord {
        return WebhookUpdateRecord(
            updateId = this[WebhookUpdateDedupTable.updateId],
            firstSeenAt = this[WebhookUpdateDedupTable.firstSeenAt].toInstant(),
            lastSeenAt = this[WebhookUpdateDedupTable.lastSeenAt].toInstant(),
            duplicateCount = this[WebhookUpdateDedupTable.duplicateCount],
        )
    }
}

private fun Instant.toOffsetDateTime(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

private fun ExposedSQLException.isDuplicateKeyError(): Boolean {
    val state = sqlState
    if (state == "23505") {
        return true
    }
    val message = message?.lowercase()
    return message?.contains("duplicate") == true || message?.contains("unique index or primary key violation") == true
}
