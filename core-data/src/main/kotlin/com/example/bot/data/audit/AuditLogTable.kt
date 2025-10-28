package com.example.bot.data.audit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone
import org.jetbrains.exposed.sql.json.jsonb

/** Audit log table mapping. */
object AuditLogTable : Table("audit_log") {
    val id = long("id").autoIncrement()
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestamp())
    val userId = long("user_id").nullable()
    val action = text("action")
    val resource = text("resource")
    val resourceId = text("resource_id").nullable()
    val clubId = long("club_id").nullable()
    val ip = varchar("ip", 64).nullable()
    val result = text("result")
    val meta = jsonb<JsonElement>("meta", Json)
    override val primaryKey = PrimaryKey(id)
}
