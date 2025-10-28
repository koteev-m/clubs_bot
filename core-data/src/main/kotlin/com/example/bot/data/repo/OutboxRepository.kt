package com.example.bot.data.repo

import com.example.bot.data.notifications.NotificationsOutboxTable
import com.example.bot.data.notifications.OutboxStatus
import com.example.bot.notifications.NotifyMessage
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insertIgnore
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import java.time.OffsetDateTime

class OutboxRepository(private val db: Database) {
    data class Record(val id: Long, val message: NotifyMessage, val dedupKey: String?, val attempts: Int)

    private val json = Json

    suspend fun enqueue(
        msg: NotifyMessage,
        campaignId: Long? = null,
        priority: Int = 100,
        dedupKey: String? = null,
    ) {
        return newSuspendedTransaction(db = db) {
            NotificationsOutboxTable.insertIgnore {
                it[targetChatId] = msg.chatId
                it[messageThreadId] = msg.messageThreadId
                it[kind] = msg.method.name
                it[payload] = json.encodeToJsonElement(NotifyMessage.serializer(), msg)
                it[status] = OutboxStatus.NEW.name
                it[nextAttemptAt] = OffsetDateTime.now()
                it[recipientType] = "chat"
                it[recipientId] = msg.chatId
                it[NotificationsOutboxTable.dedupKey] = dedupKey ?: msg.dedupKey
                it[NotificationsOutboxTable.priority] = priority
                it[NotificationsOutboxTable.campaignId] = campaignId
                it[method] = msg.method.name
                it[parseMode] = msg.parseMode?.name
            }
        }
    }

    suspend fun pickBatch(
        now: OffsetDateTime,
        limit: Int,
    ): List<Record> {
        return newSuspendedTransaction(db = db) {
            NotificationsOutboxTable
                .selectAll()
                .where {
                    (NotificationsOutboxTable.status eq OutboxStatus.NEW.name) and
                        (NotificationsOutboxTable.nextAttemptAt lessEq now)
                }.orderBy(
                    NotificationsOutboxTable.priority to SortOrder.ASC,
                    NotificationsOutboxTable.createdAt to SortOrder.ASC,
                ).forUpdate()
                .limit(limit)
                .map {
                    Record(
                        id = it[NotificationsOutboxTable.id],
                        message =
                            json.decodeFromJsonElement(
                                NotifyMessage.serializer(),
                                it[NotificationsOutboxTable.payload],
                            ),
                        dedupKey = it[NotificationsOutboxTable.dedupKey],
                        attempts = it[NotificationsOutboxTable.attempts],
                    )
                }
        }
    }

    suspend fun markSent(
        id: Long,
        messageId: Long?,
    ) {
        return newSuspendedTransaction(db = db) {
            NotificationsOutboxTable.update({ NotificationsOutboxTable.id eq id }) {
                it[status] = OutboxStatus.SENT.name
                it[lastError] = null
                it[nextAttemptAt] = null
                with(org.jetbrains.exposed.sql.SqlExpressionBuilder) {
                    it[attempts] = attempts + 1
                }
            }
        }
    }

    suspend fun markFailed(
        id: Long,
        error: String?,
        nextRetryAt: OffsetDateTime,
    ) {
        return newSuspendedTransaction(db = db) {
            NotificationsOutboxTable.update({ NotificationsOutboxTable.id eq id }) {
                it[status] = OutboxStatus.NEW.name
                it[lastError] = error
                it[NotificationsOutboxTable.nextAttemptAt] = nextRetryAt
                with(org.jetbrains.exposed.sql.SqlExpressionBuilder) {
                    it[attempts] = attempts + 1
                }
            }
        }
    }

    suspend fun postpone(
        id: Long,
        nextRetryAt: OffsetDateTime,
    ) {
        return newSuspendedTransaction(db = db) {
            NotificationsOutboxTable.update({ NotificationsOutboxTable.id eq id }) {
                it[status] = OutboxStatus.NEW.name
                it[lastError] = null
                it[NotificationsOutboxTable.nextAttemptAt] = nextRetryAt
            }
        }
    }

    suspend fun markPermanentFailure(
        id: Long,
        error: String?,
    ) {
        return newSuspendedTransaction(db = db) {
            NotificationsOutboxTable.update({ NotificationsOutboxTable.id eq id }) {
                it[status] = OutboxStatus.FAILED.name
                it[lastError] = error
                it[NotificationsOutboxTable.nextAttemptAt] = null
                with(org.jetbrains.exposed.sql.SqlExpressionBuilder) {
                    it[attempts] = attempts + 1
                }
            }
        }
    }

    suspend fun isSent(dedupKey: String): Boolean {
        return newSuspendedTransaction(db = db) {
            NotificationsOutboxTable
                .selectAll()
                .where {
                    (NotificationsOutboxTable.dedupKey eq dedupKey) and
                        (NotificationsOutboxTable.status eq OutboxStatus.SENT.name)
                }.empty()
                .not()
        }
    }
}
