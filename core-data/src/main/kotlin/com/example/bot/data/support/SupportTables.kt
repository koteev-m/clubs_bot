package com.example.bot.data.support

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object TicketsTable : Table("tickets") {
    val id = long("id").autoIncrement()
    val clubId = long("club_id")
    val userId = long("user_id")
    val bookingId = uuid("booking_id").nullable()
    val listEntryId = long("list_entry_id").nullable()
    val topic = text("topic")
    val status = text("status")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    val lastAgentId = long("last_agent_id").nullable()
    val resolutionRating = short("resolution_rating").nullable()
    override val primaryKey = PrimaryKey(id)
}

object TicketMessagesTable : Table("ticket_messages") {
    val id = long("id").autoIncrement()
    val ticketId = long("ticket_id").references(TicketsTable.id)
    val senderType = text("sender_type")
    val text = text("text")
    val attachments = text("attachments").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}
