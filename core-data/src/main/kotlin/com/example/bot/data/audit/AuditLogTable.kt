package com.example.bot.data.audit

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

/** Audit log table mapping. */
object AuditLogTable : Table("audit_log") {
    val id = long("id").autoIncrement()
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestamp())
    val clubId = long("club_id").nullable()
    val nightId = long("night_id").nullable()
    val actorUserId = long("actor_user_id").nullable()
    val actorRole = text("actor_role").nullable()
    val subjectUserId = long("subject_user_id").nullable()
    val entityType = text("entity_type")
    val entityId = long("entity_id").nullable()
    val action = text("action")
    val fingerprint = text("fingerprint")
    val metadataJson = text("metadata_json")
    override val primaryKey = PrimaryKey(id)
}
