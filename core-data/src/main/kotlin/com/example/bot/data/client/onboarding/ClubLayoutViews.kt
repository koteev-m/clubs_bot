package com.example.bot.data.client.onboarding

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Called only after the enclosing layout operation has checked current authority. */
internal class ClubLayoutViews(
    private val sql: OnboardingSql,
) {
    fun hall(hall: DbRow): JsonObject {
        val hallId = hall.long("id")
        val plan = sql.row("SELECT content_type,sha256,size_bytes FROM hall_plans WHERE hall_id=?", hallId)
        return buildJsonObject {
            put("id", hallId.toString())
            put("name", hall.getValue("name"))
            put("layoutRevision", hall.getValue("layout_revision"))
            put(
                "tables",
                JsonArray(
                    sql
                        .rows(
                            "SELECT * FROM hall_tables WHERE hall_id=? AND is_active=TRUE ORDER BY table_number,id",
                            hallId,
                        ).map(::table),
                ),
            )
            put(
                "plan",
                plan?.let {
                    buildJsonObject {
                        put("contentType", it.getValue("content_type"))
                        put("sha256", it.getValue("sha256"))
                        put("sizeBytes", it.long("size_bytes"))
                    }
                } ?: JsonNull,
            )
        }
    }

    fun table(table: DbRow): JsonObject =
        buildJsonObject {
            put("id", table.getValue("id"))
            put("hallId", table.getValue("hall_id"))
            put("tableNumber", table.long("table_number"))
            put("label", table.getValue("label"))
            put("capacity", table.long("capacity"))
            put("x", checkNotNull(table["x"]).toDouble())
            put("y", checkNotNull(table["y"]).toDouble())
        }
}
