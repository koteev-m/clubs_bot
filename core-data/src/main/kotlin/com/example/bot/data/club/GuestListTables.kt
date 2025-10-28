package com.example.bot.data.club

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/** Tables backing guest list repositories. */
object GuestListsTable : Table("guest_lists") {
    val id = long("id").autoIncrement()
    val clubId = long("club_id")
    val eventId = long("event_id")
    val ownerType = text("owner_type")
    val ownerUserId = long("owner_user_id")
    val title = text("title")
    val capacity = integer("capacity")
    val arrivalWindowStart = timestampWithTimeZone("arrival_window_start").nullable()
    val arrivalWindowEnd = timestampWithTimeZone("arrival_window_end").nullable()
    val status = text("status")
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}

object GuestListEntriesTable : Table("guest_list_entries") {
    val id = long("id").autoIncrement()
    val guestListId = long("guest_list_id")
    val fullName = text("full_name")
    val tgUsername = text("tg_username").nullable()
    val phone = text("phone_e164").nullable()
    val plusOnesAllowed = integer("plus_ones_allowed")
    val plusOnesUsed = integer("plus_ones_used")
    val category = text("category")
    val comment = text("comment").nullable()
    val status = text("status")
    val checkedInAt = timestampWithTimeZone("checked_in_at").nullable()
    val checkedInBy = long("checked_in_by").nullable()
    override val primaryKey = PrimaryKey(id)
}
