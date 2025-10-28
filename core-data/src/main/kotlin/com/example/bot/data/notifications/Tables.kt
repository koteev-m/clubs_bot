package com.example.bot.data.notifications

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.json.jsonb

object NotifySegments : Table("notify_segments") {
    val id = long("id").autoIncrement()
    val title = text("title")
    val definition = jsonb<JsonElement>("definition", Json)
    val createdBy = long("created_by")
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}

object NotifyCampaigns : Table("notify_campaigns") {
    val id = long("id").autoIncrement()
    val title = text("title")
    val status = text("status")
    val kind = text("kind")
    val clubId = long("club_id").nullable()
    val messageThreadId = integer("message_thread_id").nullable()
    val segmentId = long("segment_id").nullable()
    val scheduleCron = text("schedule_cron").nullable()
    val startsAt = timestampWithTimeZone("starts_at").nullable()
    val createdBy = long("created_by")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object UserSubscriptions : Table("user_subscriptions") {
    val userId = long("user_id")
    val clubId = long("club_id").nullable()
    val topic = text("topic")
    val optIn = bool("opt_in").default(true)
    val lang = text("lang").default("ru")
    override val primaryKey = PrimaryKey(userId, clubId, topic)
}

enum class OutboxStatus {
    NEW,
    SENT,
    FAILED,
}

object NotificationsOutboxTable : Table("notifications_outbox") {
    val id = long("id").autoIncrement()
    val clubId = long("club_id").nullable()
    val targetChatId = long("target_chat_id")
    val messageThreadId = integer("message_thread_id").nullable()
    val kind = text("kind")
    val payload = jsonb<JsonElement>("payload", Json)
    val status = text("status").default(OutboxStatus.NEW.name)
    val attempts = integer("attempts").default(0)
    val nextAttemptAt = timestampWithTimeZone("next_attempt_at").nullable()
    val lastError = text("last_error").nullable()
    val recipientType = text("recipient_type")
    val recipientId = long("recipient_id")
    val dedupKey = text("dedup_key").nullable().uniqueIndex()
    val priority = integer("priority").default(100)
    val campaignId = long("campaign_id").nullable()
    val method = text("method")
    val parseMode = text("parse_mode").nullable()
    val attachments = jsonb<JsonElement>("attachments", Json).nullable()
    val language = text("language").nullable()
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestamp())
    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_notifications_outbox_status_attempt", false, status, nextAttemptAt)
        index("idx_notifications_outbox_campaign_id", false, campaignId)
        index("idx_notifications_outbox_priority_created_at", false, priority, createdAt)
    }
}
