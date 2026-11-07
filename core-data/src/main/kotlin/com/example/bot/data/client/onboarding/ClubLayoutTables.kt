package com.example.bot.data.client.onboarding

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.math.BigDecimal

/** The caller owns the authorized client and club locks before table operations. */
internal class ClubLayoutTables(
    private val session: OnboardingSession,
) {
    private val sql get() = session.sql

    fun create(
        clubId: Long,
        hallId: Long,
        operation: OnboardingMutation,
        body: JsonObject,
    ): List<Long> {
        val drafts =
            if (operation == OnboardingMutation.CLUB_TABLE_BATCH_CREATE) {
                batch(body)
            } else {
                body.only(TABLE_FIELDS + HALL_FIELDS)
                listOf(
                    TableDraft(
                        body.tableNumber("tableNumber"),
                        body.text("label", LABEL_LIMIT, required = true),
                        body.capacity(MAX_BOOKING_CAPACITY),
                        body.deposit(),
                        body.position(),
                    ),
                )
            }
        drafts.forEach { requireFreeNumber(clubId, it.number) }
        val zone =
            sql.row("SELECT zone_id FROM hall_zones WHERE hall_id=? ORDER BY sort_order,id LIMIT 1", hallId)
                ?: fail("not_found")
        return drafts.map { insert(clubId, hallId, checkNotNull(zone["zone_id"]), it) }
    }

    fun update(
        clubId: Long,
        hallId: Long,
        body: JsonObject,
    ): Long {
        body.only(TABLE_FIELDS + HALL_FIELDS + listOf("tableId", "zoneId"))
        if (TABLE_FIELDS.none { it in body } && "zoneId" !in body) fail("validation_error")
        val id = body.id("tableId")
        val old =
            sql.row("SELECT * FROM hall_tables WHERE id=? AND hall_id=? AND is_active=TRUE", id, hallId)
                ?: fail("not_found")
        val bookingId = old["booking_table_id"]?.toLong()
        val number = if ("tableNumber" in body) body.tableNumber("tableNumber") else old.long("table_number").toInt()
        val label =
            if ("label" in
                body
            ) {
                body.text("label", LABEL_LIMIT, required = true)
            } else {
                checkNotNull(old["label"])
            }
        val maxCapacity = if (bookingId == null) MAX_LEGACY_CAPACITY else MAX_BOOKING_CAPACITY
        val capacity = if ("capacity" in body) body.capacity(maxCapacity) else old.long("capacity").toInt()
        if (number.toLong() != old.long("table_number")) requireFreeNumber(clubId, number, id, bookingId)
        if (bookingId == null) {
            if ("minDeposit" in body) fail("table_link_required")
            sql.update("UPDATE hall_tables SET table_number=?,capacity=? WHERE id=?", number, capacity, id)
        } else {
            updateBooking(clubId, bookingId, number, capacity, body)
        }
        if ("zoneId" in body) {
            val zone = ClubLayoutZones(session).key(hallId, body.id("zoneId"))
            sql.update("UPDATE hall_tables SET zone_id=? WHERE id=? AND hall_id=?", zone, id, hallId)
        }
        updateLayout(id, hallId, old, label, body)
        return id
    }

    private fun batch(body: JsonObject): List<TableDraft> {
        body.only(HALL_FIELDS + listOf("tableNumbers", "capacity"))
        val values = body["tableNumbers"] as? JsonArray ?: fail("validation_error")
        if (values.size !in 1..MAX_BATCH) fail("validation_error")
        val numbers = values.map { positiveNumber(it as? JsonPrimitive) }
        if (numbers.toSet().size != numbers.size) fail("table_number_conflict")
        val capacity = body.capacity(MAX_BOOKING_CAPACITY)
        return numbers.map { TableDraft(it, "Стол $it", capacity, BigDecimal.ZERO, null) }
    }

    private fun insert(
        clubId: Long,
        hallId: Long,
        zone: String,
        draft: TableDraft,
    ): Long {
        val bookingId =
            sql.insert(
                "INSERT INTO tables(club_id,table_number,capacity,min_deposit,active) VALUES (?,?,?,?,FALSE)",
                clubId,
                draft.number,
                draft.capacity,
                draft.deposit,
            )
        return sql.insert(
            "INSERT INTO hall_tables(hall_id,table_number,label,capacity,zone_id,x,y,is_placed," +
                "booking_table_id,booking_club_id,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
            hallId,
            draft.number,
            draft.label,
            draft.capacity,
            zone,
            draft.position?.x ?: LEGACY_COORDINATE,
            draft.position?.y ?: LEGACY_COORDINATE,
            draft.position != null,
            bookingId,
            clubId,
            session.now,
            session.now,
        )
    }

    private fun updateBooking(
        clubId: Long,
        bookingId: Long,
        number: Int,
        capacity: Int,
        body: JsonObject,
    ) {
        // The FK updates the layout projection when its authoritative booking fields change.
        if ("minDeposit" in body) {
            sql.update(
                "UPDATE tables SET table_number=?,capacity=?,min_deposit=? WHERE id=? AND club_id=?",
                number,
                capacity,
                body.deposit(),
                bookingId,
                clubId,
            )
        } else if ("tableNumber" in body || "capacity" in body) {
            sql.update(
                "UPDATE tables SET table_number=?,capacity=? WHERE id=? AND club_id=?",
                number,
                capacity,
                bookingId,
                clubId,
            )
        }
    }

    private fun updateLayout(
        id: Long,
        hallId: Long,
        old: DbRow,
        label: String,
        body: JsonObject,
    ) {
        if ("x" !in body && "y" !in body) {
            sql.update("UPDATE hall_tables SET label=?,updated_at=? WHERE id=?", label, session.now, id)
            return
        }
        val position = body.position()
        if (position != null &&
            !old.placed() &&
            sql.row("SELECT hall_id FROM hall_plans WHERE hall_id=?", hallId) == null
        ) {
            fail("hall_plan_required")
        }
        sql.update(
            "UPDATE hall_tables SET label=?,x=?,y=?,is_placed=?,updated_at=? WHERE id=?",
            label,
            position?.x ?: checkNotNull(old["x"]).toDouble(),
            position?.y ?: checkNotNull(old["y"]).toDouble(),
            position != null,
            session.now,
            id,
        )
    }

    private fun requireFreeNumber(
        clubId: Long,
        number: Int,
        layoutId: Long = 0,
        bookingId: Long? = null,
    ) {
        val layout =
            sql.row(
                "SELECT ht.id FROM hall_tables ht JOIN halls h ON h.id=ht.hall_id " +
                    "WHERE h.club_id=? AND ht.table_number=? AND ht.id<>? LIMIT 1",
                clubId,
                number,
                layoutId,
            )
        val booking =
            sql.row(
                "SELECT id FROM tables WHERE club_id=? AND table_number=? AND id<>? LIMIT 1",
                clubId,
                number,
                bookingId ?: 0L,
            )
        if (layout != null || booking != null) fail("table_number_conflict")
    }

    private fun JsonObject.tableNumber(key: String): Int = positiveNumber(this[key] as? JsonPrimitive)

    private fun positiveNumber(value: JsonPrimitive?): Int =
        value?.takeUnless { it.isString }?.intOrNull?.takeIf { it > 0 } ?: fail("validation_error")

    private fun JsonObject.capacity(max: Int): Int =
        tableNumber("capacity").takeIf { it <= max } ?: fail("validation_error")

    private fun JsonObject.deposit(): BigDecimal {
        val value = this["minDeposit"] ?: return BigDecimal.ZERO
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail("validation_error")
        if (!DEPOSIT.matches(text)) fail("validation_error")
        return text.toBigDecimal()
    }

    private fun JsonObject.position(): TablePosition? =
        when {
            "x" !in this && "y" !in this -> null
            this["x"] == JsonNull && this["y"] == JsonNull -> null
            else -> TablePosition(coordinate("x"), coordinate("y"))
        }

    private fun JsonObject.coordinate(key: String): Double =
        (this[key] as? JsonPrimitive)
            ?.takeUnless { it.isString }
            ?.doubleOrNull
            ?.takeIf { it.isFinite() && it in 0.0..1.0 }
            ?: fail("validation_error")

    private companion object {
        const val LABEL_LIMIT = 100
        const val MAX_BATCH = 100
        const val MAX_BOOKING_CAPACITY = 50
        const val MAX_LEGACY_CAPACITY = 100
        const val LEGACY_COORDINATE = 0.5
        val DEPOSIT = Regex("[0-9]{1,10}(\\.[0-9]{1,2})?")
        val TABLE_FIELDS = listOf("tableNumber", "label", "capacity", "minDeposit", "x", "y")
        val HALL_FIELDS = listOf("hallId", "expectedVersion")
    }
}

private data class TableDraft(
    val number: Int,
    val label: String,
    val capacity: Int,
    val deposit: BigDecimal,
    val position: TablePosition?,
)

private data class TablePosition(
    val x: Double,
    val y: Double,
)
