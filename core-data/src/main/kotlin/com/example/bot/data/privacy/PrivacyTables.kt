package com.example.bot.data.privacy

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object PrivacyRetentionRunsTable : Table("privacy_retention_runs") {
    val id = long("id").autoIncrement()
    val startedAt = timestampWithTimeZone("started_at")
    val finishedAt = timestampWithTimeZone("finished_at")
    val actorUserId = long("actor_user_id").nullable()
    val mode = text("mode")
    val detailsJson = text("details_json")
    override val primaryKey = PrimaryKey(id)
}
