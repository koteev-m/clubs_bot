package com.example.bot.data.visits

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object OperationalNightOverridesTable : Table("operational_night_overrides") {
    val clubId = long("club_id")
    val nightStartUtc = timestampWithTimeZone("night_start_utc")
    val earlyCutoffAt = timestampWithTimeZone("early_cutoff_at").nullable()
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestamp())
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestamp())

    override val primaryKey = PrimaryKey(clubId, nightStartUtc)
}

object ClubVisitsTable : Table("club_visits") {
    val id = long("id").autoIncrement()
    val clubId = long("club_id")
    val nightStartUtc = timestampWithTimeZone("night_start_utc")
    val eventId = long("event_id").nullable()
    val userId = long("user_id")
    val firstCheckinAt = timestampWithTimeZone("first_checkin_at")
    val actorUserId = long("actor_user_id")
    val actorRole = text("actor_role").nullable()
    val entryType = text("entry_type")
    val isEarly = bool("is_early")
    val hasTable = bool("has_table").default(false)
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestamp())
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestamp())

    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex("uq_club_visits_club_night_user", clubId, nightStartUtc, userId)
        index("idx_club_visits_club_night_start", false, clubId, nightStartUtc)
        index("idx_club_visits_user_club_first_checkin", false, userId, clubId, firstCheckinAt)
        index("idx_club_visits_club_event", false, clubId, eventId)
    }
}
