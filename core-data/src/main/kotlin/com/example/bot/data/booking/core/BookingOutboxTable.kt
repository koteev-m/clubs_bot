package com.example.bot.data.booking.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.json.jsonb

object BookingOutboxTable : Table("booking_outbox") {
    val id = long("id").autoIncrement()
    val topic = text("topic")
    val payload = jsonb<JsonObject>("payload", Json)
    val status = text("status")
    val attempts = integer("attempts").default(0)
    val nextAttemptAt = timestampWithTimeZone("next_attempt_at")
    val lastError = text("last_error").nullable()
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestamp())
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestamp())
    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_booking_outbox_status_attempt", false, status, nextAttemptAt)
    }
}
