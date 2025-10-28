package com.example.bot.data.club

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object WaitlistTable : Table("waitlist") {
    val id = long("id").autoIncrement()
    val clubId = long("club_id")
    val eventId = long("event_id")
    val userId = long("user_id")
    val partySize = integer("party_size")
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestamp())
    val calledAt = timestampWithTimeZone("called_at").nullable()
    val expiresAt = timestampWithTimeZone("expires_at").nullable()
    val status = text("status")
    override val primaryKey = PrimaryKey(id)
}
