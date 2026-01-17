package com.example.bot.data.db

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object Clubs : IntIdTable("clubs") {
    val name = varchar("name", 255)
    val description = text("description").nullable()
    val adminChatId = long("admin_chat_id").nullable()
    val timezone = varchar("timezone", 64).default("Europe/Moscow")
    val city = varchar("city", 128).default("Unknown")
    val genres = text("genres").default("[]")
    val tags = text("tags").default("[]")
    val logoUrl = text("logo_url").nullable()
    val isActive = bool("is_active").default(true)
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestamp())
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestamp())

    val generalTopicId = integer("general_topic_id").nullable()
    val bookingsTopicId = integer("bookings_topic_id").nullable()
    val listsTopicId = integer("lists_topic_id").nullable()
    val qaTopicId = integer("qa_topic_id").nullable()
    val mediaTopicId = integer("media_topic_id").nullable()
    val systemTopicId = integer("system_topic_id").nullable()
}

object HqNotify : Table("hq_notify") {
    val id = short("id").default(1).uniqueIndex()
    val chatId = long("chat_id")
    val generalTopicId = integer("general_topic_id").nullable()
    val bookingsTopicId = integer("bookings_topic_id").nullable()
    val listsTopicId = integer("lists_topic_id").nullable()
    val qaTopicId = integer("qa_topic_id").nullable()
    val systemTopicId = integer("system_topic_id").nullable()
    override val primaryKey = PrimaryKey(id)
}
