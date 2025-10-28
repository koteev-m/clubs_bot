package com.example.bot.data.notifications

import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.OffsetDateTime

data class NotifySegment(
    val id: Long,
    val title: String,
    val definition: JsonElement,
    val createdBy: Long,
    val createdAt: OffsetDateTime,
)

class NotifySegmentsRepository(private val db: Database) {
    suspend fun insert(
        title: String,
        definition: JsonElement,
        createdBy: Long,
    ): Long {
        return newSuspendedTransaction(db = db) {
            NotifySegments.insert {
                it[NotifySegments.title] = title
                it[NotifySegments.definition] = definition
                it[NotifySegments.createdBy] = createdBy
            }[NotifySegments.id]
        }
    }

    suspend fun find(id: Long): NotifySegment? {
        return newSuspendedTransaction(db = db) {
            NotifySegments
                .selectAll()
                .where { NotifySegments.id eq id }
                .map { toSegment(it) }
                .singleOrNull()
        }
    }

    private fun toSegment(row: ResultRow): NotifySegment {
        return NotifySegment(
            id = row[NotifySegments.id],
            title = row[NotifySegments.title],
            definition = row[NotifySegments.definition],
            createdBy = row[NotifySegments.createdBy],
            createdAt = row[NotifySegments.createdAt],
        )
    }
}

data class NotifyCampaign(
    val id: Long,
    val title: String,
    val status: String,
    val kind: String,
    val clubId: Long?,
    val messageThreadId: Int?,
    val segmentId: Long?,
    val scheduleCron: String?,
    val startsAt: OffsetDateTime?,
    val createdBy: Long,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

class NotifyCampaignsRepository(private val db: Database) {
    suspend fun insert(campaign: NotifyCampaign): Long {
        return newSuspendedTransaction(db = db) {
            NotifyCampaigns.insert {
                it[NotifyCampaigns.title] = campaign.title
                it[NotifyCampaigns.status] = campaign.status
                it[NotifyCampaigns.kind] = campaign.kind
                it[NotifyCampaigns.clubId] = campaign.clubId
                it[NotifyCampaigns.messageThreadId] = campaign.messageThreadId
                it[NotifyCampaigns.segmentId] = campaign.segmentId
                it[NotifyCampaigns.scheduleCron] = campaign.scheduleCron
                it[NotifyCampaigns.startsAt] = campaign.startsAt
                it[NotifyCampaigns.createdBy] = campaign.createdBy
            }[NotifyCampaigns.id]
        }
    }

    suspend fun find(id: Long): NotifyCampaign? {
        return newSuspendedTransaction(db = db) {
            NotifyCampaigns
                .selectAll()
                .where { NotifyCampaigns.id eq id }
                .map { toCampaign(it) }
                .singleOrNull()
        }
    }

    private fun toCampaign(row: ResultRow): NotifyCampaign {
        return NotifyCampaign(
            id = row[NotifyCampaigns.id],
            title = row[NotifyCampaigns.title],
            status = row[NotifyCampaigns.status],
            kind = row[NotifyCampaigns.kind],
            clubId = row[NotifyCampaigns.clubId],
            messageThreadId = row[NotifyCampaigns.messageThreadId],
            segmentId = row[NotifyCampaigns.segmentId],
            scheduleCron = row[NotifyCampaigns.scheduleCron],
            startsAt = row[NotifyCampaigns.startsAt],
            createdBy = row[NotifyCampaigns.createdBy],
            createdAt = row[NotifyCampaigns.createdAt],
            updatedAt = row[NotifyCampaigns.updatedAt],
        )
    }
}

data class UserSubscription(
    val userId: Long,
    val clubId: Long?,
    val topic: String,
    val optIn: Boolean,
    val lang: String,
)

class UserSubscriptionsRepository(private val db: Database) {
    suspend fun insert(sub: UserSubscription) {
        return newSuspendedTransaction(db = db) {
            UserSubscriptions.insert {
                it[UserSubscriptions.userId] = sub.userId
                it[UserSubscriptions.clubId] = sub.clubId
                it[UserSubscriptions.topic] = sub.topic
                it[UserSubscriptions.optIn] = sub.optIn
                it[UserSubscriptions.lang] = sub.lang
            }
        }
    }

    suspend fun find(
        userId: Long,
        clubId: Long?,
        topic: String,
    ): UserSubscription? {
        return newSuspendedTransaction(db = db) {
            UserSubscriptions
                .selectAll()
                .where {
                    (UserSubscriptions.userId eq userId) and
                        (UserSubscriptions.topic eq topic) and
                        (clubId?.let { UserSubscriptions.clubId eq it } ?: UserSubscriptions.clubId.isNull())
                }
                .map { toSubscription(it) }
                .singleOrNull()
        }
    }

    private fun toSubscription(row: ResultRow): UserSubscription {
        return UserSubscription(
            userId = row[UserSubscriptions.userId],
            clubId = row[UserSubscriptions.clubId],
            topic = row[UserSubscriptions.topic],
            optIn = row[UserSubscriptions.optIn],
            lang = row[UserSubscriptions.lang],
        )
    }
}

data class OutboxRecord(
    val id: Long,
    val recipientType: String,
    val recipientId: Long,
    val dedupKey: String?,
    val priority: Int,
    val campaignId: Long?,
    val method: String,
    val payload: JsonElement,
    val createdAt: OffsetDateTime,
)

class NotificationsOutboxRepository(private val db: Database) {
    suspend fun enqueue(
        kind: String,
        payload: JsonElement,
        recipientType: String,
        recipientId: Long,
        clubId: Long? = null,
        targetChatId: Long = 0,
        messageThreadId: Int? = null,
        method: String = "EVENT",
        priority: Int = 100,
        dedupKey: String? = null,
        campaignId: Long? = null,
        language: String? = null,
    ): Long {
        return newSuspendedTransaction(db = db) {
            NotificationsOutboxTable.insert { row ->
                row[NotificationsOutboxTable.clubId] = clubId
                row[NotificationsOutboxTable.targetChatId] = targetChatId
                row[NotificationsOutboxTable.messageThreadId] = messageThreadId
                row[NotificationsOutboxTable.kind] = kind
                row[NotificationsOutboxTable.payload] = payload
                row[NotificationsOutboxTable.recipientType] = recipientType
                row[NotificationsOutboxTable.recipientId] = recipientId
                row[NotificationsOutboxTable.dedupKey] = dedupKey
                row[NotificationsOutboxTable.priority] = priority
                row[NotificationsOutboxTable.campaignId] = campaignId
                row[NotificationsOutboxTable.method] = method
                row[NotificationsOutboxTable.language] = language
            }[NotificationsOutboxTable.id]
        }
    }

    suspend fun insert(record: OutboxRecord) {
        return newSuspendedTransaction(db = db) {
            NotificationsOutboxTable.insert {
                it[NotificationsOutboxTable.recipientType] = record.recipientType
                it[NotificationsOutboxTable.recipientId] = record.recipientId
                it[NotificationsOutboxTable.dedupKey] = record.dedupKey
                it[NotificationsOutboxTable.priority] = record.priority
                it[NotificationsOutboxTable.campaignId] = record.campaignId
                it[NotificationsOutboxTable.method] = record.method
                it[NotificationsOutboxTable.payload] = record.payload
                it[NotificationsOutboxTable.status] = OutboxStatus.NEW.name
            }
        }
    }

    suspend fun find(id: Long): OutboxRecord? {
        return newSuspendedTransaction(db = db) {
            NotificationsOutboxTable
                .selectAll()
                .where { NotificationsOutboxTable.id eq id }
                .map { toOutbox(it) }
                .singleOrNull()
        }
    }

    private fun toOutbox(row: ResultRow): OutboxRecord {
        return OutboxRecord(
            id = row[NotificationsOutboxTable.id],
            recipientType = row[NotificationsOutboxTable.recipientType],
            recipientId = row[NotificationsOutboxTable.recipientId],
            dedupKey = row[NotificationsOutboxTable.dedupKey],
            priority = row[NotificationsOutboxTable.priority],
            campaignId = row[NotificationsOutboxTable.campaignId],
            method = row[NotificationsOutboxTable.method],
            payload = row[NotificationsOutboxTable.payload],
            createdAt = row[NotificationsOutboxTable.createdAt],
        )
    }
}
