package com.example.bot.data.client.onboarding

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** Mutations stay on the caller's authorized, locked transaction and hall. */
internal class ClubLayoutTables(
    private val session: OnboardingSession,
) {
    private val sql get() = session.sql

    fun create(
        hallId: Long,
        body: JsonObject,
    ): Long {
        body.only(TABLE_FIELDS + listOf("hallId", "expectedVersion"))
        val fields = tableFields(body)
        requireTableNumber(hallId, fields.number)
        val zone =
            sql.row("SELECT zone_id FROM hall_zones WHERE hall_id=? ORDER BY sort_order,id LIMIT 1", hallId)
                ?: fail("not_found")
        return sql.insert(
            "INSERT INTO hall_tables(hall_id,table_number,label,capacity,zone_id,x,y,created_at,updated_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?)",
            hallId,
            fields.number,
            fields.label,
            fields.capacity,
            zone.getValue("zone_id"),
            fields.x,
            fields.y,
            session.now,
            session.now,
        )
    }

    fun update(
        hallId: Long,
        body: JsonObject,
    ): Long {
        body.only(TABLE_FIELDS + listOf("hallId", "tableId", "expectedVersion"))
        if (TABLE_FIELDS.none { body.containsKey(it) }) fail("validation_error")
        val tableId = body.id("tableId")
        val existing =
            sql.row("SELECT * FROM hall_tables WHERE id=? AND hall_id=? AND is_active=TRUE", tableId, hallId)
                ?: fail("not_found")
        val fields = tableFields(JsonObject(ClubLayoutViews(sql).table(existing) + body))
        requireTableNumber(hallId, fields.number, tableId)
        sql.update(
            "UPDATE hall_tables SET table_number=?,label=?,capacity=?,x=?,y=?,updated_at=? WHERE id=? AND hall_id=?",
            fields.number,
            fields.label,
            fields.capacity,
            fields.x,
            fields.y,
            session.now,
            tableId,
            hallId,
        )
        return tableId
    }

    private fun requireTableNumber(
        hallId: Long,
        number: Int,
        excluded: Long = 0,
    ) {
        if (sql.row(
                "SELECT id FROM hall_tables WHERE hall_id=? AND table_number=? AND is_active=TRUE AND id<>?",
                hallId,
                number,
                excluded,
            ) !=
            null
        ) {
            fail("table_number_conflict")
        }
    }

    private fun tableFields(body: JsonObject): LayoutTableFields {
        fun integer(key: String): Int =
            (body[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: fail("validation_error")

        fun coordinate(key: String): Double {
            val value =
                (body[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull ?: fail("validation_error")
            if (!value.isFinite() || value !in 0.0..1.0) fail("validation_error")
            return value
        }
        val number = integer("tableNumber")
        val capacity = integer("capacity")
        if (number <= 0 || capacity !in 1..MAX_CAPACITY) fail("validation_error")
        return LayoutTableFields(
            number,
            body.text("label", LABEL_LIMIT, required = true),
            capacity,
            coordinate("x"),
            coordinate("y"),
        )
    }

    private companion object {
        const val LABEL_LIMIT = 100
        const val MAX_CAPACITY = 100
        val TABLE_FIELDS = listOf("tableNumber", "label", "capacity", "x", "y")
    }
}

private data class LayoutTableFields(
    val number: Int,
    val label: String,
    val capacity: Int,
    val x: Double,
    val y: Double,
)
