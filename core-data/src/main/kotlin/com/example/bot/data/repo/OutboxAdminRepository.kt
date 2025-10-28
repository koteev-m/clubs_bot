package com.example.bot.data.repo

import com.example.bot.data.booking.core.BookingOutboxTable
import com.example.bot.data.booking.core.OutboxMessageStatus
import com.example.bot.data.notifications.NotificationsOutboxTable
import com.example.bot.data.notifications.OutboxStatus
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.HashMap
import java.util.LinkedHashMap
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.statements.api.PreparedStatementApi

interface OutboxAdminRepository {
    suspend fun list(filter: AdminFilter, page: Page): Paged<OutboxRecord>

    suspend fun markForReplayByIds(ids: List<Long>, actor: String, dryRun: Boolean): ReplayResult

    suspend fun markForReplayByFilter(
        filter: AdminFilter,
        maxRows: Int,
        actor: String,
        dryRun: Boolean,
    ): ReplayResult

    suspend fun stats(filter: AdminFilter): AdminStats
}

data class AdminFilter(
    val topic: String? = null,
    val status: String? = null,
    val attemptsMin: Int? = null,
    val createdAfter: Instant? = null,
    val idIn: List<Long>? = null,
)

data class Page(val limit: Int, val offset: Int, val sort: Sort)

data class Sort(val field: SortField, val direction: SortDirection)

enum class SortField { CreatedAt, Attempts, Id }

enum class SortDirection { ASC, DESC }

data class Paged<T>(val items: List<T>, val total: Long)

data class ReplayResult(
    val totalCandidates: Int,
    val affected: Int,
    val dryRun: Boolean,
    val topic: String?,
)

data class AdminStats(
    val total: Long,
    val byStatus: Map<String, Long>,
    val byTopic: Map<String, Long>,
)

enum class OutboxSource { BOOKING, NOTIFICATIONS }

data class OutboxRecord(
    val id: Long,
    val topic: String,
    val status: String,
    val attempts: Int,
    val nextAttemptAt: Instant?,
    val lastError: String?,
    val createdAt: Instant,
    val source: OutboxSource,
)

private data class ReplayCandidate(
    val id: Long,
    val topic: String,
    val createdAt: Instant,
    val source: OutboxSource,
)

private data class TableSpec(
    val name: String,
    val source: OutboxSource,
    val topicColumn: String,
    val statusColumn: String,
    val attemptsColumn: String,
    val createdAtColumn: String,
    val nextAttemptColumn: String,
    val lastErrorColumn: String,
)

private data class SqlConditions(
    val clauses: List<String> = emptyList(),
    val params: List<SqlParameter> = emptyList(),
) {
    fun and(clause: String, vararg additional: SqlParameter): SqlConditions {
        if (clause.isBlank()) return this
        val newClauses = clauses + clause
        val newParams = params + additional
        return SqlConditions(newClauses, newParams)
    }

    fun toWhereClause(): String =
        if (clauses.isEmpty()) {
            ""
        } else {
            "WHERE " + clauses.joinToString(" AND ")
        }
}

private sealed interface SqlParameter {
    fun bind(statement: PreparedStatementApi, index: Int)

    data class StringParam(val value: String) : SqlParameter {
        override fun bind(statement: PreparedStatementApi, index: Int) {
            statement[index] = value
        }
    }

    data class IntParam(val value: Int) : SqlParameter {
        override fun bind(statement: PreparedStatementApi, index: Int) {
            statement[index] = value
        }
    }

    data class LongParam(val value: Long) : SqlParameter {
        override fun bind(statement: PreparedStatementApi, index: Int) {
            statement[index] = value
        }
    }

    data class InstantParam(val value: Instant) : SqlParameter {
        override fun bind(statement: PreparedStatementApi, index: Int) {
            val offset = OffsetDateTime.ofInstant(value, ZoneOffset.UTC)
            statement[index] = offset
        }
    }
}

private fun SqlConditions.andIn(
    column: String,
    values: Collection<String>,
): SqlConditions {
    if (values.isEmpty()) return this
    val placeholders = values.joinToString(",") { "?" }
    val params = values.map { SqlParameter.StringParam(it) }.toTypedArray()
    return and("$column IN ($placeholders)", *params)
}

private fun buildConditions(filter: AdminFilter, spec: TableSpec): SqlConditions {
    var conditions = SqlConditions()
    filter.topic?.takeIf { it.isNotBlank() }?.let { topic ->
        conditions = conditions.and("${spec.topicColumn} = ?", SqlParameter.StringParam(topic))
    }
    filter.status?.takeIf { it.isNotBlank() }?.let { status ->
        conditions = conditions.and("${spec.statusColumn} = ?", SqlParameter.StringParam(status))
    }
    filter.attemptsMin?.takeIf { it > 0 }?.let { attempts ->
        conditions = conditions.and("${spec.attemptsColumn} >= ?", SqlParameter.IntParam(attempts))
    }
    filter.createdAfter?.let { createdAfter ->
        conditions =
            conditions.and(
                "${spec.createdAtColumn} >= ?",
                SqlParameter.InstantParam(createdAfter),
            )
    }
    filter.idIn?.filter { it > 0 }?.distinct()?.takeIf { it.isNotEmpty() }?.let { ids ->
        val placeholders = ids.joinToString(",") { "?" }
        val params = ids.map { SqlParameter.LongParam(it) }.toTypedArray()
        conditions = conditions.and("${specPrimaryKey(spec)} IN ($placeholders)", *params)
    }
    return conditions
}

private fun specPrimaryKey(spec: TableSpec): String = "id"

private fun SqlConditions.restrictToStatuses(
    spec: TableSpec,
    allowed: Collection<String>,
): SqlConditions {
    if (allowed.isEmpty()) return this
    return andIn(spec.statusColumn, allowed)
}

class OutboxAdminRepositoryImpl(
    private val db: Database,
    private val clock: Clock = Clock.systemUTC(),
) : OutboxAdminRepository {
    private val bookingSpec =
        TableSpec(
            name = BookingOutboxTable.tableName,
            source = OutboxSource.BOOKING,
            topicColumn = "topic",
            statusColumn = "status",
            attemptsColumn = "attempts",
            createdAtColumn = "created_at",
            nextAttemptColumn = "next_attempt_at",
            lastErrorColumn = "last_error",
        )

    private val notificationsSpec =
        TableSpec(
            name = NotificationsOutboxTable.tableName,
            source = OutboxSource.NOTIFICATIONS,
            topicColumn = "kind",
            statusColumn = "status",
            attemptsColumn = "attempts",
            createdAtColumn = "created_at",
            nextAttemptColumn = "next_attempt_at",
            lastErrorColumn = "last_error",
        )

    private val allowedReplayStatuses =
        setOf(
            OutboxMessageStatus.FAILED.name,
            OutboxStatus.FAILED.name,
            "PERM_ERROR",
        )

    override suspend fun list(filter: AdminFilter, page: Page): Paged<OutboxRecord> {
        val sortColumn =
            when (page.sort.field) {
                SortField.CreatedAt -> "created_at"
                SortField.Attempts -> "attempts"
                SortField.Id -> "id"
            }
        val sortDirection = if (page.sort.direction == SortDirection.ASC) "ASC" else "DESC"

        val bookingConditions = buildConditions(filter, bookingSpec)
        val notificationsConditions = buildConditions(filter, notificationsSpec)

        val sql =
            """
            SELECT id, topic, status, attempts, next_attempt_at, last_error, created_at, source FROM (
                SELECT id, topic, status, attempts, next_attempt_at, last_error, created_at, 'BOOKING' AS source
                FROM ${bookingSpec.name}
                ${bookingConditions.toWhereClause()}
                UNION ALL
                SELECT id, ${notificationsSpec.topicColumn} AS topic, status, attempts, ${notificationsSpec.nextAttemptColumn} AS next_attempt_at,
                       ${notificationsSpec.lastErrorColumn} AS last_error, ${notificationsSpec.createdAtColumn} AS created_at, 'NOTIFICATIONS' AS source
                FROM ${notificationsSpec.name}
                ${notificationsConditions.toWhereClause()}
            ) combined
            ORDER BY $sortColumn $sortDirection
            LIMIT ? OFFSET ?
            """.trimIndent()

        val params =
            bookingConditions.params +
                notificationsConditions.params +
                listOf(SqlParameter.IntParam(page.limit), SqlParameter.IntParam(page.offset))

        return newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            val items = fetch(sql, params, ::mapRecord)
            val total =
                count(bookingSpec, bookingConditions) +
                    count(notificationsSpec, notificationsConditions)
            Paged(items, total)
        }
    }

    override suspend fun markForReplayByIds(
        ids: List<Long>,
        actor: String,
        dryRun: Boolean,
    ): ReplayResult {
        if (ids.isEmpty()) {
            return ReplayResult(totalCandidates = 0, affected = 0, dryRun = dryRun, topic = null)
        }

        val uniqueIds = ids.filter { it > 0 }.distinct()
        if (uniqueIds.isEmpty()) {
            return ReplayResult(totalCandidates = 0, affected = 0, dryRun = dryRun, topic = null)
        }

        return newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            val bookingRows =
                BookingOutboxTable
                    .selectAll()
                    .where { (BookingOutboxTable.id inList uniqueIds) and (BookingOutboxTable.status inList allowedReplayStatuses) }
                    .map { row ->
                        ReplayCandidate(
                            id = row[BookingOutboxTable.id],
                            topic = row[BookingOutboxTable.topic],
                            createdAt = row[BookingOutboxTable.createdAt].toInstant(),
                            source = OutboxSource.BOOKING,
                        )
                    }
            val notificationRows =
                NotificationsOutboxTable
                    .selectAll()
                    .where {
                        (NotificationsOutboxTable.id inList uniqueIds) and
                            (NotificationsOutboxTable.status inList allowedReplayStatuses)
                    }
                    .map { row ->
                        ReplayCandidate(
                            id = row[NotificationsOutboxTable.id],
                            topic = row[NotificationsOutboxTable.kind],
                            createdAt = row[NotificationsOutboxTable.createdAt].toInstant(),
                            source = OutboxSource.NOTIFICATIONS,
                        )
                    }

            val candidates = bookingRows + notificationRows
            if (candidates.isEmpty()) {
                return@newSuspendedTransaction ReplayResult(0, 0, dryRun, null)
            }

            val affected =
                if (dryRun) {
                    0
                } else {
                    val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                    val bookingAffected =
                        if (bookingRows.isEmpty()) {
                            0
                        } else {
                            BookingOutboxTable.update({
                                (BookingOutboxTable.id inList bookingRows.map { it.id }) and
                                    (BookingOutboxTable.status inList allowedReplayStatuses)
                            }) { statement ->
                                statement[BookingOutboxTable.status] = OutboxMessageStatus.NEW.name
                                statement[BookingOutboxTable.nextAttemptAt] = now
                                statement[BookingOutboxTable.lastError] = null
                                statement[BookingOutboxTable.updatedAt] = now
                            }
                        }
                    val notificationAffected =
                        if (notificationRows.isEmpty()) {
                            0
                        } else {
                            NotificationsOutboxTable.update({
                                (NotificationsOutboxTable.id inList notificationRows.map { it.id }) and
                                    (NotificationsOutboxTable.status inList allowedReplayStatuses)
                            }) { statement ->
                                statement[NotificationsOutboxTable.status] = OutboxStatus.NEW.name
                                statement[NotificationsOutboxTable.nextAttemptAt] = now
                                statement[NotificationsOutboxTable.lastError] = null
                            }
                        }
                    bookingAffected + notificationAffected
                }

            val topic =
                candidates.map { it.topic }.distinct().singleOrNull()
            ReplayResult(
                totalCandidates = candidates.size,
                affected = affected,
                dryRun = dryRun,
                topic = topic,
            )
        }
    }

    override suspend fun markForReplayByFilter(
        filter: AdminFilter,
        maxRows: Int,
        actor: String,
        dryRun: Boolean,
    ): ReplayResult {
        val limit = maxRows.coerceIn(1, 10_000)
        val bookingConditions = buildConditions(filter, bookingSpec).restrictToStatuses(bookingSpec, allowedReplayStatuses)
        val notificationConditions = buildConditions(filter, notificationsSpec).restrictToStatuses(notificationsSpec, allowedReplayStatuses)

        return newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            val bookingCount = count(bookingSpec, bookingConditions)
            val notificationCount = count(notificationsSpec, notificationConditions)
            val totalCandidates = (bookingCount + notificationCount).toInt()
            if (totalCandidates == 0) {
                return@newSuspendedTransaction ReplayResult(0, 0, dryRun, filter.topic)
            }

            val bookingCandidates = selectCandidates(bookingSpec, bookingConditions, limit)
            val notificationCandidates = selectCandidates(notificationsSpec, notificationConditions, limit)

            val combined = (bookingCandidates + notificationCandidates).sortedBy { it.createdAt }.take(limit)
            val affected =
                if (dryRun || combined.isEmpty()) {
                    0
                } else {
                    val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                    val bookingIds = combined.filter { it.source == OutboxSource.BOOKING }.map { it.id }
                    val notificationIds = combined.filter { it.source == OutboxSource.NOTIFICATIONS }.map { it.id }
                    val bookingUpdated =
                        if (bookingIds.isEmpty()) {
                            0
                        } else {
                            BookingOutboxTable.update({
                                (BookingOutboxTable.id inList bookingIds) and
                                    (BookingOutboxTable.status inList allowedReplayStatuses)
                            }) { statement ->
                                statement[BookingOutboxTable.status] = OutboxMessageStatus.NEW.name
                                statement[BookingOutboxTable.nextAttemptAt] = now
                                statement[BookingOutboxTable.lastError] = null
                                statement[BookingOutboxTable.updatedAt] = now
                            }
                        }
                    val notificationUpdated =
                        if (notificationIds.isEmpty()) {
                            0
                        } else {
                            NotificationsOutboxTable.update({
                                (NotificationsOutboxTable.id inList notificationIds) and
                                    (NotificationsOutboxTable.status inList allowedReplayStatuses)
                            }) { statement ->
                                statement[NotificationsOutboxTable.status] = OutboxStatus.NEW.name
                                statement[NotificationsOutboxTable.nextAttemptAt] = now
                                statement[NotificationsOutboxTable.lastError] = null
                            }
                        }
                    bookingUpdated + notificationUpdated
                }

            val topic = filter.topic ?: combined.map { it.topic }.distinct().singleOrNull()
            ReplayResult(totalCandidates = totalCandidates, affected = affected, dryRun = dryRun, topic = topic)
        }
    }

    override suspend fun stats(filter: AdminFilter): AdminStats {
        val bookingConditions = buildConditions(filter, bookingSpec)
        val notificationConditions = buildConditions(filter, notificationsSpec)
        return newSuspendedTransaction(context = Dispatchers.IO, db = db) {
            val total = count(bookingSpec, bookingConditions) + count(notificationsSpec, notificationConditions)
            val byStatus =
                mergeCounts(
                    aggregate(bookingSpec, bookingConditions, bookingSpec.statusColumn, "status"),
                    aggregate(notificationsSpec, notificationConditions, notificationsSpec.statusColumn, "status"),
                )
            val byTopic =
                mergeCounts(
                    aggregate(bookingSpec, bookingConditions, bookingSpec.topicColumn, "topic"),
                    aggregate(notificationsSpec, notificationConditions, notificationsSpec.topicColumn, "topic"),
                )
            AdminStats(
                total = total,
                byStatus = byStatus,
                byTopic = byTopic,
            )
        }
    }

    private fun mergeCounts(first: Map<String, Long>, second: Map<String, Long>): Map<String, Long> {
        if (first.isEmpty()) return second
        if (second.isEmpty()) return first
        val result = HashMap<String, Long>(first.size + second.size)
        first.forEach { (key, value) -> result[key] = (result[key] ?: 0L) + value }
        second.forEach { (key, value) -> result[key] = (result[key] ?: 0L) + value }
        return result
    }

    private fun org.jetbrains.exposed.sql.Transaction.fetch(
        sql: String,
        params: List<SqlParameter>,
        mapper: (ResultSet) -> OutboxRecord,
    ): List<OutboxRecord> {
        val statement = connection.prepareStatement(sql, false)
        try {
            params.forEachIndexed { index, param -> param.bind(statement, index + 1) }
            statement.executeQuery().use { rs ->
                val result = mutableListOf<OutboxRecord>()
                while (rs.next()) {
                    result += mapper(rs)
                }
                return result
            }
        } finally {
            statement.closeIfPossible()
        }
    }

    private fun org.jetbrains.exposed.sql.Transaction.selectCandidates(
        spec: TableSpec,
        conditions: SqlConditions,
        limit: Int,
    ): List<ReplayCandidate> {
        val sql =
            """
            SELECT id, ${spec.topicColumn} AS topic, ${spec.createdAtColumn} AS created_at
            FROM ${spec.name}
            ${conditions.toWhereClause()}
            ORDER BY ${spec.createdAtColumn} ASC
            LIMIT ?
            """.trimIndent()
        val params = conditions.params + SqlParameter.IntParam(limit)
        val statement = connection.prepareStatement(sql, false)
        try {
            params.forEachIndexed { index, param -> param.bind(statement, index + 1) }
            statement.executeQuery().use { rs ->
                val result = mutableListOf<ReplayCandidate>()
                while (rs.next()) {
                    val created = rs.getTimestamp("created_at")?.toInstant() ?: Instant.EPOCH
                    result += ReplayCandidate(
                        id = rs.getLong("id"),
                        topic = rs.getString("topic"),
                        createdAt = created,
                        source = spec.source,
                    )
                }
                return result
            }
        } finally {
            statement.closeIfPossible()
        }
    }

    private fun org.jetbrains.exposed.sql.Transaction.count(
        spec: TableSpec,
        conditions: SqlConditions,
    ): Long {
        val sql = "SELECT COUNT(*) FROM ${spec.name} ${conditions.toWhereClause()}"
        val statement = connection.prepareStatement(sql, false)
        try {
            conditions.params.forEachIndexed { index, param -> param.bind(statement, index + 1) }
            statement.executeQuery().use { rs ->
                return if (rs.next()) rs.getLong(1) else 0L
            }
        } finally {
            statement.closeIfPossible()
        }
    }

    private fun org.jetbrains.exposed.sql.Transaction.aggregate(
        spec: TableSpec,
        conditions: SqlConditions,
        column: String,
        alias: String,
    ): Map<String, Long> {
        val sql =
            "SELECT $column AS $alias, COUNT(*) AS cnt FROM ${spec.name} ${conditions.toWhereClause()} GROUP BY $column"
        val statement = connection.prepareStatement(sql, false)
        try {
            conditions.params.forEachIndexed { index, param -> param.bind(statement, index + 1) }
            statement.executeQuery().use { rs ->
                val map = LinkedHashMap<String, Long>()
                while (rs.next()) {
                    val key = rs.getString(alias) ?: "UNKNOWN"
                    val value = rs.getLong("cnt")
                    map[key] = (map[key] ?: 0L) + value
                }
                return map
            }
        } finally {
            statement.closeIfPossible()
        }
    }

    private fun mapRecord(resultSet: ResultSet): OutboxRecord {
        val id = resultSet.getLong("id")
        val topic = resultSet.getString("topic")
        val status = resultSet.getString("status")
        val attempts = resultSet.getInt("attempts")
        val nextAttempt = resultSet.getTimestamp("next_attempt_at")?.toInstant()
        val lastError = resultSet.getString("last_error")
        val createdAt = resultSet.getTimestamp("created_at")?.toInstant() ?: Instant.EPOCH
        val source = OutboxSource.valueOf(resultSet.getString("source"))
        return OutboxRecord(
            id = id,
            topic = topic,
            status = status,
            attempts = attempts,
            nextAttemptAt = nextAttempt,
            lastError = lastError,
            createdAt = createdAt,
            source = source,
        )
    }
}
