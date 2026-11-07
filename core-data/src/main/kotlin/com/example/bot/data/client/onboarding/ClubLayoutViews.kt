package com.example.bot.data.client.onboarding

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Called only after the enclosing layout operation has checked current authority. */
internal class ClubLayoutViews(
    private val sql: OnboardingSql,
) {
    fun reservedNumbers(clubId: Long): JsonArray =
        JsonArray(
            sql
                .rows(
                    "SELECT table_number FROM tables WHERE club_id=? UNION " +
                        "SELECT ht.table_number FROM hall_tables ht JOIN halls h ON h.id=ht.hall_id " +
                        "WHERE h.club_id=? ORDER BY table_number",
                    clubId,
                    clubId,
                ).map { JsonPrimitive(it.long("table_number")) },
        )

    fun hall(
        hall: DbRow,
        preview: Boolean = false,
    ): JsonObject {
        val hallId = hall.long("id")
        val clubId = hall.long("club_id")
        val plan = sql.row("SELECT content_type,sha256,size_bytes FROM hall_plans WHERE hall_id=?", hallId)
        val tables =
            sql.rows(
                "SELECT ht.*,bt.min_deposit AS booking_min_deposit,bt.active AS booking_active," +
                    "CASE WHEN bt.table_number=ht.table_number AND bt.capacity=ht.capacity " +
                    "THEN bt.id END AS verified_table_id " +
                    "FROM hall_tables ht " +
                    "LEFT JOIN tables bt ON bt.id=ht.booking_table_id AND bt.club_id=ht.booking_club_id " +
                    "WHERE ht.hall_id=?" + (if (preview) "" else " AND ht.is_active=TRUE") +
                    " ORDER BY ht.table_number,ht.id",
                hallId,
            )
        return buildJsonObject {
            put("id", hallId.toString())
            put("name", hall.getValue("name"))
            put("layoutRevision", hall.getValue("layout_revision"))
            put("tableCount", tables.size)
            put("tables", JsonArray(tables.map { table(it, hall, clubId, preview) }))
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

    private fun table(
        table: DbRow,
        hall: DbRow,
        clubId: Long,
        preview: Boolean,
    ): JsonObject =
        buildJsonObject {
            put("id", table.getValue("id"))
            put("hallId", table.getValue("hall_id"))
            put("tableNumber", table.long("table_number"))
            put("label", table.getValue("label"))
            put("capacity", table.long("capacity"))
            put("bookingLinked", table["booking_table_id"] != null)
            put("placed", table.placed())
            if (!preview) {
                put("bookingDeposit", table["booking_min_deposit"])
                val issue =
                    if (!hall.active() || !table.active()) "LAYOUT_INACTIVE" else table.bookingTableIssue(clubId)
                put("bookingLinkStatus", issue ?: "ACTIVE_LINK")
            }
            put("x", table.coordinate("x"))
            put("y", table.coordinate("y"))
        }
}

internal fun DbRow.placed(): Boolean = active("is_placed")

private fun DbRow.coordinate(name: String) =
    if (placed()) JsonPrimitive(checkNotNull(this[name]).toDouble()) else JsonNull
