package com.example.bot.data.club

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/** Tables backing guest list repositories. */
object GuestListsTable : Table("guest_lists") {
    val id = long("id").autoIncrement()
    val clubId = long("club_id")
    val eventId = long("event_id")
    val promoterId = long("promoter_id").nullable()
    val ownerType = text("owner_type")
    val ownerUserId = long("owner_user_id")
    val title = text("title")
    val capacity = integer("capacity")
    val arrivalWindowStart = timestampWithTimeZone("arrival_window_start").nullable()
    val arrivalWindowEnd = timestampWithTimeZone("arrival_window_end").nullable()
    val status = text("status")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object GuestListEntriesTable : Table("guest_list_entries") {
    val id = long("id").autoIncrement()
    val guestListId = long("guest_list_id")
    val displayName = text("display_name")
    val fullName = text("full_name")
    val tgUsername = text("tg_username").nullable()
    val phone = text("phone_e164").nullable()
    val telegramUserId = long("telegram_user_id").nullable()
    val plusOnesAllowed = integer("plus_ones_allowed")
    val plusOnesUsed = integer("plus_ones_used")
    val category = text("category")
    val comment = text("comment").nullable()
    val status = text("status")
    val checkedInAt = timestampWithTimeZone("checked_in_at").nullable()
    val checkedInBy = long("checked_in_by").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object InvitationsTable : Table("invitations") {
    val id = long("id").autoIncrement()
    val guestListEntryId = long("guest_list_entry_id")
    val tokenHash = text("token_hash")
    val channel = text("channel")
    val expiresAt = timestampWithTimeZone("expires_at")
    val revokedAt = timestampWithTimeZone("revoked_at").nullable()
    val usedAt = timestampWithTimeZone("used_at").nullable()
    val createdBy = long("created_by").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}

object CheckinsTable : Table("checkins") {
    val id = long("id").autoIncrement()
    val clubId = long("club_id").nullable()
    val eventId = long("event_id").nullable()
    val subjectType = text("subject_type")
    val subjectId = text("subject_id")
    val checkedBy = long("checked_by").nullable()
    val method = text("method")
    val resultStatus = text("result_status")
    val denyReason = text("deny_reason").nullable()
    val occurredAt = timestampWithTimeZone("occurred_at")
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(id)
}
