package com.example.bot.data.club

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object ClubOpsChatConfigTable : Table("club_ops_chat_config") {
    val clubId = long("club_id")
    val chatId = long("chat_id")
    val bookingsThreadId = integer("bookings_thread_id").nullable()
    val checkinThreadId = integer("checkin_thread_id").nullable()
    val supportThreadId = integer("support_thread_id").nullable()
    val updatedAt = timestampWithTimeZone("updated_at")

    override val primaryKey = PrimaryKey(clubId)
}
