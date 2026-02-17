package com.example.bot.data.security.webhook

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object SuspiciousIpTable : Table("suspicious_ips") {
    val id = long("id").autoIncrement()
    val ip = text("ip")
    val userAgent = text("user_agent").nullable()
    val reason = text("reason")
    val details = text("details").nullable()
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestamp())

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_suspicious_ips_created_at", false, createdAt)
        index("idx_suspicious_ips_ip", false, ip)
    }
}

object WebhookUpdateDedupTable : Table("webhook_update_dedup") {
    val updateId = long("update_id")
    val firstSeenAt = timestampWithTimeZone("first_seen_at")
    val lastSeenAt = timestampWithTimeZone("last_seen_at")
    val duplicateCount = integer("duplicate_count").default(0)

    override val primaryKey = PrimaryKey(updateId)

    init {
        index("idx_webhook_update_dedup_first_seen", false, firstSeenAt)
    }
}

object TelegramWebhookUpdatesTable : Table("telegram_webhook_updates") {
    val id = long("id").autoIncrement()
    val updateId = long("update_id")
    val receivedAt = timestampWithTimeZone("received_at").defaultExpression(CurrentTimestamp())
    val payloadJson = text("payload_json")
    val status = varchar("status", length = 16)
    val attempts = integer("attempts").default(0)
    val nextAttemptAt = timestampWithTimeZone("next_attempt_at").defaultExpression(CurrentTimestamp())
    val lastError = text("last_error").nullable()
    val processedAt = timestampWithTimeZone("processed_at").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uq_telegram_webhook_updates_update_id", updateId)
        index("idx_telegram_webhook_updates_status_next_attempt", false, status, nextAttemptAt)
        index("idx_telegram_webhook_updates_received_at", false, receivedAt)
    }
}
