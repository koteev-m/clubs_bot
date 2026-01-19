package com.example.bot.data.layout

import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object HallsTable : LongIdTable("halls") {
    val clubId = long("club_id")
    val name = text("name")
    val isActive = bool("is_active").default(true)
    val layoutRevision = long("layout_revision").default(0)
    val geometryJson = text("geometry_json")
    val geometryFingerprint = varchar("geometry_fingerprint", 128)
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestamp())
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestamp())
}

object HallZonesTable : LongIdTable("hall_zones") {
    val hallId = long("hall_id")
    val zoneId = varchar("zone_id", 64)
    val name = text("name")
    val tags = text("tags").default("[]")
    val sortOrder = integer("sort_order").default(0)
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestamp())
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestamp())
}

object HallTablesTable : LongIdTable("hall_tables") {
    val hallId = long("hall_id")
    val tableNumber = integer("table_number")
    val label = text("label")
    val capacity = integer("capacity")
    val minimumTier = varchar("minimum_tier", 64).default("standard")
    val minDeposit = long("min_deposit").default(0)
    val zoneId = varchar("zone_id", 64)
    val zone = varchar("zone", 64).nullable()
    val arrivalWindow = varchar("arrival_window", 32).nullable()
    val mysteryEligible = bool("mystery_eligible").default(false)
    val x = double("x").default(0.5)
    val y = double("y").default(0.5)
    val isActive = bool("is_active").default(true)
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestamp())
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestamp())
}

object HallPlansTable : Table("hall_plans") {
    val hallId = long("hall_id")
    val bytes = binary("bytes")
    val contentType = text("content_type")
    val sha256 = text("sha256")
    val sizeBytes = long("size_bytes")
    val createdAt = timestampWithTimeZone("created_at").defaultExpression(CurrentTimestamp())
    val updatedAt = timestampWithTimeZone("updated_at").defaultExpression(CurrentTimestamp())
    override val primaryKey = PrimaryKey(hallId)
}
